package com.gigagochi.app.feature.travel

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.ScheduledStoryChoiceClaim
import com.gigagochi.app.core.database.ScheduledStoryStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.DueStoryRequestDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.toFeaturePetDto
import kotlinx.coroutines.CancellationException

sealed interface ScheduledStoryDueResult {
    data object NotDue : ScheduledStoryDueResult
    data object Pending : ScheduledStoryDueResult
    data class Saved(val story: ScheduledStory) : ScheduledStoryDueResult
    data class Failure(val failure: FeatureFailure) : ScheduledStoryDueResult
}

sealed interface ScheduledStoryChoiceResult {
    data class Saved(
        val story: ScheduledStory,
        val requestKey: String,
        val committedExperience: Int,
        val committedTravelIds: Set<String>,
    ) : ScheduledStoryChoiceResult
    data class Failure(val failure: FeatureFailure) : ScheduledStoryChoiceResult
}

class ScheduledStoryCoordinator(
    private val ownerId: String,
    private val store: ScheduledStoryStore,
    private val api: AndroidFeatureService,
    private val receiptCoordinator: StoryReceiptCoordinator? = null,
) {
    suspend fun checkDue(pet: PetDashboardState): ScheduledStoryDueResult = protectDue {
        when (val response = api.dueStory(DueStoryRequestDto(pet.toFeaturePetDto()))) {
            is FeatureApiResult.Failure -> ScheduledStoryDueResult.Failure(response.failure)
            is FeatureApiResult.Success -> {
                val dto = response.value.story ?: return@protectDue if (response.value.pending) {
                    ScheduledStoryDueResult.Pending
                } else {
                    ScheduledStoryDueResult.NotDue
                }
                val story = api.story(dto)
                    ?.takeIf { it.petId == pet.petId }
                    ?: return@protectDue protocolDueFailure()
                if (!store.saveScheduledStory(LocalScheduledStory(ownerId, story))) {
                    return@protectDue protocolDueFailure()
                }
                ScheduledStoryDueResult.Saved(story)
            }
        }
    }

    suspend fun choose(
        storyId: String,
        choice: String,
        proposedRequestKey: String,
    ): ScheduledStoryChoiceResult = protectChoice {
        when (val claim = store.claimScheduledStoryChoice(
            ownerId,
            storyId,
            proposedRequestKey,
            choice,
        )) {
            is ScheduledStoryChoiceClaim.Completed -> commitReceipt(
                claim.story,
                claim.requestKey,
                choice,
            )
            ScheduledStoryChoiceClaim.Conflict,
            ScheduledStoryChoiceClaim.Missing,
            -> protocolChoiceFailure()
            is ScheduledStoryChoiceClaim.Claimed -> submitChoice(storyId, claim.requestKey, claim.choice)
            is ScheduledStoryChoiceClaim.Existing -> submitChoice(storyId, claim.requestKey, claim.choice)
        }
    }

    suspend fun reconcileSelected(storyId: String): ScheduledStoryChoiceResult = protectChoice {
        val local = store.getScheduledStory(ownerId, storyId)
            ?: return@protectChoice protocolChoiceFailure()
        val choice = local.story.selectedChoice ?: return@protectChoice protocolChoiceFailure()
        val requestKey = local.choiceRequestKey ?: return@protectChoice protocolChoiceFailure()
        commitReceipt(local.story, requestKey, choice)
    }

    private suspend fun submitChoice(
        storyId: String,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceResult = when (
        val response = api.chooseStory(
            storyId,
            ScheduledStoryChoiceRequestDto(requestKey, choice),
        )
    ) {
        is FeatureApiResult.Failure -> ScheduledStoryChoiceResult.Failure(response.failure)
        is FeatureApiResult.Success -> {
            val story = api.story(response.value)
            if (story == null || story.storyId != storyId || story.selectedChoice != choice ||
                !store.saveScheduledStory(
                    LocalScheduledStory(ownerId, story, requestKey, pendingChoice = null),
                )
            ) {
                protocolChoiceFailure()
            } else commitReceipt(story, requestKey, choice)
        }
    }

    private suspend fun commitReceipt(
        story: ScheduledStory,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceResult {
        val result = story.result ?: return protocolChoiceFailure()
        val receipt = receiptCoordinator ?: return ScheduledStoryChoiceResult.Failure(
            FeatureFailure(FeatureFailureKind.Storage),
        )
        val interactiveResult = InteractiveTravelStoryResult(
            travelId = story.storyId,
            requestKey = requestKey,
            answer = choice,
            text = result.text,
            reaction = result.reaction,
            consequence = result.consequence,
            experienceGained = result.experienceGained,
            imageUrl = story.resultImageUrl,
            videoUrl = story.resultVideoUrl,
        )
        return when (
            val committed = receipt.commit(
                PendingTravelStoryChoice(story.storyId, requestKey, choice),
                interactiveResult,
            )
        ) {
            StoryReceiptCommitResult.Failure -> ScheduledStoryChoiceResult.Failure(
                FeatureFailure(FeatureFailureKind.Storage),
            )
            is StoryReceiptCommitResult.Committed -> ScheduledStoryChoiceResult.Saved(
                story,
                requestKey,
                committed.experience,
                committed.appliedTravelIds,
            )
        }
    }

    private suspend inline fun protectDue(
        block: () -> ScheduledStoryDueResult,
    ): ScheduledStoryDueResult = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ScheduledStoryDueResult.Failure(FeatureFailure(FeatureFailureKind.Storage))
    }

    private suspend inline fun protectChoice(
        block: () -> ScheduledStoryChoiceResult,
    ): ScheduledStoryChoiceResult = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        ScheduledStoryChoiceResult.Failure(FeatureFailure(FeatureFailureKind.Storage))
    }
}

private fun protocolDueFailure() =
    ScheduledStoryDueResult.Failure(FeatureFailure(FeatureFailureKind.Protocol))

private fun protocolChoiceFailure() =
    ScheduledStoryChoiceResult.Failure(FeatureFailure(FeatureFailureKind.Conflict))
