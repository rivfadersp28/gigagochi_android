package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.OutfitAcceptanceResult
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.database.TestOwnerRecoveryStore
import com.gigagochi.app.core.database.IdempotentInsertResult
import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDurableOperationsTest {
    @Test
    fun serverChatFailureUsesNativeFallbackReply() {
        assertEquals(
            DeterministicChatReply,
            FeatureFailure(FeatureFailureKind.Server).chatFallbackReplyOrNull(),
        )
    }

    @Test
    fun retryableNonServerChatFailuresRemainVisible() {
        listOf(
            FeatureFailureKind.Network,
            FeatureFailureKind.SessionInvalid,
            FeatureFailureKind.RateLimited,
            FeatureFailureKind.Protocol,
        ).forEach { kind ->
            assertEquals(null, FeatureFailure(kind).chatFallbackReplyOrNull())
        }
    }

    @Test
    fun failedOutfitQueueThenReopenRetriesSamePendingWithoutDebit() = runBlocking {
        val store = Store(pet(experience = 500))
        var queueCalls = 0
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                queueCalls += 1
                if (queueCalls == 1) error("network")
                assertEquals("request-1", request.requestKey)
                assertEquals("В футболку Metallica", request.prompt)
                return PendingOutfitGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    displayItem = "Футболка Metallica",
                    localJobId = "outfit-${request.requestKey}",
                    backendJobId = "backend-1",
                    createdAtEpochMillis = 10,
                )
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
        assertTrue(restarted.acceptOutfit(PendingOutfitRequest("request-2", "Другой"), store.pet) is DurableOutfitResult.Queued)
        assertEquals(300, store.pet.experience)
        assertEquals(1, store.outfits.size)
        assertEquals("backend-1", store.outfits.single().backendJobId)
        assertEquals(2, queueCalls)
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
    fun outcomeUnknownOutfitIsNeverRedispatched() = runBlocking {
        val store = Store(pet(experience = 300)).apply {
            outfits += LocalPendingOutfit(
                "owner-a", pet.petId, "outfit-ambiguous", "local-ambiguous", null,
                "Плащ", pet.assetSetId, 5,
                backendState = PendingBackendState.OutcomeUnknown,
                backendErrorCode = "SUBMIT_UNKNOWN",
            )
        }
        var queueCalls = 0
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                queueCalls += 1
                error("ambiguous submit must not repeat")
            }
        }

        val result = DashboardDurableOperations(
            "owner-a", store, adapter, UnavailableDashboardTravelAdapter(),
        ).acceptOutfit(PendingOutfitRequest("new-key", "Другой плащ"), store.pet)

        assertTrue(result is DurableOutfitResult.PersistedButQueueFailed)
        assertEquals(0, queueCalls)
        assertEquals(300, store.pet.experience)
    }

    @Test
    fun failedOutfitAuditAllowsNewRequestAndAdapterDispatch() = runBlocking {
        val store = Store(pet(experience = 800)).apply {
            outfits += LocalPendingOutfit(
                "owner-a",
                pet.petId,
                "outfit-failed",
                "local-failed",
                "backend-failed",
                "В старую футболку",
                pet.assetSetId,
                5,
                backendState = PendingBackendState.Failed,
                backendErrorCode = "GENERATION_FAILED",
            )
        }
        val dispatchedKeys = mutableListOf<String>()
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                dispatchedKeys += request.requestKey
                return PendingOutfitGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    displayItem = "Новая футболка",
                    localJobId = "outfit-${request.requestKey}",
                    backendJobId = null,
                    createdAtEpochMillis = 10,
                )
            }
        }

        val result = DashboardDurableOperations(
            "owner-a", store, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 10 },
        ).acceptOutfit(PendingOutfitRequest("outfit-new", "В новую футболку"), store.pet)

        assertTrue(result is DurableOutfitResult.Queued)
        assertEquals(listOf("outfit-new"), dispatchedKeys)
        assertEquals(600, store.pet.experience)
        assertEquals(listOf("outfit-failed", "outfit-new"), store.outfits.map { it.requestKey })
        assertEquals(PendingBackendState.Failed, store.outfits.first().backendState)
    }

    @Test
    fun failedTravelAuditAllowsNewRequestAndAdapterDispatch() = runBlocking {
        val store = Store(pet()).apply {
            travels += LocalPendingTravelVideo(
                "owner-a",
                pet.petId,
                "travel-failed",
                "local-failed",
                "backend-failed",
                "Старая поездка",
                5,
                backendState = PendingBackendState.Failed,
                backendErrorCode = "GENERATION_FAILED",
            )
        }
        val dispatchedKeys = mutableListOf<String>()
        val adapter = object : DashboardTravelAdapter {
            override suspend fun queue(
                request: PendingTravelRequest,
                pet: PetDashboardState,
            ): PendingTravelGeneration {
                dispatchedKeys += request.requestKey
                return PendingTravelGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    localJobId = "travel-${request.requestKey}",
                    backendJobId = null,
                    createdAtEpochMillis = 10,
                )
            }
        }

        val result = DashboardDurableOperations(
            "owner-a", store, UnavailableDashboardOutfitAdapter(), adapter, nowEpochMillis = { 10 },
        ).acceptTravel(PendingTravelRequest("travel-new", "Новая поездка"), store.pet)

        assertTrue(result is DurableTravelResult.Queued)
        assertEquals(listOf("travel-new"), dispatchedKeys)
        assertEquals(listOf("travel-failed", "travel-new"), store.travels.map { it.requestKey })
        assertEquals(PendingBackendState.Failed, store.travels.first().backendState)
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
            pet = pet.copy(experience = pet.experience - pending.experienceCost)
            return OutfitAcceptanceResult.Applied
        }
        override suspend fun attachOutfitBackendJob(
            ownerId: String,
            requestKey: String,
            backendJobId: String,
        ): com.gigagochi.app.core.database.BackendJobAttachmentResult {
            val index = outfits.indexOfFirst { it.ownerId == ownerId && it.requestKey == requestKey }
            if (index < 0) return com.gigagochi.app.core.database.BackendJobAttachmentResult.PendingMissing
            val current = outfits[index]
            if (current.backendJobId != null && current.backendJobId != backendJobId) {
                return com.gigagochi.app.core.database.BackendJobAttachmentResult.Conflict
            }
            if (current.backendJobId == backendJobId) {
                return com.gigagochi.app.core.database.BackendJobAttachmentResult.AlreadyAttached
            }
            outfits[index] = current.copy(backendJobId = backendJobId)
            return com.gigagochi.app.core.database.BackendJobAttachmentResult.Attached
        }
        override suspend fun savePendingTravel(pending: LocalPendingTravelVideo): IdempotentInsertResult {
            if (travels.any { it.requestKey == pending.requestKey }) return IdempotentInsertResult.AlreadyPresent
            travels += pending
            return IdempotentInsertResult.Inserted
        }
    }

    private fun pet(experience: Int = 500) = PetDashboardState(
        "pet-a", "assets-a", "Ледяной дракон", "Тото", "baby", "Малыш", "idle",
        experience, 100, 100, 100, "Привет",
    )
}
