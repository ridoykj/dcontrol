package com.dcontrol.client.feature.screen

import com.dcontrol.client.feature.screen.rtc.ControlOutbox
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlOutboxTest {

    private val delivered = mutableListOf<String>()

    @Test
    fun `messages sent before the channel opens are delivered in order when it opens`() {
        val outbox = ControlOutbox(deliver = { delivered += it })
        outbox.send("size")
        outbox.send("control")
        assertEquals(emptyList<String>(), delivered)

        outbox.onOpen()
        assertEquals(listOf("size", "control"), delivered)

        outbox.send("control-off")
        assertEquals("open channel delivers directly", listOf("size", "control", "control-off"), delivered)
    }

    @Test
    fun `a full outbox drops the oldest message`() {
        val outbox = ControlOutbox(deliver = { delivered += it }, capacity = 2)
        outbox.send("a")
        outbox.send("b")
        outbox.send("c")

        outbox.onOpen()
        assertEquals(listOf("b", "c"), delivered)
    }

    @Test
    fun `closing discards pending messages and buffers again`() {
        val outbox = ControlOutbox(deliver = { delivered += it })
        outbox.send("stale")
        outbox.onClosed()
        outbox.send("later")
        assertEquals(emptyList<String>(), delivered)

        outbox.onOpen()
        assertEquals(listOf("later"), delivered)
    }
}
