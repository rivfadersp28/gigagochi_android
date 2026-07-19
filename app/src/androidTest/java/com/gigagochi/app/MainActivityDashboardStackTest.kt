package com.gigagochi.app

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.gigagochi.app.feature.dashboard.DashboardVideoTestProbe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class MainActivityDashboardStackTest {
    private val activityRule = ActivityScenarioRule<MainActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("gigagochi.route", "dashboard"),
    )
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(composeRule).around(activityRule)

    @Test
    fun eventsRoundTripKeepsTheDashboardVideoPlayerAlive() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("События")
                .fetchSemanticsNodes().isNotEmpty()
        }
        val initialPlayerCount = composeRule.runOnIdle {
            DashboardVideoTestProbe.createdPlayerCount
        }

        composeRule.onNodeWithContentDescription("События").performClick()
        composeRule.onNodeWithText("События").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(initialPlayerCount, DashboardVideoTestProbe.createdPlayerCount)
        }

        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("События")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertEquals(initialPlayerCount, DashboardVideoTestProbe.createdPlayerCount)
        }
    }
}
