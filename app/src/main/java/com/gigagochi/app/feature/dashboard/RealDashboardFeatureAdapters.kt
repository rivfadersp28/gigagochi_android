package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.BackendJobAttachmentResult
import com.gigagochi.app.core.database.FeatureMediaOutcomeStore
import com.gigagochi.app.core.database.LocalOutfitMediaOutcome
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.OwnerRecoveryStore
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingBackendStateStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.ChatRequestDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.OutfitJobRequestDto
import com.gigagochi.app.core.network.OutfitSimplifyRequestDto
import com.gigagochi.app.core.network.TravelVideoRequestDto
import com.gigagochi.app.core.network.TravelVideoStatusDto
import com.gigagochi.app.core.network.toFeaturePetDto
import com.gigagochi.app.feature.create.FeatureAdapterException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

class RealDashboardChatAdapter(
    private val api: AndroidFeatureService,
) : DashboardChatAdapter {
    override suspend fun reply(request: PendingChatRequest, pet: PetDashboardState): String =
        when (val result = api.chat(ChatRequestDto(request.requestKey, request.message, pet.toFeaturePetDto()))) {
            is FeatureApiResult.Success -> result.value.reply.takeIf { it.isNotBlank() }
                ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
            is FeatureApiResult.Failure -> throw FeatureAdapterException(result.failure)
        }
}

class DeterministicLocalDashboardFeedAdapter : DashboardFeedAdapter {
    override suspend fun reply(request: PendingFeedRequest, pet: PetDashboardState): String =
        when (request.food) {
            DashboardFood.BerryBowl -> BerryReply
            DashboardFood.LeafCrunch -> LeafReply
        }
}

