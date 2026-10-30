package com.dcontrol.client.feature.pairing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dcontrol.client.core.designsystem.component.LabeledValue
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.feature.pairing.R

object ConfirmPairingTags {
    const val HOST = "confirm_host"
    const val ACCOUNT = "confirm_account"
    const val NAME = "confirm_device_name"
    const val PAIR = "confirm_pair"
    const val CANCEL = "confirm_cancel"
    const val LOADING = "confirm_loading"
    const val ERROR = "confirm_error"
}

@Composable
fun ConfirmPairingScreen(
    state: PairingUiState,
    onDeviceNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.step == PairingStep.LoadingPreview || state.step == PairingStep.Claiming
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.pairing_confirm_title), style = MaterialTheme.typography.headlineSmall)

            SectionCard {
                LabeledValue(
                    label = stringResource(R.string.pairing_confirm_controller),
                    value = state.host.orEmpty(),
                    modifier = Modifier.testTag(ConfirmPairingTags.HOST),
                )
                if (state.step == PairingStep.LoadingPreview) {
                    CircularProgressIndicator(Modifier.size(24.dp).testTag(ConfirmPairingTags.LOADING))
                } else {
                    LabeledValue(
                        label = stringResource(R.string.pairing_confirm_account),
                        value = state.accountEmail.orEmpty(),
                        modifier = Modifier.testTag(ConfirmPairingTags.ACCOUNT),
                    )
                }
            }

            Text(
                stringResource(R.string.pairing_confirm_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.deviceName,
                onValueChange = onDeviceNameChange,
                label = { Text(stringResource(R.string.pairing_device_name)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag(ConfirmPairingTags.NAME),
            )

            state.error?.let { error ->
                Text(
                    pairingErrorText(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(ConfirmPairingTags.ERROR),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = state.step != PairingStep.Claiming,
                    modifier = Modifier.testTag(ConfirmPairingTags.CANCEL),
                ) {
                    Text(stringResource(R.string.pairing_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = state.canConfirm,
                    modifier = Modifier.testTag(ConfirmPairingTags.PAIR),
                ) {
                    if (state.step == PairingStep.Claiming) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.pairing_pair))
                    }
                }
            }
        }
    }
}

@Composable
internal fun pairingErrorText(error: PairingError): String = stringResource(
    when (error) {
        PairingError.InvalidCode -> R.string.pairing_error_invalid_code
        PairingError.Expired -> R.string.pairing_error_expired
        PairingError.Network -> R.string.pairing_error_network
        PairingError.Rejected -> R.string.pairing_error_rejected
        PairingError.LocalNetworkDenied -> R.string.pairing_error_local_network
    },
)
