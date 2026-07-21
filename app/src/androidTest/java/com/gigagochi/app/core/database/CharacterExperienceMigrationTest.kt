package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterExperienceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration3To4PreservesOldOutfitReceiptsAndAddsDisplayItem() {
        helper.createDatabase(DatabaseName, 3).apply {
            execSQL(
                "INSERT INTO applied_outfit_receipts " +
                    "(ownerId, petId, requestKey, assetSetId, appliedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'request', 'asset', 10)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            4,
            true,
            GigagochiDatabase.Migration3To4,
        ).apply {
            query("SELECT displayItem FROM applied_outfit_receipts WHERE requestKey='request'").use {
                check(it.moveToFirst())
                assertEquals("", it.getString(0))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "character-experience-migration-test"
    }
}
