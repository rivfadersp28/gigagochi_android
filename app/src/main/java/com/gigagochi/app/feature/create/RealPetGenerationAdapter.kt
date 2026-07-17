package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.BackendJobAttachmentResult
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.CreateJobRequestDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class FeatureAdapterException(val failure: FeatureFailure) : Exception("Feature request failed") {
    override fun toString(): String = "FeatureAdapterException(kind=${failure.kind},<redacted>)"
}

class RealPetGenerationAdapter(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val stateStore: PendingBackendStateStore,
    private val api: AndroidFeatureService,
    private val pollDelayMillis: Long = 2_000L,
    private val maxPollAttempts: Int = 180,
) : PetGenerationAdapter {
    override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
        val persisted = store.loadOwnerRecovery(ownerId).pendingCreates.singleOrNull {
            it.requestKey == request.requestKey && it.petId == request.petId
        } ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Storage))
        var backendJobId = persisted.backendJobId
        if (backendJobId == null) {
            if (persisted.backendState in setOf(
                    PendingBackendState.Dispatching,
                    PendingBackendState.OutcomeUnknown,
                    PendingBackendState.Failed,
                )
            ) {
                throw FeatureAdapterException(
                    FeatureFailure(
                        if (persisted.backendState == PendingBackendState.OutcomeUnknown ||
                            persisted.backendState == PendingBackendState.Dispatching
                        ) FeatureFailureKind.OutcomeUnknown else FeatureFailureKind.Conflict,
                        persisted.backendErrorCode,
                    ),
                )
            }
            require(
                stateStore.updateCreateBackendState(
                    ownerId,
                    request.requestKey,
                    PendingBackendState.Dispatching,
                ),
            )
            val submitted = when (
                val result = api.submitCreate(
                    CreateJobRequestDto(request.requestKey, request.petId, request.description),
                )
            ) {
                is FeatureApiResult.Success -> result.value
                is FeatureApiResult.Failure -> {
                    persistSubmitFailure(request.requestKey, result.failure)
                    throw FeatureAdapterException(result.failure)
                }
            }
            if (submitted.requestKey != request.requestKey || submitted.petId != request.petId) {
                stateStore.updateCreateBackendState(
                    ownerId,
                    request.requestKey,
                    PendingBackendState.OutcomeUnknown,
                    "IDENTITY_MISMATCH",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
            }
            backendJobId = submitted.job.jobId
            when (store.attachCreateBackendJob(ownerId, request.requestKey, backendJobId)) {
                BackendJobAttachmentResult.Attached,
                BackendJobAttachmentResult.AlreadyAttached,
                -> stateStore.updateCreateBackendState(
                    ownerId,
                    request.requestKey,
                    PendingBackendState.Attached,
                )
                BackendJobAttachmentResult.Conflict,
                BackendJobAttachmentResult.PendingMissing,
                -> {
                    stateStore.updateCreateBackendState(
                        ownerId,
                        request.requestKey,
                        PendingBackendState.OutcomeUnknown,
                        "ATTACH_FAILED",
                    )
                    throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.OutcomeUnknown))
                }
            }
        }
        repeat(maxPollAttempts) { attempt ->
            when (val polled = api.pollCreate(backendJobId)) {
                is FeatureApiResult.Failure -> throw FeatureAdapterException(polled.failure)
                is FeatureApiResult.Success -> {
                    val envelope = polled.value
                    requireMatchingJob(envelope, backendJobId)
                    when (envelope.job.status) {
                        GenerationJobStatusDto.Succeeded -> {
                            val result = envelope.job.result
                                ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
                            val media = api.media(result)
                                ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
                            stateStore.updateCreateBackendState(
                                ownerId,
                                request.requestKey,
                                PendingBackendState.Ready,
                            )
                            return GeneratedPetFixture(
                                description = request.description,
                                petId = request.petId,
                                assetSetId = result.assetSetId,
                                generatedMedia = media,
                            )
                        }
                        GenerationJobStatusDto.Failed -> {
                            stateStore.updateCreateBackendState(
                                ownerId,
                                request.requestKey,
                                PendingBackendState.Failed,
                                "GENERATION_FAILED",
                            )
                            throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Server))
                        }
                        GenerationJobStatusDto.Queued,
                        GenerationJobStatusDto.Running,
                        -> if (attempt + 1 < maxPollAttempts) delay(pollDelayMillis)
                    }
                }
            }
        }
        throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.InProgress))
    }

    private suspend fun persistSubmitFailure(requestKey: String, failure: FeatureFailure) {
        val state = when (failure.kind) {
            FeatureFailureKind.Network,
            FeatureFailureKind.Server,
            FeatureFailureKind.OutcomeUnknown,
            -> PendingBackendState.OutcomeUnknown
            FeatureFailureKind.Storage,
            FeatureFailureKind.SessionInvalid,
            FeatureFailureKind.RefreshUnavailable,
            FeatureFailureKind.RateLimited,
            FeatureFailureKind.InProgress,
            -> PendingBackendState.Retryable
            else -> PendingBackendState.Failed
        }
        stateStore.updateCreateBackendState(ownerId, requestKey, state, failure.code)
    }

    private fun requireMatchingJob(envelope: GenerationEnvelopeDto, expectedJobId: String) {
        if (envelope.job.jobId != expectedJobId) {
            throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
        }
        // Poll identity is intentionally owner-scoped Room state; nullable GET fields are ignored.
    }
}
