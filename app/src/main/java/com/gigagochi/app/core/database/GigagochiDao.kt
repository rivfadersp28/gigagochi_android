package com.gigagochi.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface GigagochiDao {
    @Upsert
    suspend fun upsertPet(entity: PetSnapshotEntity)

    @Query("SELECT * FROM pet_snapshots WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun getPet(ownerId: String, petId: String): PetSnapshotEntity?

    @Query("SELECT * FROM pet_snapshots WHERE ownerId = :ownerId ORDER BY petId")
    suspend fun getPets(ownerId: String): List<PetSnapshotEntity>

    @Query("DELETE FROM pet_snapshots WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deletePet(ownerId: String, petId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessages(entities: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_messages WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun getRecentChatMessages(
        ownerId: String,
        petId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE ownerId = :ownerId AND petId = :petId AND messageId NOT IN (SELECT messageId FROM chat_messages WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAtEpochMillis DESC LIMIT :keep)")
    suspend fun trimChatMessages(ownerId: String, petId: String, keep: Int): Int

    @Query("DELETE FROM chat_messages WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteChatMessages(ownerId: String, petId: String): Int

    @Query("SELECT complimentKey FROM compliment_ledger WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun getRecentComplimentKeys(ownerId: String, petId: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompliment(entity: ComplimentLedgerEntity): Long

    @Query("DELETE FROM compliment_ledger WHERE ownerId = :ownerId AND petId = :petId AND normalizedKey NOT IN (SELECT normalizedKey FROM compliment_ledger WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAtEpochMillis DESC LIMIT :keep)")
    suspend fun trimCompliments(ownerId: String, petId: String, keep: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppliedChatResponse(entity: AppliedChatResponseEntity): Long

    @Query("SELECT * FROM applied_chat_responses WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getAppliedChatResponse(ownerId: String, requestKey: String): AppliedChatResponseEntity?

    @Upsert
    suspend fun upsertUserMemory(entity: UserMemoryEntity)

    @Query("SELECT * FROM user_memories WHERE ownerId = :ownerId AND petId = :petId ORDER BY importance DESC, updatedAtEpochMillis DESC")
    suspend fun getUserMemories(ownerId: String, petId: String): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE ownerId = :ownerId AND petId = :petId AND normalizedKey = :normalizedKey ORDER BY updatedAtEpochMillis DESC")
    suspend fun getUserMemoriesByKey(
        ownerId: String,
        petId: String,
        normalizedKey: String,
    ): List<UserMemoryEntity>

    @Query("DELETE FROM user_memories WHERE ownerId = :ownerId AND petId = :petId AND normalizedKey = :normalizedKey")
    suspend fun deleteUserMemoriesByKey(ownerId: String, petId: String, normalizedKey: String): Int

    @Query("DELETE FROM user_memories WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteUserMemories(ownerId: String, petId: String): Int

    @Upsert
    suspend fun upsertMemoryLearning(entity: MemoryLearningEntity)

    @Query("SELECT * FROM memory_learnings WHERE ownerId = :ownerId AND petId = :petId ORDER BY lastSeenAtEpochMillis DESC")
    suspend fun getMemoryLearnings(ownerId: String, petId: String): List<MemoryLearningEntity>

    @Query("DELETE FROM memory_learnings WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteMemoryLearnings(ownerId: String, petId: String): Int

    @Query("UPDATE memory_learnings SET status = :status, lastSeenAtEpochMillis = :updatedAt WHERE ownerId = :ownerId AND petId = :petId AND learningId = :learningId")
    suspend fun updateMemoryLearningStatus(
        ownerId: String,
        petId: String,
        learningId: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Upsert
    suspend fun upsertPetMemoryState(entity: PetMemoryStateEntity)

    @Query("SELECT * FROM pet_memory_state WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun getPetMemoryState(ownerId: String, petId: String): PetMemoryStateEntity?

    @Query("DELETE FROM pet_memory_state WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deletePetMemoryState(ownerId: String, petId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProactiveNotification(entity: ProactiveNotificationEntity): Long

    @Query("SELECT * FROM proactive_notifications WHERE ownerId = :ownerId AND petId = :petId AND notifiedAtEpochMillis IS NULL ORDER BY createdAtEpochMillis")
    suspend fun getUnnotifiedProactiveNotifications(
        ownerId: String,
        petId: String,
    ): List<ProactiveNotificationEntity>

    @Query("UPDATE proactive_notifications SET notifiedAtEpochMillis = :notifiedAt WHERE ownerId = :ownerId AND notificationId = :notificationId AND notifiedAtEpochMillis IS NULL")
    suspend fun markProactiveNotificationNotified(
        ownerId: String,
        notificationId: String,
        notifiedAt: Long,
    ): Int

    @Query(
        """
        UPDATE pet_snapshots
        SET experience = :experience,
            hunger = :hunger,
            happiness = :happiness,
            energy = :energy,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE ownerId = :ownerId AND petId = :petId
        """,
    )
    suspend fun updatePetProgress(
        ownerId: String,
        petId: String,
        experience: Int,
        hunger: Int,
        happiness: Int,
        energy: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstSession(entity: FirstSessionEntity): Long

    @Upsert
    suspend fun upsertFirstSession(entity: FirstSessionEntity)

    @Query("SELECT * FROM first_sessions WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun getFirstSession(ownerId: String, petId: String): FirstSessionEntity?

    @Query("SELECT * FROM first_sessions WHERE ownerId = :ownerId ORDER BY updatedAtEpochMillis")
    suspend fun getFirstSessions(ownerId: String): List<FirstSessionEntity>

    @Query("DELETE FROM first_sessions WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteFirstSession(ownerId: String, petId: String): Int

    @Query("UPDATE first_sessions SET stage = :nextStage, selectedDestination = :destination, lastActionKey = :actionKey, updatedAtEpochMillis = :updatedAt WHERE ownerId = :ownerId AND petId = :petId AND stage = :expectedStage")
    suspend fun advanceFirstSession(
        ownerId: String,
        petId: String,
        expectedStage: String,
        nextStage: String,
        destination: String?,
        actionKey: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFirstSessionActionReceipt(entity: FirstSessionActionReceiptEntity): Long

    @Query("SELECT * FROM first_session_action_receipts WHERE ownerId = :ownerId AND petId = :petId AND actionKey = :actionKey")
    suspend fun getFirstSessionActionReceipt(ownerId: String, petId: String, actionKey: String): FirstSessionActionReceiptEntity?

    @Query("DELETE FROM first_session_action_receipts WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteFirstSessionActionReceipts(ownerId: String, petId: String): Int

    @Upsert
    suspend fun upsertMoodImages(entities: List<PetMoodImageEntity>)

    @Query("SELECT * FROM pet_mood_images WHERE ownerId = :ownerId AND petId = :petId ORDER BY stage, mood")
    suspend fun getMoodImages(ownerId: String, petId: String): List<PetMoodImageEntity>

    @Query("DELETE FROM pet_mood_images WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun deleteMoodImages(ownerId: String, petId: String): Int

    @Upsert
    suspend fun upsertPendingCreate(entity: PendingCreateGenerationEntity)

    @Query(
        "SELECT * FROM pending_create_generations " +
            "WHERE ownerId = :ownerId AND requestKey = :requestKey",
    )
    suspend fun getPendingCreate(
        ownerId: String,
        requestKey: String,
    ): PendingCreateGenerationEntity?

    @Query("SELECT * FROM pending_create_generations WHERE ownerId = :ownerId ORDER BY updatedAtEpochMillis")
    suspend fun getPendingCreates(ownerId: String): List<PendingCreateGenerationEntity>

    @Query("DELETE FROM pending_create_generations WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun deletePendingCreate(ownerId: String, requestKey: String): Int

    @Query(
        """
        UPDATE pending_create_generations SET backendJobId = :backendJobId
        WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendJobId IS NULL
        """,
    )
    suspend fun attachCreateBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): Int

    @Query("UPDATE pending_create_generations SET backendState = :state, backendErrorCode = :errorCode WHERE ownerId = :ownerId AND requestKey = :requestKey AND (backendState NOT IN ('Ready', 'OutcomeUnknown', 'Failed') OR backendState = :state)")
    suspend fun updateCreateBackendState(ownerId: String, requestKey: String, state: PendingBackendState, errorCode: String?): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingOutfit(entity: PendingOutfitEntity): Long

    @Query("SELECT * FROM pending_outfits WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getPendingOutfit(ownerId: String, requestKey: String): PendingOutfitEntity?

    @Query("SELECT * FROM pending_outfits WHERE ownerId = :ownerId ORDER BY acceptedAtEpochMillis")
    suspend fun getPendingOutfits(ownerId: String): List<PendingOutfitEntity>

    @Query("DELETE FROM pending_outfits WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun deletePendingOutfit(ownerId: String, requestKey: String): Int

    @Query(
        """
        UPDATE pending_outfits SET backendJobId = :backendJobId
        WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendJobId IS NULL
        """,
    )
    suspend fun attachOutfitBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): Int

    @Query("UPDATE pending_outfits SET backendState = :state, backendErrorCode = :errorCode WHERE ownerId = :ownerId AND requestKey = :requestKey AND (backendState NOT IN ('Ready', 'OutcomeUnknown', 'Failed') OR backendState = :state)")
    suspend fun updateOutfitBackendState(ownerId: String, requestKey: String, state: PendingBackendState, errorCode: String?): Int

    @Query("UPDATE pending_outfits SET backendState = 'Failed', backendErrorCode = 'APPLY_CONFLICT' WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendState = 'Ready'")
    suspend fun markOutfitApplyConflict(ownerId: String, requestKey: String): Int

    @Query("UPDATE pending_outfits SET backendState = 'Failed', backendErrorCode = 'INCOMPLETE_MEDIA' WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendState = 'Ready'")
    suspend fun markIncompleteOutfitFailed(ownerId: String, requestKey: String): Int

    @Query("UPDATE pending_outfits SET preparedDisplayItem = :displayItem WHERE ownerId = :ownerId AND requestKey = :requestKey AND preparedDisplayItem IS NULL")
    suspend fun prepareOutfitDisplayItem(ownerId: String, requestKey: String, displayItem: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingTravel(entity: PendingTravelVideoEntity): Long

    @Query("SELECT * FROM pending_travel_videos WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getPendingTravel(ownerId: String, requestKey: String): PendingTravelVideoEntity?

    @Query("SELECT * FROM pending_travel_videos WHERE ownerId = :ownerId ORDER BY acceptedAtEpochMillis")
    suspend fun getPendingTravels(ownerId: String): List<PendingTravelVideoEntity>

    @Query("DELETE FROM pending_travel_videos WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun deletePendingTravel(ownerId: String, requestKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTravelVideoAsset(entity: TravelVideoAssetEntity): Long

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND petId = :petId ORDER BY completedAtEpochMillis")
    suspend fun getTravelVideoAssets(ownerId: String, petId: String): List<TravelVideoAssetEntity>

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND petId = :petId ORDER BY completedAtEpochMillis DESC LIMIT :limit")
    suspend fun getRecentTravelVideoAssets(
        ownerId: String,
        petId: String,
        limit: Int,
    ): List<TravelVideoAssetEntity>

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND petId = :petId ORDER BY completedAtEpochMillis DESC")
    fun observeTravelVideoAssets(ownerId: String, petId: String): Flow<List<TravelVideoAssetEntity>>

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getTravelVideoAsset(ownerId: String, requestKey: String): TravelVideoAssetEntity?

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND petId = :petId AND consumedAtEpochMillis IS NOT NULL AND notifiedAtEpochMillis IS NULL")
    suspend fun getUnnotifiedTravelVideoAssets(ownerId: String, petId: String): List<TravelVideoAssetEntity>

    @Query("UPDATE travel_video_assets SET notifiedAtEpochMillis = :notifiedAt WHERE ownerId = :ownerId AND requestKey = :requestKey AND notifiedAtEpochMillis IS NULL")
    suspend fun markTravelVideoAssetNotified(ownerId: String, requestKey: String, notifiedAt: Long): Int

    @Query("UPDATE travel_video_assets SET consumedAtEpochMillis = :consumedAt WHERE ownerId = :ownerId AND requestKey = :requestKey AND consumedAtEpochMillis IS NULL")
    suspend fun consumeTravelVideoAsset(ownerId: String, requestKey: String, consumedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutfitMediaOutcome(entity: OutfitMediaOutcomeEntity): Long

    @Query("SELECT * FROM outfit_media_outcomes WHERE ownerId = :ownerId AND petId = :petId ORDER BY completedAtEpochMillis")
    suspend fun getOutfitMediaOutcomes(ownerId: String, petId: String): List<OutfitMediaOutcomeEntity>

    @Query("SELECT * FROM outfit_media_outcomes WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getOutfitMediaOutcome(ownerId: String, requestKey: String): OutfitMediaOutcomeEntity?

    @Query("DELETE FROM outfit_media_outcomes WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun deleteOutfitMediaOutcome(ownerId: String, requestKey: String): Int

    @Upsert
    suspend fun upsertOutfitMoodImages(entities: List<OutfitMoodImageEntity>)

    @Query("SELECT * FROM outfit_mood_images WHERE ownerId = :ownerId AND requestKey = :requestKey ORDER BY stage, mood")
    suspend fun getOutfitMoodImages(ownerId: String, requestKey: String): List<OutfitMoodImageEntity>

    @Query("DELETE FROM outfit_mood_images WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun deleteOutfitMoodImages(ownerId: String, requestKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppliedOutfitReceipt(entity: AppliedOutfitReceiptEntity): Long

    @Query("SELECT * FROM applied_outfit_receipts WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getAppliedOutfitReceipt(ownerId: String, requestKey: String): AppliedOutfitReceiptEntity?

    @Query("SELECT * FROM applied_outfit_receipts WHERE ownerId = :ownerId AND petId = :petId ORDER BY appliedAtEpochMillis DESC LIMIT :limit")
    suspend fun getRecentAppliedOutfitReceipts(
        ownerId: String,
        petId: String,
        limit: Int,
    ): List<AppliedOutfitReceiptEntity>

    @Query("SELECT * FROM applied_outfit_receipts WHERE ownerId = :ownerId AND petId = :petId AND notifiedAtEpochMillis IS NULL")
    suspend fun getUnnotifiedAppliedOutfitReceipts(ownerId: String, petId: String): List<AppliedOutfitReceiptEntity>

    @Query("UPDATE applied_outfit_receipts SET notifiedAtEpochMillis = :notifiedAt WHERE ownerId = :ownerId AND requestKey = :requestKey AND notifiedAtEpochMillis IS NULL")
    suspend fun markAppliedOutfitReceiptNotified(ownerId: String, requestKey: String, notifiedAt: Long): Int

    @Query(
        """
        UPDATE pending_travel_videos SET backendJobId = :backendJobId
        WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendJobId IS NULL
        """,
    )
    suspend fun attachTravelBackendJob(
        ownerId: String,
        requestKey: String,
        backendJobId: String,
    ): Int

    @Query("UPDATE pending_travel_videos SET backendState = :state, backendErrorCode = :errorCode WHERE ownerId = :ownerId AND requestKey = :requestKey AND (backendState NOT IN ('Ready', 'OutcomeUnknown', 'Failed') OR backendState = :state)")
    suspend fun updateTravelBackendState(ownerId: String, requestKey: String, state: PendingBackendState, errorCode: String?): Int

    @Query("UPDATE pending_travel_videos SET backendState = 'Failed', backendErrorCode = 'APPLY_CONFLICT' WHERE ownerId = :ownerId AND requestKey = :requestKey AND backendState = 'Ready'")
    suspend fun markTravelApplyConflict(ownerId: String, requestKey: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStoryReceipt(entity: AppliedStoryReceiptEntity): Long

    @Query("SELECT * FROM applied_story_receipts WHERE ownerId = :ownerId AND receiptKey = :receiptKey")
    suspend fun getStoryReceipt(ownerId: String, receiptKey: String): AppliedStoryReceiptEntity?

    @Query(
        """
        SELECT * FROM applied_story_receipts
        WHERE ownerId = :ownerId AND travelId = :travelId AND partKey = :partKey
        """,
    )
    suspend fun getStoryReceiptByPart(
        ownerId: String,
        travelId: String,
        partKey: String,
    ): AppliedStoryReceiptEntity?

    @Query("SELECT * FROM applied_story_receipts WHERE ownerId = :ownerId ORDER BY appliedAtEpochMillis")
    suspend fun getStoryReceipts(ownerId: String): List<AppliedStoryReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScheduledStory(entity: ScheduledStoryEntity): Long

    @Upsert
    suspend fun upsertScheduledStory(entity: ScheduledStoryEntity)

    @Query("SELECT * FROM scheduled_stories WHERE ownerId = :ownerId AND storyId = :storyId")
    suspend fun getScheduledStory(ownerId: String, storyId: String): ScheduledStoryEntity?

    @Query("DELETE FROM scheduled_stories WHERE ownerId = :ownerId AND storyId = :storyId")
    suspend fun deleteScheduledStory(ownerId: String, storyId: String): Int

    @Query("SELECT * FROM scheduled_stories WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAt DESC")
    suspend fun getScheduledStories(ownerId: String, petId: String): List<ScheduledStoryEntity>

    @Query("SELECT * FROM scheduled_stories WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAt DESC")
    fun observeScheduledStories(ownerId: String, petId: String): Flow<List<ScheduledStoryEntity>>

    @Query("SELECT * FROM scheduled_stories WHERE ownerId = :ownerId AND petId = :petId AND notifiedAtEpochMillis IS NULL ORDER BY createdAt")
    suspend fun getUnnotifiedScheduledStories(ownerId: String, petId: String): List<ScheduledStoryEntity>

    @Query("UPDATE scheduled_stories SET notifiedAtEpochMillis = :notifiedAt WHERE ownerId = :ownerId AND storyId = :storyId AND notifiedAtEpochMillis IS NULL")
    suspend fun markScheduledStoryNotified(ownerId: String, storyId: String, notifiedAt: Long): Int

    @Query("UPDATE scheduled_stories SET choiceRequestKey = :requestKey, pendingChoice = :choice WHERE ownerId = :ownerId AND storyId = :storyId AND selectedChoice IS NULL AND choiceRequestKey IS NULL")
    suspend fun claimScheduledStoryChoice(
        ownerId: String,
        storyId: String,
        requestKey: String,
        choice: String,
    ): Int

    @Query("DELETE FROM applied_story_receipts WHERE ownerId = :ownerId AND receiptKey = :receiptKey")
    suspend fun deleteStoryReceipt(ownerId: String, receiptKey: String): Int

    @Query("DELETE FROM pending_create_generations WHERE ownerId = :ownerId")
    suspend fun deleteOwnerPendingCreates(ownerId: String): Int

    @Query("DELETE FROM pending_outfits WHERE ownerId = :ownerId")
    suspend fun deleteOwnerPendingOutfits(ownerId: String): Int

    @Query("DELETE FROM pending_travel_videos WHERE ownerId = :ownerId")
    suspend fun deleteOwnerPendingTravels(ownerId: String): Int

    @Query("DELETE FROM applied_story_receipts WHERE ownerId = :ownerId")
    suspend fun deleteOwnerStoryReceipts(ownerId: String): Int

    @Query("DELETE FROM scheduled_stories WHERE ownerId = :ownerId")
    suspend fun deleteOwnerScheduledStories(ownerId: String): Int

    @Query("DELETE FROM pet_snapshots WHERE ownerId = :ownerId")
    suspend fun deleteOwnerPets(ownerId: String): Int

    @Query("DELETE FROM pet_mood_images WHERE ownerId = :ownerId")
    suspend fun deleteOwnerMoodImages(ownerId: String): Int

    @Query("DELETE FROM travel_video_assets WHERE ownerId = :ownerId")
    suspend fun deleteOwnerTravelVideoAssets(ownerId: String): Int

    @Query("DELETE FROM outfit_media_outcomes WHERE ownerId = :ownerId")
    suspend fun deleteOwnerOutfitMediaOutcomes(ownerId: String): Int

    @Query("DELETE FROM outfit_mood_images WHERE ownerId = :ownerId")
    suspend fun deleteOwnerOutfitMoodImages(ownerId: String): Int

    @Query("DELETE FROM applied_outfit_receipts WHERE ownerId = :ownerId")
    suspend fun deleteOwnerAppliedOutfitReceipts(ownerId: String): Int

    @Query("DELETE FROM first_sessions WHERE ownerId = :ownerId")
    suspend fun deleteOwnerFirstSessions(ownerId: String): Int

    @Query("DELETE FROM first_session_action_receipts WHERE ownerId = :ownerId")
    suspend fun deleteOwnerFirstSessionActionReceipts(ownerId: String): Int

    @Query("DELETE FROM chat_messages WHERE ownerId = :ownerId")
    suspend fun deleteOwnerChatMessages(ownerId: String): Int

    @Query("DELETE FROM compliment_ledger WHERE ownerId = :ownerId")
    suspend fun deleteOwnerCompliments(ownerId: String): Int

    @Query("DELETE FROM applied_chat_responses WHERE ownerId = :ownerId")
    suspend fun deleteOwnerAppliedChatResponses(ownerId: String): Int

    @Query("DELETE FROM user_memories WHERE ownerId = :ownerId")
    suspend fun deleteOwnerUserMemories(ownerId: String): Int

    @Query("DELETE FROM memory_learnings WHERE ownerId = :ownerId")
    suspend fun deleteOwnerMemoryLearnings(ownerId: String): Int

    @Query("DELETE FROM pet_memory_state WHERE ownerId = :ownerId")
    suspend fun deleteOwnerPetMemoryStates(ownerId: String): Int

    @Query("DELETE FROM proactive_notifications WHERE ownerId = :ownerId")
    suspend fun deleteOwnerProactiveNotifications(ownerId: String): Int

}
