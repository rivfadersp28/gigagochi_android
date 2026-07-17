package com.gigagochi.app.feature.travel

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelEntryContractTest {
    private val pet = TravelEntryPet(
        petId = "pet-instance-17",
        name = "Без имени",
    )

    @Test
    fun suggestionsFailureUsesExactFallbackOptionsAndVisibleError() {
        val loading = initialTravelEntryState(pet, "suggestions-1")
        val failed = reduceTravelEntry(
            loading,
            TravelEntryEvent.SuggestionsFailed("suggestions-1"),
        )

        assertEquals(TravelEntryPhase.Picker, failed.phase)
        assertEquals(DeterministicTravelFallbackDestinations, failed.suggestions)
        assertEquals(TravelSuggestionsFailureMessage, failed.error)
        assertNull(failed.activeSuggestionsRequestKey)
    }

    @Test
    fun staleSuggestionsCompletionIsIgnoredAndInvalidPayloadFallsBack() {
        val loading = initialTravelEntryState(pet, "suggestions-current")
        val stale = reduceTravelEntry(
            loading,
            TravelEntryEvent.SuggestionsLoaded("suggestions-old", listOf("Старый вариант")),
        )
        assertSame(loading, stale)

        val invalid = reduceTravelEntry(
            loading,
            TravelEntryEvent.SuggestionsLoaded("suggestions-current", listOf("Один", "Один")),
        )
        assertEquals(DeterministicTravelFallbackDestinations, invalid.suggestions)
        assertEquals(TravelSuggestionsFailureMessage, invalid.error)

        val valid = reduceTravelEntry(
            loading,
            TravelEntryEvent.SuggestionsLoaded(
                "suggestions-current",
                listOf("В горы", "В океан", "На Луну"),
            ),
        )
        assertEquals(DeterministicTravelDestinations, valid.suggestions)
        assertNull(valid.error)
    }

    @Test
    fun customInputCapsAt500TrimsSubmissionAndRejectsDuplicate() {
        var state = TravelEntryState(
            pet = pet,
            phase = TravelEntryPhase.Picker,
            suggestions = DeterministicTravelDestinations,
        )
        state = reduceTravelEntry(state, TravelEntryEvent.OpenCustomDestination)
        state = reduceTravelEntry(
            state,
            TravelEntryEvent.UpdateCustomDraft("  В город великанов  " + "x".repeat(600)),
        )
        assertEquals(TravelDestinationMaxLength, state.customDraft.length)

        state = reduceTravelEntry(state, TravelEntryEvent.SubmitCustomDestination("start-1"))
        assertEquals(TravelEntryPhase.StartPending, state.phase)
        assertEquals("pet-instance-17", state.activeStart?.petId)
        assertTrue(state.activeStart?.destination?.startsWith("В город великанов") == true)
        val firstRequest = state.activeStart

        val duplicate = reduceTravelEntry(
            state,
            TravelEntryEvent.SubmitCustomDestination("start-2"),
        )
        assertSame(firstRequest, duplicate.activeStart)
    }

    @Test
    fun failedStartRestoresOriginAndDraftAndIgnoresLateFailure() {
        var state = TravelEntryState(
            pet = pet,
            phase = TravelEntryPhase.CustomDestination,
            suggestions = DeterministicTravelDestinations,
            customDraft = TravelCustomDestination,
        )
        state = reduceTravelEntry(state, TravelEntryEvent.SubmitCustomDestination("start-1"))
        val stale = reduceTravelEntry(state, TravelEntryEvent.StartFailed("start-old"))
        assertSame(state.activeStart, stale.activeStart)

        val failed = reduceTravelEntry(state, TravelEntryEvent.StartFailed("start-1"))
        assertEquals(TravelEntryPhase.CustomDestination, failed.phase)
        assertEquals(TravelCustomDestination, failed.customDraft)
        assertEquals(TravelStartFailureMessage, failed.error)
        assertNull(failed.activeStart)
    }

    @Test
    fun backReturnsCustomToPickerThenRequestsDashboardExit() {
        val picker = TravelEntryState(
            pet = pet,
            phase = TravelEntryPhase.Picker,
            suggestions = DeterministicTravelDestinations,
        )
        val custom = reduceTravelEntry(picker, TravelEntryEvent.OpenCustomDestination)
        val returned = reduceTravelEntry(
            custom.copy(customDraft = TravelCustomDestination, error = TravelStartFailureMessage),
            TravelEntryEvent.Back,
        )
        assertEquals(TravelEntryPhase.Picker, returned.phase)
        assertEquals(TravelCustomDestination, returned.customDraft)
        assertNull(returned.error)
        assertTrue(!returned.exitRequested)

        val exiting = reduceTravelEntry(returned, TravelEntryEvent.Back)
        assertTrue(exiting.exitRequested)
    }

    @Test
    fun backCancelsPendingAndLateCompletionCannotMutateState() {
        var state = TravelEntryState(
            pet = pet,
            phase = TravelEntryPhase.CustomDestination,
            suggestions = DeterministicTravelDestinations,
            customDraft = TravelCustomDestination,
        )
        state = reduceTravelEntry(state, TravelEntryEvent.SubmitCustomDestination("start-1"))
        state = reduceTravelEntry(state, TravelEntryEvent.Back)
        assertEquals(TravelEntryPhase.CustomDestination, state.phase)
        assertNull(state.activeStart)

        val late = reduceTravelEntry(state, TravelEntryEvent.StartFailed("start-1"))
        assertSame(state, late)
    }

    @Test
    fun onboardingFixtureKeepsExactContentAndOnlyCorrectChoiceEnabled() {
        val story = onboardingBatStory(pet.petId)
        val result = onboardingBatStoryResult(story, "choice-1")

        assertEquals(OnboardingBatStoryTitle, story.title)
        assertEquals(OnboardingBatChoices, story.choices)
        assertEquals(OnboardingBatCorrectChoice, story.enabledChoice)
        assertEquals(
            "К какой группе относится летучая мышь?",
            story.challenge,
        )
        assertEquals(
            "Летучая мышь относится к млекопитающим. Мама добралась до малыша, " +
                "согрела его и накормила молоком.",
            result.text,
        )
        assertEquals("Получилось! Малыш снова рядом с мамой.", result.reaction)
        assertEquals("Малыш в безопасности рядом с мамой.", result.consequence)
        assertEquals(OnboardingBatExperienceGain, result.experienceGained)
    }

    @Test
    fun acceptedStartEntersStoryAndStaleAcceptanceIsIgnored() {
        val story = onboardingBatStory(pet.petId)
        val pending = reduceTravelEntry(
            TravelEntryState(
                pet = pet,
                phase = TravelEntryPhase.Picker,
                suggestions = DeterministicTravelDestinations,
            ),
            TravelEntryEvent.SelectSuggestion("В горы", "start-1"),
        )

        val stale = reduceTravelEntry(
            pending,
            TravelEntryEvent.StartAccepted("start-old", story),
        )
        assertSame(pending, stale)

        val accepted = reduceTravelEntry(
            pending,
            TravelEntryEvent.StartAccepted("start-1", story),
        )
        assertEquals(TravelEntryPhase.StoryQuestion, accepted.phase)
        assertEquals(story, accepted.story)
        assertNull(accepted.activeStart)
    }

    @Test
    fun storyChoiceRejectsWrongAndDuplicateSubmissions() {
        val question = onboardingQuestionState()
        val wrong = reduceTravelEntry(
            question,
            TravelEntryEvent.SubmitStoryChoice("Птицы", "choice-wrong"),
        )
        assertSame(question, wrong)

        val pending = reduceTravelEntry(
            question,
            TravelEntryEvent.SubmitStoryChoice(OnboardingBatCorrectChoice, "choice-1"),
        )
        assertEquals(TravelEntryPhase.ChoicePending, pending.phase)
        assertEquals(OnboardingBatCorrectChoice, pending.activeChoice?.choice)

        val duplicate = reduceTravelEntry(
            pending,
            TravelEntryEvent.SubmitStoryChoice(OnboardingBatCorrectChoice, "choice-2"),
        )
        assertSame(pending, duplicate)
    }

    @Test
    fun validStoryResultAppliesExactly200OnceByTravelId() {
        val pending = onboardingChoicePendingState()
        val story = requireNotNull(pending.story)
        val result = onboardingBatStoryResult(story, "choice-1")
        val resolved = reduceTravelEntry(
            pending,
            TravelEntryEvent.StoryChoiceResolved("choice-1", result),
        )

        assertEquals(TravelEntryPhase.StoryResult, resolved.phase)
        assertEquals(200, resolved.pet.experience)
        assertTrue(story.travelId in resolved.appliedStoryTravelIds)

        val replayPending = resolved.copy(
            phase = TravelEntryPhase.ChoicePending,
            activeChoice = PendingTravelStoryChoice(
                travelId = story.travelId,
                requestKey = "choice-2",
                choice = OnboardingBatCorrectChoice,
            ),
            storyResult = null,
        )
        val replayResult = result.copy(requestKey = "choice-2")
        val replayed = reduceTravelEntry(
            replayPending,
            TravelEntryEvent.StoryChoiceResolved("choice-2", replayResult),
        )
        assertEquals(200, replayed.pet.experience)
        assertEquals(TravelEntryPhase.StoryResult, replayed.phase)
    }

    @Test
    fun committedStoryResultUsesRoomExperienceAndReceiptSetWithoutAddingDeltaAgain() {
        val pending = onboardingChoicePendingState().copy(
            pet = pet.copy(experience = 100),
        )
        val story = requireNotNull(pending.story)
        val result = onboardingBatStoryResult(story, "choice-1")
        val resolved = reduceTravelEntry(
            pending,
            TravelEntryEvent.StoryChoiceResolved(
                requestKey = "choice-1",
                result = result,
                committedExperience = 777,
                committedTravelIds = setOf("previous-travel", story.travelId),
            ),
        )

        assertEquals(777, resolved.pet.experience)
        assertEquals(setOf("previous-travel", story.travelId), resolved.appliedStoryTravelIds)
    }

    @Test
    fun invalidStoryResultsCannotMutateExperienceOrResolveChoice() {
        val pending = onboardingChoicePendingState()
        val story = requireNotNull(pending.story)
        val valid = onboardingBatStoryResult(story, "choice-1")
        val invalidResults = listOf(
            valid.copy(answer = "Птицы"),
            valid.copy(experienceGained = -1),
            valid.copy(experienceGained = 199),
            valid.copy(travelId = "stale-travel"),
            valid.copy(requestKey = "stale-request"),
        )

        invalidResults.forEach { invalid ->
            val after = reduceTravelEntry(
                pending,
                TravelEntryEvent.StoryChoiceResolved("choice-1", invalid),
            )
            assertSame(pending, after)
            assertEquals(0, after.pet.experience)
            assertEquals(TravelEntryPhase.ChoicePending, after.phase)
        }
    }

    @Test
    fun genericStoryBoundaryAcceptsNonNegativeExperience() {
        val story = InteractiveTravelStory(
            travelId = "server-story-1",
            title = "История",
            storyText = "Сюжет",
            challenge = "Выбор?",
            choices = listOf("Ответ"),
            enabledChoice = "Ответ",
        )
        val pending = TravelEntryState(
            pet = pet,
            phase = TravelEntryPhase.ChoicePending,
            story = story,
            activeChoice = PendingTravelStoryChoice(
                travelId = story.travelId,
                requestKey = "choice-1",
                choice = "Ответ",
            ),
        )
        val result = InteractiveTravelStoryResult(
            travelId = story.travelId,
            requestKey = "choice-1",
            answer = "Ответ",
            text = "Результат",
            reaction = "Реакция",
            consequence = "Последствие",
            experienceGained = 17,
        )

        val resolved = reduceTravelEntry(
            pending,
            TravelEntryEvent.StoryChoiceResolved("choice-1", result),
        )
        assertEquals(17, resolved.pet.experience)
        assertEquals(TravelEntryPhase.StoryResult, resolved.phase)
    }

    @Test
    fun storyBackAndFinishExitWithoutRestoringPicker() {
        listOf(
            onboardingQuestionState(),
            onboardingChoicePendingState(),
            onboardingResultState(),
        ).forEach { storyState ->
            val exited = reduceTravelEntry(storyState, TravelEntryEvent.Back)
            assertEquals(TravelEntryPhase.Finished, exited.phase)
            assertTrue(exited.exitRequested)
            assertFalse(exited.phase == TravelEntryPhase.Picker)
        }

        val finished = reduceTravelEntry(onboardingResultState(), TravelEntryEvent.FinishStory)
        assertEquals(TravelEntryPhase.Finished, finished.phase)
        assertTrue(finished.exitRequested)
    }

    @Test
    fun localStoryAdapterReturnsTheExactKeyedOnboardingResult() = runBlocking {
        val story = onboardingBatStory(pet.petId)
        val request = PendingTravelStoryChoice(
            travelId = story.travelId,
            requestKey = "choice-adapter-1",
            choice = OnboardingBatCorrectChoice,
        )

        val result = FakeOnboardingTravelStoryAdapter(delayMillis = 0).choose(request, story).result

        assertEquals(story.travelId, result.travelId)
        assertEquals(request.requestKey, result.requestKey)
        assertEquals(request.choice, result.answer)
        assertEquals(OnboardingBatExperienceGain, result.experienceGained)
    }

    private fun onboardingQuestionState(): TravelEntryState = TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.StoryQuestion,
        story = onboardingBatStory(pet.petId),
    )

    private fun onboardingChoicePendingState(): TravelEntryState {
        val question = onboardingQuestionState()
        return reduceTravelEntry(
            question,
            TravelEntryEvent.SubmitStoryChoice(OnboardingBatCorrectChoice, "choice-1"),
        )
    }

    private fun onboardingResultState(): TravelEntryState {
        val pending = onboardingChoicePendingState()
        val result = onboardingBatStoryResult(requireNotNull(pending.story), "choice-1")
        return reduceTravelEntry(
            pending,
            TravelEntryEvent.StoryChoiceResolved("choice-1", result),
        )
    }
}
