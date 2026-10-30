package com.dcontrol.client.feature.mic

import com.dcontrol.client.core.rtc.RtcConfig
import com.dcontrol.client.core.rtc.RtcEngine
import com.dcontrol.client.core.rtc.RtcEngineFactory
import com.dcontrol.client.core.rtc.RtcSource
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.mic.capture.CapturedAudio
import com.dcontrol.client.feature.mic.capture.MicCapture
import com.dcontrol.client.feature.mic.capture.MicCaptureFactory
import com.dcontrol.client.feature.mic.data.AudioUploader
import com.dcontrol.client.feature.mic.domain.MicConsentFlow
import android.content.Context
import java.io.File
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

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
class FakeConsentFlow(var foreground: Boolean = false) : MicConsentFlow {
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

/** A source the fake engine never starts; audio-only, so [RtcSource]'s video members stay at their defaults. */
object FakeSource : RtcSource {
    override val label = "mic"
    override val isScreencast = false
    override val hasVideo = false
    override val frameRate = 0
    override val maxBitrateBps: Int? = null
    override fun createAudioModule(context: Context): JavaAudioDeviceModule? = null
}

/** A microphone without hardware: writes small temp files, and can hold a clip open with [gate]. */
class FakeMicCapture : MicCapture {
    @Volatile var closed = false
    @Volatile var clips = mutableListOf<Int>()
    @Volatile var failNext: Exception? = null
    @Volatile var gate: CompletableDeferred<Unit>? = null
    val files = mutableListOf<File>()

    override val source: RtcSource = FakeSource

    override suspend fun recordClip(durationMs: Int): CapturedAudio {
        gate?.await()
        failNext?.let { failNext = null; throw it }
        clips += durationMs
        val file = File.createTempFile("fake-", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        files += file
        return CapturedAudio(file, "audio/mp4", durationMs)
    }

    override fun close() {
        closed = true
    }
}

class FakeMicCaptureFactory(val make: () -> FakeMicCapture = { FakeMicCapture() }) : MicCaptureFactory {
    val created = mutableListOf<FakeMicCapture>()
    val last: FakeMicCapture get() = created.last()

    override fun create(): MicCapture = make().also { created += it }
}

/** Remembers what was uploaded, and whether the file still existed at upload time. */
class RecordingUploader : AudioUploader {
    val uploads = mutableListOf<Pair<String, CapturedAudio>>()

    override suspend fun upload(mediaId: String, media: CapturedAudio) {
        check(media.file.exists())
        uploads += mediaId to media
    }
}
