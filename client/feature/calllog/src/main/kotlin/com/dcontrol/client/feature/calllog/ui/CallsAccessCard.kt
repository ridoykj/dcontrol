package com.dcontrol.client.feature.calllog.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.isPermissionGranted
import com.dcontrol.client.core.permissions.rememberRuntimePermissionsState
import com.dcontrol.client.core.telephony.PermissionSets
import com.dcontrol.client.feature.calllog.R

object CallsAccessTags {
    const val CARD = "calls_access_card"
    const val GRANT = "calls_access_grant"
    const val CALL_LOG_STATUS = "calls_access_call_log"
    const val PHONE_STATUS = "calls_access_phone"
}

private val ALL_PERMISSIONS = PermissionSets.CALL_LOG + PermissionSets.PHONE

/** Status-screen card: call log (read/delete) and phone (place calls, SIM list) permissions. */
@Composable
fun CallsAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = rememberRuntimePermissionsState(ALL_PERMISSIONS)
    // Re-read per group whenever the combined state changes (after a request or on resume).
    val callLog = state.granted || PermissionSets.CALL_LOG.all { context.isPermissionGranted(it) }
    val phone = state.granted || PermissionSets.PHONE.all { context.isPermissionGranted(it) }
    CallsAccessCardContent(callLogGranted = callLog, phoneGranted = phone, blocked = state.blocked, onGrant = state::request, modifier = modifier)
}

@Composable
fun CallsAccessCardContent(
    callLogGranted: Boolean,
    phoneGranted: Boolean,
    blocked: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(CallsAccessTags.CARD)) {
        Text(stringResource(R.string.calls_access_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (callLogGranted) R.string.calls_access_call_log_granted else R.string.calls_access_call_log_missing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(CallsAccessTags.CALL_LOG_STATUS),
        )
        Text(
            stringResource(if (phoneGranted) R.string.calls_access_phone_granted else R.string.calls_access_phone_missing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(CallsAccessTags.PHONE_STATUS),
        )
        if (!callLogGranted || !phoneGranted) {
            Button(onClick = onGrant, modifier = Modifier.testTag(CallsAccessTags.GRANT)) {
                Text(stringResource(if (blocked) R.string.calls_access_open_settings else R.string.calls_access_grant))
            }
        }
    }
}
