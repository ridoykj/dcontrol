package com.dcontrol.client.feature.sms.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.mayNeedRestrictedSettings
import com.dcontrol.client.core.permissions.rememberRuntimePermissionsState
import com.dcontrol.client.core.telephony.PermissionSets
import com.dcontrol.client.feature.sms.R

object SmsAccessTags {
    const val CARD = "sms_access_card"
    const val GRANT = "sms_access_grant"
    const val RESTRICTED_HINT = "sms_access_restricted_hint"
}

/** Status-screen card: SMS read + send permissions for the web Messages tab. */
@Composable
fun SmsAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = rememberRuntimePermissionsState(PermissionSets.SMS)
    val restricted = remember { context.mayNeedRestrictedSettings() }
    SmsAccessCardContent(
        granted = state.granted,
        blocked = state.blocked,
        showRestrictedHint = restricted,
        onGrant = state::request,
        modifier = modifier,
    )
}

@Composable
fun SmsAccessCardContent(
    granted: Boolean,
    blocked: Boolean,
    showRestrictedHint: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(SmsAccessTags.CARD)) {
        Text(stringResource(R.string.sms_access_title), style = MaterialTheme.typography.titleMedium)
        if (granted) {
            Text(stringResource(R.string.sms_access_granted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.sms_access_missing), style = MaterialTheme.typography.bodyMedium)
            if (showRestrictedHint) {
                Text(
                    stringResource(R.string.sms_access_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(SmsAccessTags.RESTRICTED_HINT),
                )
            }
            Button(onClick = onGrant, modifier = Modifier.testTag(SmsAccessTags.GRANT)) {
                Text(stringResource(if (blocked) R.string.sms_access_open_settings else R.string.sms_access_grant))
            }
        }
    }
}
