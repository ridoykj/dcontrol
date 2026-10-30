package com.dcontrol.client.feature.camera.ui

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
import com.dcontrol.client.feature.camera.R

object CameraShareTags {
    const val CARD = "camera_share_card"
    const val GRANT = "camera_share_grant"
}

private val CAMERA_ONLY = arrayOf(Manifest.permission.CAMERA)
private val CAMERA_AND_MIC = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/** Status-screen card: per-session consent, and the CAMERA grant (the microphone is optional, for sound). */
@Composable
fun CameraShareCard(modifier: Modifier = Modifier) {
    val camera = rememberRuntimePermissionsState(CAMERA_ONLY)
    val both = rememberRuntimePermissionsState(CAMERA_AND_MIC)
    CameraShareCardContent(
        cameraGranted = camera.granted,
        micGranted = both.granted,
        blocked = both.blocked,
        onGrant = both::request,
        modifier = modifier,
    )
}

@Composable
fun CameraShareCardContent(
    cameraGranted: Boolean,
    micGranted: Boolean,
    blocked: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier.testTag(CameraShareTags.CARD)) {
        Text(stringResource(R.string.camera_share_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.camera_share_consent), style = MaterialTheme.typography.bodyMedium)
        val status = when {
            cameraGranted && micGranted -> R.string.camera_share_granted_audio
            cameraGranted -> R.string.camera_share_granted
            else -> R.string.camera_share_missing
        }
        Text(
            stringResource(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!micGranted) {
            Button(onClick = onGrant, modifier = Modifier.testTag(CameraShareTags.GRANT)) {
                Text(stringResource(if (blocked) R.string.camera_share_open_settings else R.string.camera_share_grant))
            }
        }
    }
}
