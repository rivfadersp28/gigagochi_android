package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
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
    private val stateStore: PendingBackendStateStore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun finalize(
        state: CreatePetState,
        foregroundHandled: Boolean = true,
    ): CreateFinalizationResult {
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
                keepPendingCreate = generated.backgroundGenerationPending || !foregroundHandled,
            )
            if (saved && generated.backgroundGenerationPending && foregroundHandled) {
                stateStore?.updateCreateBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.ForegroundReady,
                )
            }
            if (saved) CreateFinalizationResult.Success(pet) else CreateFinalizationResult.Failure
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CreateFinalizationResult.Failure
        }
    }
}

internal fun createdPetFromPending(
    pending: LocalPendingCreateGeneration,
    generated: GeneratedPetFixture,
): PetDashboardState? {
    val name = pending.name ?: return null
    if (pending.currentStep != FinalCreationStep ||
        pending.personality == null || pending.fear == null || pending.favoriteItem == null ||
        pending.petId != generated.petId
    ) {
        return null
    }
    return PetDashboardState(
        petId = pending.petId,
        assetSetId = generated.assetSetId,
        description = pending.description,
        name = name,
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
}
