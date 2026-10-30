package com.dcontrol.client.feature.camera

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.camera.ui.CameraShareCardContent
import com.dcontrol.client.feature.camera.ui.CameraShareTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraShareCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `missing grants offer the Grant button`() {
        var requests = 0
        composeRule.setContent {
            CameraShareCardContent(cameraGranted = false, micGranted = false, blocked = false, onGrant = { requests++ })
        }

        composeRule.onNodeWithTag(CameraShareTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(CameraShareTags.GRANT).performClick()
        assertEquals(1, requests)
    }

    @Test
    fun `with camera and microphone granted only the explanation remains`() {
        composeRule.setContent {
            CameraShareCardContent(cameraGranted = true, micGranted = true, blocked = false, onGrant = {})
        }

        composeRule.onNodeWithTag(CameraShareTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(CameraShareTags.GRANT).assertDoesNotExist()
    }
}
