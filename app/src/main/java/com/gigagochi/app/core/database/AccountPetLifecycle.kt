package com.gigagochi.app.core.database

import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.CancellationException

sealed interface AccountStartupDestination {
    data class Create(
        val pending: LocalPendingCreateGeneration? = null,
    ) : AccountStartupDestination
    data class Dashboard(
        val pet: PetDashboardState,
        val pendingOutfit: LocalPendingOutfit?,
        val pendingTravel: LocalPendingTravelVideo?,
        val storyReceipts: List<InteractiveStoryReceipt>,
        val travelPresentation: LocalTravelVideoAsset? = null,
    ) : AccountStartupDestination
    data object Failure : AccountStartupDestination
}

class AccountPetLifecycle(
    private val store: OwnerRecoveryStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun startup(ownerId: String): AccountStartupDestination = try {
        val recovery = store.loadOwnerRecovery(ownerId)
        val latest = recovery.petSnapshots.maxByOrNull { it.updatedAtEpochMillis }
        latest?.let { snapshot ->
            AccountStartupDestination.Dashboard(
                pet = snapshot.pet,
                pendingOutfit = recovery.pendingOutfits
                    .filter {
                        it.petId == snapshot.pet.petId &&
                            it.backendState != PendingBackendState.Failed
                    }
                    .maxByOrNull { it.acceptedAtEpochMillis },
                pendingTravel = recovery.pendingTravels
                    .filter {
                        it.petId == snapshot.pet.petId &&
                            it.backendState != PendingBackendState.Failed
                    }
                    .maxByOrNull { it.acceptedAtEpochMillis },
                travelPresentation = recovery.travelVideoAssets
                    .filter { it.petId == snapshot.pet.petId && it.consumedAtEpochMillis != null }
                    .maxByOrNull { it.consumedAtEpochMillis ?: Long.MIN_VALUE },
                storyReceipts = recovery.storyReceipts.filter {
                    it.petId == snapshot.pet.petId
                },
            )
        } ?: AccountStartupDestination.Create(
            pending = recovery.pendingCreates.maxByOrNull { it.updatedAtEpochMillis },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AccountStartupDestination.Failure
    }

    suspend fun save(ownerId: String, pet: PetDashboardState): Boolean = try {
        store.replacePetSnapshotIfAssetCurrent(
            OwnedPetSnapshot(
                ownerId = ownerId,
                pet = pet,
                updatedAtEpochMillis = nowEpochMillis(),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
