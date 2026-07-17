package com.gigagochi.app.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gigagochi.app.core.designsystem.GigagochiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GoogleAuthScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun missingConfigurationExplainsSetupAndDisablesGoogleButton() {
        composeRule.setContent {
            GigagochiTheme {
                GoogleAuthRoute(
                    debugState = AuthDebugState.MissingConfiguration,
                    reducedMotionOverride = true,
                    onAuthenticated = {},
                )
            }
        }

        composeRule.onNodeWithText("Вход ещё не настроен").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Добавьте Google Web client ID и HTTPS адрес backend в локальную конфигурацию.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Войти через Google")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun readyButtonIsAccessibleAndPendingStateDisablesDoubleSubmit() {
        composeRule.setContent {
            GigagochiTheme {
                GoogleAuthRoute(
                    debugState = AuthDebugState.Ready,
                    reducedMotionOverride = true,
                    onAuthenticated = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Войти через Google")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText("Выбери аккаунт Google").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ожидаем выбор аккаунта").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Войти через Google").assertIsNotEnabled()
    }

    @Test
    fun recoverableErrorIsAnnouncedAndRetryReturnsToPending() {
        composeRule.setContent {
            GigagochiTheme {
                GoogleAuthRoute(
                    debugState = AuthDebugState.Error,
                    reducedMotionOverride = true,
                    onAuthenticated = {},
                )
            }
        }

        composeRule.onNodeWithText("Не получилось войти").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Не удалось связаться с сервером. Проверьте подключение к интернету.",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Войти через Google")
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithText("Выбери аккаунт Google").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Войти через Google").assertIsNotEnabled()
    }

    @Test
    fun authStateChangesKeepOneMediaPlayerRoot() {
        AuthMediaTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme {
                GoogleAuthRoute(
                    debugState = AuthDebugState.Ready,
                    reducedMotionOverride = false,
                    onAuthenticated = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, AuthMediaTestProbe.createdPlayerCount) }
        composeRule.onNodeWithContentDescription("Войти через Google").performClick()
        composeRule.onNodeWithText("Выбери аккаунт Google").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, AuthMediaTestProbe.createdPlayerCount) }
    }
}
