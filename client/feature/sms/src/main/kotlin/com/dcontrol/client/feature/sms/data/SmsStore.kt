package com.dcontrol.client.feature.sms.data

import android.database.Cursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.TextBasedSmsColumns
import com.dcontrol.client.core.telephony.ContactNames
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.telephony.int
import com.dcontrol.client.core.telephony.long
import com.dcontrol.client.core.telephony.mapRows
import com.dcontrol.client.core.telephony.rows
import com.dcontrol.client.core.telephony.string
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.Cursors
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageBoxes
import com.dcontrol.client.core.websocket.protocol.MessageKinds
import com.dcontrol.client.core.websocket.protocol.MessageStatuses
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.SmsMessage
import com.dcontrol.client.core.websocket.protocol.SmsMessagesResult
import com.dcontrol.client.core.websocket.protocol.SmsThread
import com.dcontrol.client.core.websocket.protocol.SmsThreadsResult
import com.dcontrol.client.core.websocket.protocol.ThreadParticipant
import com.dcontrol.client.core.websocket.protocol.fillPage
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only SMS access. Threads come from `content://mms-sms/conversations?simple=true` with canonical
 * addresses; OEMs whose threads URI fails fall back to grouping `content://sms` by `thread_id`. Messages
 * merge SMS rows with MMS rows (MMS as a placeholder; parts are never read).
 */
