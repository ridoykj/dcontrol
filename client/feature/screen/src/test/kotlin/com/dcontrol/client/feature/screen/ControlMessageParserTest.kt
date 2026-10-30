package com.dcontrol.client.feature.screen

import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.feature.screen.control.ControlMessage
import com.dcontrol.client.feature.screen.control.ControlMessageParser
import com.dcontrol.client.feature.screen.control.ControlRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessageParserTest {

    private val parser = ControlMessageParser()

    @Test
    fun `parses pointer, wheel, key, text and edit`() {
        assertEquals(ControlMessage.Down(0.5f, 0.25f), parser.parse("""{"t":"down","x":0.5,"y":0.25}"""))
        assertEquals(ControlMessage.Wheel(0.5f, 0.5f, 120f), parser.parse("""{"t":"wheel","x":0.5,"y":0.5,"dy":120}"""))
        assertEquals(ControlMessage.Key(NavKey.LOCK), parser.parse("""{"t":"key","k":"lock"}"""))
        assertEquals(ControlMessage.Text("hi"), parser.parse("""{"t":"text","s":"hi"}"""))
        assertEquals(ControlMessage.Edit(EditKey.ENTER), parser.parse("""{"t":"edit","k":"enter"}"""))
    }

    @Test
    fun `rejects unknown type and unknown keys`() {
        assertNull(parser.parse("""{"t":"teleport","x":0.1,"y":0.1}"""))
        assertNull(parser.parse("""{"t":"key","k":"selfdestruct"}"""))
        assertNull(parser.parse("""{"t":"edit","k":"delete"}"""))
    }

    @Test
    fun `rejects out-of-range coordinates and wheel`() {
        assertNull(parser.parse("""{"t":"down","x":1.5,"y":0.1}"""))
        assertNull(parser.parse("""{"t":"move","x":0.1,"y":-0.2}"""))
        assertNull(parser.parse("""{"t":"wheel","x":0.5,"y":0.5,"dy":99999}"""))
    }

    @Test
    fun `rejects empty and oversized text`() {
        assertNull(parser.parse("""{"t":"text","s":""}"""))
        assertNull(parser.parse("""{"t":"text","s":"${"a".repeat(257)}"}"""))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(parser.parse("not json"))
        assertNull(parser.parse("""{"t":"down"}"""))
    }

    private class FakeActions : ControlActions {
        val downs = mutableListOf<Pair<Float, Float>>()
        var navKeys = 0
        override fun down(x: Float, y: Float) { downs += x to y }
        override fun move(x: Float, y: Float) = Unit
        override fun up(x: Float, y: Float) = Unit
        override fun wheel(x: Float, y: Float, dy: Float) = Unit
        override fun navKey(key: NavKey): Boolean { navKeys++; return true }
        override fun text(value: String) = true
        override fun edit(key: EditKey) = true
    }

    @Test
    fun `router applies valid messages`() {
        val actions = FakeActions()
        val router = ControlRouter(actions = { actions }, onExcessiveDrops = {})
        router.handle("""{"t":"down","x":0.5,"y":0.5}""")
        router.handle("""{"t":"key","k":"back"}""")

        assertEquals(listOf(0.5f to 0.5f), actions.downs)
        assertEquals(1, actions.navKeys)
        assertEquals(0, router.drops)
    }

    @Test
    fun `router closes the channel after 20 drops`() {
        var closed = false
        val router = ControlRouter(actions = { FakeActions() }, onExcessiveDrops = { closed = true })
        repeat(19) { router.handle("garbage") }
        assertFalse(closed)
        router.handle("garbage")
        assertTrue(closed)
        assertEquals(20, router.drops)
    }

    @Test
    fun `router ignores input while unbound without counting a drop`() {
        var closed = false
        val router = ControlRouter(actions = { null }, onExcessiveDrops = { closed = true })
        router.handle("""{"t":"down","x":0.5,"y":0.5}""")
        assertEquals(0, router.drops)
        assertFalse(closed)
    }
}
