package com.dcontrol.client.core.telephony

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ContentResolver] calls the contacts, call log and SMS stores make. Tests replace it with
 * `MatrixCursor`-backed fakes instead of re-implementing the system providers.
 */
interface ContentQueries {
    fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor?

    fun delete(uri: Uri, selection: String?, args: Array<String>?): Int

    fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult>

    /** [ContactsContract.Contacts.lookupContact]: the current contact URI for a lookup URI, or null. */
    fun lookupContact(lookupUri: Uri): Uri?

    fun openContactPhoto(contactUri: Uri): InputStream?
}

@Singleton
class ResolverContentQueries @Inject constructor(
    @ApplicationContext context: Context,
) : ContentQueries {
    private val resolver: ContentResolver = context.contentResolver

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor? =
        resolver.query(uri, projection, selection, args, sortOrder)

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = resolver.delete(uri, selection, args)

    override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> =
        resolver.applyBatch(authority, operations)

    override fun lookupContact(lookupUri: Uri): Uri? = ContactsContract.Contacts.lookupContact(resolver, lookupUri)

    override fun openContactPhoto(contactUri: Uri): InputStream? =
        ContactsContract.Contacts.openContactPhotoInputStream(resolver, contactUri, true)
}

/** Iterates the rows of a nullable cursor and closes it. */
inline fun <T> Cursor?.mapRows(block: (Cursor) -> T?): List<T> {
    if (this == null) return emptyList()
    return use { cursor ->
        val out = ArrayList<T>(cursor.count.coerceAtLeast(0))
        while (cursor.moveToNext()) block(cursor)?.let(out::add)
        out
    }
}

fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

fun Cursor.long(column: String, default: Long = 0): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) default else getLong(index)
}

fun Cursor.int(column: String, default: Int = 0): Int {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) default else getInt(index)
}

/** The cursor's rows, read lazily; the caller closes the cursor. Each element is the cursor at that row. */
fun Cursor.rows(): Sequence<Cursor> = sequence {
    while (moveToNext()) yield(this@rows)
}
