package com.gigagochi.app.feature.travel

import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.StoryApplicationResult
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryReceiptCoordinatorTest {
    @Test
    fun replayAndRestartApplyRewardOnlyOnce() = runBlocking {
        val store = Store(pet(experience = 100))
        val story = onboardingBatStory("pet-a")
        val request = PendingTravelStoryChoice(story.travelId, "receipt-1", story.enabledChoice)
        val result = onboardingBatStoryResult(story, request.requestKey)

        val first = StoryReceiptCoordinator("owner-a", "pet-a", store).commit(request, result)
        val restarted = StoryReceiptCoordinator("owner-a", "pet-a", store).commit(request, result)

        assertTrue(first is StoryReceiptCommitResult.Committed)
        assertTrue(restarted is StoryReceiptCommitResult.Committed)
        assertEquals(300, store.pet.experience)
        assertEquals(1, store.receipts.size)
        assertEquals(300, (restarted as StoryReceiptCommitResult.Committed).experience)
    }

    private class Store(var pet: PetDashboardState) : TestOwnerRecoveryStore() {
        val receipts = mutableListOf<InteractiveStoryReceipt>()
        override suspend fun applyInteractiveStoryReceipt(
            receipt: InteractiveStoryReceipt,
        ): StoryApplicationResult {
            if (receipts.any {
                    it.receiptKey == receipt.receiptKey ||
                        it.travelId == receipt.travelId && it.partKey == receipt.partKey
                }
            ) return StoryApplicationResult.AlreadyApplied
            receipts += receipt
            pet = pet.copy(experience = pet.experience + receipt.experienceDelta)
            return StoryApplicationResult.Applied
        }
        override suspend fun loadOwnerRecovery(ownerId: String) = OwnerRecoveryData(
            listOf(OwnedPetSnapshot(ownerId, pet, 1)), emptyList(), emptyList(), emptyList(),
            receipts.filter { it.ownerId == ownerId },
        )
    }

    private fun pet(experience: Int) = PetDashboardState(
        "pet-a", "assets-a", "Ледяной дракон", "Тото", "baby", "Малыш", "idle",
        experience, 100, 100, 100, "Привет",
    )
}
