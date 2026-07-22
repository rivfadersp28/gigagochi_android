package com.gigagochi.app.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationOutboxMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigagochiDatabase::class.java,
    )

    @Test
    fun migration7To8BackfillsEveryExistingPendingNotificationKind() {
        helper.createDatabase(DatabaseName, 7).apply {
            execSQL(
                "INSERT INTO proactive_notifications " +
                    "(ownerId, petId, notificationId, reply, memoryIdsJson, " +
                    "createdAtEpochMillis, notifiedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'proactive-key', 'Привет', '[]', 10, NULL)",
            )
            execSQL(
                "INSERT INTO applied_outfit_receipts " +
                    "(ownerId, petId, requestKey, assetSetId, displayItem, appliedAtEpochMillis, " +
                    "notifiedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'outfit-key', 'asset', 'футболка', 20, NULL)",
            )
            execSQL(
                "INSERT INTO travel_video_assets " +
                    "(ownerId, petId, requestKey, backendJobId, prompt, title, scenario, imageUrl, " +
                    "videoUrl, completedAtEpochMillis, consumedAtEpochMillis, notifiedAtEpochMillis) " +
                    "VALUES ('owner', 'pet', 'travel-key', 'job', 'концерт', NULL, NULL, NULL, " +
                    "'https://example.com/video.mp4', 30, 31, NULL)",
            )
            execSQL(
                "INSERT INTO scheduled_stories " +
                    "(ownerId, storyId, petId, title, text, question, choice0, choice1, choice2, " +
                    "choice3, createdAt, imageUrl, videoUrl, choiceRequestKey, pendingChoice, " +
                    "selectedChoice, resultText, resultReaction, resultConsequence, " +
                    "resultExperienceGained, resultImageUrl, resultVideoUrl, notifiedAtEpochMillis) " +
                    "VALUES ('owner', 'story-key', 'pet', 'История', 'Приключение', 'Что делать?', " +
                    "'A', 'B', 'C', 'D', '2026-07-22T10:00:00Z', NULL, NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            8,
            true,
            GigagochiDatabase.Migration7To8,
        ).apply {
            query("SELECT kind FROM notification_outbox ORDER BY kind").use { cursor ->
                val kinds = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(
                    listOf("OutfitReady", "Proactive", "ScheduledStory", "TravelReady"),
                    kinds,
                )
            }
            close()
        }
    }

    private companion object {
        const val DatabaseName = "notification-outbox-migration-test"
    }
}
