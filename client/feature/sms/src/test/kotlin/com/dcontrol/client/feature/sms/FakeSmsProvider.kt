package com.dcontrol.client.feature.sms

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import com.dcontrol.client.core.telephony.ContactNames
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.AccessStates
import com.dcontrol.client.core.websocket.protocol.SimsResult
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer

/** In-memory SMS/MMS provider that applies the store's selections the way SQLite would. */
class FakeSmsProvider : ContentQueries {
    data class SmsRow(val id: Long, val threadId: Long, val address: String?, val body: String, val date: Long, val type: Int = Sms.MESSAGE_TYPE_INBOX, val read: Boolean = true, val subId: Int = -1)
    data class MmsRow(val id: Long, val threadId: Long, val dateSec: Long, val box: Int, val subject: String? = null, val address: String? = null)

    val sms = mutableListOf<SmsRow>()
    val mms = mutableListOf<MmsRow>()
    var conversationsWork = true
    var queriedUris = mutableListOf<String>()

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor? {
        val path = uri.toString()
        queriedUris += path
        return when {
            path.startsWith("content://mms-sms/conversations") -> {
                if (!conversationsWork) throw IllegalArgumentException("Unknown URI")
                conversations(selection, args)
            }
            path == "content://mms-sms/canonical-addresses" -> MatrixCursor(arrayOf("_id", "address")).apply {
                addresses().forEach { (address, id) -> addRow(arrayOf<Any?>(id, address)) }
            }
            path == Sms.CONTENT_URI.toString() -> smsCursor(projection!!, selection, args, sortOrder)
            path == Mms.CONTENT_URI.toString() -> mmsCursor(projection!!, selection, args)
            path.matches(Regex("content://mms/\\d+/addr")) -> MatrixCursor(arrayOf("address", "type")).apply {
                val id = uri.pathSegments[0].toLong()
                mms.firstOrNull { it.id == id }?.address?.let { addRow(arrayOf<Any?>(it, args!![0].toInt())) }
            }
            else -> error("Unexpected query $uri")
        }
    }

    private fun addresses(): Map<String, Long> =
        sms.mapNotNull { it.address }.distinct().withIndex().associate { (index, address) -> address to index + 1L }

    private fun conversations(selection: String?, args: Array<String>?): Cursor {
        val ids = addresses()
        val threads = sms.groupBy { it.threadId }.map { (threadId, rows) ->
            val newest = rows.maxBy { it.date }
            arrayOf<Any?>(threadId, newest.date, rows.size, rows.mapNotNull { it.address }.distinct().joinToString(" ") { ids.getValue(it).toString() }, newest.body)
        }.filter { row ->
            if (selection == null) return@filter true
            val date = args!![0].toLong()
            val id = args[2].toLong()
            (row[1] as Long) < date || (row[1] == date && (row[0] as Long) < id)
        }.sortedWith(compareByDescending<Array<Any?>> { it[1] as Long }.thenByDescending { it[0] as Long })
        return MatrixCursor(arrayOf("_id", "date", "message_count", "recipient_ids", "snippet")).apply { threads.forEach { addRow(it) } }
    }

    private fun smsCursor(projection: Array<String>, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
        var rows = sms.toList()
        val sel = selection.orEmpty()
        var argIndex = 0
        if (sel.contains("${Sms.THREAD_ID} = ?")) {
            val threadId = args!![argIndex++].toLong()
            rows = rows.filter { it.threadId == threadId }
        }
        if (sel.contains("${Sms.DATE} <= ?")) {
            val date = args!![argIndex++].toLong()
            rows = rows.filter { it.date <= date }
        }
        if (sel.contains("${Sms.READ} = 0")) rows = rows.filter { !it.read }
        if (sel.contains("${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}")) rows = rows.filter { it.type == Sms.MESSAGE_TYPE_INBOX }
        if (sel.contains("${Sms._ID} > ?")) {
            val id = args!![argIndex].toLong()
            rows = rows.filter { it.id > id }
        }
        rows = when (sortOrder) {
            "${Sms._ID} ASC" -> rows.sortedBy { it.id }
            "${Sms._ID} DESC" -> rows.sortedByDescending { it.id }
            null -> rows
            else -> rows.sortedWith(compareByDescending<SmsRow> { it.date }.thenByDescending { it.id })
        }
        return MatrixCursor(projection).apply {
            rows.forEach { row ->
                addRow(
                    projection.map { column ->
                        when (column) {
                            Sms._ID -> row.id
                            Sms.THREAD_ID -> row.threadId
                            Sms.ADDRESS -> row.address
                            Sms.BODY -> row.body
                            Sms.DATE -> row.date
                            Sms.READ -> if (row.read) 1 else 0
                            Sms.TYPE -> row.type
                            Sms.STATUS -> -1
                            Sms.SUBSCRIPTION_ID -> row.subId
                            else -> null
                        }
                    },
                )
            }
        }
    }

    private fun mmsCursor(projection: Array<String>, selection: String?, args: Array<String>?): Cursor {
        var rows = mms.toList()
        if (selection?.contains("${Mms.THREAD_ID} = ?") == true) rows = rows.filter { it.threadId == args!![0].toLong() }
        if (selection?.contains("${Mms.DATE} <= ?") == true) rows = rows.filter { it.dateSec <= args!![1].toLong() }
        rows = rows.sortedWith(compareByDescending<MmsRow> { it.dateSec }.thenByDescending { it.id })
        return MatrixCursor(projection).apply {
            rows.forEach { row ->
                addRow(
                    projection.map { column ->
                        when (column) {
                            Mms._ID -> row.id
                            Mms.THREAD_ID -> row.threadId
                            Mms.DATE -> row.dateSec
                            Mms.READ -> 1
                            Mms.MESSAGE_BOX -> row.box
                            Mms.SUBJECT -> row.subject
                            Mms.SUBSCRIPTION_ID -> -1
                            else -> null
                        }
                    },
                )
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>) = emptyArray<ContentProviderResult>()
    override fun lookupContact(lookupUri: Uri): Uri? = null
    override fun openContactPhoto(contactUri: Uri): InputStream? = null
}

class FakeNames : ContactNames {
    override fun nameFor(number: String) = if (number == "+491") "Alice" else null
    override fun clear() = Unit
}

class FakeSims : SimCards {
    var telephony = true
    override fun hasTelephony() = telephony
    override fun snapshot() = SimsResult(telephony, AccessStates.GRANTED, sims = emptyList())
    override fun labelForSubscription(subscriptionId: Int) = if (subscriptionId == 2) "Work" else null
    override fun labelForAccountId(accountId: String?): String? = null
    override fun isActive(subscriptionId: Int) = subscriptionId == 2
}

class RecordingEvents : DeviceEventSender {
    val sent = mutableListOf<Pair<String, Any?>>()
    override val connectionState: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Idle)

    override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean {
        sent += type to payload
        return true
    }
}
