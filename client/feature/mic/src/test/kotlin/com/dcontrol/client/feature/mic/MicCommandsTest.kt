package com.dcontrol.client.feature.mic

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.rtc.MicrophoneLock
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.mic.data.MicCommands
import com.dcontrol.client.feature.mic.data.MicUploader
import com.dcontrol.client.feature.mic.domain.MicController
import com.dcontrol.client.feature.mic.domain.MicScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicCommandsTest {

    private val codec = ProtocolCodec()
    private val server = MockWebServer().apply { start() }
    private val events = RecordingEvents()
    private val consent = FakeConsentFlow()
    private val holder = FakeEngineHolder()
    private val captures = FakeMicCaptureFactory()
    private val granted = mutableSetOf(Manifest.permission.RECORD_AUDIO)
    private val microphone = MicrophoneLock()
    private val http = DeviceHttpClient(
        OkHttpClient(),
        object : CredentialStore {
            override val credentials: StateFlow<StoredCredentials?> =
                MutableStateFlow(StoredCredentials(server.url("/").toString(), "dev-1", "secret", "Pixel"))
            override suspend fun save(credentials: StoredCredentials) = Unit
            override suspend fun clear() = Unit
        },
    )
    private val controller = MicController(
        events = events,
        permissions = RuntimePermissions { it in granted },
        consent = consent,
        engineFactory = holder.factory(),
        captureFactory = captures,
        uploader = MicUploader(http),
        microphone = microphone,
        scope = MicScope(),
    )
    private val commands = MicCommands(codec, controller)

    @After
    fun tearDown() = server.close()

    private fun start(payload: String = START) = runBlocking { commands.start.handle(command(MessageTypes.MIC_START, payload))!! }

    private fun capture(payload: String) = runBlocking { commands.capture.handle(command(MessageTypes.MIC_CAPTURE, payload))!! }

    /** Start, approve on the phone, and let the engine connect. */
    private fun liveSession() {
        start()
        assertTrue(controller.onConsentGranted("s1"))
        eventually { holder.engines.isNotEmpty() && holder.engine.started }
        holder.listener!!.onLive()
    }

    @Test
    fun `without RECORD_AUDIO the start is refused and nothing is shown`() {
        granted.clear()

        val error = expectError { start() }

        assertEquals(ErrorCodes.PERMISSION_REQUIRED, error.code)
        assertEquals(Permissions.MICROPHONE, error.permission)
        assertNull(consent.launched)
        assertNull(consent.requested)
        assertNull(controller.pendingSession)
    }

    @Test
    fun `a mic start while a camera session holds the microphone is a CONFLICT`() {
        val cameraHold = microphone.tryAcquire(MicrophoneLock.CAMERA)!!

        assertEquals(ErrorCodes.CONFLICT, expectError { start() }.code)
        assertNull("nothing is shown while the microphone is busy", consent.requested)
        assertNull(controller.pendingSession)

        cameraHold.release()
        start()
        assertEquals(MicrophoneLock.MIC, microphone.owner)
    }

    @Test
    fun `in the background the owner is asked through the request notification`() {
        start()

        assertEquals("s1" to "Pat", consent.requested)
        assertNull(consent.launched)
    }

    @Test
    fun `in the foreground the consent prompt opens directly`() {
        consent.foreground = true
        start()

        assertEquals("s1", consent.launched)
        assertNull(consent.requested)
    }

    @Test
    fun `the start result always reports audio`() {
        assertEquals("true", start()["audio"]!!.jsonPrimitive.content)
    }

    @Test
    fun `AWAITING_CONSENT follows the result and nothing is captured before approval`() {
        start()

        eventually { events.count(MessageTypes.MIC_STATE) == 1 }
        val state = events.of(MessageTypes.MIC_STATE).single()
        assertEquals(CameraStates.AWAITING_CONSENT, state["state"]!!.jsonPrimitive.content)
        assertTrue(captures.created.isEmpty())
        assertTrue(holder.engines.isEmpty())
    }

    @Test
    fun `invalid starts are VALIDATION errors`() {
        val longViewer = START.replace("\"Pat\"", "\"${"x".repeat(101)}\"")

        assertEquals(ErrorCodes.VALIDATION, expectError { start(longViewer) }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { start("""{"viewer":"Pat","iceServers":[]}""") }.code)
    }

    @Test
    fun `declining reports ENDED DENIED and gives the microphone back`() {
        start()
        controller.onConsentDenied("s1")

        val ended = events.of(MessageTypes.MIC_STATE).last()
        assertEquals(CameraStates.ENDED, ended["state"]!!.jsonPrimitive.content)
        assertEquals(CameraReasons.DENIED, ended["reason"]!!.jsonPrimitive.content)
        assertNull(microphone.owner)
    }

    @Test
    fun `capture needs an approved, live session`() {
        start()

        assertEquals(ErrorCodes.CONFLICT, expectError { capture(clip()) }.code)
    }

    @Test
    fun `capture payloads are validated`() {
        liveSession()

        assertEquals(ErrorCodes.VALIDATION, expectError { capture(clip(mediaId = "../etc")) }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","durationMs":999}""") }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","durationMs":300001}""") }.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a clip is uploaded as audio mp4 before the result`() {
        liveSession()
        server.enqueue(MockResponse.Builder().code(204).build())

        val result = capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","durationMs":10000}""")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/device-api/mic/$MEDIA_ID", request.url.encodedPath)
        assertEquals("audio/mp4", request.headers["Content-Type"])
        assertEquals("Device dev-1.secret", request.headers["Authorization"])
        assertEquals("10000", result["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upload refusals map to command errors`() {
        liveSession()
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(413).build())
        server.enqueue(MockResponse.Builder().code(415).build())

        assertEquals(ErrorCodes.NOT_FOUND, expectError { capture(clip()) }.code)
        assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, expectError { capture(clip()) }.code)
        assertEquals(ErrorCodes.UNSUPPORTED, expectError { capture(clip()) }.code)
    }

    @Test
    fun `the answer and stop reach the running session, and stop ends capture silently and frees the microphone`() {
        liveSession()
        val before = events.count(MessageTypes.MIC_STATE)

        runBlocking {
            commands.signal.handle(event(MessageTypes.MIC_SIGNAL, """{"sessionId":"other","kind":"ANSWER","sdp":"wrong"}"""))
            commands.signal.handle(event(MessageTypes.MIC_SIGNAL, """{"sessionId":"s1","kind":"OFFER","sdp":"wrong"}"""))
            commands.signal.handle(event(MessageTypes.MIC_SIGNAL, """{"sessionId":"s1","kind":"ANSWER","sdp":"answer-sdp"}"""))
        }
        eventually { holder.engine.answered == "answer-sdp" }

        runBlocking { commands.stop.handle(event(MessageTypes.MIC_STOP, """{"sessionId":"s1"}""")) }

        assertTrue(holder.engine.closed)
        assertTrue(captures.last.closed)
        assertEquals(1, consent.captureStops)
        assertEquals(before, events.count(MessageTypes.MIC_STATE))
        assertNull(microphone.owner)
    }

    @Test
    fun `Stop on the phone ends the session with STOPPED_ON_PHONE`() {
        liveSession()

        controller.stopFromPhone()

        val ended = events.of(MessageTypes.MIC_STATE).last()
        assertEquals(CameraReasons.STOPPED_ON_PHONE, ended["reason"]!!.jsonPrimitive.content)
        assertTrue(captures.last.closed)
        assertNull(microphone.owner)
    }

    @Test
    fun `going offline ends the session`() {
        liveSession()

        events.connection.value = ConnectionState.Reconnecting(kotlin.time.Duration.ZERO, "network lost")

        eventually { holder.engine.closed }
        assertTrue(captures.last.closed)
        assertEquals(1, consent.captureStops)
        assertNull(microphone.owner)
    }

    @Test
    fun `a new start replaces the running session and keeps the microphone`() {
        liveSession()
        val first = holder.engine

        start(START.replace("\"s1\"", "\"s2\""))

        assertTrue(first.closed)
        assertEquals("s2", controller.pendingSession?.sessionId)
        assertEquals(MicrophoneLock.MIC, microphone.owner)
    }

    private fun clip(mediaId: String = MEDIA_ID) = """{"sessionId":"s1","mediaId":"$mediaId","durationMs":10000}"""

    private fun command(type: String, payload: String) =
        Envelope(type = type, requestId = "r-1", payload = Json.parseToJsonElement(payload).jsonObject)

    private fun event(type: String, payload: String) =
        Envelope(type = type, payload = Json.parseToJsonElement(payload).jsonObject)

    private inline fun expectError(block: () -> Unit): CommandException {
        try {
            block()
        } catch (e: CommandException) {
            return e
        }
        fail("Expected CommandException")
        throw AssertionError()
    }

    /** The controller runs sessions on its own dispatcher; wait for the effect instead of a fixed sleep. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within 5 s" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val MEDIA_ID = "0192f3a4-5b6c-7d8e-9f01-23456789abcd"
        const val START = """{"sessionId":"s1","viewer":"Pat","iceServers":[]}"""
    }
}
