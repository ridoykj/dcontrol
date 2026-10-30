package com.dcontrol.client.feature.camera

import android.Manifest
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.protocol.CameraFacings
import com.dcontrol.client.core.websocket.protocol.CameraReasons
import com.dcontrol.client.core.websocket.protocol.CameraStates
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.camera.data.CameraCommands
import com.dcontrol.client.feature.camera.data.CameraUploader
import com.dcontrol.client.feature.camera.domain.CameraController
import com.dcontrol.client.feature.camera.domain.CameraScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCommandsTest {

    private val codec = ProtocolCodec()
    private val server = MockWebServer().apply { start() }
    private val events = RecordingEvents()
    private val consent = FakeConsentFlow()
    private val holder = FakeEngineHolder()
    private val captures = FakeCaptureFactory()
    private val granted = mutableSetOf(Manifest.permission.CAMERA)
    private val http = DeviceHttpClient(
        OkHttpClient(),
        object : CredentialStore {
            override val credentials: StateFlow<StoredCredentials?> =
                MutableStateFlow(StoredCredentials(server.url("/").toString(), "dev-1", "secret", "Pixel"))
            override suspend fun save(credentials: StoredCredentials) = Unit
            override suspend fun clear() = Unit
        },
    )
    private val controller = CameraController(
        events = events,
        permissions = RuntimePermissions { it in granted },
        consent = consent,
        engineFactory = holder.factory(),
        captureFactory = captures,
        uploader = CameraUploader(http),
        scope = CameraScope(),
    )
    private val commands = CameraCommands(codec, controller)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @After
    fun tearDown() = server.close()

    private fun start(payload: String = START) = runBlocking { commands.start.handle(command(MessageTypes.CAMERA_START, payload))!! }

    private fun capture(payload: String) = runBlocking { commands.capture.handle(command(MessageTypes.CAMERA_CAPTURE, payload))!! }

    private fun switchTo(facing: String) =
        runBlocking { commands.switchCamera.handle(command(MessageTypes.CAMERA_SWITCH, """{"sessionId":"s1","facing":"$facing"}"""))!! }

    /** Start, approve on the phone, and let the engine connect. */
    private fun liveSession() {
        start()
        assertTrue(controller.onConsentGranted("s1", owner))
        eventually { holder.engines.isNotEmpty() && holder.engine.started }
        holder.listener!!.onLive()
    }

    @Test
    fun `without CAMERA the start is refused and nothing is shown`() {
        granted.clear()

        val error = expectError { start() }

        assertEquals(ErrorCodes.PERMISSION_REQUIRED, error.code)
        assertEquals(Permissions.CAMERA, error.permission)
        assertNull(consent.launched)
        assertNull(consent.requested)
        assertNull(controller.pendingSession)
    }

    @Test
    fun `a phone without a camera answers UNSUPPORTED`() {
        captures.hasCamera = false

        assertEquals(ErrorCodes.UNSUPPORTED, expectError { start() }.code)
        assertNull(consent.requested)
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
    fun `audio is only granted with RECORD_AUDIO`() {
        assertEquals("false", start()["audio"]!!.jsonPrimitive.content)

        granted += Manifest.permission.RECORD_AUDIO
        assertEquals("true", start()["audio"]!!.jsonPrimitive.content)
    }

    @Test
    fun `AWAITING_CONSENT follows the result and nothing is captured before approval`() {
        start()

        eventually { events.count(MessageTypes.CAMERA_STATE) == 1 }
        val state = events.of(MessageTypes.CAMERA_STATE).single()
        assertEquals(CameraStates.AWAITING_CONSENT, state["state"]!!.jsonPrimitive.content)
        assertEquals(CameraFacings.BACK, state["facing"]!!.jsonPrimitive.content)
        assertTrue(captures.created.isEmpty())
        assertTrue(holder.engines.isEmpty())
    }

    @Test
    fun `invalid starts are VALIDATION errors`() {
        val badFacing = START.replace("\"BACK\"", "\"SIDE\"")
        val longViewer = START.replace("\"Pat\"", "\"${"x".repeat(101)}\"")

        assertEquals(ErrorCodes.VALIDATION, expectError { start(badFacing) }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { start(longViewer) }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { start("""{"facing":"BACK","audio":false}""") }.code)
    }

    @Test
    fun `declining reports ENDED DENIED`() {
        start()
        controller.onConsentDenied("s1")

        val ended = events.of(MessageTypes.CAMERA_STATE).last()
        assertEquals(CameraStates.ENDED, ended["state"]!!.jsonPrimitive.content)
        assertEquals(CameraReasons.DENIED, ended["reason"]!!.jsonPrimitive.content)
        assertFalse("a denied request can't be approved later", controller.onConsentGranted("s1", owner))
        assertTrue(captures.created.isEmpty())
    }

    @Test
    fun `capture and switch need an approved, live session`() {
        start()

        assertEquals(ErrorCodes.CONFLICT, expectError { capture(photo()) }.code)
        assertEquals(ErrorCodes.CONFLICT, expectError { switchTo(CameraFacings.FRONT) }.code)
    }

    @Test
    fun `capture payloads are validated`() {
        liveSession()

        assertEquals(ErrorCodes.VALIDATION, expectError { capture(photo(mediaId = "../etc")) }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","kind":"GIF"}""") }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","kind":"VIDEO"}""") }.code)
        assertEquals(
            ErrorCodes.VALIDATION,
            expectError { capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","kind":"VIDEO","durationMs":60001}""") }.code,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a photo is uploaded to the media slot before the result`() {
        liveSession()
        server.enqueue(MockResponse.Builder().code(204).build())

        val result = capture(photo())

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/device-api/camera/$MEDIA_ID", request.url.encodedPath)
        assertEquals("image/jpeg", request.headers["Content-Type"])
        assertEquals("Device dev-1.secret", request.headers["Authorization"])
        assertEquals(3, request.body!!.size)
        assertEquals("4000", result["width"]!!.jsonPrimitive.content)
        assertEquals("3000", result["height"]!!.jsonPrimitive.content)
        assertNull(result["durationMs"])
    }

    @Test
    fun `a clip is uploaded as video mp4 with its duration`() {
        liveSession()
        server.enqueue(MockResponse.Builder().code(204).build())

        val result = capture("""{"sessionId":"s1","mediaId":"$MEDIA_ID","kind":"VIDEO","durationMs":3000}""")

        assertEquals("video/mp4", server.takeRequest().headers["Content-Type"])
        assertEquals("3000", result["durationMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upload refusals map to command errors`() {
        liveSession()
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(413).build())
        server.enqueue(MockResponse.Builder().code(415).build())

        assertEquals(ErrorCodes.NOT_FOUND, expectError { capture(photo()) }.code)
        assertEquals(ErrorCodes.PAYLOAD_TOO_LARGE, expectError { capture(photo()) }.code)
        assertEquals(ErrorCodes.UNSUPPORTED, expectError { capture(photo()) }.code)
    }

    @Test
    fun `switch answers the new facing`() {
        liveSession()

        assertEquals(CameraFacings.FRONT, switchTo(CameraFacings.FRONT)["facing"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.VALIDATION, expectError { switchTo("SIDE") }.code)
    }

    @Test
    fun `the answer and stop reach the running session, and stop ends capture silently`() {
        liveSession()
        val before = events.count(MessageTypes.CAMERA_STATE)

        runBlocking {
            commands.signal.handle(event(MessageTypes.CAMERA_SIGNAL, """{"sessionId":"other","kind":"ANSWER","sdp":"wrong"}"""))
            commands.signal.handle(event(MessageTypes.CAMERA_SIGNAL, """{"sessionId":"s1","kind":"OFFER","sdp":"wrong"}"""))
            commands.signal.handle(event(MessageTypes.CAMERA_SIGNAL, """{"sessionId":"s1","kind":"ANSWER","sdp":"answer-sdp"}"""))
        }
        eventually { holder.engine.answered == "answer-sdp" }

        runBlocking { commands.stop.handle(event(MessageTypes.CAMERA_STOP, """{"sessionId":"s1"}""")) }

        assertTrue(holder.engine.closed)
        assertTrue(captures.last.closed)
        assertEquals(1, consent.captureStops)
        assertEquals(before, events.count(MessageTypes.CAMERA_STATE))
    }

    @Test
    fun `Stop on the phone ends the session with STOPPED_ON_PHONE`() {
        liveSession()

        controller.stopFromPhone()

        val ended = events.of(MessageTypes.CAMERA_STATE).last()
        assertEquals(CameraReasons.STOPPED_ON_PHONE, ended["reason"]!!.jsonPrimitive.content)
        assertTrue(captures.last.closed)
    }

    @Test
    fun `going offline ends the session`() {
        liveSession()

        events.connection.value = ConnectionState.Reconnecting(kotlin.time.Duration.ZERO, "network lost")

        eventually { holder.engine.closed }
        assertTrue(captures.last.closed)
        assertEquals(1, consent.captureStops)
    }

    @Test
    fun `a new start replaces the running session`() {
        liveSession()
        val first = holder.engine

        start(START.replace("\"s1\"", "\"s2\""))

        assertTrue(first.closed)
        assertEquals("s2", controller.pendingSession?.sessionId)
    }

    private fun photo(mediaId: String = MEDIA_ID) = """{"sessionId":"s1","mediaId":"$mediaId","kind":"PHOTO"}"""

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
        const val START = """{"sessionId":"s1","viewer":"Pat","facing":"BACK","audio":true,"iceServers":[]}"""
    }
}
