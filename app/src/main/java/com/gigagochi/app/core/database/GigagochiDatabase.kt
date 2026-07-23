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
        PendingChatEntity::class,
        DashboardCommandReceiptEntity::class,
        ComplimentLedgerEntity::class,
        AppliedChatResponseEntity::class,
        UserMemoryEntity::class,
        MemoryLearningEntity::class,
        PetMemoryStateEntity::class,
        ProactiveNotificationEntity::class,
        NotificationOutboxEntity::class,
        EventHistoryViewEntity::class,
    ],
    version = 9,
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

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `compliment_ledger` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `normalizedKey` TEXT NOT NULL, `complimentKey` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `petId`, `normalizedKey`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_compliment_owner_pet_created` ON `compliment_ledger` (`ownerId`, `petId`, `createdAtEpochMillis`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `applied_chat_responses` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `requestKey` TEXT NOT NULL, `happinessDelta` INTEGER NOT NULL, `complimentKey` TEXT, `appliedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `requestKey`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_applied_chat_responses_ownerId_petId` ON `applied_chat_responses` (`ownerId`, `petId`)")
            }
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `applied_outfit_receipts` ADD COLUMN `displayItem` TEXT NOT NULL DEFAULT ''")
            }
        }

        val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `event_history_views` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `lastViewedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ownerId`, `petId`))")
            }
        }

        val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pet_snapshots` ADD COLUMN `hungerTickAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pet_snapshots` ADD COLUMN `happinessTickAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pet_snapshots` ADD COLUMN `energyTickAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE `pet_snapshots`
                    SET `hungerTickAtEpochMillis` = `updatedAtEpochMillis`,
                        `happinessTickAtEpochMillis` = `updatedAtEpochMillis` + 7200000,
                        `energyTickAtEpochMillis` = `updatedAtEpochMillis` + 14400000
                    """.trimIndent(),
                )
            }
        }

        val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_chats` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `requestKey` TEXT NOT NULL, `message` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `responseText` TEXT, `completedAtEpochMillis` INTEGER, PRIMARY KEY(`ownerId`, `requestKey`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_chat_owner_pet_created` ON `pending_chats` (`ownerId`, `petId`, `createdAtEpochMillis`)",
                )
            }
        }

        val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_outbox` (`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, `kind` TEXT NOT NULL, `stableKey` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `storyId` TEXT, `travelRequestKey` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, `notifiedAtEpochMillis` INTEGER, PRIMARY KEY(`ownerId`, `kind`, `stableKey`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_outbox_owner_pet_pending` ON `notification_outbox` (`ownerId`, `petId`, `notifiedAtEpochMillis`, `createdAtEpochMillis`)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `notification_outbox` (`ownerId`, `petId`, `kind`, `stableKey`, `title`, `body`, `storyId`, `travelRequestKey`, `createdAtEpochMillis`, `notifiedAtEpochMillis`) SELECT `ownerId`, `petId`, 'ScheduledStory', `storyId`, `title`, substr(`text`, 1, 180), `storyId`, NULL, 0, NULL FROM `scheduled_stories` WHERE `notifiedAtEpochMillis` IS NULL",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `notification_outbox` (`ownerId`, `petId`, `kind`, `stableKey`, `title`, `body`, `storyId`, `travelRequestKey`, `createdAtEpochMillis`, `notifiedAtEpochMillis`) SELECT `ownerId`, `petId`, 'OutfitReady', `requestKey`, 'Новый образ готов', 'Загляни к питомцу и посмотри результат.', NULL, NULL, `appliedAtEpochMillis`, NULL FROM `applied_outfit_receipts` WHERE `notifiedAtEpochMillis` IS NULL",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `notification_outbox` (`ownerId`, `petId`, `kind`, `stableKey`, `title`, `body`, `storyId`, `travelRequestKey`, `createdAtEpochMillis`, `notifiedAtEpochMillis`) SELECT `ownerId`, `petId`, 'TravelReady', `requestKey`, 'Я вернулся из путешествия', 'Открой моё новое видео.', NULL, `requestKey`, `completedAtEpochMillis`, NULL FROM `travel_video_assets` WHERE `consumedAtEpochMillis` IS NOT NULL AND `notifiedAtEpochMillis` IS NULL",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `notification_outbox` (`ownerId`, `petId`, `kind`, `stableKey`, `title`, `body`, `storyId`, `travelRequestKey`, `createdAtEpochMillis`, `notifiedAtEpochMillis`) SELECT `ownerId`, `petId`, 'Proactive', `notificationId`, 'Сообщение от питомца', substr(`reply`, 1, 180), NULL, NULL, `createdAtEpochMillis`, NULL FROM `proactive_notifications` WHERE `notifiedAtEpochMillis` IS NULL",
                )
            }
        }

        val Migration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dashboard_command_receipts` " +
                        "(`ownerId` TEXT NOT NULL, `petId` TEXT NOT NULL, " +
                        "`requestKey` TEXT NOT NULL, `commandType` TEXT NOT NULL, " +
                        "`payloadFingerprint` TEXT NOT NULL, `originFirstSessionStage` TEXT, " +
                        "`food` TEXT, " +
                        "`audioIndex` INTEGER, `reply` TEXT, `explicitPortionsJson` TEXT, " +
                        "`autoAdvanceDelayMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`, `requestKey`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dashboard_command_owner_pet_created` " +
                        "ON `dashboard_command_receipts` (`ownerId`, `petId`, `createdAtEpochMillis`)",
                )
            }
        }

        fun build(context: Context): GigagochiDatabase = Room.databaseBuilder(
            context.applicationContext,
            GigagochiDatabase::class.java,
            DatabaseName,
        ).addMigrations(
            Migration1To2,
            Migration2To3,
            Migration3To4,
            Migration4To5,
            Migration5To6,
            Migration6To7,
            Migration7To8,
            Migration8To9,
        ).build()
    }
}
