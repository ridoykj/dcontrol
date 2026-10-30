package com.dcontrol.client.feature.screen

import android.content.Intent
import com.dcontrol.client.core.accessibility.AccessibilityBridge
import com.dcontrol.client.core.accessibility.ControlActions
import com.dcontrol.client.core.accessibility.EditKey
import com.dcontrol.client.core.accessibility.NavKey
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.ScreenPreset
import com.dcontrol.client.core.websocket.protocol.ScreenReasons
import com.dcontrol.client.core.websocket.protocol.ScreenStates
import com.dcontrol.client.feature.screen.domain.ScreenFrame
import com.dcontrol.client.feature.screen.domain.ScreenSession
import com.dcontrol.client.feature.screen.rtc.RtcConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenSessionTest {

    private val events = RecordingEvents()
    private val bridge = AccessibilityBridge()
    private val holder = FakeEngineHolder()

    private fun config(control: Boolean) = RtcConfig(
        sessionId = "s1",
        resultData = Intent(),
        preset = ScreenPreset.of("MEDIUM"),
        audio = false,
        control = control,
        iceServers = emptyList(),
    )

    private fun session(scope: kotlinx.coroutines.CoroutineScope, control: Boolean = false) = ScreenSession(
        sessionId = "s1",
        config = config(control),
        factory = holder.factory(),
        events = events,
        bridge = bridge,
        frame = { ScreenFrame(1080, 2400, 0) },
        scope = scope,
    )

    @Test
    fun `begin sends STARTING then the offer after gathering`() = runTest {
        session(this).begin()
        advanceUntilIdle()

        assertEquals(ScreenStates.STARTING, events.first(MessageTypes.SCREEN_STATE)["state"]!!.jsonPrimitive.content)
        assertTrue(holder.engine.started)
        val signal = events.first(MessageTypes.RTC_SIGNAL)
        assertEquals("OFFER", signal["kind"]!!.jsonPrimitive.content)
        assertEquals("offer-sdp", signal["sdp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `onLive reports LIVE and sends size and control over the channel`() = runTest {
        val session = session(this, control = false)
        session.begin()
        advanceUntilIdle()
        session.onLive()
        advanceUntilIdle()

        assertTrue(events.types().contains(MessageTypes.SCREEN_STATE))
        assertTrue(holder.engine.controls.any { it.contains("\"t\":\"size\"") })
        assertTrue(holder.engine.controls.any { it.contains("\"t\":\"control\"") })

        session.stopSilently() // cancel the availability collector so the test scope completes
        advanceUntilIdle()
    }

    @Test
    fun `answer is applied to the engine`() = runTest {
        val session = session(this)
        session.begin()
        advanceUntilIdle()
        session.acceptAnswer("answer-sdp")
        advanceUntilIdle()

        assertEquals("answer-sdp", holder.engine.answered)
    }

    @Test
    fun `controller stop ends silently`() = runTest {
        val session = session(this)
        session.begin()
        advanceUntilIdle()
        val before = events.count(MessageTypes.SCREEN_STATE)
        session.stopSilently()
        advanceUntilIdle()

        assertTrue(holder.engine.closed)
        // No ENDED state is sent back for a controller stop.
        assertEquals(before, events.count(MessageTypes.SCREEN_STATE))
    }

    @Test
    fun `stop on phone reports ENDED with STOPPED_ON_PHONE`() = runTest {
        val session = session(this)
        session.begin()
        advanceUntilIdle()
        session.stopOnPhone()
        advanceUntilIdle()

        val ended = events.sent.last { it.first == MessageTypes.SCREEN_STATE }.second
        assertEquals(ScreenStates.ENDED, ended["state"]!!.jsonPrimitive.content)
        assertEquals(ScreenReasons.STOPPED_ON_PHONE, ended["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `losing accessibility mid-session pushes a control update`() = runTest {
        val actions = NoopActions()
        bridge.register(actions)
        val session = session(this, control = true)
        session.begin()
        advanceUntilIdle()
        session.onLive()
        advanceUntilIdle()
        val controlsBefore = holder.engine.controls.size

        bridge.unregister(actions)
        advanceUntilIdle()

        assertTrue(holder.engine.controls.size > controlsBefore)
        assertTrue(holder.engine.controls.last().contains("\"available\":false"))

        session.stopSilently() // cancel the availability collector so the test scope completes
        advanceUntilIdle()
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
