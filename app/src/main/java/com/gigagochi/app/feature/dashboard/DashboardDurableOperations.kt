package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.BackendJobAttachmentResult
import com.gigagochi.app.core.database.IdempotentInsertResult
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OutfitAcceptanceResult
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.CancellationException

sealed interface DurableOutfitResult {
    data class Queued(
        val pending: PendingOutfitGeneration,
        val acceptedPet: PetDashboardState,
    ) : DurableOutfitResult
    data class PersistedButQueueFailed(
        val pending: PendingOutfitGeneration,
        val acceptedPet: PetDashboardState,
    ) : DurableOutfitResult
    data object Unavailable : DurableOutfitResult
    data object Failure : DurableOutfitResult
}

sealed interface DurableTravelResult {
    data class Queued(val pending: PendingTravelGeneration) : DurableTravelResult
    data class PersistedButQueueFailed(val pending: PendingTravelGeneration) : DurableTravelResult
    data object Unavailable : DurableTravelResult
    data object Failure : DurableTravelResult
}

class DashboardDurableOperations(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val outfitAdapter: DashboardOutfitAdapter,
    private val travelAdapter: DashboardTravelAdapter,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun acceptOutfit(
        request: PendingOutfitRequest,
        pet: PetDashboardState,
    ): DurableOutfitResult {
        return try {
            val existingRecovery = store.loadOwnerRecovery(ownerId)
            val existing = existingRecovery.pendingOutfits.firstOrNull {
                it.petId == pet.petId && it.backendState != PendingBackendState.Failed
            }
            if (existing != null) {
                val persistedPet = existingRecovery.petSnapshots.firstOrNull {
                    it.pet.petId == pet.petId
                }?.pet ?: return DurableOutfitResult.Failure
                if (existing.backendJobId != null) {
                    return DurableOutfitResult.Queued(existing.toUi(), persistedPet)
                }
                if (existing.backendState !in setOf(
                        PendingBackendState.Pending,
                        PendingBackendState.Retryable,
                    )
                ) {
                    return DurableOutfitResult.PersistedButQueueFailed(existing.toUi(), persistedPet)
                }
                if (!outfitAdapter.isAvailable) return DurableOutfitResult.Unavailable
                val retryRequest = PendingOutfitRequest(existing.requestKey, existing.prompt)
                val queued = try {
                    outfitAdapter.queue(retryRequest, persistedPet)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return DurableOutfitResult.PersistedButQueueFailed(existing.toUi(), persistedPet)
                }
                queued.backendJobId?.let { backendId ->
                    when (store.attachOutfitBackendJob(ownerId, existing.requestKey, backendId)) {
                        BackendJobAttachmentResult.Conflict,
                        BackendJobAttachmentResult.PendingMissing,
                        -> return DurableOutfitResult.Failure
                        else -> Unit
                    }
                }
                return DurableOutfitResult.Queued(
                    existing.copy(backendJobId = queued.backendJobId).toUi(queued.displayItem),
                    persistedPet,
                )
            }
            if (!outfitAdapter.isAvailable) return DurableOutfitResult.Unavailable
            val local = LocalPendingOutfit(
                ownerId = ownerId,
                petId = pet.petId,
                requestKey = request.requestKey,
                localJobId = "outfit-${request.requestKey}",
                backendJobId = null,
                prompt = request.prompt,
                baseAssetSetId = pet.assetSetId,
                acceptedAtEpochMillis = nowEpochMillis(),
            )
            when (store.acceptOutfit(local)) {
                OutfitAcceptanceResult.PetMissing,
                OutfitAcceptanceResult.InsufficientExperience,
                -> return DurableOutfitResult.Failure
                OutfitAcceptanceResult.Applied,
                OutfitAcceptanceResult.AlreadyApplied,
                -> Unit
            }
            val recovery = store.loadOwnerRecovery(ownerId)
            val persisted = recovery.pendingOutfits.firstOrNull {
                it.requestKey == request.requestKey && it.petId == pet.petId
            } ?: return DurableOutfitResult.Failure
            val acceptedPet = recovery.petSnapshots.firstOrNull { it.pet.petId == pet.petId }?.pet
                ?: return DurableOutfitResult.Failure
            if (persisted.backendJobId != null) {
                return DurableOutfitResult.Queued(persisted.toUi(), acceptedPet)
            }
            val queued = try {
                outfitAdapter.queue(request, acceptedPet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return DurableOutfitResult.PersistedButQueueFailed(persisted.toUi(), acceptedPet)
            }
            queued.backendJobId?.let { backendId ->
                when (store.attachOutfitBackendJob(ownerId, request.requestKey, backendId)) {
                    BackendJobAttachmentResult.Conflict,
                    BackendJobAttachmentResult.PendingMissing,
                    -> return DurableOutfitResult.Failure
                    else -> Unit
                }
            }
            DurableOutfitResult.Queued(
                persisted.copy(backendJobId = queued.backendJobId).toUi(queued.displayItem),
                acceptedPet,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DurableOutfitResult.Failure
        }
    }

    suspend fun acceptTravel(
        request: PendingTravelRequest,
        pet: PetDashboardState,
    ): DurableTravelResult {
        return try {
            val recovery = store.loadOwnerRecovery(ownerId)
            val existing = recovery.pendingTravels.firstOrNull {
                it.petId == pet.petId && it.backendState != PendingBackendState.Failed
            }
            if (existing != null) {
                return if (existing.backendJobId != null) {
                    DurableTravelResult.Queued(existing.toUi())
                } else {
                    DurableTravelResult.PersistedButQueueFailed(existing.toUi())
                }
            }
            if (recovery.travelVideoAssets.any {
                    it.petId == pet.petId && it.requestKey == request.requestKey
                }
            ) {
                return DurableTravelResult.Failure
            }
            if (!travelAdapter.isAvailable) return DurableTravelResult.Unavailable
            val local = LocalPendingTravelVideo(
                ownerId = ownerId,
                petId = pet.petId,
                requestKey = request.requestKey,
                localJobId = "travel-${request.requestKey}",
                backendJobId = null,
                prompt = request.prompt,
                acceptedAtEpochMillis = nowEpochMillis(),
            )
            store.savePendingTravel(local)
            val persisted = store.loadOwnerRecovery(ownerId).pendingTravels.firstOrNull {
                it.requestKey == request.requestKey && it.petId == pet.petId
            } ?: return DurableTravelResult.Failure
            if (persisted.backendJobId != null) return DurableTravelResult.Queued(persisted.toUi())
            val queued = try {
                travelAdapter.queue(request, pet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return DurableTravelResult.PersistedButQueueFailed(persisted.toUi())
            }
            queued.backendJobId?.let { backendId ->
                when (store.attachTravelBackendJob(ownerId, request.requestKey, backendId)) {
                    BackendJobAttachmentResult.Conflict,
                    BackendJobAttachmentResult.PendingMissing,
                    -> return DurableTravelResult.Failure
                    else -> Unit
                }
            }
            DurableTravelResult.Queued(persisted.copy(backendJobId = queued.backendJobId).toUi())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DurableTravelResult.Failure
        }
    }
}

fun LocalPendingOutfit.toUi(displayItem: String = canonicalOutfitDisplayItem(prompt)) =
    PendingOutfitGeneration(
        petId = petId,
        requestKey = requestKey,
        prompt = prompt,
        displayItem = displayItem,
        localJobId = localJobId,
        backendJobId = backendJobId,
        createdAtEpochMillis = acceptedAtEpochMillis,
    )

fun LocalPendingTravelVideo.toUi() = PendingTravelGeneration(
    petId = petId,
    requestKey = requestKey,
    prompt = prompt,
    localJobId = localJobId,
    backendJobId = backendJobId,
    createdAtEpochMillis = acceptedAtEpochMillis,
)
