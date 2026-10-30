package com.dcontrol.client.feature.screen.domain

import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.protocol.ScreenReasons
import com.dcontrol.client.core.websocket.protocol.ScreenStatePayload
import com.dcontrol.client.core.websocket.protocol.ScreenStates
import com.dcontrol.client.feature.screen.control.ControlRouter
import com.dcontrol.client.feature.screen.control.PhoneControl
import com.dcontrol.client.feature.screen.rtc.RtcConfig
import com.dcontrol.client.feature.screen.rtc.RtcEngine
import com.dcontrol.client.feature.screen.rtc.RtcEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The mirrored frame: its pixel size and rotation, reported to the browser so it can place pointer events. */
data class ScreenFrame(val width: Int, val height: Int, val rotation: Int)

/**
 * Drives one mirroring session: it owns the [RtcEngine], sends `screen.state` and the `rtc.signal` OFFER, and
 * routes the browser's control messages to the Accessibility service. It sits behind the [RtcEngineFactory]
 * seam so it can be tested with a fake engine, no native libraries required.
 */
class ScreenSession(
    val sessionId: String,
    private val config: RtcConfig,
    private val factory: RtcEngineFactory,
    private val events: DeviceEventSender,
    private val bridge: AccessibilityBridge,
    private val frame: () -> ScreenFrame,
    private val scope: CoroutineScope,
    /** Called once when the session ends for any reason, so the capture service and its indicator stop too. */
    private val onEnded: () -> Unit = {},
) : RtcEngine.Listener {

    private var engine: RtcEngine? = null
    private var availabilityJob: Job? = null
    private var ended = false

    private val router = ControlRouter(
        actions = { if (config.control) bridge.actions else null },
        onExcessiveDrops = { end(ScreenReasons.STOPPED, silent = false) },
    )

    /** Consent was granted: set up the connection and send the offer. */
    fun begin() {
        sendState(ScreenStates.STARTING)
        scope.launch {
            val created = factory.create(config, this@ScreenSession).also { engine = it }
            val offer = try {
                created.start()
            } catch (_: Exception) {
                end(ScreenReasons.ERROR, silent = false)
                return@launch
            }
            events.send(MessageTypes.RTC_SIGNAL, RtcSignal.serializer(), RtcSignal(sessionId, RtcKinds.OFFER, offer))
        }
    }

    fun acceptAnswer(sdp: String) {
        scope.launch { engine?.acceptAnswer(sdp) }
    }

    /** The browser denied consent, or it timed out before Start was tapped. */
    fun denied() = end(ScreenReasons.DENIED, silent = false)

    /** The user tapped Stop on the phone's ongoing notification. */
    fun stopOnPhone() = end(ScreenReasons.STOPPED_ON_PHONE, silent = false)

    /** The controller asked the phone to stop (`screen.stop`); the phone ends silently. */
    fun stopSilently() = end(reason = null, silent = true)

    override fun onLive() {
        if (ended) return
        sendState(ScreenStates.LIVE)
        val f = frame()
        engine?.sendControl(PhoneControl.size(f.width, f.height, f.rotation))
        engine?.sendControl(PhoneControl.control(currentControl()))
        observeAvailability()
    }

    override fun onFailed() = end(ScreenReasons.ERROR, silent = false)

    override fun onControl(text: String) = router.handle(text)

    private fun observeAvailability() {
        availabilityJob?.cancel()
        availabilityJob = scope.launch {
            // Skip the current value; react only to later on/off toggles of the service.
            bridge.available.drop(1).collect {
                engine?.sendControl(PhoneControl.control(currentControl()))
                sendState(ScreenStates.LIVE)
            }
        }
    }

    private fun end(reason: String?, silent: Boolean) {
        if (ended) return
        ended = true
        availabilityJob?.cancel()
        engine?.close()
        engine = null
        if (!silent) sendState(ScreenStates.ENDED, reason)
        onEnded()
    }

    private fun currentControl(): Boolean = config.control && bridge.isBound

    private fun sendState(state: String, reason: String? = null) {
        events.send(
            MessageTypes.SCREEN_STATE,
            ScreenStatePayload.serializer(),
            ScreenStatePayload(sessionId = sessionId, state = state, reason = reason, control = currentControl()),
        )
    }
}
