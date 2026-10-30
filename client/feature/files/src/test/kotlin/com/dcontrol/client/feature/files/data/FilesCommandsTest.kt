package com.dcontrol.client.feature.files.data

import com.dcontrol.client.core.storage.StorageAccess
import com.dcontrol.client.core.storage.StorageRoots
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.FilesListResult
import com.dcontrol.client.core.websocket.protocol.FilesRootsResult
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.Volume
import com.dcontrol.client.feature.files.transfer.TransferEngine
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesCommandsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val codec = ProtocolCodec()
    private var granted = true
    private lateinit var root: File
    private lateinit var commands: FilesCommands

    @Before
    fun setUp() {
        root = temp.newFolder("storage").canonicalFile
        val volume = Volume("primary", "Internal storage", root.path, 100, 50, removable = false, primary = true)
        val roots = object : StorageRoots {
            override fun volumes() = listOf(volume)
            override fun blockedPaths() = emptyList<File>()
        }
        val fileSystem = FileSystem(roots = { listOf(root) })
        // The engine is only used by the transfer handlers, which these tests don't call.
        val engine = TransferEngine(
            api = com.dcontrol.client.feature.files.transfer.TransferApi(
                com.dcontrol.client.core.network.device.DeviceHttpClient(okhttp3.OkHttpClient(), NoCredentials),
                codec.json,
            ),
            store = UnusedStore,
            fileSystem = fileSystem,
            events = UnusedEvents,
            notifier = UnusedNotifier,
            indexer = {},
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        commands = FilesCommands(codec, fileSystem, roots, StorageAccess { granted }, engine)
    }

    @Test
    fun `roots answer the storage volumes`() = runTest {
        val result = decode(commands.roots, null, FilesRootsResult.serializer())
        assertEquals("primary", result.volumes.single().id)
    }

    @Test
    fun `list answers a page of entries`() = runTest {
        File(root, "a.txt").writeText("x")

        val result = decode(commands.list, JsonObject(mapOf("path" to JsonPrimitive(root.path))), FilesListResult.serializer())

        assertEquals(listOf("a.txt"), result.entries.map { it.name })
        assertEquals(root.path, result.path)
    }

    @Test
    fun `commands answer PERMISSION_REQUIRED without storage access`() = runTest {
        granted = false
        assertCode(ErrorCodes.PERMISSION_REQUIRED) { commands.list.handle(envelope(JsonObject(mapOf("path" to JsonPrimitive(root.path))))) }
    }

    @Test
    fun `malformed payloads answer VALIDATION`() = runTest {
        assertCode(ErrorCodes.VALIDATION) { commands.list.handle(envelope(JsonObject(mapOf("offset" to JsonPrimitive(1))))) }
    }

    private suspend fun <T> decode(handler: CommandHandler, payload: JsonObject?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val result = handler.handle(envelope(payload))!!
        return codec.json.decodeFromJsonElement(serializer, result)
    }

    private fun envelope(payload: JsonObject?) = Envelope(type = "files.x", requestId = "r-1", payload = payload)

    private suspend fun assertCode(code: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected CommandException $code")
    }

    private object NoCredentials : com.dcontrol.client.core.network.credentials.CredentialStore {
        override val credentials = kotlinx.coroutines.flow.flowOf(null)
        override suspend fun save(credentials: com.dcontrol.client.core.network.credentials.StoredCredentials) = Unit
        override suspend fun clear() = Unit
    }

    private object UnusedStore : com.dcontrol.client.feature.files.transfer.TransferStore {
        override suspend fun save(transfer: com.dcontrol.client.core.database.TransferEntity) = Unit
        override suspend fun find(transferId: String) = null
        override suspend fun all() = emptyList<com.dcontrol.client.core.database.TransferEntity>()
        override suspend fun delete(transferId: String) = Unit
    }

    private object UnusedEvents : com.dcontrol.client.core.websocket.DeviceEventSender {
        override val connectionState = kotlinx.coroutines.flow.emptyFlow<com.dcontrol.client.core.websocket.ConnectionState>()
        override fun <T> send(type: String, serializer: kotlinx.serialization.KSerializer<T>, payload: T) = false
    }

    private object UnusedNotifier : com.dcontrol.client.feature.files.transfer.TransferNotifier {
        override fun progress(transferId: String, fileName: String, bytes: Long, size: Long, upload: Boolean) = Unit
        override fun finished(transferId: String, fileName: String, ok: Boolean, upload: Boolean) = Unit
        override fun clear(transferId: String) = Unit
    }
}
