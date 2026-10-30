package com.dcontrol.client.feature.sms

import android.Manifest
import android.app.PendingIntent
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.telephony.ProviderChanges
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageBoxes
import com.dcontrol.client.core.websocket.protocol.MessageKinds
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.SmsReceivedEvent
import com.dcontrol.client.core.websocket.protocol.SmsSendStatuses
import com.dcontrol.client.core.websocket.protocol.SmsStatusEvent
import com.dcontrol.client.feature.sms.data.SmsCommands
import com.dcontrol.client.feature.sms.data.SmsGateway
import com.dcontrol.client.feature.sms.data.SmsReportIntents
import com.dcontrol.client.feature.sms.data.SmsStatusTracker
import com.dcontrol.client.feature.sms.data.SmsStore
import com.dcontrol.client.feature.sms.data.SmsWatcher
import com.dcontrol.client.feature.sms.ui.SmsAccessCardContent
import com.dcontrol.client.feature.sms.ui.SmsAccessTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val codec = ProtocolCodec()
    private val provider = FakeSmsProvider()
    private val sims = FakeSims()
    private val events = RecordingEvents()
    private val store = SmsStore(provider, FakeNames(), sims, codec)
    private val gateway = FakeGateway()
    private val tracker = SmsStatusTracker(events)
    private val granted = mutableSetOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
    private val commands = SmsCommands(
        codec,
        store,
        gateway,
        SmsReportIntents(ApplicationProvider.getApplicationContext()),
        tracker,
        sims,
        RuntimePermissions { it in granted },
    )

    @Test
    fun `threads come from conversations with names, unread counts and MMS flags`() {
        provider.sms += FakeSmsProvider.SmsRow(1, threadId = 10, address = "+491", body = "hi", date = 1_000, read = false)
        provider.sms += FakeSmsProvider.SmsRow(2, threadId = 10, address = "+491", body = "newer", date = 2_000, type = Sms.MESSAGE_TYPE_SENT)
        provider.sms += FakeSmsProvider.SmsRow(3, threadId = 11, address = "VM-BANK", body = "otp", date = 3_000)
        provider.mms += FakeSmsProvider.MmsRow(1, threadId = 10, dateSec = 1, box = Mms.MESSAGE_BOX_INBOX)

        val threads = store.threads(null).items

        assertEquals(listOf(11L, 10L), threads.map { it.threadId })
        assertEquals("VM-BANK", threads[0].participants.single().address)
        assertNull(threads[0].participants.single().name)
        assertEquals("Alice", threads[1].participants.single().name)
        assertEquals(1, threads[1].unreadCount)
        assertEquals(2, threads[1].messageCount)
        assertTrue(threads[1].hasMms)
        assertEquals("newer", threads[1].snippet)
    }

    @Test
    fun `threads fall back to grouping SMS when the conversations URI fails`() {
        provider.conversationsWork = false
        provider.sms += FakeSmsProvider.SmsRow(1, threadId = 10, address = "+491", body = "a", date = 1_000, read = false)
        provider.sms += FakeSmsProvider.SmsRow(2, threadId = 12, address = "+492", body = "b", date = 2_000)

        val threads = store.threads(null).items

        assertEquals(listOf(12L, 10L), threads.map { it.threadId })
        assertEquals(1, threads[1].unreadCount)
    }

    @Test
    fun `thread paging covers every thread once`() {
        repeat(450) { i -> provider.sms += FakeSmsProvider.SmsRow(i + 1L, threadId = i + 1L, address = "+49$i", body = "x".repeat(150), date = 1_000L + i / 4) }
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val page = store.threads(cursor)
            seen += page.items.map { it.threadId }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(450, seen.size)
        assertEquals(450, seen.toSet().size)
    }

    @Test
    fun `messages merge SMS and MMS newest first and page with a cursor`() {
        repeat(600) { i ->
            provider.sms += FakeSmsProvider.SmsRow(i + 1L, threadId = 7, address = "+491", body = "message $i", date = 10_000L + i * 1000)
        }
        provider.mms += FakeSmsProvider.MmsRow(5, threadId = 7, dateSec = 10L + 300, box = Mms.MESSAGE_BOX_INBOX, subject = "Photo", address = "+491")
        provider.sms += FakeSmsProvider.SmsRow(9_999, threadId = 8, address = "+499", body = "other thread", date = 50_000)

        val all = mutableListOf<Pair<String, Long>>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = store.messages(7, cursor)
            all += page.items.map { it.kind to it.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        assertTrue(pages > 1)
        assertEquals(601, all.size)
        assertEquals(MessageKinds.SMS to 600L, all.first())
        val mmsIndex = all.indexOf(MessageKinds.MMS to 5L)
        assertEquals("SMS of the same second sorts before the MMS", MessageKinds.SMS to 301L, all[mmsIndex - 1])

        val mms = findMms()
        assertEquals("MMS · Photo", mms.mmsSummary)
        assertEquals("+491", mms.address)
        assertNull(mms.body)
        assertEquals(MessageBoxes.INBOX, mms.box)
    }

    private fun findMms(): com.dcontrol.client.core.websocket.protocol.SmsMessage {
        var cursor: String? = null
        do {
            val page = store.messages(7, cursor)
            page.items.firstOrNull { it.kind == MessageKinds.MMS }?.let { return it }
            cursor = page.nextCursor
        } while (cursor != null)
        error("no MMS")
    }

    @Test
    fun `messages reject foreign cursors`() {
        expectError(ErrorCodes.VALIDATION) { store.messages(1, "bad") }
    }

    @Test
    fun `send divides, tracks parts and reports one status per stage`() = runTest {
        gateway.parts = listOf("part 1", "part 2")
        val result = commands.send.handle(command("""{"to":"+49 151 234","text":"hello","simId":2,"clientRef":"c-1"}"""))!!

        assertEquals("2", result.getValue("parts").toString())
        assertEquals("+49151234", gateway.sent.single().to)
        assertEquals(2, gateway.sent.single().simId)
        assertEquals(2, gateway.sent.single().sentIntents)

        tracker.onSent("c-1", 0, ok = true, errorCode = null)
        assertTrue(events.sent.isEmpty())
        tracker.onSent("c-1", 1, ok = true, errorCode = null)
        tracker.onSent("c-1", 1, ok = true, errorCode = null)
        tracker.onDelivered("c-1", 0, ok = true)
        tracker.onDelivered("c-1", 1, ok = true)
        assertEquals(
            listOf(SmsSendStatuses.SENT, SmsSendStatuses.DELIVERED),
            events.sent.map { (it.second as SmsStatusEvent).status },
        )
    }

    @Test
    fun `a failed part reports FAILED once with the error code`() = runTest {
        gateway.parts = listOf("a", "b", "c")
        commands.send.handle(command("""{"to":"12345","text":"hello","clientRef":"c-2"}"""))
        tracker.onSent("c-2", 0, ok = true, errorCode = null)
        tracker.onSent("c-2", 1, ok = false, errorCode = SmsStatusTracker.errorCode(android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE))
        tracker.onSent("c-2", 2, ok = true, errorCode = null)

        val status = events.sent.single().second as SmsStatusEvent
        assertEquals(SmsSendStatuses.FAILED, status.status)
        assertEquals("NO_SERVICE", status.errorCode)
    }

    @Test
    fun `send validates numbers, size, SIM, telephony and permission`() = runTest {
        expectError(ErrorCodes.VALIDATION) { commands.send.handle(command("""{"to":"*#06#","text":"x","clientRef":"c"}""")) }
        expectError(ErrorCodes.VALIDATION) { commands.send.handle(command("""{"to":"12345","text":"","clientRef":"c"}""")) }
        expectError(ErrorCodes.VALIDATION) { commands.send.handle(command("""{"to":"12345","text":"x","clientRef":"c/../x"}""")) }
        expectError(ErrorCodes.VALIDATION) { commands.send.handle(command("""{"to":"12345","text":"x","simId":9,"clientRef":"c"}""")) }

        gateway.parts = List(21) { "p" }
        expectError(ErrorCodes.PAYLOAD_TOO_LARGE) { commands.send.handle(command("""{"to":"12345","text":"x","clientRef":"c"}""")) }

        granted.remove(Manifest.permission.SEND_SMS)
        val denied = expectError(ErrorCodes.PERMISSION_REQUIRED) { commands.send.handle(command("""{"to":"12345","text":"x","clientRef":"c"}""")) }
        assertEquals(Permissions.SMS, denied.permission)

        sims.telephony = false
        expectError(ErrorCodes.UNSUPPORTED) { commands.send.handle(command("""{"to":"12345","text":"x","clientRef":"c"}""")) }
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `reading needs READ_SMS`() = runTest {
        granted.remove(Manifest.permission.READ_SMS)
        val error = expectError(ErrorCodes.PERMISSION_REQUIRED) {
            commands.threads.handle(Envelope(type = MessageTypes.SMS_THREADS, requestId = "r", payload = Json.parseToJsonElement("{}").jsonObject))
        }
        assertEquals(Permissions.SMS, error.permission)
    }

    @Test
    fun `watcher reports new inbox rows as received and other changes as changed`() = runTest {
        provider.sms += FakeSmsProvider.SmsRow(1, threadId = 10, address = "+491", body = "old", date = 1_000)
        val signals = MutableSharedFlow<Unit>()
        val changes = ProviderChanges { uri: Uri -> if (uri == Sms.CONTENT_URI) signals else MutableSharedFlow<Unit>() as Flow<Unit> }
        val watcher = SmsWatcher(changes, store, events, RuntimePermissions { true })
        backgroundScope.launch { watcher.watch() }
        runCurrent()

        provider.sms += FakeSmsProvider.SmsRow(2, threadId = 10, address = "+491", body = "new", date = 2_000)
        provider.sms += FakeSmsProvider.SmsRow(3, threadId = 10, address = "+491", body = "mine", date = 3_000, type = Sms.MESSAGE_TYPE_SENT)
        signals.emit(Unit)
        runCurrent()
        signals.emit(Unit)
        runCurrent()

        assertEquals(listOf(MessageTypes.SMS_RECEIVED, MessageTypes.SMS_CHANGED), events.sent.map { it.first })
        val received = events.sent.first().second as SmsReceivedEvent
        assertEquals(10L, received.threadId)
        assertEquals("new", received.message.body)
    }

    @Test
    fun accessCardShowsRestrictedHintWhenMissing() {
        var clicks = 0
        composeRule.setContent { SmsAccessCardContent(granted = false, blocked = false, showRestrictedHint = true, onGrant = { clicks++ }) }
        composeRule.onNodeWithTag(SmsAccessTags.RESTRICTED_HINT).assertIsDisplayed()
        composeRule.onNodeWithTag(SmsAccessTags.GRANT).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun accessCardHidesGrantWhenGranted() {
        composeRule.setContent { SmsAccessCardContent(granted = true, blocked = false, showRestrictedHint = true, onGrant = {}) }
        composeRule.onNodeWithTag(SmsAccessTags.GRANT).assertDoesNotExist()
        composeRule.onNodeWithTag(SmsAccessTags.RESTRICTED_HINT).assertDoesNotExist()
    }

    private fun command(payload: String) =
        Envelope(type = MessageTypes.SMS_SEND, requestId = "r-1", payload = Json.parseToJsonElement(payload).jsonObject)

    private inline fun expectError(code: String, block: () -> Unit): CommandException {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return e
        }
        fail("Expected $code")
        throw AssertionError()
    }

    class FakeGateway : SmsGateway {
        data class Sent(val to: String, val parts: List<String>, val simId: Int?, val sentIntents: Int)

        var parts = listOf("one")
        val sent = mutableListOf<Sent>()
        override fun divide(text: String, simId: Int?) = parts
        override fun send(to: String, parts: List<String>, simId: Int?, sent: List<PendingIntent>, delivered: List<PendingIntent>) {
            this.sent += Sent(to, parts, simId, sent.size)
        }
    }
}
