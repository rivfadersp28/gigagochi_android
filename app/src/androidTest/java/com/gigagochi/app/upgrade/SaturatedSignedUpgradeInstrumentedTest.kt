package com.gigagochi.app.upgrade

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hash-bound, APK-to-APK recovery fixture.
 *
 * The androidTest APK is deliberately signed with the production certificate by the QA script and
 * first installed next to the exact 0.1.11 release. Android instrumentation therefore executes
 * this test in the target process and resolves application classes from whichever signed target
 * APK is currently installed:
 *
 *  1. [seedExactV8StateAndQueuedWorkers] creates a real Room v8 database through 0.1.11, fills
 *     every v8 entity table, persists an encrypted offline session, and enqueues all old workers.
 *  2. The QA script performs `adb install -r` with the exact 0.1.13 release while offline.
 *  3. [verifyV9MigrationOfflineAndExactlyOnceLocalRecovery] proves byte-for-byte logical
 *     preservation before the first WebView launch, then proves ready Outfit/Travel outcomes are
 *     applied exactly once by the production runtime.
 *  4. The emulator is rebooted and [verifyStateAndWorkersAfterReboot] proves the same durable state
 *     and worker class names still load.
 *
 * This class intentionally references only application APIs that exist unchanged in 0.1.11 and
 * 0.1.13. The WebView activity is discovered through the launcher Intent rather than linked as a
 * class so the seed phase can run against the native 0.1.11 APK.
 */
@RunWith(AndroidJUnit4::class)
class SaturatedSignedUpgradeInstrumentedTest {
    @Test
    fun seedExactV8StateAndQueuedWorkers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertInstalledVersion(target, expectedCode = 12L, expectedName = "0.1.11")
        assertOffline(target)

        target.deleteDatabase(DatabaseName)
        createV8Database(target)
        val databaseFile = target.getDatabasePath(DatabaseName)
        assertTrue(databaseFile.isFile)

        openDatabase(databaseFile).use { database ->
            assertEquals(8, database.version)
            V8Tables.forEach { table ->
                assertEquals("Expected a clean old-release table: $table", 0L, rowCount(database, table))
            }
            seedEveryV8Table(database, System.currentTimeMillis())
            V8Tables.forEach { table ->
                assertTrue("Expected saturated old-release table: $table", rowCount(database, table) > 0L)
            }
            val snapshot = canonicalSnapshot(database, V8Tables)
            writeEvidence("v8-before-upgrade.snapshot", snapshot)
            writeEvidence("v8-before-upgrade.sha256", sha256(snapshot))
        }

        persistEncryptedOfflineSession(target)
        assertEncryptedSessionDoesNotContainPlaintext(target)

