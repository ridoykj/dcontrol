package com.dcontrol.client.feature.pairing.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfirmPairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val confirmState = PairingUiState(
        step = PairingStep.Confirm,
        host = "10.0.2.2:8080",
        accountEmail = "r***@example.com",
        expiresAt = Instant.parse("2026-10-29T10:18:30Z"),
        deviceName = "Pixel 9",
    )

    @Test
    fun showsControllerHostAndAccountBeforePairing() {
        composeRule.setContent { ConfirmPairingScreen(confirmState, {}, {}, {}) }

        composeRule.onNodeWithTag(ConfirmPairingTags.HOST).assertTextContains("10.0.2.2:8080", substring = true)
        composeRule.onNodeWithTag(ConfirmPairingTags.ACCOUNT).assertTextContains("r***@example.com", substring = true)
        composeRule.onNodeWithTag(ConfirmPairingTags.PAIR).assertIsEnabled()
    }

    @Test
    fun pairAndCancelInvokeCallbacks() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            ConfirmPairingScreen(confirmState, {}, onConfirm = { confirmed++ }, onCancel = { cancelled++ })
        }

        composeRule.onNodeWithTag(ConfirmPairingTags.PAIR).performClick()
        composeRule.onNodeWithTag(ConfirmPairingTags.CANCEL).performClick()

        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun editingNameReportsNewValue() {
        var name = ""
        composeRule.setContent { ConfirmPairingScreen(confirmState, { name = it }, {}, {}) }

        composeRule.onNodeWithTag(ConfirmPairingTags.NAME).performTextReplacement("Work phone")

        assertEquals("Work phone", name)
    }

    @Test
    fun pairIsDisabledWithBlankNameAndWhileLoading() {
        composeRule.setContent { ConfirmPairingScreen(confirmState.copy(deviceName = " "), {}, {}, {}) }
        composeRule.onNodeWithTag(ConfirmPairingTags.PAIR).assertIsNotEnabled()
    }

    @Test
    fun loadingPreviewShowsProgressInsteadOfAccount() {
        composeRule.setContent {
            ConfirmPairingScreen(confirmState.copy(step = PairingStep.LoadingPreview, accountEmail = null), {}, {}, {})
        }

        composeRule.onNodeWithTag(ConfirmPairingTags.LOADING).assertIsDisplayed()
        composeRule.onNodeWithTag(ConfirmPairingTags.PAIR).assertIsNotEnabled()
    }

    @Test
    fun errorIsShown() {
        composeRule.setContent { ConfirmPairingScreen(confirmState.copy(error = PairingError.Network), {}, {}, {}) }

        composeRule.onNodeWithTag(ConfirmPairingTags.ERROR).assertIsDisplayed()
    }
}
