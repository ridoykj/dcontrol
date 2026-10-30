package com.dcontrol.client.feature.contacts.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.OperationApplicationException
import android.content.res.Resources
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.core.telephony.int
import com.dcontrol.client.core.telephony.long
import com.dcontrol.client.core.telephony.mapRows
import com.dcontrol.client.core.telephony.string
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.AccountRef
import com.dcontrol.client.core.websocket.protocol.ContactAccount
import com.dcontrol.client.core.websocket.protocol.ContactDeleteFailure
import com.dcontrol.client.core.websocket.protocol.ContactEmail
import com.dcontrol.client.core.websocket.protocol.ContactFields
import com.dcontrol.client.core.websocket.protocol.ContactName
import com.dcontrol.client.core.websocket.protocol.ContactOrganization
import com.dcontrol.client.core.websocket.protocol.ContactPayload
import com.dcontrol.client.core.websocket.protocol.ContactPhone
import com.dcontrol.client.core.websocket.protocol.ContactRef
import com.dcontrol.client.core.websocket.protocol.ContactSummary
import com.dcontrol.client.core.websocket.protocol.ContactsDeleteResult
import com.dcontrol.client.core.websocket.protocol.ContactsListResult
import com.dcontrol.client.core.websocket.protocol.Cursors
import com.dcontrol.client.core.websocket.protocol.EmailTypes
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.Phase3Text
import com.dcontrol.client.core.websocket.protocol.PhoneTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.fillPage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.security.MessageDigest
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contacts through `ContactsContract`. The web edits a merged contact; writes go to its raw contacts:
 * - the editable kinds (name, phone, email, organization, note) are deleted from every writable raw contact and
 *   inserted again on the first writable one, skipping values a read-only raw contact already shows;
 * - `version` is built from the raw contacts' `VERSION`s and asserted inside the same batch.
 */
