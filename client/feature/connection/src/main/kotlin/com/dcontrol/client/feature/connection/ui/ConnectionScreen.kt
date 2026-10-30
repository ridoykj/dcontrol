package com.dcontrol.client.feature.connection.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dcontrol.client.core.designsystem.component.LabeledValue
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.designsystem.component.StatusIndicator
import com.dcontrol.client.core.designsystem.theme.StatusColors
import com.dcontrol.client.feature.connection.R

object ConnectionTags {
    const val STATUS = "connection_status"
    const val RETRY = "connection_retry"
    const val DEVICE_NAME = "connection_device_name"
    const val HOST = "connection_host"
    const val BATTERY_SETTINGS = "connection_battery_settings"
    const val UNPAIR = "connection_unpair"
    const val UNPAIR_CONFIRM = "connection_unpair_confirm"
}

@Composable
fun ConnectionScreen(
    state: ConnectionUiState,
    ignoringBatteryOptimizations: Boolean,
    onOpenBatterySettings: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
    featureCards: @Composable () -> Unit = {},
) {
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.headlineSmall)

            SectionCard {
                StatusIndicator(
                    color = statusColor(state.status),
                    label = statusLabel(state.status),
                    modifier = Modifier.testTag(ConnectionTags.STATUS),
                )
                if (state.status == ConnectionStatus.Reconnecting && state.retryInSeconds != null) {
                    Text(
                        stringResource(R.string.connection_retry_in, state.retryInSeconds.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ConnectionTags.RETRY),
                    )
                }
                state.deviceName?.let {
                    LabeledValue(stringResource(R.string.connection_device_name), it, Modifier.testTag(ConnectionTags.DEVICE_NAME))
                }
                state.controllerHost?.let {
                    LabeledValue(stringResource(R.string.connection_controller), it, Modifier.testTag(ConnectionTags.HOST))
                }
            }

            SectionCard {
                Text(stringResource(R.string.connection_battery_title), style = MaterialTheme.typography.titleMedium)
                if (ignoringBatteryOptimizations) {
                    Text(stringResource(R.string.connection_battery_exempt), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.connection_battery_text), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.testTag(ConnectionTags.BATTERY_SETTINGS)) {
                        Text(stringResource(R.string.connection_battery_open))
                    }
                }
            }

            if (state.isPaired) featureCards()

            if (state.isPaired) {
                OutlinedButton(onClick = { confirmUnpair = true }, modifier = Modifier.testTag(ConnectionTags.UNPAIR)) {
                    Text(stringResource(R.string.connection_unpair))
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(stringResource(R.string.connection_unpair_title)) },
            text = { Text(stringResource(R.string.connection_unpair_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        onUnpair()
                    },
                    modifier = Modifier.testTag(ConnectionTags.UNPAIR_CONFIRM),
                ) { Text(stringResource(R.string.connection_unpair_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.connection_cancel)) }
            },
        )
    }
}

private fun statusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Online -> StatusColors.Online
    ConnectionStatus.Connecting, ConnectionStatus.Reconnecting -> StatusColors.Pending
    ConnectionStatus.Idle -> StatusColors.Offline
    ConnectionStatus.Unpaired -> StatusColors.Problem
}

@Composable
private fun statusLabel(status: ConnectionStatus): String = stringResource(
    when (status) {
        ConnectionStatus.Online -> R.string.connection_status_online
        ConnectionStatus.Connecting -> R.string.connection_status_connecting
        ConnectionStatus.Reconnecting -> R.string.connection_status_reconnecting
        ConnectionStatus.Idle -> R.string.connection_status_idle
        ConnectionStatus.Unpaired -> R.string.connection_status_unpaired
    },
)
