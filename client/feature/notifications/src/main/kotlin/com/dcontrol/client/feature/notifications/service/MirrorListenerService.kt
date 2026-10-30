package com.dcontrol.client.feature.notifications.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dcontrol.client.feature.notifications.data.ActiveNotificationSource
import com.dcontrol.client.feature.notifications.data.NotificationMirror
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Bound by the system once the user grants Notification access. It is not a foreground service and needs
 * no foreground-service type; it only forwards to [NotificationMirror].
 */
@AndroidEntryPoint
class MirrorListenerService : NotificationListenerService() {

    @Inject
    lateinit var mirror: NotificationMirror

    private val source = object : ActiveNotificationSource {
        override fun active(): List<StatusBarNotification> = activeNotifications?.toList().orEmpty()
        override fun cancel(key: String) = cancelNotification(key)
    }

    override fun onListenerConnected() {
        mirror.source = source
    }

    override fun onListenerDisconnected() {
        if (mirror.source === source) mirror.source = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        mirror.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        mirror.onRemoved(sbn)
    }

    override fun onDestroy() {
        if (mirror.source === source) mirror.source = null
        super.onDestroy()
    }
}
