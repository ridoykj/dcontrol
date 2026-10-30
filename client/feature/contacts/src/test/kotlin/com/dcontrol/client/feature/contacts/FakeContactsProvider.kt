package com.dcontrol.client.feature.contacts

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.OperationApplicationException
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import com.dcontrol.client.core.telephony.ContentQueries
import com.dcontrol.client.feature.contacts.data.ContactAccounts
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A tiny in-memory model of the contacts provider, enough for [com.dcontrol.client.feature.contacts.data.ContactsStore]. */
class FakeContactsProvider : ContentQueries {

    data class Contact(val id: Long, val lookupKey: String, var name: String, var starred: Boolean = false, val photo: ByteArray? = null)
    data class Raw(val id: Long, val contactId: Long, var version: Long, val accountType: String?, val accountName: String?)
    data class Row(val id: Long, val rawId: Long, val mime: String, val values: Map<String, String?>)

    val contacts = mutableListOf<Contact>()
    val raws = mutableListOf<Raw>()
    val data = mutableListOf<Row>()
    val batches = mutableListOf<List<ContentProviderOperation>>()
    private var nextId = 10_000L

    fun contact(id: Long, name: String, accountType: String? = null, photo: ByteArray? = null, version: Long = 1): Raw {
        contacts += Contact(id, "lk-$id", name, photo = photo)
        return addRaw(id, accountType, version)
    }

    fun addRaw(contactId: Long, accountType: String?, version: Long = 1): Raw =
        Raw(nextId++, contactId, version, accountType, accountType?.let { "user@$it" }).also { raws += it }

    fun row(raw: Raw, mime: String, vararg values: Pair<String, String?>) {
        data += Row(nextId++, raw.id, mime, values.toMap())
    }

