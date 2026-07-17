package com.gigagochi.app.core.database

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia

const val OutfitAcceptanceCost = 200

data class OwnedPetSnapshot(
    val ownerId: String,
    val pet: PetDashboardState,
    val updatedAtEpochMillis: Long,
)

enum class PendingCreateStage {
    Requested,
    BackendQueued,
    Generating,
}

enum class PendingBackendState {
    Pending, Dispatching, Attached, Retryable, OutcomeUnknown, Failed, Ready,
}

interface PendingBackendStateStore {
    suspend fun updateCreateBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String? = null,
    ): Boolean
    suspend fun updateOutfitBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String? = null,
    ): Boolean
    suspend fun updateTravelBackendState(
        ownerId: String,
        requestKey: String,
        state: PendingBackendState,
        errorCode: String? = null,
    ): Boolean
    suspend fun prepareOutfitDisplayItem(
        ownerId: String,
        requestKey: String,
        displayItem: String,
    ): Boolean
    suspend fun markOutfitApplyConflict(ownerId: String, requestKey: String): Boolean
    suspend fun markTravelApplyConflict(ownerId: String, requestKey: String): Boolean
}

interface FeatureMediaOutcomeStore {
    suspend fun saveTravelVideoAsset(asset: LocalTravelVideoAsset)
    suspend fun saveOutfitMediaOutcome(outcome: LocalOutfitMediaOutcome)
}

data class LocalPendingCreateGeneration(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val backendJobId: String?,
    val stage: PendingCreateStage,
    val description: String,
    val name: String?,
    val personality: String?,
    val fear: String?,
    val favoriteItem: String?,
    val currentStep: Int,
    val updatedAtEpochMillis: Long,
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
)

data class LocalPendingOutfit(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val localJobId: String,
    val backendJobId: String?,
    val prompt: String,
    val baseAssetSetId: String,
    val acceptedAtEpochMillis: Long,
    val experienceCost: Int = OutfitAcceptanceCost,
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
    val preparedDisplayItem: String? = null,
)

data class LocalPendingTravelVideo(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val localJobId: String,
    val backendJobId: String?,
    val prompt: String,
    val acceptedAtEpochMillis: Long,
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
)

data class LocalTravelVideoAsset(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val backendJobId: String,
    val prompt: String,
    val title: String?,
    val scenario: String?,
    val imageUrl: String?,
    val videoUrl: String,
    val completedAtEpochMillis: Long,
    val consumedAtEpochMillis: Long? = null,
)

data class LocalOutfitMediaOutcome(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val backendJobId: String,
    val displayItem: String,
    val assetSetId: String,
    val media: PetGeneratedMedia,
    val completedAtEpochMillis: Long,
)

data class AppliedOutfitReceipt(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val assetSetId: String,
    val appliedAtEpochMillis: Long,
)

sealed interface OutfitOutcomeApplicationResult {
    data class Applied(val pet: PetDashboardState) : OutfitOutcomeApplicationResult
    data class AlreadyApplied(val pet: PetDashboardState) : OutfitOutcomeApplicationResult
    data object NotReady : OutfitOutcomeApplicationResult
    data object Conflict : OutfitOutcomeApplicationResult
}

sealed interface TravelAssetConsumptionResult {
    data class Consumed(val asset: LocalTravelVideoAsset) : TravelAssetConsumptionResult
    data class AlreadyConsumed(val asset: LocalTravelVideoAsset) : TravelAssetConsumptionResult
    data object NotReady : TravelAssetConsumptionResult
    data object Conflict : TravelAssetConsumptionResult
}

data class InteractiveStoryReceipt(
    val ownerId: String,
    val petId: String,
    val receiptKey: String,
    val travelId: String,
    val partKey: String,
    val experienceDelta: Int,
    val hungerDelta: Int,
    val happinessDelta: Int,
    val energyDelta: Int,
    val appliedAtEpochMillis: Long,
)

enum class IdempotentInsertResult { Inserted, AlreadyPresent }

enum class BackendJobAttachmentResult {
    Attached,
    AlreadyAttached,
    Conflict,
    PendingMissing,
}

sealed interface OutfitAcceptanceResult {
    data object Applied : OutfitAcceptanceResult
    data object AlreadyApplied : OutfitAcceptanceResult
    data object PetMissing : OutfitAcceptanceResult
    data object InsufficientExperience : OutfitAcceptanceResult
}

sealed interface StoryApplicationResult {
    data object Applied : StoryApplicationResult
    data object AlreadyApplied : StoryApplicationResult
    data object PetMissing : StoryApplicationResult
}
