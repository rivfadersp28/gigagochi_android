package com.gigagochi.app.feature.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.model.PetDashboardState
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardShowsFixtureAndDispatchesChatAction() {
        var chatClicks = 0
        composeRule.setContent {
            GigagochiTheme {
                DashboardScreen(
                    state = PetDashboardState(
                        petId = "fixture-pet",
                        assetSetId = "fixture",
                        description = "Ледяной дракон",
                        name = "Без имени",
                        stage = "baby",
                        stageLabel = "Малыш",
                        mood = "idle",
                        experience = 0,
                        hunger = 100,
                        happiness = 100,
                        energy = 100,
                        message = "Как тебя зовут?",
                        firstSessionActive = false,
                    ),
                    onChat = { chatClicks += 1 },
                    onFeed = {},
                    onTravel = {},
                    onPetTap = {},
                )
            }
        }

        composeRule.onNodeWithText("Как тебя зовут?").assertIsDisplayed()
        composeRule.onNodeWithText("Уровень: Малыш").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hunger: 100 из 100").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mood: 100 из 100").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Energy: 100 из 100").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Experience: 0").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Поболтать").performClick()
        composeRule.runOnIdle { check(chatClicks == 1) }
    }

    @Test
    fun hazeContractKeepsApi23ScrimFallbackAndExactDesignValues() {
        val packagedMinSdk = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.minSdkVersion

        assertEquals(23, packagedMinSdk)
        assertEquals(23, DashboardGlassContract.MinimumSupportedSdk)
        assertEquals(31, DashboardGlassContract.NativeBlurMinimumSdk)
        assertEquals(12.dp, DashboardGlassContract.ActionStyle.blurRadius)
        assertEquals(8.dp, DashboardGlassContract.ExperienceStyle.blurRadius)
        assertEquals(0f, DashboardGlassContract.ActionStyle.noiseFactor, 0f)
        assertEquals(0f, DashboardGlassContract.ExperienceStyle.noiseFactor, 0f)
        assertEquals(Color.White.copy(alpha = .15f), DashboardGlassContract.ActionTint)
        assertEquals(Color(0x29845C05), DashboardGlassContract.ExperienceTint)
        assertEquals(DashboardGlassContract.ActionTint, DashboardGlassContract.ActionStyle.tints.single().color)
        assertEquals(DashboardGlassContract.ActionTint, DashboardGlassContract.ActionStyle.fallbackTint.color)
        assertEquals(DashboardGlassContract.ExperienceTint, DashboardGlassContract.ExperienceStyle.tints.single().color)
        assertEquals(DashboardGlassContract.ExperienceTint, DashboardGlassContract.ExperienceStyle.fallbackTint.color)
        assertEquals(3.dp, DashboardGlassContract.ActionHighlightInset.radius)
        assertEquals(1.dp, DashboardGlassContract.ActionHighlightInset.offset.x)
        assertEquals(2.dp, DashboardGlassContract.ActionHighlightInset.offset.y)
        assertEquals(2.dp, DashboardGlassContract.ActionShadeInset.radius)
        assertEquals((-4).dp, DashboardGlassContract.ActionShadeInset.offset.x)
        assertEquals((-4).dp, DashboardGlassContract.ActionShadeInset.offset.y)
        assertEquals(Color.White.copy(alpha = .2f), DashboardGlassContract.ActionHighlightInset.color)
        assertEquals(Color.Black.copy(alpha = .2f), DashboardGlassContract.ActionShadeInset.color)
    }

    @Test
    fun inlineModeSwitchKeepsTheSameVideoPlayerRoot() {
        DashboardVideoTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme { DashboardRoute(requestImeOverride = false) }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, DashboardVideoTestProbe.createdPlayerCount) }
        composeRule.onNodeWithContentDescription("Поболтать").performClick()
        composeRule.onNodeWithText("Расскажи о себе").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, DashboardVideoTestProbe.createdPlayerCount) }
        pressBack()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onNodeWithContentDescription("Поболтать").isDisplayed()
        }
        composeRule.onNodeWithContentDescription("Поболтать").assertExists()
        composeRule.runOnIdle { assertEquals(1, DashboardVideoTestProbe.createdPlayerCount) }
        composeRule.onNodeWithContentDescription("В путешествие")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(TravelPrompt).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Место путешествия").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, DashboardVideoTestProbe.createdPlayerCount) }
        pressBack()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(TravelPrompt).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("В путешествие")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, DashboardVideoTestProbe.createdPlayerCount) }
    }

    @Test
    fun visibleCloseMatchesSystemBackAndKeepsIdleWithoutTopNavigation() {
        composeRule.setContent {
            GigagochiTheme { DashboardRoute(requestImeOverride = false) }
        }

        composeRule.onNodeWithContentDescription("Закрыть").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Поболтать").performClick()
        composeRule.onNodeWithContentDescription("Закрыть")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Закрыть").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Поболтать").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Покормить").performClick()
        composeRule.onNodeWithContentDescription("Закрыть").assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithContentDescription("Закрыть").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Покормить").assertIsDisplayed()
    }

    @Test
    fun reducedMotionUsesPosterAndDoesNotCreateLoopingPlayer() {
        DashboardVideoTestProbe.reset()
        composeRule.setContent {
            GigagochiTheme { DashboardRoute(reducedMotionOverride = true) }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(0, DashboardVideoTestProbe.createdPlayerCount) }
        composeRule.onNodeWithText("Как тебя зовут?").assertIsDisplayed()
    }

    @Test
    fun feedDragConsumesAndShowsDeterministicReply() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(feedAdapter = FakeDashboardFeedAdapter(adapterDelayMillis = 0))
            }
        }

        composeRule.onNodeWithContentDescription("Покормить").performClick()
        composeRule.onNodeWithContentDescription("Ягодная миска").performTouchInput {
            swipe(start = center, end = Offset(center.x + 68f, center.y - 252f), durationMillis = 320)
        }
        composeRule.waitUntil(timeoutMillis = 4_000) {
            composeRule.onAllNodesWithText(BerryReply).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(BerryReply).assertIsDisplayed()
    }

    @Test
    fun acceptedOutfitQueueShowsFirstReplyPortion() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    debugState = DashboardDebugState.OutfitPromptIme,
                    outfitAdapter = FakeDashboardOutfitAdapter(adapterDelayMillis = 0),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Создать наряд за 200 монет").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Футболка Metallica?", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Футболка Metallica?", substring = true).assertIsDisplayed()
    }

    @Test
    fun acceptedTravelQueueReturnsToIdleAndShowsFirstReplyPortion() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    debugState = DashboardDebugState.TravelIme,
                    travelAdapter = FakeDashboardTravelAdapter(adapterDelayMillis = 0),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Место путешествия").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Отправить").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("На ночной рынок духов?", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("На ночной рынок духов?", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(TravelPrompt).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("В путешествие")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
