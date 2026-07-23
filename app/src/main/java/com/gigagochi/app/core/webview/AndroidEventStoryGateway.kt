package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.feature.travel.ScheduledStoryChoiceResult
import com.gigagochi.app.feature.travel.ScheduledStoryChoicePreparationResult
import com.gigagochi.app.feature.travel.ScheduledStoryCoordinator
import com.gigagochi.app.feature.travel.ScheduledStoryDueResult
import com.gigagochi.app.feature.travel.StoryReceiptCoordinator
import kotlinx.coroutines.flow.first

/** Production Room/network adapter for [DurableEventStoryRuntime]. */
internal class AndroidEventStoryGateway(
    private val ownerId: String,
    private val repository: PetLocalRepository,
    private val api: AndroidFeatureService,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : EventStoryGateway {
    override suspend fun load(petId: String): EventStoryRecovery? {
        val recovery = repository.loadOwnerRecovery(ownerId)
        val pet = recovery.petSnapshots.singleOrNull { it.pet.petId == petId }?.pet ?: return null
        return EventStoryRecovery(
            pet = pet,
            firstSession = recovery.firstSessions.singleOrNull { it.petId == petId },
            scheduledStories = repository.getScheduledStories(ownerId, petId),
            travelVideoAssets = recovery.travelVideoAssets.filter { it.petId == petId },
            storyReceipts = recovery.storyReceipts.filter { it.petId == petId },
            lastViewedAtEpochMillis = repository
                .observeEventHistoryLastViewed(ownerId, petId)
                .first(),
        )
    }

    override suspend fun markViewed(petId: String, viewedAtEpochMillis: Long): Boolean {
        repository.markEventHistoryViewed(ownerId, petId, viewedAtEpochMillis)
        return true
    }

    override suspend fun checkDue(pet: PetDashboardState): ScheduledStoryDueResult =
        coordinator(pet.petId).checkDue(pet)

    override suspend fun prepareScheduledStoryChoice(
        storyId: String,
        choice: String,
        proposedRequestKey: String,
    ): ScheduledStoryChoicePreparationResult = coordinatorForStory(storyId).prepareChoice(
        storyId,
        choice,
        proposedRequestKey,
    )

    override suspend fun executePreparedScheduledStoryChoice(
        storyId: String,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceResult = coordinatorForStory(storyId).executePreparedChoice(
        storyId,
        requestKey,
        choice,
    )

    override suspend fun reconcileScheduledStory(storyId: String): ScheduledStoryChoiceResult =
        coordinatorForStory(storyId).reconcileSelected(storyId)

    override suspend fun commitOnboardingBatChoice(
        petId: String,
        requestKey: String,
    ): FirstSessionMutationResult = repository.commitFirstSessionBatChoice(
        ownerId,
        petId,
        requestKey,
        nowEpochMillis(),
    )

    override suspend fun finishOnboardingBat(
        petId: String,
        actionKey: String,
    ): FirstSessionMutationResult = repository.finishFirstSessionBat(
        ownerId,
        petId,
        actionKey,
        nowEpochMillis(),
    )

    private suspend fun coordinatorForStory(storyId: String): ScheduledStoryCoordinator {
        val story = repository.getScheduledStory(ownerId, storyId)
        val petId = story?.story?.petId ?: "missing-pet"
        return coordinator(petId)
    }

    private fun coordinator(petId: String) = ScheduledStoryCoordinator(
        ownerId = ownerId,
        store = repository,
        api = api,
        receiptCoordinator = StoryReceiptCoordinator(
            ownerId = ownerId,
            petId = petId,
            store = repository,
            nowEpochMillis = nowEpochMillis,
        ),
    )
}
