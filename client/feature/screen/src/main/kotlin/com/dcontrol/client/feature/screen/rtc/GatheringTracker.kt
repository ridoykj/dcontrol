package com.dcontrol.client.feature.screen.rtc

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Decides when non-trickle ICE gathering is done. WebRTC can report COMPLETE before the first candidate (seen on
 * the API 37 emulator), which would send an offer without candidates. Done therefore means: COMPLETE was reported,
 * at least one candidate exists, and no new candidate arrived for [settleMs]. The caller caps the wait.
 */
class GatheringTracker(
    private val settleMs: Long = SETTLE_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val candidates = AtomicInteger()

    @Volatile
    private var complete = false

    @Volatile
    private var lastCandidateAt = 0L

    fun onComplete() {
        complete = true
    }

    fun onCandidate() {
        lastCandidateAt = now()
        candidates.incrementAndGet()
    }

    fun isSettled(): Boolean = complete && candidates.get() > 0 && now() - lastCandidateAt >= settleMs

    suspend fun awaitSettled() {
        while (!isSettled()) delay(POLL_MS)
    }

    companion object {
        const val SETTLE_MS = 200L
        private const val POLL_MS = 25L
    }
}
