package com.dcontrol.client.feature.files.data

import com.dcontrol.client.core.storage.StorageAccess
import com.dcontrol.client.core.storage.StorageRoots
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FileEntry
import com.dcontrol.client.core.websocket.protocol.FileEntryResult
import com.dcontrol.client.core.websocket.protocol.FilesDeleteRequest
import com.dcontrol.client.core.websocket.protocol.FilesDeleteResult
import com.dcontrol.client.core.websocket.protocol.FilesListRequest
import com.dcontrol.client.core.websocket.protocol.FilesListResult
import com.dcontrol.client.core.websocket.protocol.FilesMkdirRequest
import com.dcontrol.client.core.websocket.protocol.FilesRenameRequest
import com.dcontrol.client.core.websocket.protocol.FilesRootsResult
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.TransferIdPayload
import com.dcontrol.client.core.websocket.protocol.TransferReceiveRequest
import com.dcontrol.client.core.websocket.protocol.TransferSendRequest
import com.dcontrol.client.core.websocket.protocol.TransferSendResult
import com.dcontrol.client.core.websocket.typedCommand
import com.dcontrol.client.feature.files.transfer.TransferEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/** Handlers for `files.*`, `transfer.send`, `transfer.receive` and the `transfer.cancel` event. */
class FilesCommands(
    private val codec: ProtocolCodec,
    private val fileSystem: FileSystem,
    private val storageRoots: StorageRoots,
    private val access: StorageAccess,
    private val engine: TransferEngine,
) {
    val roots: CommandHandler = command(EmptyPayload.serializer(), FilesRootsResult.serializer()) {
        FilesRootsResult(storageRoots.volumes())
    }

    val list: CommandHandler = command(FilesListRequest.serializer(), FilesListResult.serializer()) {
        fileSystem.list(it.path, it.offset, LIST_BUDGET_BYTES, ::encodedSize)
    }

    val mkdir: CommandHandler = command(FilesMkdirRequest.serializer(), FileEntryResult.serializer()) {
        FileEntryResult(fileSystem.mkdir(it.path, it.name))
    }

    val rename: CommandHandler = command(FilesRenameRequest.serializer(), FileEntryResult.serializer()) {
        FileEntryResult(fileSystem.rename(it.path, it.newName))
    }

    val delete: CommandHandler = command(FilesDeleteRequest.serializer(), FilesDeleteResult.serializer()) {
        fileSystem.delete(it.paths)
    }

    val transferSend: CommandHandler = command(TransferSendRequest.serializer(), TransferSendResult.serializer()) {
        engine.send(it)
    }

    val transferReceive: CommandHandler = command(TransferReceiveRequest.serializer(), EmptyPayload.serializer()) {
        engine.receive(it)
        EmptyPayload()
    }

    val transferCancel = EventHandler { event ->
        val payload = codec.payloadOf(event, TransferIdPayload.serializer()) ?: return@EventHandler
        engine.cancel(payload.transferId)
    }

    private fun encodedSize(entry: FileEntry): Int =
        codec.json.encodeToString(FileEntry.serializer(), entry).encodeToByteArray().size + 1

    private fun <Req, Res> command(
        request: KSerializer<Req>,
        result: KSerializer<Res>,
        block: suspend (Req) -> Res,
    ): CommandHandler = typedCommand(codec, request, result) { input ->
        if (!access.isGranted()) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "All files access is not granted on the phone")
        }
        withContext(Dispatchers.IO) { block(input) }
    }

    companion object {
        /** Keeps a list result well under the 64 KiB frame limit (ws-v1.md). */
        const val LIST_BUDGET_BYTES = 48 * 1024
    }
}
