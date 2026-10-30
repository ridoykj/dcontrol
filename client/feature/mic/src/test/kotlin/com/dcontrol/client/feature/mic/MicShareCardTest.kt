package com.dcontrol.client.feature.mic

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.mic.ui.MicShareCardContent
import com.dcontrol.client.feature.mic.ui.MicShareTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicShareCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a missing grant offers the Grant button`() {
        var requests = 0
        composeRule.setContent {
            MicShareCardContent(granted = false, blocked = false, onGrant = { requests++ })
        }

        composeRule.onNodeWithTag(MicShareTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(MicShareTags.GRANT).performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `with the microphone granted only the explanation remains`() {
        composeRule.setContent {
            MicShareCardContent(granted = true, blocked = false, onGrant = {})
        }

        composeRule.onNodeWithTag(MicShareTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(MicShareTags.GRANT).assertDoesNotExist()
    }
}
