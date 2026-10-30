package com.dcontrol.client.feature.mic.domain

import com.dcontrol.client.core.rtc.RtcConfig
import com.dcontrol.client.core.rtc.RtcEngine
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.IceServer
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.MicCaptureRequest
import com.dcontrol.client.core.websocket.protocol.MicCaptureResult
import com.dcontrol.client.core.websocket.protocol.MicStatePayload
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.feature.mic.capture.MicCapture
import com.dcontrol.client.feature.mic.data.AudioUploader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives one microphone session: it owns the [RtcEngine] (audio only, no data channel), sends `mic.state` and the
 * `mic.signal` OFFER, and runs `mic.capture` one clip at a time. The microphone and the engine sit behind seams,
 * so it is tested without the audio stack or native libraries. States and reasons reuse the camera values.
 */
class MicSession(
    val sessionId: String,
    private val iceServers: List<IceServer>,
    private val capture: MicCapture,
    private val factory: RtcEngineFactory,
    private val events: DeviceEventSender,
    private val uploader: AudioUploader,
    private val scope: CoroutineScope,
    /** Called once when the session ends for any reason, so the capture service and its indicator stop too. */
    private val onEnded: () -> Unit = {},
) : RtcEngine.Listener {

    private val lock = Any()
    private var engine: RtcEngine? = null
    private var ended = false

    @Volatile
    private var live = false

    /** Held by the clip in progress; a second one is a CONFLICT. */
    private val busy = AtomicBoolean(false)

    /** Consent was granted and the service is in the foreground: open the connection and send the offer. */
    fun begin() {
        sendState(CameraStates.STARTING)
        scope.launch {
            val offer = try {
                val config = RtcConfig(sessionId, capture.source, audio = true, control = false, iceServers = iceServers)
                val created = factory.create(config, this@MicSession)
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
            events.send(MessageTypes.MIC_SIGNAL, RtcSignal.serializer(), RtcSignal(sessionId, RtcKinds.OFFER, offer))
        }
    }

    fun acceptAnswer(sdp: String) {
        scope.launch { synchronized(lock) { engine }?.acceptAnswer(sdp) }
    }

    /** The user tapped Stop on the phone's ongoing notification. */
    fun stopOnPhone() = end(CameraReasons.STOPPED_ON_PHONE, silent = false)

    /** The controller asked the phone to stop (`mic.stop`), or the phone went offline: end silently. */
    fun stopSilently() = end(reason = null, silent = true)

    /** `mic.capture`: records a clip, uploads it, and returns its duration. */
    suspend fun capture(request: MicCaptureRequest): MicCaptureResult {
        if (!live) throw CommandException(ErrorCodes.CONFLICT, "The microphone is not live")
        return exclusive("A clip is already recording") {
            val media = attempt("Recording failed") { capture.recordClip(request.durationMs) }
            try {
                uploader.upload(request.mediaId, media)
            } finally {
                media.file.delete()
            }
            MicCaptureResult(durationMs = media.durationMs)
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

    /** Runs a capture call, turning anything but a [CommandException] into INTERNAL. */
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
            MessageTypes.MIC_STATE,
            MicStatePayload.serializer(),
            MicStatePayload(sessionId = sessionId, state = state, reason = reason),
        )
    }
}
