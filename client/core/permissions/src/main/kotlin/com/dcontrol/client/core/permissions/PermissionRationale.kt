package com.dcontrol.client.core.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dcontrol.client.core.designsystem.component.SectionCard

/**
 * Explains why a permission is needed before the system dialog is shown. When the permission was
 * permanently denied, [onOpenSettings] sends the user to the app's settings page instead.
 */
@Composable
fun PermissionRationale(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean = false,
    settingsLabel: String = "Open settings",
    onOpenSettings: () -> Unit = {},
    dismissLabel: String? = null,
    onDismiss: () -> Unit = {},
) {
    SectionCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text(settingsLabel) }
            } else {
                Button(onClick = onConfirm) { Text(confirmLabel) }
            }
        }
    }
}
