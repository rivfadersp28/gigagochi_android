package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.OutfitAcceptanceResult
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import com.gigagochi.app.core.database.IdempotentInsertResult
import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDurableOperationsTest {
    @Test
    fun failedOutfitQueueThenReopenDoesNotDebitOrQueueTwice() = runBlocking {
        val store = Store(pet(experience = 500))
        var queueCalls = 0
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                queueCalls += 1
                error("network")
            }
        }
        val firstCoordinator = DashboardDurableOperations(
            "owner-a", store, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 10 },
        )
        val request = PendingOutfitRequest("request-1", "В футболку Metallica")
        assertTrue(firstCoordinator.acceptOutfit(request, store.pet) is DurableOutfitResult.PersistedButQueueFailed)
        assertEquals(300, store.pet.experience)

        val restarted = DashboardDurableOperations(
            "owner-a", store, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 20 },
        )
        assertTrue(restarted.acceptOutfit(PendingOutfitRequest("request-2", "Другой"), store.pet) is DurableOutfitResult.PersistedButQueueFailed)
        assertEquals(300, store.pet.experience)
        assertEquals(1, store.outfits.size)
        assertEquals(1, queueCalls)
    }

    @Test
    fun restartWithPendingTravelDoesNotQueueSecondRequest() = runBlocking {
        val store = Store(pet()).apply {
            travels += LocalPendingTravelVideo(
                "owner-a", pet.petId, "travel-1", "local-1", null, "Луна", 5,
            )
        }
        var queueCalls = 0
        val adapter = object : DashboardTravelAdapter {
            override suspend fun queue(
                request: PendingTravelRequest,
                pet: PetDashboardState,
            ): PendingTravelGeneration {
                queueCalls += 1
                error("must not queue")
            }
        }
        val coordinator = DashboardDurableOperations(
            "owner-a", store, UnavailableDashboardOutfitAdapter(), adapter,
        )

        assertTrue(coordinator.acceptTravel(PendingTravelRequest("travel-2", "Марс"), store.pet) is DurableTravelResult.PersistedButQueueFailed)
        assertEquals(1, store.travels.size)
        assertEquals(0, queueCalls)
    }

    @Test
    fun consumedTravelRequestReplayDoesNotCreatePendingOrSubmitAgain() = runBlocking {
        val store = Store(pet()).apply {
            travelAssets += LocalTravelVideoAsset(
                "owner-a",
                pet.petId,
                "travel-consumed",
                "backend-ready",
                "Луна",
                null,
                null,
                null,
                "https://gigagochi.serega.works/static/travel.mp4",
                10,
                11,
            )
        }
        var queueCalls = 0
        val adapter = object : DashboardTravelAdapter {
            override suspend fun queue(
                request: PendingTravelRequest,
                pet: PetDashboardState,
            ): PendingTravelGeneration {
                queueCalls += 1
                error("completed request must not submit again")
            }
        }

        val result = DashboardDurableOperations(
            "owner-a", store, UnavailableDashboardOutfitAdapter(), adapter,
        ).acceptTravel(PendingTravelRequest("travel-consumed", "Луна"), store.pet)

        assertEquals(DurableTravelResult.Failure, result)
        assertTrue(store.travels.isEmpty())
        assertEquals(0, queueCalls)
    }

    private class Store(var pet: PetDashboardState) : TestOwnerRecoveryStore() {
        val outfits = mutableListOf<LocalPendingOutfit>()
        val travels = mutableListOf<LocalPendingTravelVideo>()
        val travelAssets = mutableListOf<LocalTravelVideoAsset>()
        override suspend fun loadOwnerRecovery(ownerId: String) = OwnerRecoveryData(
            listOf(OwnedPetSnapshot(ownerId, pet, 1)), emptyList(),
            outfits.filter { it.ownerId == ownerId }, travels.filter { it.ownerId == ownerId }, emptyList(),
            travelVideoAssets = travelAssets.filter { it.ownerId == ownerId },
        )
        override suspend fun acceptOutfit(pending: LocalPendingOutfit): OutfitAcceptanceResult {
            if (outfits.any { it.requestKey == pending.requestKey }) return OutfitAcceptanceResult.AlreadyApplied
            outfits += pending
            pet = pet.copy(experience = pet.experience - 200)
            return OutfitAcceptanceResult.Applied
        }
        override suspend fun savePendingTravel(pending: LocalPendingTravelVideo): IdempotentInsertResult {
            if (travels.any { it.requestKey == pending.requestKey }) return IdempotentInsertResult.AlreadyPresent
            travels += pending
            return IdempotentInsertResult.Inserted
        }
    }

    private fun pet(experience: Int = 500) = PetDashboardState(
        "pet-a", "assets-a", "Ледяной дракон", "Тото", "baby", "Малыш", "idle",
        experience, 100, 100, 100, "Привет", false,
    )
}
