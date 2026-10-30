package com.dcontrol.client.feature.screen.data

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase4Limits
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.protocol.ScreenQualities
import com.dcontrol.client.core.websocket.protocol.ScreenStartRequest
import com.dcontrol.client.core.websocket.protocol.ScreenStartResult
import com.dcontrol.client.core.websocket.protocol.ScreenStopPayload
import com.dcontrol.client.core.websocket.typedCommand
import com.dcontrol.client.feature.screen.domain.ScreenController
import javax.inject.Inject
import javax.inject.Singleton

/** Handlers for `screen.start` (command), `rtc.signal` ANSWER and `screen.stop` (events). */
@Singleton
class ScreenCommands @Inject constructor(
    codec: ProtocolCodec,
    private val controller: ScreenController,
) {
    val start: CommandHandler = typedCommand(codec, ScreenStartRequest.serializer(), ScreenStartResult.serializer()) { request ->
        if (request.sessionId.isBlank()) invalid("Missing sessionId")
        if (request.quality !in ScreenQualities.ALL) invalid("Unknown quality ${request.quality}")
        controller.start(request)
    }

    /** The browser's ANSWER. Anything but a well-formed ANSWER for the active session is ignored. */
    val signal: EventHandler = EventHandler { event ->
        val signal = codec.payloadOf(event, RtcSignal.serializer()) ?: return@EventHandler
        if (signal.kind != RtcKinds.ANSWER || signal.sdp.length > Phase4Limits.MAX_SDP) return@EventHandler
        controller.onAnswer(signal)
    }

    val stop: EventHandler = EventHandler { event ->
        val payload = codec.payloadOf(event, ScreenStopPayload.serializer()) ?: return@EventHandler
        controller.onStop(payload.sessionId)
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)
}
