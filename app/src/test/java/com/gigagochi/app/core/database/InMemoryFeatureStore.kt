package com.gigagochi.app.core.database

open class InMemoryFeatureStore : TestOwnerRecoveryStore(), PendingBackendStateStore,
    FeatureMediaOutcomeStore, NotificationOutboxStore {
    val creates = mutableListOf<LocalPendingCreateGeneration>()
    val outfits = mutableListOf<LocalPendingOutfit>()
    val travels = mutableListOf<LocalPendingTravelVideo>()
    val outfitOutcomes = mutableListOf<LocalOutfitMediaOutcome>()
    val travelAssets = mutableListOf<LocalTravelVideoAsset>()
    val notifications = mutableListOf<LocalCompletionNotification>()
    var createAttachCalls = 0
    var outfitAttachCalls = 0
    var travelAttachCalls = 0

    override suspend fun loadOwnerRecovery(ownerId: String) = OwnerRecoveryData(
        emptyList(),
        creates.filter { it.ownerId == ownerId },
        outfits.filter { it.ownerId == ownerId },
        travels.filter { it.ownerId == ownerId },
        emptyList(),
        outfitOutcomes.filter { it.ownerId == ownerId },
        travelAssets.filter { it.ownerId == ownerId },
    )

    override suspend fun savePendingCreate(pending: LocalPendingCreateGeneration) {
        creates.removeAll { it.ownerId == pending.ownerId && it.requestKey == pending.requestKey }
        creates += pending
    }

    override suspend fun attachCreateBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        createAttachCalls += 1
        val index = creates.indexOfFirst { it.ownerId == ownerId && it.requestKey == requestKey }
        if (index < 0) return BackendJobAttachmentResult.PendingMissing
        val current = creates[index]
        current.backendJobId?.let {
            return if (it == backendJobId) BackendJobAttachmentResult.AlreadyAttached
            else BackendJobAttachmentResult.Conflict
        }
        creates[index] = current.copy(backendJobId = backendJobId)
        return BackendJobAttachmentResult.Attached
    }

    override suspend fun attachOutfitBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        outfitAttachCalls += 1
        val index = outfits.indexOfFirst { it.ownerId == ownerId && it.requestKey == requestKey }
        if (index < 0) return BackendJobAttachmentResult.PendingMissing
        val current = outfits[index]
        outfits[index] = current.copy(backendJobId = backendJobId)
        return BackendJobAttachmentResult.Attached
    }

    override suspend fun attachTravelBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): BackendJobAttachmentResult {
        travelAttachCalls += 1
        val index = travels.indexOfFirst { it.ownerId == ownerId && it.requestKey == requestKey }
        if (index < 0) return BackendJobAttachmentResult.PendingMissing
        travels[index] = travels[index].copy(backendJobId = backendJobId)
        return BackendJobAttachmentResult.Attached
    }

    override suspend fun updateCreateBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ) = update(creates, ownerId, requestKey) { it.copy(backendState = state, backendErrorCode = errorCode) }

    override suspend fun updateOutfitBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ) = update(outfits, ownerId, requestKey) { it.copy(backendState = state, backendErrorCode = errorCode) }

    override suspend fun updateTravelBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String?,
    ) = update(travels, ownerId, requestKey) { it.copy(backendState = state, backendErrorCode = errorCode) }

    override suspend fun prepareOutfitDisplayItem(
        ownerId: String,
        requestKey: String,
        displayItem: String,
    ) = update(outfits, ownerId, requestKey) { it.copy(preparedDisplayItem = displayItem) }

    override suspend fun markOutfitApplyConflict(ownerId: String, requestKey: String) =
        update(outfits, ownerId, requestKey) {
            it.copy(backendState = PendingBackendState.Failed, backendErrorCode = "APPLY_CONFLICT")
        }

    override suspend fun markTravelApplyConflict(ownerId: String, requestKey: String) =
        update(travels, ownerId, requestKey) {
            it.copy(backendState = PendingBackendState.Failed, backendErrorCode = "APPLY_CONFLICT")
        }

    override suspend fun saveTravelVideoAsset(asset: LocalTravelVideoAsset) {
        travelAssets.removeAll { it.ownerId == asset.ownerId && it.requestKey == asset.requestKey }
        travelAssets += asset
    }

    override suspend fun saveOutfitMediaOutcome(outcome: LocalOutfitMediaOutcome) {
        outfitOutcomes.removeAll { it.ownerId == outcome.ownerId && it.requestKey == outcome.requestKey }
        outfitOutcomes += outcome
    }

    override suspend fun enqueueNotification(
        ownerId: String,
        petId: String,
        notification: LocalCompletionNotification,
        createdAtEpochMillis: Long,
    ): Boolean {
        val existing = notifications.singleOrNull {
            it.kind == notification.kind && it.stableKey == notification.stableKey
        }
        if (existing != null) return existing == notification
        notifications += notification
        return true
    }

    private fun <T> update(
        values: MutableList<T>,
        ownerId: String,
        requestKey: String,
        transform: (T) -> T,
    ): Boolean {
        val index = values.indexOfFirst {
            when (it) {
                is LocalPendingCreateGeneration -> it.ownerId == ownerId && it.requestKey == requestKey
                is LocalPendingOutfit -> it.ownerId == ownerId && it.requestKey == requestKey
                is LocalPendingTravelVideo -> it.ownerId == ownerId && it.requestKey == requestKey
                else -> false
            }
        }
        if (index < 0) return false
        values[index] = transform(values[index])
        return true
    }
}
