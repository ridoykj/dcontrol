package com.dcontrol.client.feature.contacts

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.network.credentials.StoredCredentials
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.contacts.data.ContactPhotoUploader
import com.dcontrol.client.feature.contacts.data.ContactsCommands
import com.dcontrol.client.feature.contacts.data.ContactsStore
import com.dcontrol.client.feature.contacts.ui.ContactsAccessCardContent
import com.dcontrol.client.feature.contacts.ui.ContactsAccessTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsCommandsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val codec = ProtocolCodec()
    private val server = MockWebServer().apply { start() }
    private val provider = FakeContactsProvider()
    private val granted = mutableSetOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    private val store = ContactsStore(provider, FakeAccounts(), ApplicationProvider.getApplicationContext<android.content.Context>().resources, codec)
    private val http = DeviceHttpClient(
        OkHttpClient(),
        object : CredentialStore {
            override val credentials: StateFlow<StoredCredentials?> =
                MutableStateFlow(StoredCredentials(server.url("/").toString(), "dev-1", "secret", "Pixel"))
            override suspend fun save(credentials: StoredCredentials) = Unit
            override suspend fun clear() = Unit
        },
    )
    private val commands = ContactsCommands(
        codec,
        store,
        ContactPhotoUploader(store, { input -> input.readBytes() }, http),
        RuntimePermissions { it in granted },
    )

    @After
    fun tearDown() = server.close()

    @Test
    fun `list answers through the typed handler`() = runTest {
        provider.contact(1, "Alice")
        val result = commands.list.handle(command(MessageTypes.CONTACTS_LIST, """{}"""))!!
        assertEquals(1, result.getValue("items").jsonArray.size)
    }

    @Test
    fun `missing permissions name the CONTACTS group`() = runTest {
        granted.remove(Manifest.permission.WRITE_CONTACTS)
        commands.list.handle(command(MessageTypes.CONTACTS_LIST, """{}"""))
        val error = expectError { commands.delete.handle(command(MessageTypes.CONTACTS_DELETE, """{"items":[{"id":1,"lookupKey":"lk-1"}]}""")) }
        assertEquals(ErrorCodes.PERMISSION_REQUIRED, error.code)
        assertEquals(Permissions.CONTACTS, error.permission)

        granted.clear()
        assertEquals(Permissions.CONTACTS, expectError { commands.list.handle(command(MessageTypes.CONTACTS_LIST, "{}")) }.permission)
    }

    @Test
    fun `photo is uploaded to the blob slot`() = runTest {
        provider.contact(1, "Alice", photo = byteArrayOf(1, 2, 3))
        server.enqueue(MockResponse.Builder().code(204).build())

        commands.photo.handle(command(MessageTypes.CONTACTS_PHOTO, """{"uploadId":"0192-abc","id":1,"lookupKey":"lk-1"}"""))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/device-api/blobs/0192-abc", request.url.encodedPath)
        assertEquals("image/jpeg", request.headers["Content-Type"])
        assertEquals("Device dev-1.secret", request.headers["Authorization"])
        assertEquals(3, request.body!!.size)
    }

    @Test
    fun `photo errors map to protocol codes`() = runTest {
        provider.contact(1, "Alice", photo = byteArrayOf(1))
        provider.contact(2, "Bob")
        server.enqueue(MockResponse.Builder().code(404).build())

        assertEquals(ErrorCodes.NOT_FOUND, expectError { photo("""{"uploadId":"u-1","id":1,"lookupKey":"lk-1"}""") }.code)
        assertEquals(ErrorCodes.NOT_FOUND, expectError { photo("""{"uploadId":"u-2","id":2,"lookupKey":"lk-2"}""") }.code)
        assertEquals(ErrorCodes.VALIDATION, expectError { photo("""{"uploadId":"../x","id":1,"lookupKey":"lk-1"}""") }.code)
    }

    @Test
    fun accessCardOffersGrantWhenMissing() {
        var clicks = 0
        composeRule.setContent { ContactsAccessCardContent(granted = false, blocked = false, onGrant = { clicks++ }) }
        composeRule.onNodeWithTag(ContactsAccessTags.GRANT).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun accessCardHidesGrantWhenGranted() {
        composeRule.setContent { ContactsAccessCardContent(granted = true, blocked = false, onGrant = {}) }
        composeRule.onNodeWithTag(ContactsAccessTags.GRANT).assertDoesNotExist()
        composeRule.onNodeWithTag(ContactsAccessTags.CARD).assertIsDisplayed()
    }

    @Test
    fun accessCardSendsBlockedUsersToSettings() {
        composeRule.setContent { ContactsAccessCardContent(granted = false, blocked = true, onGrant = {}) }
        composeRule.onNodeWithText("Open app settings").assertIsDisplayed()
    }

    private suspend fun photo(payload: String) = commands.photo.handle(command(MessageTypes.CONTACTS_PHOTO, payload))

    private fun command(type: String, payload: String) =
        Envelope(type = type, requestId = "r-1", payload = Json.parseToJsonElement(payload).jsonObject)

    private inline fun expectError(block: () -> Unit): CommandException {
        try {
            block()
        } catch (e: CommandException) {
            return e
        }
        fail("Expected CommandException")
        throw AssertionError()
    }
}
