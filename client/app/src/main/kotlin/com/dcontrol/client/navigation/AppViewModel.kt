package com.dcontrol.client.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dcontrol.client.core.network.credentials.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AppViewModel @Inject constructor(credentialStore: CredentialStore) : ViewModel() {

    /** Null until the credentials have been read. */
    val isPaired: StateFlow<Boolean?> = credentialStore.credentials
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
