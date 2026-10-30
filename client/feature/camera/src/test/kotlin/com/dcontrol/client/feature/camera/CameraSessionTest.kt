package com.dcontrol.client.feature.camera

import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.CameraCaptureRequest
import com.dcontrol.client.core.websocket.protocol.CameraFacings
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.CaptureKinds
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.feature.camera.domain.CameraSession
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
class CameraSessionTest {

    private val events = RecordingEvents()
    private val holder = FakeEngineHolder()
    private val uploader = RecordingUploader()
    private var ended = 0

    private fun session(scope: CoroutineScope, capture: FakeCapture = FakeCapture(), audio: Boolean = true) = CameraSession(
        sessionId = "s1",
        requestedFacing = CameraFacings.FRONT,
        audio = audio,
        iceServers = emptyList(),
        capture = capture,
        factory = holder.factory(),
        events = events,
        uploader = uploader,
        scope = scope,
        onEnded = { ended++ },
    )

    private suspend fun TestScope.live(capture: FakeCapture = FakeCapture()): CameraSession {
        val session = session(this, capture)
        session.begin()
        advanceUntilIdle()
        session.onLive()
        return session
    }

    private fun states() = events.of(MessageTypes.CAMERA_STATE).map { it["state"]!!.jsonPrimitive.content }

    private fun photo(mediaId: String = "m-1") = CameraCaptureRequest("s1", mediaId, CaptureKinds.PHOTO)

    @Test
    fun `begin opens the camera, sends STARTING, then the OFFER without a data channel`() = runTest {
        val capture = FakeCapture()
        session(this, capture).begin()
        advanceUntilIdle()

        assertEquals(CameraFacings.FRONT, capture.opened)
        assertEquals(listOf(CameraStates.STARTING), states())
        assertTrue(holder.engine.started)
        val config = holder.config!!
        assertFalse("camera sessions have no control channel", config.control)
        assertTrue(config.audio)
        assertTrue(config.source === capture.source)
        val signal = events.of(MessageTypes.CAMERA_SIGNAL).single()
        assertEquals("s1", signal["sessionId"]!!.jsonPrimitive.content)
        assertEquals("OFFER", signal["kind"]!!.jsonPrimitive.content)
        assertEquals("offer-sdp", signal["sdp"]!!.jsonPrimitive.content)
        assertEquals(0, events.count(MessageTypes.RTC_SIGNAL))
    }

    @Test
    fun `LIVE carries the bound lens and video availability`() = runTest {
        live(FakeCapture(lensSet = setOf(CameraFacings.BACK), video = false))

        val state = events.of(MessageTypes.CAMERA_STATE).last()
        assertEquals(CameraStates.LIVE, state["state"]!!.jsonPrimitive.content)
        assertEquals(CameraFacings.BACK, state["facing"]!!.jsonPrimitive.content)
        assertEquals("false", state["video"]!!.jsonPrimitive.content)
        assertNull("reason is only set with ENDED", state["reason"])
    }

    @Test
    fun `a camera that won't open ends with ERROR before any engine exists`() = runTest {
        session(this, FakeCapture(openError = IllegalStateException("busy"))).begin()
        advanceUntilIdle()

        assertTrue(holder.engines.isEmpty())
        val endedState = events.of(MessageTypes.CAMERA_STATE).last()
        assertEquals(CameraStates.ENDED, endedState["state"]!!.jsonPrimitive.content)
        assertEquals(CameraReasons.ERROR, endedState["reason"]!!.jsonPrimitive.content)
        assertEquals(1, ended)
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

        assertEquals(ErrorCodes.CONFLICT, expectError { session.capture(photo()) }.code)
    }

    @Test
    fun `photo is uploaded, then its metadata returned and the temp file removed`() = runTest {
        val capture = FakeCapture()
        val session = live(capture)

        val result = session.capture(photo("m-7"))

        assertEquals(4000, result.width)
        assertEquals(3000, result.height)
        assertNull(result.durationMs)
        assertEquals("m-7", uploader.uploads.single().first)
        assertEquals("image/jpeg", uploader.uploads.single().second.mime)
        assertFalse(capture.files.single().exists())
    }

