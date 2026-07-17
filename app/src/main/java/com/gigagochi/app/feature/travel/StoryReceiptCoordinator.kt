package com.gigagochi.app.feature.travel

import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.StoryApplicationResult
import kotlinx.coroutines.CancellationException

sealed interface StoryReceiptCommitResult {
    data class Committed(
        val experience: Int,
        val appliedTravelIds: Set<String>,
    ) : StoryReceiptCommitResult
    data object Failure : StoryReceiptCommitResult
}

class StoryReceiptCoordinator(
    private val ownerId: String,
    private val petId: String,
    private val store: OwnerRecoveryStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun commit(
        request: PendingTravelStoryChoice,
        result: InteractiveTravelStoryResult,
    ): StoryReceiptCommitResult {
        return try {
            if (
                request.travelId != result.travelId ||
                request.requestKey != result.requestKey ||
                result.experienceGained < 0
            ) return StoryReceiptCommitResult.Failure
            val receipt = InteractiveStoryReceipt(
                ownerId = ownerId,
                petId = petId,
                receiptKey = request.requestKey,
                travelId = result.travelId,
                partKey = "choice-result",
                experienceDelta = result.experienceGained,
                hungerDelta = 0,
                happinessDelta = 0,
                energyDelta = 0,
                appliedAtEpochMillis = nowEpochMillis(),
            )
            when (store.applyInteractiveStoryReceipt(receipt)) {
                StoryApplicationResult.PetMissing -> return StoryReceiptCommitResult.Failure
                StoryApplicationResult.Applied,
                StoryApplicationResult.AlreadyApplied,
                -> Unit
            }
            val recovery = store.loadOwnerRecovery(ownerId)
            val pet = recovery.petSnapshots.firstOrNull { it.pet.petId == petId }?.pet
                ?: return StoryReceiptCommitResult.Failure
            StoryReceiptCommitResult.Committed(
                experience = pet.experience,
                appliedTravelIds = recovery.storyReceipts
                    .filter { it.petId == petId }
                    .mapTo(mutableSetOf()) { it.travelId },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            StoryReceiptCommitResult.Failure
        }
    }
}
