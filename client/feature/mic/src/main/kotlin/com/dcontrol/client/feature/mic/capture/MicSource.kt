package com.dcontrol.client.feature.mic.capture

import android.content.Context
import com.dcontrol.client.core.rtc.RtcSource
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The live audio the engine streams: WebRTC's standard microphone module, with a samples-ready callback that
 * forwards every captured PCM chunk to [recorder]. There is no video ([hasVideo] is false), so the engine adds a
 * single audio track and the offer has one audio m-line. Platform AEC/NS are left on (the module's defaults).
 */
internal class MicSource(private val recorder: ClipRecorder) : RtcSource {
    override val label: String = "mic"
    override val isScreencast: Boolean = false
    override val hasVideo: Boolean = false
    override val frameRate: Int = 0
    override val maxBitrateBps: Int? = null

    override fun createAudioModule(context: Context): JavaAudioDeviceModule =
        JavaAudioDeviceModule.builder(context)
            .setSamplesReadyCallback { samples ->
                recorder.onSamples(samples.data, samples.sampleRate, samples.channelCount)
            }
            .createAudioDeviceModule()
}
