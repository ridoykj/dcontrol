package com.dcontrol.client.feature.mic.capture

import com.dcontrol.client.core.rtc.RtcSource
import java.io.File

/** An audio clip written to a temporary file, ready to upload. The caller deletes [file]. */
data class CapturedAudio(
    val file: File,
    val mime: String,
    val durationMs: Int,
)

/**
 * The microphone behind an interface, so [com.dcontrol.client.feature.mic.domain.MicSession] can be tested
 * without the audio stack. One instance drives one session: the live audio the RTC engine streams, plus clips
 * recorded from the same microphone samples so live audio keeps playing while a clip records.
 */
interface MicCapture {
    /** The live audio the RTC engine streams. */
    val source: RtcSource

    /** Records [durationMs] of the live microphone samples as AAC in an MPEG-4 (`.m4a`) file. */
    suspend fun recordClip(durationMs: Int): CapturedAudio

    fun close()
}

/** Builds the [MicCapture] for a consented session. */
interface MicCaptureFactory {
    fun create(): MicCapture
}