@Singleton
class ContactsStore internal constructor(
    private val queries: ContentQueries,
    private val accounts: ContactAccounts,
    private val resources: Resources,
    private val codec: ProtocolCodec,
) {
    @Inject
    constructor(
        queries: ContentQueries,
        accounts: ContactAccounts,
        @ApplicationContext context: Context,
        codec: ProtocolCodec,
    ) : this(queries, accounts, context.resources, codec)

    // ---- list ----

    fun list(query: String?, cursor: String?): ContactsListResult {
        val after = cursor?.let { Cursors.decode(it, 2) ?: invalid("Invalid cursor") }
        val search = query?.trim()?.takeIf { it.isNotEmpty() }
        if (search != null && search.length > Phase3Limits.MAX_QUERY) invalid("query is too long")
        val uri = if (search == null) Contacts.CONTENT_URI else Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(search))
        val rows = queries.query(uri, LIST_PROJECTION, null, null, "${Contacts.SORT_KEY_PRIMARY} ASC, ${Contacts._ID} ASC")
            .mapRows { c ->
                val lookupKey = c.string(Contacts.LOOKUP_KEY)?.takeIf { it.isNotEmpty() && it.length <= Phase3Limits.MAX_LOOKUP_KEY }
                    ?: return@mapRows null
                ListRow(
                    id = c.long(Contacts._ID),
                    lookupKey = lookupKey,
                    name = Phase3Text.clean(c.string(Contacts.DISPLAY_NAME_PRIMARY), Phase3Limits.MAX_NAME).orEmpty(),
                    sortKey = c.string(Contacts.SORT_KEY_PRIMARY).orEmpty(),
                    starred = c.int(Contacts.STARRED) != 0,
                    photo = c.long(Contacts.PHOTO_ID) != 0L || c.string(Contacts.PHOTO_THUMBNAIL_URI) != null,
                )
            }
        val start = after?.let { startAfter(rows, it[0], it[1].toLongOrNull() ?: invalid("Invalid cursor")) } ?: 0
        val candidates = rows.subList(start.coerceAtMost(rows.size), (start + Phase3Limits.MAX_CONTACTS_PAGE).coerceAtMost(rows.size))
        val details = summaryDetails(candidates.map { it.id })
        val summaries = candidates.map { row ->
            val (phones, emails) = details[row.id] ?: (emptyList<String>() to emptyList())
            ContactSummary(row.id, row.lookupKey, row.name, row.starred, row.photo, phones, emails)
        }
        val page = fillPage(codec, ContactSummary.serializer(), summaries.asSequence(), Phase3Limits.MAX_CONTACTS_PAGE)
        val more = page.hasMore || start + candidates.size < rows.size
        val last = page.items.lastOrNull()?.let { item -> candidates.first { it.id == item.id } }
        return ContactsListResult(page.items, if (more && last != null) Cursors.encode(last.sortKey.take(CURSOR_SORT_KEY), last.id.toString()) else null)
    }

    /** Index after the cursor's row; if that contact is gone, the first row that sorts after it. */
    private fun startAfter(rows: List<ListRow>, sortKey: String, id: Long): Int {
        val exact = rows.indexOfFirst { it.id == id }
        if (exact >= 0) return exact + 1
        val collator = Collator.getInstance()
        val next = rows.indexOfFirst { collator.compare(it.sortKey, sortKey) > 0 || (it.sortKey == sortKey && it.id > id) }
        return if (next < 0) rows.size else next
    }

    private fun summaryDetails(ids: List<Long>): Map<Long, Pair<List<String>, List<String>>> {
        if (ids.isEmpty()) return emptyMap()
        val phones = HashMap<Long, MutableList<String>>()
        val emails = HashMap<Long, MutableList<String>>()
        ids.chunked(IN_CHUNK).forEach { chunk ->
            queries.query(
                Data.CONTENT_URI,
                arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1),
                "${Data.CONTACT_ID} IN (${placeholders(chunk.size)}) AND ${Data.MIMETYPE} IN (?, ?)",
                (chunk.map { it.toString() } + listOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE)).toTypedArray(),
                "${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID} ASC",
            ).mapRows { c ->
                val contactId = c.long(Data.CONTACT_ID)
                when (c.string(Data.MIMETYPE)) {
                    Phone.CONTENT_ITEM_TYPE -> Phase3Text.phoneNumber(c.string(Data.DATA1))?.let { number ->
                        val list = phones.getOrPut(contactId) { mutableListOf() }
                        if (list.size < Phase3Limits.MAX_SUMMARY_PHONES && number !in list) list += number
                    }
                    Email.CONTENT_ITEM_TYPE -> email(c.string(Data.DATA1))?.let { address ->
                        val list = emails.getOrPut(contactId) { mutableListOf() }
                        if (list.size < Phase3Limits.MAX_SUMMARY_EMAILS) list += address
                    }
                }
            }
        }
        return ids.associateWith { (phones[it].orEmpty()) to (emails[it].orEmpty()) }
    }

    // ---- read ----

    fun accounts(): List<ContactAccount> {
        val seen = LinkedHashSet<Pair<String?, String?>>()
        seen += null to null
        queries.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME), "${RawContacts.DELETED} = 0", null, null)
            .mapRows { c -> seen += c.string(RawContacts.ACCOUNT_TYPE) to c.string(RawContacts.ACCOUNT_NAME) }
        return seen.asSequence()
            .filter { (type, name) -> (type == null) == (name == null) && accounts.isWritable(type) }
            .filter { (type, name) -> type == null || (validAccountField(type) && validAccountField(name)) }
            .map { (type, name) -> ContactAccount(type, name, accounts.label(type, name)) }
            .take(Phase3Limits.MAX_ACCOUNTS)
            .toList()
    }

    fun get(ref: ContactRef): ContactPayload = load(resolve(ref))

    fun openPhoto(ref: ContactRef): InputStream? {
        val contactId = resolve(ref)
        return queries.openContactPhoto(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId))
    }

    private fun load(contactId: Long): ContactPayload {
        val contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)
        val contact = queries.query(contactUri, LIST_PROJECTION, null, null, null).mapRows { c ->
            Triple(c.string(Contacts.LOOKUP_KEY), c.string(Contacts.DISPLAY_NAME_PRIMARY), c.int(Contacts.STARRED) != 0) to
                (c.long(Contacts.PHOTO_ID) != 0L || c.string(Contacts.PHOTO_THUMBNAIL_URI) != null)
        }.firstOrNull() ?: notFound()
        val (row, photo) = contact
        val lookupKey = row.first?.takeIf { it.isNotEmpty() && it.length <= Phase3Limits.MAX_LOOKUP_KEY } ?: notFound()
        val raws = rawContacts(contactId)
        if (raws.isEmpty()) notFound()
        val primary = raws.firstOrNull { it.writable } ?: raws.first()
        val data = dataRows(contactId)

        val nameRow = data.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE && it.rawId == primary.id }
            ?: data.firstOrNull { it.mime == StructuredName.CONTENT_ITEM_TYPE }
        val name = ContactName(
            display = Phase3Text.clean(row.second, Phase3Limits.MAX_NAME).orEmpty(),
            given = nameRow?.text(StructuredName.GIVEN_NAME),
            middle = nameRow?.text(StructuredName.MIDDLE_NAME),
            family = nameRow?.text(StructuredName.FAMILY_NAME),
            prefix = nameRow?.text(StructuredName.PREFIX),
            suffix = nameRow?.text(StructuredName.SUFFIX),
        )
        val phones = data.filter { it.mime == Phone.CONTENT_ITEM_TYPE }
            .mapNotNull { it.toPhone() }
            .distinctBy { normalize(it.number) to it.type }
            .take(Phase3Limits.MAX_CONTACT_ITEMS)
        val emails = data.filter { it.mime == Email.CONTENT_ITEM_TYPE }
            .mapNotNull { it.toEmail() }
            .distinctBy { it.address.lowercase() to it.type }
            .take(Phase3Limits.MAX_CONTACT_ITEMS)
        val organization = data.firstOrNull { it.mime == Organization.CONTENT_ITEM_TYPE }?.let {
            val company = it.text(Organization.COMPANY)
            val title = it.text(Organization.TITLE)
            if (company == null && title == null) null else ContactOrganization(company, title)
        }
        val note = data.firstNotNullOfOrNull { row -> row.takeIf { it.mime == Note.CONTENT_ITEM_TYPE }?.text(Note.NOTE, Phase3Limits.MAX_NOTE, multiline = true) }
        val account = primary.accountType?.let { type ->
            ContactAccount(type, primary.accountName, accounts.label(type, primary.accountName))
        }
        return ContactPayload(
            id = contactId,
            lookupKey = lookupKey,
            version = version(raws),
            account = account?.takeIf { validAccountField(it.type) && validAccountField(it.name) },
            readOnly = raws.none { it.writable },
            starred = row.third,
            photo = photo,
            name = name,
            phones = phones,
            emails = emails,
            organization = organization,
            note = note,
        )
    }

    // ---- write ----

    fun create(account: AccountRef?, raw: ContactFields): ContactPayload {
        val fields = ContactFieldsValidator.validate(raw)
        val target = account?.takeIf { it.type != null || it.name != null }
        if (target != null && accounts().none { it.type == target.type && it.name == target.name }) {
            invalid("Unknown or read-only account")
        }
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, target?.type)
            .withValue(RawContacts.ACCOUNT_NAME, target?.name)
            .withValue(RawContacts.STARRED, if (fields.starred) 1 else 0)
            .build()
        dataInserts(fields, rawId = null, skip = Existing.NONE).forEach { ops += it }
        val results = apply(ops)
        val rawUri = results.firstOrNull()?.uri ?: throw CommandException(ErrorCodes.INTERNAL, "The contact was not created")
        val contactId = queries.query(rawUri, arrayOf(RawContacts.CONTACT_ID), null, null, null)
            .mapRows { it.long(RawContacts.CONTACT_ID, -1) }.firstOrNull()?.takeIf { it >= 0 }
            ?: throw CommandException(ErrorCodes.INTERNAL, "The contact was not created")
        return load(contactId)
    }

    fun update(ref: ContactRef, version: String, raw: ContactFields): ContactPayload {
        val fields = ContactFieldsValidator.validate(raw)
        val contactId = resolve(ref)
        val raws = rawContacts(contactId)
        if (raws.isEmpty()) notFound()
        if (version(raws) != version) throw CommandException(ErrorCodes.CONFLICT, "The contact changed on the phone")
        val writable = raws.filter { it.writable }
        if (writable.isEmpty()) throw CommandException(ErrorCodes.FORBIDDEN, "This contact is read-only")
        val readOnlyIds = raws.filterNot { it.writable }.map { it.id }.toSet()
        val readOnlyData = dataRows(contactId).filter { it.rawId in readOnlyIds }
        val existing = Existing(
            phones = readOnlyData.filter { it.mime == Phone.CONTENT_ITEM_TYPE }.mapNotNull { it.text(Phone.NUMBER, Phase3Limits.MAX_PHONE_NUMBER)?.let(::normalize) }.toSet(),
            emails = readOnlyData.filter { it.mime == Email.CONTENT_ITEM_TYPE }.mapNotNull { it.text(Email.ADDRESS, Phase3Limits.MAX_EMAIL)?.lowercase() }.toSet(),
        )

        val ops = ArrayList<ContentProviderOperation>()
        raws.forEach { rawContact ->
            ops += ContentProviderOperation.newAssertQuery(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContact.id))
                .withValue(RawContacts.VERSION, rawContact.version)
                .withExpectedCount(1)
                .build()
        }
        writable.forEach { rawContact ->
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${placeholders(EDITABLE_TYPES.size)})",
                    arrayOf(rawContact.id.toString(), *EDITABLE_TYPES),
                )
                .build()
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContact.id))
                .withValue(RawContacts.STARRED, if (fields.starred) 1 else 0)
                .build()
        }
        dataInserts(fields, rawId = writable.first().id, skip = existing).forEach { ops += it }
        apply(ops)
        return get(ref)
    }

    fun delete(items: List<ContactRef>): ContactsDeleteResult {
        if (items.isEmpty() || items.size > Phase3Limits.MAX_DELETE) invalid("items must hold 1-${Phase3Limits.MAX_DELETE} contacts")
        var deleted = 0
        val failures = mutableListOf<ContactDeleteFailure>()
        items.forEach { ref ->
            try {
                val contactId = resolve(ref)
                val raws = rawContacts(contactId)
                if (raws.isNotEmpty() && raws.none { it.writable }) {
                    throw CommandException(ErrorCodes.FORBIDDEN, "This contact is read-only")
                }
                if (queries.delete(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), null, null) > 0) {
                    deleted++
                } else {
                    notFound()
                }
            } catch (e: CommandException) {
                failures += ContactDeleteFailure(ref.id, e.code, Phase3Text.truncate(e.message, MAX_MESSAGE))
            }
        }
        return ContactsDeleteResult(deleted, failures)
    }

    // ---- helpers ----

    /** Current contact id for a reference; ids change when contacts are re-aggregated. */
    private fun resolve(ref: ContactRef): Long {
        if (ref.id < 0 || ref.lookupKey.isEmpty() || ref.lookupKey.length > Phase3Limits.MAX_LOOKUP_KEY) invalid("Invalid contact reference")
        val uri = queries.lookupContact(Contacts.getLookupUri(ref.id, ref.lookupKey)) ?: notFound()
        return ContentUris.parseId(uri).takeIf { it >= 0 } ?: notFound()
    }

    private fun rawContacts(contactId: Long): List<RawRow> = queries.query(
        RawContacts.CONTENT_URI,
        arrayOf(RawContacts._ID, RawContacts.VERSION, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
        "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
        arrayOf(contactId.toString()),
        "${RawContacts._ID} ASC",
    ).mapRows { c ->
        val type = c.string(RawContacts.ACCOUNT_TYPE)
        RawRow(c.long(RawContacts._ID), c.long(RawContacts.VERSION), type, c.string(RawContacts.ACCOUNT_NAME), accounts.isWritable(type))
    }

    private fun dataRows(contactId: Long): List<DataRow> = queries.query(
        Data.CONTENT_URI,
        DATA_PROJECTION,
        "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${placeholders(EDITABLE_TYPES.size)})",
        arrayOf(contactId.toString(), *EDITABLE_TYPES),
        "${Data.RAW_CONTACT_ID} ASC, ${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID} ASC",
    ).mapRows { c ->
        DataRow(
            rawId = c.long(Data.RAW_CONTACT_ID),
            mime = c.string(Data.MIMETYPE).orEmpty(),
            values = DATA_COLUMNS.associateWith { column -> c.string(column) },
        )
    }

    private fun dataInserts(fields: ContactFields, rawId: Long?, skip: Existing): List<ContentProviderOperation> {
        fun insert(mime: String): ContentProviderOperation.Builder {
            val builder = ContentProviderOperation.newInsert(Data.CONTENT_URI).withValue(Data.MIMETYPE, mime)
            return if (rawId == null) builder.withValueBackReference(Data.RAW_CONTACT_ID, 0) else builder.withValue(Data.RAW_CONTACT_ID, rawId)
        }
        val ops = mutableListOf<ContentProviderOperation>()
        val name = fields.name
        if (listOf(name.given, name.middle, name.family, name.prefix, name.suffix).any { it != null }) {
            ops += insert(StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, name.given)
                .withValue(StructuredName.MIDDLE_NAME, name.middle)
                .withValue(StructuredName.FAMILY_NAME, name.family)
                .withValue(StructuredName.PREFIX, name.prefix)
                .withValue(StructuredName.SUFFIX, name.suffix)
                .build()
        }
        fields.phones.filterNot { normalize(it.number) in skip.phones }.forEach { phone ->
            val (type, label) = phoneTypeToProvider(phone)
            ops += insert(Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone.number)
                .withValue(Phone.TYPE, type)
                .withValue(Phone.LABEL, label)
                .build()
        }
        fields.emails.filterNot { it.address.lowercase() in skip.emails }.forEach { email ->
            val (type, label) = emailTypeToProvider(email)
            ops += insert(Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, email.address)
                .withValue(Email.TYPE, type)
                .withValue(Email.LABEL, label)
                .build()
        }
        fields.organization?.let { org ->
            ops += insert(Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, org.company)
                .withValue(Organization.TITLE, org.title)
                .withValue(Organization.TYPE, Organization.TYPE_WORK)
                .build()
        }
        fields.note?.let { note -> ops += insert(Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, note).build() }
        return ops
    }

    private fun apply(ops: ArrayList<ContentProviderOperation>) = try {
        queries.applyBatch(ContactsContract.AUTHORITY, ops)
    } catch (_: OperationApplicationException) {
        throw CommandException(ErrorCodes.CONFLICT, "The contact changed on the phone")
    }

    private fun DataRow.toPhone(): ContactPhone? {
        val number = Phase3Text.phoneNumber(values[Phone.NUMBER]) ?: return null
        val type = values[Phone.TYPE]?.toIntOrNull() ?: Phone.TYPE_OTHER
        val label = text(Phone.LABEL, Phase3Limits.MAX_LABEL)
        return when (type) {
            Phone.TYPE_MOBILE -> ContactPhone(number, PhoneTypes.MOBILE)
            Phone.TYPE_HOME -> ContactPhone(number, PhoneTypes.HOME)
            Phone.TYPE_WORK -> ContactPhone(number, PhoneTypes.WORK)
            Phone.TYPE_MAIN -> ContactPhone(number, PhoneTypes.MAIN)
            Phone.TYPE_WORK_MOBILE -> ContactPhone(number, PhoneTypes.WORK_MOBILE)
            Phone.TYPE_FAX_WORK, Phone.TYPE_FAX_HOME, Phone.TYPE_OTHER_FAX -> ContactPhone(number, PhoneTypes.FAX)
            Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> ContactPhone(number, PhoneTypes.PAGER)
            Phone.TYPE_OTHER -> ContactPhone(number, PhoneTypes.OTHER)
            Phone.TYPE_CUSTOM -> if (label == null) ContactPhone(number, PhoneTypes.OTHER) else ContactPhone(number, PhoneTypes.CUSTOM, label)
            // Car, callback, assistant…: kept as a custom label so an edit doesn't silently drop the meaning.
            else -> ContactPhone(
                number,
                PhoneTypes.CUSTOM,
                Phase3Text.cleanOrNull(Phone.getTypeLabel(resources, type, "").toString(), Phase3Limits.MAX_LABEL) ?: "Other",
            )
        }
    }

    private fun DataRow.toEmail(): ContactEmail? {
        val address = email(values[Email.ADDRESS]) ?: return null
        val label = text(Email.LABEL, Phase3Limits.MAX_LABEL)
        return when (values[Email.TYPE]?.toIntOrNull()) {
            Email.TYPE_HOME -> ContactEmail(address, EmailTypes.HOME)
            Email.TYPE_WORK -> ContactEmail(address, EmailTypes.WORK)
            Email.TYPE_MOBILE -> ContactEmail(address, EmailTypes.MOBILE)
            Email.TYPE_CUSTOM -> if (label == null) ContactEmail(address, EmailTypes.OTHER) else ContactEmail(address, EmailTypes.CUSTOM, label)
            else -> ContactEmail(address, EmailTypes.OTHER)
        }
    }

    private fun phoneTypeToProvider(phone: ContactPhone): Pair<Int, String?> = when (phone.type) {
        PhoneTypes.MOBILE -> Phone.TYPE_MOBILE to null
        PhoneTypes.HOME -> Phone.TYPE_HOME to null
        PhoneTypes.WORK -> Phone.TYPE_WORK to null
        PhoneTypes.MAIN -> Phone.TYPE_MAIN to null
        PhoneTypes.WORK_MOBILE -> Phone.TYPE_WORK_MOBILE to null
        PhoneTypes.FAX -> Phone.TYPE_FAX_WORK to null
        PhoneTypes.PAGER -> Phone.TYPE_PAGER to null
        PhoneTypes.CUSTOM -> if (phone.label != null) Phone.TYPE_CUSTOM to phone.label else Phone.TYPE_OTHER to null
        else -> Phone.TYPE_OTHER to null
    }

    private fun emailTypeToProvider(email: ContactEmail): Pair<Int, String?> = when (email.type) {
        EmailTypes.HOME -> Email.TYPE_HOME to null
        EmailTypes.WORK -> Email.TYPE_WORK to null
        EmailTypes.MOBILE -> Email.TYPE_MOBILE to null
        EmailTypes.CUSTOM -> if (email.label != null) Email.TYPE_CUSTOM to email.label else Email.TYPE_OTHER to null
        else -> Email.TYPE_OTHER to null
    }

    private fun DataRow.text(column: String, max: Int = Phase3Limits.MAX_NAME, multiline: Boolean = false): String? =
        Phase3Text.cleanOrNull(values[column], max, multiline)?.trim()

    private fun email(value: String?): String? = Phase3Text.cleanOrNull(value, Phase3Limits.MAX_EMAIL)?.trim()?.takeIf { it.isNotEmpty() }

    private fun validAccountField(value: String?) = value != null && value.isNotEmpty() && value.length <= Phase3Limits.MAX_ACCOUNT_FIELD

    private fun version(raws: List<RawRow>): String {
        val full = raws.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.version}" }
        if (full.length <= Phase3Limits.MAX_VERSION) return full
        return MessageDigest.getInstance("SHA-256").digest(full.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun normalize(number: String) = number.filter { it.isDigit() || it == '+' }

    private fun notFound(): Nothing = throw CommandException(ErrorCodes.NOT_FOUND, "Contact not found")

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")

    private data class ListRow(val id: Long, val lookupKey: String, val name: String, val sortKey: String, val starred: Boolean, val photo: Boolean)

    private data class RawRow(val id: Long, val version: Long, val accountType: String?, val accountName: String?, val writable: Boolean)

    private class DataRow(val rawId: Long, val mime: String, val values: Map<String, String?>)

    private class Existing(val phones: Set<String>, val emails: Set<String>) {
        companion object {
            val NONE = Existing(emptySet(), emptySet())
        }
    }

    private companion object {
        const val IN_CHUNK = 400

        // Only used to find the position again when the cursor's contact was deleted; keeps cursors short.
        const val CURSOR_SORT_KEY = 48
        const val MAX_MESSAGE = 200

        val LIST_PROJECTION = arrayOf(
            Contacts._ID,
            Contacts.LOOKUP_KEY,
            Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.SORT_KEY_PRIMARY,
            Contacts.STARRED,
            Contacts.PHOTO_ID,
            Contacts.PHOTO_THUMBNAIL_URI,
        )

        val EDITABLE_TYPES = arrayOf(
            StructuredName.CONTENT_ITEM_TYPE,
            Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE,
            Note.CONTENT_ITEM_TYPE,
        )

        val DATA_COLUMNS = listOf(Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6)
        val DATA_PROJECTION = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE, *DATA_COLUMNS.toTypedArray())
    }
}
