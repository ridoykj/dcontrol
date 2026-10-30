package com.dcontrol.client.feature.calllog.data

import android.provider.CallLog.Calls
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.telephony.int
import com.dcontrol.client.core.telephony.long
import com.dcontrol.client.core.telephony.mapRows
import com.dcontrol.client.core.telephony.string
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.CallEntry
import com.dcontrol.client.core.websocket.protocol.CallTypes
import com.dcontrol.client.core.websocket.protocol.CallsListResult
import com.dcontrol.client.core.websocket.protocol.Cursors
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import com.dcontrol.client.core.websocket.protocol.Presentations
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.fillPage
import javax.inject.Inject
import javax.inject.Singleton

/** The call log, newest first, paged with a `(date, _id)` keyset cursor. */
@Singleton
class CallLogStore @Inject constructor(
    private val queries: ContentQueries,
    private val sims: SimCards,
    private val codec: ProtocolCodec,
) {
    fun list(type: String?, cursor: String?): CallsListResult {
        val providerType = type?.let { TYPE_TO_PROVIDER[it] ?: invalid("Invalid call type") }
        val after = cursor?.let { Cursors.decode(it, 2) ?: invalid("Invalid cursor") }
            ?.map { it.toLongOrNull() ?: invalid("Invalid cursor") }

        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (providerType != null) {
            clauses += "${Calls.TYPE} = ?"
            args += providerType.toString()
        }
        if (after != null) {
            clauses += "(${Calls.DATE} < ? OR (${Calls.DATE} = ? AND ${Calls._ID} < ?))"
            args += listOf(after[0].toString(), after[0].toString(), after[1].toString())
        }
        val uri = Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(Calls.LIMIT_PARAM_KEY, (Phase3Limits.MAX_CALLS_PAGE + 1).toString())
            .build()
        val labels = HashMap<String?, String?>()
        val rows = queries.query(
            uri,
            PROJECTION,
            clauses.joinToString(" AND ").ifEmpty { null },
            args.toTypedArray().takeIf { it.isNotEmpty() },
            "${Calls.DATE} DESC, ${Calls._ID} DESC",
        ).mapRows { c ->
            val presentation = PRESENTATIONS[c.int(Calls.NUMBER_PRESENTATION, Calls.PRESENTATION_ALLOWED)] ?: Presentations.UNKNOWN
            val accountId = c.string(Calls.PHONE_ACCOUNT_ID)
            CallEntry(
                id = c.long(Calls._ID),
                number = if (presentation == Presentations.ALLOWED) Phase3Text.phoneNumber(c.string(Calls.NUMBER)) else null,
                presentation = presentation,
                name = Phase3Text.cleanOrNull(c.string(Calls.CACHED_NAME), Phase3Limits.MAX_NAME),
                type = PROVIDER_TO_TYPE[c.int(Calls.TYPE)] ?: CallTypes.OTHER,
                date = Phase3Text.timestamp(c.long(Calls.DATE)),
                durationSec = c.long(Calls.DURATION).coerceAtLeast(0),
                simLabel = labels.getOrPut(accountId) { sims.labelForAccountId(accountId) },
                isNew = c.int(Calls.NEW) != 0,
            ) to c.long(Calls.DATE)
        }
        val page = fillPage(codec, CallEntry.serializer(), rows.asSequence().map { it.first }, Phase3Limits.MAX_CALLS_PAGE)
        val last = page.items.lastOrNull()
        val next = if (page.hasMore && last != null) {
            Cursors.encode(rows[page.items.size - 1].second.toString(), last.id.toString())
        } else {
            null
        }
        return CallsListResult(page.items, next)
    }

    fun delete(ids: List<Long>): Int {
        if (ids.isEmpty() || ids.size > Phase3Limits.MAX_DELETE || ids.any { it < 0 }) {
            invalid("ids must hold 1-${Phase3Limits.MAX_DELETE} entries")
        }
        val distinct = ids.distinct()
        return queries.delete(
            Calls.CONTENT_URI,
            "${Calls._ID} IN (${distinct.joinToString(",") { "?" }})",
            distinct.map { it.toString() }.toTypedArray(),
        )
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)

    private companion object {
        val PROJECTION = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.NUMBER_PRESENTATION,
            Calls.CACHED_NAME,
            Calls.TYPE,
            Calls.DATE,
            Calls.DURATION,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NEW,
        )

        val TYPE_TO_PROVIDER = mapOf(
            CallTypes.INCOMING to Calls.INCOMING_TYPE,
            CallTypes.OUTGOING to Calls.OUTGOING_TYPE,
            CallTypes.MISSED to Calls.MISSED_TYPE,
            CallTypes.VOICEMAIL to Calls.VOICEMAIL_TYPE,
            CallTypes.REJECTED to Calls.REJECTED_TYPE,
            CallTypes.BLOCKED to Calls.BLOCKED_TYPE,
            CallTypes.ANSWERED_EXTERNALLY to Calls.ANSWERED_EXTERNALLY_TYPE,
        )
        val PROVIDER_TO_TYPE = TYPE_TO_PROVIDER.entries.associate { (k, v) -> v to k }

        val PRESENTATIONS = mapOf(
            Calls.PRESENTATION_ALLOWED to Presentations.ALLOWED,
            Calls.PRESENTATION_RESTRICTED to Presentations.RESTRICTED,
            Calls.PRESENTATION_UNKNOWN to Presentations.UNKNOWN,
            Calls.PRESENTATION_PAYPHONE to Presentations.PAYPHONE,
        )
    }
}
