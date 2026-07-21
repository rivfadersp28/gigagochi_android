package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventHistoryViewMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration4To5PreservesEventsAndCreatesViewWatermark() {
        helper.createDatabase(DatabaseName, 4).apply {
            execSQL(
                "INSERT INTO scheduled_stories " +
                    "(ownerId, storyId, petId, title, text, question, choice0, choice1, " +
                    "choice2, choice3, createdAt, imageUrl, videoUrl, choiceRequestKey, " +
                    "pendingChoice, selectedChoice, resultText, resultReaction, " +
                    "resultConsequence, resultExperienceGained, resultImageUrl, resultVideoUrl, " +
                    "notifiedAtEpochMillis) VALUES " +
                    "('owner', 'story', 'pet', 'title', 'text', 'question', 'a', 'b', 'c', 'd', " +
                    "'2026-07-21T10:00:00Z', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            5,
            true,
            GigagochiDatabase.Migration4To5,
        ).apply {
            query("SELECT title FROM scheduled_stories WHERE storyId='story'").use {
                check(it.moveToFirst())
                assertEquals("title", it.getString(0))
            }
            execSQL(
                "INSERT INTO event_history_views " +
                    "(ownerId, petId, lastViewedAtEpochMillis) VALUES ('owner', 'pet', 42)",
            )
            query("SELECT lastViewedAtEpochMillis FROM event_history_views").use {
                check(it.moveToFirst())
                assertEquals(42L, it.getLong(0))
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "event-history-view-migration-test"
    }
}
