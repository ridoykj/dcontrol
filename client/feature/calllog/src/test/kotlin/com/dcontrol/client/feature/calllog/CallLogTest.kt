package com.dcontrol.client.feature.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog.Calls
import android.telecom.PhoneAccountHandle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.AccessStates
import com.dcontrol.client.core.websocket.protocol.CallTypes
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.Presentations
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.SimsResult
import com.dcontrol.client.feature.calllog.data.CallCommands
import com.dcontrol.client.feature.calllog.data.CallLogStore
import com.dcontrol.client.feature.calllog.data.TelecomGateway
import com.dcontrol.client.feature.calllog.ui.CallsAccessCardContent
import com.dcontrol.client.feature.calllog.ui.CallsAccessTags
import java.io.InputStream
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
class CallLogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val codec = ProtocolCodec()
    private val log = FakeCallLog()
    private val sims = FakeSims()
    private val telecom = FakeTelecom()
    private val granted = mutableSetOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
    )
    private val store = CallLogStore(log, sims, codec)
    private val commands = CallCommands(codec, store, telecom, sims, RuntimePermissions { it in granted })

    @Test
    fun `entries map types, withheld numbers and SIM labels`() {
        log.add(1, "+491511", Calls.MISSED_TYPE, date = 3_000, accountId = "1", new = true)
        log.add(2, "", Calls.INCOMING_TYPE, date = 2_000, presentation = Calls.PRESENTATION_RESTRICTED)
        log.add(3, "+4930", 99, date = 1_000)

        val items = store.list(null, null).items

        assertEquals(listOf(1L, 2L, 3L), items.map { it.id })
        assertEquals(CallTypes.MISSED, items[0].type)
        assertEquals("Work", items[0].simLabel)
        assertTrue(items[0].isNew)
        assertEquals("1970-01-01T00:00:03.000Z", items[0].date)
        assertNull(items[1].number)
        assertEquals(Presentations.RESTRICTED, items[1].presentation)
        assertEquals(CallTypes.OTHER, items[2].type)
    }

    @Test
    fun `keyset paging walks the whole log and filters by type`() {
        repeat(1200) { i -> log.add(i + 1L, "+4915$i", if (i % 2 == 0) Calls.INCOMING_TYPE else Calls.OUTGOING_TYPE, date = 10_000L - i / 3) }
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        do {
            val page = store.list(null, cursor)
            seen += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        val expected = log.rows.sortedWith(compareByDescending<Row> { it.date }.thenByDescending { it.id }).map { it.id }
        assertEquals(expected, seen)
        assertTrue(log.lastUri!!.getQueryParameter(Calls.LIMIT_PARAM_KEY) == "501")

        val incoming = store.list(CallTypes.INCOMING, null).items
        assertTrue(incoming.all { it.type == CallTypes.INCOMING })
        expectError(ErrorCodes.VALIDATION) { store.list(CallTypes.OTHER, null) }
        expectError(ErrorCodes.VALIDATION) { store.list(null, "garbage") }
    }

    @Test
    fun `delete removes by id within limits`() = runTest {
        log.add(1, "+1", Calls.INCOMING_TYPE, 1)
        log.add(2, "+2", Calls.INCOMING_TYPE, 2)
        val result = commands.delete.handle(command(MessageTypes.CALLS_DELETE, """{"ids":[1,1,7]}"""))!!
        assertEquals("1", result.getValue("deleted").toString())
        assertEquals(listOf(2L), log.rows.map { it.id })
        expectError(ErrorCodes.VALIDATION) { store.delete(emptyList()) }
        expectError(ErrorCodes.VALIDATION) { store.delete((1L..101L).toList()) }
    }

    @Test
    fun `call log commands need the CALL_LOG permissions`() = runTest {
        granted.remove(Manifest.permission.WRITE_CALL_LOG)
        commands.list.handle(command(MessageTypes.CALLS_LIST, "{}"))
        val error = expectError(ErrorCodes.PERMISSION_REQUIRED) { commands.delete.handle(command(MessageTypes.CALLS_DELETE, """{"ids":[1]}""")) }
        assertEquals(Permissions.CALL_LOG, error.permission)
    }

    @Test
    fun `place dials the stripped number on the chosen SIM`() = runTest {
        commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"+49 (151) 234-5678","simId":1}"""))

        assertEquals("+491512345678", telecom.placed.single().first.schemeSpecificPart)
        assertEquals("sim-1", telecom.placed.single().second!!.id)
    }

    @Test
    fun `place refuses MMI codes, unknown SIMs, missing telephony and permission`() = runTest {
        expectError(ErrorCodes.VALIDATION) { commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"*#06#"}""")) }
        expectError(ErrorCodes.VALIDATION) { commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"**21*12345#"}""")) }
        expectError(ErrorCodes.VALIDATION) { commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"12345","simId":9}""")) }

        granted.remove(Manifest.permission.CALL_PHONE)
        val denied = expectError(ErrorCodes.PERMISSION_REQUIRED) { commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"12345"}""")) }
        assertEquals(Permissions.PHONE, denied.permission)

        sims.telephony = false
        expectError(ErrorCodes.UNSUPPORTED) { commands.place.handle(command(MessageTypes.CALL_PLACE, """{"number":"12345"}""")) }
        assertTrue(telecom.placed.isEmpty())
    }

    @Test
    fun `sims answers the snapshot`() = runTest {
        val result = commands.sims.handle(command(MessageTypes.TELEPHONY_SIMS, "{}"))!!
        assertEquals("\"GRANTED\"", result.getValue("access").toString())
    }

    @Test
    fun accessCardShowsEachGroup() {
        var clicks = 0
        composeRule.setContent { CallsAccessCardContent(callLogGranted = true, phoneGranted = false, blocked = false, onGrant = { clicks++ }) }
        composeRule.onNodeWithTag(CallsAccessTags.CALL_LOG_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(CallsAccessTags.PHONE_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(CallsAccessTags.GRANT).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun accessCardHidesGrantWhenAllGranted() {
        composeRule.setContent { CallsAccessCardContent(callLogGranted = true, phoneGranted = true, blocked = false, onGrant = {}) }
        composeRule.onNodeWithTag(CallsAccessTags.GRANT).assertDoesNotExist()
    }

    private fun command(type: String, payload: String) =
        Envelope(type = type, requestId = "r-1", payload = Json.parseToJsonElement(payload).jsonObject)

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

    class Row(val id: Long, val number: String, val type: Int, val date: Long, val presentation: Int, val accountId: String?, val new: Boolean)

    /** Applies the store's keyset selection the way SQLite would. */
    class FakeCallLog : ContentQueries {
        val rows = mutableListOf<Row>()
        var lastUri: Uri? = null

        fun add(id: Long, number: String, type: Int, date: Long, presentation: Int = Calls.PRESENTATION_ALLOWED, accountId: String? = null, new: Boolean = false) {
            rows += Row(id, number, type, date, presentation, accountId, new)
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
            lastUri = uri
            var remaining = args.orEmpty().toList()
            var selected = rows.toList()
            if (selection?.contains("${Calls.TYPE} = ?") == true) {
                val type = remaining.first().toInt()
                selected = selected.filter { it.type == type }
                remaining = remaining.drop(1)
            }
            if (selection?.contains("${Calls.DATE} < ?") == true) {
                val date = remaining[0].toLong()
                val id = remaining[2].toLong()
                selected = selected.filter { it.date < date || (it.date == date && it.id < id) }
            }
            val limit = uri.getQueryParameter(Calls.LIMIT_PARAM_KEY)?.toInt() ?: Int.MAX_VALUE
            return MatrixCursor(projection!!).apply {
                selected.sortedWith(compareByDescending<Row> { it.date }.thenByDescending { it.id }).take(limit).forEach { row ->
                    addRow(
                        projection.map { column ->
                            when (column) {
                                Calls._ID -> row.id
                                Calls.NUMBER -> row.number
                                Calls.NUMBER_PRESENTATION -> row.presentation
                                Calls.CACHED_NAME -> null
                                Calls.TYPE -> row.type
                                Calls.DATE -> row.date
                                Calls.DURATION -> 5
                                Calls.PHONE_ACCOUNT_ID -> row.accountId
                                Calls.NEW -> if (row.new) 1 else 0
                                else -> null
                            }
                        },
                    )
                }
            }
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            val ids = args!!.map { it.toLong() }.toSet()
            val before = rows.size
            rows.removeAll { it.id in ids }
            return before - rows.size
        }

        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>) = emptyArray<ContentProviderResult>()
        override fun lookupContact(lookupUri: Uri): Uri? = null
        override fun openContactPhoto(contactUri: Uri): InputStream? = null
    }

    class FakeSims : SimCards {
        var telephony = true
        override fun hasTelephony() = telephony
        override fun snapshot() = SimsResult(telephony, AccessStates.GRANTED, sims = emptyList())
        override fun labelForSubscription(subscriptionId: Int) = if (subscriptionId == 1) "Work" else null
        override fun labelForAccountId(accountId: String?) = if (accountId == "1") "Work" else null
        override fun isActive(subscriptionId: Int) = subscriptionId == 1
    }

    class FakeTelecom : TelecomGateway {
        val placed = mutableListOf<Pair<Uri, PhoneAccountHandle?>>()
        override fun placeCall(number: Uri, account: PhoneAccountHandle?) {
            placed += number to account
        }

        override fun accountFor(subscriptionId: Int): PhoneAccountHandle? =
            if (subscriptionId == 1) PhoneAccountHandle(android.content.ComponentName("com.android.phone", "Telephony"), "sim-1") else null
    }
}
