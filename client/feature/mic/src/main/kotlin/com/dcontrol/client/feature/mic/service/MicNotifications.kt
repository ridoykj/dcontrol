package com.dcontrol.client.feature.mic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dcontrol.client.feature.mic.R

/** The two Phase 6 microphone channels: a high-importance "tap to share" request and the ongoing capture note. */
internal class MicNotifications(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                context.getString(R.string.mic_requests_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.mic_requests_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.mic_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    /** Posted when the app is in the background; tapping it opens the consent prompt. */
    fun postRequest(sessionId: String, viewer: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.mic_request_title))
            .setContentText(context.getString(R.string.mic_request_text, viewer))
            .setAutoCancel(true)
            .setContentIntent(consentIntent(sessionId))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(REQUEST_ID, notification)
    }

    fun cancelRequest() {
        context.getSystemService(NotificationManager::class.java)?.cancel(REQUEST_ID)
    }

    /** The ongoing foreground-service notification while the microphone is shared. */
    fun capture(viewer: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.mic_capture_title))
            .setContentText(context.getString(R.string.mic_capture_text, viewer))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.mic_capture_stop), stopIntent())
            .build()

    private fun consentIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        MicConsentActivity.intent(context, sessionId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, MicCaptureService::class.java).setAction(MicCaptureService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_REQUESTS = "mic_requests"
        const val CHANNEL_CAPTURE = "mic_capture"
        const val REQUEST_ID = 33
        const val CAPTURE_ID = 34
    }
}
