package com.gigagochi.app.feature.dashboard

import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import androidx.core.content.res.ResourcesCompat
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.database.FirstSessionStore
import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedy
import com.gigagochi.app.feature.onboarding.firstSessionDashboardMessagePortions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.roundToInt

class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shortSafeViewportKeepsReferenceChromeAnchoredToTop() {
        val density = InstrumentationRegistry.getInstrumentation().targetContext
            .resources.displayMetrics.density
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    initialPet = testPet(hunger = 100).copy(experience = 50),
                    requestImeOverride = false,
                    reducedMotionOverride = true,
                    modifier = Modifier.requiredSize(393.dp, 780.dp),
                )
            }
        }

        val rootTop = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.top
        val experienceTop = composeRule.onNodeWithContentDescription("Experience: 50")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(experienceTop - rootTop >= 84f * density)
    }

    @Test
    fun onboardingRestoresFollowupPromptAndExactlyOneMainAction() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    requestImeOverride = false,
                    reducedMotionOverride = true,
                    initialFirstSession = LocalFirstSession(
                        "owner-a",
                        "debug-test-pet",
                        FirstSessionStage.AwaitingChatFollowup,
                        updatedAtEpochMillis = 1,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("А чем ты любишь заниматься?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Поболтать").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Покормить").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("В путешествие").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Нарядить").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Experience: 0").assertDoesNotExist()
        val rootCenter = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        val chatCenter = composeRule.onNodeWithContentDescription("Поболтать")
            .fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(rootCenter, chatCenter, 1f)
    }

    @Test
    fun onboardingChatActionOpensFromTouchInput() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    requestImeOverride = false,
                    initialFirstSession = LocalFirstSession(
                        "owner-a",
                        "debug-test-pet",
                        FirstSessionStage.AwaitingChat,
                        updatedAtEpochMillis = 1,
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Поболтать")
            .assertIsDisplayed()
            .performTouchInput { click() }

        composeRule.onNodeWithContentDescription("Сообщение персонажу").assertIsDisplayed()
    }

    @Test
    fun wideOnboardingTravelActionIsCenteredWithoutScrollOffset() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    requestImeOverride = false,
                    reducedMotionOverride = true,
                    initialFirstSession = LocalFirstSession(
                        "owner-a",
                        "debug-test-pet",
                        FirstSessionStage.AwaitingTravel,
                        updatedAtEpochMillis = 1,
                    ),
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(OnboardingBlockAutoAdvanceMillis + 100)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val rootCenter = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x
        val actionBounds = composeRule.onNodeWithContentDescription("Помочь летучей мыши")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val labelBounds = composeRule.onNodeWithText(
            "Помочь летучей мыши",
            useUnmergedTree = true,
        )
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(rootCenter, actionBounds.center.x, 1f)
        assertTrue(actionBounds.width <= 346f)
        assertEquals(20f, labelBounds.left - actionBounds.left, 1f)
        assertEquals(20f, actionBounds.right - labelBounds.right, 1f)
    }

    @Test
    fun onboardingOutfitActionOpensPromptFromTouchInput() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    requestImeOverride = false,
                    initialFirstSession = LocalFirstSession(
                        "owner-a",
                        "debug-test-pet",
                        FirstSessionStage.AwaitingCompletionMessage,
                        updatedAtEpochMillis = 1,
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Нарядить")
            .assertIsDisplayed()
            .performTouchInput { click() }

        composeRule.onNodeWithContentDescription("Описание наряда").assertIsDisplayed()
    }

    @Test
    fun chatSuccessSurvivesParentFirstSessionHydration() {
        val pet = testPet(hunger = 40)
        val initial = LocalFirstSession(
            "owner-a", pet.petId, FirstSessionStage.AwaitingChat, updatedAtEpochMillis = 1,
        )
        val nextSession = initial.copy(stage = FirstSessionStage.AwaitingChatFollowup, updatedAtEpochMillis = 2)
        val store = object : FirstSessionStore {
            override suspend fun advanceFirstSession(
                ownerId: String,
                petId: String,
                expected: FirstSessionStage,
                next: FirstSessionStage,
                actionKey: String,
                selectedDestination: String?,
                nowEpochMillis: Long,
            ) = FirstSessionMutationResult.Applied(nextSession, pet)
        }
        composeRule.setContent {
            var externalSession by mutableStateOf(initial)
            GigagochiTheme {
                DashboardRoute(
                    initialPet = pet,
                    initialFirstSession = externalSession,
                    firstSessionOwnerId = "owner-a",
                    firstSessionStore = store,
                    chatAdapter = object : DashboardChatAdapter {
                        override suspend fun reply(request: PendingChatRequest, pet: PetDashboardState) =
                            DashboardChatResult("Очень приятно! Как твои дела?", pet)
                    },
                    requestKeyFactory = { "chat-1" },
                    requestImeOverride = false,
                    onFirstSessionChanged = { externalSession = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Поболтать").performClick()
        composeRule.onNodeWithContentDescription("Сообщение персонажу").performTextInput("Сергей")
        composeRule.onNodeWithContentDescription("Отправить").performClick()
        composeRule.waitUntil(12_000) {
            composeRule.onAllNodesWithText("А чем ты любишь заниматься?", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Сообщение персонажу").assertIsDisplayed()
        composeRule.onNodeWithText("А чем ты любишь заниматься?")
            .assertIsDisplayed()
    }

    @Test
    fun berrySuccessSurvivesParentFirstSessionHydration() {
        val pet = testPet(hunger = 40)
        val initial = LocalFirstSession(
            "owner-a", pet.petId, FirstSessionStage.AwaitingFirstFood, updatedAtEpochMillis = 1,
        )
        val nextSession = initial.copy(stage = FirstSessionStage.AwaitingRemedy, updatedAtEpochMillis = 2)
        val authoritativePet = pet.copy(hunger = 65)
        val store = object : FirstSessionStore {
            override suspend fun applyFirstSessionFood(
                ownerId: String,
                petId: String,
                food: String,
                actionKey: String,
                nowEpochMillis: Long,
            ) = FirstSessionMutationResult.Applied(nextSession, authoritativePet)
        }
        composeRule.setContent {
            var externalSession by mutableStateOf(initial)
            GigagochiTheme {
                DashboardRoute(
                    initialPet = pet,
                    initialFirstSession = externalSession,
                    firstSessionOwnerId = "owner-a",
                    firstSessionStore = store,
                    requestKeyFactory = { "berry-1" },
                    requestImeOverride = false,
                    onFirstSessionChanged = { externalSession = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Покормить")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Ягодная миска").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Хм, вкусно!", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Еда").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Хм, вкусно! Но что-то я себя неважно чувствую. Может, у тебя есть какое-нибудь снадобье?",
        ).assertIsDisplayed()
    }

    @Test
    fun restoredOnboardingCopyRevealsActionOnlyAfterFinalPortion() {
        composeRule.mainClock.autoAdvance = false
        val pet = testPet(hunger = 65)
        val session = LocalFirstSession(
            "owner-a", pet.petId, FirstSessionStage.AwaitingTravel, updatedAtEpochMillis = 1,
        )
        val portions = firstSessionDashboardMessagePortions(pet, session)
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    initialPet = pet,
                    initialFirstSession = session,
                    reducedMotionOverride = false,
                    requestImeOverride = false,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Помочь летучей мыши").assertDoesNotExist()
        composeRule.onNodeWithText(portions.first()).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(
            checkNotNull(firstSessionIdleReply(pet, session)).autoAdvanceDelayMillis + 100,
        )
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(portions.last()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Помочь летучей мыши").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(2_700)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Помочь летучей мыши").assertExists()

        composeRule.mainClock.advanceTimeBy(350)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Помочь летучей мыши").assertIsDisplayed()
        composeRule.onNodeWithText(portions.last()).assertIsDisplayed()
    }

    @Test
    fun externalPetRefreshDoesNotRestartCompletionMessage() {
        composeRule.mainClock.autoAdvance = false
        val pet = testPet(hunger = 65)
        val session = LocalFirstSession(
            "owner-a",
            pet.petId,
            FirstSessionStage.AwaitingCompletionMessage,
            updatedAtEpochMillis = 1,
        )
        val portions = firstSessionDashboardMessagePortions(pet, session)
        var externalPet by mutableStateOf(pet)
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(
                    initialPet = externalPet,
                    initialFirstSession = session,
                    requestImeOverride = false,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(portions.first()).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(DashboardReplyAutoAdvanceMillis + 100)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(portions[1]).assertIsDisplayed()

        composeRule.runOnIdle { externalPet = pet.copy(experience = 200) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(portions[1]).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(DashboardReplyAutoAdvanceMillis + 100)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(portions.last()).assertIsDisplayed()
    }

    @Test
    fun everyRestoredOnboardingPortionFitsFourDialogueLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pet = testPet(hunger = 65)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f * context.resources.displayMetrics.scaledDensity
            typeface = checkNotNull(ResourcesCompat.getFont(context, R.font.sb_sans_display_bold))
        }
        val widthPx = (356f * context.resources.displayMetrics.density).roundToInt()

        FirstSessionStage.entries.filter { it != FirstSessionStage.Completed }.forEach { stage ->
            val session = LocalFirstSession("owner-a", pet.petId, stage, updatedAtEpochMillis = 1)
            firstSessionDashboardMessagePortions(pet, session).forEach { portion ->
                val layout = StaticLayout.Builder.obtain(portion, 0, portion.length, paint, widthPx)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                assertTrue(
                    "$stage onboarding portion uses ${layout.lineCount} lines: $portion",
                    layout.lineCount <= 4,
                )
            }
        }
    }

    @Test
    fun automaticallySplitRepliesTargetAtMostFourDialogueLines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f * context.resources.displayMetrics.scaledDensity
            typeface = checkNotNull(ResourcesCompat.getFont(context, R.font.sb_sans_display_bold))
        }
        val widthPx = (356f * context.resources.displayMetrics.density).roundToInt()

        splitDashboardReplyPortions(FirstSessionAfterRemedy).forEach { portion ->
            val layout = StaticLayout.Builder.obtain(portion, 0, portion.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            assertTrue(
                "automatic portion uses ${layout.lineCount} lines: $portion",
                layout.lineCount <= 4,
            )
        }
    }

    private fun testPet(hunger: Int) = PetDashboardState(
        "fixture-pet", "fixture", "Ледяной дракон", "Листик", "baby", "Малыш", "idle",
        0, hunger, 100, 100, "Как тебя зовут?",
    )

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
    fun eventsActionShowsUnansweredBadgeAndDispatchesNavigation() {
        var eventClicks = 0
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
                    ),
                    onChat = {},
                    onFeed = {},
                    onTravel = {},
                    onPetTap = {},
                    onEvents = { eventClicks += 1 },
                    unansweredEventCount = 3,
                )
            }
        }

        composeRule.onNodeWithContentDescription("События, без ответа: 3")
            .performClick()
        composeRule.runOnIdle { assertEquals(1, eventClicks) }
    }

    @Test
    fun hazeContractKeepsApi23ScrimFallbackAndExactDesignValues() {
        val packagedMinSdk = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.minSdkVersion

        assertEquals(23, packagedMinSdk)
        assertEquals(23, DashboardGlassContract.MinimumSupportedSdk)
        assertEquals(31, DashboardGlassContract.NativeBlurMinimumSdk)
        assertEquals(12.dp, DashboardGlassContract.ActionStyle.blurRadius)
        assertEquals(12.dp, DashboardGlassContract.ActionBlurStyle.blurRadius)
        assertEquals(8.dp, DashboardGlassContract.ExperienceStyle.blurRadius)
        assertEquals(0f, DashboardGlassContract.ActionStyle.noiseFactor, 0f)
        assertEquals(0f, DashboardGlassContract.ActionBlurStyle.noiseFactor, 0f)
        assertEquals(0f, DashboardGlassContract.ExperienceStyle.noiseFactor, 0f)
        assertEquals(Color.White.copy(alpha = .15f), DashboardGlassContract.ActionTint)
        assertEquals(Color(0x29845C05), DashboardGlassContract.ExperienceTint)
        assertEquals(DashboardGlassContract.ActionTint, DashboardGlassContract.ActionStyle.tints.single().color)
        assertEquals(DashboardGlassContract.ActionTint, DashboardGlassContract.ActionStyle.fallbackTint.color)
        assertTrue(DashboardGlassContract.ActionBlurStyle.tints.isEmpty())
        assertEquals(Color.Transparent, DashboardGlassContract.ActionBlurStyle.fallbackTint.color)
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
    fun visibleBackMatchesSystemBackAndKeepsIdleWithoutTopNavigation() {
        composeRule.setContent {
            GigagochiTheme { DashboardRoute(requestImeOverride = false) }
        }

        composeRule.onNodeWithContentDescription("Назад").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Поболтать").performClick()
        composeRule.onNodeWithContentDescription("Назад")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithContentDescription("Назад").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Поболтать").assertIsDisplayed()

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
    fun petTapHasAccessibleTargetAndShowsReaction() {
        composeRule.setContent {
            GigagochiTheme { DashboardRoute(reducedMotionOverride = true) }
        }

        repeat(PetTapsPerHappinessReward) {
            composeRule.onNodeWithContentDescription("Погладить Без имени").performClick()
        }
        composeRule.waitForIdle()
        assertTrue(
            PetTapThanksReplies.any { reply ->
                composeRule.onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty()
            },
        )
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
        composeRule.mainClock.advanceTimeBy(DashboardMinimumThinkingMillis)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(BerryReply).assertIsDisplayed()
    }

    @Test
    fun slowMouseFeedDragSurvivesRecomposition() {
        composeRule.setContent {
            GigagochiTheme {
                DashboardRoute(feedAdapter = FakeDashboardFeedAdapter(adapterDelayMillis = 0))
            }
        }

        composeRule.onNodeWithContentDescription("Покормить").performClick()
        val food = composeRule.onNodeWithContentDescription("Ягодная миска")
        food.performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(12f, -40f))
        }
        composeRule.waitForIdle()
        food.performMouseInput {
            moveBy(Offset(56f, -212f))
            release()
        }
        composeRule.mainClock.advanceTimeBy(DashboardMinimumThinkingMillis)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
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
