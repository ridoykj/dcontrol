package com.dcontrol.client.feature.findphone.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.isPermissionGranted
import com.dcontrol.client.core.permissions.openAppSettings
import com.dcontrol.client.core.permissions.rememberRuntimePermissionsState
import com.dcontrol.client.feature.findphone.R

object FindPhoneTags {
    const val CARD = "find_phone_card"
    const val GRANT = "find_phone_grant"
    const val ALLOW_ALL_TIME = "find_phone_allow_all_time"
}

private val LOCATION_FOREGROUND = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Status-screen card: location for find my phone, granted in two steps up to "Allow all the time". */
@Composable
fun FindPhoneCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val foreground = rememberRuntimePermissionsState(LOCATION_FOREGROUND)
    var background by remember { mutableStateOf(context.hasBackgroundLocation()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { background = context.hasBackgroundLocation() }
    FindPhoneCardContent(
        foregroundGranted = foreground.granted,
        backgroundGranted = background,
        onGrantForeground = foreground::request,
        onAllowAllTime = { context.openAppSettings() },
        modifier = modifier,
    )
}

@Composable
fun FindPhoneCardContent(
    foregroundGranted: Boolean,
    backgroundGranted: Boolean,
    onGrantForeground: () -> Unit,
    onAllowAllTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(FindPhoneTags.CARD)) {
        Text(stringResource(R.string.find_phone_title), style = MaterialTheme.typography.titleMedium)
        when {
            !foregroundGranted -> {
                Text(stringResource(R.string.find_phone_need_location), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onGrantForeground, modifier = Modifier.testTag(FindPhoneTags.GRANT)) {
                    Text(stringResource(R.string.find_phone_grant))
                }
            }
            !backgroundGranted -> {
                Text(stringResource(R.string.find_phone_need_all_time), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onAllowAllTime, modifier = Modifier.testTag(FindPhoneTags.ALLOW_ALL_TIME)) {
                    Text(stringResource(R.string.find_phone_allow_all_time))
                }
            }
            else -> Text(stringResource(R.string.find_phone_granted), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Background location is only a separate grant on Android 10+; before that the foreground grant suffices. */
fun Context.hasBackgroundLocation(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isPermissionGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        true
    }
