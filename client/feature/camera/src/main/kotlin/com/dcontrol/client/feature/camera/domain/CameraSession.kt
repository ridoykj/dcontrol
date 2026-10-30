package com.dcontrol.client.feature.camera.domain

import com.dcontrol.client.core.rtc.RtcConfig
import com.dcontrol.client.core.rtc.RtcEngine
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.CameraCaptureRequest
import com.dcontrol.client.core.websocket.protocol.CameraCaptureResult
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStatePayload
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.CaptureKinds
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.IceServer
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.feature.camera.capture.CameraCapture
import com.dcontrol.client.feature.camera.data.MediaUploader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives one camera session: it opens the [CameraCapture], owns the [RtcEngine] (no data channel), sends
 * `camera.state` and the `camera.signal` OFFER, and runs `camera.capture` / `camera.switch` one at a time.
 * Both the camera and the engine sit behind seams, so it is tested without a camera or native libraries.
 */
class CameraSession(
    val sessionId: String,
    private val requestedFacing: String,
    private val audio: Boolean,
    private val iceServers: List<IceServer>,
    private val capture: CameraCapture,
    private val factory: RtcEngineFactory,
    private val events: DeviceEventSender,
    private val uploader: MediaUploader,
    private val scope: CoroutineScope,
    /** Called once when the session ends for any reason, so the capture service and its indicators stop too. */
    private val onEnded: () -> Unit = {},
) : RtcEngine.Listener {

    private val lock = Any()
    private var engine: RtcEngine? = null
    private var ended = false

    @Volatile
    private var live = false

    @Volatile
    private var facing = requestedFacing

    /** Held by the capture or switch in progress; a second one is a CONFLICT. */
    private val busy = AtomicBoolean(false)

    /** Consent was granted and the service is in the foreground: open the camera and send the offer. */
    fun begin() {
        sendState(CameraStates.STARTING)
        scope.launch {
            val offer = try {
                capture.open(requestedFacing)
                facing = capture.facing
                val config = RtcConfig(sessionId, capture.source, audio = audio, control = false, iceServers = iceServers)
                val created = factory.create(config, this@CameraSession)
                synchronized(lock) {
                    if (ended) {
                        created.close()
                        return@launch
                    }
                    engine = created
                }
                created.start()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                end(CameraReasons.ERROR, silent = false)
                return@launch
            }
            events.send(MessageTypes.CAMERA_SIGNAL, RtcSignal.serializer(), RtcSignal(sessionId, RtcKinds.OFFER, offer))
        }
    }

    fun acceptAnswer(sdp: String) {
        scope.launch { synchronized(lock) { engine }?.acceptAnswer(sdp) }
    }

    /** The user tapped Stop on the phone's ongoing notification. */
    fun stopOnPhone() = end(CameraReasons.STOPPED_ON_PHONE, silent = false)

    /** The controller asked the phone to stop (`camera.stop`), or the phone went offline: end silently. */
    fun stopSilently() = end(reason = null, silent = true)

    /** `camera.capture`: takes the photo or clip, uploads it, and returns its metadata. */
    suspend fun capture(request: CameraCaptureRequest): CameraCaptureResult {
        if (!live) throw CommandException(ErrorCodes.CONFLICT, "The camera is not live")
        if (request.kind == CaptureKinds.VIDEO && !capture.videoAvailable) {
            throw CommandException(ErrorCodes.UNSUPPORTED, "Video recording is not available on this phone")
        }
        return exclusive("A capture is already running") {
            val media = attempt("Capture failed") {
                if (request.kind == CaptureKinds.VIDEO) {
                    capture.recordClip(requireNotNull(request.durationMs))
                } else {
                    capture.takePhoto()
                }
            }
            try {
                uploader.upload(request.mediaId, media)
            } finally {
                media.file.delete()
            }
            CameraCaptureResult(width = media.width, height = media.height, durationMs = media.durationMs)
        }
    }

    /** `camera.switch`: rebinds to the other lens while the stream keeps going on the same track. */
    suspend fun switch(target: String): String {
        if (!live) throw CommandException(ErrorCodes.CONFLICT, "The camera is not live")
        return exclusive("A capture is running") {
            if (target != facing) {
                if (!capture.hasLens(target)) throw CommandException(ErrorCodes.UNSUPPORTED, "This phone has no $target camera")
                attempt("Switching cameras failed") { capture.switch(target) }
                facing = capture.facing
                sendState(CameraStates.LIVE)
            }
            facing
        }
    }

    override fun onLive() {
        if (synchronized(lock) { ended }) return
        live = true
        sendState(CameraStates.LIVE)
    }

    override fun onFailed() = end(CameraReasons.ERROR, silent = false)

    /** There is no data channel (`control = false`), so nothing arrives here. */
    override fun onControl(text: String) = Unit

    private suspend fun <T> exclusive(conflict: String, block: suspend () -> T): T {
        if (!busy.compareAndSet(false, true)) throw CommandException(ErrorCodes.CONFLICT, conflict)
        try {
            return block()
        } finally {
            busy.set(false)
        }
    }

    /** Runs a camera call, turning anything but a [CommandException] into INTERNAL. */
    private suspend fun <T> attempt(message: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CommandException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw CommandException(ErrorCodes.INTERNAL, "$message: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun end(reason: String?, silent: Boolean) {
        val running = synchronized(lock) {
            if (ended) return
            ended = true
            engine.also { engine = null }
        }
        live = false
        capture.close()
        running?.close()
        if (!silent) sendState(CameraStates.ENDED, reason)
        onEnded()
    }

    private fun sendState(state: String, reason: String? = null) {
        events.send(
            MessageTypes.CAMERA_STATE,
            CameraStatePayload.serializer(),
            CameraStatePayload(
                sessionId = sessionId,
                state = state,
                reason = reason,
                facing = facing,
                video = capture.videoAvailable,
            ),
        )
    }
}
