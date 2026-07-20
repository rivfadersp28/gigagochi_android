package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComplimentMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration2To3PreservesPetsAndCreatesComplimentTables() {
        helper.createDatabase(DatabaseName, 2).apply {
            execSQL(
                "INSERT INTO pet_snapshots " +
                    "(ownerId, petId, assetSetId, description, name, stage, stageLabel, mood, " +
                    "experience, hunger, happiness, energy, message, petTapProgress, updatedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'asset', 'description', 'name', 'baby', 'Малыш', " +
                    "'idle', 0, 100, 40, 100, 'message', 0, 1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            3,
            true,
            GigagochiDatabase.Migration2To3,
        ).apply {
            query("SELECT happiness FROM pet_snapshots WHERE ownerId='owner' AND petId='pet'").use {
                check(it.moveToFirst())
                assertEquals(40, it.getInt(0))
            }
            execSQL(
                "INSERT INTO compliment_ledger " +
                    "(ownerId, petId, normalizedKey, complimentKey, createdAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'смелый', 'Смелый', 2)",
            )
            query("SELECT complimentKey FROM compliment_ledger").use {
                check(it.moveToFirst())
                assertEquals("Смелый", it.getString(0))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "compliment-migration-test"
    }
}
