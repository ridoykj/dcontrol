package com.dcontrol.client.core.websocket

import com.dcontrol.client.core.websocket.protocol.Battery
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.HelloPayload
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/** Supplies what the device reports about itself in hello and heartbeat messages. */
interface DeviceInfoSource {
    fun hello(): HelloPayload
    fun battery(): Battery
}

/**
 * Executes one command type sent by the controller. Return the result payload, or throw
 * [CommandException] to answer with an error.
 */
fun interface CommandHandler {
    suspend fun handle(command: Envelope): JsonObject?
}

/** Handles a controller event (a message without requestId, e.g. `transfer.cancel`). */
fun interface EventHandler {
    suspend fun handle(event: Envelope)
}

/**
 * Answers a command with an error. [permission] (one of [com.dcontrol.client.core.websocket.protocol.Permissions])
 * is sent as `error.details.permission` and only together with `PERMISSION_REQUIRED`.
 */
class CommandException(
    val code: String,
    override val message: String,
    val permission: String? = null,
) : RuntimeException(message)

/** Sends phone -> controller events; implemented on top of [DeviceSocket]. */
interface DeviceEventSender {
    val connectionState: Flow<ConnectionState>

    /** Returns false when the phone is not online; events are best-effort. */
    fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean
}

/**
 * A [CommandHandler] that decodes the payload with [request] (a missing payload decodes as `{}`), answers
 * `VALIDATION` when it doesn't parse, and encodes the returned value with [result].
 */
fun <Req, Res> typedCommand(
    codec: ProtocolCodec,
    request: KSerializer<Req>,
    result: KSerializer<Res>,
    block: suspend (Req) -> Res,
): CommandHandler = CommandHandler { command ->
    val input = decodeOrFail(codec, command, request)
    codec.toPayload(result, block(input))
}

private fun <T> decodeOrFail(codec: ProtocolCodec, command: Envelope, serializer: KSerializer<T>): T {
    val payload = command.payload ?: JsonObject(emptyMap())
    return try {
        codec.json.decodeFromJsonElement(serializer, payload)
    } catch (_: SerializationException) {
        throw CommandException(ErrorCodes.VALIDATION, "Invalid payload for ${command.type}")
    } catch (_: IllegalArgumentException) {
        throw CommandException(ErrorCodes.VALIDATION, "Invalid payload for ${command.type}")
    }
}
