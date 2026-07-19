package com.gigagochi.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PetSnapshotEntity::class,
        PendingCreateGenerationEntity::class,
        PendingOutfitEntity::class,
        PendingTravelVideoEntity::class,
        AppliedStoryReceiptEntity::class,
        PetMoodImageEntity::class,
        TravelVideoAssetEntity::class,
        OutfitMediaOutcomeEntity::class,
        OutfitMoodImageEntity::class,
        AppliedOutfitReceiptEntity::class,
        ScheduledStoryEntity::class,
        FirstSessionEntity::class,
        FirstSessionActionReceiptEntity::class,
        ChatMessageEntity::class,
        UserMemoryEntity::class,
        MemoryLearningEntity::class,
        PetMemoryStateEntity::class,
        ProactiveNotificationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseTypeConverters::class)
abstract class GigagochiDatabase : RoomDatabase() {
    internal abstract fun gigagochiDao(): GigagochiDao

    companion object {
        const val DatabaseName = "gigagochi-local.db"

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `petId`, `messageId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_owner_pet_created` ON `chat_messages` (`ownerId`, `petId`, `createdAtEpochMillis`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_memories` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `normalizedKey` TEXT NOT NULL, `confidence` REAL NOT NULL, `importance` REAL NOT NULL, `memoryClass` TEXT NOT NULL, `recordedAtEpochMillis` INTEGER NOT NULL, `occurredAtEpochMillis` INTEGER, `dueAtEpochMillis` INTEGER, `expiresAtEpochMillis` INTEGER, `lastMentionedAtEpochMillis` INTEGER, `mentionCount` INTEGER NOT NULL, `tagsJson` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `petId`, `memoryId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_owner_pet_key` ON `user_memories` (`ownerId`, `petId`, `normalizedKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_owner_pet_due` ON `user_memories` (`ownerId`, `petId`, `dueAtEpochMillis`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `memory_learnings` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `learningId` TEXT NOT NULL, `status` TEXT NOT NULL, `observation` TEXT NOT NULL, `patternKey` TEXT, `kind` TEXT, `confidence` REAL NOT NULL, `importance` REAL NOT NULL, `recurrenceCount` INTEGER NOT NULL, `firstSeenAtEpochMillis` INTEGER NOT NULL, `lastSeenAtEpochMillis` INTEGER NOT NULL, `occurredAtEpochMillis` INTEGER, `dueAtEpochMillis` INTEGER, PRIMARY KEY(`ownerId`, `petId`, `learningId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_owner_pet_status` ON `memory_learnings` (`ownerId`, `petId`, `status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pet_memory_state` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `summary` TEXT, `userProfile` TEXT, `lastExtractionAtEpochMillis` INTEGER, `lastConsolidationAtEpochMillis` INTEGER, `lastProactiveAtEpochMillis` INTEGER, `proactiveLogJson` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `petId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `proactive_notifications` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `notificationId` TEXT NOT NULL, `reply` TEXT NOT NULL, `memoryIdsJson` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `notifiedAtEpochMillis` INTEGER, PRIMARY KEY(`ownerId`, `petId`, `notificationId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_owner_pet_notified` ON `proactive_notifications` (`ownerId`, `petId`, `notifiedAtEpochMillis`)")
            }
        }

        fun build(context: Context): GigagochiDatabase = Room.databaseBuilder(
            context.applicationContext,
            GigagochiDatabase::class.java,
            DatabaseName,
        ).addMigrations(Migration1To2).build()
    }
}