class RealDashboardOutfitAdapter(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val stateStore: PendingBackendStateStore,
    private val outcomeStore: FeatureMediaOutcomeStore,
    private val api: AndroidFeatureService,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onJobAttached: () -> Unit = {},
) : DashboardOutfitAdapter {
    override suspend fun queue(
        request: PendingOutfitRequest,
        pet: PetDashboardState,
    ): PendingOutfitGeneration {
        val pending = store.loadOwnerRecovery(ownerId).pendingOutfits.singleOrNull {
            it.requestKey == request.requestKey && it.petId == pet.petId
        } ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Storage))
        pending.backendJobId?.let {
            pollOnce(pending)
            return pending.toUi(pending.preparedDisplayItem ?: canonicalOutfitDisplayItem(pending.prompt))
        }
        val idle = pet.requiredMoodUrl("idle")
        val sad = pet.requiredMoodUrl("sad")
        val happy = pet.requiredMoodUrl("happy")
        rejectUnsafeRedispatch(pending.backendState, pending.backendErrorCode)
        require(
            stateStore.updateOutfitBackendState(
                ownerId,
                request.requestKey,
                PendingBackendState.Dispatching,
            ),
        )
        val simplified = when (
            val result = api.simplifyOutfit(
                OutfitSimplifyRequestDto(request.requestKey, request.prompt, pet.description),
            )
        ) {
            is FeatureApiResult.Success -> result.value
            is FeatureApiResult.Failure -> {
                persistSubmitFailure(request.requestKey, result.failure)
                throw FeatureAdapterException(result.failure)
            }
        }
        if (!stateStore.prepareOutfitDisplayItem(ownerId, request.requestKey, simplified.displayItem)) {
            val recovered = store.loadOwnerRecovery(ownerId).pendingOutfits.singleOrNull {
                it.requestKey == request.requestKey
            }
            if (recovered?.preparedDisplayItem != simplified.displayItem) {
                stateStore.updateOutfitBackendState(
                    ownerId,
                    request.requestKey,
                    PendingBackendState.OutcomeUnknown,
                    "DISPLAY_ITEM_CONFLICT",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.OutcomeUnknown))
            }
        }
        val submitted = when (
            val result = api.submitOutfit(
                OutfitJobRequestDto(
                    request.requestKey,
                    pet.petId,
                    simplified.generationDescription,
                    idle,
                    sad,
                    happy,
                ),
            )
        ) {
            is FeatureApiResult.Success -> result.value
            is FeatureApiResult.Failure -> {
                persistSubmitFailure(request.requestKey, result.failure)
                throw FeatureAdapterException(result.failure)
            }
        }
        if (!submitIdentityMatches(submitted, request.requestKey, pet.petId)) {
            stateStore.updateOutfitBackendState(
                ownerId,
                request.requestKey,
                PendingBackendState.OutcomeUnknown,
                "IDENTITY_MISMATCH",
            )
            throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))
        }
        attach(request.requestKey, submitted.job.jobId)
        onJobAttached()
        val attached = pending.copy(
            backendJobId = submitted.job.jobId,
            backendState = PendingBackendState.Attached,
        )
        persistReadyIfPresent(attached, simplified.displayItem, submitted)
        return attached.toUi(simplified.displayItem)
    }

    suspend fun pollOnce(pending: LocalPendingOutfit): Boolean {
        val jobId = pending.backendJobId ?: return false
        when (val result = api.pollOutfit(jobId)) {
            is FeatureApiResult.Failure -> if (result.failure.kind != FeatureFailureKind.Network) {
                throw FeatureAdapterException(result.failure)
            }
            is FeatureApiResult.Success -> {
                if (result.value.job.jobId != jobId) protocolFailure()
                persistReadyIfPresent(
                    pending,
                    pending.preparedDisplayItem ?: canonicalOutfitDisplayItem(pending.prompt),
                    result.value,
                )
                return result.value.job.status in setOf(
                    GenerationJobStatusDto.Succeeded,
                    GenerationJobStatusDto.Failed,
                )
            }
        }
        return false
    }

    private suspend fun persistReadyIfPresent(
        pending: LocalPendingOutfit,
        displayItem: String,
        envelope: GenerationEnvelopeDto,
    ) {
        when (envelope.job.status) {
            GenerationJobStatusDto.Succeeded -> {
                val result = envelope.job.result ?: protocolFailure()
                val media = api.media(result) ?: protocolFailure()
                outcomeStore.saveOutfitMediaOutcome(
                    LocalOutfitMediaOutcome(
                        ownerId,
                        pending.petId,
                        pending.requestKey,
                        requireNotNull(pending.backendJobId ?: envelope.job.jobId),
                        displayItem,
                        result.assetSetId,
                        media,
                        nowEpochMillis(),
                    ),
                )
                stateStore.updateOutfitBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.Ready,
                )
            }
            GenerationJobStatusDto.Failed -> {
                stateStore.updateOutfitBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.Failed,
                    "GENERATION_FAILED",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Server, "GENERATION_FAILED"))
            }
            else -> Unit
        }
    }

    private suspend fun attach(requestKey: String, jobId: String) {
        when (store.attachOutfitBackendJob(ownerId, requestKey, jobId)) {
            BackendJobAttachmentResult.Attached,
            BackendJobAttachmentResult.AlreadyAttached,
            -> stateStore.updateOutfitBackendState(ownerId, requestKey, PendingBackendState.Attached)
            else -> {
                stateStore.updateOutfitBackendState(
                    ownerId,
                    requestKey,
                    PendingBackendState.OutcomeUnknown,
                    "ATTACH_FAILED",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.OutcomeUnknown))
            }
        }
    }

    private suspend fun persistSubmitFailure(requestKey: String, failure: FeatureFailure) {
        stateStore.updateOutfitBackendState(
            ownerId,
            requestKey,
            submitFailureState(failure),
            failure.code,
        )
    }
}

