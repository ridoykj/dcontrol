package com.dcontrol.client.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dcontrol.client.feature.connection.ui.ConnectionDestination
import com.dcontrol.client.feature.calllog.ui.CallsAccessCard
import com.dcontrol.client.feature.clipboard.ui.ClipboardCard
import com.dcontrol.client.feature.contacts.ui.ContactsAccessCard
import com.dcontrol.client.feature.connection.ui.connectionScreen
import com.dcontrol.client.feature.files.ui.FilesAccessCard
import com.dcontrol.client.feature.notifications.ui.MirroredAppsDestination
import com.dcontrol.client.feature.notifications.ui.NotificationAccessCard
import com.dcontrol.client.feature.notifications.ui.mirroredAppsScreen
import com.dcontrol.client.feature.findphone.ui.FindPhoneCard
import com.dcontrol.client.feature.pairing.ui.PairingDestination
import com.dcontrol.client.feature.pairing.ui.pairingScreen
import com.dcontrol.client.feature.remotecontrol.ui.RemoteControlCard
import com.dcontrol.client.feature.screen.ui.ScreenShareCard
import com.dcontrol.client.feature.sms.ui.SmsAccessCard
import com.dcontrol.client.onboarding.OnboardingScreen
import kotlinx.serialization.Serializable

@Serializable
data object OnboardingDestination

@Composable
fun AppRoot(
    pairingPayload: String?,
    onPairingPayloadConsumed: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val isPaired by viewModel.isPaired.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        when (val paired = isPaired) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> AppNavHost(
                isPaired = paired,
                pairingPayload = pairingPayload,
                onPairingPayloadConsumed = onPairingPayloadConsumed,
            )
        }
    }
}

@Composable
private fun AppNavHost(
    isPaired: Boolean,
    pairingPayload: String?,
    onPairingPayloadConsumed: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val startDestination: Any = remember { if (isPaired) ConnectionDestination else OnboardingDestination }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<OnboardingDestination> {
            OnboardingScreen(onContinue = { navController.navigate(PairingDestination()) })
        }
        pairingScreen(
            onPaired = { navController.navigateClearingBackStack(ConnectionDestination) },
            onCancel = { if (!navController.popBackStack()) navController.navigateClearingBackStack(OnboardingDestination) },
        )
        connectionScreen(
            featureCards = {
                FilesAccessCard()
                NotificationAccessCard(onChooseApps = { navController.navigate(MirroredAppsDestination) })
                ClipboardCard()
                ContactsAccessCard()
                CallsAccessCard()
                SmsAccessCard()
                ScreenShareCard()
                RemoteControlCard()
                FindPhoneCard()
            },
        )
        mirroredAppsScreen(onBack = { navController.popBackStack() })
    }

    // Credentials disappear when the user unpairs or the controller revokes the device.
    LaunchedEffect(isPaired) {
        val onConnection = navController.currentDestination?.hasRoute(ConnectionDestination::class) == true
        if (!isPaired && onConnection) navController.navigateClearingBackStack(OnboardingDestination)
    }
    LaunchedEffect(pairingPayload) {
        if (pairingPayload != null) {
            navController.navigate(PairingDestination(pairingPayload))
            onPairingPayloadConsumed()
        }
    }
}

private fun NavHostController.navigateClearingBackStack(destination: Any) {
    navigate(destination) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
