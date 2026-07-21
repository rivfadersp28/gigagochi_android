package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatDecayMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration5To6StartsIndependentClocksFromLastSnapshotUpdate() {
        helper.createDatabase(DatabaseName, 5).apply {
            execSQL(
                "INSERT INTO pet_snapshots " +
                    "(ownerId, petId, assetSetId, description, name, stage, stageLabel, mood, " +
                    "experience, hunger, happiness, energy, message, petTapProgress, updatedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'asset', 'description', 'name', 'baby', 'Малыш', " +
                    "'idle', 0, 100, 80, 60, 'message', 0, 123456)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            6,
            true,
            GigagochiDatabase.Migration5To6,
        ).apply {
            query(
                "SELECT hungerTickAtEpochMillis, happinessTickAtEpochMillis, " +
                    "energyTickAtEpochMillis FROM pet_snapshots WHERE ownerId='owner'",
            ).use {
                check(it.moveToFirst())
                assertEquals(123456L, it.getLong(0))
                assertEquals(123456L + 2 * 60 * 60 * 1_000L, it.getLong(1))
                assertEquals(123456L + 4 * 60 * 60 * 1_000L, it.getLong(2))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "stat-decay-migration-test"
    }
}
