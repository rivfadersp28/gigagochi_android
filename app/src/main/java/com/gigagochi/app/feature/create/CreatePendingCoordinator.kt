package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.LocalOperationResult
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingCreateStage
import kotlinx.coroutines.CancellationException

class CreatePendingCoordinator(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun persist(state: CreatePetState): LocalOperationResult<LocalPendingCreateGeneration> {
        val request = state.pending ?: return LocalOperationResult.Failure
        if (state.step !in 1..FinalCreationStep) return LocalOperationResult.Failure
        val pending = LocalPendingCreateGeneration(
            ownerId = ownerId,
            petId = request.petId,
            requestKey = request.requestKey,
            backendJobId = null,
            stage = when (state.generation) {
                GenerationStatus.Idle -> PendingCreateStage.Requested
                else -> PendingCreateStage.Generating
            },
            description = state.description,
            name = state.answers.getOrNull(1),
            personality = state.answers.getOrNull(2),
            fear = state.answers.getOrNull(3),
            favoriteItem = state.answers.getOrNull(4),
            currentStep = state.step,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        return try {
            val existing = store.loadOwnerRecovery(ownerId).pendingCreates.firstOrNull {
                it.requestKey == request.requestKey
            }
            val durable = pending.copy(
                backendJobId = existing?.backendJobId,
                backendState = existing?.backendState ?: pending.backendState,
                backendErrorCode = existing?.backendErrorCode,
            )
            store.savePendingCreate(durable)
            LocalOperationResult.Success(durable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LocalOperationResult.Failure
        }
    }

    fun restore(pending: LocalPendingCreateGeneration): CreatePetState {
        val answers = listOfNotNull(
            pending.description,
            pending.name,
            pending.personality,
            pending.fear,
            pending.favoriteItem,
        )
        val canResumeGeneration = pending.currentStep == FinalCreationStep &&
            pending.backendState in setOf(
                com.gigagochi.app.core.database.PendingBackendState.Pending,
                com.gigagochi.app.core.database.PendingBackendState.Retryable,
                com.gigagochi.app.core.database.PendingBackendState.Attached,
                com.gigagochi.app.core.database.PendingBackendState.Ready,
            )
        return CreatePetState(
            step = pending.currentStep,
            answers = answers,
            description = pending.description,
            backgroundPhase = CreationBackgroundPhase.Formed,
            generation = if (canResumeGeneration) GenerationStatus.Running else {
                GenerationStatus.Error(
                    "Создание питомца сохранено и продолжится после подключения сервера.",
                )
            },
            generationAttempt = if (canResumeGeneration) 1 else 0,
            pending = PendingPetGeneration(
                petId = pending.petId,
                description = pending.description,
                requestKey = pending.requestKey,
            ),
        )
    }
}
