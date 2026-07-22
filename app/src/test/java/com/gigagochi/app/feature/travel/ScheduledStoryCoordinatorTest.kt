package com.gigagochi.app.feature.travel

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.ScheduledStoryChoiceClaim
import com.gigagochi.app.core.database.ScheduledStoryStore
import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.StoryApplicationResult
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.DueStoryResponseDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.ScheduledStoryResultDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledStoryCoordinatorTest {
    @Test
    fun dueEpisodePersistsIdempotentlyWithoutNavigationState() = runBlocking {
        val store = MemoryStoryStore()
        val api = StoryApi()
        val coordinator = ScheduledStoryCoordinator("owner-a", store, api)

        assertTrue(coordinator.checkDue(pet()) is ScheduledStoryDueResult.Saved)
        assertTrue(coordinator.checkDue(pet()) is ScheduledStoryDueResult.Saved)
        assertEquals(1, store.rows.size)
        assertEquals(2, api.dueCalls)
    }

    @Test
    fun generatingEpisodeRequestsAWorkerRetryWithoutPersistingPlaceholder() = runBlocking {
        val store = MemoryStoryStore()
        val api = StoryApi(pending = true)

        val result = ScheduledStoryCoordinator("owner-a", store, api).checkDue(pet())

        assertEquals(ScheduledStoryDueResult.Pending, result)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun secondChoiceAttemptAdoptsDurableWinnerRequestKeyAndDoesNotDoublePost() = runBlocking {
        val store = MemoryStoryStore().apply {
            saveScheduledStory(LocalScheduledStory("owner-a", StoryApi().story(StoryApi.storyDto())!!))
        }
        val api = StoryApi()
        val receiptStore = ReceiptStore(pet())
        val coordinator = ScheduledStoryCoordinator(
            "owner-a",
            store,
            api,
            StoryReceiptCoordinator("owner-a", "pet-a", receiptStore),
        )

        val first = coordinator.choose(StoryId, "b", WinnerKey)
        val replay = coordinator.choose(StoryId, "b", LoserKey)

        assertTrue(first is ScheduledStoryChoiceResult.Saved)
        assertTrue(replay is ScheduledStoryChoiceResult.Saved)
        assertEquals(listOf(WinnerKey), api.choiceKeys)
        assertEquals("b", store.rows.single().story.selectedChoice)
        assertEquals(20, receiptStore.pet.experience)
        assertEquals(1, receiptStore.receipts.size)
        assertEquals(20, (replay as ScheduledStoryChoiceResult.Saved).committedExperience)
    }

    @Test
    fun processDeathAfterChoiceSaveReconcilesMissingReceiptWithoutSecondPostOrXp() = runBlocking {
        val api = StoryApi()
        val selected = requireNotNull(
            api.story(
                StoryApi.storyDto().copy(
                    selectedChoice = "b",
                    result = ScheduledStoryResultDto(
                        "result", "helpful", "reaction", "enthusiastic",
                        "consequence", "positive", 20,
                    ),
                ),
            ),
        )
        val store = MemoryStoryStore().apply {
            saveScheduledStory(LocalScheduledStory("owner-a", selected, WinnerKey))
        }
        val receiptStore = ReceiptStore(pet())
        val restarted = ScheduledStoryCoordinator(
            "owner-a",
            store,
            api,
            StoryReceiptCoordinator("owner-a", "pet-a", receiptStore),
        )

        assertTrue(restarted.reconcileSelected(StoryId) is ScheduledStoryChoiceResult.Saved)
        assertTrue(restarted.reconcileSelected(StoryId) is ScheduledStoryChoiceResult.Saved)
        assertEquals(0, api.choiceKeys.size)
        assertEquals(20, receiptStore.pet.experience)
        assertEquals(1, receiptStore.receipts.size)
    }

    @Test
    fun onePartProjectionUsesAllChoicesAndSelectedOutcomeWithoutFourPartState() {
        val api = StoryApi()
        val question = requireNotNull(api.story(StoryApi.storyDto())).toTravelState(pet())
        assertEquals(TravelEntryPhase.StoryQuestion, question.phase)
        assertEquals(listOf("a", "b", "c", "d"), question.story?.choices)
        assertEquals("", question.story?.enabledChoice)

        val result = requireNotNull(
            api.story(
                StoryApi.storyDto().copy(
                    selectedChoice = "b",
                    result = ScheduledStoryResultDto(
                        "result", "helpful", "reaction", "enthusiastic",
                        "consequence", "positive", 20,
                    ),
                ),
            ),
        ).toTravelState(pet(), WinnerKey)
        assertEquals(TravelEntryPhase.StoryResult, result.phase)
        assertEquals("b", result.storyResult?.answer)
        assertEquals(20, result.storyResult?.experienceGained)
    }

    private class StoryApi(
        private val pending: Boolean = false,
    ) : TestAndroidFeatureService() {
        var dueCalls = 0
        val choiceKeys = mutableListOf<String>()

        override suspend fun dueStory(request: com.gigagochi.app.core.network.DueStoryRequestDto) =
            FeatureApiResult.Success(
                if (pending) DueStoryResponseDto(pending = true)
                else DueStoryResponseDto(storyDto()),
            )
                .also { dueCalls += 1 }

        override suspend fun chooseStory(storyId: String, request: ScheduledStoryChoiceRequestDto):
            FeatureApiResult<ScheduledStoryDto> {
            choiceKeys += request.requestKey
            return FeatureApiResult.Success(
                storyDto().copy(
                    selectedChoice = request.choice,
                    result = ScheduledStoryResultDto(
                        "result", "helpful", "reaction", "enthusiastic",
                        "consequence", "positive", 20,
                    ),
                ),
            )
        }

        companion object {
            fun storyDto() = ScheduledStoryDto(
                StoryId,
                "pet-a",
                "История",
                "Ситуация",
                "Что делать?",
                listOf("a", "b", "c", "d"),
                "2026-07-17T10:11:12Z",
                "/static/story.png?v=1",
            )
        }
    }

    private class MemoryStoryStore : ScheduledStoryStore {
        val rows = mutableListOf<LocalScheduledStory>()

        override suspend fun deleteScheduledStory(ownerId: String, storyId: String): Boolean =
            rows.removeAll { it.ownerId == ownerId && it.story.storyId == storyId }

        override suspend fun saveScheduledStory(story: LocalScheduledStory): Boolean {
            val index = rows.indexOfFirst { it.ownerId == story.ownerId && it.story.storyId == story.story.storyId }
            if (index < 0) rows += story else rows[index] = story.copy(
                choiceRequestKey = story.choiceRequestKey ?: rows[index].choiceRequestKey,
                pendingChoice = story.pendingChoice,
            )
            return true
        }

        override suspend fun getScheduledStory(ownerId: String, storyId: String) =
            rows.singleOrNull { it.ownerId == ownerId && it.story.storyId == storyId }

        override suspend fun getScheduledStories(ownerId: String, petId: String) =
            rows.filter { it.ownerId == ownerId && it.story.petId == petId }

        override suspend fun claimScheduledStoryChoice(
            ownerId: String,
            storyId: String,
            requestKey: String,
            choice: String,
        ): ScheduledStoryChoiceClaim {
            val index = rows.indexOfFirst { it.ownerId == ownerId && it.story.storyId == storyId }
            if (index < 0) return ScheduledStoryChoiceClaim.Missing
            val row = rows[index]
            row.story.selectedChoice?.let {
                return if (it == choice && row.choiceRequestKey != null) {
                    ScheduledStoryChoiceClaim.Completed(row.story, row.choiceRequestKey)
                }
                else ScheduledStoryChoiceClaim.Conflict
            }
            row.choiceRequestKey?.let {
                return if (row.pendingChoice == choice) ScheduledStoryChoiceClaim.Existing(it, choice)
                else ScheduledStoryChoiceClaim.Conflict
            }
            rows[index] = row.copy(choiceRequestKey = requestKey, pendingChoice = choice)
            return ScheduledStoryChoiceClaim.Claimed(requestKey, choice)
        }
    }

    private class ReceiptStore(var pet: PetDashboardState) : TestOwnerRecoveryStore() {
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
            listOf(OwnedPetSnapshot(ownerId, pet, 1)),
            emptyList(),
            emptyList(),
            emptyList(),
            receipts.filter { it.ownerId == ownerId },
        )
    }

    private fun pet() = PetDashboardState(
        petId = "pet-a",
        assetSetId = "asset-a",
        description = "dragon",
        name = "Гига",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 0,
        hunger = 50,
        happiness = 50,
        energy = 50,
        message = "",
    )

    private companion object {
        const val StoryId = "android-story-1234567890abcdef1234567890abcdef"
        const val WinnerKey = "123e4567-e89b-42d3-a456-426614174000"
        const val LoserKey = "223e4567-e89b-42d3-a456-426614174001"
    }
}
