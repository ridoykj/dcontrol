package com.dcontrol.client.feature.sms.data

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Phase3Limits
import com.dcontrol.client.core.websocket.protocol.SmsSendStatuses
import com.dcontrol.client.core.websocket.protocol.SmsStatusEvent
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** The [SmsManager] calls, behind a seam for tests. */
interface SmsGateway {
    fun divide(text: String, simId: Int?): List<String>

    fun send(to: String, parts: List<String>, simId: Int?, sent: List<PendingIntent>, delivered: List<PendingIntent>)
}

@Singleton
class AndroidSmsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmsGateway {
    override fun divide(text: String, simId: Int?): List<String> = manager(simId).divideMessage(text)

    override fun send(to: String, parts: List<String>, simId: Int?, sent: List<PendingIntent>, delivered: List<PendingIntent>) {
        // As a non-default SMS app, the system writes the sent message to the provider for us (API 19+).
        manager(simId).sendMultipartTextMessage(to, null, ArrayList(parts), ArrayList(sent), ArrayList(delivered))
    }

    @Suppress("DEPRECATION")
    private fun manager(simId: Int?): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        if (simId == null) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.createForSubscriptionId(simId) else SmsManager.getSmsManagerForSubscriptionId(simId)
    }
}

/**
 * Aggregates per-part sent/delivered reports into one `sms.status` per stage and `clientRef`:
 * `SENT` once every part is sent, `FAILED` on the first failure, `DELIVERED` once every part is delivered.
 * Kept in memory only; at most [MAX_PENDING] sends are tracked (delivery reports often never arrive).
 */
@Singleton
class SmsStatusTracker @Inject constructor(
    private val events: DeviceEventSender,
) {
    private class Pending(val parts: Int) {
        val sent = BooleanArray(parts)
        val delivered = BooleanArray(parts)
        var sentReported = false
    }

    private val pending = object : LinkedHashMap<String, Pending>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pending>) = size > MAX_PENDING
    }

    @Synchronized
    fun register(clientRef: String, parts: Int) {
        pending[clientRef] = Pending(parts)
    }

    @Synchronized
    fun onSent(clientRef: String, part: Int, ok: Boolean, errorCode: String?) {
        val entry = pending[clientRef] ?: return
        if (part !in 0 until entry.parts || entry.sentReported) return
        if (!ok) {
            pending.remove(clientRef)
            report(clientRef, SmsSendStatuses.FAILED, errorCode ?: "GENERIC_FAILURE")
            return
        }
        entry.sent[part] = true
        if (entry.sent.all { it }) {
            entry.sentReported = true
            report(clientRef, SmsSendStatuses.SENT, null)
        }
    }

    @Synchronized
    fun onDelivered(clientRef: String, part: Int, ok: Boolean) {
        val entry = pending[clientRef] ?: return
        if (part !in 0 until entry.parts) return
        if (!ok) {
            pending.remove(clientRef)
            report(clientRef, SmsSendStatuses.FAILED, "DELIVERY_FAILED")
            return
        }
        entry.delivered[part] = true
        if (entry.delivered.all { it }) {
            pending.remove(clientRef)
            report(clientRef, SmsSendStatuses.DELIVERED, null)
        }
    }

    private fun report(clientRef: String, status: String, errorCode: String?) {
        events.send(
            MessageTypes.SMS_STATUS,
            SmsStatusEvent.serializer(),
            SmsStatusEvent(clientRef, status, errorCode?.take(Phase3Limits.MAX_ERROR_CODE)),
        )
    }

    companion object {
        const val MAX_PENDING = 100

        /** `SmsManager` result codes of a sent report, as `sms.status.errorCode`. */
        fun errorCode(resultCode: Int): String = when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "SHORT_CODE_NOT_ALLOWED"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "SHORT_CODE_NEVER_ALLOWED"
            else -> "ERROR_$resultCode"
        }
    }
}

/** Builds the explicit per-part report intents for [SmsStatusReceiver]. */
@Singleton
class SmsReportIntents @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val requestCodes = AtomicInteger()

    fun sent(clientRef: String, part: Int): PendingIntent = intent(ACTION_SENT, clientRef, part, PendingIntent.FLAG_IMMUTABLE)

    /** Mutable: the telephony stack fills in the status report PDU. Safe because the intent is explicit. */
    fun delivered(clientRef: String, part: Int): PendingIntent = intent(ACTION_DELIVERED, clientRef, part, PendingIntent.FLAG_MUTABLE)

    private fun intent(action: String, clientRef: String, part: Int, mutability: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .setData(Uri.fromParts("dcontrol-sms", "$clientRef/$part", null))
            .putExtra(EXTRA_CLIENT_REF, clientRef)
            .putExtra(EXTRA_PART, part)
        return PendingIntent.getBroadcast(context, requestCodes.incrementAndGet(), intent, PendingIntent.FLAG_ONE_SHOT or mutability)
    }

    companion object {
        const val ACTION_SENT = "com.dcontrol.client.action.SMS_SENT"
        const val ACTION_DELIVERED = "com.dcontrol.client.action.SMS_DELIVERED"
        const val EXTRA_CLIENT_REF = "clientRef"
        const val EXTRA_PART = "part"
    }
}

@AndroidEntryPoint
class SmsStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var tracker: SmsStatusTracker

    override fun onReceive(context: Context, intent: Intent) {
        val clientRef = intent.getStringExtra(SmsReportIntents.EXTRA_CLIENT_REF) ?: return
        val part = intent.getIntExtra(SmsReportIntents.EXTRA_PART, -1)
        when (intent.action) {
            SmsReportIntents.ACTION_SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                tracker.onSent(clientRef, part, ok, if (ok) null else SmsStatusTracker.errorCode(resultCode))
            }
            SmsReportIntents.ACTION_DELIVERED -> {
                val status = deliveryStatus(intent) ?: Telephony.Sms.STATUS_COMPLETE
                if (status >= Telephony.Sms.STATUS_FAILED) {
                    tracker.onDelivered(clientRef, part, ok = false)
                } else if (status < Telephony.Sms.STATUS_PENDING) {
                    tracker.onDelivered(clientRef, part, ok = true)
                }
            }
        }
    }

    private fun deliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        return SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))?.status
    }
}
