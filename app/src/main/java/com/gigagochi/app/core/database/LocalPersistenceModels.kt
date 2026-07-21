package com.gigagochi.app.core.database

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.ScheduledStory

const val OutfitAcceptanceCost = 0

enum class FirstSessionStage(val storageValue: String) {
    AwaitingChat("awaiting-chat"),
    AwaitingChatFollowup("awaiting-chat-followup"),
    AwaitingFirstFood("awaiting-first-food"),
    AwaitingRemedy("awaiting-remedy"),
    AwaitingTravel("awaiting-travel"),
    ConfirmingTravel("confirming-travel"),
    AwaitingCompletionMessage("awaiting-completion-message"),
    Completed("completed");

    companion object {
        fun fromStorage(value: String): FirstSessionStage = entries.single {
            it.storageValue == value
        }
    }
}

data class LocalFirstSession(
    val ownerId: String,
    val petId: String,
    val stage: FirstSessionStage,
    val selectedDestination: String? = null,
    val lastActionKey: String? = null,
    val updatedAtEpochMillis: Long,
)

sealed interface FirstSessionMutationResult {
    data class Applied(val session: LocalFirstSession, val pet: PetDashboardState) : FirstSessionMutationResult
    data class AlreadyApplied(val session: LocalFirstSession, val pet: PetDashboardState) : FirstSessionMutationResult
    data object WrongStage : FirstSessionMutationResult
    data object Missing : FirstSessionMutationResult
}

data class LocalScheduledStory(
    val ownerId: String,
    val story: ScheduledStory,
    val choiceRequestKey: String? = null,
    val pendingChoice: String? = null,
    val notifiedAtEpochMillis: Long? = null,
)

sealed interface ScheduledStoryChoiceClaim {
    data class Claimed(val requestKey: String, val choice: String) : ScheduledStoryChoiceClaim
    data class Existing(val requestKey: String, val choice: String) : ScheduledStoryChoiceClaim
    data class Completed(val story: ScheduledStory, val requestKey: String) : ScheduledStoryChoiceClaim
    data object Conflict : ScheduledStoryChoiceClaim
    data object Missing : ScheduledStoryChoiceClaim
}

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
    Pending, Dispatching, Attached, ForegroundReady, Retryable, OutcomeUnknown, Failed, Ready,
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

interface ScheduledStoryStore {
    suspend fun saveScheduledStory(story: LocalScheduledStory): Boolean
    suspend fun deleteScheduledStory(ownerId: String, storyId: String): Boolean
    suspend fun getScheduledStory(ownerId: String, storyId: String): LocalScheduledStory?
    suspend fun getScheduledStories(ownerId: String, petId: String): List<LocalScheduledStory>
    suspend fun claimScheduledStoryChoice(
        ownerId: String,
        storyId: String,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceClaim
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
    val notifiedAtEpochMillis: Long? = null,
)

enum class LocalNotificationKind { PetReady, ScheduledStory, OutfitReady, TravelReady, Proactive }

data class LocalCompletionNotification(
    val kind: LocalNotificationKind,
    val stableKey: String,
    val title: String,
    val body: String,
    val storyId: String? = null,
    val travelRequestKey: String? = null,
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
