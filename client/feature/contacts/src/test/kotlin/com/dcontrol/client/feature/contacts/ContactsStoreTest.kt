package com.dcontrol.client.feature.contacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.AccountRef
import com.dcontrol.client.core.websocket.protocol.ContactEmail
import com.dcontrol.client.core.websocket.protocol.ContactFields
import com.dcontrol.client.core.websocket.protocol.ContactName
import com.dcontrol.client.core.websocket.protocol.ContactPhone
import com.dcontrol.client.core.websocket.protocol.ContactRef
import com.dcontrol.client.core.websocket.protocol.EmailTypes
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.PhoneTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.contacts.data.ContactsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsStoreTest {

    private val provider = FakeContactsProvider()
    private val store = ContactsStore(provider, FakeAccounts(), ApplicationProvider.getApplicationContext<android.content.Context>().resources, ProtocolCodec())

    @Test
    fun `list pages by byte budget and follows the cursor without gaps`() {
        repeat(400) { i ->
            val raw = provider.contact(i.toLong() + 1, "Name %03d ".format(i) + "x".repeat(180))
            provider.row(raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+49 151 $i", Phone.TYPE to "2")
        }
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = store.list(null, cursor)
            assertTrue(page.items.isNotEmpty())
            seen += page.items.map { it.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue("several pages expected, got $pages", pages > 1)
        assertEquals((1L..400L).toList(), seen)
        assertEquals(listOf("+49 151 0"), store.list(null, null).items.first().phones)
    }

    @Test
    fun `list filters by query and rejects foreign cursors`() {
        provider.contact(1, "Alice")
        provider.contact(2, "Bob")
        assertEquals(listOf("Bob"), store.list("bo", null).items.map { it.name })
        expectCode(ErrorCodes.VALIDATION) { store.list(null, "not-a-cursor!") }
    }

    @Test
    fun `get merges raw contacts and maps types`() {
        val raw = provider.contact(7, "Alice Example", accountType = "com.google", version = 3)
        provider.row(raw, StructuredName.CONTENT_ITEM_TYPE, StructuredName.GIVEN_NAME to "Alice", StructuredName.FAMILY_NAME to "Example")
        provider.row(raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+49 151 1", Phone.TYPE to Phone.TYPE_MOBILE.toString())
        provider.row(raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+49 30 2", Phone.TYPE to Phone.TYPE_CAR.toString())
        provider.row(raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+49 30 3", Phone.TYPE to "0", Phone.LABEL to "Boat")
        provider.row(raw, Email.CONTENT_ITEM_TYPE, Email.ADDRESS to "a@example.com", Email.TYPE to Email.TYPE_WORK.toString())
        provider.row(raw, Note.CONTENT_ITEM_TYPE, Note.NOTE to "line 1\nline 2")
        val second = provider.addRaw(7, FakeAccounts.READ_ONLY, version = 5)
        provider.row(second, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+49 151 1", Phone.TYPE to Phone.TYPE_MOBILE.toString())

        val contact = store.get(ContactRef(7, "lk-7"))

        assertEquals("${raw.id}:3,${second.id}:5", contact.version)
        assertEquals("com.google", contact.account!!.type)
        assertFalse(contact.readOnly)
        assertEquals("Alice", contact.name.given)
        assertEquals("Alice Example", contact.name.display)
        assertEquals(
            listOf(PhoneTypes.MOBILE, PhoneTypes.CUSTOM, PhoneTypes.CUSTOM),
            contact.phones.map { it.type },
        )
        assertEquals("Boat", contact.phones[2].label)
        assertTrue(contact.phones[1].label!!.isNotBlank())
        assertEquals(EmailTypes.WORK, contact.emails.single().type)
        assertEquals("line 1\nline 2", contact.note)
    }

    @Test
    fun `get of a vanished contact is NOT_FOUND`() {
        expectCode(ErrorCodes.NOT_FOUND) { store.get(ContactRef(99, "lk-99")) }
    }

    @Test
    fun `update rewrites editable rows on the writable raw contact only`() {
        val raw = provider.contact(1, "Alice")
        provider.row(raw, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 111", Phone.TYPE to "2")
        val readOnly = provider.addRaw(1, FakeAccounts.READ_ONLY)
        provider.row(readOnly, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to "+1 999", Phone.TYPE to "2")
        val version = store.get(ContactRef(1, "lk-1")).version

        store.update(
            ContactRef(1, "lk-1"),
            version,
            ContactFields(
                name = ContactName(given = "Alicia"),
                phones = listOf(ContactPhone("+1 222", PhoneTypes.WORK), ContactPhone("+1 999", PhoneTypes.MOBILE)),
                emails = listOf(ContactEmail("alicia@example.com", EmailTypes.CUSTOM, "Club")),
                starred = true,
            ),
        )

        val writablePhones = provider.data.filter { it.rawId == raw.id && it.mime == Phone.CONTENT_ITEM_TYPE }.map { it.values[Phone.NUMBER] }
        assertEquals("old phone removed, duplicate of the read-only phone skipped", listOf("+1 222"), writablePhones)
        assertEquals(listOf("+1 999"), provider.data.filter { it.rawId == readOnly.id }.map { it.values[Phone.NUMBER] })
        assertEquals("Alicia", provider.rowsOf(1, StructuredName.CONTENT_ITEM_TYPE).single().values[StructuredName.GIVEN_NAME])
        assertEquals("Club", provider.rowsOf(1, Email.CONTENT_ITEM_TYPE).single().values[Email.LABEL])
        assertTrue(provider.contacts.first { it.id == 1L }.starred)
        assertTrue("the batch asserts every raw contact's version", provider.batches.single().count { it.isAssertQuery } == 2)
    }

    @Test
    fun `update with a stale version is a CONFLICT and read-only contacts are FORBIDDEN`() {
        provider.contact(1, "Alice")
        expectCode(ErrorCodes.CONFLICT) { store.update(ContactRef(1, "lk-1"), "stale", ContactFields(name = ContactName(given = "A"))) }

        provider.contact(2, "Bob", accountType = FakeAccounts.READ_ONLY)
        val contact = store.get(ContactRef(2, "lk-2"))
        assertTrue(contact.readOnly)
        expectCode(ErrorCodes.FORBIDDEN) { store.update(ContactRef(2, "lk-2"), contact.version, ContactFields(name = ContactName(given = "B"))) }
    }

    @Test
    fun `a concurrent change inside the batch becomes CONFLICT`() {
        val raw = provider.contact(1, "Alice")
        val version = store.get(ContactRef(1, "lk-1")).version
        val racing = object : com.dcontrol.client.core.telephony.ContentQueries by provider {
            override fun applyBatch(authority: String, operations: ArrayList<android.content.ContentProviderOperation>): Array<android.content.ContentProviderResult> {
                raw.version++
                return provider.applyBatch(authority, operations)
            }
        }
        val racingStore = ContactsStore(racing, FakeAccounts(), ApplicationProvider.getApplicationContext<android.content.Context>().resources, ProtocolCodec())
        expectCode(ErrorCodes.CONFLICT) { racingStore.update(ContactRef(1, "lk-1"), version, ContactFields(name = ContactName(given = "A"))) }
    }

    @Test
    fun `create inserts a phone-only raw contact with back-referenced data`() {
        val created = store.create(
            null,
            ContactFields(name = ContactName(given = "Carol"), phones = listOf(ContactPhone("+1 333", PhoneTypes.MOBILE))),
        )

        assertNull(created.account)
        assertEquals(listOf("+1 333"), created.phones.map { it.number })
        val batch = provider.batches.single()
        assertTrue(batch.first().isInsert)
        assertEquals(3, batch.size)
    }

    @Test
    fun `create validates fields and accounts`() {
        expectCode(ErrorCodes.VALIDATION) { store.create(null, ContactFields()) }
        expectCode(ErrorCodes.VALIDATION) {
            store.create(null, ContactFields(phones = listOf(ContactPhone("+1", "SATELLITE"))))
        }
        expectCode(ErrorCodes.VALIDATION) {
            store.create(AccountRef("com.unknown", "x"), ContactFields(name = ContactName(given = "A")))
        }
        provider.contact(1, "Existing", accountType = "com.google")
        val created = store.create(AccountRef("com.google", "user@com.google"), ContactFields(name = ContactName(given = "A")))
        assertEquals("com.google", created.account!!.type)
    }

    @Test
    fun `accounts lists phone-only and writable accounts`() {
        provider.contact(1, "A", accountType = "com.google")
        provider.contact(2, "B", accountType = FakeAccounts.READ_ONLY)
        assertEquals(listOf(null, "com.google"), store.accounts().map { it.type })
        assertEquals("Phone", store.accounts().first().label)
    }

    @Test
    fun `delete reports missing and read-only contacts`() {
        provider.contact(1, "A")
        provider.contact(2, "B", accountType = FakeAccounts.READ_ONLY)
        val result = store.delete(listOf(ContactRef(1, "lk-1"), ContactRef(2, "lk-2"), ContactRef(3, "lk-3")))

        assertEquals(1, result.deleted)
        assertEquals(listOf(2L to ErrorCodes.FORBIDDEN, 3L to ErrorCodes.NOT_FOUND), result.failures.map { it.id to it.code })
        expectCode(ErrorCodes.VALIDATION) { store.delete(emptyList()) }
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: CommandException) {
            assertEquals(code, e.code)
        }
    }
}