class RealDashboardTravelAdapter(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val stateStore: PendingBackendStateStore,
    private val outcomeStore: FeatureMediaOutcomeStore,
    private val api: AndroidFeatureService,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val onJobAttached: () -> Unit = {},
) : DashboardTravelAdapter {
    override suspend fun queue(
        request: PendingTravelRequest,
        pet: PetDashboardState,
    ): PendingTravelGeneration {
        val pending = store.loadOwnerRecovery(ownerId).pendingTravels.singleOrNull {
            it.requestKey == request.requestKey && it.petId == pet.petId
        } ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Storage))
        pending.backendJobId?.let {
            pollOnce(pending)
            return pending.toUi()
        }
        rejectUnsafeRedispatch(pending.backendState, pending.backendErrorCode)
        require(
            stateStore.updateTravelBackendState(
                ownerId,
                request.requestKey,
                PendingBackendState.Dispatching,
            ),
        )
        val submitted = when (
            val result = api.submitTravel(
                TravelVideoRequestDto(request.requestKey, pet.toFeaturePetDto(), request.prompt),
            )
        ) {
            is FeatureApiResult.Success -> result.value
            is FeatureApiResult.Failure -> {
                stateStore.updateTravelBackendState(
                    ownerId,
                    request.requestKey,
                    submitFailureState(result.failure),
                    result.failure.code,
                )
                throw FeatureAdapterException(result.failure)
            }
        }
        attach(request.requestKey, submitted.jobId)
        onJobAttached()
        val attached = pending.copy(
            backendJobId = submitted.jobId,
            backendState = PendingBackendState.Attached,
        )
        persistReadyIfPresent(attached, submitted)
        return attached.toUi()
    }

    suspend fun pollOnce(pending: LocalPendingTravelVideo): Boolean {
        val jobId = pending.backendJobId ?: return false
        when (val result = api.pollTravel(jobId)) {
            is FeatureApiResult.Failure -> if (result.failure.kind != FeatureFailureKind.Network) {
                throw FeatureAdapterException(result.failure)
            }
            is FeatureApiResult.Success -> {
                if (result.value.jobId != jobId) protocolFailure()
                persistReadyIfPresent(pending, result.value)
                return result.value.status in setOf(
                    TravelVideoStatusDto.Ready,
                    TravelVideoStatusDto.Failed,
                )
            }
        }
        return false
    }

    private suspend fun persistReadyIfPresent(
        pending: LocalPendingTravelVideo,
        result: com.gigagochi.app.core.network.TravelVideoDto,
    ) {
        when (result.status) {
            TravelVideoStatusDto.Ready -> {
                val video = api.resolveMediaUrl(result.videoUrl) ?: protocolFailure()
                val image = result.imageUrl?.let { api.resolveMediaUrl(it) ?: protocolFailure() }
                outcomeStore.saveTravelVideoAsset(
                    LocalTravelVideoAsset(
                        ownerId,
                        pending.petId,
                        pending.requestKey,
                        requireNotNull(pending.backendJobId ?: result.jobId),
                        pending.prompt,
                        result.title,
                        result.scenario,
                        image,
                        video,
                        nowEpochMillis(),
                    ),
                )
                stateStore.updateTravelBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.Ready,
                )
            }
            TravelVideoStatusDto.Failed -> {
                stateStore.updateTravelBackendState(
                    ownerId,
                    pending.requestKey,
                    PendingBackendState.Failed,
                    "GENERATION_FAILED",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Server, "GENERATION_FAILED"))
            }
            else -> Unit
        }
    }

    private suspend fun attach(requestKey: String, jobId: String) {
        when (store.attachTravelBackendJob(ownerId, requestKey, jobId)) {
            BackendJobAttachmentResult.Attached,
            BackendJobAttachmentResult.AlreadyAttached,
            -> stateStore.updateTravelBackendState(ownerId, requestKey, PendingBackendState.Attached)
            else -> {
                stateStore.updateTravelBackendState(
                    ownerId,
                    requestKey,
                    PendingBackendState.OutcomeUnknown,
                    "ATTACH_FAILED",
                )
                throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.OutcomeUnknown))
            }
        }
    }
}

class ForegroundRecoverySignal {
    private val events = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    fun request() {
        events.tryEmit(Unit)
    }

    suspend fun collect(block: suspend () -> Unit) = events.collectLatest { block() }
}

