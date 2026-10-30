package com.dcontrol.client.feature.files.transfer

import com.dcontrol.client.core.database.TransferEntity
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.TransferFinishedPayload
import com.dcontrol.client.core.websocket.protocol.TransferReceiveRequest
import com.dcontrol.client.core.websocket.protocol.TransferSendRequest
import com.dcontrol.client.feature.files.data.FileSystem
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = MemoryStore()
    private val events = RecordingEvents()
    private val indexed = mutableListOf<File>()
    private lateinit var root: File
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        server.start()
        root = temp.newFolder("storage").canonicalFile
        val credentials = FixedCredentials(StoredCredentials(server.url("/").toString(), "dev-1", "secret", "Pixel"))
        engine = TransferEngine(
            api = TransferApi(DeviceHttpClient(client, credentials), json),
            store = store,
            fileSystem = FileSystem(roots = { listOf(root) }),
            events = events,
            notifier = NoopNotifier,
            indexer = { indexed += it },
            scope = scope,
            chunkBytes = 8,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `upload resumes from the controller offset in chunks`() = runBlocking {
        val file = File(root, "data.bin").apply { writeBytes(ByteArray(20) { it.toByte() }) }
        server.enqueue(transfer("RECEIVING", size = 20, received = 8))
        server.enqueue(transfer("RECEIVING", size = 20, received = 16))
        server.enqueue(transfer("READY", size = 20, received = 20))

        val result = engine.send(TransferSendRequest("t-1", file.path))
        awaitIdle()

        assertEquals("data.bin", result.fileName)
        assertEquals(20L, result.size)
        val status = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", status.method)
        assertEquals("/api/device-api/transfers/t-1", status.url.encodedPath)
        assertEquals("Device dev-1.secret", status.headers["Authorization"])

        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", first.method)
        assertEquals("/api/device-api/transfers/t-1/content", first.url.encodedPath)
        assertEquals("bytes 8-15/20", first.headers["Content-Range"])
        assertEquals((8 until 16).map { it.toByte() }, first.body!!.toByteArray().toList())

        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("bytes 16-19/20", second.headers["Content-Range"])
        assertEquals(4, second.body!!.size)
        assertNull(store.find("t-1"))
    }

    @Test
    fun `an offset conflict continues from the controller's receivedBytes`() = runBlocking {
        val file = File(root, "data.bin").apply { writeBytes(ByteArray(16)) }
        server.enqueue(transfer("RECEIVING", size = 16, received = 0))
        server.enqueue(problem(409, """{"code":"CONFLICT","receivedBytes":8}"""))
        server.enqueue(transfer("READY", size = 16, received = 16))

        engine.send(TransferSendRequest("t-2", file.path))
        awaitIdle()

        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("bytes 0-7/16", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Content-Range"])
        assertEquals("bytes 8-15/16", server.takeRequest(5, TimeUnit.SECONDS)!!.headers["Content-Range"])
    }

    @Test
    fun `send rejects directories and missing files`() = runBlocking {
        assertCode(ErrorCodes.VALIDATION) { engine.send(TransferSendRequest("t-3", root.path)) }
        assertCode(ErrorCodes.NOT_FOUND) { engine.send(TransferSendRequest("t-3", File(root, "nope").path)) }
    }

    @Test
    fun `download resumes with Range, verifies the hash and avoids name collisions`() = runBlocking {
        val content = "hello transfer!".toByteArray()
        File(root, "note.txt").writeText("existing")
        TransferEngine.partFile(root, "note.txt", "t-4").writeBytes(content.copyOfRange(0, 5))
        server.enqueue(transfer("READY", size = content.size.toLong(), received = content.size.toLong()))
        server.enqueue(MockResponse.Builder().code(206).body(okio.Buffer().write(content.copyOfRange(5, content.size))).build())

        engine.receive(TransferReceiveRequest("t-4", root.path, "note.txt", content.size.toLong(), sha256(content)))
        awaitIdle()

        server.takeRequest(5, TimeUnit.SECONDS)
        val download = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("bytes=5-", download.headers["Range"])
        val saved = File(root, "note (1).txt")
        assertEquals("hello transfer!", saved.readText())
        assertEquals("existing", File(root, "note.txt").readText())
        assertFalse(TransferEngine.partFile(root, "note.txt", "t-4").exists())
        assertEquals(listOf(saved), indexed)
        val finished = events.finished.single()
        assertTrue(finished.ok)
        assertNull(store.find("t-4"))
    }

    @Test
    fun `a checksum mismatch deletes the partial file and reports failure`() = runBlocking {
        val content = "abc".toByteArray()
        server.enqueue(transfer("READY", size = 3, received = 3))
        server.enqueue(MockResponse.Builder().code(200).body("abc").build())

        engine.receive(TransferReceiveRequest("t-5", root.path, "a.txt", 3, sha256("xyz".toByteArray())))
        awaitIdle()

        assertFalse(File(root, "a.txt").exists())
        assertFalse(TransferEngine.partFile(root, "a.txt", "t-5").exists())
        val finished = events.finished.single()
        assertFalse(finished.ok)
        assertEquals(ErrorCodes.VALIDATION, finished.errorCode)
        assertTrue(content.isNotEmpty())
    }

    @Test
    fun `a completed download is reported again when the first report could not be sent`() = runBlocking {
        val content = "abc".toByteArray()
        server.enqueue(transfer("READY", size = 3, received = 3))
        server.enqueue(MockResponse.Builder().code(200).body("abc").build())
        events.online = false

        val request = TransferReceiveRequest("t-6", root.path, "a.txt", 3, sha256(content))
        engine.receive(request)
        awaitIdle()
        assertEquals("a.txt", store.find("t-6")?.completedName)

        events.online = true
        engine.receive(request)
        awaitIdle()

        assertTrue(events.finished.single().ok)
        assertFalse(File(root, "a (1).txt").exists())
        assertEquals(2, server.requestCount)
        assertNull(store.find("t-6"))
    }

    @Test
    fun `cancel removes the partial file and the stored transfer`() = runBlocking {
        TransferEngine.partFile(root, "big.bin", "t-7").writeBytes(ByteArray(4))
        store.save(TransferEntity("t-7", TransferEntity.TO_DEVICE, root.path, "big.bin", 10, "0".repeat(64), 0))

        engine.cancel("t-7")

        assertFalse(TransferEngine.partFile(root, "big.bin", "t-7").exists())
        assertNull(store.find("t-7"))
    }

    @Test
    fun `a transfer gone on the controller is dropped`() = runBlocking {
        server.enqueue(problem(404, """{"code":"NOT_FOUND"}"""))

        engine.receive(TransferReceiveRequest("t-8", root.path, "x.bin", 1, "0".repeat(64)))
        awaitIdle()

        assertNull(store.find("t-8"))
        assertTrue(events.finished.isEmpty())
    }

    @Test
    fun `receive validates the request`() = runBlocking {
        assertCode(ErrorCodes.VALIDATION) { engine.receive(TransferReceiveRequest("t-9", root.path, "../x", 1, "0".repeat(64))) }
        assertCode(ErrorCodes.VALIDATION) { engine.receive(TransferReceiveRequest("t-9", root.path, "x", 1, "nothex")) }
        assertCode(ErrorCodes.NOT_FOUND) { engine.receive(TransferReceiveRequest("t-9", File(root, "nope").path, "x", 1, "0".repeat(64))) }
        assertCode(ErrorCodes.PAYLOAD_TOO_LARGE) {
            engine.receive(TransferReceiveRequest("t-9", root.path, "x", Long.MAX_VALUE, "0".repeat(64)))
        }
    }

    @Test
    fun `freeTarget numbers names before the extension`() {
        File(root, "a.tar.gz").writeText("")
        File(root, "a.tar (1).gz").writeText("")
        File(root, ".bashrc").writeText("")

        assertEquals("a.tar (2).gz", TransferEngine.freeTarget(root, "a.tar.gz").name)
        assertEquals(".bashrc (1)", TransferEngine.freeTarget(root, ".bashrc").name)
        assertEquals("new.txt", TransferEngine.freeTarget(root, "new.txt").name)
    }

    private suspend fun awaitIdle() = withTimeout(10_000) { engine.awaitIdle() }

    private fun transfer(state: String, size: Long, received: Long) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"id":"x","deviceId":"d","direction":"TO_DEVICE","state":"$state","fileName":"f","devicePath":"/","size":$size,"receivedBytes":$received}""")
        .build()

    private fun problem(status: Int, body: String) = MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/problem+json")
        .body(body)
        .build()

    private suspend fun assertCode(code: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected CommandException $code")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class FixedCredentials(value: StoredCredentials) : CredentialStore {
        override val credentials: StateFlow<StoredCredentials?> = MutableStateFlow(value)
        override suspend fun save(credentials: StoredCredentials) = Unit
        override suspend fun clear() = Unit
    }

    private class MemoryStore : TransferStore {
        private val rows = java.util.concurrent.ConcurrentHashMap<String, TransferEntity>()
        override suspend fun save(transfer: TransferEntity) {
            rows[transfer.transferId] = transfer
        }
        override suspend fun find(transferId: String) = rows[transferId]
        override suspend fun all() = rows.values.sortedBy { it.createdAtMillis }
        override suspend fun delete(transferId: String) {
            rows.remove(transferId)
        }
    }

    private class RecordingEvents : DeviceEventSender {
        @Volatile
        var online = true
        val finished = java.util.concurrent.CopyOnWriteArrayList<TransferFinishedPayload>()
        override val connectionState: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)

        override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean {
            if (!online) return false
            if (type == MessageTypes.TRANSFER_FINISHED) finished += payload as TransferFinishedPayload
            return true
        }
    }

    private object NoopNotifier : TransferNotifier {
        override fun progress(transferId: String, fileName: String, bytes: Long, size: Long, upload: Boolean) = Unit
        override fun finished(transferId: String, fileName: String, ok: Boolean, upload: Boolean) = Unit
        override fun clear(transferId: String) = Unit
    }
}
