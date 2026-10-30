package com.dcontrol.client.feature.mic.domain

import android.Manifest
import com.dcontrol.client.core.rtc.MicrophoneLock
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.IceServer
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.MicCaptureRequest
import com.dcontrol.client.core.websocket.protocol.MicCaptureResult
import com.dcontrol.client.core.websocket.protocol.MicStartRequest
import com.dcontrol.client.core.websocket.protocol.MicStartResult
import com.dcontrol.client.core.websocket.protocol.MicStatePayload
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.feature.mic.capture.MicCaptureFactory
import com.dcontrol.client.feature.mic.data.AudioUploader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The request that is waiting for the owner to approve it on the phone. */
data class PendingMic(
    val sessionId: String,
    val viewer: String,
    val iceServers: List<IceServer>,
)

/**
 * Coordinates a single microphone session across the command handlers, the consent prompt and the capture
 * service. At most one session (or pending request) exists at a time; a new `mic.start` replaces the old. Nothing
 * is captured before the owner approves, and losing the controller connection ends the session. The microphone is
 * held for the whole session so a camera or screen session can't take it (ws-v1.md, Phase 6).
 */
@Singleton
class MicController @Inject constructor(
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
    private val consent: MicConsentFlow,
    private val engineFactory: RtcEngineFactory,
    private val captureFactory: MicCaptureFactory,
    private val uploader: AudioUploader,
    private val microphone: MicrophoneLock,
    private val scope: MicScope,
) {
    @Volatile
    private var pending: PendingMic? = null

    @Volatile
    private var session: MicSession? = null

    /** The microphone held for the pending request; it passes to the session once the owner approves. */
    private var pendingMic: MicrophoneLock.Hold? = null

    val pendingSession: PendingMic? get() = pending

    private var watchingConnection = false

    /** Handles `mic.start`: checks the grant and the microphone lock, records the request, and asks the owner. */
    @Synchronized
    fun start(request: MicStartRequest): MicStartResult {
        if (!permissions.isGranted(Manifest.permission.RECORD_AUDIO)) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Microphone permission is not granted on the phone", Permissions.MICROPHONE)
        }
        watchConnection()

        // A new start replaces any running or pending session; the controller tells the old browser REPLACED.
        session?.stopSilently()
        session = null
        clearPending()

        // The microphone has one user: refuse while a camera or screen session holds it.
        val mic = microphone.tryAcquire(MicrophoneLock.MIC)
            ?: throw CommandException(ErrorCodes.CONFLICT, "The microphone is in use by the ${microphone.owner ?: "camera"}")
        pendingMic = mic
        pending = PendingMic(
            sessionId = request.sessionId,
            viewer = request.viewer,
            iceServers = request.iceServers,
        )
        try {
            if (consent.canLaunchDirectly()) {
                consent.launchConsent(request.sessionId)
            } else {
                consent.postRequest(request.sessionId, request.viewer)
            }
        } catch (e: Exception) {
            clearPending()
            throw CommandException(ErrorCodes.INTERNAL, "The consent prompt could not be shown: ${e.message ?: e.javaClass.simpleName}")
        }
        // The mic.start result must reach the browser before the first mic.state. The result is sent when this
        // handler returns, so emit AWAITING_CONSENT off-thread after a short delay to keep that order.
        scope.value.launch {
            delay(AWAITING_CONSENT_DELAY_MS)
            synchronized(this@MicController) {
                if (pending?.sessionId == request.sessionId) sendPendingState(request.sessionId, CameraStates.AWAITING_CONSENT)
            }
        }
        return MicStartResult(audio = true)
    }

    /** The owner approved and the capture service is in the foreground: build the session and start it. */
    @Synchronized
    fun onConsentGranted(sessionId: String): Boolean {
        val request = pending?.takeIf { it.sessionId == sessionId } ?: return false
        val mic = pendingMic
        pending = null
        pendingMic = null
        lateinit var built: MicSession
        built = MicSession(
            sessionId = request.sessionId,
            iceServers = request.iceServers,
            capture = captureFactory.create(),
            factory = engineFactory,
            events = events,
            uploader = uploader,
            scope = scope.value,
            onEnded = {
                synchronized(this@MicController) { if (session === built) session = null }
                mic?.release()
                consent.stopCapture()
            },
        )
        session = built
        built.begin()
        return true
    }

    @Synchronized
    fun onConsentDenied(sessionId: String) {
        val request = pending?.takeIf { it.sessionId == sessionId } ?: return
        clearPending()
        sendPendingState(request.sessionId, CameraStates.ENDED, CameraReasons.DENIED)
    }

    /** The capture service could not enter the foreground, so the microphone can't be opened. */
    @Synchronized
    fun onCaptureFailed(sessionId: String) {
        val request = pending?.takeIf { it.sessionId == sessionId } ?: return
        clearPending()
        sendPendingState(request.sessionId, CameraStates.ENDED, CameraReasons.ERROR)
    }

    /** The browser's ANSWER (`mic.signal`). */
    fun onAnswer(signal: RtcSignal) {
        active(signal.sessionId)?.acceptAnswer(signal.sdp)
    }

    /** `mic.stop` from the controller: tear down silently. */
    @Synchronized
    fun onStop(sessionId: String) {
        if (pending?.sessionId == sessionId) {
            clearPending()
            consent.cancelRequest()
        }
        active(sessionId)?.let {
            session = null
            it.stopSilently()
        }
    }

    /** The user tapped Stop on the ongoing notification, or the capture service was destroyed. */
    @Synchronized
    fun stopFromPhone() {
        val running = session ?: return
        session = null
        running.stopOnPhone()
    }

    suspend fun capture(request: MicCaptureRequest): MicCaptureResult {
        val running = active(request.sessionId) ?: throw CommandException(ErrorCodes.CONFLICT, "No live microphone session ${request.sessionId}")
        return running.capture(request)
    }

    /**
     * Ends whatever is running once the phone is no longer online. Started on the first `mic.start`, not at
     * construction: this controller is built while the socket itself is (it is part of the handler map).
     */
    private fun watchConnection() {
        if (watchingConnection) return
        watchingConnection = true
        scope.value.launch {
            events.connectionState.collect { state -> if (state !is ConnectionState.Online) onOffline() }
        }
    }

    @Synchronized
    private fun onOffline() {
        if (pending != null) {
            clearPending()
            consent.cancelRequest()
        }
        val running = session ?: return
        session = null
        running.stopSilently()
    }

    /** Drops the pending request and gives back the microphone it held. */
    private fun clearPending() {
        pending = null
        pendingMic?.release()
        pendingMic = null
    }

    @Synchronized
    private fun active(sessionId: String): MicSession? = session?.takeIf { it.sessionId == sessionId }

    private fun sendPendingState(sessionId: String, state: String, reason: String? = null) {
        events.send(
            MessageTypes.MIC_STATE,
            MicStatePayload.serializer(),
            MicStatePayload(sessionId = sessionId, state = state, reason = reason),
        )
    }

    private companion object {
        const val AWAITING_CONSENT_DELAY_MS = 150L
    }
}

/** A long-lived scope for the active session, kept off the command-handling threads. */
@Singleton
class MicScope @Inject constructor() {
    val value: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
