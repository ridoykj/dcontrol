package com.dcontrol.client.core.websocket.protocol

import kotlinx.serialization.Serializable

// Payloads of the Phase 3 additions in docs/protocol/ws-v1.md (contacts, call log, SMS).
// Paging uses opaque `cursor` / `nextCursor` tokens made by the phone; `null` means first / last page.

/** Shared limits of the Phase 3 contract. */
object Phase3Limits {
    const val MAX_CURSOR = 256
    const val MAX_PHONE_NUMBER = 64
    const val MAX_NAME = 200
    const val MAX_LABEL = 100
    const val MAX_EMAIL = 254
    const val MAX_NOTE = 2000
    const val MAX_LOOKUP_KEY = 512
    const val MAX_VERSION = 256
    const val MAX_ACCOUNT_FIELD = 255
    const val MAX_QUERY = 100
    const val MAX_CONTACT_ITEMS = 20
    const val MAX_SUMMARY_PHONES = 3
    const val MAX_SUMMARY_EMAILS = 1
    const val MAX_CONTACTS_PAGE = 500
    const val MAX_ACCOUNTS = 32
    const val MAX_DELETE = 100
    const val MAX_CALLS_PAGE = 500
    const val MAX_THREADS_PAGE = 200
    const val MAX_MESSAGES_PAGE = 500
    const val MAX_PARTICIPANTS = 10
    const val MAX_SNIPPET = 200
    const val MAX_BODY = 2000
    const val MAX_MMS_SUMMARY = 200
    const val MAX_SMS_TEXT = 1600
    const val MAX_SMS_PARTS = 20
    const val MAX_SIMS = 8
    const val MAX_ERROR_CODE = 64

    /** Encoded page budget, same as `files.list`. */
    const val PAGE_BUDGET_BYTES = 48 * 1024
}

/** Ordered by display name (contacts) or newest first (calls, threads, messages); see [Phase3Limits]. */
@Serializable
data class PageCursorRequest(val cursor: String? = null)

// ---- contacts ----

@Serializable
data class AccountRef(val type: String? = null, val name: String? = null)

@Serializable
data class ContactAccount(val type: String? = null, val name: String? = null, val label: String)

@Serializable
data class ContactsListRequest(val query: String? = null, val cursor: String? = null)

@Serializable
data class ContactSummary(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val starred: Boolean,
    val photo: Boolean,
    val phones: List<String>,
    val emails: List<String>,
)

@Serializable
data class ContactsListResult(val items: List<ContactSummary>, val nextCursor: String? = null)

@Serializable
data class ContactRef(val id: Long, val lookupKey: String)

@Serializable
data class ContactName(
    val display: String = "",
    val given: String? = null,
    val middle: String? = null,
    val family: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
)

object PhoneTypes {
    const val MOBILE = "MOBILE"
    const val HOME = "HOME"
    const val WORK = "WORK"
    const val MAIN = "MAIN"
    const val WORK_MOBILE = "WORK_MOBILE"
    const val FAX = "FAX"
    const val PAGER = "PAGER"
    const val OTHER = "OTHER"
    const val CUSTOM = "CUSTOM"
    val ALL = setOf(MOBILE, HOME, WORK, MAIN, WORK_MOBILE, FAX, PAGER, OTHER, CUSTOM)
}

object EmailTypes {
    const val HOME = "HOME"
    const val WORK = "WORK"
    const val MOBILE = "MOBILE"
    const val OTHER = "OTHER"
    const val CUSTOM = "CUSTOM"
    val ALL = setOf(HOME, WORK, MOBILE, OTHER, CUSTOM)
}

@Serializable
data class ContactPhone(val number: String, val type: String, val label: String? = null)

@Serializable
data class ContactEmail(val address: String, val type: String, val label: String? = null)

@Serializable
data class ContactOrganization(val company: String? = null, val title: String? = null)

@Serializable
data class ContactPayload(
    val id: Long,
    val lookupKey: String,
    val version: String,
    val account: ContactAccount? = null,
    val readOnly: Boolean,
    val starred: Boolean,
    val photo: Boolean,
    val name: ContactName,
    val phones: List<ContactPhone>,
    val emails: List<ContactEmail>,
    val organization: ContactOrganization? = null,
    val note: String? = null,
)

/** `Contact` without ids, version, account, readOnly, photo and `name.display`. */
@Serializable
data class ContactFields(
    val name: ContactName = ContactName(),
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<ContactEmail> = emptyList(),
    val organization: ContactOrganization? = null,
    val note: String? = null,
    val starred: Boolean = false,
)

@Serializable
data class ContactsAccountsResult(val accounts: List<ContactAccount>)

