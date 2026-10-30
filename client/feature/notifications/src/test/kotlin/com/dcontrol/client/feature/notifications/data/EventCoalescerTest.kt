package com.dcontrol.client.feature.notifications.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventCoalescerTest {

    @Test
    fun `first value is sent at once and bursts collapse to the newest value`() = runTest {
        val sent = mutableListOf<String>()
        val coalescer = EventCoalescer<String>(backgroundScope, 250, { testScheduler.currentTime }) { sent += it }

        coalescer.post("k", "v1")
        coalescer.post("k", "v2")
        coalescer.post("k", "v3")
        assertEquals(listOf("v1"), sent)

        advanceTimeBy(249)
        runCurrent()
        assertEquals(listOf("v1"), sent)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("v1", "v3"), sent)
    }

    @Test
    fun `keys are independent`() = runTest {
        val sent = mutableListOf<String>()
        val coalescer = EventCoalescer<String>(backgroundScope, 250, { testScheduler.currentTime }) { sent += it }

        coalescer.post("a", "a1")
        coalescer.post("b", "b1")

        assertEquals(listOf("a1", "b1"), sent)
    }

    @Test
    fun `remove drops a held value`() = runTest {
        val sent = mutableListOf<String>()
        val coalescer = EventCoalescer<String>(backgroundScope, 250, { testScheduler.currentTime }) { sent += it }

        coalescer.post("k", "v1")
        coalescer.post("k", "v2")
        coalescer.remove("k")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("v1"), sent)
    }

    @Test
    fun `a value after the interval goes out immediately`() = runTest {
        val sent = mutableListOf<String>()
        val coalescer = EventCoalescer<String>(backgroundScope, 250, { testScheduler.currentTime }) { sent += it }

        coalescer.post("k", "v1")
        advanceTimeBy(300)
        coalescer.post("k", "v2")

        assertEquals(listOf("v1", "v2"), sent)
    }
}
