package com.dcontrol.client.feature.connection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dcontrol.client.core.network.credentials.CredentialStore
import com.dcontrol.client.core.websocket.ConnectionState
import com.dcontrol.client.feature.connection.domain.ConnectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val controller: ConnectionController,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    val uiState: StateFlow<ConnectionUiState> =
        combine(credentialStore.credentials, controller.state) { credentials, state ->
            ConnectionUiState(
                isPaired = credentials != null,
                deviceName = (state as? ConnectionState.Online)?.deviceName ?: credentials?.deviceName,
                controllerHost = credentials?.host,
                status = state.toStatus(),
                retryInSeconds = (state as? ConnectionState.Reconnecting)?.delay?.inWholeSeconds,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionUiState())

    /** Forgets the controller locally; the service stops once the credentials are gone. */
    fun unpair() {
        viewModelScope.launch {
            controller.stop()
            credentialStore.clear()
        }
    }
}
