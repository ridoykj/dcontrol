package com.dcontrol.client.feature.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.screen.ui.ScreenShareCard
import com.dcontrol.client.feature.screen.ui.ScreenShareTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenShareCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `card explains per-session consent`() {
        composeRule.setContent { ScreenShareCard() }
        composeRule.onNodeWithTag(ScreenShareTags.CARD).assertIsDisplayed()
    }
}
