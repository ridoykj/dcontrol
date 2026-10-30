package com.dcontrol.client.feature.screen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dcontrol.client.feature.screen.R

/** The two Phase 4 screen channels: a high-importance "tap to share" request and the ongoing capture note. */
internal class ScreenNotifications(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                context.getString(R.string.screen_requests_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.screen_requests_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.screen_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    /** Posted when the Accessibility service is off, so the consent screen can't open from the background. */
    fun postRequest(sessionId: String, viewer: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_screen)
            .setContentTitle(context.getString(R.string.screen_request_title))
            .setContentText(context.getString(R.string.screen_request_text, viewer))
            .setAutoCancel(true)
            .setContentIntent(consentIntent(sessionId))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(REQUEST_ID, notification)
    }

    fun cancelRequest() {
        context.getSystemService(NotificationManager::class.java)?.cancel(REQUEST_ID)
    }

    /** The ongoing foreground-service notification while the screen is shared. */
    fun capture(viewer: String, controlled: Boolean): Notification {
        val text = if (controlled) {
            context.getString(R.string.screen_capture_text_controlled, viewer)
        } else {
            context.getString(R.string.screen_capture_text, viewer)
        }
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_screen)
            .setContentTitle(context.getString(R.string.screen_capture_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.screen_capture_stop), stopIntent())
            .build()
    }

    private fun consentIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, ScreenConsentActivity::class.java)
            .putExtra(ScreenConsentActivity.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_REQUESTS = "screen_requests"
        const val CHANNEL_CAPTURE = "screen_capture"
        const val REQUEST_ID = 11
        const val CAPTURE_ID = 12
    }
}
