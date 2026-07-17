package com.gigagochi.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalDataStartupErrorRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retryIsAccessibleButtonWithMinimumTouchTarget() {
        var retries = 0
        composeRule.setContent {
            LocalDataStartupErrorRoute(onRetry = { retries += 1 })
        }

        composeRule.onNodeWithText("Повторить")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(LocalDataRetryMinimumTouchTarget)
            .performClick()
        assertEquals(1, retries)
    }
}
