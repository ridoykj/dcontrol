package com.dcontrol.client.feature.notifications.data

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.database.MirroredAppDao
import com.dcontrol.client.core.database.MirroredAppEntity
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.NotificationAccess
import com.dcontrol.client.core.websocket.protocol.NotificationsListResult
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NotificationCommandsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val codec = ProtocolCodec()
    private val dao = FakeDao(disabled = listOf("com.muted"))
    private val source = FakeSource()
    private lateinit var mirror: NotificationMirror
    private lateinit var commands: NotificationCommands

    @Before
    fun setUp() {
        val mapper = NotificationMapper(context)
        val icons = AppIcons(context, DeviceHttpClient(OkHttpClient(), NoCredentials))
        mirror = NotificationMirror(mapper, icons, dao, NoEvents)
        commands = NotificationCommands(context, codec, mirror, mapper)
        val deadline = System.currentTimeMillis() + 5_000
        while (mirror.isMirrored("com.muted") && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun `list reports missing access when the listener is not connected`() = runBlocking {
        val result = list(0)

        assertEquals(NotificationAccess.MISSING, result.access)
        assertEquals(emptyList<Any>(), result.items)
    }

    @Test
    fun `list returns mirrored notifications newest first`() = runBlocking {
        mirror.source = source
        source.items += sbn("com.chat", 1, "Old", postTime = 1_000)
        source.items += sbn("com.chat", 2, "New", postTime = 2_000)
        source.items += sbn("com.muted", 3, "Muted", postTime = 3_000)
        source.items += sbn(context.packageName, 4, "Own", postTime = 4_000)

        val result = list(0)

        assertEquals(NotificationAccess.GRANTED, result.access)
        assertEquals(listOf("New", "Old"), result.items.map { it.title })
        assertNull(result.nextOffset)
        assertEquals("1970-01-01T00:00:02Z", result.items.first().postedAt)
    }

    @Test
    fun `dismiss cancels clearable notifications only`() = runBlocking {
        mirror.source = source
        val clearable = sbn("com.chat", 1, "Hi")
        val ongoing = sbn("com.music", 2, "Playing", flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR)
        source.items += listOf(clearable, ongoing)

        commands.dismiss.handle(command(mapOf("key" to clearable.key)))
        assertEquals(listOf(clearable.key), source.cancelled)

        assertCode(ErrorCodes.FORBIDDEN) { commands.dismiss.handle(command(mapOf("key" to ongoing.key))) }
        assertCode(ErrorCodes.NOT_FOUND) { commands.dismiss.handle(command(mapOf("key" to "missing"))) }
    }

    @Test
    fun `dismiss without access answers PERMISSION_REQUIRED`() = runBlocking {
        assertCode(ErrorCodes.PERMISSION_REQUIRED) { commands.dismiss.handle(command(mapOf("key" to "k"))) }
    }

    @Test
    fun `reply fills the RemoteInput of the reply action`() = runBlocking {
        mirror.source = source
        val message = sbn("com.chat", 1, "Alice", reply = true)
        val plain = sbn("com.chat", 2, "News")
        source.items += listOf(message, plain)

        commands.reply.handle(command(mapOf("key" to message.key, "text" to "On my way")))

        val sent = shadowOf(context as android.app.Application).broadcastIntents.last()
        assertEquals("On my way", RemoteInput.getResultsFromIntent(sent)?.getCharSequence(REPLY_KEY))
        assertCode(ErrorCodes.UNSUPPORTED) { commands.reply.handle(command(mapOf("key" to plain.key, "text" to "x"))) }
        assertCode(ErrorCodes.VALIDATION) { commands.reply.handle(command(mapOf("key" to message.key, "text" to ""))) }
    }

    private suspend fun list(offset: Int): NotificationsListResult =
        decode(commands.list, mapOf("offset" to offset), NotificationsListResult.serializer())

    private suspend fun <T> decode(handler: CommandHandler, payload: Map<String, Any>, serializer: KSerializer<T>): T =
        codec.json.decodeFromJsonElement(serializer, handler.handle(command(payload))!!)

    private fun command(payload: Map<String, Any>) = Envelope(
        type = "notifications.x",
        requestId = "r-1",
        payload = JsonObject(payload.mapValues { (_, v) -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    private fun sbn(packageName: String, id: Int, title: String, postTime: Long = 1_000, flags: Int = 0, reply: Boolean = false): StatusBarNotification {
        val builder = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("text of $title")
        if (reply) {
            val intent = PendingIntent.getBroadcast(context, id, Intent("reply").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build()
            builder.addAction(Notification.Action.Builder(null as Icon?, "Reply", intent).addRemoteInput(input).build())
        }
        val notification = builder.build().apply { this.flags = this.flags or flags }
        return StatusBarNotification(packageName, packageName, id, null, 0, 0, 0, notification, Process.myUserHandle(), postTime)
    }

    private suspend fun assertCode(code: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected CommandException $code")
    }

    private class FakeSource : ActiveNotificationSource {
        val items = mutableListOf<StatusBarNotification>()
        val cancelled = mutableListOf<String>()
        override fun active() = items.toList()
        override fun cancel(key: String) {
            cancelled += key
        }
    }

    private class FakeDao(disabled: List<String>) : MirroredAppDao {
        private val disabledFlow = MutableStateFlow(disabled)
        override fun observeAll(): Flow<List<MirroredAppEntity>> = emptyFlow()
        override fun observeDisabled(): Flow<List<String>> = disabledFlow
        override suspend fun recordSeen(packageName: String, appLabel: String, seenAtMillis: Long) = Unit
        override suspend fun setEnabled(packageName: String, enabled: Boolean) = Unit
    }

    private object NoCredentials : CredentialStore {
        override val credentials: Flow<StoredCredentials?> = flowOf(null)
        override suspend fun save(credentials: StoredCredentials) = Unit
        override suspend fun clear() = Unit
    }

    private object NoEvents : DeviceEventSender {
        override val connectionState: Flow<ConnectionState> = emptyFlow()
        override fun <T> send(type: String, serializer: KSerializer<T>, payload: T) = false
    }

    private companion object {
        const val REPLY_KEY = "reply_text"
    }
}
