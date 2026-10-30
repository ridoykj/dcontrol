package com.dcontrol.client.feature.screen

import com.dcontrol.client.feature.screen.rtc.GatheringTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatheringTrackerTest {

    private var clock = 1_000L
    private val tracker = GatheringTracker(settleMs = 200, now = { clock })

    @Test
    fun `complete before any candidate is not settled`() {
        tracker.onComplete()
        clock += 1_000

        assertFalse(tracker.isSettled())
    }

    @Test
    fun `early complete settles once candidates stop arriving`() {
        tracker.onComplete()
        tracker.onCandidate()
        clock += 100
        tracker.onCandidate()
        clock += 150
        assertFalse("a candidate arrived 150 ms ago", tracker.isSettled())

        clock += 50
        assertTrue(tracker.isSettled())
    }

    @Test
    fun `candidates without complete are not settled`() {
        tracker.onCandidate()
        clock += 1_000

        assertFalse(tracker.isSettled())
    }

    @Test
    fun `normal order settles after the quiet period`() {
        tracker.onCandidate()
        clock += 200
        tracker.onComplete()

        assertTrue(tracker.isSettled())
    }
}
