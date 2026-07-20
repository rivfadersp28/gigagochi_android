package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.model.PetDashboardState

sealed interface CreateFinalizationResult {
    data class Success(val pet: PetDashboardState) : CreateFinalizationResult
    data object Failure : CreateFinalizationResult
}

class CreateFinalizationCoordinator(
    private val ownerId: String,
    @Suppress("unused")
    private val lifecycle: AccountPetLifecycle,
    private val store: OwnerRecoveryStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun finalize(state: CreatePetState): CreateFinalizationResult {
        val generated = (state.generation as? GenerationStatus.Ready)?.pet
            ?: return CreateFinalizationResult.Failure
        if (!state.isFinal || state.answers.size < FinalCreationStep) {
            return CreateFinalizationResult.Failure
        }
        val pending = state.pending ?: return CreateFinalizationResult.Failure
        if (pending.requestKey.isBlank() || pending.petId.isBlank()) {
            return CreateFinalizationResult.Failure
        }
        val pet = PetDashboardState(
            petId = pending.petId,
            assetSetId = generated.assetSetId,
            description = generated.description,
            name = state.answers[1],
            stage = "baby",
            stageLabel = "Малыш",
            mood = "idle",
            experience = 0,
            hunger = 100,
            happiness = 100,
            energy = 100,
            message = "Как тебя зовут?",
            generatedMedia = generated.generatedMedia,
        )
        return try {
            val saved = store.finalizeCreatedPet(
                OwnedPetSnapshot(ownerId, pet, nowEpochMillis()),
                pending.requestKey,
                keepPendingCreate = generated.backgroundGenerationPending,
            )
            if (saved) CreateFinalizationResult.Success(pet) else CreateFinalizationResult.Failure
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CreateFinalizationResult.Failure
        }
    }
}