    fun rowsOf(contactId: Long, mime: String) = data.filter { row -> raws.first { it.id == row.rawId }.contactId == contactId && row.mime == mime }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
        val path = uri.toString()
        return when {
            path == Contacts.CONTENT_URI.toString() -> contactsCursor(contacts)
            path.startsWith(Contacts.CONTENT_FILTER_URI.toString()) -> {
                val query = Uri.decode(uri.lastPathSegment)
                contactsCursor(contacts.filter { it.name.contains(query, ignoreCase = true) })
            }
            path.startsWith(Contacts.CONTENT_URI.toString() + "/") -> contactsCursor(contacts.filter { it.id == ContentUris.parseId(uri) })
            path == RawContacts.CONTENT_URI.toString() -> {
                val selected = if (selection!!.startsWith(RawContacts.CONTACT_ID)) raws.filter { it.contactId == args!![0].toLong() } else raws
                MatrixCursor(arrayOf(RawContacts._ID, RawContacts.VERSION, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME)).apply {
                    selected.sortedBy { it.id }.forEach { addRow(arrayOf<Any?>(it.id, it.version, it.accountType, it.accountName)) }
                }
            }
            path.startsWith(RawContacts.CONTENT_URI.toString() + "/") -> MatrixCursor(arrayOf(RawContacts.CONTACT_ID)).apply {
                raws.firstOrNull { it.id == ContentUris.parseId(uri) }?.let { addRow(arrayOf<Any?>(it.contactId)) }
            }
            path == Data.CONTENT_URI.toString() -> dataCursor(selection!!, args!!)
            else -> error("Unexpected query $uri")
        }
    }

    private fun contactsCursor(list: List<Contact>) = MatrixCursor(
        arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.SORT_KEY_PRIMARY, Contacts.STARRED, Contacts.PHOTO_ID, Contacts.PHOTO_THUMBNAIL_URI),
    ).apply {
        list.sortedWith(compareBy({ it.name }, { it.id })).forEach {
            addRow(arrayOf<Any?>(it.id, it.lookupKey, it.name, it.name, if (it.starred) 1 else 0, if (it.photo != null) 1 else 0, null))
        }
    }

    private fun dataCursor(selection: String, args: Array<String>): Cursor {
        val columns = arrayOf(Data.CONTACT_ID, Data.RAW_CONTACT_ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6)
        val (contactIds, mimes) = if (selection.startsWith("${Data.CONTACT_ID} IN")) {
            args.dropLast(2).map { it.toLong() }.toSet() to args.takeLast(2).toSet()
        } else {
            setOf(args[0].toLong()) to args.drop(1).toSet()
        }
        return MatrixCursor(columns).apply {
            data.sortedBy { it.id }.forEach { row ->
                val contactId = raws.first { it.id == row.rawId }.contactId
                if (contactId in contactIds && row.mime in mimes) {
                    addRow((listOf<Any?>(contactId, row.rawId, row.mime) + listOf(Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6).map { row.values[it] }).toTypedArray())
                }
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
        val id = ContentUris.parseId(uri)
        val removed = contacts.removeAll { it.id == id }
        if (removed) {
            val rawIds = raws.filter { it.contactId == id }.map { it.id }.toSet()
            raws.removeAll { it.id in rawIds }
            data.removeAll { it.rawId in rawIds }
        }
        return if (removed) 1 else 0
    }

    override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
        batches += operations.toList()
        val results = arrayOfNulls<ContentProviderResult>(operations.size)
        operations.forEachIndexed { index, op ->
            val values = op.resolveValueBackReferences(results.requireNoNulls(index), index) ?: android.content.ContentValues()
            val path = op.uri.toString()
            results[index] = when {
                op.isAssertQuery -> {
                    val raw = raws.firstOrNull { it.id == ContentUris.parseId(op.uri) }
                    if (raw == null || raw.version != values.getAsLong(RawContacts.VERSION)) throw OperationApplicationException("assert failed")
                    ContentProviderResult(1)
                }
                op.isInsert && path == RawContacts.CONTENT_URI.toString() -> {
                    val contactId = nextId++
                    contacts += Contact(contactId, "lk-$contactId", "New")
                    val raw = Raw(nextId++, contactId, 1, values.getAsString(RawContacts.ACCOUNT_TYPE), values.getAsString(RawContacts.ACCOUNT_NAME))
                    raws += raw
                    ContentProviderResult(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw.id))
                }
                op.isInsert && path == Data.CONTENT_URI.toString() -> {
                    val rawId = values.getAsLong(Data.RAW_CONTACT_ID)
                    val mime = values.getAsString(Data.MIMETYPE)
                    val columns = values.keySet().filter { it.startsWith("data") }.associateWith { values.getAsString(it) }
                    data += Row(nextId++, rawId, mime, columns)
                    raws.first { it.id == rawId }.version++
                    ContentProviderResult(ContentUris.withAppendedId(Data.CONTENT_URI, nextId))
                }
                op.isDelete && path == Data.CONTENT_URI.toString() -> {
                    val args = op.resolveSelectionArgsBackReferences(results.requireNoNulls(index), index)!!
                    val rawId = args[0].toLong()
                    val mimes = args.drop(1).toSet()
                    val before = data.size
                    data.removeAll { it.rawId == rawId && it.mime in mimes }
                    ContentProviderResult(before - data.size)
                }
                op.isUpdate && path.startsWith(RawContacts.CONTENT_URI.toString() + "/") -> {
                    val raw = raws.first { it.id == ContentUris.parseId(op.uri) }
                    contacts.first { it.id == raw.contactId }.starred = values.getAsInteger(RawContacts.STARRED) == 1
                    ContentProviderResult(1)
                }
                else -> error("Unexpected operation $op")
            }
        }
        return results.requireNoNulls()
    }

    private fun Array<ContentProviderResult?>.requireNoNulls(count: Int): Array<ContentProviderResult> =
        Array(count) { this[it]!! }

    override fun lookupContact(lookupUri: Uri): Uri? {
        val lookupKey = lookupUri.pathSegments[lookupUri.pathSegments.size - 2]
        return contacts.firstOrNull { it.lookupKey == lookupKey }?.let { ContentUris.withAppendedId(Contacts.CONTENT_URI, it.id) }
    }

    override fun openContactPhoto(contactUri: Uri): InputStream? =
        contacts.firstOrNull { it.id == ContentUris.parseId(contactUri) }?.photo?.let(::ByteArrayInputStream)
}

class FakeAccounts : ContactAccounts {
    override fun isWritable(accountType: String?) = accountType != READ_ONLY

    override fun label(accountType: String?, accountName: String?) = accountType ?: "Phone"

    companion object {
        const val READ_ONLY = "com.readonly"
    }
}
