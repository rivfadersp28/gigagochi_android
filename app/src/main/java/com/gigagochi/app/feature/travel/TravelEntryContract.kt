package com.gigagochi.app.feature.travel

const val TravelDestinationMaxLength = 500
const val TravelStartFailureMessage = "Не получилось начать путешествие. Попробуй ещё раз."
const val TravelSuggestionsFailureMessage =
    "Не получилось обновить варианты. Можно выбрать любой из этих."
const val TravelCustomDestination = "В город великанов"

val DeterministicTravelDestinations = listOf("В горы", "В океан", "На Луну")
val DeterministicTravelFallbackDestinations = listOf("в подземелье", "на болото", "в лес")

data class TravelEntryPet(
    val petId: String,
    val name: String,
    val experience: Int = 0,
)

enum class TravelEntryPhase {
    LoadingSuggestions,
    Picker,
    CustomDestination,
    StartPending,
    StoryQuestion,
    ChoicePending,
    StoryResult,
    Finished,
}

enum class TravelStartOrigin {
    Picker,
    CustomDestination,
}

enum class TravelExitReason { UserDismissed, Completed }

data class PendingTravelStart(
    val petId: String,
    val requestKey: String,
    val destination: String,
    val origin: TravelStartOrigin,
)

data class TravelEntryState(
    val pet: TravelEntryPet,
    val phase: TravelEntryPhase = TravelEntryPhase.LoadingSuggestions,
    val suggestions: List<String> = emptyList(),
    val activeSuggestionsRequestKey: String? = null,
    val customDraft: String = "",
    val activeStart: PendingTravelStart? = null,
    val story: InteractiveTravelStory? = null,
    val activeChoice: PendingTravelStoryChoice? = null,
    val storyResult: InteractiveTravelStoryResult? = null,
    val appliedStoryTravelIds: Set<String> = emptySet(),
    val error: String? = null,
    val exitRequested: Boolean = false,
    val exitReason: TravelExitReason? = null,
)

sealed interface TravelEntryEvent {
    data class LoadSuggestions(val requestKey: String) : TravelEntryEvent
    data class SuggestionsLoaded(
        val requestKey: String,
        val suggestions: List<String>,
    ) : TravelEntryEvent
    data class SuggestionsFailed(val requestKey: String) : TravelEntryEvent
    data object OpenCustomDestination : TravelEntryEvent
    data class UpdateCustomDraft(val value: String) : TravelEntryEvent
    data class SelectSuggestion(
        val destination: String,
        val requestKey: String,
    ) : TravelEntryEvent
    data class SubmitCustomDestination(val requestKey: String) : TravelEntryEvent
    data class StartAccepted(
        val requestKey: String,
        val story: InteractiveTravelStory,
    ) : TravelEntryEvent
    data class StartFailed(val requestKey: String) : TravelEntryEvent
    data class SubmitStoryChoice(
        val choice: String,
        val requestKey: String,
    ) : TravelEntryEvent
    data class StoryChoiceResolved(
        val requestKey: String,
        val result: InteractiveTravelStoryResult,
        val committedExperience: Int? = null,
        val committedTravelIds: Set<String> = emptySet(),
    ) : TravelEntryEvent
    data class StoryChoiceFailed(val requestKey: String) : TravelEntryEvent
    data object ResultConsumptionFailed : TravelEntryEvent
    data object FinishStory : TravelEntryEvent
    data object Back : TravelEntryEvent
}

fun initialTravelEntryState(
    pet: TravelEntryPet,
    requestKey: String = "suggestions-initial",
): TravelEntryState = TravelEntryState(
    pet = pet,
    activeSuggestionsRequestKey = requestKey,
)

