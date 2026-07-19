package com.gigagochi.app.feature.travel

import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.FirstSessionStore
import kotlinx.coroutines.delay

interface TravelSuggestionsAdapter {
    suspend fun load(pet: TravelEntryPet, requestKey: String): List<String>
}

interface TravelStartAdapter {
    /**
     * Hands the request to a future backend adapter. Stage 4C deliberately supplies only a
     * recoverable fake failure and never fabricates a server-created travel session.
     */
    suspend fun start(request: PendingTravelStart): TravelStartOutcome
}

sealed interface TravelStartOutcome {
    data class Accepted(val story: InteractiveTravelStory) : TravelStartOutcome
}

interface TravelStoryChoiceAdapter {
    suspend fun choose(
        request: PendingTravelStoryChoice,
        story: InteractiveTravelStory,
    ): TravelStoryChoiceOutcome
}

interface TravelResultConsumptionAdapter {
    suspend fun consume(result: InteractiveTravelStoryResult): Boolean
}

data class TravelStoryChoiceOutcome(
    val result: InteractiveTravelStoryResult,
    val committedExperience: Int? = null,
    val committedTravelIds: Set<String> = emptySet(),
)

class FakeTravelSuggestionsAdapter(
    private val delayMillis: Long = 450L,
) : TravelSuggestionsAdapter {
    override suspend fun load(pet: TravelEntryPet, requestKey: String): List<String> {
        delay(delayMillis)
        return DeterministicTravelDestinations
    }
}

class TravelStartUnavailableException : IllegalStateException("Travel backend is not configured")

class FakeTravelStartAdapter(
    private val delayMillis: Long = 900L,
) : TravelStartAdapter {
    override suspend fun start(request: PendingTravelStart): TravelStartOutcome {
        delay(delayMillis)
        throw TravelStartUnavailableException()
    }
}

class FakeOnboardingTravelStoryAdapter(
    private val delayMillis: Long = 320L,
) : TravelStoryChoiceAdapter {
    override suspend fun choose(
        request: PendingTravelStoryChoice,
        story: InteractiveTravelStory,
    ): TravelStoryChoiceOutcome {
        delay(delayMillis)
        check(request.travelId == story.travelId)
        check(request.choice == story.enabledChoice)
        return TravelStoryChoiceOutcome(onboardingBatStoryResult(story, request.requestKey))
    }
}

object ImmediateTravelResultConsumptionAdapter : TravelResultConsumptionAdapter {
    override suspend fun consume(result: InteractiveTravelStoryResult): Boolean = true
}

class DurableOnboardingBatChoiceAdapter(
    private val ownerId: String,
    private val petId: String,
    private val store: FirstSessionStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : TravelStoryChoiceAdapter {
    override suspend fun choose(
        request: PendingTravelStoryChoice,
        story: InteractiveTravelStory,
    ): TravelStoryChoiceOutcome {
        check(isOnboardingBatStory(story))
        check(request.choice == OnboardingBatCorrectChoice)
        val mutation = store.commitFirstSessionBatChoice(
            ownerId, petId, request.requestKey, nowEpochMillis(),
        )
        val pet = when (mutation) {
            is FirstSessionMutationResult.Applied -> mutation.pet
            is FirstSessionMutationResult.AlreadyApplied -> mutation.pet
            else -> error("First-session bat receipt was not committed")
        }
        return TravelStoryChoiceOutcome(
            result = onboardingBatStoryResult(story, request.requestKey),
            committedExperience = pet.experience,
            committedTravelIds = setOf(story.travelId),
        )
    }
}

class DurableOnboardingBatFinishAdapter(
    private val ownerId: String,
    private val petId: String,
    private val store: FirstSessionStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : TravelResultConsumptionAdapter {
    override suspend fun consume(result: InteractiveTravelStoryResult): Boolean =
        store.finishFirstSessionBat(
            ownerId,
            petId,
            "finish-${result.travelId}".take(160),
            nowEpochMillis(),
        ) is FirstSessionMutationResult.Applied ||
            store.getFirstSession(ownerId, petId)?.stage ==
            com.gigagochi.app.core.database.FirstSessionStage.AwaitingCompletionMessage
}
