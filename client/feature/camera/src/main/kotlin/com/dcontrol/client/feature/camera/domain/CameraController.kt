package com.dcontrol.client.feature.camera.domain

import android.Manifest
import androidx.lifecycle.LifecycleOwner
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.CameraCaptureRequest
import com.dcontrol.client.core.websocket.protocol.CameraCaptureResult
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStartRequest
import com.dcontrol.client.core.websocket.protocol.CameraStartResult
import com.dcontrol.client.core.websocket.protocol.CameraStatePayload
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.CameraSwitchRequest
import com.dcontrol.client.core.websocket.protocol.CameraSwitchResult
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.IceServer
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.feature.camera.capture.CameraCaptureFactory
import com.dcontrol.client.feature.camera.data.MediaUploader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The request that is waiting for the owner to approve it on the phone. */
data class PendingCamera(
    val sessionId: String,
    val viewer: String,
    val facing: String,
    val audio: Boolean,
    val iceServers: List<IceServer>,
)

/**
 * Coordinates a single camera session across the command handlers, the consent prompt and the capture service.
 * At most one session (or pending request) exists at a time; a new `camera.start` replaces the old. Nothing is
 * captured before the owner approves, and losing the controller connection ends the session.
 */
@Singleton
class CameraController @Inject constructor(
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
    private val consent: CameraConsentFlow,
    private val engineFactory: RtcEngineFactory,
    private val captureFactory: CameraCaptureFactory,
    private val uploader: MediaUploader,
    private val scope: CameraScope,
) {
    @Volatile
    private var pending: PendingCamera? = null

    @Volatile
    private var session: CameraSession? = null

    val pendingSession: PendingCamera? get() = pending

    private var watchingConnection = false

    /** Handles `camera.start`: checks the grants, records the request, and asks the owner. */
    @Synchronized
    fun start(request: CameraStartRequest): CameraStartResult {
        if (!permissions.isGranted(Manifest.permission.CAMERA)) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Camera permission is not granted on the phone", Permissions.CAMERA)
        }
        if (!captureFactory.hasCamera()) throw CommandException(ErrorCodes.UNSUPPORTED, "This phone has no camera")
        watchConnection()

        // A new start replaces any running or pending session; the controller tells the old browser REPLACED.
        session?.stopSilently()
        session = null
        pending = null

        val audio = request.audio && permissions.isGranted(Manifest.permission.RECORD_AUDIO)
        pending = PendingCamera(
            sessionId = request.sessionId,
            viewer = request.viewer,
            facing = request.facing,
            audio = audio,
            iceServers = request.iceServers,
        )
        try {
            if (consent.canLaunchDirectly()) {
                consent.launchConsent(request.sessionId)
            } else {
                consent.postRequest(request.sessionId, request.viewer)
            }
        } catch (e: Exception) {
            pending = null
            throw CommandException(ErrorCodes.INTERNAL, "The consent prompt could not be shown: ${e.message ?: e.javaClass.simpleName}")
        }
        // The camera.start result must reach the browser before the first camera.state. The result is sent when
        // this handler returns, so emit AWAITING_CONSENT off-thread after a short delay to keep that order.
        scope.value.launch {
            delay(AWAITING_CONSENT_DELAY_MS)
            synchronized(this@CameraController) {
                val waiting = pending
                if (waiting?.sessionId == request.sessionId) sendPendingState(waiting, CameraStates.AWAITING_CONSENT)
            }
        }
        return CameraStartResult(audio = audio)
    }

    /** The owner approved and the capture service is in the foreground: build the session and start it. */
    @Synchronized
    fun onConsentGranted(sessionId: String, owner: LifecycleOwner): Boolean {
        val request = pending?.takeIf { it.sessionId == sessionId } ?: return false
        pending = null
        lateinit var built: CameraSession
        built = CameraSession(
            sessionId = request.sessionId,
            requestedFacing = request.facing,
            audio = request.audio,
            iceServers = request.iceServers,
            capture = captureFactory.create(owner, request.audio),
            factory = engineFactory,
            events = events,
            uploader = uploader,
            scope = scope.value,
            onEnded = {
                synchronized(this@CameraController) { if (session === built) session = null }
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
        pending = null
        sendPendingState(request, CameraStates.ENDED, CameraReasons.DENIED)
    }

    /** The capture service could not enter the foreground, so the camera can't be opened. */
    @Synchronized
    fun onCaptureFailed(sessionId: String) {
        val request = pending?.takeIf { it.sessionId == sessionId } ?: return
        pending = null
        sendPendingState(request, CameraStates.ENDED, CameraReasons.ERROR)
    }

    /** The browser's ANSWER (`camera.signal`). */
    fun onAnswer(signal: RtcSignal) {
        active(signal.sessionId)?.acceptAnswer(signal.sdp)
    }

    /** `camera.stop` from the controller: tear down silently. */
    @Synchronized
    fun onStop(sessionId: String) {
        if (pending?.sessionId == sessionId) {
            pending = null
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

    suspend fun switch(request: CameraSwitchRequest): CameraSwitchResult {
        val running = active(request.sessionId) ?: throw CommandException(ErrorCodes.CONFLICT, "No live camera session ${request.sessionId}")
        return CameraSwitchResult(facing = running.switch(request.facing))
    }

    suspend fun capture(request: CameraCaptureRequest): CameraCaptureResult {
        val running = active(request.sessionId) ?: throw CommandException(ErrorCodes.CONFLICT, "No live camera session ${request.sessionId}")
        return running.capture(request)
    }

    /**
     * Ends whatever is running once the phone is no longer online. Started on the first `camera.start`, not at
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
            pending = null
            consent.cancelRequest()
        }
        val running = session ?: return
        session = null
        running.stopSilently()
    }

    @Synchronized
    private fun active(sessionId: String): CameraSession? = session?.takeIf { it.sessionId == sessionId }

    private fun sendPendingState(request: PendingCamera, state: String, reason: String? = null) {
        events.send(
            MessageTypes.CAMERA_STATE,
            CameraStatePayload.serializer(),
            CameraStatePayload(sessionId = request.sessionId, state = state, reason = reason, facing = request.facing, video = false),
        )
    }

    private companion object {
        const val AWAITING_CONSENT_DELAY_MS = 150L
    }
}

/** A long-lived scope for the active session, kept off the command-handling threads. */
@Singleton
class CameraScope @Inject constructor() {
    val value: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
