package com.gigagochi.app.core.database

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPersistenceValidationTest {
    @Test
    fun acceptedSnapshotBoundsMatchProductCaps() {
        LocalPersistenceValidation.petSnapshot(snapshot(experience = 3_000, hunger = 0, energy = 100))
    }

    @Test
    fun invalidProgressAndOwnerScopeAreRejected() {
        assertRejected { LocalPersistenceValidation.petSnapshot(snapshot(experience = 3_001)) }
        assertRejected { LocalPersistenceValidation.petSnapshot(snapshot(happiness = 101)) }
        assertRejected { LocalPersistenceValidation.ownerId("   ") }
        assertRejected { LocalPersistenceValidation.ownerId(" owner-a") }
    }

    @Test
    fun pendingPromptsIdsAndExactOutfitCostAreBounded() {
        assertRejected {
            LocalPersistenceValidation.pendingTravel(
                travel(prompt = "x".repeat(1_001)),
            )
        }
        assertRejected {
            LocalPersistenceValidation.pendingOutfit(
                outfit(experienceCost = 199),
            )
        }
        assertRejected {
            LocalPersistenceValidation.pendingCreate(
                create(requestKey = " "),
            )
        }
    }

    @Test
    fun roomSchemaTypesContainNoAuthSecretColumns() {
        val forbidden = listOf("accessToken", "refreshToken", "idToken", "nonce", "bearer")
        val fieldNames = listOf(
            PetSnapshotEntity::class,
            PendingCreateGenerationEntity::class,
            PendingOutfitEntity::class,
            PendingTravelVideoEntity::class,
            AppliedStoryReceiptEntity::class,
        ).flatMap { type -> type.java.declaredFields.map { it.name } }

        forbidden.forEach { secret ->
            assertFalse(fieldNames.any { it.contains(secret, ignoreCase = true) })
        }
        assertTrue(fieldNames.all { it.isNotBlank() })
    }

    @Test
    fun characterBibleUsesAuthoritativeUtf8ByteLimitNotLegacy64KiB() {
        val accepted = "{\"lore\":\"${"я".repeat(100_000)}\"}"
        LocalPersistenceValidation.petSnapshot(
            snapshot().copy(
                pet = snapshot().pet.copy(
                    generatedMedia = PetGeneratedMedia(characterBibleJson = accepted),
                ),
            ),
        )
        val rejected = "{\"lore\":\"${"я".repeat(140_000)}\"}"
        assertRejected {
            LocalPersistenceValidation.petSnapshot(
                snapshot().copy(
                    pet = snapshot().pet.copy(
                        generatedMedia = PetGeneratedMedia(characterBibleJson = rejected),
                    ),
                ),
            )
        }
    }

    private fun snapshot(
        experience: Int = 500,
        hunger: Int = 100,
        happiness: Int = 100,
        energy: Int = 100,
    ) = OwnedPetSnapshot(
        ownerId = "owner-a",
        pet = PetDashboardState(
            petId = "pet-a",
            assetSetId = "asset-a",
            description = "Ледяной дракон",
            name = "Без имени",
            stage = "baby",
            stageLabel = "Уровень: Малыш",
            mood = "idle",
            experience = experience,
            hunger = hunger,
            happiness = happiness,
            energy = energy,
            message = "Как тебя зовут?",
        ),
        updatedAtEpochMillis = 1,
    )

    private fun create(requestKey: String = "create-request") = LocalPendingCreateGeneration(
        ownerId = "owner-a",
        petId = "pet-a",
        requestKey = requestKey,
        backendJobId = null,
        stage = PendingCreateStage.Requested,
        description = "Ледяной дракон",
        name = null,
        personality = null,
        fear = null,
        favoriteItem = null,
        currentStep = 1,
        updatedAtEpochMillis = 1,
    )

    private fun outfit(experienceCost: Int = OutfitAcceptanceCost) = LocalPendingOutfit(
        ownerId = "owner-a",
        petId = "pet-a",
        requestKey = "outfit-request",
        localJobId = "outfit-local",
        backendJobId = null,
        prompt = "В футболку Metallica",
        baseAssetSetId = "asset-a",
        acceptedAtEpochMillis = 2,
        experienceCost = experienceCost,
    )

    private fun travel(prompt: String = "На ночной рынок духов") = LocalPendingTravelVideo(
        ownerId = "owner-a",
        petId = "pet-a",
        requestKey = "travel-request",
        localJobId = "travel-local",
        backendJobId = null,
        prompt = prompt,
        acceptedAtEpochMillis = 2,
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected validation rejection", rejected)
    }
}
