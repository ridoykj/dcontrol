package com.dcontrol.client.feature.notifications.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.feature.notifications.R
import com.dcontrol.client.feature.notifications.service.MirrorListenerService

object NotificationAccessTags {
    const val GRANT = "notification_access_grant"
    const val CHOOSE_APPS = "notification_access_choose_apps"
}

/** Status-screen card: Notification access state, a shortcut to grant it and the per-app choice. */
@Composable
fun NotificationAccessCard(onChooseApps: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasNotificationAccess()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasNotificationAccess() }
    NotificationAccessCardContent(
        granted = granted,
        onGrant = { context.openNotificationAccessSettings() },
        onChooseApps = onChooseApps,
        modifier = modifier,
    )
}

@Composable
fun NotificationAccessCardContent(
    granted: Boolean,
    onGrant: () -> Unit,
    onChooseApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier) {
        Text(stringResource(R.string.notification_access_title), style = MaterialTheme.typography.titleMedium)
        if (granted) {
            Text(stringResource(R.string.notification_access_granted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.notification_access_missing), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.notification_access_restricted_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!granted) {
                Button(onClick = onGrant, modifier = Modifier.testTag(NotificationAccessTags.GRANT)) {
                    Text(stringResource(R.string.notification_access_grant))
                }
            }
            OutlinedButton(onClick = onChooseApps, modifier = Modifier.testTag(NotificationAccessTags.CHOOSE_APPS)) {
                Text(stringResource(R.string.notification_access_choose_apps))
            }
        }
    }
}

fun Context.hasNotificationAccess(): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

/** The per-app Notification access page on API 30+, the general list before. */
fun Context.openNotificationAccessSettings() {
    val general = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this, MirrorListenerService::class.java).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        general
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(general)
    }
}
