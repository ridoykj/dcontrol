package com.dcontrol.client.feature.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ClipboardTextPayload
import com.dcontrol.client.core.websocket.protocol.Envelope
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.clipboard.data.Clipboard
import com.dcontrol.client.feature.clipboard.data.ClipboardSendResult
import com.dcontrol.client.feature.clipboard.ui.ClipboardCardContent
import com.dcontrol.client.feature.clipboard.ui.ClipboardTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class ClipboardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val events = RecordingEvents()
    private val clipboard = Clipboard(context, ProtocolCodec(), events)

    @Test
    fun `clipboard set copies the text`() {
        runBlocking { clipboard.set.handle(command("from the web")) }
        ShadowLooper.idleMainLooper()

        assertEquals("from the web", manager.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test
    fun `clipboard set rejects empty and oversized text`() = runBlocking {
        assertCode(ErrorCodes.VALIDATION) { clipboard.set.handle(command("")) }
        assertCode(ErrorCodes.VALIDATION) { clipboard.set.handle(command("x".repeat(Clipboard.MAX_TEXT + 1))) }
    }

    @Test
    fun `sending the clip emits clipboard changed truncated to the limit`() {
        manager.setPrimaryClip(ClipData.newPlainText("t", "y".repeat(Clipboard.MAX_TEXT + 5)))

        assertEquals(ClipboardSendResult.Sent, clipboard.sendCurrentClip())

        val (type, payload) = events.sent.single()
        assertEquals(MessageTypes.CLIPBOARD_CHANGED, type)
        assertEquals(Clipboard.MAX_TEXT, (payload as ClipboardTextPayload).text.length)
    }

    @Test
    fun `sending reports empty clipboard and offline`() {
        manager.clearPrimaryClip()
        assertEquals(ClipboardSendResult.Empty, clipboard.sendCurrentClip())

        manager.setPrimaryClip(ClipData.newPlainText("t", "hello"))
        events.online = false
        assertEquals(ClipboardSendResult.Offline, clipboard.sendCurrentClip())
    }

    @Test
    fun cardShowsTheSendResult() {
        composeRule.setContent { ClipboardCardContent(onSend = { ClipboardSendResult.Offline }) }

        composeRule.onNodeWithTag(ClipboardTags.SEND).performClick()

        composeRule.onNodeWithText(context.getString(R.string.clipboard_offline)).assertExists()
    }

    private fun command(text: String) =
        Envelope(type = MessageTypes.CLIPBOARD_SET, requestId = "r-1", payload = JsonObject(mapOf("text" to JsonPrimitive(text))))

    private suspend fun assertCode(code: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CommandException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected CommandException $code")
    }

    private class RecordingEvents : DeviceEventSender {
        var online = true
        val sent = mutableListOf<Pair<String, Any?>>()
        override val connectionState: Flow<ConnectionState> = emptyFlow()
        override fun <T> send(type: String, serializer: KSerializer<T>, payload: T): Boolean {
            if (!online) return false
            sent += type to payload
            return true
        }
    }
}
