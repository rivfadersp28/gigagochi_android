package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardCommandReceiptMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration8To9PreservesExistingRowsAndCreatesUniqueReceiptIdentity() {
        helper.createDatabase(DatabaseName, 8).apply {
            execSQL(
                "INSERT INTO pending_chats " +
                    "(ownerId, petId, requestKey, message, createdAtEpochMillis, " +
                    "responseText, completedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'chat-before-migration', 'Привет', 10, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO first_session_action_receipts " +
                    "(ownerId, petId, actionKey, actionKind, appliedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'onboarding-before-migration', 'stage', 11)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            9,
            true,
            GigagochiDatabase.Migration8To9,
        ).apply {
            query("SELECT message FROM pending_chats WHERE requestKey = 'chat-before-migration'")
                .use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("Привет", cursor.getString(0))
                }
            query(
                "SELECT actionKind FROM first_session_action_receipts " +
                    "WHERE actionKey = 'onboarding-before-migration'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("stage", cursor.getString(0))
            }
            execSQL(
                "INSERT INTO dashboard_command_receipts " +
                    "(ownerId, petId, requestKey, commandType, payloadFingerprint, " +
                    "originFirstSessionStage, food, " +
                    "audioIndex, reply, explicitPortionsJson, autoAdvanceDelayMillis, " +
                    "createdAtEpochMillis) VALUES " +
                    "('owner', 'pet', 'feed-key', 'feed-consume', 'hash-a', NULL, 'berry-bowl', " +
                    "0, 'Ням', NULL, 6000, 12)",
            )
            execSQL(
                "INSERT OR IGNORE INTO dashboard_command_receipts " +
                    "(ownerId, petId, requestKey, commandType, payloadFingerprint, food, " +
                    "audioIndex, reply, explicitPortionsJson, autoAdvanceDelayMillis, " +
                    "createdAtEpochMillis) VALUES " +
                    "('owner', 'pet', 'feed-key', 'feed-consume', 'hash-b', 'leaf-crunch', " +
                    "1, 'Лист', NULL, 6000, 13)",
            )
            query(
                "SELECT payloadFingerprint, food, audioIndex, reply " +
                    "FROM dashboard_command_receipts WHERE ownerId = 'owner' " +
                    "AND requestKey = 'feed-key'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("hash-a", cursor.getString(0))
                assertEquals("berry-bowl", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals("Ням", cursor.getString(3))
            }
            execSQL(
                "INSERT INTO dashboard_command_receipts " +
                    "(ownerId, petId, requestKey, commandType, payloadFingerprint, " +
                    "originFirstSessionStage, food, audioIndex, reply, explicitPortionsJson, " +
                    "autoAdvanceDelayMillis, createdAtEpochMillis) VALUES " +
                    "('owner', 'pet', 'chat-key', 'chat-send', 'hash-chat', " +
                    "'awaiting-chat', NULL, NULL, NULL, NULL, NULL, 14)",
            )
            query(
                "SELECT originFirstSessionStage FROM dashboard_command_receipts " +
                    "WHERE ownerId = 'owner' AND requestKey = 'chat-key'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("awaiting-chat", cursor.getString(0))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "dashboard-command-receipt-migration-test"
    }
}
