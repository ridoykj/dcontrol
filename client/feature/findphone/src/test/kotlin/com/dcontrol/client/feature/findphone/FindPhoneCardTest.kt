package com.dcontrol.client.feature.findphone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.findphone.ui.FindPhoneCardContent
import com.dcontrol.client.feature.findphone.ui.FindPhoneTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FindPhoneCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `offers the location grant first`() {
        var grants = 0
        composeRule.setContent {
            FindPhoneCardContent(foregroundGranted = false, backgroundGranted = false, onGrantForeground = { grants++ }, onAllowAllTime = {})
        }
        composeRule.onNodeWithTag(FindPhoneTags.GRANT).performClick()
        assertEquals(1, grants)
    }

    @Test
    fun `asks for allow all the time once foreground is granted`() {
        var allowAllTime = 0
        composeRule.setContent {
            FindPhoneCardContent(foregroundGranted = true, backgroundGranted = false, onGrantForeground = {}, onAllowAllTime = { allowAllTime++ })
        }
        composeRule.onNodeWithTag(FindPhoneTags.ALLOW_ALL_TIME).performClick()
        assertEquals(1, allowAllTime)
    }

    @Test
    fun `shows ready when granted all the time`() {
        composeRule.setContent {
            FindPhoneCardContent(foregroundGranted = true, backgroundGranted = true, onGrantForeground = {}, onAllowAllTime = {})
        }
        composeRule.onNodeWithTag(FindPhoneTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(FindPhoneTags.GRANT).assertDoesNotExist()
        composeRule.onNodeWithTag(FindPhoneTags.ALLOW_ALL_TIME).assertDoesNotExist()
    }
}
