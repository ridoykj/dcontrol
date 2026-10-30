package com.dcontrol.client.feature.notifications.data

import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.dcontrol.client.core.websocket.protocol.NotificationPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The app that posted a notification, read from the notification itself (no QUERY_ALL_PACKAGES needed). */
data class PostingApp(val packageName: String, val label: String, val info: ApplicationInfo?)

/** Turns a [StatusBarNotification] into the protocol's `Notification`, truncated to the protocol limits. */
@Singleton
class NotificationMapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    /** Own notifications and group summaries (duplicates of their children) are never mirrored. */
    fun isMirrorable(sbn: StatusBarNotification): Boolean =
        sbn.packageName != context.packageName &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            sbn.key.length <= MAX_KEY &&
            sbn.packageName.length <= MAX_PACKAGE

    fun app(sbn: StatusBarNotification): PostingApp {
        val info = BundleCompat.getParcelable(sbn.notification.extras, EXTRA_BUILDER_APPLICATION_INFO, ApplicationInfo::class.java)
        val label = info?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: sbn.packageName
        return PostingApp(sbn.packageName, label.take(MAX_LABEL), info)
    }

    fun map(sbn: StatusBarNotification, app: PostingApp, iconHash: String?): NotificationPayload {
        val extras: Bundle = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        return NotificationPayload(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = app.label,
            iconHash = iconHash,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(MAX_TITLE),
            text = text?.toString()?.take(MAX_TEXT),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.take(MAX_SUB_TEXT),
            postedAt = Instant.ofEpochMilli(sbn.postTime).toString(),
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            canReply = replyAction(sbn.notification) != null,
        )
    }

    /** The first action that takes free-form text (the "Reply" button of messaging apps). */
    fun replyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.actionIntent != null && action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    companion object {
        /**
         * `Notification.EXTRA_BUILDER_APPLICATION_INFO` (hidden in the SDK): the posting app's ApplicationInfo,
         * which Notification.Builder adds to every notification.
         */
        const val EXTRA_BUILDER_APPLICATION_INFO = "android.appInfo"
        const val MAX_KEY = 512
        const val MAX_PACKAGE = 255
        const val MAX_LABEL = 100
        const val MAX_TITLE = 200
        const val MAX_TEXT = 2000
        const val MAX_SUB_TEXT = 200
    }
}