fun reduceTravelEntry(
    state: TravelEntryState,
    event: TravelEntryEvent,
): TravelEntryState = when (event) {
    is TravelEntryEvent.LoadSuggestions -> if (
        state.activeSuggestionsRequestKey != null || state.activeStart != null
    ) {
        state
    } else {
        state.copy(
            phase = TravelEntryPhase.LoadingSuggestions,
            activeSuggestionsRequestKey = event.requestKey,
            error = null,
        )
    }

    is TravelEntryEvent.SuggestionsLoaded -> if (
        state.activeSuggestionsRequestKey == event.requestKey
    ) {
        val normalized = normalizedSuggestionsOrNull(event.suggestions)
        state.copy(
            phase = TravelEntryPhase.Picker,
            suggestions = normalized ?: DeterministicTravelFallbackDestinations,
            activeSuggestionsRequestKey = null,
            error = if (normalized == null) TravelSuggestionsFailureMessage else null,
        )
    } else {
        state
    }

    is TravelEntryEvent.SuggestionsFailed -> if (
        state.activeSuggestionsRequestKey == event.requestKey
    ) {
        // The web entry screen falls back to its deterministic local suggestions.
        state.copy(
            phase = TravelEntryPhase.Picker,
            suggestions = DeterministicTravelFallbackDestinations,
            activeSuggestionsRequestKey = null,
            error = TravelSuggestionsFailureMessage,
        )
    } else {
        state
    }

    TravelEntryEvent.OpenCustomDestination -> if (
        state.phase == TravelEntryPhase.Picker && state.activeStart == null
    ) {
        state.copy(
            phase = TravelEntryPhase.CustomDestination,
            error = null,
        )
    } else {
        state
    }

    is TravelEntryEvent.UpdateCustomDraft -> if (
        state.phase == TravelEntryPhase.CustomDestination && state.activeStart == null
    ) {
        state.copy(
            customDraft = event.value.take(TravelDestinationMaxLength),
            error = null,
        )
    } else {
        state
    }

    is TravelEntryEvent.SelectSuggestion -> startTravel(
        state = state,
        destination = event.destination,
        requestKey = event.requestKey,
        origin = TravelStartOrigin.Picker,
        expectedPhase = TravelEntryPhase.Picker,
    )

    is TravelEntryEvent.SubmitCustomDestination -> startTravel(
        state = state,
        destination = state.customDraft,
        requestKey = event.requestKey,
        origin = TravelStartOrigin.CustomDestination,
        expectedPhase = TravelEntryPhase.CustomDestination,
    )

    is TravelEntryEvent.StartAccepted -> if (
        state.activeStart?.requestKey == event.requestKey &&
        event.story.travelId.isNotBlank()
    ) {
        state.copy(
            phase = TravelEntryPhase.StoryQuestion,
            activeStart = null,
            story = event.story,
            activeChoice = null,
            storyResult = null,
            error = null,
        )
    } else {
        state
    }

    is TravelEntryEvent.StartFailed -> if (state.activeStart?.requestKey == event.requestKey) {
        state.copy(
            phase = when (state.activeStart.origin) {
                TravelStartOrigin.Picker -> TravelEntryPhase.Picker
                TravelStartOrigin.CustomDestination -> TravelEntryPhase.CustomDestination
            },
            activeStart = null,
            error = TravelStartFailureMessage,
        )
    } else {
        state
    }

    is TravelEntryEvent.SubmitStoryChoice -> {
        val story = state.story
        val choice = event.choice.trim()
        if (
            state.phase != TravelEntryPhase.StoryQuestion ||
            state.activeChoice != null ||
            story == null ||
            (story.enabledChoice.isNotEmpty() && choice != story.enabledChoice) ||
            choice !in story.choices
        ) {
            state
        } else {
            state.copy(
                phase = TravelEntryPhase.ChoicePending,
                activeChoice = PendingTravelStoryChoice(
                    travelId = story.travelId,
                    requestKey = event.requestKey,
                    choice = choice,
                ),
                error = null,
            )
        }
    }

    is TravelEntryEvent.StoryChoiceResolved -> if (
        state.phase == TravelEntryPhase.ChoicePending &&
        state.activeChoice?.requestKey == event.requestKey &&
        state.activeChoice.travelId == event.result.travelId &&
        event.result.requestKey == event.requestKey &&
        event.result.answer == state.activeChoice.choice &&
        state.story != null &&
        (state.story?.enabledChoice.isNullOrEmpty() ||
            state.story.enabledChoice == state.activeChoice.choice) &&
        event.result.experienceGained >= 0 &&
        (!isOnboardingBatStory(state.story) ||
            event.result.experienceGained == OnboardingBatExperienceGain)
    ) {
        val alreadyApplied = event.result.travelId in state.appliedStoryTravelIds
        state.copy(
            pet = if (event.committedExperience != null) {
                state.pet.copy(experience = event.committedExperience)
            } else if (alreadyApplied) {
                state.pet
            } else {
                state.pet.copy(
                    experience = state.pet.experience + event.result.experienceGained,
                )
            },
            phase = TravelEntryPhase.StoryResult,
            activeChoice = null,
            storyResult = event.result,
            appliedStoryTravelIds = state.appliedStoryTravelIds +
                event.committedTravelIds + event.result.travelId,
            error = null,
        )
    } else {
        state
    }

    is TravelEntryEvent.StoryChoiceFailed -> if (
        state.phase == TravelEntryPhase.ChoicePending &&
        state.activeChoice?.requestKey == event.requestKey
    ) {
        state.copy(
            phase = TravelEntryPhase.StoryQuestion,
            activeChoice = null,
            error = TravelStoryChoiceFailureMessage,
        )
    } else {
        state
    }

    TravelEntryEvent.ResultConsumptionFailed -> if (state.phase == TravelEntryPhase.StoryResult) {
        state.copy(error = "Не получилось сохранить продолжение. Попробуй ещё раз.")
    } else state

    TravelEntryEvent.FinishStory -> if (state.phase == TravelEntryPhase.StoryResult) {
        state.copy(
            phase = TravelEntryPhase.Finished,
            activeChoice = null,
            exitRequested = true,
            exitReason = TravelExitReason.Completed,
        )
    } else {
        state
    }

    TravelEntryEvent.Back -> when (state.phase) {
        TravelEntryPhase.CustomDestination -> state.copy(
            phase = TravelEntryPhase.Picker,
            error = null,
        )
        TravelEntryPhase.StartPending -> state.copy(
            phase = when (state.activeStart?.origin) {
                TravelStartOrigin.CustomDestination -> TravelEntryPhase.CustomDestination
                else -> TravelEntryPhase.Picker
            },
            activeStart = null,
            error = null,
        )
        TravelEntryPhase.LoadingSuggestions,
        TravelEntryPhase.Picker,
        -> state.copy(exitRequested = true, exitReason = TravelExitReason.UserDismissed)
        TravelEntryPhase.StoryQuestion,
        TravelEntryPhase.ChoicePending,
        TravelEntryPhase.StoryResult,
        TravelEntryPhase.Finished,
        -> state.copy(
            phase = TravelEntryPhase.Finished,
            activeChoice = null,
            exitRequested = true,
            exitReason = TravelExitReason.UserDismissed,
        )
    }
}

private fun startTravel(
    state: TravelEntryState,
    destination: String,
    requestKey: String,
    origin: TravelStartOrigin,
    expectedPhase: TravelEntryPhase,
): TravelEntryState {
    val normalized = destination.trim().take(TravelDestinationMaxLength)
    if (
        state.phase != expectedPhase ||
        state.activeStart != null ||
        normalized.isEmpty()
    ) {
        return state
    }
    return state.copy(
        phase = TravelEntryPhase.StartPending,
        activeStart = PendingTravelStart(
            petId = state.pet.petId,
            requestKey = requestKey,
            destination = normalized,
            origin = origin,
        ),
        error = null,
    )
}

private fun normalizedSuggestionsOrNull(values: List<String>): List<String>? {
    val normalized = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(3)
    return normalized.takeIf { it.size == 3 }
}
