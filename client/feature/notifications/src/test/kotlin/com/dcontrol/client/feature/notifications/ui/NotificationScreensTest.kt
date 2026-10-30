package com.dcontrol.client.feature.notifications.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcontrol.client.core.database.MirroredAppEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mirroredAppsShowTogglesAndReportChanges() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        val apps = listOf(
            MirroredAppEntity("com.chat", "Chat", enabled = true, lastSeenAtMillis = 1),
            MirroredAppEntity("com.bank", "Bank", enabled = false, lastSeenAtMillis = 1),
        )
        composeRule.setContent { MirroredAppsScreen(apps = apps, onToggle = { p, e -> toggles += p to e }, onBack = {}) }

        composeRule.onNodeWithTag(MirroredAppsTags.toggle("com.chat")).assertIsOn()
        composeRule.onNodeWithTag(MirroredAppsTags.toggle("com.bank")).assertIsOff().performClick()

        assertEquals(listOf("com.bank" to true), toggles)
        composeRule.onNodeWithTag(MirroredAppsTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun mirroredAppsShowEmptyState() {
        composeRule.setContent { MirroredAppsScreen(apps = emptyList(), onToggle = { _, _ -> }, onBack = {}) }

        composeRule.onNodeWithTag(MirroredAppsTags.EMPTY).assertExists()
    }

    @Test
    fun accessCardOffersGrantOnlyWhenMissing() {
        var grants = 0
        var chooses = 0
        composeRule.setContent { NotificationAccessCardContent(granted = false, onGrant = { grants++ }, onChooseApps = { chooses++ }) }

        composeRule.onNodeWithTag(NotificationAccessTags.GRANT).performClick()
        composeRule.onNodeWithTag(NotificationAccessTags.CHOOSE_APPS).performClick()

        assertEquals(1, grants)
        assertEquals(1, chooses)
    }
}
