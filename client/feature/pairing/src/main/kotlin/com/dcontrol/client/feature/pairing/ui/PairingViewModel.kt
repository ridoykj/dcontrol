package com.dcontrol.client.feature.pairing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dcontrol.client.core.network.api.ApiException
import com.dcontrol.client.feature.pairing.domain.PairingPayload
import com.dcontrol.client.feature.pairing.domain.PairingRepository
import com.dcontrol.client.feature.pairing.domain.QrPayloadParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState(deviceName = repository.defaultDeviceName()))
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var payload: PairingPayload? = null

    /** Called for every QR code the camera sees, and for the debug deep link. */
    fun onCodeScanned(raw: String) {
        if (_uiState.value.step != PairingStep.Scan) return
        val parsed = QrPayloadParser.parse(raw)
        if (parsed == null) {
            _uiState.update { it.copy(error = PairingError.InvalidCode) }
            return
        }
        payload = parsed
        _uiState.update { it.copy(step = PairingStep.LoadingPreview, host = parsed.host, error = null) }
        viewModelScope.launch {
            try {
                val preview = repository.preview(parsed)
                _uiState.update {
                    it.copy(step = PairingStep.Confirm, accountEmail = preview.accountEmail, expiresAt = preview.expiresAt)
                }
            } catch (e: ApiException) {
                payload = null
                _uiState.update { it.copy(step = PairingStep.Scan, host = null, error = e.toPairingError()) }
            }
        }
    }

    /** The user refused local network access, which a controller on a private address needs (Android 17+). */
    fun onLocalNetworkDenied() {
        _uiState.update { it.copy(error = PairingError.LocalNetworkDenied) }
    }

    fun onDeviceNameChange(name: String) {
        _uiState.update { it.copy(deviceName = name.take(MAX_NAME_LENGTH)) }
    }

    fun onConfirm() {
        val current = _uiState.value
        val target = payload
        if (!current.canConfirm || target == null) return
        _uiState.update { it.copy(step = PairingStep.Claiming, error = null) }
        viewModelScope.launch {
            try {
                repository.claim(target, current.deviceName.trim())
                _uiState.update { it.copy(step = PairingStep.Paired) }
            } catch (e: ApiException) {
                val error = e.toPairingError()
                if (error == PairingError.Expired) {
                    payload = null
                    _uiState.update { it.copy(step = PairingStep.Scan, host = null, accountEmail = null, expiresAt = null, error = error) }
                } else {
                    _uiState.update { it.copy(step = PairingStep.Confirm, error = error) }
                }
            }
        }
    }

    fun onCancel() {
        payload = null
        _uiState.update { PairingUiState(deviceName = it.deviceName) }
    }

    private fun ApiException.toPairingError(): PairingError = when {
        code == ApiException.NETWORK -> PairingError.Network
        status == 410 || code == PAIRING_TOKEN_INVALID -> PairingError.Expired
        else -> PairingError.Rejected
    }

    private companion object {
        const val MAX_NAME_LENGTH = 64
        const val PAIRING_TOKEN_INVALID = "PAIRING_TOKEN_INVALID"
    }
}
