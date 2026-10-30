package com.dcontrol.client.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dcontrol.client.R
import com.dcontrol.client.core.permissions.PermissionRationale
import com.dcontrol.client.core.permissions.isPermissionGranted
import com.dcontrol.client.core.permissions.openAppSettings
import com.dcontrol.client.core.permissions.rememberPermissionRequester

@Composable
fun OnboardingScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember {
        mutableStateOf(!needsNotificationPermission || context.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS))
    }
    var askedOnce by remember { mutableStateOf(false) }
    val requester = rememberPermissionRequester(
        if (needsNotificationPermission) Manifest.permission.POST_NOTIFICATIONS else Manifest.permission.INTERNET,
    ) { granted ->
        notificationsGranted = granted
        askedOnce = true
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.onboarding_text), style = MaterialTheme.typography.bodyLarge)

            // The connection still works without this permission; only the status notification is hidden.
            if (!notificationsGranted) {
                PermissionRationale(
                    title = stringResource(R.string.onboarding_notifications_title),
                    text = stringResource(R.string.onboarding_notifications_text),
                    confirmLabel = stringResource(R.string.onboarding_allow_notifications),
                    onConfirm = { requester.request() },
                    permanentlyDenied = askedOnce && !requester.shouldShowRationale(),
                    onOpenSettings = { context.openAppSettings() },
                )
            }

            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_scan))
            }
        }
    }
}
