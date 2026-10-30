package com.dcontrol.client.feature.notifications.data

import android.service.notification.StatusBarNotification
import com.dcontrol.client.core.database.MirroredAppDao
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.NotificationKeyPayload
import com.dcontrol.client.core.websocket.protocol.NotificationPayload
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the listener service can do; the bound [android.service.notification.NotificationListenerService]. */
interface ActiveNotificationSource {
    fun active(): List<StatusBarNotification>
    fun cancel(key: String)
}

/**
 * Forwards notifications from the listener service to the controller as `notification.posted` /
 * `notification.removed` events. Nothing is stored: the web asks for `notifications.list` when it opens.
 */
@Singleton
class NotificationMirror @Inject constructor(
    private val mapper: NotificationMapper,
    private val icons: AppIcons,
    private val apps: MirroredAppDao,
    private val events: DeviceEventSender,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val disabled: StateFlow<Set<String>> = apps.observeDisabled()
        .map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())
    private val posted = EventCoalescer<NotificationPayload>(scope, COALESCE_MILLIS, System::currentTimeMillis) {
        events.send(MessageTypes.NOTIFICATION_POSTED, NotificationPayload.serializer(), it)
    }

    /** The bound listener while Notification access is granted and the system has connected it. */
    @Volatile
    var source: ActiveNotificationSource? = null

    fun isMirrored(packageName: String): Boolean = packageName !in disabled.value

    fun onPosted(sbn: StatusBarNotification) {
        if (!mapper.isMirrorable(sbn)) return
        scope.launch {
            val app = mapper.app(sbn)
            if (seen.add(app.packageName)) apps.recordSeen(app.packageName, app.label, System.currentTimeMillis())
            if (!isMirrored(app.packageName)) return@launch
            posted.post(sbn.key, toPayload(sbn, app))
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (!mapper.isMirrorable(sbn) || !isMirrored(sbn.packageName)) return
        posted.remove(sbn.key)
        events.send(MessageTypes.NOTIFICATION_REMOVED, NotificationKeyPayload.serializer(), NotificationKeyPayload(sbn.key))
    }

    /** Mirrorable active notifications of enabled apps, newest first. */
    fun active(): List<StatusBarNotification>? = source?.active()
        ?.filter { mapper.isMirrorable(it) && isMirrored(it.packageName) }
        ?.sortedByDescending { it.postTime }

    fun toPayload(sbn: StatusBarNotification, app: PostingApp = mapper.app(sbn)): NotificationPayload {
        val icon = icons.iconOf(app)?.also { icons.ensureUploaded(it) }
        return mapper.map(sbn, app, icon?.hash)
    }

    private companion object {
        const val COALESCE_MILLIS = 250L
    }
}