@Serializable
data class ContactsCreateRequest(val account: AccountRef? = null, val fields: ContactFields)

@Serializable
data class ContactsUpdateRequest(val id: Long, val lookupKey: String, val version: String, val fields: ContactFields)

@Serializable
data class ContactsDeleteRequest(val items: List<ContactRef>)

@Serializable
data class ContactDeleteFailure(val id: Long, val code: String, val message: String)

@Serializable
data class ContactsDeleteResult(val deleted: Int, val failures: List<ContactDeleteFailure>)

@Serializable
data class ContactsPhotoRequest(val uploadId: String, val id: Long, val lookupKey: String)

// ---- calls ----

object CallTypes {
    const val INCOMING = "INCOMING"
    const val OUTGOING = "OUTGOING"
    const val MISSED = "MISSED"
    const val VOICEMAIL = "VOICEMAIL"
    const val REJECTED = "REJECTED"
    const val BLOCKED = "BLOCKED"
    const val ANSWERED_EXTERNALLY = "ANSWERED_EXTERNALLY"
    const val OTHER = "OTHER"

    /** Valid `calls.list` filters (`OTHER` is not one). */
    val FILTERS = setOf(INCOMING, OUTGOING, MISSED, VOICEMAIL, REJECTED, BLOCKED, ANSWERED_EXTERNALLY)
}

object Presentations {
    const val ALLOWED = "ALLOWED"
    const val RESTRICTED = "RESTRICTED"
    const val UNKNOWN = "UNKNOWN"
    const val PAYPHONE = "PAYPHONE"
}

@Serializable
data class CallsListRequest(val type: String? = null, val cursor: String? = null)

@Serializable
data class CallEntry(
    val id: Long,
    val number: String? = null,
    val presentation: String,
    val name: String? = null,
    val type: String,
    val date: String,
    val durationSec: Long,
    val simLabel: String? = null,
    val isNew: Boolean,
)

@Serializable
data class CallsListResult(val items: List<CallEntry>, val nextCursor: String? = null)

@Serializable
data class CallsDeleteRequest(val ids: List<Long>)

@Serializable
data class CallsDeleteResult(val deleted: Int)

@Serializable
data class CallPlaceRequest(val number: String, val simId: Int? = null)

object AccessStates {
    const val GRANTED = "GRANTED"
    const val MISSING = "MISSING"
}

@Serializable
data class SimInfo(val id: Int, val label: String, val slot: Int, val carrier: String? = null)

@Serializable
data class SimsResult(
    val hasTelephony: Boolean,
    val access: String,
    val defaultSmsSimId: Int? = null,
    val defaultCallSimId: Int? = null,
    val sims: List<SimInfo>,
)

// ---- SMS ----

object MessageKinds {
    const val SMS = "SMS"
    const val MMS = "MMS"
}

object MessageBoxes {
    const val INBOX = "INBOX"
    const val SENT = "SENT"
    const val OUTBOX = "OUTBOX"
    const val FAILED = "FAILED"
    const val QUEUED = "QUEUED"
    const val DRAFT = "DRAFT"
}

object MessageStatuses {
    const val NONE = "NONE"
    const val PENDING = "PENDING"
    const val COMPLETE = "COMPLETE"
    const val FAILED = "FAILED"
}

object SmsSendStatuses {
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
}

@Serializable
data class ThreadParticipant(val address: String, val name: String? = null)

@Serializable
data class SmsThread(
    val threadId: Long,
    val participants: List<ThreadParticipant>,
    val snippet: String? = null,
    val date: String,
    val unreadCount: Int,
    val messageCount: Int,
    val hasMms: Boolean,
)

@Serializable
data class SmsThreadsResult(val items: List<SmsThread>, val nextCursor: String? = null)

@Serializable
data class SmsMessagesRequest(val threadId: Long, val cursor: String? = null)

@Serializable
data class SmsMessage(
    val id: Long,
    val kind: String,
    val box: String,
    val address: String? = null,
    val body: String? = null,
    val date: String,
    val read: Boolean,
    val status: String,
    val simLabel: String? = null,
    val mmsSummary: String? = null,
)

@Serializable
data class SmsMessagesResult(val threadId: Long, val items: List<SmsMessage>, val nextCursor: String? = null)

@Serializable
data class SmsSendRequest(val to: String, val text: String, val simId: Int? = null, val clientRef: String)

@Serializable
data class SmsSendResult(val parts: Int)

@Serializable
data class SmsReceivedEvent(val threadId: Long, val message: SmsMessage)

@Serializable
data class SmsStatusEvent(val clientRef: String, val status: String, val errorCode: String? = null)
