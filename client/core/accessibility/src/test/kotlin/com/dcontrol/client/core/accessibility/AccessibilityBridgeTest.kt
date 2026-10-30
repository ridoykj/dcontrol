package com.dcontrol.client.core.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityBridgeTest {

    private val bridge = AccessibilityBridge()

    private val actions = object : ControlActions {
        override fun down(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun up(x: Float, y: Float) = Unit
        override fun wheel(x: Float, y: Float, dy: Float) = Unit
        override fun navKey(key: NavKey) = true
        override fun text(value: String) = true
        override fun edit(key: EditKey) = true
    }

    @Test
    fun `starts unbound`() {
        assertFalse(bridge.isBound)
        assertNull(bridge.actions)
        assertFalse(bridge.available.value)
    }

    @Test
    fun `register exposes the actions and marks available`() {
        bridge.register(actions)
        assertTrue(bridge.isBound)
        assertSame(actions, bridge.actions)
        assertTrue(bridge.available.value)
    }

    @Test
    fun `unregister only clears the matching instance`() {
        bridge.register(actions)
        val other = object : ControlActions {
            override fun down(x: Float, y: Float) = Unit
            override fun move(x: Float, y: Float) = Unit
            override fun up(x: Float, y: Float) = Unit
            override fun wheel(x: Float, y: Float, dy: Float) = Unit
            override fun navKey(key: NavKey) = true
            override fun text(value: String) = true
            override fun edit(key: EditKey) = true
        }
        bridge.unregister(other)
        assertTrue("a stale service must not clear the live one", bridge.isBound)

        bridge.unregister(actions)
        assertFalse(bridge.isBound)
        assertFalse(bridge.available.value)
    }

    @Test
    fun `nav and edit keys are distinct`() {
        assertEquals(6, NavKey.entries.size)
        assertEquals(2, EditKey.entries.size)
    }
}
