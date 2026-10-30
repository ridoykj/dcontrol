package com.dcontrol.client.feature.connection.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.feature.connection.R

internal class ConnectionNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.connection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.connection_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: ConnectionState): Notification {
        val text = when (state) {
            is ConnectionState.Online -> context.getString(R.string.connection_notification_online, state.deviceName)
            is ConnectionState.Connecting -> context.getString(R.string.connection_status_connecting)
            is ConnectionState.Reconnecting -> context.getString(R.string.connection_status_reconnecting)
            is ConnectionState.Unpaired -> context.getString(R.string.connection_status_unpaired)
            ConnectionState.Idle -> context.getString(R.string.connection_status_idle)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_connection)
            .setContentTitle(context.getString(R.string.connection_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.connection_send_clipboard), sendClipboardIntent())
            .build()
    }

    fun update(state: ConnectionState) {
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(state))
    }

    // Handled by ClipboardSendActivity (feature:clipboard), which reads the clip once it has window focus.
    private fun sendClipboardIntent(): PendingIntent {
        val intent = Intent(ACTION_SEND_CLIPBOARD).setPackage(context.packageName)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
        private const val ACTION_SEND_CLIPBOARD = "com.dcontrol.client.action.SEND_CLIPBOARD"
    }
}
