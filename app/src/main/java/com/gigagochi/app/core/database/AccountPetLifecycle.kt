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
        val firstSession: LocalFirstSession? = null,
        val pendingChat: LocalPendingChat? = null,
        val queuedChat: LocalPendingChat? = null,
    ) : AccountStartupDestination
    data object Failure : AccountStartupDestination
}

class AccountPetLifecycle(
    private val store: OwnerRecoveryStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun startup(
        ownerId: String,
        preferredPetId: String? = null,
    ): AccountStartupDestination = try {
        val recovery = store.loadOwnerRecovery(ownerId)
        val latest = preferredPetId?.let { preferred ->
            recovery.petSnapshots.singleOrNull { it.pet.petId == preferred }
        } ?: recovery.petSnapshots.maxByOrNull { it.updatedAtEpochMillis }
        latest?.let { snapshot ->
            val chatQueue = recovery.pendingChats
                .filter { it.petId == snapshot.pet.petId }
                .sortedWith(
                    compareBy(
                        LocalPendingChat::createdAtEpochMillis,
                        LocalPendingChat::requestKey,
                    ),
                )
                .take(MaxDashboardChatQueueSize)
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
                storyReceipts = recovery.storyReceipts.filter {
                    it.petId == snapshot.pet.petId
                },
                firstSession = recovery.firstSessions.singleOrNull {
                    it.petId == snapshot.pet.petId
                },
                pendingChat = chatQueue.getOrNull(0),
                queuedChat = chatQueue.getOrNull(1),
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

private const val MaxDashboardChatQueueSize = 2
