package com.dcontrol.client.feature.files.transfer

import com.dcontrol.client.core.database.TransferEntity
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.TransferFinishedPayload
import com.dcontrol.client.core.websocket.protocol.TransferProgressPayload
import com.dcontrol.client.core.websocket.protocol.TransferReceiveRequest
import com.dcontrol.client.core.websocket.protocol.TransferSendRequest
import com.dcontrol.client.core.websocket.protocol.TransferSendResult
import com.dcontrol.client.feature.files.data.FileSystem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Persists transfers the phone still has to finish (Room in the app, in-memory in tests). */
interface TransferStore {
    suspend fun save(transfer: TransferEntity)
    suspend fun find(transferId: String): TransferEntity?
    suspend fun all(): List<TransferEntity>
    suspend fun delete(transferId: String)
}

/** Shows transfer progress in the "Transfers" notification channel. */
interface TransferNotifier {
    fun progress(transferId: String, fileName: String, bytes: Long, size: Long, upload: Boolean)
    fun finished(transferId: String, fileName: String, ok: Boolean, upload: Boolean)
    fun clear(transferId: String)
}

/** Makes a received file visible to other apps (MediaScanner on Android). */
fun interface MediaIndexer {
    fun index(file: File)
}

/**
 * Moves file contents between the phone and the controller's spool over HTTP (transfers-v1.md).
 *
 * - `transfer.send` (FROM_DEVICE): uploads the file in chunks, resuming from the controller's `receivedBytes`.
 * - `transfer.receive` (TO_DEVICE): downloads into `<name>.<transferId>.part` with Range resume, verifies the
 *   SHA-256, renames to a free name and reports `transfer.finished`.
 *
 * Both commands are idempotent by transferId: the controller re-sends them after every `hello.ack`, and the
 * engine also resumes its stored transfers whenever the connection comes back online. A network error just
 * pauses a transfer until then.
 */
