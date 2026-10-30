package com.dcontrol.client.feature.screen

import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.screen.domain.ScreenConsentFlow
import com.dcontrol.client.feature.screen.rtc.RtcConfig
import com.dcontrol.client.feature.screen.rtc.RtcEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/** Records the events a component sends to the controller. */
class RecordingEvents(private val codec: ProtocolCodec = ProtocolCodec()) : DeviceEventSender {
    val sent = mutableListOf<Pair<String, JsonObject>>()

    override val connectionState: Flow<ConnectionState> = emptyFlow()

    override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean {
        sent += type to codec.toPayload(serializer, payload)
        return true
    }

    fun types(): List<String> = sent.map { it.first }
    fun first(type: String): JsonObject = sent.first { it.first == type }.second
    fun count(type: String): Int = sent.count { it.first == type }
}

/** A fake [RtcEngine] that returns a canned offer and records the session's calls. */
class FakeRtcEngine(private val offer: String = "offer-sdp") : RtcEngine {
    @Volatile var started = false
    @Volatile var answered: String? = null
    @Volatile var closed = false
    val controls = mutableListOf<String>()

    override suspend fun start(): String {
        started = true
        return offer
    }

    override suspend fun acceptAnswer(sdp: String) {
        answered = sdp
    }

    override fun sendControl(text: String) {
        controls += text
    }

    override fun close() {
        closed = true
    }
}

/** Records which consent path the controller chose. */
class FakeConsentFlow : ScreenConsentFlow {
    var launched: String? = null
    var requested: Pair<String, String>? = null

    override fun launchConsent(sessionId: String) {
        launched = sessionId
    }

    override fun postRequest(sessionId: String, viewer: String) {
        requested = sessionId to viewer
    }

    @Volatile
    var captureStops = 0

    override fun stopCapture() {
        captureStops++
    }
}

/** Captures the config and listener passed to the factory. */
class FakeEngineHolder(val engine: FakeRtcEngine = FakeRtcEngine()) {
    var config: RtcConfig? = null
    var listener: RtcEngine.Listener? = null

    fun factory() = com.dcontrol.client.feature.screen.rtc.RtcEngineFactory { config, listener ->
        this.config = config
        this.listener = listener
        engine
    }
}
