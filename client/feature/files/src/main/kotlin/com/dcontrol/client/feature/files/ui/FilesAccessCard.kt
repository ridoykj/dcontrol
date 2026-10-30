package com.dcontrol.client.feature.files.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.dcontrol.client.core.permissions.findActivity
import com.dcontrol.client.core.permissions.openAppSettings
import com.dcontrol.client.core.storage.LEGACY_STORAGE_PERMISSIONS
import com.dcontrol.client.core.storage.hasStorageAccess
import com.dcontrol.client.core.storage.openAllFilesAccessSettings
import com.dcontrol.client.feature.files.R

object FilesAccessTags {
    const val CARD = "files_access_card"
    const val GRANT = "files_access_grant"
}

/** Status-screen card: shows whether the web file manager can reach the phone's storage. */
@Composable
fun FilesAccessCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasStorageAccess()) }
    var askedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasStorageAccess() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasStorageAccess()
        askedOnce = true
    }

    FilesAccessCardContent(
        granted = granted,
        modifier = modifier,
        onGrant = {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.openAllFilesAccessSettings()
                askedOnce && !canAskAgain(context) -> context.openAppSettings()
                else -> launcher.launch(LEGACY_STORAGE_PERMISSIONS)
            }
        },
    )
}

@Composable
fun FilesAccessCardContent(granted: Boolean, onGrant: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier.testTag(FilesAccessTags.CARD)) {
        Text(stringResource(R.string.files_access_title), style = MaterialTheme.typography.titleMedium)
        if (granted) {
            Text(stringResource(R.string.files_access_granted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.files_access_missing), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGrant, modifier = Modifier.testTag(FilesAccessTags.GRANT)) {
                Text(stringResource(R.string.files_access_grant))
            }
        }
    }
}

private fun canAskAgain(context: android.content.Context): Boolean {
    val activity = context.findActivity() ?: return true
    return LEGACY_STORAGE_PERMISSIONS.any { activity.shouldShowRequestPermissionRationale(it) }
}
