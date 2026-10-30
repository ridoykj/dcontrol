package com.dcontrol.client.feature.camera.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.CameraCaptureRequest
import com.dcontrol.client.core.websocket.protocol.CameraCaptureResult
import com.dcontrol.client.core.websocket.protocol.CameraFacings
import com.dcontrol.client.core.websocket.protocol.CameraStartRequest
import com.dcontrol.client.core.websocket.protocol.CameraStartResult
import com.dcontrol.client.core.websocket.protocol.CameraStopPayload
import com.dcontrol.client.core.websocket.protocol.CameraSwitchRequest
import com.dcontrol.client.core.websocket.protocol.CameraSwitchResult
import com.dcontrol.client.core.websocket.protocol.CaptureKinds
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase4Limits
import com.dcontrol.client.core.websocket.protocol.Phase5Limits
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.typedCommand
import com.dcontrol.client.feature.camera.domain.CameraController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handlers for `camera.start`, `camera.switch` and `camera.capture` (commands), and the `camera.signal` ANSWER
 * and `camera.stop` (events).
 */
@Singleton
class CameraCommands @Inject constructor(
    codec: ProtocolCodec,
    private val controller: CameraController,
) {
    val start: CommandHandler = typedCommand(codec, CameraStartRequest.serializer(), CameraStartResult.serializer()) { request ->
        if (request.sessionId.isBlank()) invalid("Missing sessionId")
        if (request.viewer.length > Phase4Limits.MAX_VIEWER) invalid("Viewer name is too long")
        if (request.facing !in CameraFacings.ALL) invalid("Unknown facing ${request.facing}")
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
        val payload = codec.payloadOf(event, CameraStopPayload.serializer()) ?: return@EventHandler
        controller.onStop(payload.sessionId)
    }

    val switchCamera: CommandHandler = typedCommand(codec, CameraSwitchRequest.serializer(), CameraSwitchResult.serializer()) { request ->
        if (request.facing !in CameraFacings.ALL) invalid("Unknown facing ${request.facing}")
        controller.switch(request)
    }

    val capture: CommandHandler = typedCommand(codec, CameraCaptureRequest.serializer(), CameraCaptureResult.serializer()) { request ->
        if (!MEDIA_ID.matches(request.mediaId)) invalid("Invalid mediaId")
        if (request.kind !in CaptureKinds.ALL) invalid("Unknown kind ${request.kind}")
        if (request.kind == CaptureKinds.VIDEO) {
            val duration = request.durationMs ?: invalid("A VIDEO capture needs durationMs")
            if (duration !in 1..Phase5Limits.MAX_CLIP_MS) invalid("durationMs must be 1..${Phase5Limits.MAX_CLIP_MS}")
        }
        controller.capture(request)
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)

    private companion object {
        val MEDIA_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
