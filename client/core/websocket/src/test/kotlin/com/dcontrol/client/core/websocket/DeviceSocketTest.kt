package com.dcontrol.client.core.websocket

import com.dcontrol.client.core.websocket.protocol.Battery
import com.dcontrol.client.core.websocket.protocol.ClipboardTextPayload
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FilesListRequest
import com.dcontrol.client.core.websocket.protocol.FilesListResult
import com.dcontrol.client.core.websocket.protocol.FilesRootsResult
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.TransferIdPayload
import com.dcontrol.client.core.websocket.protocol.CloseCodes
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.HelloPayload
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceSocketTest {

    private val codec = ProtocolCodec()
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:15:30Z"), ZoneOffset.UTC)
    private var battery = Battery(87, false)

    private val deviceInfo = object : DeviceInfoSource {
        override fun hello() = HelloPayload("1.0.0", "Google", "Pixel 9", "17", 37, battery)
        override fun battery() = battery
    }

    private lateinit var socket: DeviceSocket
    private lateinit var credentials: DeviceCredentials

    @Before
    fun setUp() {
        server.start()
        credentials = DeviceCredentials(server.url("/").toString(), "dev-1", "secret")
        socket = DeviceSocket(
            client = client,
            scope = scope,
            deviceInfo = deviceInfo,
            codec = codec,
            backoff = Backoff(base = 10.milliseconds, cap = 20.milliseconds),
            clock = clock,
            supersededDelay = 200.milliseconds,
            heartbeatTimeUnit = 50.milliseconds,
        )
    }

    @After
    fun tearDown() {
        socket.stop()
        scope.cancel()
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `sends hello with device authorization and heartbeats after ack`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/ws/device", request.url.encodedPath)
        assertEquals("Device dev-1.secret", request.headers["Authorization"])

        val hello = peer.next()
        assertEquals("device.hello", hello.type)
        assertEquals("Pixel 9", hello.payload!!.getValue("model").jsonPrimitive.content)

        peer.send("""{"v":1,"type":"hello.ack","payload":{"serverTime":"2026-10-29T10:15:30Z","heartbeatIntervalSec":1,"deviceName":"My phone"}}""")
        val online = withTimeout(5_000) { socket.state.first { it is ConnectionState.Online } }
        assertEquals(ConnectionState.Online("My phone", 1), online)

        battery = Battery(42, true)
        val heartbeat = peer.next()
        assertEquals("device.heartbeat", heartbeat.type)
        val reported = heartbeat.payload!!.getValue("battery").jsonObject
        assertEquals(42, reported.getValue("level").jsonPrimitive.int)
    }

    @Test
    fun `answers ping with result carrying the same request id`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)
        peer.next()

        peer.send("""{"v":1,"type":"device.ping","requestId":"req-1"}""")
        val result = peer.nextOfType("device.ping.result")

        assertEquals("req-1", result.requestId)
        assertNull(result.error)
        assertEquals("2026-10-29T10:15:30Z", result.payload!!.getValue("deviceTime").jsonPrimitive.content)
    }

    @Test
    fun `unknown command is answered with UNSUPPORTED`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)
        peer.next()

        peer.send("""{"v":1,"type":"files.list","requestId":"req-2","payload":{"path":"/"}}""")
        val result = peer.nextOfType("files.list.result")

        assertEquals("req-2", result.requestId)
        assertEquals("UNSUPPORTED", result.error!!.code)
    }

    @Test
    fun `revocation reports unpaired and stops reconnecting`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        server.enqueue(ServerPeer().upgrade())
        socket.start(credentials)
        peer.next()

        peer.close(CloseCodes.REVOKED, "revoked")
        val state = withTimeout(5_000) { socket.state.first { it is ConnectionState.Unpaired } }

        assertEquals(ConnectionState.Unpaired(revoked = true), state)
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unauthorized handshake reports unpaired`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        socket.start(credentials)

        val state = withTimeout(5_000) { socket.state.first { it is ConnectionState.Unpaired } }

        assertEquals(ConnectionState.Unpaired(revoked = false), state)
    }

    @Test
    fun `reconnects after the server goes away`() = runBlocking {
        val first = ServerPeer().also { server.enqueue(it.upgrade()) }
        val second = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)
        first.next()

        first.close(CloseCodes.GOING_AWAY, "restart")

        assertEquals("device.hello", second.next().type)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `superseded connection waits before reconnecting`() = runBlocking {
        val first = ServerPeer().also { server.enqueue(it.upgrade()) }
        val second = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)
        first.next()

        first.close(CloseCodes.SUPERSEDED, "superseded")
        val state = withTimeout(5_000) { socket.state.first { it is ConnectionState.Reconnecting } }

        assertEquals(200.milliseconds, (state as ConnectionState.Reconnecting).delay)
        assertNotNull(second.next())
    }

    @Test
    fun `stop closes the socket and returns to idle`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        socket.start(credentials)
        peer.next()

        socket.stop()

        assertEquals(ConnectionState.Idle, socket.state.value)
        assertEquals(CloseCodes.NORMAL, peer.closeCode.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `events are sent only after the hello handshake`() = runBlocking {
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        val payload = ClipboardTextPayload("copied")
        assertFalse(socket.sendEvent(MessageTypes.CLIPBOARD_CHANGED, ClipboardTextPayload.serializer(), payload))

        socket.start(credentials)
        peer.next()
        assertFalse(socket.sendEvent(MessageTypes.CLIPBOARD_CHANGED, ClipboardTextPayload.serializer(), payload))
        peer.send("""{"v":1,"type":"hello.ack","payload":{"serverTime":"2026-10-29T10:15:30Z","heartbeatIntervalSec":30,"deviceName":"My phone"}}""")
        withTimeout(5_000) { socket.state.first { it is ConnectionState.Online } }

        assertTrue(socket.sendEvent(MessageTypes.CLIPBOARD_CHANGED, ClipboardTextPayload.serializer(), payload))
        val event = peer.nextOfType(MessageTypes.CLIPBOARD_CHANGED)
        assertNull(event.requestId)
        assertEquals("copied", event.payload!!.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `controller events reach their handler and registered commands answer`() = runBlocking {
        val cancelled = LinkedBlockingQueue<String>()
        val withHandlers = DeviceSocket(
            client = client,
            scope = scope,
            deviceInfo = deviceInfo,
            commandHandlers = mapOf(
                MessageTypes.FILES_ROOTS to typedCommand(codec, EmptyPayload.serializer(), FilesRootsResult.serializer()) {
                    FilesRootsResult(emptyList())
                },
                MessageTypes.FILES_LIST to typedCommand(codec, FilesListRequest.serializer(), FilesListResult.serializer()) {
                    throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "no access", Permissions.SMS)
                },
            ),
            eventHandlers = mapOf(
                MessageTypes.TRANSFER_CANCEL to EventHandler { event ->
                    cancelled += codec.payloadOf(event, TransferIdPayload.serializer())!!.transferId
                },
            ),
            codec = codec,
        )
        val peer = ServerPeer().also { server.enqueue(it.upgrade()) }
        withHandlers.start(credentials)
        peer.next()

        peer.send("""{"v":1,"type":"transfer.cancel","payload":{"transferId":"t-1"}}""")
        assertEquals("t-1", cancelled.poll(5, TimeUnit.SECONDS))

        peer.send("""{"v":1,"type":"files.roots","requestId":"r-1"}""")
        val roots = peer.nextOfType("files.roots.result")
        assertEquals(0, roots.payload!!.getValue("volumes").jsonArray.size)

        peer.send("""{"v":1,"type":"files.list","requestId":"r-2","payload":{"path":"/x"}}""")
        val denied = peer.nextOfType("files.list.result").error!!
        assertEquals(ErrorCodes.PERMISSION_REQUIRED, denied.code)
        assertEquals(Permissions.SMS, denied.details!!.permission)

        peer.send("""{"v":1,"type":"files.list","requestId":"r-3","payload":{"offset":"nope"}}""")
        assertEquals(ErrorCodes.VALIDATION, peer.nextOfType("files.list.result").error!!.code)
        withHandlers.stop()
    }

    private inner class ServerPeer : WebSocketListener() {
        private val messages = LinkedBlockingQueue<String>()
        private val opened = LinkedBlockingQueue<WebSocket>(1)
        val closeCode = LinkedBlockingQueue<Int>()
        private var webSocket: WebSocket? = null

        fun upgrade(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.offer(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.add(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode.add(code)
            webSocket.close(code, null)
        }

        private fun socket(): WebSocket =
            webSocket ?: opened.poll(5, TimeUnit.SECONDS)!!.also { webSocket = it }

        fun send(text: String) {
            socket().send(text)
        }

        fun close(code: Int, reason: String) {
            socket().close(code, reason)
        }

        fun next(): Envelope {
            val text = messages.poll(5, TimeUnit.SECONDS) ?: error("No message from device")
            return codec.decode(text) ?: error("Invalid message: $text")
        }

        fun nextOfType(type: String): Envelope {
            while (true) {
                val envelope = next()
                if (envelope.type == type) return envelope
            }
        }
    }
}
