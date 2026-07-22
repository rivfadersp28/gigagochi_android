package com.gigagochi.app.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.TypeConverter

@Entity(
    tableName = "pet_snapshots",
    primaryKeys = ["ownerId", "petId"],
)
internal data class PetSnapshotEntity(
    val ownerId: String,
    val petId: String,
    val assetSetId: String,
    val description: String,
    val name: String,
    val stage: String,
    val stageLabel: String,
    val mood: String,
    val experience: Int,
    val hunger: Int,
    val happiness: Int,
    val energy: Int,
    val message: String,
    val petTapProgress: Int,
    val generatedAt: String? = null,
    val videoUrl: String? = null,
    val sadVideoUrl: String? = null,
    val happyVideoUrl: String? = null,
    val blinkImageUrl: String? = null,
    val spriteSheetUrl: String? = null,
    val characterBibleJson: String? = null,
    val hungerTickAtEpochMillis: Long,
    val happinessTickAtEpochMillis: Long,
    val energyTickAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["ownerId", "petId", "messageId"],
    indices = [
        Index(
            value = ["ownerId", "petId", "createdAtEpochMillis"],
            name = "index_chat_owner_pet_created",
        ),
    ],
)
internal data class ChatMessageEntity(
    val ownerId: String,
    val petId: String,
    val messageId: String,
    val role: String,
    val text: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "pending_chats",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(
            value = ["ownerId", "petId", "createdAtEpochMillis"],
            name = "index_pending_chat_owner_pet_created",
        ),
    ],
)
internal data class PendingChatEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val message: String,
    val createdAtEpochMillis: Long,
    val responseText: String?,
    val completedAtEpochMillis: Long?,
)

@Entity(
    tableName = "compliment_ledger",
    primaryKeys = ["ownerId", "petId", "normalizedKey"],
    indices = [
        Index(
            value = ["ownerId", "petId", "createdAtEpochMillis"],
            name = "index_compliment_owner_pet_created",
        ),
    ],
)
internal data class ComplimentLedgerEntity(
    val ownerId: String,
    val petId: String,
    val normalizedKey: String,
    val complimentKey: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "applied_chat_responses",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [Index(value = ["ownerId", "petId"])],
)
internal data class AppliedChatResponseEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val happinessDelta: Int,
    val complimentKey: String?,
    val appliedAtEpochMillis: Long,
)

@Entity(
    tableName = "user_memories",
    primaryKeys = ["ownerId", "petId", "memoryId"],
    indices = [
        Index(
            value = ["ownerId", "petId", "normalizedKey"],
            name = "index_memory_owner_pet_key",
        ),
        Index(
            value = ["ownerId", "petId", "dueAtEpochMillis"],
            name = "index_memory_owner_pet_due",
        ),
    ],
)
internal data class UserMemoryEntity(
    val ownerId: String,
    val petId: String,
    val memoryId: String,
    val kind: String,
    val text: String,
    val normalizedKey: String,
    val confidence: Double,
    val importance: Double,
    val memoryClass: String,
    val recordedAtEpochMillis: Long,
    val occurredAtEpochMillis: Long?,
    val dueAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long?,
    val lastMentionedAtEpochMillis: Long?,
    val mentionCount: Int,
    val tagsJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "memory_learnings",
    primaryKeys = ["ownerId", "petId", "learningId"],
    indices = [Index(value = ["ownerId", "petId", "status"], name = "index_learning_owner_pet_status")],
)
internal data class MemoryLearningEntity(
    val ownerId: String,
    val petId: String,
    val learningId: String,
    val status: String,
    val observation: String,
    val patternKey: String?,
    val kind: String?,
    val confidence: Double,
    val importance: Double,
    val recurrenceCount: Int,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val occurredAtEpochMillis: Long?,
    val dueAtEpochMillis: Long?,
)

@Entity(tableName = "pet_memory_state", primaryKeys = ["ownerId", "petId"])
internal data class PetMemoryStateEntity(
    val ownerId: String,
    val petId: String,
    val summary: String?,
    val userProfile: String?,
    val lastExtractionAtEpochMillis: Long?,
    val lastConsolidationAtEpochMillis: Long?,
    val lastProactiveAtEpochMillis: Long?,
    val proactiveLogJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "proactive_notifications",
    primaryKeys = ["ownerId", "petId", "notificationId"],
    indices = [
        Index(
            value = ["ownerId", "petId", "notifiedAtEpochMillis"],
            name = "index_proactive_owner_pet_notified",
        ),
    ],
)
internal data class ProactiveNotificationEntity(
    val ownerId: String,
    val petId: String,
    val notificationId: String,
    val reply: String,
    val memoryIdsJson: String,
    val createdAtEpochMillis: Long,
    val notifiedAtEpochMillis: Long?,
)

@Entity(
    tableName = "first_sessions",
    primaryKeys = ["ownerId", "petId"],
    indices = [Index(value = ["ownerId", "stage"], name = "index_first_session_owner_stage")],
)
internal data class FirstSessionEntity(
    val ownerId: String,
    val petId: String,
    val stage: String,
    val selectedDestination: String?,
    val lastActionKey: String?,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "first_session_action_receipts",
    primaryKeys = ["ownerId", "petId", "actionKey"],
    indices = [Index(value = ["ownerId", "petId"], name = "index_first_session_action_owner_pet")],
)
internal data class FirstSessionActionReceiptEntity(
    val ownerId: String,
    val petId: String,
    val actionKey: String,
    val actionKind: String,
    val appliedAtEpochMillis: Long,
)

