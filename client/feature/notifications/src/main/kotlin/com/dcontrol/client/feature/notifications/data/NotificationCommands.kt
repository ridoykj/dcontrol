package com.dcontrol.client.feature.notifications.data

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.NotificationAccess
import com.dcontrol.client.core.websocket.protocol.NotificationKeyPayload
import com.dcontrol.client.core.websocket.protocol.NotificationPayload
import com.dcontrol.client.core.websocket.protocol.NotificationReplyRequest
import com.dcontrol.client.core.websocket.protocol.NotificationsListRequest
import com.dcontrol.client.core.websocket.protocol.NotificationsListResult
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.typedCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Handlers for `notifications.list`, `notification.dismiss` and `notification.reply`. */
@Singleton
class NotificationCommands @Inject constructor(
    @ApplicationContext private val context: Context,
    private val codec: ProtocolCodec,
    private val mirror: NotificationMirror,
    private val mapper: NotificationMapper,
) {
    val list: CommandHandler = typedCommand(codec, NotificationsListRequest.serializer(), NotificationsListResult.serializer()) { request ->
        if (request.offset < 0) throw CommandException(ErrorCodes.VALIDATION, "offset must be >= 0")
        val active = mirror.active()
            ?: return@typedCommand NotificationsListResult(NotificationAccess.MISSING, emptyList())
        page(active.asSequence().drop(request.offset).map { mirror.toPayload(it) }, request.offset, active.size)
    }

    val dismiss: CommandHandler = typedCommand(codec, NotificationKeyPayload.serializer(), EmptyPayload.serializer()) { request ->
        val sbn = find(request.key)
        if (!sbn.isClearable) throw CommandException(ErrorCodes.FORBIDDEN, "This notification can't be dismissed")
        requireSource().cancel(sbn.key)
        EmptyPayload()
    }

    val reply: CommandHandler = typedCommand(codec, NotificationReplyRequest.serializer(), EmptyPayload.serializer()) { request ->
        if (request.text.isEmpty() || request.text.length > MAX_REPLY) {
            throw CommandException(ErrorCodes.VALIDATION, "text must be 1-$MAX_REPLY chars")
        }
        val sbn = find(request.key)
        val action = mapper.replyAction(sbn.notification)
            ?: throw CommandException(ErrorCodes.UNSUPPORTED, "This notification has no reply action")
        val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, request.text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        try {
            action.actionIntent.send(context, 0, intent)
        } catch (_: PendingIntent.CanceledException) {
            throw CommandException(ErrorCodes.NOT_FOUND, "The app no longer accepts replies to this notification")
        }
        EmptyPayload()
    }

    /** Fills a page up to ~48 KiB of encoded items so the result stays under the 64 KiB frame limit. */
    private fun page(items: Sequence<NotificationPayload>, offset: Int, total: Int): NotificationsListResult {
        val page = mutableListOf<NotificationPayload>()
        var used = 0
        for (item in items) {
            val size = codec.json.encodeToString(NotificationPayload.serializer(), item).encodeToByteArray().size + 1
            if (page.size >= MAX_PAGE || (page.isNotEmpty() && used + size > BUDGET_BYTES)) break
            page += item
            used += size
        }
        val next = offset + page.size
        return NotificationsListResult(NotificationAccess.GRANTED, page, next.takeIf { it < total })
    }

    private fun find(key: String): StatusBarNotification {
        if (key.isEmpty() || key.length > NotificationMapper.MAX_KEY) throw CommandException(ErrorCodes.VALIDATION, "Invalid key")
        val active = mirror.active() ?: throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Notification access is not granted on the phone")
        return active.firstOrNull { it.key == key } ?: throw CommandException(ErrorCodes.NOT_FOUND, "Notification not found")
    }

    private fun requireSource(): ActiveNotificationSource =
        mirror.source ?: throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "Notification access is not granted on the phone")

    private companion object {
        const val MAX_REPLY = 1000
        const val MAX_PAGE = 200
        const val BUDGET_BYTES = 48 * 1024
    }
}
