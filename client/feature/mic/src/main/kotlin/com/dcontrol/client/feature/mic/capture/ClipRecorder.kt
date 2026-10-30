package com.dcontrol.client.feature.mic.capture

/**
 * Records a clip from the live microphone samples WebRTC already captures. [onSamples] is called for every PCM
 * chunk the audio device produces (on WebRTC's audio thread); it is ignored unless a clip is being recorded, so
 * live streaming is never interrupted. [record] encodes [durationMs] of those samples to a `.m4a` file.
 */
interface ClipRecorder {
    /** A chunk of 16-bit PCM from the live microphone: [pcm] of [channels] channels at [sampleRate] Hz. */
    fun onSamples(pcm: ByteArray, sampleRate: Int, channels: Int)

    /** Records the next [durationMs] of live samples. One clip at a time. */
    suspend fun record(durationMs: Int): CapturedAudio
}

/**
 * The clip's timing, independent of any encoder so it can be tested as pure Kotlin. Presentation times and the
 * end of the clip come from the sample count, not the wall clock: a clip is [durationMs] of audio however long
 * the samples take to arrive, and the final chunk is trimmed so the clip ends exactly on the requested length.
 */
class ClipTiming(
    private val sampleRate: Int,
    channels: Int,
    durationMs: Int,
) {
    private val bytesPerFrame = BYTES_PER_SAMPLE * channels
    private val targetFrames: Long = durationMs.toLong() * sampleRate / MILLIS_PER_SECOND
    private var frames = 0L

    /** True once a full [durationMs] of samples has been taken. */
    val isComplete: Boolean get() = frames >= targetFrames

    /** A run of PCM to hand to the encoder: its byte count, its presentation time, and whether it ends the clip. */
    data class Slice(val presentationTimeUs: Long, val byteCount: Int, val endOfStream: Boolean)

    /**
     * Takes as much of [availableBytes] of PCM as the clip still needs, whole frames only, trimming the last
     * chunk so the clip ends on [targetFrames]. Returns null when the clip is already complete or no whole frame
     * fits, so callers stop feeding the encoder.
     */
    fun take(availableBytes: Int): Slice? {
        if (isComplete) return null
        val offeredFrames = availableBytes / bytesPerFrame
        val remainingFrames = targetFrames - frames
        val usedFrames = minOf(offeredFrames.toLong(), remainingFrames)
        if (usedFrames <= 0) return null
        val presentationTimeUs = frames * MICROS_PER_SECOND / sampleRate
        frames += usedFrames
        return Slice(presentationTimeUs, (usedFrames * bytesPerFrame).toInt(), frames >= targetFrames)
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MILLIS_PER_SECOND = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
