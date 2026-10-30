package com.dcontrol.client.core.websocket

import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    @Test
    fun `ceiling doubles from one second and caps at sixty`() {
        val backoff = Backoff()
        val ceilings = (0..8).map { backoff.ceilingFor(it) }
        assertEquals(
            listOf(1, 2, 4, 8, 16, 32, 60, 60, 60).map { it.seconds },
            ceilings,
        )
    }

    @Test
    fun `huge attempt numbers do not overflow`() {
        assertEquals(60.seconds, Backoff().ceilingFor(Int.MAX_VALUE))
    }

    @Test
    fun `delay uses full jitter below the ceiling`() {
        val backoff = Backoff(random = FixedRandom(0.5))
        assertEquals(500.milliseconds, backoff.nextDelay())
        assertEquals(1000.milliseconds, backoff.nextDelay())
        assertEquals(2000.milliseconds, backoff.nextDelay())
        assertEquals(3, backoff.attempt)
    }

    @Test
    fun `random delays never exceed the ceiling`() {
        val backoff = Backoff(random = Random(42))
        repeat(20) { attempt ->
            val delay = backoff.nextDelay()
            assertTrue(delay >= 0.milliseconds)
            assertTrue(delay <= backoff.ceilingFor(attempt))
        }
    }

    @Test
    fun `reset starts over from the first attempt`() {
        val backoff = Backoff(random = FixedRandom(0.99))
        repeat(5) { backoff.nextDelay() }
        backoff.reset()
        assertEquals(0, backoff.attempt)
        assertEquals(990.milliseconds, backoff.nextDelay())
    }
}
