package com.gigagochi.app.feature.create

import com.gigagochi.app.core.background.LocalNotificationEmitter
import com.gigagochi.app.core.background.ManualGenerationKind
import com.gigagochi.app.core.background.manualGenerationFailedNotification
import com.gigagochi.app.core.background.petReadyNotification
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.GenerationJobStatusDto

enum class DurableCreateRecoveryResult { Complete, Retry, Terminal }

class DurableCreateRecoveryCoordinator(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val stateStore: PendingBackendStateStore,
    private val api: AndroidFeatureService,
    private val notificationEmitter: LocalNotificationEmitter,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun recoverOnce(): DurableCreateRecoveryResult {
        val recovery = store.loadOwnerRecovery(ownerId)
        val pending = recovery.pendingCreates
            .filter { it.backendJobId != null }
            .maxByOrNull { it.updatedAtEpochMillis }
            ?: return DurableCreateRecoveryResult.Complete
        val backendJobId = requireNotNull(pending.backendJobId)
        val envelope = when (val polled = api.pollCreate(backendJobId)) {
            is FeatureApiResult.Failure -> return if (polled.failure.kind in RetryableFailures) {
                DurableCreateRecoveryResult.Retry
            } else {
                DurableCreateRecoveryResult.Terminal
            }
            is FeatureApiResult.Success -> polled.value
        }
        if (envelope.job.jobId != backendJobId) {
            stateStore.updateCreateBackendState(
                ownerId,
                pending.requestKey,
                PendingBackendState.OutcomeUnknown,
                "IDENTITY_MISMATCH",
            )
            return DurableCreateRecoveryResult.Terminal
        }
        if (envelope.job.status == GenerationJobStatusDto.Failed) {
            stateStore.updateCreateBackendState(
                ownerId,
                pending.requestKey,
                PendingBackendState.Failed,
                "GENERATION_FAILED",
            )
            return if (notificationEmitter.emit(
                    manualGenerationFailedNotification(
                        ManualGenerationKind.Create,
                        pending.requestKey,
                    ),
                )
            ) {
                DurableCreateRecoveryResult.Terminal
            } else {
                DurableCreateRecoveryResult.Retry
            }
        }
        val result = envelope.job.result ?: return DurableCreateRecoveryResult.Retry
        val media = api.media(result) ?: return protocolFailure(pending.requestKey)
        if (envelope.job.status == GenerationJobStatusDto.Running && media.videoUrl == null) {
            return DurableCreateRecoveryResult.Retry
        }
        val generated = GeneratedPetFixture(
            description = pending.description,
            petId = pending.petId,
            assetSetId = result.assetSetId,
            generatedMedia = media,
            backgroundGenerationPending = envelope.job.status != GenerationJobStatusDto.Succeeded,
        )
        val newPet = createdPetFromPending(pending, generated)
            ?: return DurableCreateRecoveryResult.Retry
        val current = recovery.petSnapshots.singleOrNull { it.pet.petId == pending.petId }
        if (current != null && current.pet.assetSetId != result.assetSetId) {
            return protocolFailure(pending.requestKey)
        }
        val snapshot = if (current == null) {
            OwnedPetSnapshot(ownerId, newPet, nowEpochMillis())
        } else {
            current.copy(
                pet = current.pet.copy(generatedMedia = media),
                updatedAtEpochMillis = nowEpochMillis(),
            )
        }
        val saved = if (current == null) {
            store.finalizeCreatedPet(snapshot, pending.requestKey, keepPendingCreate = true)
        } else {
            store.replacePetSnapshotIfAssetCurrent(snapshot)
        }
        if (!saved) return DurableCreateRecoveryResult.Retry

        if (pending.backendState != PendingBackendState.ForegroundReady) {
            if (!notificationEmitter.emit(petReadyNotification(pending.requestKey))) {
                return DurableCreateRecoveryResult.Retry
            }
            if (!stateStore.updateCreateBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.ForegroundReady,
                )
            ) {
                return DurableCreateRecoveryResult.Retry
            }
        }
        if (envelope.job.status != GenerationJobStatusDto.Succeeded) {
            return DurableCreateRecoveryResult.Retry
        }
        stateStore.updateCreateBackendState(
            ownerId,
            pending.requestKey,
            PendingBackendState.Ready,
        )
        return if (store.deletePendingCreate(ownerId, pending.requestKey)) {
            DurableCreateRecoveryResult.Complete
        } else {
            DurableCreateRecoveryResult.Retry
        }
    }

    private suspend fun protocolFailure(requestKey: String): DurableCreateRecoveryResult {
        stateStore.updateCreateBackendState(
            ownerId,
            requestKey,
            PendingBackendState.OutcomeUnknown,
            "BACKGROUND_RESULT_INVALID",
        )
        return DurableCreateRecoveryResult.Terminal
    }

    private companion object {
        val RetryableFailures = setOf(
            FeatureFailureKind.Network,
            FeatureFailureKind.Server,
            FeatureFailureKind.RefreshUnavailable,
            FeatureFailureKind.Storage,
            FeatureFailureKind.RateLimited,
            FeatureFailureKind.InProgress,
        )
    }
}
