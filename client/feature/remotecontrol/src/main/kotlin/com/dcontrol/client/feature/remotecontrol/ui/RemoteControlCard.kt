package com.dcontrol.client.feature.remotecontrol.ui

import android.content.ComponentName
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
import com.dcontrol.client.core.accessibility.isAccessibilityServiceEnabled
import com.dcontrol.client.core.accessibility.openAccessibilitySettings
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.mayNeedRestrictedSettings
import com.dcontrol.client.feature.remotecontrol.R
import com.dcontrol.client.feature.remotecontrol.service.ControlAccessibilityService

object RemoteControlTags {
    const val CARD = "remote_control_card"
    const val ENABLE = "remote_control_enable"
    const val RESTRICTED_HINT = "remote_control_restricted_hint"
}

/** Status-screen card: the Accessibility service state and the restricted-settings hint for sideloads. */
@Composable
fun RemoteControlCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val component = remember { ComponentName(context, ControlAccessibilityService::class.java) }
    var enabled by remember { mutableStateOf(context.isAccessibilityServiceEnabled(component)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = context.isAccessibilityServiceEnabled(component)
    }
    val restricted = remember { context.mayNeedRestrictedSettings() }
    RemoteControlCardContent(
        enabled = enabled,
        showRestrictedHint = restricted,
        onEnable = { context.openAccessibilitySettings() },
        modifier = modifier,
    )
}

@Composable
fun RemoteControlCardContent(
    enabled: Boolean,
    showRestrictedHint: Boolean,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(RemoteControlTags.CARD)) {
        Text(stringResource(R.string.remote_control_title), style = MaterialTheme.typography.titleMedium)
        if (enabled) {
            Text(stringResource(R.string.remote_control_enabled), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.remote_control_disabled), style = MaterialTheme.typography.bodyMedium)
            if (showRestrictedHint) {
                Text(
                    stringResource(R.string.remote_control_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(RemoteControlTags.RESTRICTED_HINT),
                )
            }
            Button(onClick = onEnable, modifier = Modifier.testTag(RemoteControlTags.ENABLE)) {
                Text(stringResource(R.string.remote_control_enable))
            }
        }
    }
}
