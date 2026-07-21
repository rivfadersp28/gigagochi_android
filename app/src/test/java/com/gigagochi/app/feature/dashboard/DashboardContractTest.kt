package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterFirstFood
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedy
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedyPortions
import com.gigagochi.app.feature.onboarding.FirstSessionMainAction
import com.gigagochi.app.feature.onboarding.firstSessionMainAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardContractTest {
    @Test
    fun dashboardActionsUsePaperHorizontalInsets() {
        assertEquals(12.dp, DashboardActionStartPadding)
        assertEquals(16.dp, DashboardActionEndPadding)
    }

    @Test
    fun onlyNestedDashboardModesExposeContextualClose() {
        assertNull(contextualNavigationForDashboardMode(DashboardMode.Idle))
        listOf(
            DashboardMode.Chat,
            DashboardMode.Feed,
            DashboardMode.Outfit,
            DashboardMode.Travel,
        ).forEach { mode ->
            assertEquals(
                ContextualNavigationAction.Back,
                contextualNavigationForDashboardMode(mode),
            )
        }
    }

    @Test
    fun visibleOutfitActionOpensEvenIfOnboardingStateChangesBeforeDispatch() {
        val staleState = DashboardUiState(
            pet = pet(),
            firstSession = LocalFirstSession(
                ownerId = "owner-a",
                petId = "pet-instance-17",
                stage = FirstSessionStage.AwaitingTravel,
                updatedAtEpochMillis = 2,
            ),
        )

        val opened = reduceDashboard(staleState, DashboardEvent.OpenOutfit)

        assertEquals(DashboardMode.Outfit, opened.mode)
    }

    private fun pet(
        experience: Int = 0,
        hunger: Int = 50,
        happiness: Int = 100,
        energy: Int = 50,
        petTapProgress: Int = 0,
    ) = PetDashboardState(
        petId = "pet-instance-17",
        assetSetId = "media-asset-set-99",
        description = "Ледяной дракон",
        name = "Без имени",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = experience,
        hunger = hunger,
        happiness = happiness,
        energy = energy,
        message = "Как тебя зовут?",
        petTapProgress = petTapProgress,
    )

    @Test
    fun reviewFixturesFreezeReplyAdvanceWithoutChangingProductionIdle() {
        assertFalse(DashboardDebugState.Idle.freezesReplyAdvance)
        assertTrue(DashboardDebugState.OutfitPromptIme.freezesReplyAdvance)
        assertTrue(DashboardDebugState.TravelIme.freezesReplyAdvance)
    }

    @Test
    fun restoredPetIsTheInitialDashboardSnapshot() {
        val restored = pet(hunger = 37, experience = 412)

        assertSame(restored, dashboardDebugFixture(DashboardDebugState.Idle, restored).pet)
    }

    @Test
    fun modeAndBackReducerRestoreIdleWithoutMutatingPet() {
        val originalPet = pet()
        val chat = reduceDashboard(DashboardUiState(originalPet), DashboardEvent.OpenChat)
        val closed = reduceDashboard(
            chat.copy(chatDraft = "черновик", chatError = ChatFailureMessage),
            DashboardEvent.CloseMode,
        )

        assertEquals(DashboardMode.Chat, chat.mode)
        assertEquals(DashboardMode.Idle, closed.mode)
        assertEquals("черновик", closed.chatDraft)
        assertNull(closed.chatError)
        assertSame(originalPet, closed.pet)
    }

    @Test
    fun chatTrimsCapsRejectsDuplicateRestoresDraftAndIgnoresLateCompletion() {
        var state = reduceDashboard(DashboardUiState(pet()), DashboardEvent.OpenChat)
        state = reduceDashboard(
            state,
            DashboardEvent.UpdateChatDraft("  hello  " + "x".repeat(DashboardPromptMaxLength)),
        )
        assertEquals(DashboardPromptMaxLength, state.chatDraft.length)

        state = reduceDashboard(state, DashboardEvent.SubmitChat("chat-1"))
        val firstRequest = state.activeChat
        val duplicate = reduceDashboard(state, DashboardEvent.SubmitChat("chat-2"))
        assertSame(firstRequest, duplicate.activeChat)

        state = reduceDashboard(state, DashboardEvent.ChatFailed("chat-1"))
        assertTrue(state.chatDraft.startsWith("hello"))
        assertEquals(ChatFailureMessage, state.chatError)

        state = reduceDashboard(state, DashboardEvent.SubmitChat("chat-3"))
        state = reduceDashboard(state, DashboardEvent.CloseMode)
        val late = reduceDashboard(
            state,
            DashboardEvent.ChatSucceeded(
                "chat-3",
                DashboardChatResult(DeterministicChatReply, state.pet),
            ),
        )
        assertEquals(DashboardMode.Idle, late.mode)
        assertNull(late.chatReply)
        assertEquals(0L, remainingThinkingDelayMillis(0, DashboardMinimumThinkingMillis))
        assertEquals(750L, remainingThinkingDelayMillis(0, 250))
    }

    @Test
    fun chatSuccessPreservesExplicitSemanticPortions() {
        var state = reduceDashboard(DashboardUiState(pet()), DashboardEvent.OpenChat)
        state = reduceDashboard(state, DashboardEvent.UpdateChatDraft("Ничем"))
        state = reduceDashboard(state, DashboardEvent.SubmitChat("chat-portions"))

        state = reduceDashboard(
            state,
            DashboardEvent.ChatSucceeded(
                "chat-portions",
                DashboardChatResult(
                    reply = "Звучит здорово! $FirstSessionAfterChat",
                    pet = state.pet,
                    explicitPortions = listOf("Звучит здорово!", FirstSessionAfterChat),
                ),
            ),
        )

        assertEquals(
            listOf("Звучит здорово!", FirstSessionAfterChat),
            state.chatReply?.portions,
        )
    }

    @Test
    fun feedAppliesStatFirstClampsCyclesAudioAndKeepsEffectOnFailure() {
        var state = reduceDashboard(DashboardUiState(pet(hunger = 90, energy = 80)), DashboardEvent.OpenFeed)
        state = reduceDashboard(state, DashboardEvent.TapFood(DashboardFood.BerryBowl, "feed-1"))
        assertEquals(100, state.pet.hunger)
        assertEquals(0, state.activeFeed?.audioIndex)
        assertEquals(FoodTokenPhase.Consuming, state.feedToken.phase)

        state = reduceDashboard(state, DashboardEvent.FeedFailed("feed-1"))
        assertEquals(100, state.pet.hunger)
        assertEquals(FeedFailureMessage, state.feedError)
        state = reduceDashboard(state, DashboardEvent.FoodConsumeFinished(DashboardFood.BerryBowl))
        assertEquals(FoodTokenPhase.Reappearing, state.feedToken.phase)
        state = reduceDashboard(state, DashboardEvent.FoodReappearFinished(DashboardFood.BerryBowl))
        assertEquals(FoodTokenPhase.Idle, state.feedToken.phase)

        state = reduceDashboard(state, DashboardEvent.TapFood(DashboardFood.LeafCrunch, "feed-2"))
        assertEquals(100, state.pet.energy)
        assertEquals(1, state.activeFeed?.audioIndex)
    }

    @Test
    fun petTapRewardsEveryFifthTapAndKeepsThanksOptional() {
        var state = DashboardUiState(pet(happiness = 70))
        repeat(PetTapsPerHappinessReward - 1) {
            state = reduceDashboard(state, DashboardEvent.PetTapped())
            assertEquals(70, state.pet.happiness)
            assertEquals("Как тебя зовут?", state.pet.message)
        }

        state = reduceDashboard(
            state,
            DashboardEvent.PetTapped("Приятно!", replyRequestKey = "pet-tap-1"),
        )
        assertEquals(85, state.pet.happiness)
        assertEquals(0, state.pet.petTapProgress)
        assertEquals("Как тебя зовут?", state.pet.message)
        assertEquals("Приятно!", state.transientReply?.text)

        state = reduceDashboard(state, DashboardEvent.ClearReply("pet-tap-1"))
        assertNull(state.transientReply)

        repeat(PetTapsPerHappinessReward) {
            state = reduceDashboard(state, DashboardEvent.PetTapped())
        }
        assertEquals(100, state.pet.happiness)
        assertEquals("Как тебя зовут?", state.pet.message)
    }

    @Test
    fun outfitQueuesForFreeAndAcceptsExactlyOnce() {
        var state = reduceDashboard(DashboardUiState(pet(experience = 0)), DashboardEvent.OpenOutfit)
        state = reduceDashboard(state, DashboardEvent.UpdateOutfitDraft("  В футболку Metallica  "))
        state = reduceDashboard(state, DashboardEvent.SubmitOutfit("outfit-1"))
        val failed = reduceDashboard(state, DashboardEvent.OutfitFailed("outfit-1"))
        assertEquals(0, failed.pet.experience)
        assertEquals("  В футболку Metallica  ", failed.outfitDraft)

        state = reduceDashboard(failed, DashboardEvent.SubmitOutfit("outfit-2"))
        val pending = PendingOutfitGeneration(
            petId = state.pet.petId,
            requestKey = "outfit-2",
            prompt = "В футболку Metallica",
            displayItem = "футболка Metallica",
            localJobId = "fake-job",
            createdAtEpochMillis = 7,
        )
        state = reduceDashboard(
            state,
            DashboardEvent.OutfitQueued(
                "outfit-2",
                pending,
                state.pet,
                DeterministicOutfitReply,
            ),
        )
        assertEquals(0, state.pet.experience)
        assertEquals(setOf("outfit-2"), state.chargedOutfitRequestKeys)
        assertEquals(DeterministicOutfitReply, state.transientReply?.text)

        val duplicate = reduceDashboard(
            state,
            DashboardEvent.OutfitQueued(
                "outfit-2",
                pending,
                state.pet,
                DeterministicOutfitReply,
            ),
        )
        assertEquals(0, duplicate.pet.experience)
        assertEquals(setOf("outfit-2"), duplicate.chargedOutfitRequestKeys)
    }

    @Test
    fun restoredPendingBlocksSecondOutfitAndTravelSubmission() {
        val original = pet(experience = 300)
        val outfitPending = PendingOutfitGeneration(
            petId = original.petId,
            requestKey = "outfit-existing",
            prompt = "Наряд",
            displayItem = "наряд",
            localJobId = "local-outfit",
            createdAtEpochMillis = 1,
        )
        var outfitState = DashboardUiState(
            pet = original,
            mode = DashboardMode.Outfit,
            outfitDraft = "Ещё наряд",
            pendingOutfit = outfitPending,
            chargedOutfitRequestKeys = setOf(outfitPending.requestKey),
        )
        outfitState = reduceDashboard(outfitState, DashboardEvent.SubmitOutfit("new-request"))
        assertNull(outfitState.activeOutfit)
        assertEquals(300, outfitState.pet.experience)

        val travelPending = PendingTravelGeneration(
            petId = original.petId,
            requestKey = "travel-existing",
            prompt = "Луна",
            localJobId = "local-travel",
            createdAtEpochMillis = 1,
        )
        var travelState = DashboardUiState(
            pet = original,
            mode = DashboardMode.Travel,
            travelDraft = "Марс",
            pendingTravel = travelPending,
            queuedTravelRequestKeys = setOf(travelPending.requestKey),
        )
        travelState = reduceDashboard(travelState, DashboardEvent.SubmitTravel("new-request"))
        assertNull(travelState.activeTravel)
    }

    @Test
    fun fakeOutfitPendingUsesPetIdNotAssetSetId() = runBlocking {
        val pet = pet(experience = 200)
        val pending = FakeDashboardOutfitAdapter(
            adapterDelayMillis = 0,
            nowEpochMillis = { 123L },
        ).queue(
            request = PendingOutfitRequest("outfit-pet-id", "В футболку Metallica"),
            pet = pet,
        )

        assertEquals("pet-instance-17", pending.petId)
        assertTrue(pending.petId != pet.assetSetId)
        assertEquals("media-asset-set-99", pet.assetSetId)
    }

    @Test
    fun outfitReplyFitsInOneFourLinePortion() {
        assertEquals(
            listOf(DeterministicOutfitReply),
            splitDashboardReplyPortions(DeterministicOutfitReply),
        )

        val state = DashboardUiState(
            pet = pet(),
            transientReply = DashboardReply("outfit-advance", DeterministicOutfitReply),
        )
        assertEquals(DeterministicOutfitReply, state.transientReply?.visibleText)
        assertFalse(state.transientReply?.hasNextPortion ?: true)
    }

    @Test
    fun longDialogueIsSplitIntoPortionsThatTargetFourLines() {
        val reference = "Я тут подумал, а где ты живешь? Можешь рассказать, мне очень интересно?"
        assertEquals(
            listOf(reference),
            splitDashboardReplyPortions(reference),
        )

        val longSentence = List(24) { "любопытная" }.joinToString(" ") + "!"
        val portions = splitDashboardReplyPortions(longSentence)
        assertTrue(portions.size > 1)
        assertEquals(longSentence, portions.joinToString(" "))

        val onboardingPortions = splitDashboardReplyPortions(FirstSessionAfterRemedy)
        assertTrue(onboardingPortions.size > 1)
        assertEquals(FirstSessionAfterRemedy, onboardingPortions.joinToString(" "))
    }

    @Test
    fun onboardingRemedyReplyKeepsTwoSemanticBlocksAndLongerPause() {
        val request = PendingFeedRequest("remedy-feed", DashboardFood.LeafCrunch, 0)
        val initial = DashboardUiState(
            pet = pet(),
            mode = DashboardMode.Feed,
            activeFeed = request,
        )

        val result = reduceDashboard(
            initial,
            DashboardEvent.FeedSucceeded(
                requestKey = request.requestKey,
                reply = FirstSessionAfterRemedy,
                explicitPortions = FirstSessionAfterRemedyPortions,
                autoAdvanceDelayMillis = OnboardingBlockAutoAdvanceMillis,
            ),
        )

        assertEquals(FirstSessionAfterRemedyPortions, result.feedReply?.portions)
        assertEquals(OnboardingBlockAutoAdvanceMillis, result.feedReply?.autoAdvanceDelayMillis)
    }

    @Test
    fun completingFirstOnboardingChatKeepsChatOpenForFollowup() {
        val reply = DashboardReply("chat-first", "Приятно познакомиться")
        val state = DashboardUiState(
            pet = pet(),
            firstSession = LocalFirstSession(
                "owner-a",
                "pet-instance-17",
                FirstSessionStage.AwaitingChatFollowup,
                updatedAtEpochMillis = 2,
            ),
            mode = DashboardMode.Chat,
            chatReply = reply,
        )

        val completed = reduceDashboard(state, DashboardEvent.CompleteReply(reply.requestKey))

        assertEquals(DashboardMode.Chat, completed.mode)
        assertNull(completed.chatReply)
        assertFalse(isFirstSessionReplyPending(completed))
        assertEquals(reply.visibleText, completed.settledFirstSessionReply?.visibleText)
    }

    @Test
    fun completingSecondOnboardingChatReturnsToFeedAction() {
        val reply = DashboardReply("chat-second", "Может, у тебя что-нибудь завалялось?")
        val state = DashboardUiState(
            pet = pet(),
            firstSession = LocalFirstSession(
                "owner-a",
                "pet-instance-17",
                FirstSessionStage.AwaitingFirstFood,
                updatedAtEpochMillis = 3,
            ),
            mode = DashboardMode.Chat,
            chatReply = reply,
        )

        val completed = reduceDashboard(state, DashboardEvent.CompleteReply(reply.requestKey))

        assertEquals(DashboardMode.Idle, completed.mode)
        assertNull(completed.chatReply)
        assertEquals(FirstSessionMainAction.Feed, firstSessionMainAction(completed.firstSession))
        assertEquals(reply.visibleText, dashboardIdleMessage(completed))
    }

    @Test
    fun completedOnboardingInstructionDoesNotFallBackToDuplicatePetMessage() {
        val session = LocalFirstSession(
            "owner-a",
            "pet-instance-17",
            FirstSessionStage.AwaitingChat,
            updatedAtEpochMillis = 1,
        )
        val speaking = DashboardUiState(
            pet = pet().copy(message = "Как тебя зовут?"),
            firstSession = session,
        )
        val introduction = checkNotNull(dashboardIdleMessage(speaking))
        check(introduction.startsWith("Привет"))
        check(introduction.endsWith("Как тебя зовут?"))

        val finished = reduceDashboard(
            speaking,
            DashboardEvent.CompleteReply(checkNotNull(speaking.firstSessionIdleReply).requestKey),
        )

        assertEquals(introduction, dashboardIdleMessage(finished))
        val onboardingCompleted = reduceDashboard(
            finished,
            DashboardEvent.FirstSessionSynced(
                session.copy(stage = FirstSessionStage.Completed),
                finished.pet,
            ),
        )
        assertEquals(
            "Как тебя зовут?",
            dashboardIdleMessage(onboardingCompleted),
        )
    }

    @Test
    fun onboardingFoodWaitsForReplyThenRemedyReturnsToTravelAction() {
        val firstReply = DashboardReply("feed-berry", FirstSessionAfterFirstFood)
        val remedySession = LocalFirstSession(
            "owner-a",
            "pet-instance-17",
            FirstSessionStage.AwaitingRemedy,
            updatedAtEpochMillis = 4,
        )
        val replying = DashboardUiState(
            pet = pet(),
            firstSession = remedySession,
            mode = DashboardMode.Feed,
            feedReply = firstReply,
        )

        val blocked = reduceDashboard(
            replying,
            DashboardEvent.TapFood(DashboardFood.LeafCrunch, "too-early"),
        )
        assertNull(blocked.activeFeed)

        val ready = reduceDashboard(
            replying,
            DashboardEvent.CompleteReply(firstReply.requestKey),
        )
        assertEquals(DashboardMode.Feed, ready.mode)
        assertNull(ready.feedReply)
        val accepted = reduceDashboard(
            ready,
            DashboardEvent.TapFood(DashboardFood.LeafCrunch, "remedy"),
        )
        check(accepted.activeFeed != null)

        val remedyReply = DashboardReply("feed-remedy", FirstSessionAfterRemedy)
        val travelReady = reduceDashboard(
            ready.copy(
                firstSession = remedySession.copy(stage = FirstSessionStage.AwaitingTravel),
                feedReply = remedyReply,
            ),
            DashboardEvent.CompleteReply(remedyReply.requestKey),
        )
        assertEquals(DashboardMode.Idle, travelReady.mode)
        assertNull(travelReady.feedReply)
        assertEquals(FirstSessionMainAction.Travel, firstSessionMainAction(travelReady.firstSession))
        assertEquals(remedyReply.visibleText, dashboardIdleMessage(travelReady))
    }

    @Test
    fun travelTrimsCapsRejectsDuplicateRetainsDraftAndQueuesWithoutCharge() {
        var state = reduceDashboard(DashboardUiState(pet(experience = 200)), DashboardEvent.OpenTravel)
        state = reduceDashboard(
            state,
            DashboardEvent.UpdateTravelDraft("  $DeterministicTravelPrompt  " + "x".repeat(DashboardPromptMaxLength)),
        )
        assertEquals(DashboardPromptMaxLength, state.travelDraft.length)

        state = reduceDashboard(state, DashboardEvent.SubmitTravel("travel-1"))
        val firstRequest = state.activeTravel
        val duplicateSubmit = reduceDashboard(state, DashboardEvent.SubmitTravel("travel-2"))
        assertSame(firstRequest, duplicateSubmit.activeTravel)

        state = reduceDashboard(state, DashboardEvent.TravelFailed("travel-1"))
        assertTrue(state.travelDraft.startsWith("  $DeterministicTravelPrompt"))
        assertEquals(TravelFailureMessage, state.travelError)

        state = reduceDashboard(state, DashboardEvent.SubmitTravel("travel-3"))
        val pending = PendingTravelGeneration(
            petId = state.pet.petId,
            requestKey = "travel-3",
            prompt = state.activeTravel!!.prompt,
            localJobId = "local-fake-travel-3",
            createdAtEpochMillis = 7L,
        )
        state = reduceDashboard(
            state,
            DashboardEvent.TravelQueued("travel-3", pending, travelQueuedReply(pending.prompt)),
        )

        assertEquals(DashboardMode.Idle, state.mode)
        assertEquals("", state.travelDraft)
        assertEquals(200, state.pet.experience)
        assertEquals(pending, state.pendingTravel)
        assertEquals(setOf("travel-3"), state.queuedTravelRequestKeys)
        assertEquals("${pending.prompt}? Надеюсь, со мной всё будет в порядке. Пришлю видео, когда вернусь", state.transientReply?.text)

        val duplicateQueue = reduceDashboard(
            state,
            DashboardEvent.TravelQueued("travel-3", pending, DeterministicTravelReply),
        )
        assertSame(state, duplicateQueue)
    }

    @Test
    fun travelBackIgnoresLateCompletionAndRejectsWrongPetPending() {
        var state = reduceDashboard(DashboardUiState(pet()), DashboardEvent.OpenTravel)
        state = reduceDashboard(state, DashboardEvent.UpdateTravelDraft(DeterministicTravelPrompt))
        state = reduceDashboard(state, DashboardEvent.SubmitTravel("travel-late"))

        val wrongPetPending = PendingTravelGeneration(
            petId = state.pet.assetSetId,
            requestKey = "travel-late",
            prompt = DeterministicTravelPrompt,
            localJobId = "wrong-pet-job",
            createdAtEpochMillis = 1L,
        )
        val rejected = reduceDashboard(
            state,
            DashboardEvent.TravelQueued("travel-late", wrongPetPending, DeterministicTravelReply),
        )
        assertSame(state, rejected)

        state = reduceDashboard(state, DashboardEvent.CloseMode)
        val late = reduceDashboard(
            state,
            DashboardEvent.TravelQueued("travel-late", wrongPetPending.copy(petId = state.pet.petId), DeterministicTravelReply),
        )
        assertEquals(DashboardMode.Idle, late.mode)
        assertNull(late.pendingTravel)
        assertNull(late.transientReply)
    }

    @Test
    fun fakeTravelQueueIsIdempotentAndUsesPetIdNotAssetSetId() = runBlocking {
        val pet = pet()
        var clockCalls = 0
        val adapter = FakeDashboardTravelAdapter(
            adapterDelayMillis = 0,
            nowEpochMillis = { clockCalls += 1; 123L },
        )
        val request = PendingTravelRequest("travel-pet-id", DeterministicTravelPrompt)

        val first = adapter.queue(request, pet)
        val duplicate = adapter.queue(request, pet)

        assertSame(first, duplicate)
        assertEquals(1, clockCalls)
        assertEquals("pet-instance-17", first.petId)
        assertTrue(first.petId != pet.assetSetId)
        assertEquals("media-asset-set-99", pet.assetSetId)
        assertEquals("local-fake-travel-travel-pet-id", first.localJobId)
    }

    @Test
    fun travelReplyFitsInOneThreeLinePortion() {
        assertEquals(
            listOf(DeterministicTravelReply),
            splitDashboardReplyPortions(DeterministicTravelReply),
        )

        val state = DashboardUiState(
            pet = pet(),
            transientReply = DashboardReply("travel-advance", DeterministicTravelReply),
        )
        assertEquals(DeterministicTravelReply, state.transientReply?.visibleText)
        assertFalse(state.transientReply?.hasNextPortion ?: true)
    }

    @Test
    fun queuedRepliesCapitalizeUserTextAndOmitTerminalPeriod() {
        assertEquals(
            "В столицу сша? Надеюсь, со мной всё будет в порядке. Пришлю видео, когда вернусь",
            travelQueuedReply("  в столицу сша...  "),
        )
        assertEquals(
            "Футболка iron maiden? Интересно. Я получу заказ примерно через 10 минут",
            outfitQueuedReply("футболка iron maiden"),
        )
    }

    @Test
    fun characterReplyCapitalizesEverySentenceForPresentation() {
        val reply = DashboardReply(
            requestKey = "lowercase-reply",
            text = "первая фраза. вторая фраза? «третья фраза!»",
        )

        assertEquals(
            "Первая фраза. Вторая фраза? «Третья фраза!»",
            reply.visibleText,
        )
    }
}
