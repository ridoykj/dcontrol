package com.dcontrol.client.feature.mic.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MicCaptureRequest
import com.dcontrol.client.core.websocket.protocol.MicCaptureResult
import com.dcontrol.client.core.websocket.protocol.MicStartRequest
import com.dcontrol.client.core.websocket.protocol.MicStartResult
import com.dcontrol.client.core.websocket.protocol.MicStopPayload
import com.dcontrol.client.core.websocket.protocol.Phase4Limits
import com.dcontrol.client.core.websocket.protocol.Phase6Limits
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.typedCommand
import com.dcontrol.client.feature.mic.domain.MicController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handlers for `mic.start` and `mic.capture` (commands), and the `mic.signal` ANSWER and `mic.stop` (events).
 */
@Singleton
class MicCommands @Inject constructor(
    codec: ProtocolCodec,
    private val controller: MicController,
) {
    val start: CommandHandler = typedCommand(codec, MicStartRequest.serializer(), MicStartResult.serializer()) { request ->
        if (request.sessionId.isBlank()) invalid("Missing sessionId")
        if (request.viewer.length > Phase4Limits.MAX_VIEWER) invalid("Viewer name is too long")
        if (request.iceServers.size > Phase4Limits.MAX_ICE_SERVERS) invalid("Too many ICE servers")
        controller.start(request)
    }

    /** The browser's ANSWER. Anything but a well-formed ANSWER for the active session is ignored. */
    val signal: EventHandler = EventHandler { event ->
        val signal = codec.payloadOf(event, RtcSignal.serializer()) ?: return@EventHandler
        if (signal.kind != RtcKinds.ANSWER || signal.sdp.length > Phase4Limits.MAX_SDP) return@EventHandler
        controller.onAnswer(signal)
    }

    val stop: EventHandler = EventHandler { event ->
        val payload = codec.payloadOf(event, MicStopPayload.serializer()) ?: return@EventHandler
        controller.onStop(payload.sessionId)
    }

    val capture: CommandHandler = typedCommand(codec, MicCaptureRequest.serializer(), MicCaptureResult.serializer()) { request ->
        if (!MEDIA_ID.matches(request.mediaId)) invalid("Invalid mediaId")
        if (request.durationMs !in MIN_CLIP_MS..Phase6Limits.MAX_CLIP_MS) {
            invalid("durationMs must be $MIN_CLIP_MS..${Phase6Limits.MAX_CLIP_MS}")
        }
        controller.capture(request)
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)

    private companion object {
        const val MIN_CLIP_MS = 1_000
        val MEDIA_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
