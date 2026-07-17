package com.gigagochi.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

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

    @Query("UPDATE pet_snapshots SET firstSessionActive = :active, updatedAtEpochMillis = :updatedAt WHERE ownerId = :ownerId AND petId = :petId")
    suspend fun updateFirstSessionActive(
        ownerId: String,
        petId: String,
        active: Boolean,
        updatedAt: Long,
    ): Int

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

    @Query("SELECT * FROM travel_video_assets WHERE ownerId = :ownerId AND requestKey = :requestKey")
    suspend fun getTravelVideoAsset(ownerId: String, requestKey: String): TravelVideoAssetEntity?

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

    @Query("SELECT * FROM scheduled_stories WHERE ownerId = :ownerId AND petId = :petId ORDER BY createdAt DESC")
    suspend fun getScheduledStories(ownerId: String, petId: String): List<ScheduledStoryEntity>

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

}
