package com.dcontrol.client.feature.mic.ui

import android.Manifest
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.rememberRuntimePermissionsState
import com.dcontrol.client.feature.mic.R

object MicShareTags {
    const val CARD = "mic_share_card"
    const val GRANT = "mic_share_grant"
}

private val MICROPHONE = arrayOf(Manifest.permission.RECORD_AUDIO)

/** Status-screen card: per-session consent, and the RECORD_AUDIO grant the web Microphone tab needs. */
@Composable
fun MicShareCard(modifier: Modifier = Modifier) {
    val microphone = rememberRuntimePermissionsState(MICROPHONE)
    MicShareCardContent(
        granted = microphone.granted,
        blocked = microphone.blocked,
        onGrant = microphone::request,
        modifier = modifier,
    )
}

@Composable
fun MicShareCardContent(
    granted: Boolean,
    blocked: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(MicShareTags.CARD)) {
        Text(stringResource(R.string.mic_share_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mic_share_consent), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (granted) R.string.mic_share_granted else R.string.mic_share_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            Button(onClick = onGrant, modifier = Modifier.testTag(MicShareTags.GRANT)) {
                Text(stringResource(if (blocked) R.string.mic_share_open_settings else R.string.mic_share_grant))
            }
        }
    }
}
