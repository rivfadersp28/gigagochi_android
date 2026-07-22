package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingChatMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration6To7CreatesDurablePendingChatTable() {
        helper.createDatabase(DatabaseName, 6).close()

        helper.runMigrationsAndValidate(
            DatabaseName,
            7,
            true,
            GigagochiDatabase.Migration6To7,
        ).apply {
            execSQL(
                "INSERT INTO pending_chats " +
                    "(ownerId, petId, requestKey, message, createdAtEpochMillis, responseText, " +
                    "completedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'request', 'hello', 42, NULL, NULL)",
            )
            query("SELECT message, createdAtEpochMillis FROM pending_chats").use {
                check(it.moveToFirst())
                assertEquals("hello", it.getString(0))
                assertEquals(42L, it.getLong(1))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "pending-chat-migration-test"
    }
}
