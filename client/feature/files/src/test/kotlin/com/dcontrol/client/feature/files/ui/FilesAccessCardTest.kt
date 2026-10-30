package com.dcontrol.client.feature.files.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.feature.files.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesAccessCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun missingAccessOffersTheGrantButton() {
        var clicks = 0
        composeRule.setContent { FilesAccessCardContent(granted = false, onGrant = { clicks++ }) }

        composeRule.onNodeWithText(context.getString(R.string.files_access_missing)).assertExists()
        composeRule.onNodeWithTag(FilesAccessTags.GRANT).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun grantedAccessHidesTheButton() {
        composeRule.setContent { FilesAccessCardContent(granted = true, onGrant = {}) }

        composeRule.onNodeWithText(context.getString(R.string.files_access_granted)).assertExists()
        composeRule.onNodeWithTag(FilesAccessTags.GRANT).assertDoesNotExist()
    }
}