        enqueueOldWorkerContracts(target)
        waitForExpectedWork(target)
        val work = canonicalWorkSnapshot(target)
        assertExpectedWorkers(work)
        writeEvidence("v8-before-upgrade.work", work)
    }

    @Test
    fun verifyV9MigrationOfflineAndExactlyOnceLocalRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertInstalledVersion(target, expectedCode = 14L, expectedName = "0.1.13")
        assertOffline(target)

        val databaseFile = target.getDatabasePath(DatabaseName)
        openDatabase(databaseFile).use { database ->
            assertEquals(8, database.version)
            assertEquals(
                readEvidence("v8-before-upgrade.snapshot"),
                canonicalSnapshot(database, V8Tables),
            )
            migrateInstalledV8ToV9(database, instrumentation)
        }
        openDatabase(databaseFile).use { database ->
            assertEquals(9, database.version)
            assertEquals(
                readEvidence("v8-before-upgrade.snapshot"),
                canonicalSnapshot(database, V8Tables),
            )
            assertEquals(0L, rowCount(database, "dashboard_command_receipts"))
            writeEvidence(
                "v9-after-migration-before-launch.sha256",
                sha256(canonicalSnapshot(database, V8Tables)),
            )
        }

        val migrationDocument = launchProductionWebViewAndReadDocument(
            instrumentation,
            target,
            requiredText = RecoveredReply,
        )
        assertTrue(migrationDocument.startsWith("dashboard\n"))
        assertTrue(migrationDocument.contains(RecoveredReply))
        openDatabase(databaseFile).use(::assertExpectedPostBootstrapState)

        openDatabase(databaseFile).use { database ->
            database.beginTransaction()
            try {
                assertEquals(
                    1,
                    database.compileStatement(
                        """
                        UPDATE pending_outfits SET backendState = 'Ready', backendErrorCode = NULL
                        WHERE ownerId = ? AND requestKey = ? AND backendState = 'Attached'
                        """.trimIndent(),
                    ).apply {
                        bindString(1, OwnerId)
                        bindString(2, OutfitRequestKey)
                    }.executeUpdateDelete(),
                )
                assertEquals(
                    1,
                    database.compileStatement(
                        """
                        UPDATE pending_travel_videos
                        SET backendState = 'Ready', backendErrorCode = NULL
                        WHERE ownerId = ? AND requestKey = ? AND backendState = 'Attached'
                        """.trimIndent(),
                    ).apply {
                        bindString(1, OwnerId)
                        bindString(2, TravelRequestKey)
                    }.executeUpdateDelete(),
                )
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            writeEvidence(
                "v9-armed-local-recovery.sha256",
                sha256(canonicalSnapshot(database, V9Tables)),
            )
        }

        val workAfterMigration = canonicalWorkSnapshot(target)
        assertExpectedWorkers(workAfterMigration)
        assertEquals(
            canonicalWorkIdentity(readEvidence("v8-before-upgrade.work")),
            canonicalWorkIdentity(workAfterMigration),
        )

        val firstDocument = launchProductionWebViewAndReadDocument(
            instrumentation,
            target,
            settleAfterDashboard = true,
        )
        assertTrue(firstDocument.startsWith("dashboard\n"))
        waitForRecoveredRows(databaseFile)

        val afterFirst = openDatabase(databaseFile).use { database ->
            assertExactlyOnceRecoveredState(database)
            canonicalSnapshot(database, V9Tables)
        }
        writeEvidence("v9-after-first-launch.snapshot", afterFirst)
        writeEvidence("v9-after-first-launch.sha256", sha256(afterFirst))

        val secondDocument = launchProductionWebViewAndReadDocument(instrumentation, target)
        assertTrue(secondDocument.startsWith("dashboard\n"))
        val afterSecond = openDatabase(databaseFile).use { database ->
            assertExactlyOnceRecoveredState(database)
            canonicalSnapshot(database, V9Tables)
        }
        assertEquals(afterFirst, afterSecond)
        assertEncryptedSessionDoesNotContainPlaintext(target)
    }

    @Test
    fun verifyStateAndWorkersAfterReboot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertInstalledVersion(target, expectedCode = 14L, expectedName = "0.1.13")
        assertOffline(target)

        val databaseFile = target.getDatabasePath(DatabaseName)
        val rebootSnapshot = openDatabase(databaseFile).use { database ->
            assertExactlyOnceRecoveredState(database)
            canonicalSnapshot(database, V9Tables)
        }
        assertEquals(readEvidence("v9-after-first-launch.snapshot"), rebootSnapshot)
        writeEvidence("v9-after-reboot.snapshot", rebootSnapshot)
        writeEvidence("v9-after-reboot.sha256", sha256(rebootSnapshot))

        val work = canonicalWorkSnapshot(target)
        assertExpectedWorkers(work)
        assertEquals(
            canonicalWorkIdentity(readEvidence("v8-before-upgrade.work")),
            canonicalWorkIdentity(work),
        )
        writeEvidence("v9-after-reboot.work", work)

        val document = launchProductionWebViewAndReadDocument(instrumentation, target)
        assertTrue(document.startsWith("dashboard\n"))
        val afterLaunch = openDatabase(databaseFile).use { database ->
            assertExactlyOnceRecoveredState(database)
            canonicalSnapshot(database, V9Tables)
        }
        assertEquals(rebootSnapshot, afterLaunch)
        assertEncryptedSessionDoesNotContainPlaintext(target)
    }

    private fun seedEveryV8Table(database: SQLiteDatabase, now: Long) {
        val mediaRoot = "https://gigagochi.serega.works/media/upgrade-fixture"
        database.beginTransaction()
        try {
            database.exec(
                """
                INSERT INTO pet_snapshots (
                    ownerId, petId, assetSetId, description, name, stage, stageLabel, mood,
                    experience, hunger, happiness, energy, message, petTapProgress, generatedAt,
                    videoUrl, sadVideoUrl, happyVideoUrl, blinkImageUrl, spriteSheetUrl,
                    characterBibleJson, hungerTickAtEpochMillis, happinessTickAtEpochMillis,
                    energyTickAtEpochMillis, updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                BaseAssetSetId,
                "Тестовый хранитель данных между версиями",
                PetName,
                "teen",
                "Подросток",
                "idle",
                900,
                73,
                81,
                67,
                "Я дождался обновления.",
                4,
                "2026-07-23T08:00:00Z",
                "$mediaRoot/base-idle.mp4",
                "$mediaRoot/base-sad.mp4",
                "$mediaRoot/base-happy.mp4",
                "$mediaRoot/base-blink.webp",
                null,
                "{}",
                now,
                now,
                now,
                now,
            )
            database.exec(
                """
                INSERT INTO pending_create_generations (
                    ownerId, petId, requestKey, backendJobId, stage, description, name,
                    personality, fear, favoriteItem, currentStep, updatedAtEpochMillis,
                    backendState, backendErrorCode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                "upgrade-create-pet",
                "upgrade-create-request",
                null,
                "Generating",
                "Незавершённый персонаж",
                "Черновичок",
                "бережный",
                "гроза",
                "фонарь",
                5,
                now + 1,
                "Retryable",
                "OFFLINE_FIXTURE",
            )
            database.exec(
                """
                INSERT INTO pending_outfits (
                    ownerId, petId, requestKey, localJobId, backendJobId, prompt, baseAssetSetId,
                    acceptedAtEpochMillis, experienceCost, backendState, backendErrorCode,
                    preparedDisplayItem
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                OutfitRequestKey,
                "upgrade-outfit-local",
                "upgrade-outfit-backend",
                "жёлтый дождевик",
                BaseAssetSetId,
                now + 2,
                200,
                "Attached",
                null,
                "жёлтый дождевик",
            )
            database.exec(
                """
                INSERT INTO pending_travel_videos (
                    ownerId, petId, requestKey, localJobId, backendJobId, prompt,
                    acceptedAtEpochMillis, backendState, backendErrorCode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                TravelRequestKey,
                "upgrade-travel-local",
                "upgrade-travel-backend",
                "поездка к северному маяку",
                now + 3,
                "Attached",
                null,
            )
            database.exec(
                """
                INSERT INTO applied_story_receipts (
                    ownerId, petId, receiptKey, travelId, partKey, experienceDelta,
                    hungerDelta, happinessDelta, energyDelta, appliedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-story-receipt",
                "upgrade-travel-history",
                "part-1",
                40,
                -3,
                7,
                -2,
                now - 4_000,
            )
            listOf("idle", "sad", "happy").forEach { mood ->
                database.exec(
                    """
                    INSERT INTO pet_mood_images (ownerId, petId, stage, mood, url)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    OwnerId,
                    PetId,
                    "teen",
                    mood,
                    "$mediaRoot/base-$mood.webp",
                )
            }
            database.exec(
                """
                INSERT INTO travel_video_assets (
                    ownerId, petId, requestKey, backendJobId, prompt, title, scenario, imageUrl,
                    videoUrl, completedAtEpochMillis, consumedAtEpochMillis, notifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                TravelRequestKey,
                "upgrade-travel-backend",
                "поездка к северному маяку",
                "Северный маяк",
                "Питомец находит свет в тумане.",
                "$mediaRoot/travel.webp",
                "$mediaRoot/travel.mp4",
                now + 4,
                null,
                null,
            )
            database.exec(
                """
                INSERT INTO outfit_media_outcomes (
                    ownerId, petId, requestKey, backendJobId, displayItem, assetSetId, generatedAt,
                    videoUrl, sadVideoUrl, happyVideoUrl, blinkImageUrl, spriteSheetUrl,
                    characterBibleJson, completedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                OutfitRequestKey,
                "upgrade-outfit-backend",
                "жёлтый дождевик",
                OutfitAssetSetId,
                "2026-07-23T08:00:01Z",
                "$mediaRoot/outfit-idle.mp4",
                "$mediaRoot/outfit-sad.mp4",
                "$mediaRoot/outfit-happy.mp4",
                "$mediaRoot/outfit-blink.webp",
                null,
                "{}",
                now + 5,
            )
            listOf("idle", "sad", "happy").forEach { mood ->
                database.exec(
                    """
                    INSERT INTO outfit_mood_images (ownerId, requestKey, stage, mood, url)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    OwnerId,
                    OutfitRequestKey,
                    "teen",
                    mood,
                    "$mediaRoot/outfit-$mood.webp",
                )
            }
            database.exec(
                """
                INSERT INTO applied_outfit_receipts (
                    ownerId, petId, requestKey, assetSetId, displayItem, appliedAtEpochMillis,
                    notifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-prior-outfit",
                BaseAssetSetId,
                "старый шарф",
                now - 3_000,
                now - 2_000,
            )
            database.exec(
                """
                INSERT INTO scheduled_stories (
                    ownerId, storyId, petId, title, text, question, choice0, choice1, choice2,
                    choice3, createdAt, imageUrl, videoUrl, choiceRequestKey, pendingChoice,
                    selectedChoice, resultText, resultReaction, resultConsequence,
                    resultExperienceGained, resultImageUrl, resultVideoUrl, notifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                StoryId,
                PetId,
                "Письмо из тумана",
                "У старого моста лежит непрочитанное письмо.",
                "Что сделать с письмом?",
                "Открыть",
                "Отнести ворону",
                "Спрятать",
                "Оставить на месте",
                "2099-07-23T08:00:00Z",
                "$mediaRoot/story.webp",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )
            database.exec(
                """
                INSERT INTO first_sessions (
                    ownerId, petId, stage, selectedDestination, lastActionKey, updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "completed",
                "Северный маяк",
                "upgrade-onboarding-completed",
                now - 2_000,
            )
            database.exec(
                """
                INSERT INTO first_session_action_receipts (
                    ownerId, petId, actionKey, actionKind, appliedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-onboarding-completed",
                "outfit",
                now - 2_000,
            )
            database.exec(
                """
                INSERT INTO chat_messages (
                    ownerId, petId, messageId, role, text, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-chat-user",
                "user",
                "Ты помнишь северный маяк?",
                now - 1_500,
            )
            database.exec(
                """
                INSERT INTO chat_messages (
                    ownerId, petId, messageId, role, text, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-chat-pet",
                "pet",
                "Помню. Там туман светится у самой воды.",
                now - 1_400,
            )
            database.exec(
                """
                INSERT INTO pending_chats (
                    ownerId, petId, requestKey, message, createdAtEpochMillis, responseText,
                    completedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-chat-completed",
                "Что было до обновления?",
                now - 1_300,
                RecoveredReply,
                now - 1_200,
            )
            database.exec(
                """
                INSERT INTO pending_chats (
                    ownerId, petId, requestKey, message, createdAtEpochMillis, responseText,
                    completedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-chat-pending",
                "Ответь, когда сеть вернётся.",
                now - 1_100,
                null,
                null,
            )
            database.exec(
                """
                INSERT INTO compliment_ledger (
                    ownerId, petId, normalizedKey, complimentKey, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "ты-смелый",
                "смелый",
                now - 1_000,
            )
            database.exec(
                """
                INSERT INTO applied_chat_responses (
                    ownerId, petId, requestKey, happinessDelta, complimentKey, appliedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-chat-applied",
                30,
                "смелый",
                now - 900,
            )
            database.exec(
                """
                INSERT INTO user_memories (
                    ownerId, petId, memoryId, kind, text, normalizedKey, confidence, importance,
                    memoryClass, recordedAtEpochMillis, occurredAtEpochMillis, dueAtEpochMillis,
                    expiresAtEpochMillis, lastMentionedAtEpochMillis, mentionCount, tagsJson,
                    updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-memory",
                "preference",
                "Пользователь любит северные маяки.",
                "любит-северные-маяки",
                0.9,
                0.8,
                "long_term",
                now - 800,
                now - 800,
                null,
                null,
                now - 700,
                2,
                "[\"travel\"]",
                now - 700,
            )
            database.exec(
                """
                INSERT INTO memory_learnings (
                    ownerId, petId, learningId, status, observation, patternKey, kind, confidence,
                    importance, recurrenceCount, firstSeenAtEpochMillis, lastSeenAtEpochMillis,
                    occurredAtEpochMillis, dueAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-learning",
                "pending",
                "Пользователь возвращается к историям о море.",
                "sea-stories",
                "preference",
                0.7,
                0.6,
                2,
                now - 700,
                now - 600,
                now - 600,
                null,
            )
            database.exec(
                """
                INSERT INTO pet_memory_state (
                    ownerId, petId, summary, userProfile, lastExtractionAtEpochMillis,
                    lastConsolidationAtEpochMillis, lastProactiveAtEpochMillis, proactiveLogJson,
                    updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "Мы говорили о маяках и тумане.",
                "Любит спокойные путешествия.",
                now - 600,
                now - 500,
                now - 400,
                "[]",
                now - 400,
            )
            database.exec(
                """
                INSERT INTO proactive_notifications (
                    ownerId, petId, notificationId, reply, memoryIdsJson, createdAtEpochMillis,
                    notifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "upgrade-proactive",
                "У маяка сегодня тихо. Вспомнил о тебе.",
                "[\"upgrade-memory\"]",
                now - 300,
                null,
            )
            database.exec(
                """
                INSERT INTO notification_outbox (
                    ownerId, petId, kind, stableKey, title, body, storyId, travelRequestKey,
                    createdAtEpochMillis, notifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                OwnerId,
                PetId,
                "ScheduledStory",
                StoryId,
                "Письмо из тумана",
                "У старого моста лежит непрочитанное письмо.",
                StoryId,
                null,
                now - 200,
                null,
            )
            database.exec(
                """
                INSERT INTO event_history_views (
                    ownerId, petId, lastViewedAtEpochMillis
                ) VALUES (?, ?, ?)
                """,
                OwnerId,
                PetId,
                now - 100,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun assertExactlyOnceRecoveredState(database: SQLiteDatabase) {
        assertEquals(9, database.version)
        assertEquals(1L, rowCount(database, "pending_create_generations"))
        assertEquals(0L, rowCount(database, "pending_outfits"))
        assertEquals(0L, rowCount(database, "outfit_media_outcomes"))
        assertEquals(0L, rowCount(database, "outfit_mood_images"))
        assertEquals(2L, rowCount(database, "applied_outfit_receipts"))
        assertEquals(0L, rowCount(database, "pending_travel_videos"))
        assertEquals(1L, rowCount(database, "travel_video_assets"))
        assertEquals(1L, rowCount(database, "pending_chats"))
        assertEquals(
            0L,
            scalarLong(
                database,
                "SELECT COUNT(*) FROM pending_chats WHERE ownerId = ? AND requestKey = ?",
                OwnerId,
                CompletedChatRequestKey,
            ),
        )
        assertEquals(
            1L,
            scalarLong(
                database,
                "SELECT COUNT(*) FROM pending_chats WHERE ownerId = ? AND requestKey = ?",
                OwnerId,
                PendingChatRequestKey,
            ),
        )
        assertEquals(1L, rowCount(database, "dashboard_command_receipts"))
        assertEquals(
            1L,
            scalarLong(
                database,
                """
                SELECT COUNT(*) FROM dashboard_command_receipts
                WHERE ownerId = ? AND petId = ? AND requestKey = ? AND commandType = 'chat-send'
                """,
                OwnerId,
                PetId,
                PendingChatRequestKey,
            ),
        )
        assertEquals(
            OutfitAssetSetId,
            stringValue(
                database,
                "SELECT assetSetId FROM pet_snapshots WHERE ownerId = ? AND petId = ?",
                OwnerId,
                PetId,
            ),
        )
        assertNotNull(
            longValue(
                database,
                """
                SELECT consumedAtEpochMillis FROM travel_video_assets
                WHERE ownerId = ? AND requestKey = ?
                """,
                OwnerId,
                TravelRequestKey,
            ),
        )
        assertEquals(
            1L,
            scalarLong(
                database,
                """
                SELECT COUNT(*) FROM applied_outfit_receipts
                WHERE ownerId = ? AND requestKey = ?
                """,
                OwnerId,
                OutfitRequestKey,
            ),
        )
        assertEquals(
            1L,
            scalarLong(
                database,
                """
                SELECT COUNT(*) FROM notification_outbox
                WHERE ownerId = ? AND kind = 'OutfitReady' AND stableKey = ?
                """,
                OwnerId,
                OutfitRequestKey,
            ),
        )
        assertEquals(
            1L,
            scalarLong(
                database,
                """
                SELECT COUNT(*) FROM notification_outbox
                WHERE ownerId = ? AND kind = 'TravelReady' AND stableKey = ?
                """,
                OwnerId,
                TravelRequestKey,
            ),
        )
    }

    private fun launchProductionWebViewAndReadDocument(
        instrumentation: android.app.Instrumentation,
        target: Context,
        settleAfterDashboard: Boolean = false,
        requiredText: String? = null,
    ): String {
        val launchIntent = target.packageManager.getLaunchIntentForPackage(target.packageName)
        assertNotNull(launchIntent)
        val activity = instrumentation.startActivitySync(
            requireNotNull(launchIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            var latest = ""
            var dashboardReadyAt: Long? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                latest = readWebDocument(instrumentation, activity) ?: latest
                if (
                    latest.startsWith("dashboard\n") &&
                    (requiredText == null || latest.contains(requiredText))
                ) {
                    if (!settleAfterDashboard) return latest
                    val readyAt = dashboardReadyAt
                        ?: SystemClock.elapsedRealtime().also { dashboardReadyAt = it }
                    if (SystemClock.elapsedRealtime() - readyAt >= 1_500L) return latest
                } else {
                    dashboardReadyAt = null
                }
                SystemClock.sleep(200L)
            }
            throw AssertionError("WebView did not expose the offline Dashboard: $latest")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500L)
        }
    }

    private fun waitForRecoveredRows(databaseFile: File) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var latest = "database unavailable"
        while (SystemClock.elapsedRealtime() < deadline) {
            val ready = runCatching {
                openDatabase(databaseFile).use { database ->
                    val pendingOutfits = rowCount(database, "pending_outfits")
                    val pendingTravel = rowCount(database, "pending_travel_videos")
                    val notified = scalarLong(
                        database,
                        """
                        SELECT COUNT(*) FROM notification_outbox
                        WHERE ownerId = ? AND notifiedAtEpochMillis IS NOT NULL
                          AND (
                            (kind = 'OutfitReady' AND stableKey = ?) OR
                            (kind = 'TravelReady' AND stableKey = ?)
                          )
                        """,
                        OwnerId,
                        OutfitRequestKey,
                        TravelRequestKey,
                    )
                    latest =
                        "pendingOutfits=$pendingOutfits pendingTravel=$pendingTravel notified=$notified"
                    pendingOutfits == 0L && pendingTravel == 0L && notified == 2L
                }
            }.getOrElse {
                latest = "${it.javaClass.simpleName}: ${it.message}"
                false
            }
            if (ready) return
            SystemClock.sleep(200L)
        }
        throw AssertionError("Local recovery did not settle: $latest")
    }

    private fun readWebDocument(
        instrumentation: android.app.Instrumentation,
        activity: Activity,
    ): String? {
        val webView = AtomicReference<WebView?>()
        instrumentation.runOnMainSync {
            webView.set(findWebView(activity.window.decorView))
        }
        val resolved = webView.get() ?: return null
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            resolved.evaluateJavascript(
                """
                (() => {
                  const route = document.querySelector('[data-route]')?.getAttribute('data-route') ?? '';
                  return route + '\n' + (document.body?.innerText ?? '');
                })()
                """.trimIndent(),
            ) { raw ->
                result.set(
                    runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull(),
                )
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun migrateInstalledV8ToV9(
        database: SQLiteDatabase,
        instrumentation: android.app.Instrumentation,
    ) {
        val databaseClass = Class.forName(
            "com.gigagochi.app.core.database.GigagochiDatabase",
        )
        val migration = databaseClass.declaredFields
            .asSequence()
            .filter { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                runCatching { field.get(null) }.getOrNull()
            }
            .firstOrNull(::isEightToNineMigration)
        assertNotNull("Installed release does not expose its v8 to v9 migration", migration)
        val installedMigration = requireNotNull(migration)
        val migrateMethod = installedMigration.javaClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.returnType == Void.TYPE &&
                runCatching {
                    method.parameterTypes.single()
                        .getDeclaredConstructor(SQLiteDatabase::class.java)
                }.isSuccess
        }
        assertNotNull("Installed release migration cannot wrap SQLiteDatabase", migrateMethod)
        val method = requireNotNull(migrateMethod).apply { isAccessible = true }
        val wrapper = method.parameterTypes.single()
            .getDeclaredConstructor(SQLiteDatabase::class.java)
            .apply { isAccessible = true }
            .newInstance(database)
        val identityHash = instrumentation.context.assets.open(Schema9Asset)
            .bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database").getString("identityHash") }

        database.beginTransaction()
        try {
            method.invoke(installedMigration, wrapper)
            database.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, ?)",
                arrayOf(identityHash),
            )
            database.version = 9
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun isEightToNineMigration(candidate: Any): Boolean {
        var type: Class<*>? = candidate.javaClass
        while (type != null && type != Any::class.java) {
            val versionValues = type.declaredFields
                .filter {
                    !Modifier.isStatic(it.modifiers) &&
                        it.type == Integer.TYPE
                }
                .map { field ->
                    field.isAccessible = true
                    field.getInt(candidate)
                }
            if (versionValues.contains(8) && versionValues.contains(9)) return true
            type = type.superclass
        }
        return false
    }

    private fun assertExpectedPostBootstrapState(database: SQLiteDatabase) {
        assertEquals(9, database.version)
        assertEquals(1L, rowCount(database, "pending_chats"))
        assertEquals(
            0L,
            scalarLong(
                database,
                "SELECT COUNT(*) FROM pending_chats WHERE ownerId = ? AND requestKey = ?",
                OwnerId,
                CompletedChatRequestKey,
            ),
        )
        assertEquals(
            1L,
            scalarLong(
                database,
                "SELECT COUNT(*) FROM pending_chats WHERE ownerId = ? AND requestKey = ?",
                OwnerId,
                PendingChatRequestKey,
            ),
        )
        assertEquals(1L, rowCount(database, "pending_outfits"))
        assertEquals(1L, rowCount(database, "pending_travel_videos"))
        assertEquals(1L, rowCount(database, "dashboard_command_receipts"))
        assertEquals(
            1L,
            scalarLong(
                database,
                """
                SELECT COUNT(*) FROM dashboard_command_receipts
                WHERE ownerId = ? AND petId = ? AND requestKey = ? AND commandType = 'chat-send'
                """,
                OwnerId,
                PetId,
                PendingChatRequestKey,
            ),
        )
    }

    private fun createV8Database(context: Context) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema = instrumentation.context.assets.open(Schema8Asset).bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        val file = context.getDatabasePath(DatabaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (entityIndex in 0 until entities.length()) {
                    val entity = entities.getJSONObject(entityIndex)
                    val table = entity.getString("tableName")
                    database.execSQL(
                        entity.getString("createSql").replace("\${TABLE_NAME}", table),
                    )
                    entity.optJSONArray("indices")?.let { indices ->
                        for (index in 0 until indices.length()) {
                            database.execSQL(
                                indices.getJSONObject(index).getString("createSql")
                                    .replace("\${TABLE_NAME}", table),
                            )
                        }
                    }
                }
                val setupQueries = schema.getJSONArray("setupQueries")
                for (index in 0 until setupQueries.length()) {
                    database.execSQL(setupQueries.getString(index))
                }
                database.version = 8
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun persistEncryptedOfflineSession(context: Context) {
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(SessionPayloadMagic)
                output.writeInt(SessionEnvelopeVersion)
                output.writeLong(4_102_444_800_000L)
                output.writeSized(OwnerId.toByteArray(StandardCharsets.UTF_8))
                output.writeSized(FakeAccessToken.toByteArray(StandardCharsets.UTF_8))
                output.writeBoolean(false)
            }
            bytes.toByteArray()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey())
            cipher.updateAAD(SessionAssociatedData)
            val ciphertext = cipher.doFinal(plaintext)
            assertTrue(
                context.getSharedPreferences(SessionPreferencesName, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .putInt("version", SessionEnvelopeVersion)
                    .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .putString(
                        "ciphertext",
                        Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    )
                    .commit(),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    private fun sessionKey(): SecretKey = synchronized(SessionKeyAlias) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(SessionKeyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    SessionKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun DataOutputStream.writeSized(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun enqueueOldWorkerContracts(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val createWorker = Class.forName(ExpectedWork.getValue("gigagochi-create-sync"))
            .asSubclass(ListenableWorker::class.java)
        val syncWorker = Class.forName(ExpectedWork.getValue("gigagochi-mvp-sync"))
            .asSubclass(ListenableWorker::class.java)
        workManager.enqueueUniqueWork(
            "gigagochi-create-sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(createWorker)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10L, TimeUnit.SECONDS)
                .build(),
        ).result.get(10, TimeUnit.SECONDS)
        workManager.enqueueUniqueWork(
            "gigagochi-story-sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(syncWorker)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10L, TimeUnit.SECONDS)
                .build(),
        ).result.get(10, TimeUnit.SECONDS)
        workManager.enqueueUniquePeriodicWork(
            "gigagochi-mvp-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(syncWorker, 15L, TimeUnit.MINUTES)
                .setConstraints(network)
                .build(),
        ).result.get(10, TimeUnit.SECONDS)
    }

    private fun openDatabase(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )

    private fun SQLiteDatabase.exec(sql: String, vararg values: Any?) {
        execSQL(sql.trimIndent(), values)
    }

    private fun rowCount(database: SQLiteDatabase, table: String): Long =
        scalarLong(database, "SELECT COUNT(*) FROM `${table.replace("`", "``")}`")

    private fun scalarLong(
        database: SQLiteDatabase,
        sql: String,
        vararg arguments: String,
    ): Long = database.rawQuery(sql.trimIndent(), arguments).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun longValue(
        database: SQLiteDatabase,
        sql: String,
        vararg arguments: String,
    ): Long? = database.rawQuery(sql.trimIndent(), arguments).use { cursor ->
        assertTrue(cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getLong(0)
    }

    private fun stringValue(
        database: SQLiteDatabase,
        sql: String,
        vararg arguments: String,
    ): String? = database.rawQuery(sql.trimIndent(), arguments).use { cursor ->
        assertTrue(cursor.moveToFirst())
        if (cursor.isNull(0)) null else cursor.getString(0)
    }

    private fun canonicalSnapshot(database: SQLiteDatabase, tables: List<String>): String =
        buildString {
            tables.sorted().forEach { table ->
                val columns = tableColumns(database, table)
                append("TABLE\t").append(table).append('\n')
                append("COLUMNS\t").append(columns.joinToString("\t")).append('\n')
                val order = columns.joinToString(",") { "`${it.replace("`", "``")}`" }
                database.rawQuery(
                    "SELECT * FROM `${table.replace("`", "``")}` ORDER BY $order",
                    emptyArray(),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        append("ROW")
                        for (index in columns.indices) {
                            append('\t').append(canonicalCursorValue(cursor, index))
                        }
                        append('\n')
                    }
                }
            }
        }

    private fun tableColumns(database: SQLiteDatabase, table: String): List<String> =
        database.rawQuery(
            "PRAGMA table_info(`${table.replace("`", "``")}`)",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private fun canonicalCursorValue(cursor: Cursor, index: Int): String = when (
        cursor.getType(index)
    ) {
        Cursor.FIELD_TYPE_NULL -> "null"
        Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
        Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
        Cursor.FIELD_TYPE_STRING -> "string:${
            Base64.encodeToString(
                cursor.getString(index).toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
        }"
        Cursor.FIELD_TYPE_BLOB ->
            "blob:${Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)}"
        else -> error("Unknown cursor type")
    }

    private fun canonicalWorkSnapshot(context: Context): String {
        val workDatabase = listOf(
            File(context.noBackupFilesDir, "androidx.work.workdb"),
            context.getDatabasePath("androidx.work.workdb"),
        ).firstOrNull(File::isFile)
        assertTrue("WorkManager database is missing", workDatabase?.isFile == true)
        return SQLiteDatabase.openDatabase(
            requireNotNull(workDatabase).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery(
                """
                SELECT WorkName.name, WorkSpec.id, WorkSpec.state, WorkSpec.worker_class_name
                FROM WorkName
                INNER JOIN WorkSpec ON WorkName.work_spec_id = WorkSpec.id
                WHERE WorkName.name IN (?, ?, ?)
                ORDER BY WorkName.name, WorkSpec.id
                """.trimIndent(),
                ExpectedWork.keys.sorted().toTypedArray(),
            ).use { cursor ->
                buildString {
                    while (cursor.moveToNext()) {
                        append(cursor.getString(0))
                            .append('\t')
                            .append(cursor.getString(1))
                            .append('\t')
                            .append(cursor.getInt(2))
                            .append('\t')
                            .append(cursor.getString(3))
                            .append('\n')
                    }
                }
            }
        }
    }

    private fun canonicalWorkIdentity(snapshot: String): String = snapshot.lineSequence()
        .filter(String::isNotBlank)
        .map { line ->
            val columns = line.split('\t')
            assertTrue(columns.size == 4)
            "${columns[0]}\t${columns[1]}\t${columns[3]}"
        }
        .sorted()
        .joinToString("\n")

    private fun assertExpectedWorkers(snapshot: String) {
        val rows = snapshot.lineSequence()
            .filter(String::isNotBlank)
            .map { it.split('\t') }
            .toList()
        assertEquals(ExpectedWork.size, rows.size)
        rows.forEach { columns ->
            assertEquals(4, columns.size)
            assertEquals(ExpectedWork.getValue(columns[0]), columns[3])
            assertTrue(
                "Unexpected terminal WorkSpec state for ${columns[0]}: ${columns[2]}",
                columns[2].toInt() !in setOf(3, 5),
            )
        }
    }

    private fun waitForExpectedWork(context: Context) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = runCatching { canonicalWorkSnapshot(context) }.getOrNull().orEmpty()
            if (snapshot.lineSequence().count(String::isNotBlank) == ExpectedWork.size) return
            SystemClock.sleep(100L)
        }
        assertExpectedWorkers(canonicalWorkSnapshot(context))
    }

    private fun assertInstalledVersion(
        context: Context,
        expectedCode: Long,
        expectedName: String,
    ) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(expectedCode, info.longVersionCode)
        assertEquals(expectedName, info.versionName)
    }

    private fun assertOffline(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        assertFalse(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    private fun assertEncryptedSessionDoesNotContainPlaintext(context: Context) {
        val preferences = context.getSharedPreferences("gigagochi_secure_session", Context.MODE_PRIVATE)
        val serialized = preferences.all.values.joinToString("|")
        assertFalse(serialized.contains(FakeAccessToken))
        assertFalse(serialized.contains(OwnerId))
    }

    private fun writeEvidence(name: String, value: String) {
        evidenceFile(name).writeText(value, StandardCharsets.UTF_8)
    }

    private fun readEvidence(name: String): String {
        val file = evidenceFile(name)
        assertTrue("Missing prior upgrade evidence: $name", file.isFile)
        return file.readText(StandardCharsets.UTF_8)
    }

    private fun evidenceFile(name: String): File {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceDirectory = requireNotNull(
            targetContext.getExternalFilesDir("signed-upgrade-evidence"),
        ).apply { mkdirs() }
        return File(evidenceDirectory, name)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DatabaseName = "gigagochi-local.db"
        const val Schema8Asset =
            "com.gigagochi.app.core.database.GigagochiDatabase/8.json"
        const val Schema9Asset =
            "com.gigagochi.app.core.database.GigagochiDatabase/9.json"
        const val SessionPreferencesName = "gigagochi_secure_session"
        const val SessionKeyAlias = "gigagochi.session.aes.v2"
        const val SessionEnvelopeVersion = 2
        const val SessionPayloadMagic = 0x47475332
        const val OwnerId = "upgrade-owner"
        const val PetId = "upgrade-pet"
        const val PetName = "Апгрейдчик"
        const val RecoveredReply = "Я сохранил наш разговор."
        const val CompletedChatRequestKey = "upgrade-chat-completed"
        const val PendingChatRequestKey = "upgrade-chat-pending"
        const val BaseAssetSetId = "upgrade-base-asset"
        const val OutfitAssetSetId = "upgrade-outfit-asset"
        const val OutfitRequestKey = "upgrade-outfit-ready"
        const val TravelRequestKey = "upgrade-travel-ready"
        const val StoryId = "upgrade-story"
        const val FakeAccessToken = "qa-only-fake-access-token-not-a-secret"
        val SessionAssociatedData =
            "gigagochi-session-envelope-v2".toByteArray(StandardCharsets.UTF_8)

        val V8Tables = listOf(
            "pet_snapshots",
            "pending_create_generations",
            "pending_outfits",
            "pending_travel_videos",
            "applied_story_receipts",
            "pet_mood_images",
            "travel_video_assets",
            "outfit_media_outcomes",
            "outfit_mood_images",
            "applied_outfit_receipts",
            "scheduled_stories",
            "first_sessions",
            "first_session_action_receipts",
            "chat_messages",
            "pending_chats",
            "compliment_ledger",
            "applied_chat_responses",
            "user_memories",
            "memory_learnings",
            "pet_memory_state",
            "proactive_notifications",
            "notification_outbox",
            "event_history_views",
        )
        val V9Tables = V8Tables + "dashboard_command_receipts"
        val ExpectedWork = mapOf(
            "gigagochi-create-sync" to
                "com.gigagochi.app.core.background.CreateSyncWorker",
            "gigagochi-mvp-sync" to
                "com.gigagochi.app.core.background.GigagochiSyncWorker",
            "gigagochi-story-sync" to
                "com.gigagochi.app.core.background.GigagochiSyncWorker",
        )
    }
}
