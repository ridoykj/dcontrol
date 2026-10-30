package com.dcontrol.client.feature.pairing.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dcontrol.client.core.permissions.PermissionRationale
import com.dcontrol.client.core.permissions.isPermissionGranted
import com.dcontrol.client.core.permissions.openAppSettings
import com.dcontrol.client.core.permissions.rememberPermissionRequester
import com.dcontrol.client.feature.pairing.R

@Composable
fun ScanScreen(
    error: PairingError?,
    onCodeScanned: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.isPermissionGranted(Manifest.permission.CAMERA)) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val requester = rememberPermissionRequester(Manifest.permission.CAMERA) { result ->
        granted = result
        permanentlyDenied = !result
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.pairing_scan_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pairing_scan_instructions), style = MaterialTheme.typography.bodyMedium)

            if (granted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    QrScanner(onScanned = onCodeScanned, modifier = Modifier.fillMaxSize())
                }
            } else {
                PermissionRationale(
                    title = stringResource(R.string.pairing_camera_rationale_title),
                    text = stringResource(R.string.pairing_camera_rationale_text),
                    confirmLabel = stringResource(R.string.pairing_camera_allow),
                    onConfirm = { requester.request() },
                    permanentlyDenied = permanentlyDenied && !requester.shouldShowRationale(),
                    settingsLabel = stringResource(R.string.pairing_open_settings),
                    onOpenSettings = { context.openAppSettings() },
                )
            }

            error?.let {
                Text(pairingErrorText(it), color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.pairing_cancel))
            }
        }
    }
}
