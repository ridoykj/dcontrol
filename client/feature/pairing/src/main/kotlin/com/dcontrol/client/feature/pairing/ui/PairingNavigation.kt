package com.dcontrol.client.feature.pairing.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dcontrol.client.core.permissions.isPermissionGranted
import com.dcontrol.client.core.permissions.rememberPermissionRequester
import com.dcontrol.client.feature.pairing.domain.LocalNetwork
import com.dcontrol.client.feature.pairing.domain.QrPayloadParser
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

/** [payload] carries a QR payload that arrived through the debug deep link. */
@Serializable
data class PairingDestination(val payload: String? = null)

fun NavGraphBuilder.pairingScreen(onPaired: () -> Unit, onCancel: () -> Unit) {
    composable<PairingDestination> { entry ->
        PairingRoute(payload = entry.toRoute<PairingDestination>().payload, onPaired = onPaired, onCancel = onCancel)
    }
}

@Composable
fun PairingRoute(
    payload: String?,
    onPaired: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Android 17+ blocks connections to private addresses without ACCESS_LOCAL_NETWORK, so a code for a LAN or
    // emulator controller asks for it first. The camera keeps reporting the same code: one request per code, and a
    // denied code is ignored until a new one is shown.
    var awaitingLocalNetwork by remember { mutableStateOf<String?>(null) }
    var deniedCode by remember { mutableStateOf<String?>(null) }
    val localNetwork = rememberPermissionRequester(LOCAL_NETWORK_PERMISSION) { granted ->
        val code = awaitingLocalNetwork ?: return@rememberPermissionRequester
        awaitingLocalNetwork = null
        if (granted) {
            viewModel.onCodeScanned(code)
        } else {
            deniedCode = code
            viewModel.onLocalNetworkDenied()
        }
    }
    val onCode: (String) -> Unit = { raw ->
        when {
            awaitingLocalNetwork != null || raw == deniedCode -> Unit
            needsLocalNetwork(context, raw) -> {
                awaitingLocalNetwork = raw
                localNetwork.request()
            }
            else -> viewModel.onCodeScanned(raw)
        }
    }

    LaunchedEffect(payload) {
        payload?.let(onCode)
    }
    LaunchedEffect(state.step) {
        if (state.step == PairingStep.Paired) onPaired()
    }

    when (state.step) {
        PairingStep.Scan -> ScanScreen(
            error = state.error,
            onCodeScanned = onCode,
            onCancel = onCancel,
        )
        PairingStep.LoadingPreview, PairingStep.Confirm, PairingStep.Claiming -> ConfirmPairingScreen(
            state = state,
            onDeviceNameChange = viewModel::onDeviceNameChange,
            onConfirm = viewModel::onConfirm,
            onCancel = viewModel::onCancel,
        )
        PairingStep.Paired -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

// Manifest.permission.ACCESS_LOCAL_NETWORK, spelled out so the call sites need no API 37 guard of their own.
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_SDK = 37

private fun needsLocalNetwork(context: Context, raw: String): Boolean {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) return false
    val payload = QrPayloadParser.parse(raw) ?: return false
    return LocalNetwork.isLocal(payload.baseUrl) && !context.isPermissionGranted(LOCAL_NETWORK_PERMISSION)
}
