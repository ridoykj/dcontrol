package com.dcontrol.client.feature.remotecontrol

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.remotecontrol.ui.RemoteControlCardContent
import com.dcontrol.client.feature.remotecontrol.ui.RemoteControlTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteControlCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `disabled card offers to enable and shows the restricted hint`() {
        var enables = 0
        composeRule.setContent {
            RemoteControlCardContent(enabled = false, showRestrictedHint = true, onEnable = { enables++ })
        }

        composeRule.onNodeWithTag(RemoteControlTags.RESTRICTED_HINT).assertIsDisplayed()
        composeRule.onNodeWithTag(RemoteControlTags.ENABLE).performClick()

        assertEquals(1, enables)
    }

    @Test
    fun `enabled card hides the enable button`() {
        composeRule.setContent {
            RemoteControlCardContent(enabled = true, showRestrictedHint = false, onEnable = {})
        }

        composeRule.onNodeWithTag(RemoteControlTags.ENABLE).assertDoesNotExist()
    }
}
