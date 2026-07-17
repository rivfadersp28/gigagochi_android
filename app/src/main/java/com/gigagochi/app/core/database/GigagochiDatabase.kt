package com.gigagochi.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DatabaseTypeConverters::class)
abstract class GigagochiDatabase : RoomDatabase() {
    internal abstract fun gigagochiDao(): GigagochiDao

    companion object {
        const val DatabaseName = "gigagochi-local.db"

        fun build(context: Context): GigagochiDatabase = Room.databaseBuilder(
            context.applicationContext,
            GigagochiDatabase::class.java,
            DatabaseName,
        ).build()
    }
}
