package com.gigagochi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
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
        PetLocalRepository(first).replacePetSnapshot(snapshot(experience = 321))
        first.close()

        val reopened = Room.databaseBuilder(context, GigagochiDatabase::class.java, name).build()
        assertEquals(321, PetLocalRepository(reopened).getPetSnapshot(OwnerId, PetId)?.pet?.experience)
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
        assertTrue(repository.getOutfitMediaOutcomes(OwnerId, PetId).isEmpty())
        assertTrue(repository.applyOutfitOutcome(OwnerId, PetId, pending.requestKey) is OutfitOutcomeApplicationResult.AlreadyApplied)

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
            firstSessionActive = false,
        ),
        updatedAtEpochMillis = 10,
    )

    private fun create(ownerId: String = OwnerId) = LocalPendingCreateGeneration(
        ownerId = ownerId,
        petId = PetId,
        requestKey = "create-request",
        backendJobId = null,
        stage = PendingCreateStage.Generating,
        description = "Ледяной дракон",
        name = "Тото",
        personality = "Добрый",
        fear = "Пауков",
        favoriteItem = null,
        currentStep = 4,
        updatedAtEpochMillis = 11,
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
    }
}
