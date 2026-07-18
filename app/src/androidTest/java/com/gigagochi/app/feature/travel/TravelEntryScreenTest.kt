package com.gigagochi.app.feature.travel

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TravelEntryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pickerShowsExactFixtureAndOpensAccessibleCustomField() {
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.Picker,
                    reducedMotionOverride = true,
                    onNavigateDashboard = {},
                )
            }
        }

        composeRule.onNodeWithText("Куда отправим Без имени?").assertIsDisplayed()
        DeterministicTravelDestinations.forEach {
            composeRule.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("Свой вариант").performClick()
        composeRule.onNodeWithContentDescription("Свой вариант путешествия").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Путешествие").assertIsDisplayed()
    }

    @Test
    fun backClosesCustomBeforeRequestingDashboardNavigation() {
        var dashboardNavigations = 0
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.Picker,
                    reducedMotionOverride = true,
                    onNavigateDashboard = { dashboardNavigations += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Свой вариант").performClick()
        pressBack()
        composeRule.onNodeWithContentDescription("В горы").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dashboardNavigations) }
        pressBack()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, dashboardNavigations) }
    }

    @Test
    fun visibleBackUsesTheSameNestedThenExitSemanticsAsSystemBack() {
        var dashboardNavigations = 0
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.Picker,
                    reducedMotionOverride = true,
                    onNavigateDashboard = { dashboardNavigations += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Назад")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Свой вариант").performClick()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithContentDescription("В горы").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, dashboardNavigations) }
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, dashboardNavigations) }
    }

    @Test
    fun scheduledStoryShellExposesVisibleClose() {
        var closes = 0
        composeRule.setContent {
            GigagochiTheme {
                InteractiveTravelStoryScreen(
                    state = TravelEntryState(
                        pet = TravelEntryPet("pet", "Тото"),
                        phase = TravelEntryPhase.StoryQuestion,
                        story = onboardingBatStory("pet"),
                    ),
                    reducedMotion = true,
                    forcePoster = true,
                    scrollTarget = StoryScrollTarget.Top,
                    navigationAction = ContextualNavigationAction.Close,
                    onNavigateBack = { closes += 1 },
                    onChoice = {},
                    onFinish = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Закрыть")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun pickerToCustomAndBackKeepsOneMediaPlayerRoot() {
        TravelEntryMediaTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.Picker,
                    reducedMotionOverride = false,
                    onNavigateDashboard = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, TravelEntryMediaTestProbe.createdPlayerCount) }
        composeRule.onNodeWithContentDescription("Свой вариант").performClick()
        composeRule.onNodeWithContentDescription("Свой вариант путешествия").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, TravelEntryMediaTestProbe.createdPlayerCount) }
        pressBack()
        composeRule.onNodeWithContentDescription("В горы").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, TravelEntryMediaTestProbe.createdPlayerCount) }
    }

    @Test
    fun fallbackAndStartErrorsHaveExactAccessibleCopy() {
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.SuggestionsError,
                    reducedMotionOverride = true,
                    onNavigateDashboard = {},
                )
            }
        }
        composeRule.onNodeWithText(TravelSuggestionsFailureMessage).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(TravelSuggestionsFailureMessage).assertIsDisplayed()
    }

    @Test
    fun onboardingQuestionExposesOnlyTheCorrectEnabledChoice() {
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.StoryQuestionPoster,
                    reducedMotionOverride = true,
                    onNavigateDashboard = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("История и вопрос").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Млекопитающие")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        listOf("Птицы", "Насекомые", "Пресмыкающиеся").forEach { choice ->
            composeRule.onNodeWithContentDescription(choice)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
        }
    }

    @Test
    fun correctChoiceResolvesOnceAndFinishReturnsToDashboard() {
        var dashboardNavigations = 0
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.StoryQuestionPoster,
                    storyChoiceAdapter = FakeOnboardingTravelStoryAdapter(delayMillis = 0),
                    reducedMotionOverride = true,
                    onNavigateDashboard = { dashboardNavigations += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Млекопитающие")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Получено 200 единиц опыта")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Результат выбора").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Получено 200 единиц опыта").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Завершить")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, dashboardNavigations) }
    }

    @Test
    fun storyBackReturnsDirectlyToDashboard() {
        var dashboardNavigations = 0
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.StoryQuestionPoster,
                    reducedMotionOverride = true,
                    onNavigateDashboard = { dashboardNavigations += 1 },
                )
            }
        }

        pressBack()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, dashboardNavigations) }
    }

    @Test
    fun storyQuestionToResultKeepsOneMediaPlayerRoot() {
        TravelStoryMediaTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.StoryQuestionVideo,
                    storyChoiceAdapter = FakeOnboardingTravelStoryAdapter(delayMillis = 0),
                    reducedMotionOverride = false,
                    onNavigateDashboard = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, TravelStoryMediaTestProbe.createdPlayerCount) }
        composeRule.onNodeWithContentDescription("Млекопитающие")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithContentDescription("Получено 200 единиц опыта")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertEquals(1, TravelStoryMediaTestProbe.createdPlayerCount) }
    }

    @Test
    fun reducedMotionUsesPosterWithoutCreatingPlayer() {
        TravelStoryMediaTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme {
                TravelEntryRoute(
                    debugState = TravelDebugState.StoryReducedMotion,
                    onNavigateDashboard = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("История и вопрос").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, TravelStoryMediaTestProbe.createdPlayerCount) }
    }
}
