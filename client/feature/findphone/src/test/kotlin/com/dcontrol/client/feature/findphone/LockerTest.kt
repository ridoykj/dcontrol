package com.dcontrol.client.feature.findphone

import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.feature.findphone.domain.Locker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LockerTest {

    private val notifier = FakeLockNotifier()

    @Test
    fun `lock without the service answers PERMISSION_REQUIRED accessibility`() {
        val locker = Locker(AccessibilityBridge(), notifier)
        try {
            locker.lock("Lost phone, please call", null)
            fail("expected CommandException")
        } catch (e: CommandException) {
            assertEquals(ErrorCodes.PERMISSION_REQUIRED, e.code)
            assertEquals(Permissions.ACCESSIBILITY, e.permission)
        }
    }

    @Test
    fun `lock with the service locks and posts the notification`() {
        val bridge = AccessibilityBridge()
        val actions = RecordingActions()
        bridge.register(actions)
        val locker = Locker(bridge, notifier)

        locker.lock("Lost phone", "+8801000000")

        assertTrue(actions.navKeys.contains(NavKey.LOCK))
        assertEquals("Lost phone" to "+8801000000", notifier.last)
    }
}
