package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.DashboardOutcomeStore
import com.gigagochi.app.core.database.OutfitOutcomeApplicationResult
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.database.TravelAssetConsumptionResult
import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.CancellationException

sealed interface DashboardOutcomeRecoveryResult {
    data class Changed(
        val pet: PetDashboardState,
    ) : DashboardOutcomeRecoveryResult
    data object Unchanged : DashboardOutcomeRecoveryResult
    data object Conflict : DashboardOutcomeRecoveryResult
    data object StorageFailure : DashboardOutcomeRecoveryResult
}

class DashboardOutcomeApplicationCoordinator(
    private val ownerId: String,
    private val recoveryStore: OwnerRecoveryStore,
    private val outcomeStore: DashboardOutcomeStore,
    private val stateStore: PendingBackendStateStore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun applyReady(petId: String): DashboardOutcomeRecoveryResult {
        return try {
        val before = recoveryStore.loadOwnerRecovery(ownerId)
        var changed = false
        for (pending in before.pendingOutfits.filter {
            it.petId == petId && it.backendState == PendingBackendState.Ready
        }) {
            when (outcomeStore.applyOutfitOutcome(ownerId, petId, pending.requestKey)) {
                is OutfitOutcomeApplicationResult.Applied -> changed = true
                is OutfitOutcomeApplicationResult.AlreadyApplied -> Unit
                OutfitOutcomeApplicationResult.NotReady -> Unit
                OutfitOutcomeApplicationResult.Conflict -> {
                    stateStore?.markOutfitApplyConflict(ownerId, pending.requestKey)
                    return DashboardOutcomeRecoveryResult.Conflict
                }
            }
        }
        for (pending in before.pendingTravels.filter {
            it.petId == petId && it.backendState == PendingBackendState.Ready
        }) {
            when (
                outcomeStore.consumeTravelAsset(
                    ownerId,
                    petId,
                    pending.requestKey,
                    nowEpochMillis(),
                )
            ) {
                is TravelAssetConsumptionResult.Consumed -> changed = true
                is TravelAssetConsumptionResult.AlreadyConsumed -> Unit
                TravelAssetConsumptionResult.NotReady -> Unit
                TravelAssetConsumptionResult.Conflict -> {
                    stateStore?.markTravelApplyConflict(ownerId, pending.requestKey)
                    return DashboardOutcomeRecoveryResult.Conflict
                }
            }
        }
        if (!changed) return DashboardOutcomeRecoveryResult.Unchanged
        val after = recoveryStore.loadOwnerRecovery(ownerId)
        val pet = after.petSnapshots.firstOrNull { it.pet.petId == petId }?.pet
            ?: return DashboardOutcomeRecoveryResult.Conflict
        DashboardOutcomeRecoveryResult.Changed(pet = pet)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DashboardOutcomeRecoveryResult.StorageFailure
        }
    }
}
