package com.dcontrol.client.feature.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.protocol.RtcKinds
import com.dcontrol.client.core.websocket.protocol.RtcSignal
import com.dcontrol.client.core.websocket.protocol.ScreenStartRequest
import com.dcontrol.client.feature.screen.domain.ScreenController
import com.dcontrol.client.feature.screen.domain.ScreenScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val consent = FakeConsentFlow()
    private val holder = FakeEngineHolder()
    private var granted = emptySet<String>()
    private val permissions = RuntimePermissions { it in granted }

    private fun controller(bridge: AccessibilityBridge) = ScreenController(
        context = context,
        bridge = bridge,
        events = RecordingEvents(),
        permissions = permissions,
        consent = consent,
        engineFactory = holder.factory(),
        scope = ScreenScope(),
    )

    private fun request() = ScreenStartRequest(
        sessionId = "s1",
        viewer = "Pat",
        quality = "MEDIUM",
        audio = true,
        control = true,
        iceServers = emptyList(),
    )

    @Test
    fun `opens consent directly when accessibility is bound`() {
        val bridge = AccessibilityBridge().apply { register(NoopActions()) }
        val result = controller(bridge).start(request())

        assertEquals("s1", consent.launched)
        assertNull(consent.requested)
        assertTrue("control available when bound", result.control)
    }

    @Test
    fun `posts a request notification when accessibility is off`() {
        val result = controller(AccessibilityBridge()).start(request())

        assertEquals("s1" to "Pat", consent.requested)
        assertNull(consent.launched)
        assertFalse("control unavailable when unbound", result.control)
    }

    @Test
    fun `audio is only offered when RECORD_AUDIO is granted`() {
        assertFalse(controller(AccessibilityBridge()).start(request()).audio)

        granted = setOf(Manifest.permission.RECORD_AUDIO)
        assertTrue(controller(AccessibilityBridge()).start(request()).audio)
    }

    @Test
    fun `after consent the browser answer and stop reach the running session and end capture`() {
        val bridge = AccessibilityBridge().apply { register(NoopActions()) }
        val controller = controller(bridge)
        controller.start(request())
        // Consent clears the pending request; the session id must still match afterwards.
        controller.onProjectionResult(Intent())
        eventually { holder.engine.started }

        controller.onAnswer(RtcSignal("other", RtcKinds.ANSWER, "wrong-session"))
        controller.onAnswer(RtcSignal("s1", RtcKinds.ANSWER, "answer-sdp"))
        eventually { holder.engine.answered == "answer-sdp" }

        controller.onStop("s1")
        eventually { holder.engine.closed }
        assertEquals("the capture service stops with the session", 1, consent.captureStops)
    }

    /** The controller runs sessions on its own dispatcher; wait for the effect instead of a fixed sleep. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within 5 s" }
            Thread.sleep(10)
        }
    }

    private class NoopActions : ControlActions {
        override fun down(x: Float, y: Float) = Unit
        override fun move(x: Float, y: Float) = Unit
        override fun up(x: Float, y: Float) = Unit
        override fun wheel(x: Float, y: Float, dy: Float) = Unit
        override fun navKey(key: NavKey) = true
        override fun text(value: String) = true
        override fun edit(key: EditKey) = true
    }
}
