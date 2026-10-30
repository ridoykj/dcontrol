package com.dcontrol.client.feature.mic

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.MicCaptureRequest
import com.dcontrol.client.feature.mic.domain.MicSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicSessionTest {

    private val events = RecordingEvents()
    private val holder = FakeEngineHolder()
    private val uploader = RecordingUploader()
    private var ended = 0

    private fun session(scope: CoroutineScope, capture: FakeMicCapture = FakeMicCapture()) = MicSession(
        sessionId = "s1",
        iceServers = emptyList(),
        capture = capture,
        factory = holder.factory(),
        events = events,
        uploader = uploader,
        scope = scope,
        onEnded = { ended++ },
    )

    private suspend fun TestScope.live(capture: FakeMicCapture = FakeMicCapture()): MicSession {
        val session = session(this, capture)
        session.begin()
        advanceUntilIdle()
        session.onLive()
        return session
    }

    private fun states() = events.of(MessageTypes.MIC_STATE).map { it["state"]!!.jsonPrimitive.content }

    private fun clip(mediaId: String = "m-1", durationMs: Int = 10_000) = MicCaptureRequest("s1", mediaId, durationMs)

    @Test
    fun `begin sends STARTING, then an audio-only OFFER without a data channel`() = runTest {
        val capture = FakeMicCapture()
        session(this, capture).begin()
        advanceUntilIdle()

        assertEquals(listOf(CameraStates.STARTING), states())
        assertTrue(holder.engine.started)
        val config = holder.config!!
        assertFalse("microphone sessions have no control channel", config.control)
        assertTrue(config.audio)
        assertFalse("the source carries no video", config.source.hasVideo)
        assertTrue(config.source === capture.source)
        val signal = events.of(MessageTypes.MIC_SIGNAL).single()
        assertEquals("s1", signal["sessionId"]!!.jsonPrimitive.content)
        assertEquals("OFFER", signal["kind"]!!.jsonPrimitive.content)
        assertEquals("offer-sdp", signal["sdp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `LIVE is reported once the engine connects`() = runTest {
        live()

        val state = events.of(MessageTypes.MIC_STATE).last()
        assertEquals(CameraStates.LIVE, state["state"]!!.jsonPrimitive.content)
        assertNull("reason is only set with ENDED", state["reason"])
    }

    @Test
    fun `a failed connection ends with ERROR`() = runTest {
        val session = live()
        session.onFailed()

        val endedState = events.of(MessageTypes.MIC_STATE).last()
        assertEquals(CameraStates.ENDED, endedState["state"]!!.jsonPrimitive.content)
        assertEquals(CameraReasons.ERROR, endedState["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `answer is applied to the engine`() = runTest {
        val session = live()
        session.acceptAnswer("answer-sdp")
        advanceUntilIdle()

        assertEquals("answer-sdp", holder.engine.answered)
    }

    @Test
    fun `capture before LIVE is a CONFLICT`() = runTest {
        val session = session(this)
        session.begin()
        advanceUntilIdle()

        assertEquals(ErrorCodes.CONFLICT, expectError { session.capture(clip()) }.code)
    }

    @Test
    fun `a clip is recorded, uploaded, its metadata returned and the temp file removed`() = runTest {
        val capture = FakeMicCapture()
        val session = live(capture)

        val result = session.capture(clip("m-7", durationMs = 5_000))

        assertEquals(listOf(5_000), capture.clips)
        assertEquals(5_000, result.durationMs)
        assertEquals("m-7", uploader.uploads.single().first)
        assertEquals("audio/mp4", uploader.uploads.single().second.mime)
        assertFalse(capture.files.single().exists())
    }

    @Test
    fun `a second clip while one records is a CONFLICT`() = runTest {
        val capture = FakeMicCapture().apply { gate = CompletableDeferred() }
        val session = live(capture)
        val first = async { session.capture(clip("m-1")) }
        advanceUntilIdle()

        assertEquals(ErrorCodes.CONFLICT, expectError { session.capture(clip("m-2")) }.code)

        capture.gate!!.complete(Unit)
        first.await()
        assertEquals(1, uploader.uploads.size)
    }

    @Test
    fun `a failing recorder answers INTERNAL and frees the session for the next clip`() = runTest {
        val capture = FakeMicCapture().apply { failNext = IllegalStateException("recorder closed") }
        val session = live(capture)

        assertEquals(ErrorCodes.INTERNAL, expectError { session.capture(clip("m-1")) }.code)
        session.capture(clip("m-2"))
        assertEquals("m-2", uploader.uploads.single().first)
    }

    @Test
    fun `controller stop ends silently and releases microphone and engine`() = runTest {
        val capture = FakeMicCapture()
        val session = live(capture)
        val before = events.count(MessageTypes.MIC_STATE)

        session.stopSilently()

        assertTrue(capture.closed)
        assertTrue(holder.engine.closed)
        assertEquals("no ENDED is sent for a controller stop", before, events.count(MessageTypes.MIC_STATE))
        assertEquals(1, ended)
    }

    @Test
    fun `stop on phone reports ENDED with STOPPED_ON_PHONE once`() = runTest {
        val session = live()
        session.stopOnPhone()
        session.onFailed()

        val endedStates = events.of(MessageTypes.MIC_STATE).filter { it["state"]!!.jsonPrimitive.content == CameraStates.ENDED }
        assertEquals(1, endedStates.size)
        assertEquals(CameraReasons.STOPPED_ON_PHONE, endedStates.single()["reason"]!!.jsonPrimitive.content)
        assertEquals(1, ended)
    }

    @Test
    fun `a stop during setup never leaves an engine running`() = runTest {
        val session = session(this)
        session.begin()
        session.stopSilently()
        advanceUntilIdle()

        assertTrue(holder.engines.all { it.closed })
        assertEquals(0, events.count(MessageTypes.MIC_SIGNAL))
    }

    private suspend fun expectError(block: suspend () -> Unit): CommandException {
        try {
            block()
        } catch (e: CommandException) {
            return e
        }
        fail("Expected CommandException")
        throw AssertionError()
    }
}
