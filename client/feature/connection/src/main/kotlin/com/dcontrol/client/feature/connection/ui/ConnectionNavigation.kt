package com.dcontrol.client.feature.connection.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dcontrol.client.core.permissions.isIgnoringBatteryOptimizations
import com.dcontrol.client.core.permissions.openBatteryOptimizationSettings
import com.dcontrol.client.feature.connection.service.ConnectionService
import kotlinx.serialization.Serializable

@Serializable
data object ConnectionDestination

/** [featureCards] are the status cards of other features (files, notifications, clipboard), supplied by :app. */
fun NavGraphBuilder.connectionScreen(featureCards: @Composable () -> Unit = {}) {
    composable<ConnectionDestination> { ConnectionRoute(featureCards = featureCards) }
}

@Composable
fun ConnectionRoute(featureCards: @Composable () -> Unit = {}, viewModel: ConnectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var ignoringOptimizations by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoringOptimizations = context.isIgnoringBatteryOptimizations()
    }
    // Started from the UI so the foreground-service start is always allowed.
    LaunchedEffect(state.isPaired) {
        if (state.isPaired) ConnectionService.start(context)
    }

    ConnectionScreen(
        state = state,
        ignoringBatteryOptimizations = ignoringOptimizations,
        onOpenBatterySettings = { context.openBatteryOptimizationSettings() },
        onUnpair = {
            viewModel.unpair()
            ConnectionService.stop(context)
        },
        featureCards = featureCards,
    )
}
