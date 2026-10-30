package com.dcontrol.client.feature.camera

import android.content.Context
import android.util.Size
import androidx.lifecycle.LifecycleOwner
import com.dcontrol.client.core.rtc.RtcConfig
import com.dcontrol.client.core.rtc.RtcEngine
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.rtc.RtcSource
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.CameraFacings
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.camera.capture.CameraCapture
import com.dcontrol.client.feature.camera.capture.CameraCaptureFactory
import com.dcontrol.client.feature.camera.capture.CapturedMedia
import com.dcontrol.client.feature.camera.data.MediaUploader
import com.dcontrol.client.feature.camera.domain.CameraConsentFlow
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import org.webrtc.VideoCapturer
import org.webrtc.audio.JavaAudioDeviceModule

/** Records the events a component sends to the controller; [connection] drives the online/offline state. */
class RecordingEvents(private val codec: ProtocolCodec = ProtocolCodec()) : DeviceEventSender {
    val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Online("Pixel", 30))

    override val connectionState = connection

    override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean {
        sent += type to codec.toPayload(serializer, payload)
        return true
    }

    fun of(type: String): List<JsonObject> = synchronized(sent) { sent.filter { it.first == type }.map { it.second } }
    fun count(type: String): Int = of(type).size
}

/** A fake [RtcEngine] that returns a canned offer and records the session's calls. */
class FakeRtcEngine(private val offer: String = "offer-sdp") : RtcEngine {
    @Volatile var started = false
    @Volatile var answered: String? = null
    @Volatile var closed = false

    override suspend fun start(): String {
        started = true
        return offer
    }

    override suspend fun acceptAnswer(sdp: String) {
        answered = sdp
    }

    override fun sendControl(text: String) = Unit

    override fun close() {
        closed = true
    }
}

/** Captures the config and listener passed to the factory; each create returns a fresh engine. */
class FakeEngineHolder {
    val engines = mutableListOf<FakeRtcEngine>()
    @Volatile var config: RtcConfig? = null
    @Volatile var listener: RtcEngine.Listener? = null

    val engine: FakeRtcEngine get() = engines.last()

    fun factory() = RtcEngineFactory { config, listener ->
        this.config = config
        this.listener = listener
        FakeRtcEngine().also { engines += it }
    }
}

/** Records which consent path the controller chose. */
class FakeConsentFlow(var foreground: Boolean = false) : CameraConsentFlow {
    @Volatile var launched: String? = null
    @Volatile var requested: Pair<String, String>? = null
    @Volatile var cancelled = 0
    @Volatile var captureStops = 0

    override fun canLaunchDirectly(): Boolean = foreground

    override fun launchConsent(sessionId: String) {
        launched = sessionId
    }

    override fun postRequest(sessionId: String, viewer: String) {
        requested = sessionId to viewer
    }

    override fun cancelRequest() {
        cancelled++
    }

    override fun stopCapture() {
        captureStops++
    }
}

/** A source the fake engine never starts. */
object FakeSource : RtcSource {
    override val label = "camera"
    override val isScreencast = false
    override val frameRate = 30
    override val maxBitrateBps: Int? = null
    override fun createVideoCapturer(context: Context, onStopped: () -> Unit): VideoCapturer = error("not used")
    override fun videoSize(context: Context) = Size(1280, 720)
    override fun createAudioModule(context: Context): JavaAudioDeviceModule? = null
}

/** A camera without hardware: writes small temp files, and can hold a capture open with [gate]. */
class FakeCapture(
    private val lensSet: Set<String> = CameraFacings.ALL,
    private val video: Boolean = true,
    private val openError: Exception? = null,
) : CameraCapture {
    @Volatile var opened: String? = null
    @Volatile var closed = false
    @Volatile var photos = 0
    @Volatile var clips = mutableListOf<Int>()
    @Volatile var failNext: Exception? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val files = mutableListOf<File>()

    override val source: RtcSource = FakeSource

    @Volatile
    override var facing: String = CameraFacings.BACK
        private set

    @Volatile
    override var videoAvailable: Boolean = false
        private set

    override suspend fun open(preferred: String) {
        openError?.let { throw it }
        facing = if (preferred in lensSet) preferred else lensSet.first()
        videoAvailable = video
        opened = facing
    }

    override fun hasLens(facing: String) = facing in lensSet

    override suspend fun switch(facing: String) {
        this.facing = facing
    }

    override suspend fun takePhoto(): CapturedMedia {
        gate?.await()
        failNext?.let { failNext = null; throw it }
        photos++
        return media("jpg", "image/jpeg", 4000, 3000, null)
    }

    override suspend fun recordClip(durationMs: Int): CapturedMedia {
        gate?.await()
        clips += durationMs
        return media("mp4", "video/mp4", 1920, 1080, durationMs)
    }

    override fun close() {
        closed = true
    }

    private fun media(ext: String, mime: String, width: Int, height: Int, durationMs: Int?): CapturedMedia {
        val file = File.createTempFile("fake-", ".$ext").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        files += file
        return CapturedMedia(file, mime, width, height, durationMs)
    }
}

class FakeCaptureFactory(var hasCamera: Boolean = true, val make: () -> FakeCapture = { FakeCapture() }) : CameraCaptureFactory {
    val created = mutableListOf<FakeCapture>()
    val last: FakeCapture get() = created.last()

    override fun hasCamera(): Boolean = hasCamera

    override fun create(owner: LifecycleOwner, audio: Boolean): CameraCapture = make().also { created += it }
}

/** Remembers what was uploaded, and whether the file still existed at upload time. */
class RecordingUploader : MediaUploader {
    val uploads = mutableListOf<Pair<String, CapturedMedia>>()

    override suspend fun upload(mediaId: String, media: CapturedMedia) {
        check(media.file.exists())
        uploads += mediaId to media
    }
}
