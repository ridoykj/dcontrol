package com.dcontrol.client.feature.screen.domain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.protocol.ScreenPreset
import com.dcontrol.client.core.websocket.protocol.ScreenReasons
import com.dcontrol.client.core.websocket.protocol.ScreenStartRequest
import com.dcontrol.client.core.websocket.protocol.ScreenStartResult
import com.dcontrol.client.core.websocket.protocol.ScreenStatePayload
import com.dcontrol.client.core.websocket.protocol.ScreenStates
import com.dcontrol.client.feature.screen.rtc.RtcConfig
import com.dcontrol.client.feature.screen.rtc.RtcEngineFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The request that is waiting for the user to tap Start on the consent screen. */
data class PendingScreen(
    val sessionId: String,
    val viewer: String,
    val audio: Boolean,
    val control: Boolean,
    val quality: String,
    val iceServers: List<com.dcontrol.client.core.websocket.protocol.IceServer>,
)

/**
 * Coordinates a single mirroring session across the command handler, the consent screen and the capture
 * service. At most one session (or pending request) exists at a time; a new `screen.start` replaces the old.
 */
@Singleton
class ScreenController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: AccessibilityBridge,
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
    private val consent: ScreenConsentFlow,
    private val engineFactory: RtcEngineFactory,
    private val scope: ScreenScope,
) {
    @Volatile
    private var pending: PendingScreen? = null

    @Volatile
    private var session: ScreenSession? = null

    val pendingSession: PendingScreen? get() = pending

    /** Handles `screen.start`: records the request, opens consent, and reports what the phone can provide. */
    @Synchronized
    fun start(request: ScreenStartRequest): ScreenStartResult {
        // A new start replaces any running or pending session; the controller tells the old browser REPLACED.
        session?.stopSilently()
        session = null
        pending = null

        val audio = request.audio && permissions.isGranted(Manifest.permission.RECORD_AUDIO)
        val control = request.control && bridge.isBound
        pending = PendingScreen(
            sessionId = request.sessionId,
            viewer = request.viewer,
            audio = audio,
            control = control,
            quality = request.quality,
            iceServers = request.iceServers,
        )

        if (bridge.isBound) {
            consent.launchConsent(request.sessionId)
        } else {
            consent.postRequest(request.sessionId, request.viewer)
        }
        // The screen.start result must reach the browser before the first screen.state. The result is sent
        // when this handler returns, so emit AWAITING_CONSENT off-thread after a short delay to keep that order.
        scope.value.launch {
            delay(AWAITING_CONSENT_DELAY_MS)
            synchronized(this@ScreenController) {
                if (pending?.sessionId == request.sessionId) {
                    sendState(request.sessionId, ScreenStates.AWAITING_CONSENT)
                }
            }
        }
        return ScreenStartResult(audio = audio, control = control)
    }

    /** The capture service has consent and is foregrounded: build the session and send the offer. */
    @Synchronized
    fun onProjectionResult(resultData: Intent) {
        val request = pending ?: return
        pending = null
        val config = RtcConfig(
            sessionId = request.sessionId,
            resultData = resultData,
            preset = ScreenPreset.of(request.quality),
            audio = request.audio,
            control = request.control,
            iceServers = request.iceServers,
        )
        val built = ScreenSession(
            sessionId = request.sessionId,
            config = config,
            factory = engineFactory,
            events = events,
            bridge = bridge,
            frame = ::currentFrame,
            scope = scope.value,
            onEnded = consent::stopCapture,
        )
        session = built
        built.begin()
    }

    @Synchronized
    fun onConsentDenied() {
        val request = pending ?: return
        pending = null
        sendState(request.sessionId, ScreenStates.ENDED, ScreenReasons.DENIED)
    }

    /** The browser's ANSWER (`rtc.signal`). */
    @Synchronized
    fun onAnswer(signal: RtcSignal) {
        if (signal.sessionId == currentId()) session?.acceptAnswer(signal.sdp)
    }

    /** `screen.stop` from the controller: tear down silently. */
    @Synchronized
    fun onStop(sessionId: String) {
        if (sessionId == currentId()) {
            session?.stopSilently()
            session = null
            pending = null
        }
    }

    /** The user tapped Stop on the ongoing notification. */
    @Synchronized
    fun stopFromNotification() {
        session?.stopOnPhone()
        session = null
        pending = null
    }

    /** The running session's id once consent was given (pending is cleared then), or the pending request's. */
    private fun currentId(): String? = session?.sessionId ?: pending?.sessionId

    private fun sendState(sessionId: String, state: String, reason: String? = null) {
        events.send(
            MessageTypes.SCREEN_STATE,
            ScreenStatePayload.serializer(),
            ScreenStatePayload(sessionId = sessionId, state = state, reason = reason, control = bridge.isBound),
        )
    }

    private fun currentFrame(): ScreenFrame {
        val windowManager = context.getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        val display: Display? = windowManager.defaultDisplay
        val rotation = display?.rotation ?: Surface.ROTATION_0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return ScreenFrame(bounds.width(), bounds.height(), rotation)
        }
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { display?.getRealMetrics(it) }
        return ScreenFrame(metrics.widthPixels, metrics.heightPixels, rotation)
    }

    private companion object {
        const val AWAITING_CONSENT_DELAY_MS = 150L
    }
}

/** A long-lived scope for the active session, kept off the command-handling threads. */
@Singleton
class ScreenScope @Inject constructor() {
    val value: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
