package com.gigagochi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.feature.dashboard.DashboardDurableOperations
import com.gigagochi.app.feature.dashboard.DashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.DurableOutfitResult
import com.gigagochi.app.feature.dashboard.PendingOutfitGeneration
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.UnavailableDashboardTravelAdapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetLocalRepositoryTest {
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cleanV1DatabaseCreatesAndReopensWithMvpSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mvp-v1-reopen-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, GigagochiDatabase::class.java, name).build()
        PetLocalRepository(first).replacePetSnapshot(snapshot(experience = 321, petTapProgress = 4))
        first.close()

        val reopened = Room.databaseBuilder(context, GigagochiDatabase::class.java, name).build()
        assertEquals(321, PetLocalRepository(reopened).getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(4, PetLocalRepository(reopened).getPetSnapshot(OwnerId, PetId)?.pet?.petTapProgress)
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun ownerScopedReadsAndDeletesNeverCrossAccounts() = runBlocking {
        repository.replacePetSnapshot(snapshot(ownerId = "owner-a", experience = 100))
        repository.replacePetSnapshot(snapshot(ownerId = "owner-b", experience = 900))

        assertEquals(100, repository.getPetSnapshot("owner-a", PetId)?.pet?.experience)
        assertEquals(900, repository.getPetSnapshot("owner-b", PetId)?.pet?.experience)

        repository.deleteOwnerData("owner-a")

        assertNull(repository.getPetSnapshot("owner-a", PetId))
        assertNotNull(repository.getPetSnapshot("owner-b", PetId))
    }

    @Test
    fun chatComplimentRewardAndLedgerAreDurableAndIdempotent() = runBlocking {
        repository.replacePetSnapshot(snapshot(happiness = 10))
        val messages = listOf(
            LocalChatMessage("user-1", "user", "Ты очень смелый", 20),
            LocalChatMessage("pet-1", "pet", "Спасибо!", 21),
        )

        val ordinary = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-ordinary",
            messages,
            happinessDelta = 30,
            complimentKey = "смелый и добрый",
            appliedAtEpochMillis = 22,
        )
        assertEquals(40, ordinary.happiness)
        assertEquals(listOf("смелый и добрый"), repository.complimentHistory(OwnerId, PetId))

        val replay = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-ordinary",
            messages,
            happinessDelta = 100,
            complimentKey = "другой ключ",
            appliedAtEpochMillis = 23,
        )
        assertEquals(40, replay.happiness)
        assertEquals(listOf("смелый и добрый"), repository.complimentHistory(OwnerId, PetId))

        val exceptional = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-exceptional",
            emptyList(),
            happinessDelta = 100,
            complimentKey = "самый внимательный хранитель",
            appliedAtEpochMillis = 24,
        )
        assertEquals(100, exceptional.happiness)
        assertEquals(
            listOf("смелый и добрый", "самый внимательный хранитель"),
            repository.complimentHistory(OwnerId, PetId),
        )
    }

    @Test
    fun existingPetWithoutFirstSessionRowKeepsOrdinaryFlow() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))

        assertNull(repository.getFirstSession(OwnerId, PetId))
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun debugRestartFirstSessionRewindsDurableStageAndClearsActionReceipts() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat.storageValue,
                null,
                null,
                1,
            ),
        )
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 2,
            ) is FirstSessionMutationResult.Applied,
        )

        val restarted = repository.restartFirstSession(OwnerId, PetId, 3)
        assertEquals(FirstSessionStage.AwaitingChat, restarted?.stage)
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 4,
            ) is FirstSessionMutationResult.Applied,
        )

        assertTrue(repository.disableFirstSession(OwnerId, PetId))
        assertNull(repository.getFirstSession(OwnerId, PetId))
        repository.restartFirstSession(OwnerId, PetId, 5)
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 6,
            ) is FirstSessionMutationResult.Applied,
        )
    }

    @Test
    fun onboardingOutfitRetryReusesPendingAndCompletesOnlyAfterAttach() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 200))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingCompletionMessage.storageValue,
                null,
                null,
                1,
            ),
        )
        var calls = 0
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                calls += 1
                if (calls == 1) error("definite network failure")
                assertEquals("outfit-retry", request.requestKey)
                return PendingOutfitGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    displayItem = "Плащ",
                    localJobId = "outfit-${request.requestKey}",
                    backendJobId = "backend-retry",
                    createdAtEpochMillis = 10,
                )
            }
        }
        val first = DashboardDurableOperations(
            OwnerId, repository, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 10 },
        )
        assertTrue(first.acceptOutfit(
            PendingOutfitRequest("outfit-retry", "Зелёный плащ"),
            repository.getPetSnapshot(OwnerId, PetId)!!.pet,
        ) is DurableOutfitResult.PersistedButQueueFailed)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )

        val restarted = DashboardDurableOperations(
            OwnerId, repository, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 20 },
        )
        assertTrue(restarted.acceptOutfit(
            PendingOutfitRequest("ignored-new-key", "Другой наряд"),
            repository.getPetSnapshot(OwnerId, PetId)!!.pet,
        ) is DurableOutfitResult.Queued)

        assertEquals(2, calls)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)
        assertEquals("outfit-retry", repository.getPendingOutfits(OwnerId).single().requestKey)
        assertEquals("backend-retry", repository.getPendingOutfits(OwnerId).single().backendJobId)
        assertEquals(FirstSessionStage.Completed, repository.getFirstSession(OwnerId, PetId)?.stage)
    }

    @Test
    fun firstSessionSurvivesRecreationAndIsAtomicIdempotentThroughCompletion() = runBlocking {
        repository.savePendingCreate(create())
        assertTrue(repository.finalizeCreatedPet(snapshot(experience = 0, hunger = 40), "create-request"))
        assertEquals(FirstSessionStage.AwaitingChat, repository.getFirstSession(OwnerId, PetId)?.stage)

        val firstChat = repository.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChat,
            FirstSessionStage.AwaitingChatFollowup, "chat-1", nowEpochMillis = 20,
        )
        assertTrue(firstChat is FirstSessionMutationResult.Applied)
        val recreatedAfterName = PetLocalRepository(database)
        assertEquals(
            FirstSessionStage.AwaitingChatFollowup,
            recreatedAfterName.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertTrue(recreatedAfterName.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChat,
            FirstSessionStage.AwaitingChatFollowup, "chat-1", nowEpochMillis = 21,
        ) is FirstSessionMutationResult.AlreadyApplied)

        assertTrue(repository.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChatFollowup,
            FirstSessionStage.AwaitingFirstFood, "chat-2", nowEpochMillis = 22,
        ) is FirstSessionMutationResult.Applied)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "leaf-crunch", "wrong-food", 23,
        ) is FirstSessionMutationResult.WrongStage)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-1", 24,
        ) is FirstSessionMutationResult.Applied)
        val hungerAfterBerry = repository.getPetSnapshot(OwnerId, PetId)!!.pet.hunger
        assertEquals(65, hungerAfterBerry)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-1", 25,
        ) is FirstSessionMutationResult.AlreadyApplied)
        assertEquals(hungerAfterBerry, repository.getPetSnapshot(OwnerId, PetId)!!.pet.hunger)

        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-remedy", 26,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(FirstSessionStage.AwaitingRemedy, repository.getFirstSession(OwnerId, PetId)?.stage)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "leaf-crunch", "leaf-remedy", 27,
        ) is FirstSessionMutationResult.Applied)

        assertTrue(repository.commitFirstSessionBatChoice(
            OwnerId, PetId, "bat-choice", 28,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(200, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(FirstSessionStage.AwaitingTravel, repository.getFirstSession(OwnerId, PetId)?.stage)
        val recreatedAfterChoice = PetLocalRepository(database)
        assertTrue(recreatedAfterChoice.commitFirstSessionBatChoice(
            OwnerId, PetId, "bat-choice", 29,
        ) is FirstSessionMutationResult.AlreadyApplied)
        assertEquals(200, recreatedAfterChoice.getPetSnapshot(OwnerId, PetId)?.pet?.experience)

        assertTrue(recreatedAfterChoice.finishFirstSessionBat(
            OwnerId, PetId, "bat-finish", 30,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachOutfitBackendJob(OwnerId, "outfit-request", "outfit-backend"),
        )
        assertEquals(FirstSessionStage.Completed, repository.getFirstSession(OwnerId, PetId)?.stage)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(outfit()))
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun scheduledEpisodeIsOwnerScopedAndChoiceIsSingleRowIdempotent() = runBlocking {
        val base = ScheduledStory(
            "android-story-1234567890abcdef1234567890abcdef",
            PetId,
            "История",
            "Ситуация",
            "Что делать?",
            listOf("a", "b", "c", "d"),
            "2026-07-17T10:11:12Z",
            "https://gigagochi.serega.works/static/story.png?v=1",
            null,
        )
        assertTrue(repository.saveScheduledStory(LocalScheduledStory("owner-a", base)))
        assertTrue(repository.saveScheduledStory(LocalScheduledStory("owner-b", base)))
        assertEquals(1, repository.getScheduledStories("owner-a", PetId).size)
        assertEquals(1, repository.getScheduledStories("owner-b", PetId).size)
        val storyNotification = repository.getUnnotifiedNotifications("owner-a", PetId).single()
        assertEquals(LocalNotificationKind.ScheduledStory, storyNotification.kind)
        assertTrue(repository.markNotificationSent("owner-a", storyNotification, 20))
        assertTrue(repository.getUnnotifiedNotifications("owner-a", PetId).isEmpty())
        assertEquals(1, repository.getUnnotifiedNotifications("owner-b", PetId).size)

        val claim = repository.claimScheduledStoryChoice("owner-a", base.storyId, ChoiceKey, "b")
        assertTrue(claim is ScheduledStoryChoiceClaim.Claimed)
        assertTrue(
            repository.saveScheduledStory(
                LocalScheduledStory(
                    "owner-a",
                    base.copy(
                        selectedChoice = "b",
                        result = ScheduledStoryResult("result", "reaction", "consequence", 20),
                    ),
                    ChoiceKey,
                ),
            ),
        )
        assertTrue(
            repository.claimScheduledStoryChoice("owner-a", base.storyId, "other-key", "b")
                is ScheduledStoryChoiceClaim.Completed,
        )
        assertTrue(repository.getUnnotifiedNotifications("owner-a", PetId).isEmpty())
        assertNull(repository.getScheduledStory("owner-b", base.storyId)?.story?.selectedChoice)
    }

    @Test
    fun outfitAcceptanceDebitsOnceAndBackendJobAttachRejectsConflict() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        val pending = outfit()

        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(pending))
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(pending))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)

        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.AlreadyAttached,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.Conflict,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-b"),
        )
        assertEquals("backend-outfit-a", repository.getPendingOutfits(OwnerId).single().backendJobId)
    }

    @Test
    fun duplicateStoryReceiptRewardsOnceAndClampsAtProductCaps() = runBlocking {
        repository.replacePetSnapshot(
            snapshot(
                experience = 2_950,
                hunger = 95,
                happiness = 5,
                energy = 100,
            ),
        )
        val receipt = storyReceipt(
            experienceDelta = 200,
            hungerDelta = 25,
            happinessDelta = -10,
            energyDelta = 20,
        )

        assertEquals(StoryApplicationResult.Applied, repository.applyInteractiveStoryReceipt(receipt))
        assertEquals(
            StoryApplicationResult.AlreadyApplied,
            repository.applyInteractiveStoryReceipt(receipt.copy(receiptKey = "different-key")),
        )

        val pet = requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet
        assertEquals(3_000, pet.experience)
        assertEquals(100, pet.hunger)
        assertEquals(0, pet.happiness)
        assertEquals(100, pet.energy)
        assertEquals(1, repository.getStoryReceipts(OwnerId).size)
    }

    @Test
    fun replacingPetPreservesAllUnrelatedPendingRows() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        repository.savePendingCreate(create())
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(IdempotentInsertResult.Inserted, repository.savePendingTravel(travel()))

        repository.replacePetSnapshot(snapshot(experience = 700, message = "Обновлено"))

        assertEquals(1, repository.getPendingCreates(OwnerId).size)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)
        assertEquals(1, repository.getPendingTravels(OwnerId).size)
        assertEquals("Обновлено", repository.getPetSnapshot(OwnerId, PetId)?.pet?.message)

        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachTravelBackendJob(OwnerId, "travel-request", "backend-travel-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.Conflict,
            repository.attachTravelBackendJob(OwnerId, "travel-request", "backend-travel-b"),
        )
        assertEquals("backend-travel-a", repository.getPendingTravels(OwnerId).single().backendJobId)
    }

    @Test
    fun terminalCreateRetryAtomicallyReplacesFailedRequest() = runBlocking {
        repository.savePendingCreate(
            create(
                requestKey = "failed-request",
                backendJobId = "failed-job",
                backendState = PendingBackendState.Failed,
            ),
        )
        val replacement = create(requestKey = "retry-request")

        assertTrue(
            repository.replaceFailedPendingCreate(
                OwnerId,
                "failed-request",
                replacement,
            ),
        )

        val pending = repository.getPendingCreates(OwnerId).single()
        assertEquals("retry-request", pending.requestKey)
        assertNull(pending.backendJobId)
        assertEquals(PendingBackendState.Pending, pending.backendState)
    }

    @Test
    fun invalidDataIsRejectedBeforeAnyDatabaseWrite() = runBlocking {
        assertRejected {
            repository.replacePetSnapshot(snapshot(experience = 3_001))
        }
        assertRejected {
            repository.savePendingTravel(travel(prompt = "x".repeat(1_001)))
        }
        assertRejected {
            repository.savePendingCreate(create(ownerId = " "))
        }

        assertTrue(repository.getPetSnapshots(OwnerId).isEmpty())
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())
        assertTrue(repository.getPendingCreates(OwnerId).isEmpty())
    }

    @Test
    fun ownerRecoveryTransactionReturnsOnlyRequestedOwnerRows() = runBlocking {
        repository.replacePetSnapshot(snapshot(ownerId = OwnerId, experience = 500))
        repository.replacePetSnapshot(snapshot(ownerId = "owner-b", experience = 900))
        repository.savePendingCreate(create())
        repository.savePendingCreate(create(ownerId = "owner-b"))
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(
            OutfitAcceptanceResult.Applied,
            repository.acceptOutfit(
                outfit().copy(
                    ownerId = "owner-b",
                ),
            ),
        )
        repository.savePendingTravel(travel())
        repository.savePendingTravel(
            travel().copy(
                ownerId = "owner-b",
            ),
        )
        repository.applyInteractiveStoryReceipt(
            storyReceipt(10, 0, 0, 0),
        )
        repository.applyInteractiveStoryReceipt(
            storyReceipt(20, 0, 0, 0).copy(
                ownerId = "owner-b",
            ),
        )

        val recovery = repository.loadOwnerRecovery(OwnerId)
        assertEquals(setOf(OwnerId), recovery.petSnapshots.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingCreates.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingOutfits.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingTravels.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.storyReceipts.map { it.ownerId }.toSet())
        assertEquals(1, recovery.petSnapshots.size)
        assertEquals(1, recovery.pendingCreates.size)
        assertEquals(1, recovery.pendingOutfits.size)
        assertEquals(1, recovery.pendingTravels.size)
        assertEquals(1, recovery.storyReceipts.size)
        val ownerBRecovery = repository.loadOwnerRecovery("owner-b")
        assertEquals(setOf("owner-b"), ownerBRecovery.petSnapshots.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingCreates.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingOutfits.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingTravels.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.storyReceipts.map { it.ownerId }.toSet())
    }

    @Test
    fun outfitOutcomesKeepRequestScopedImagesAndNeverPrematurelyMutateSnapshot() = runBlocking {
        val activeImages = mediaImages("active")
        repository.replacePetSnapshot(
            snapshot().copy(
                pet = snapshot().pet.copy(
                    generatedMedia = PetGeneratedMedia(moodImages = activeImages),
                ),
            ),
        )
        repository.saveOutfitMediaOutcome(outcome("request-b", "job-b", mediaImages("b")))
        repository.saveOutfitMediaOutcome(outcome("request-c", "job-c", mediaImages("c")))

        assertEquals(activeImages, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.moodImages)
        val outcomes = repository.getOutfitMediaOutcomes(OwnerId, PetId).associateBy { it.requestKey }
        assertEquals(mediaImages("b"), outcomes.getValue("request-b").media.moodImages)
        assertEquals(mediaImages("c"), outcomes.getValue("request-c").media.moodImages)
    }

    @Test
    fun characterBibleAboveLegacy64KiBRoundTripsButOver256KiBIsRejectedBeforeWrite() = runBlocking {
        val accepted = "{\"lore\":\"${"я".repeat(100_000)}\"}"
        repository.replacePetSnapshot(
            snapshot().copy(
                pet = snapshot().pet.copy(
                    generatedMedia = PetGeneratedMedia(characterBibleJson = accepted),
                ),
            ),
        )
        assertEquals(accepted, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.characterBibleJson)

        val rejected = "{\"lore\":\"${"я".repeat(140_000)}\"}"
        assertRejected {
            repository.replacePetSnapshot(
                snapshot().copy(
                    pet = snapshot().pet.copy(
                        generatedMedia = PetGeneratedMedia(characterBibleJson = rejected),
                    ),
                ),
            )
        }
        assertEquals(accepted, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.characterBibleJson)
    }

    @Test
    fun outfitApplyIsAtomicOwnerFencedIdempotentAndRejectsStaleSnapshotSave() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        val pending = outfit()
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(pending))
        repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "job-ready")
        repository.updateOutfitBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        repository.saveOutfitMediaOutcome(
            outcome(pending.requestKey, "job-ready", mediaImages("applied")),
        )

        assertEquals(
            OutfitOutcomeApplicationResult.NotReady,
            repository.applyOutfitOutcome("owner-b", PetId, pending.requestKey),
        )
        val applied = repository.applyOutfitOutcome(OwnerId, PetId, pending.requestKey)
        assertTrue(applied is OutfitOutcomeApplicationResult.Applied)
        assertEquals("asset-${pending.requestKey}", repository.getPetSnapshot(OwnerId, PetId)?.pet?.assetSetId)
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())
        val outfitNotification = repository.getUnnotifiedNotifications(OwnerId, PetId).single()
        assertEquals(LocalNotificationKind.OutfitReady, outfitNotification.kind)
        assertTrue(repository.markNotificationSent(OwnerId, outfitNotification, 30))
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
        assertTrue(repository.getOutfitMediaOutcomes(OwnerId, PetId).isEmpty())
        assertTrue(repository.applyOutfitOutcome(OwnerId, PetId, pending.requestKey) is OutfitOutcomeApplicationResult.AlreadyApplied)
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(pending))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())

        val stale = snapshot(experience = 999)
        assertEquals(false, repository.replacePetSnapshotIfAssetCurrent(stale))
        assertEquals("asset-${pending.requestKey}", repository.getPetSnapshot(OwnerId, PetId)?.pet?.assetSetId)
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun travelConsumptionKeepsReadableAssetAndDeletesPendingExactlyOnce() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = travel()
        repository.savePendingTravel(pending)
        repository.attachTravelBackendJob(OwnerId, pending.requestKey, "travel-job")
        repository.updateTravelBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        repository.saveTravelVideoAsset(
            LocalTravelVideoAsset(
                OwnerId,
                PetId,
                pending.requestKey,
                "travel-job",
                pending.prompt,
                "Ночной рынок",
                "Сценарий",
                "https://gigagochi.serega.works/static/travel.jpg",
                "https://gigagochi.serega.works/static/travel.mp4",
                20,
            ),
        )

        assertEquals(
            TravelAssetConsumptionResult.NotReady,
            repository.consumeTravelAsset("owner-b", PetId, pending.requestKey, 21),
        )
        val consumed = repository.consumeTravelAsset(OwnerId, PetId, pending.requestKey, 21)
        assertTrue(consumed is TravelAssetConsumptionResult.Consumed)
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())
        val recovered = repository.getTravelVideoAssets(OwnerId, PetId).single()
        assertEquals(21L, recovered.consumedAtEpochMillis)
        repository.saveTravelVideoAsset(recovered.copy(consumedAtEpochMillis = null))
        assertEquals(21L, repository.getTravelVideoAssets(OwnerId, PetId).single().consumedAtEpochMillis)
        val travelNotification = repository.getUnnotifiedNotifications(OwnerId, PetId).single()
        assertEquals(LocalNotificationKind.TravelReady, travelNotification.kind)
        assertEquals(pending.requestKey, travelNotification.travelRequestKey)
        assertTrue(repository.markNotificationSent(OwnerId, travelNotification, 31))
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
        assertTrue(
            repository.consumeTravelAsset(OwnerId, PetId, pending.requestKey, 22) is
                TravelAssetConsumptionResult.AlreadyConsumed,
        )
        assertEquals(21L, repository.getTravelVideoAssets(OwnerId, PetId).single().consumedAtEpochMillis)
    }

    @Test
    fun applyConflictCasTransitionsOnlyReadyPendingToDurableFailed() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = outfit()
        repository.acceptOutfit(pending)
        assertEquals(false, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
        repository.updateOutfitBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        assertEquals(true, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
        val failed = repository.getPendingOutfits(OwnerId).single()
        assertEquals(PendingBackendState.Failed, failed.backendState)
        assertEquals("APPLY_CONFLICT", failed.backendErrorCode)
        assertEquals(false, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
    }

    private fun snapshot(
        ownerId: String = OwnerId,
        experience: Int = 500,
        hunger: Int = 100,
        happiness: Int = 100,
        energy: Int = 100,
        message: String = "Как тебя зовут?",
        petTapProgress: Int = 0,
    ) = OwnedPetSnapshot(
        ownerId = ownerId,
        pet = PetDashboardState(
            petId = PetId,
            assetSetId = AssetSetId,
            description = "Ледяной дракон",
            name = "Без имени",
            stage = "baby",
            stageLabel = "Уровень: Малыш",
            mood = "idle",
            experience = experience,
            hunger = hunger,
            happiness = happiness,
            energy = energy,
            message = message,
            petTapProgress = petTapProgress,
        ),
        updatedAtEpochMillis = 10,
    )

    private fun create(
        ownerId: String = OwnerId,
        requestKey: String = "create-request",
        backendJobId: String? = null,
        backendState: PendingBackendState = PendingBackendState.Pending,
    ) = LocalPendingCreateGeneration(
        ownerId = ownerId,
        petId = PetId,
        requestKey = requestKey,
        backendJobId = backendJobId,
        stage = PendingCreateStage.Generating,
        description = "Ледяной дракон",
        name = "Тото",
        personality = "Добрый",
        fear = "Пауков",
        favoriteItem = null,
        currentStep = 4,
        updatedAtEpochMillis = 11,
        backendState = backendState,
    )

    private fun outfit() = LocalPendingOutfit(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = "outfit-request",
        localJobId = "outfit-local",
        backendJobId = null,
        prompt = "В футболку Metallica",
        baseAssetSetId = AssetSetId,
        acceptedAtEpochMillis = 12,
    )

    private fun travel(prompt: String = "На ночной рынок духов") = LocalPendingTravelVideo(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = "travel-request",
        localJobId = "travel-local",
        backendJobId = null,
        prompt = prompt,
        acceptedAtEpochMillis = 13,
    )

    private fun storyReceipt(
        experienceDelta: Int,
        hungerDelta: Int,
        happinessDelta: Int,
        energyDelta: Int,
    ) = InteractiveStoryReceipt(
        ownerId = OwnerId,
        petId = PetId,
        receiptKey = "story-receipt",
        travelId = "travel-story",
        partKey = "result-1",
        experienceDelta = experienceDelta,
        hungerDelta = hungerDelta,
        happinessDelta = happinessDelta,
        energyDelta = energyDelta,
        appliedAtEpochMillis = 14,
    )

    private fun mediaImages(label: String) = listOf(
        PetMoodImage("baby", "idle", "https://gigagochi.serega.works/static/$label.png"),
    )

    private fun outcome(requestKey: String, jobId: String, images: List<PetMoodImage>) =
        LocalOutfitMediaOutcome(
            OwnerId,
            PetId,
            requestKey,
            jobId,
            "плащ",
            "asset-$requestKey",
            PetGeneratedMedia(
                generatedAt = "2026-07-17T10:11:12Z",
                moodImages = images,
            ),
            20,
        )

    private suspend fun assertRejected(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected validation rejection", rejected)
    }

    private companion object {
        const val OwnerId = "owner-a"
        const val PetId = "pet-a"
        const val AssetSetId = "debug-test-pet-seedance-forest-mouse-v1"
        const val ChoiceKey = "123e4567-e89b-42d3-a456-426614174000"
    }
}