@Singleton
class SmsStore @Inject constructor(
    private val queries: ContentQueries,
    private val names: ContactNames,
    private val sims: SimCards,
    private val codec: ProtocolCodec,
) {
    // ---- threads ----

    fun threads(cursor: String?): SmsThreadsResult {
        val after = cursor?.let { decodeLongs(it, 2) }
        val rows = conversationRows(after) ?: fallbackThreadRows(after)
        val unread by lazy { unreadCounts() }
        val mmsThreads by lazy { mmsThreadIds() }
        val threads = rows.asSequence().mapNotNull { row ->
            val participants = row.addresses.asSequence()
                .mapNotNull { Phase3Text.phoneNumber(it) }
                .distinct()
                .take(Phase3Limits.MAX_PARTICIPANTS)
                .map { ThreadParticipant(it, names.nameFor(it)) }
                .toList()
            if (participants.isEmpty()) return@mapNotNull null
            SmsThread(
                threadId = row.threadId,
                participants = participants,
                snippet = Phase3Text.cleanOrNull(row.snippet, Phase3Limits.MAX_SNIPPET),
                date = Phase3Text.timestamp(row.date),
                unreadCount = row.unread ?: unread[row.threadId] ?: 0,
                messageCount = row.messageCount.coerceAtLeast(0),
                hasMms = row.threadId in mmsThreads,
            )
        }
        val page = fillPage(codec, SmsThread.serializer(), threads, Phase3Limits.MAX_THREADS_PAGE)
        val last = page.items.lastOrNull()
        val next = if (page.hasMore && last != null) Cursors.encode(millis(last.date).toString(), last.threadId.toString()) else null
        return SmsThreadsResult(page.items, next)
    }

    /** Null when the threads URI isn't usable on this device. */
    private fun conversationRows(after: List<Long>?): List<ThreadRow>? {
        val (selection, args) = keyset(after, THREAD_DATE, THREAD_ID)
        val cursor = try {
            queries.query(CONVERSATIONS, THREAD_PROJECTION, selection, args, "$THREAD_DATE DESC, $THREAD_ID DESC")
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val addresses by lazy { canonicalAddresses() }
        return cursor.mapRows { c ->
            val count = c.int(THREAD_MESSAGE_COUNT)
            if (count <= 0) return@mapRows null
            ThreadRow(
                threadId = c.long(THREAD_ID),
                date = c.long(THREAD_DATE),
                messageCount = count,
                snippet = c.string(THREAD_SNIPPET),
                addresses = c.string(THREAD_RECIPIENT_IDS).orEmpty().split(' ')
                    .mapNotNull { it.toLongOrNull()?.let(addresses::get) },
                unread = null,
            )
        }
    }

    private fun fallbackThreadRows(after: List<Long>?): List<ThreadRow> {
        val cursor = queries.query(
            Sms.CONTENT_URI,
            arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.READ, Sms.TYPE),
            null,
            null,
            "${Sms.DATE} DESC, ${Sms._ID} DESC",
        ) ?: throw CommandException(ErrorCodes.UNSUPPORTED, "This device has no SMS provider")
        val byThread = LinkedHashMap<Long, ThreadRow>()
        cursor.mapRows { c ->
            val threadId = c.long(Sms.THREAD_ID)
            val unread = c.int(Sms.READ) == 0 && c.int(Sms.TYPE) == Sms.MESSAGE_TYPE_INBOX
            val existing = byThread[threadId]
            if (existing == null) {
                byThread[threadId] = ThreadRow(
                    threadId = threadId,
                    date = c.long(Sms.DATE),
                    messageCount = 1,
                    snippet = c.string(Sms.BODY),
                    addresses = listOfNotNull(c.string(Sms.ADDRESS)),
                    unread = if (unread) 1 else 0,
                )
            } else {
                val address = c.string(Sms.ADDRESS)
                byThread[threadId] = existing.copy(
                    messageCount = existing.messageCount + 1,
                    addresses = if (address != null && address !in existing.addresses) existing.addresses + address else existing.addresses,
                    unread = (existing.unread ?: 0) + if (unread) 1 else 0,
                )
            }
            Unit
        }
        return byThread.values
            .sortedWith(compareByDescending<ThreadRow> { it.date }.thenByDescending { it.threadId })
            .filter { after == null || it.date < after[0] || (it.date == after[0] && it.threadId < after[1]) }
    }

    private fun canonicalAddresses(): Map<Long, String> = try {
        queries.query(CANONICAL_ADDRESSES, arrayOf(ID, "address"), null, null, null)
            .mapRows { c -> c.string("address")?.let { c.long(ID) to it } }
            .toMap()
    } catch (_: RuntimeException) {
        emptyMap()
    }

    private fun unreadCounts(): Map<Long, Int> = queries.query(
        Sms.CONTENT_URI,
        arrayOf(Sms.THREAD_ID),
        "${Sms.READ} = 0 AND ${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}",
        null,
        null,
    ).mapRows { it.long(Sms.THREAD_ID) }.groupingBy { it }.eachCount()

    private fun mmsThreadIds(): Set<Long> = try {
        queries.query(Mms.CONTENT_URI, arrayOf(Mms.THREAD_ID), null, null, null).mapRows { it.long(Mms.THREAD_ID) }.toSet()
    } catch (_: RuntimeException) {
        emptySet()
    }

    // ---- messages ----

    fun messages(threadId: Long, cursor: String?): SmsMessagesResult {
        if (threadId < 0) throw CommandException(ErrorCodes.VALIDATION, "Invalid threadId")
        val after = cursor?.let { decodeMessageCursor(it) }
        val smsCursor = queries.query(
            Sms.CONTENT_URI,
            SMS_PROJECTION,
            "${Sms.THREAD_ID} = ?" + (after?.let { " AND ${Sms.DATE} <= ?" } ?: ""),
            listOfNotNull(threadId.toString(), after?.date?.toString()).toTypedArray(),
            "${Sms.DATE} DESC, ${Sms._ID} DESC",
        ) ?: throw CommandException(ErrorCodes.UNSUPPORTED, "This device has no SMS provider")
        val mmsCursor = try {
            queries.query(
                Mms.CONTENT_URI,
                MMS_PROJECTION,
                "${Mms.THREAD_ID} = ?" + (after?.let { " AND ${Mms.DATE} <= ?" } ?: ""),
                listOfNotNull(threadId.toString(), after?.let { (it.date / 1000).toString() }).toTypedArray(),
                "${Mms.DATE} DESC, ${Mms._ID} DESC",
            )
        } catch (_: RuntimeException) {
            null
        }
        val labels = HashMap<Int, String?>()
        return smsCursor.use { sms ->
            mmsCursor.use { mms ->
                val smsRows = sms.rows().map { smsKey(it) to toSms(it, labels) }
                val mmsRows = mms?.rows()?.map { mmsKey(it) to mmsPlaceholder(it, labels) } ?: emptySequence()
                val merged = mergeNewestFirst(smsRows.iterator(), mmsRows.iterator())
                    .filter { (key, _) -> after == null || KEY_ORDER.compare(key, after) > 0 }
                    .map { (_, message) -> message() }
                val page = fillPage(codec, SmsMessage.serializer(), merged, Phase3Limits.MAX_MESSAGES_PAGE)
                val last = page.items.lastOrNull()
                val next = if (page.hasMore && last != null) Cursors.encode(millis(last.date).toString(), last.kind, last.id.toString()) else null
                SmsMessagesResult(threadId, page.items, next)
            }
        }
    }

    /** Inbox SMS rows with `_id` greater than [afterId], oldest first, for `sms.received`. */
    fun inboxSince(afterId: Long, limit: Int = MAX_RECEIVED_BURST): List<Pair<Long, SmsMessage>> {
        val labels = HashMap<Int, String?>()
        return queries.query(
            Sms.CONTENT_URI,
            SMS_PROJECTION,
            "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX} AND ${Sms._ID} > ?",
            arrayOf(afterId.toString()),
            "${Sms._ID} ASC",
        ).use { cursor ->
            cursor?.rows()?.take(limit)?.map { it.long(Sms.THREAD_ID) to toSms(it, labels)() }?.toList().orEmpty()
        }
    }

    fun maxInboxId(): Long = queries.query(
        Sms.CONTENT_URI,
        arrayOf(Sms._ID),
        "${Sms.TYPE} = ${Sms.MESSAGE_TYPE_INBOX}",
        null,
        "${Sms._ID} DESC",
    ).use { cursor -> if (cursor != null && cursor.moveToFirst()) cursor.long(Sms._ID) else 0L }

    // ---- mapping ----

    /** Reads the row now (the cursor moves on) and returns a builder for the message. */
    private fun toSms(c: Cursor, labels: MutableMap<Int, String?>): () -> SmsMessage {
        val message = SmsMessage(
            id = c.long(Sms._ID),
            kind = MessageKinds.SMS,
            box = SMS_BOXES[c.int(Sms.TYPE)] ?: MessageBoxes.INBOX,
            address = Phase3Text.phoneNumber(c.string(Sms.ADDRESS)),
            body = Phase3Text.clean(c.string(Sms.BODY), Phase3Limits.MAX_BODY, multiline = true),
            date = Phase3Text.timestamp(c.long(Sms.DATE)),
            read = c.int(Sms.READ) != 0,
            status = smsStatus(c.int(Sms.STATUS, Sms.STATUS_NONE)),
            simLabel = simLabel(c, Sms.SUBSCRIPTION_ID, labels),
        )
        return { message }
    }

    private fun mmsPlaceholder(c: Cursor, labels: MutableMap<Int, String?>): () -> SmsMessage {
        val id = c.long(Mms._ID)
        val box = MMS_BOXES[c.int(Mms.MESSAGE_BOX)] ?: MessageBoxes.INBOX
        val subject = Phase3Text.cleanOrNull(c.string(Mms.SUBJECT), Phase3Limits.MAX_MMS_SUMMARY - 6)
        val date = c.long(Mms.DATE) * 1000
        val read = c.int(Mms.READ) != 0
        val simLabel = simLabel(c, Mms.SUBSCRIPTION_ID, labels)
        // The sender/recipient lives in the addr table: only looked up for messages that make it into the page.
        return {
            SmsMessage(
                id = id,
                kind = MessageKinds.MMS,
                box = box,
                address = mmsAddress(id, if (box == MessageBoxes.INBOX) MMS_FROM else MMS_TO),
                body = null,
                date = Phase3Text.timestamp(date),
                read = read,
                status = MessageStatuses.NONE,
                simLabel = simLabel,
                mmsSummary = if (subject == null) "MMS" else "MMS · $subject",
            )
        }
    }

    private fun mmsAddress(id: Long, type: Int): String? = try {
        queries.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = ?", arrayOf(type.toString()), null)
            .mapRows { c -> c.string("address")?.takeIf { it != MMS_ADDRESS_TOKEN }?.let(Phase3Text::phoneNumber) }
            .firstOrNull()
    } catch (_: RuntimeException) {
        null
    }

    private fun simLabel(c: Cursor, column: String, labels: MutableMap<Int, String?>): String? {
        val id = c.int(column, -1).takeIf { it >= 0 } ?: return null
        return labels.getOrPut(id) { sims.labelForSubscription(id) }
    }

    private fun smsStatus(status: Int) = when {
        status >= TextBasedSmsColumns.STATUS_FAILED -> MessageStatuses.FAILED
        status >= TextBasedSmsColumns.STATUS_PENDING -> MessageStatuses.PENDING
        status == TextBasedSmsColumns.STATUS_COMPLETE -> MessageStatuses.COMPLETE
        else -> MessageStatuses.NONE
    }

    // ---- ordering and cursors ----

    /** Order key: newest first; within one millisecond SMS before MMS, then higher ids first. */
    private data class Key(val date: Long, val kind: String, val id: Long)

    private fun smsKey(c: Cursor) = Key(c.long(Sms.DATE), MessageKinds.SMS, c.long(Sms._ID))

    private fun mmsKey(c: Cursor) = Key(c.long(Mms.DATE) * 1000, MessageKinds.MMS, c.long(Mms._ID))

    private fun <T> mergeNewestFirst(a: Iterator<Pair<Key, T>>, b: Iterator<Pair<Key, T>>): Sequence<Pair<Key, T>> = sequence {
        var left = if (a.hasNext()) a.next() else null
        var right = if (b.hasNext()) b.next() else null
        while (left != null || right != null) {
            if (right == null || (left != null && KEY_ORDER.compare(left.first, right.first) <= 0)) {
                yield(left!!)
                left = if (a.hasNext()) a.next() else null
            } else {
                yield(right)
                right = if (b.hasNext()) b.next() else null
            }
        }
    }

    private fun decodeMessageCursor(cursor: String): Key {
        val parts = Cursors.decode(cursor, 3) ?: throw CommandException(ErrorCodes.VALIDATION, "Invalid cursor")
        val date = parts[0].toLongOrNull()
        val id = parts[2].toLongOrNull()
        if (date == null || id == null || parts[1] !in setOf(MessageKinds.SMS, MessageKinds.MMS)) {
            throw CommandException(ErrorCodes.VALIDATION, "Invalid cursor")
        }
        return Key(date, parts[1], id)
    }

    private fun decodeLongs(cursor: String, count: Int): List<Long> =
        Cursors.decode(cursor, count)?.map { it.toLongOrNull() ?: throw CommandException(ErrorCodes.VALIDATION, "Invalid cursor") }
            ?: throw CommandException(ErrorCodes.VALIDATION, "Invalid cursor")

    private fun keyset(after: List<Long>?, dateColumn: String, idColumn: String): Pair<String?, Array<String>?> {
        if (after == null) return null to null
        return "($dateColumn < ? OR ($dateColumn = ? AND $idColumn < ?))" to
            arrayOf(after[0].toString(), after[0].toString(), after[1].toString())
    }

    private fun millis(date: String) = Instant.parse(date).toEpochMilli()

    private data class ThreadRow(
        val threadId: Long,
        val date: Long,
        val messageCount: Int,
        val snippet: String?,
        val addresses: List<String>,
        val unread: Int?,
    )

    private companion object {
        const val MAX_RECEIVED_BURST = 20
        const val ID = "_id"
        const val THREAD_ID = "_id"
        const val THREAD_DATE = "date"
        const val THREAD_MESSAGE_COUNT = "message_count"
        const val THREAD_RECIPIENT_IDS = "recipient_ids"
        const val THREAD_SNIPPET = "snippet"
        const val MMS_FROM = 137
        const val MMS_TO = 151
        const val MMS_ADDRESS_TOKEN = "insert-address-token"

        val CONVERSATIONS: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL_ADDRESSES: Uri = Uri.parse("content://mms-sms/canonical-addresses")

        val THREAD_PROJECTION = arrayOf(THREAD_ID, THREAD_DATE, THREAD_MESSAGE_COUNT, THREAD_RECIPIENT_IDS, THREAD_SNIPPET)
        val SMS_PROJECTION = arrayOf(
            Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.READ, Sms.TYPE, Sms.STATUS, Sms.SUBSCRIPTION_ID,
        )
        val MMS_PROJECTION = arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.READ, Mms.MESSAGE_BOX, Mms.SUBJECT, Mms.SUBSCRIPTION_ID)

        val SMS_BOXES = mapOf(
            Sms.MESSAGE_TYPE_ALL to MessageBoxes.INBOX,
            Sms.MESSAGE_TYPE_INBOX to MessageBoxes.INBOX,
            Sms.MESSAGE_TYPE_SENT to MessageBoxes.SENT,
            Sms.MESSAGE_TYPE_DRAFT to MessageBoxes.DRAFT,
            Sms.MESSAGE_TYPE_OUTBOX to MessageBoxes.OUTBOX,
            Sms.MESSAGE_TYPE_FAILED to MessageBoxes.FAILED,
            Sms.MESSAGE_TYPE_QUEUED to MessageBoxes.QUEUED,
        )
        val MMS_BOXES = mapOf(
            Mms.MESSAGE_BOX_INBOX to MessageBoxes.INBOX,
            Mms.MESSAGE_BOX_SENT to MessageBoxes.SENT,
            Mms.MESSAGE_BOX_DRAFTS to MessageBoxes.DRAFT,
            Mms.MESSAGE_BOX_OUTBOX to MessageBoxes.OUTBOX,
            Mms.MESSAGE_BOX_FAILED to MessageBoxes.FAILED,
        )

        val KINDS = mapOf(MessageKinds.SMS to 0, MessageKinds.MMS to 1)

        /** Smaller = newer = earlier in the list. */
        val KEY_ORDER: Comparator<Key> = compareByDescending<Key> { it.date }
            .thenBy { KINDS.getValue(it.kind) }
            .thenByDescending { it.id }
    }
}
