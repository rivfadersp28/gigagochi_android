package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.GenerationJobStatusDto
import kotlinx.coroutines.delay

class CreateBackgroundMediaCoordinator(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val stateStore: PendingBackendStateStore,
    private val api: AndroidFeatureService,
    private val pollDelayMillis: Long = 3_000L,
    private val maxPollAttempts: Int = 600,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onMediaReady: suspend (PetDashboardState) -> Unit = {},
) {
    suspend fun recover(petId: String) {
        repeat(maxPollAttempts) { attempt ->
            val recovery = store.loadOwnerRecovery(ownerId)
            val pending = recovery.pendingCreates
                .filter { it.petId == petId && it.backendJobId != null }
                .maxByOrNull { it.updatedAtEpochMillis }
                ?: return
            val backendJobId = requireNotNull(pending.backendJobId)
            when (val polled = api.pollCreate(backendJobId)) {
                is FeatureApiResult.Failure -> {
                    if (polled.failure.kind !in RetryableFailures) return
                }
                is FeatureApiResult.Success -> {
                    val envelope = polled.value
                    if (envelope.job.jobId != backendJobId) {
                        stateStore.updateCreateBackendState(
                            ownerId,
                            pending.requestKey,
                            PendingBackendState.OutcomeUnknown,
                            "IDENTITY_MISMATCH",
                        )
                        return
                    }
                    when (envelope.job.status) {
                        GenerationJobStatusDto.Failed -> {
                            stateStore.updateCreateBackendState(
                                ownerId,
                                pending.requestKey,
                                PendingBackendState.Failed,
                                "BACKGROUND_GENERATION_FAILED",
                            )
                            return
                        }
                        GenerationJobStatusDto.Succeeded -> {
                            val result = envelope.job.result ?: return protocolFailure(pending.requestKey)
                            val media = api.media(result) ?: return protocolFailure(pending.requestKey)
                            val snapshot = recovery.petSnapshots.singleOrNull {
                                it.pet.petId == petId && it.pet.assetSetId == result.assetSetId
                            } ?: return protocolFailure(pending.requestKey)
                            val updated = snapshot.copy(
                                pet = snapshot.pet.copy(generatedMedia = media),
                                updatedAtEpochMillis = nowEpochMillis(),
                            )
                            if (!store.replacePetSnapshotIfAssetCurrent(updated)) return
                            stateStore.updateCreateBackendState(
                                ownerId,
                                pending.requestKey,
                                PendingBackendState.Ready,
                            )
                            if (!store.deletePendingCreate(ownerId, pending.requestKey)) return
                            onMediaReady(updated.pet)
                            return
                        }
                        GenerationJobStatusDto.Queued,
                        GenerationJobStatusDto.Running,
                        -> Unit
                    }
                }
            }
            if (attempt + 1 < maxPollAttempts) delay(pollDelayMillis)
        }
    }

    private suspend fun protocolFailure(requestKey: String) {
        stateStore.updateCreateBackendState(
            ownerId,
            requestKey,
            PendingBackendState.OutcomeUnknown,
            "BACKGROUND_RESULT_INVALID",
        )
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
