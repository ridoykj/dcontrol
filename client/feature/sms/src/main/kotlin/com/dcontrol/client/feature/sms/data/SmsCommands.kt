package com.dcontrol.client.feature.sms.data

import android.Manifest
import android.provider.Telephony
import com.dcontrol.client.core.telephony.ProviderChanges
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.telephony.SimCards
import com.dcontrol.client.core.telephony.require
import com.dcontrol.client.core.telephony.whenGranted
import com.dcontrol.client.core.websocket.CommandException
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.DialNumbers
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.ErrorCodes
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.PageCursorRequest
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.protocol.SmsMessagesRequest
import com.dcontrol.client.core.websocket.protocol.SmsMessagesResult
import com.dcontrol.client.core.websocket.protocol.SmsReceivedEvent
import com.dcontrol.client.core.websocket.protocol.SmsSendRequest
import com.dcontrol.client.core.websocket.protocol.SmsSendResult
import com.dcontrol.client.core.websocket.protocol.SmsThreadsResult
import com.dcontrol.client.core.websocket.typedCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.merge

/** Handlers for `sms.threads`, `sms.messages` and `sms.send`. */
@Singleton
class SmsCommands @Inject constructor(
    codec: ProtocolCodec,
    private val store: SmsStore,
    private val gateway: SmsGateway,
    private val intents: SmsReportIntents,
    private val tracker: SmsStatusTracker,
    private val sims: SimCards,
    private val permissions: RuntimePermissions,
) {
    val threads: CommandHandler = typedCommand(codec, PageCursorRequest.serializer(), SmsThreadsResult.serializer()) { request ->
        permissions.require(Permissions.SMS, Manifest.permission.READ_SMS)
        store.threads(request.cursor)
    }

    val messages: CommandHandler = typedCommand(codec, SmsMessagesRequest.serializer(), SmsMessagesResult.serializer()) { request ->
        permissions.require(Permissions.SMS, Manifest.permission.READ_SMS)
        store.messages(request.threadId, request.cursor)
    }

    val send: CommandHandler = typedCommand(codec, SmsSendRequest.serializer(), SmsSendResult.serializer()) { request ->
        val to = DialNumbers.normalize(request.to) ?: invalid("Only digits and a leading + can be messaged")
        if (request.text.isEmpty() || request.text.length > Phase3Limits.MAX_SMS_TEXT) invalid("text must be 1-${Phase3Limits.MAX_SMS_TEXT} chars")
        if (!CLIENT_REF.matches(request.clientRef)) invalid("Invalid clientRef")
        if (!sims.hasTelephony()) throw CommandException(ErrorCodes.UNSUPPORTED, "This device can't send SMS")
        permissions.require(Permissions.SMS, Manifest.permission.SEND_SMS)
        request.simId?.let { simId -> if (!sims.isActive(simId)) invalid("Unknown SIM") }

        val parts = try {
            gateway.divide(request.text, request.simId)
        } catch (_: RuntimeException) {
            invalid("The text can't be encoded as SMS")
        }
        if (parts.isEmpty()) invalid("Empty message")
        if (parts.size > Phase3Limits.MAX_SMS_PARTS) {
            throw CommandException(ErrorCodes.PAYLOAD_TOO_LARGE, "The text needs ${parts.size} SMS parts; at most ${Phase3Limits.MAX_SMS_PARTS} are allowed")
        }
        tracker.register(request.clientRef, parts.size)
        try {
            gateway.send(
                to,
                parts,
                request.simId,
                parts.indices.map { intents.sent(request.clientRef, it) },
                parts.indices.map { intents.delivered(request.clientRef, it) },
            )
        } catch (_: SecurityException) {
            throw CommandException(ErrorCodes.PERMISSION_REQUIRED, "SMS permission is not granted on the phone", Permissions.SMS)
        } catch (e: IllegalArgumentException) {
            invalid(e.message ?: "The SMS was rejected")
        }
        SmsSendResult(parts.size)
    }

    private fun invalid(message: String): Nothing = throw CommandException(ErrorCodes.VALIDATION, message)

    private companion object {
        val CLIENT_REF = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}

/**
 * While online: `sms.received` for every new inbox row (by `_id`, so the default SMS app must have stored it),
 * `sms.changed` for any other change of the SMS/MMS providers.
 */
class SmsWatcher @Inject constructor(
    private val changes: ProviderChanges,
    private val store: SmsStore,
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
) : OnlineWatcher {
    override suspend fun watch() = permissions.whenGranted(Manifest.permission.READ_SMS) {
        var lastInboxId = store.maxInboxId()
        merge(changes.changes(Telephony.Sms.CONTENT_URI), changes.changes(Telephony.Mms.CONTENT_URI)).collect {
            val fresh = store.inboxSince(lastInboxId)
            if (fresh.isEmpty()) {
                events.send(MessageTypes.SMS_CHANGED, EmptyPayload.serializer(), EmptyPayload())
            } else {
                lastInboxId = maxOf(lastInboxId, fresh.maxOf { it.second.id })
                fresh.forEach { (threadId, message) ->
                    events.send(MessageTypes.SMS_RECEIVED, SmsReceivedEvent.serializer(), SmsReceivedEvent(threadId, message))
                }
            }
        }
    }
}
