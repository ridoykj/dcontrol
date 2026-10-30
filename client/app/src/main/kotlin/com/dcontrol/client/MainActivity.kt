package com.dcontrol.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dcontrol.client.core.designsystem.theme.DControlTheme
import com.dcontrol.client.navigation.AppRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingPairingPayload by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only the debug manifest declares the dcontrol://pair filter, so release builds never get a link here.
        pendingPairingPayload = PairingLink.initial(intent?.data, savedInstanceState)
        setContent {
            DControlTheme {
                AppRoot(
                    pairingPayload = pendingPairingPayload,
                    onPairingPayloadConsumed = { pendingPairingPayload = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PairingLink.of(intent.data)?.let { pendingPairingPayload = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        PairingLink.save(outState, pendingPairingPayload)
    }
}
