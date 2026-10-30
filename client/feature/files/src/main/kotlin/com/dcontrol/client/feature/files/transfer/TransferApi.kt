package com.dcontrol.client.feature.files.transfer

import com.dcontrol.client.core.network.device.DeviceHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/** The `Transfer` resource of transfers-v1.md (fields the phone uses). */
@Serializable
data class TransferView(
    val id: String,
    val direction: String,
    val state: String,
    val fileName: String,
    val size: Long,
    val receivedBytes: Long,
    val sha256: String? = null,
) {
    companion object {
        const val RECEIVING = "RECEIVING"
        const val READY = "READY"
        const val DELIVERED = "DELIVERED"
    }
}

/** The transfer no longer exists for this device or is in a state that doesn't allow the call. */
class TransferGoneException(message: String) : IOException(message)

/** A chunk didn't start at the controller's `receivedBytes`. */
class OffsetConflictException(val receivedBytes: Long) : IOException("Controller has $receivedBytes bytes")

/** A body being read from the controller; [partial] is true for a `206` answer to a Range request. */
class DownloadBody(val partial: Boolean, val stream: InputStream, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
}

/** `/api/device-api/transfers/...` calls (transfers-v1.md, "Phone endpoints"). */
class TransferApi(
    private val http: DeviceHttpClient,
    private val json: Json,
) {

    suspend fun status(transferId: String): TransferView =
        http.execute(path(transferId)).use { response -> parse(response) }

    /** Uploads bytes `[start, start + length)` of [source]; the declared total is [size]. */
    suspend fun uploadChunk(transferId: String, source: File, start: Long, length: Long, size: Long): TransferView {
        val range = if (size == 0L) "bytes */0" else "bytes $start-${start + length - 1}/$size"
        val body = FileRangeBody(source, start, length)
        return http.execute("${path(transferId)}/content") {
            put(body)
            header("Content-Range", range)
        }.use { response -> parse(response) }
    }

    /** Opens the staged file, from [offset] on when it is > 0. */
    suspend fun download(transferId: String, offset: Long): DownloadBody {
        val response = http.execute("${path(transferId)}/content") {
            if (offset > 0) header("Range", "bytes=$offset-")
        }
        if (response.code != HTTP_OK && response.code != HTTP_PARTIAL) {
            response.use { fail(it.code, it.body.string()) }
        }
        return DownloadBody(response.code == HTTP_PARTIAL, response.body.byteStream()) { response.close() }
    }

    private fun parse(response: okhttp3.Response): TransferView {
        val text = response.body.string()
        if (!response.isSuccessful) fail(response.code, text)
        return json.decodeFromString(TransferView.serializer(), text)
    }

    private fun fail(status: Int, body: String): Nothing {
        val problem = runCatching { json.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
        val code = problem?.get("code")?.jsonPrimitive?.contentOrNull
        when {
            status == HTTP_CONFLICT && code == "CONFLICT" -> {
                val received = problem["receivedBytes"]?.jsonPrimitive?.longOrNull
                    ?: throw IOException("Offset conflict without receivedBytes")
                throw OffsetConflictException(received)
            }
            status == HTTP_NOT_FOUND || status == HTTP_CONFLICT || status == HTTP_GONE ->
                throw TransferGoneException("Transfer unavailable ($status ${code.orEmpty()})")
            else -> throw IOException("Transfer call failed with $status ${code.orEmpty()}")
        }
    }

    private fun path(transferId: String) = "/api/device-api/transfers/$transferId"

    private class FileRangeBody(private val file: File, private val start: Long, private val length: Long) : RequestBody() {
        override fun contentType() = OCTET_STREAM

        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val stream = object : InputStream() {
                    private var remaining = length
                    override fun read(): Int {
                        if (remaining <= 0) return -1
                        return raf.read().also { if (it >= 0) remaining-- }
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val read = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
                        if (read > 0) remaining -= read
                        return read
                    }
                }
                sink.writeAll(stream.source())
            }
        }
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_GONE = 410
    }
}
