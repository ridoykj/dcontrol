package com.dcontrol.client.feature.connection.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.dcontrol.client.feature.connection.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val online = ConnectionUiState(
        isPaired = true,
        deviceName = "Pixel 9",
        controllerHost = "10.0.2.2:8080",
        status = ConnectionStatus.Online,
    )

    @Test
    fun showsOnlineStatusDeviceAndController() {
        composeRule.setContent { ConnectionScreen(online, ignoringBatteryOptimizations = true, onOpenBatterySettings = {}, onUnpair = {}) }

        composeRule.onNodeWithTag(ConnectionTags.STATUS).assertTextContains(context.getString(R.string.connection_status_online))
        composeRule.onNodeWithTag(ConnectionTags.DEVICE_NAME).assertTextContains("Pixel 9", substring = true)
        composeRule.onNodeWithTag(ConnectionTags.HOST).assertTextContains("10.0.2.2:8080", substring = true)
        composeRule.onNodeWithTag(ConnectionTags.BATTERY_SETTINGS).assertDoesNotExist()
    }

    @Test
    fun showsRetryCountdownWhileReconnecting() {
        val state = online.copy(status = ConnectionStatus.Reconnecting, retryInSeconds = 8)
        composeRule.setContent { ConnectionScreen(state, ignoringBatteryOptimizations = true, onOpenBatterySettings = {}, onUnpair = {}) }

        composeRule.onNodeWithTag(ConnectionTags.RETRY).assertTextContains(context.getString(R.string.connection_retry_in, 8))
    }

    @Test
    fun batteryShortcutOpensSettings() {
        var opened = 0
        composeRule.setContent {
            ConnectionScreen(online, ignoringBatteryOptimizations = false, onOpenBatterySettings = { opened++ }, onUnpair = {})
        }

        composeRule.onNodeWithTag(ConnectionTags.BATTERY_SETTINGS).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun unpairAsksForConfirmation() {
        var unpaired = 0
        composeRule.setContent {
            ConnectionScreen(online, ignoringBatteryOptimizations = true, onOpenBatterySettings = {}, onUnpair = { unpaired++ })
        }

        composeRule.onNodeWithTag(ConnectionTags.UNPAIR).performClick()
        assertEquals(0, unpaired)
        composeRule.onNodeWithTag(ConnectionTags.UNPAIR_CONFIRM).assertIsDisplayed().performClick()

        assertEquals(1, unpaired)
    }

    @Test
    fun unpairIsHiddenWhenNotPaired() {
        composeRule.setContent {
            ConnectionScreen(ConnectionUiState(), ignoringBatteryOptimizations = true, onOpenBatterySettings = {}, onUnpair = {})
        }

        composeRule.onNodeWithTag(ConnectionTags.UNPAIR).assertDoesNotExist()
    }
}