class TransferEngine(
    private val api: TransferApi,
    private val store: TransferStore,
    private val fileSystem: FileSystem,
    private val events: DeviceEventSender,
    private val notifier: TransferNotifier,
    private val indexer: MediaIndexer,
    private val scope: CoroutineScope,
    private val chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Starts resuming stored transfers whenever the connection comes online. */
    fun start() {
        scope.launch {
            events.connectionState.collect { state ->
                if (state is ConnectionState.Online) resumeAll()
            }
        }
    }

    suspend fun send(request: TransferSendRequest): TransferSendResult {
        validateId(request.transferId)
        val existing = store.find(request.transferId)
        val file = fileSystem.resolveFile(existing?.path ?: request.path)
        val transfer = existing ?: TransferEntity(
            transferId = request.transferId,
            direction = TransferEntity.FROM_DEVICE,
            path = file.path,
            fileName = file.name,
            size = file.length(),
            sha256 = null,
            createdAtMillis = System.currentTimeMillis(),
        ).also { store.save(it) }
        launch(transfer)
        return TransferSendResult(
            fileName = transfer.fileName,
            size = transfer.size,
            modified = Instant.ofEpochMilli(file.lastModified()).toString(),
        )
    }

    suspend fun receive(request: TransferReceiveRequest) {
        validateId(request.transferId)
        FileSystem.validateName(request.fileName)
        if (request.size < 0) throw CommandException(ErrorCodes.VALIDATION, "size must be >= 0")
        if (!SHA256_HEX.matches(request.sha256)) throw CommandException(ErrorCodes.VALIDATION, "sha256 must be 64 hex chars")
        val dir = fileSystem.resolveDirectory(request.targetDir)
        val existing = store.find(request.transferId)
        if (existing == null) {
            val alreadyHave = partFile(dir, request.fileName, request.transferId).length()
            if (dir.usableSpace + alreadyHave < request.size) {
                throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "Not enough free space in ${dir.path}")
            }
        }
        val transfer = existing ?: TransferEntity(
            transferId = request.transferId,
            direction = TransferEntity.TO_DEVICE,
            path = dir.path,
            fileName = request.fileName,
            size = request.size,
            sha256 = request.sha256.lowercase(),
            createdAtMillis = System.currentTimeMillis(),
        ).also { store.save(it) }
        launch(transfer)
    }

    /** `transfer.cancel`: stops the transfer and removes partial data. */
    suspend fun cancel(transferId: String) {
        jobs.remove(transferId)?.cancelAndJoin()
        val transfer = store.find(transferId) ?: return
        if (transfer.direction == TransferEntity.TO_DEVICE) partFile(File(transfer.path), transfer.fileName, transferId).delete()
        store.delete(transferId)
        notifier.clear(transferId)
    }

    suspend fun resumeAll() {
        store.all().forEach { launch(it) }
    }

    /** Waits for every running transfer job (tests). */
    internal suspend fun awaitIdle() {
        while (jobs.values.any { it.isActive }) jobs.values.toList().joinAll()
    }

    private fun launch(transfer: TransferEntity) {
        jobs.compute(transfer.transferId) { _, running ->
            running?.takeIf { it.isActive } ?: scope.launch { run(transfer) }
        }
    }

    private suspend fun run(transfer: TransferEntity) {
        try {
            if (transfer.direction == TransferEntity.FROM_DEVICE) upload(transfer) else download(transfer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TransferGoneException) {
            // Cancelled, expired or already finished on the controller.
            if (transfer.direction == TransferEntity.TO_DEVICE) partFile(File(transfer.path), transfer.fileName, transfer.transferId).delete()
            store.delete(transfer.transferId)
            notifier.clear(transfer.transferId)
        } catch (_: IOException) {
            // Paused: resumed when the connection is back online or the controller re-sends the command.
        } finally {
            jobs.remove(transfer.transferId, coroutineContext[Job])
        }
    }

    private suspend fun upload(transfer: TransferEntity) {
        val file = File(transfer.path)
        if (!file.isFile || file.length() != transfer.size) {
            fail(transfer, ErrorCodes.NOT_FOUND, "${transfer.fileName} was changed or removed")
            return
        }
        var view = api.status(transfer.transferId)
        if (view.state == TransferView.RECEIVING && transfer.size == 0L) {
            view = api.uploadChunk(transfer.transferId, file, 0, 0, 0)
        }
        while (view.state == TransferView.RECEIVING && view.receivedBytes < transfer.size) {
            coroutineContext.ensureActive()
            val start = view.receivedBytes
            val length = minOf(chunkBytes, transfer.size - start)
            view = try {
                api.uploadChunk(transfer.transferId, file, start, length, transfer.size)
            } catch (conflict: OffsetConflictException) {
                view.copy(receivedBytes = conflict.receivedBytes)
            }
            notifier.progress(transfer.transferId, transfer.fileName, view.receivedBytes, transfer.size, upload = true)
        }
        // The controller stages the file itself once the last chunk arrives; nothing to report on success.
        store.delete(transfer.transferId)
        val staged = view.state == TransferView.READY || view.state == TransferView.DELIVERED
        notifier.finished(transfer.transferId, transfer.fileName, ok = staged, upload = true)
    }

    private suspend fun download(transfer: TransferEntity) {
        val completedName = transfer.completedName
        if (completedName != null) {
            reportDelivered(transfer, completedName)
            return
        }
        val dir = File(transfer.path)
        val part = partFile(dir, transfer.fileName, transfer.transferId)
        val view = api.status(transfer.transferId)
        if (view.state != TransferView.READY) throw TransferGoneException("Transfer is ${view.state}")
        var offset = if (part.isFile) part.length() else 0L
        if (offset > transfer.size) {
            part.delete()
            offset = 0
        }
        if (offset < transfer.size) offset = fetch(transfer, part, offset)
        if (offset != transfer.size || sha256(part) != transfer.sha256) {
            part.delete()
            fail(transfer, ErrorCodes.VALIDATION, "Checksum mismatch for ${transfer.fileName}")
            return
        }
        val target = freeTarget(dir, transfer.fileName)
        if (!part.renameTo(target)) {
            fail(transfer, ErrorCodes.INTERNAL, "Could not save ${transfer.fileName}")
            return
        }
        indexer.index(target)
        val completed = transfer.copy(completedName = target.name)
        store.save(completed)
        reportDelivered(completed, target.name)
    }

    /**
     * Keeps the row until `transfer.finished` actually went out, so a re-sent `transfer.receive` after a
     * reconnect reports the delivery again instead of downloading a second copy.
     */
    private suspend fun reportDelivered(transfer: TransferEntity, savedName: String) {
        val sent = events.send(
            MessageTypes.TRANSFER_FINISHED,
            TransferFinishedPayload.serializer(),
            TransferFinishedPayload(transfer.transferId, ok = true),
        )
        if (!sent) return
        store.delete(transfer.transferId)
        notifier.finished(transfer.transferId, savedName, ok = true, upload = false)
    }

    /** Streams the remaining bytes into [part]; returns the new length. */
    private suspend fun fetch(transfer: TransferEntity, part: File, from: Long): Long {
        var offset = from
        api.download(transfer.transferId, from).use { body ->
            val append = body.partial && from > 0
            if (!append) offset = 0
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var lastReport = nowMillis()
                while (true) {
                    coroutineContext.ensureActive()
                    val read = body.stream.read(buffer)
                    if (read < 0) break
                    if (offset + read > transfer.size) throw IOException("Controller sent more than ${transfer.size} bytes")
                    out.write(buffer, 0, read)
                    offset += read
                    val now = nowMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        events.send(
                            MessageTypes.TRANSFER_PROGRESS,
                            TransferProgressPayload.serializer(),
                            TransferProgressPayload(transfer.transferId, offset),
                        )
                        notifier.progress(transfer.transferId, transfer.fileName, offset, transfer.size, upload = false)
                    }
                }
            }
        }
        return offset
    }

    private suspend fun fail(transfer: TransferEntity, code: String, message: String) {
        events.send(
            MessageTypes.TRANSFER_FINISHED,
            TransferFinishedPayload.serializer(),
            TransferFinishedPayload(transfer.transferId, ok = false, errorCode = code, message = message.take(MAX_MESSAGE)),
        )
        store.delete(transfer.transferId)
        notifier.finished(transfer.transferId, transfer.fileName, ok = false, upload = transfer.direction == TransferEntity.FROM_DEVICE)
    }

    private fun validateId(transferId: String) {
        if (transferId.isBlank() || transferId.length > MAX_ID || !ID_CHARS.matches(transferId)) {
            throw CommandException(ErrorCodes.VALIDATION, "Invalid transferId")
        }
    }

    companion object {
        const val DEFAULT_CHUNK_BYTES = 8L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val MAX_MESSAGE = 200
        private const val MAX_ID = 64
        private val ID_CHARS = Regex("[A-Za-z0-9-]+")
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

        fun partFile(dir: File, fileName: String, transferId: String) = File(dir, "$fileName.$transferId.part")

        /** `name.ext`, then `name (1).ext`, `name (2).ext`, ... */
        fun freeTarget(dir: File, fileName: String): File {
            val first = File(dir, fileName)
            if (!first.exists()) return first
            val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
            val base = fileName.substring(0, dot)
            val extension = fileName.substring(dot)
            return generateSequence(1) { it + 1 }
                .map { File(dir, "$base ($it)$extension") }
                .first { !it.exists() }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
