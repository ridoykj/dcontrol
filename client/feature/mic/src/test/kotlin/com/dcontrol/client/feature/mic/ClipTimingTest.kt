package com.dcontrol.client.feature.mic

import com.dcontrol.client.feature.mic.capture.ClipTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder's timing/chunking, as pure Kotlin: sample-count timestamps, trimming, and the end of the clip. */
class ClipTimingTest {

    private val sampleRate = 48_000
    private val channels = 1
    private val bytesPerFrame = 2 * channels

    /** A 1 s mono clip at 48 kHz: 48000 frames, 96000 bytes of 16-bit PCM. */
    private fun oneSecond() = ClipTiming(sampleRate, channels, durationMs = 1_000)

    @Test
    fun `presentation times come from the sample count, not the wall clock`() {
        val timing = oneSecond()
        val chunk = 960 * bytesPerFrame // 20 ms

        assertEquals(0L, timing.take(chunk)!!.presentationTimeUs)
        assertEquals(20_000L, timing.take(chunk)!!.presentationTimeUs)
        assertEquals(40_000L, timing.take(chunk)!!.presentationTimeUs)
    }

    @Test
    fun `the clip stops at exactly the requested duration`() {
        val timing = oneSecond()
        var total = 0
        var slices = 0
        while (!timing.isComplete) {
            val slice = timing.take(960 * bytesPerFrame) ?: break
            total += slice.byteCount
            slices++
        }

        assertEquals("48000 frames", 96_000, total)
        assertEquals(50, slices)
        assertTrue(timing.isComplete)
    }

    @Test
    fun `the final chunk is trimmed and flagged end of stream`() {
        val timing = ClipTiming(sampleRate, channels, durationMs = 10) // 480 frames = 960 bytes
        val huge = 100_000

        val slice = timing.take(huge)!!
        assertEquals(960, slice.byteCount)
        assertTrue("reaching the target ends the stream", slice.endOfStream)
        assertTrue(timing.isComplete)
    }

    @Test
    fun `nothing is taken once the clip is complete`() {
        val timing = ClipTiming(sampleRate, channels, durationMs = 10)
        timing.take(100_000)

        assertNull(timing.take(100_000))
    }

    @Test
    fun `only whole frames are taken, and a sub-frame offering yields nothing`() {
        val timing = oneSecond()

        assertNull("one byte is less than a frame", timing.take(1))
        val slice = timing.take(3)!! // one whole 2-byte frame, odd byte dropped
        assertEquals(2, slice.byteCount)
        assertFalse(slice.endOfStream)
    }

    @Test
    fun `stereo counts frames across both channels`() {
        val timing = ClipTiming(sampleRate, channels = 2, durationMs = 1_000) // 48000 frames, 192000 bytes
        var total = 0
        while (!timing.isComplete) {
            total += (timing.take(1_920 * 2) ?: break).byteCount
        }

        assertEquals(192_000, total)
    }
}
