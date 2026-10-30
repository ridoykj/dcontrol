package com.dcontrol.client.feature.clipboard.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.ClipboardTextPayload
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.typedCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ClipboardSendResult { Sent, Empty, Offline }

/**
 * `clipboard.set` (web -> phone) works in the background. The other direction can't: Android 10+ only lets
 * the focused app read the clipboard, so [sendCurrentClip] must run from a focused activity (the in-app
 * button, or ClipboardSendActivity started by the tile or the notification action).
 */
@Singleton
class Clipboard @Inject constructor(
    @ApplicationContext private val context: Context,
    codec: ProtocolCodec,
    private val events: DeviceEventSender,
) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)

    val set: CommandHandler = typedCommand(codec, ClipboardTextPayload.serializer(), EmptyPayload.serializer()) { request ->
        if (request.text.isEmpty() || request.text.length > MAX_TEXT) {
            throw CommandException(ErrorCodes.VALIDATION, "text must be 1-$MAX_TEXT chars")
        }
        val manager = manager ?: throw CommandException(ErrorCodes.UNSUPPORTED, "No clipboard on this device")
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, request.text))
        EmptyPayload()
    }

    /** Reads the current clip (call only while an activity of this app has window focus) and sends it. */
    fun sendCurrentClip(): ClipboardSendResult {
        val clip = manager?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return ClipboardSendResult.Empty
        val sent = events.send(MessageTypes.CLIPBOARD_CHANGED, ClipboardTextPayload.serializer(), ClipboardTextPayload(text.take(MAX_TEXT)))
        return if (sent) ClipboardSendResult.Sent else ClipboardSendResult.Offline
    }

    companion object {
        const val MAX_TEXT = 16_000
        private const val CLIP_LABEL = "dcontrol"

        /** Also used by the connection notification's "Send clipboard" action (feature:connection). */
        const val ACTION_SEND_CLIPBOARD = "com.dcontrol.client.action.SEND_CLIPBOARD"
    }
}