class ForegroundPendingRecoveryCoordinator(
    private val ownerId: String,
    private val store: OwnerRecoveryStore,
    private val outfit: RealDashboardOutfitAdapter,
    private val travel: RealDashboardTravelAdapter,
    private val signal: ForegroundRecoverySignal,
    private val pollDelayMillis: Long = 3_000L,
    private val maxPollAttempts: Int = 200,
    private val outcomeApplication: DashboardOutcomeApplicationCoordinator? = null,
    private val onOutcomeApplied: suspend (DashboardOutcomeRecoveryResult.Changed) -> Unit = {},
    private val onOutcomeConflict: suspend () -> Unit = {},
    private val onTerminalFailure: suspend () -> Unit = {},
) {
    suspend fun watch(petId: String) {
        signal.collect { recoverForeground(petId) }
    }

    suspend fun recoverForeground(petId: String) {
        val initialTerminalFailures = terminalFailureKeys(
            store.loadOwnerRecovery(ownerId),
            petId,
        )
        repeat(maxPollAttempts) { attempt ->
            var recovery = store.loadOwnerRecovery(ownerId)
            val pet = recovery.petSnapshots.firstOrNull { it.pet.petId == petId }?.pet
            if (pet != null) {
                recovery.pendingOutfits.filter {
                    it.petId == petId && it.backendJobId == null &&
                        it.backendState in setOf(
                            PendingBackendState.Pending,
                            PendingBackendState.Retryable,
                        )
                }.forEach {
                    cancellationSafe {
                        outfit.queue(PendingOutfitRequest(it.requestKey, it.prompt), pet)
                    }
                }
                recovery.pendingTravels.filter {
                    it.petId == petId && it.backendJobId == null &&
                        it.backendState in setOf(
                            PendingBackendState.Pending,
                            PendingBackendState.Retryable,
                        )
                }.forEach {
                    cancellationSafe {
                        travel.queue(PendingTravelRequest(it.requestKey, it.prompt), pet)
                    }
                }
                recovery = store.loadOwnerRecovery(ownerId)
            }
            applyReadyOutcomes(petId)
            recovery = store.loadOwnerRecovery(ownerId)
            if ((terminalFailureKeys(recovery, petId) - initialTerminalFailures).isNotEmpty()) {
                onTerminalFailure()
                return
            }
            val pendingOutfits = recovery.pendingOutfits.filter {
                it.petId == petId && it.backendJobId != null &&
                    it.backendState !in setOf(PendingBackendState.Ready, PendingBackendState.Failed)
            }
            val pendingTravels = recovery.pendingTravels.filter {
                it.petId == petId && it.backendJobId != null &&
                    it.backendState !in setOf(PendingBackendState.Ready, PendingBackendState.Failed)
            }
            val unresolvedReady = recovery.pendingOutfits.any {
                it.petId == petId && it.backendState == PendingBackendState.Ready
            } || recovery.pendingTravels.any {
                it.petId == petId && it.backendState == PendingBackendState.Ready
            }
            if (pendingOutfits.isEmpty() && pendingTravels.isEmpty() && !unresolvedReady) return
            pendingOutfits.forEach { cancellationSafe { outfit.pollOnce(it) } }
            pendingTravels.forEach { cancellationSafe { travel.pollOnce(it) } }
            applyReadyOutcomes(petId)
            recovery = store.loadOwnerRecovery(ownerId)
            if ((terminalFailureKeys(recovery, petId) - initialTerminalFailures).isNotEmpty()) {
                onTerminalFailure()
                return
            }
            if (attempt + 1 < maxPollAttempts) delay(pollDelayMillis)
        }
    }

    private fun terminalFailureKeys(
        recovery: com.gigagochi.app.core.database.OwnerRecoveryData,
        petId: String,
    ): Set<String> = buildSet {
        recovery.pendingOutfits.filter {
            it.petId == petId && it.backendState == PendingBackendState.Failed &&
                it.backendErrorCode != "APPLY_CONFLICT"
        }.forEach { add("outfit:${it.requestKey}") }
        recovery.pendingTravels.filter {
            it.petId == petId && it.backendState == PendingBackendState.Failed &&
                it.backendErrorCode != "APPLY_CONFLICT"
        }.forEach { add("travel:${it.requestKey}") }
    }

    private suspend fun applyReadyOutcomes(petId: String) {
        when (val result = outcomeApplication?.applyReady(petId)) {
            is DashboardOutcomeRecoveryResult.Changed -> onOutcomeApplied(result)
            DashboardOutcomeRecoveryResult.Conflict -> onOutcomeConflict()
            else -> Unit
        }
    }
}

private fun PetDashboardState.requiredMoodUrl(mood: String): String =
    generatedMedia.moodImages.singleOrNull { it.stage == stage && it.mood == mood }?.url
        ?: throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))

private fun submitIdentityMatches(
    envelope: GenerationEnvelopeDto,
    requestKey: String,
    petId: String,
): Boolean = envelope.requestKey == requestKey && envelope.petId == petId

private fun rejectUnsafeRedispatch(state: PendingBackendState, code: String?) {
    if (state in setOf(PendingBackendState.Dispatching, PendingBackendState.OutcomeUnknown)) {
        throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.OutcomeUnknown, code))
    }
    if (state == PendingBackendState.Failed) {
        throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Conflict, code))
    }
}

private fun submitFailureState(failure: FeatureFailure): PendingBackendState = when (failure.kind) {
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

private fun protocolFailure(): Nothing =
    throw FeatureAdapterException(FeatureFailure(FeatureFailureKind.Protocol))

private suspend fun cancellationSafe(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Foreground recovery is best-effort; durable pending/error state remains authoritative.
    }
}
