package com.gigagochi.app.debugmenu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.model.PetDashboardState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DebugMenuHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun globalTriggerOpensFromEveryRouteLabel() {
        var route by mutableStateOf("Create")
        composeRule.setContent {
            GigagochiTheme { DebugMenuHost(bindings(routeName = route)) }
        }
        listOf("Create", "Dashboard", "Events", "Travel", "Story", "ConnectionError")
            .forEach { expected ->
                composeRule.onNodeWithContentDescription("Открыть debug-меню").performClick()
                composeRule.onNodeWithText(expected).assertIsDisplayed()
                composeRule.onNodeWithText("Закрыть").performClick()
                route = when (expected) {
                    "Create" -> "Dashboard"
                    "Dashboard" -> "Events"
                    "Events" -> "Travel"
                    "Travel" -> "Story"
                    "Story" -> "ConnectionError"
                    else -> expected
                }
                composeRule.waitForIdle()
            }
    }

    @Test
    fun onboardingToggleReflectsStateAndFixtureActionIsWired() {
        var onboardingCalls = 0
        var fixtureCalls = 0
        var onboardingActive by mutableStateOf(false)
        composeRule.setContent {
            GigagochiTheme {
                DebugMenuHost(
                    bindings(
                        onboardingActive = onboardingActive,
                        onToggleOnboarding = {
                            onboardingCalls += 1
                            onboardingActive = !onboardingActive
                        },
                        onOpenFixture = { fixtureCalls += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Открыть debug-меню").performClick()
        composeRule.onNodeWithText("Включить onboarding").performClick()
        assertEquals(1, onboardingCalls)
        composeRule.onNodeWithContentDescription("Открыть debug-меню").performClick()
        composeRule.onNodeWithText("Выключить onboarding").performClick()
        assertEquals(2, onboardingCalls)
        composeRule.onNodeWithContentDescription("Открыть debug-меню").performClick()
        composeRule.onNodeWithText("Открыть тестового персонажа").performClick()
        assertEquals(1, fixtureCalls)
    }

    @Test
    fun pushTestActionIsAvailableInDebugMenu() {
        composeRule.setContent {
            GigagochiTheme { DebugMenuHost(bindings()) }
        }

        composeRule.onNodeWithContentDescription("Открыть debug-меню").performClick()
        composeRule.onNodeWithText("Отправить пуш").assertIsDisplayed()
    }

    private fun bindings(
        routeName: String = "Dashboard",
        onboardingActive: Boolean = false,
        onToggleOnboarding: () -> Unit = {},
        onOpenFixture: () -> Unit = {},
    ) = DebugMenuBindings(
        routeName = routeName,
        pet = PetDashboardState(
            "pet", "assets", "Человек-яблоко", "Тото", "baby", "Малыш",
            "idle", 1_000, 100, 100, 100, "Привет",
        ),
        firstSession = null,
        onboardingActive = onboardingActive,
        savedPetAvailable = true,
        fixtureActive = false,
        visualMoodOverride = null,
        isPetDead = false,
        onToggleOnboarding = onToggleOnboarding,
        onOpenFixture = onOpenFixture,
        onRestoreSavedPet = {},
        onOpenTravelDemo = {},
        onResetStats = {},
        onVisualMoodOverride = {},
        onKillPet = {},
        onRevivePet = {},
        onCreateNewPet = {},
        onSendPush = {},
    )
}
