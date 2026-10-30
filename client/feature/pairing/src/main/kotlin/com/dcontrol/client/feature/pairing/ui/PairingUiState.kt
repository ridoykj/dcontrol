package com.dcontrol.client.feature.pairing.ui

import java.time.Instant

enum class PairingStep { Scan, LoadingPreview, Confirm, Claiming, Paired }

enum class PairingError { InvalidCode, Expired, Network, Rejected, LocalNetworkDenied }

data class PairingUiState(
    val step: PairingStep = PairingStep.Scan,
    val host: String? = null,
    val accountEmail: String? = null,
    val expiresAt: Instant? = null,
    val deviceName: String = "",
    val error: PairingError? = null,
) {
    val canConfirm: Boolean get() = step == PairingStep.Confirm && deviceName.isNotBlank()
}