@Entity(
    tableName = "pet_mood_images",
    primaryKeys = ["ownerId", "petId", "stage", "mood"],
    indices = [Index(value = ["ownerId", "petId"], name = "index_media_owner_pet")],
)
internal data class PetMoodImageEntity(
    val ownerId: String,
    val petId: String,
    val stage: String,
    val mood: String,
    val url: String,
)

@Entity(
    tableName = "travel_video_assets",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(value = ["ownerId", "petId"], name = "index_travel_asset_owner_pet"),
        Index(value = ["ownerId", "backendJobId"], name = "index_travel_asset_owner_job"),
    ],
)
internal data class TravelVideoAssetEntity(
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

@Entity(
    tableName = "applied_outfit_receipts",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [Index(value = ["ownerId", "petId"], name = "index_applied_outfit_owner_pet")],
)
internal data class AppliedOutfitReceiptEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val assetSetId: String,
    @ColumnInfo(defaultValue = "''")
    val displayItem: String,
    val appliedAtEpochMillis: Long,
    val notifiedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "outfit_media_outcomes",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(value = ["ownerId", "petId"], name = "index_outfit_outcome_owner_pet"),
        Index(value = ["ownerId", "backendJobId"], name = "index_outfit_outcome_owner_job"),
    ],
)
internal data class OutfitMediaOutcomeEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val backendJobId: String,
    val displayItem: String,
    val assetSetId: String,
    val generatedAt: String?,
    val videoUrl: String?,
    val sadVideoUrl: String?,
    val happyVideoUrl: String?,
    val blinkImageUrl: String?,
    val spriteSheetUrl: String?,
    val characterBibleJson: String?,
    val completedAtEpochMillis: Long,
)

@Entity(
    tableName = "outfit_mood_images",
    primaryKeys = ["ownerId", "requestKey", "stage", "mood"],
    indices = [
        Index(value = ["ownerId", "requestKey"], name = "index_outfit_image_owner_request"),
    ],
)
internal data class OutfitMoodImageEntity(
    val ownerId: String,
    val requestKey: String,
    val stage: String,
    val mood: String,
    val url: String,
)

@Entity(
    tableName = "pending_create_generations",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(value = ["ownerId", "petId"], name = "index_create_owner_pet"),
        Index(value = ["ownerId", "backendJobId"], name = "index_create_owner_backend_job"),
    ],
)
internal data class PendingCreateGenerationEntity(
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
    @ColumnInfo(defaultValue = "'Pending'")
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
)

@Entity(
    tableName = "pending_outfits",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(value = ["ownerId", "petId"], name = "index_outfit_owner_pet"),
        Index(value = ["ownerId", "localJobId"], name = "index_outfit_owner_local_job"),
        Index(value = ["ownerId", "backendJobId"], name = "index_outfit_owner_backend_job"),
    ],
)
internal data class PendingOutfitEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val localJobId: String,
    val backendJobId: String?,
    val prompt: String,
    val baseAssetSetId: String,
    val acceptedAtEpochMillis: Long,
    val experienceCost: Int,
    @ColumnInfo(defaultValue = "'Pending'")
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
    val preparedDisplayItem: String? = null,
)

@Entity(
    tableName = "pending_travel_videos",
    primaryKeys = ["ownerId", "requestKey"],
    indices = [
        Index(value = ["ownerId", "petId"], name = "index_travel_owner_pet"),
        Index(value = ["ownerId", "localJobId"], name = "index_travel_owner_local_job"),
        Index(value = ["ownerId", "backendJobId"], name = "index_travel_owner_backend_job"),
    ],
)
internal data class PendingTravelVideoEntity(
    val ownerId: String,
    val petId: String,
    val requestKey: String,
    val localJobId: String,
    val backendJobId: String?,
    val prompt: String,
    val acceptedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'Pending'")
    val backendState: PendingBackendState = PendingBackendState.Pending,
    val backendErrorCode: String? = null,
)

@Entity(
    tableName = "applied_story_receipts",
    primaryKeys = ["ownerId", "receiptKey"],
    indices = [
        Index(
            value = ["ownerId", "travelId", "partKey"],
            name = "index_story_owner_travel_part",
            unique = true,
        ),
        Index(value = ["ownerId", "petId"], name = "index_story_owner_pet"),
    ],
)
internal data class AppliedStoryReceiptEntity(
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

@Entity(
    tableName = "scheduled_stories",
    primaryKeys = ["ownerId", "storyId"],
    indices = [Index(value = ["ownerId", "petId"], name = "index_scheduled_story_owner_pet")],
)
internal data class ScheduledStoryEntity(
    val ownerId: String,
    val storyId: String,
    val petId: String,
    val title: String,
    val text: String,
    val question: String,
    val choice0: String,
    val choice1: String,
    val choice2: String,
    val choice3: String,
    val createdAt: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val choiceRequestKey: String?,
    val pendingChoice: String?,
    val selectedChoice: String?,
    val resultText: String?,
    val resultReaction: String?,
    val resultConsequence: String?,
    val resultExperienceGained: Int?,
    val resultImageUrl: String?,
    val resultVideoUrl: String?,
    val notifiedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "event_history_views",
    primaryKeys = ["ownerId", "petId"],
)
internal data class EventHistoryViewEntity(
    val ownerId: String,
    val petId: String,
    val lastViewedAtEpochMillis: Long,
)

internal class DatabaseTypeConverters {
    @TypeConverter
    fun pendingCreateStageToStorage(value: PendingCreateStage): String = value.name

    @TypeConverter
    fun pendingCreateStageFromStorage(value: String): PendingCreateStage =
        PendingCreateStage.valueOf(value)

    @TypeConverter
    fun pendingBackendStateToStorage(value: PendingBackendState): String = value.name

    @TypeConverter
    fun pendingBackendStateFromStorage(value: String): PendingBackendState =
        PendingBackendState.valueOf(value)
}