    @Test
    fun `video records for the requested duration`() = runTest {
        val capture = FakeCapture()
        val session = live(capture)

        val result = session.capture(CameraCaptureRequest("s1", "m-1", CaptureKinds.VIDEO, durationMs = 5_000))

        assertEquals(listOf(5_000), capture.clips)
        assertEquals(5_000, result.durationMs)
        assertEquals("video/mp4", uploader.uploads.single().second.mime)
    }

    @Test
    fun `video without a bound VideoCapture is UNSUPPORTED`() = runTest {
        val session = live(FakeCapture(video = false))

        val error = expectError { session.capture(CameraCaptureRequest("s1", "m-1", CaptureKinds.VIDEO, durationMs = 1_000)) }
        assertEquals(ErrorCodes.UNSUPPORTED, error.code)
    }

    @Test
    fun `a second capture or a switch while one runs is a CONFLICT`() = runTest {
        val capture = FakeCapture().apply { gate = CompletableDeferred() }
        val session = live(capture)
        val first = async { session.capture(photo("m-1")) }
        advanceUntilIdle()

        assertEquals(ErrorCodes.CONFLICT, expectError { session.capture(photo("m-2")) }.code)
        assertEquals(ErrorCodes.CONFLICT, expectError { session.switch(CameraFacings.BACK) }.code)

        capture.gate!!.complete(Unit)
        first.await()
        assertEquals(1, uploader.uploads.size)
    }

    @Test
    fun `a failing camera answers INTERNAL and frees the session for the next capture`() = runTest {
        val capture = FakeCapture().apply { failNext = IllegalStateException("camera closed") }
        val session = live(capture)

        assertEquals(ErrorCodes.INTERNAL, expectError { session.capture(photo("m-1")) }.code)
        session.capture(photo("m-2"))
        assertEquals("m-2", uploader.uploads.single().first)
    }

    @Test
    fun `switch rebinds the lens and re-reports LIVE`() = runTest {
        val capture = FakeCapture()
        val session = live(capture)

        assertEquals(CameraFacings.BACK, session.switch(CameraFacings.BACK))

        assertEquals(CameraFacings.BACK, capture.facing)
        val state = events.of(MessageTypes.CAMERA_STATE).last()
        assertEquals(CameraStates.LIVE, state["state"]!!.jsonPrimitive.content)
        assertEquals(CameraFacings.BACK, state["facing"]!!.jsonPrimitive.content)
        assertEquals("the stream stays on one engine", 1, holder.engines.size)
    }

    @Test
    fun `switching to a missing lens is UNSUPPORTED`() = runTest {
        val session = live(FakeCapture(lensSet = setOf(CameraFacings.FRONT)))

        assertEquals(ErrorCodes.UNSUPPORTED, expectError { session.switch(CameraFacings.BACK) }.code)
    }

    @Test
    fun `controller stop ends silently and releases camera and engine`() = runTest {
        val capture = FakeCapture()
        val session = live(capture)
        val before = events.count(MessageTypes.CAMERA_STATE)

        session.stopSilently()

        assertTrue(capture.closed)
        assertTrue(holder.engine.closed)
        assertEquals("no ENDED is sent for a controller stop", before, events.count(MessageTypes.CAMERA_STATE))
        assertEquals(1, ended)
    }

    @Test
    fun `stop on phone reports ENDED with STOPPED_ON_PHONE once`() = runTest {
        val session = live()
        session.stopOnPhone()
        session.onFailed()

        val endedStates = events.of(MessageTypes.CAMERA_STATE).filter { it["state"]!!.jsonPrimitive.content == CameraStates.ENDED }
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
        assertEquals(0, events.count(MessageTypes.CAMERA_SIGNAL))
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
