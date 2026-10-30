package com.dcontrol.client.core.telephony

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.Permissions
import java.io.InputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelephonyCoreTest {

    @Test
    fun `provider changes are debounced to one signal per burst`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.parse("content://sms")
        var signals = 0
        backgroundScope.launch { AndroidProviderChanges(context).changes(uri).collect { signals++ } }
        runCurrent()

        repeat(5) {
            context.contentResolver.notifyChange(Uri.parse("content://sms/$it"), null)
            advanceTimeBy(200.milliseconds)
        }
        assertEquals(0, signals)
        advanceTimeBy(1_100.milliseconds)
        assertEquals(1, signals)

        context.contentResolver.notifyChange(uri, null)
        advanceTimeBy(1_100.milliseconds)
        assertEquals(2, signals)
    }

    @Test
    fun `contact names are looked up once and need READ_CONTACTS`() {
        val queries = NameQueries()
        var granted = false
        val names = AndroidContactNames(queries) { it == Manifest.permission.READ_CONTACTS && granted }

        assertNull(names.nameFor("+4915112345"))
        assertEquals(0, queries.calls)

        granted = true
        assertEquals("Alice", names.nameFor("+4915112345"))
        assertEquals("Alice", names.nameFor("+4915112345"))
        assertNull(names.nameFor("+100"))
        assertNull(names.nameFor("+100"))
        assertEquals(2, queries.calls)

        names.clear()
        names.nameFor("+4915112345")
        assertEquals(3, queries.calls)
    }

    @Test
    fun `missing permission carries the details group`() {
        val permissions = RuntimePermissions { it == Manifest.permission.READ_SMS }
        permissions.require(Permissions.SMS, Manifest.permission.READ_SMS)
        try {
            permissions.require(Permissions.SMS, *PermissionSets.SMS)
            fail("expected PERMISSION_REQUIRED")
        } catch (e: CommandException) {
            assertEquals(ErrorCodes.PERMISSION_REQUIRED, e.code)
            assertEquals(Permissions.SMS, e.permission)
        }
    }

    private class NameQueries : ContentQueries {
        var calls = 0

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
            calls++
            val cursor = MatrixCursor(arrayOf(PhoneLookup.DISPLAY_NAME))
            if (Uri.decode(uri.lastPathSegment) == "+4915112345") cursor.addRow(arrayOf("Alice"))
            return cursor
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>) = emptyArray<ContentProviderResult>()
        override fun lookupContact(lookupUri: Uri): Uri? = null
        override fun openContactPhoto(contactUri: Uri): InputStream? = null
    }
}
