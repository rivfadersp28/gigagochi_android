package com.gigagochi.app.feature.create

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import com.gigagochi.app.core.designsystem.GigagochiTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CreatePetScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyQuestionTitleFitsWithoutVisualOverflow() {
        val state = mutableStateOf(CreatePetState())
        composeRule.setContent {
            GigagochiTheme {
                CreatePetScreen(
                    state = state.value,
                    reducedMotion = true,
                    requestCustomIme = false,
                    onBackgroundPhaseComplete = {},
                    onAnswer = {},
                    onOpenCustom = {},
                    onCustomValueChange = {},
                    onCloseCustom = {},
                    onSubmitCustom = {},
                    onRetry = {},
                )
            }
        }

        CreationQuestions.forEachIndexed { index, question ->
            composeRule.runOnIdle { state.value = CreatePetState(step = index) }
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(question.title)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(layouts)
                }
            assertFalse(
                "Question title has visual overflow: ${question.title}",
                layouts.single().hasVisualOverflow,
            )
        }
    }

    @Test
    fun firstPressStartsFakeGenerationAndReadyNavigatesOnlyAfterAllAnswers() {
        var requests = 0
        var navigations = 0
        val result = CompletableDeferred<GeneratedPetFixture>()
        val adapter = object : PetGenerationAdapter {
            override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
                requests += 1
                return result.await()
            }
        }
        composeRule.setContent {
            GigagochiTheme {
                CreatePetRoute(
                    generationAdapter = adapter,
                    readyTransitionDelayMillis = 0,
                    reducedMotionOverride = true,
                    onNavigateDashboard = { navigations += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Ледяного дракона").performClick()
        composeRule.waitUntil { requests == 1 }
        composeRule.onNodeWithText("Как его будут звать?").assertIsDisplayed()
        composeRule.runOnIdle { result.complete(GeneratedPetFixture("Ледяного дракона")) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, navigations) }

        composeRule.onNodeWithContentDescription("Тото").performClick()
        composeRule.onNodeWithContentDescription("Добрый").performClick()
        composeRule.onNodeWithContentDescription("Пауков").performClick()
        composeRule.onNodeWithContentDescription("Вантуз").performClick()
        composeRule.waitUntil { navigations == 1 }
        composeRule.runOnIdle {
            assertEquals(1, requests)
            assertEquals(1, navigations)
        }
    }

    @Test
    fun customNextAppearsOnlyForNonBlankValue() {
        val state = mutableStateOf(CreatePetState().openCustomInput())
        composeRule.setContent {
            GigagochiTheme {
                CreatePetScreen(
                    state = state.value,
                    reducedMotion = true,
                    requestCustomIme = false,
                    onBackgroundPhaseComplete = {},
                    onAnswer = {},
                    onOpenCustom = {},
                    onCustomValueChange = {},
                    onCloseCustom = { state.value = state.value.closeCustomInput() },
                    onSubmitCustom = {},
                    onRetry = {},
                )
            }
        }
        composeRule.onAllNodesWithContentDescription("Далее").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Назад").assertIsDisplayed()

        composeRule.runOnIdle { state.value = state.value.updateCustomValue("Кот из облака") }
        composeRule.onNodeWithContentDescription("Далее").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText("Кого хочешь создать?").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Назад").assertCountEquals(0)
    }
}
