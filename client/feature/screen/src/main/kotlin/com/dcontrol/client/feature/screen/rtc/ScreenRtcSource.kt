package com.dcontrol.client.feature.screen.rtc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import android.view.WindowManager
import com.dcontrol.client.core.rtc.RtcSource
import com.dcontrol.client.core.websocket.protocol.ScreenPreset
import java.nio.ByteBuffer
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.VideoCapturer
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Mirrors the screen through MediaProjection. Audio is the device's own playback, captured with
 * `AudioPlaybackCapture` and fed into WebRTC's audio buffer callback (verified on the SDK jar), so no
 * microphone samples are ever sent. Single-use: one per session.
 */
class ScreenRtcSource(
    /** The MediaProjection permission result (`Activity.onActivityResult` data). */
    private val resultData: Intent,
    private val preset: ScreenPreset,
    private val audio: Boolean,
) : RtcSource {

    private var capturer: ScreenCapturerAndroid? = null
    private val playback = PlaybackAudioCapture()

    override val label: String = "screen"
    override val isScreencast: Boolean = true
    override val frameRate: Int get() = preset.frameRate
    override val maxBitrateBps: Int get() = preset.maxBitrateBps

    override fun createVideoCapturer(context: Context, onStopped: () -> Unit): VideoCapturer =
        ScreenCapturerAndroid(
            resultData,
            object : MediaProjection.Callback() {
                override fun onStop() = onStopped()
            },
        ).also { capturer = it }

    override fun videoSize(context: Context): Size {
        val metrics = realMetrics(context)
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (longEdge <= preset.longEdge || longEdge == 0) {
            return Size(metrics.widthPixels, metrics.heightPixels)
        }
        val scale = preset.longEdge.toFloat() / longEdge
        return Size(even(metrics.widthPixels * scale), even(metrics.heightPixels * scale))
    }

    override fun createAudioModule(context: Context): JavaAudioDeviceModule {
        val builder = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(PlaybackAudioCapture.SAMPLE_RATE)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
        if (audio) {
            // Overwrite the mic buffer WebRTC just read with the captured playback audio; return its timestamp.
            builder.setAudioBufferCallback { buffer, _, _, _, bytesRead, captureTimestampNs ->
                playback.fill(buffer, bytesRead)
                captureTimestampNs
            }
        }
        return builder.createAudioDeviceModule()
    }

    override fun onAudioTrackAdded() {
        val projection = capturer?.mediaProjection
        if (projection != null) playback.start(projection)
    }

    override fun release() = playback.stop()

    private fun realMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private companion object {
        fun even(value: Float): Int {
            val rounded = value.toInt()
            return if (rounded % 2 == 0) rounded else rounded - 1
        }
    }
}

/**
 * Captures the device's own playback (media/game/unknown usages) and feeds it into WebRTC's audio buffer.
 * A real device opens an [AudioRecord]; until it is started (or when unavailable) the buffer is silenced.
 */
internal class PlaybackAudioCapture {
    private var record: AudioRecord? = null

    // RECORD_AUDIO is verified by ScreenController before audio is turned on, so this path only runs when
    // it is granted; lint can't follow that, and the AudioRecord build is already guarded by try/catch.
    @SuppressLint("MissingPermission")
    fun start(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val created = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (_: Exception) {
            null
        }
        created?.runCatching { startRecording() }
        record = created
    }

    /** Replaces the first [bytesRead] bytes of [buffer] with captured audio, or silence. */
    fun fill(buffer: ByteBuffer, bytesRead: Int) {
        buffer.position(0)
        val active = record
        if (active != null && active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val read = active.read(buffer, bytesRead)
            if (read in 0 until bytesRead) zero(buffer, read, bytesRead)
        } else {
            zero(buffer, 0, bytesRead)
        }
        buffer.position(0)
    }

    private fun zero(buffer: ByteBuffer, from: Int, to: Int) {
        buffer.position(from)
        for (i in from until to) buffer.put(0)
    }

    fun stop() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}
