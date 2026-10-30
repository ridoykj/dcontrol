package com.dcontrol.client.core.websocket

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exponential backoff with full jitter: delay = random(0, min(cap, base * 2^attempt)).
 */
class Backoff(
    private val base: Duration = 1.seconds,
    private val cap: Duration = 60.seconds,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun ceilingFor(attempt: Int): Duration {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT)
        val raw = base.inWholeMilliseconds * (1L shl exponent)
        return minOf(raw, cap.inWholeMilliseconds).milliseconds
    }

    fun nextDelay(): Duration {
        val ceiling = ceilingFor(attempt).inWholeMilliseconds
        attempt++
        return (random.nextDouble() * ceiling).toLong().milliseconds
    }

    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_EXPONENT = 30
    }
}
