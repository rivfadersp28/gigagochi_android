package com.gigagochi.app.core.background

import com.gigagochi.app.core.database.LocalCompletionNotification
import com.gigagochi.app.core.database.LocalNotificationKind
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MvpSyncPassRunnerTest {
    @Test
    fun noAuthOrPetIsSuccessfulNoOp() = runBlocking {
        var syncCalls = 0
        assertEquals(
            MvpSyncPassResult.Success,
            runner(session = null, onSync = { syncCalls += 1; null }).runOnce(),
        )
        assertEquals(
            MvpSyncPassResult.Success,
            runner(pet = null, onSync = { syncCalls += 1; null }).runOnce(),
        )
        assertEquals(0, syncCalls)
    }

    @Test
    fun storyNotReadyEmitsNothingAndReadyStoryIsMarkedOnce() = runBlocking {
        val durable = mutableListOf<LocalCompletionNotification>()
        val emitted = mutableListOf<LocalCompletionNotification>()
        val runner = runner(
            notifications = { durable.toList() },
            emitted = emitted,
            onMark = { durable.remove(it) },
        )
        assertEquals(MvpSyncPassResult.Success, runner.runOnce())
        durable += story()
        assertEquals(MvpSyncPassResult.Success, runner.runOnce())
        assertEquals(MvpSyncPassResult.Success, runner.runOnce())
        assertEquals(listOf(story()), emitted)
    }

    @Test
    fun outfitAndTravelCompletionsEmitWithStableReplacementIds() = runBlocking {
        val outfit = notification(LocalNotificationKind.OutfitReady, "outfit-key")
        val travel = notification(LocalNotificationKind.TravelReady, "travel-key")
        val durable = mutableListOf<LocalCompletionNotification>()
        val emitted = mutableListOf<LocalCompletionNotification>()
        runner(
            onSync = { durable += listOf(outfit, travel); null },
            notifications = { durable.toList() },
            emitted = emitted,
        ).runOnce()

        assertEquals(listOf(outfit, travel), emitted)
        assertEquals(stableNotificationId(outfit), stableNotificationId(outfit.copy()))
        assertNotEquals(stableNotificationId(outfit), stableNotificationId(travel))
    }

    @Test
    fun petReadyNotificationMatchesTelegramCopyAndHasStableReplacementId() {
        val notification = petReadyNotification("create-key")

        assertEquals(LocalNotificationKind.PetReady, notification.kind)
        assertEquals("Ваш друг родился", notification.title)
        assertEquals("Скорее познакомьтесь с ним", notification.body)
        assertEquals(stableNotificationId(notification), stableNotificationId(notification.copy()))
    }

    @Test
    fun manualGenerationFailureNotificationsUseOperationCopyAndStableReplacementIds() {
        val create = manualGenerationFailedNotification(ManualGenerationKind.Create, "same-key")
        val outfit = manualGenerationFailedNotification(ManualGenerationKind.Outfit, "same-key")
        val notification = manualGenerationFailedNotification(ManualGenerationKind.Travel, "same-key")

        assertEquals(LocalNotificationKind.GenerationFailed, notification.kind)
        assertEquals("Не получилось создать персонажа, попробуй еще раз", create.body)
        assertEquals("Не получилось переодеть питомца, попробуй еще раз", outfit.body)
        assertEquals("Путешествие не получилось", notification.title)
        assertEquals(
            "Не получилось отправиться в путешествие, попробуй еще раз",
            notification.body,
        )
        assertEquals(stableNotificationId(notification), stableNotificationId(notification.copy()))
        assertNotEquals(stableNotificationId(create), stableNotificationId(outfit))
        assertNotEquals(stableNotificationId(outfit), stableNotificationId(notification))
    }

    @Test
    fun deniedPermissionLeavesRowsUnmarkedAndAppUsable() = runBlocking {
        var marked = 0
        val emitted = mutableListOf<LocalCompletionNotification>()
        val result = runner(
            permission = false,
            notifications = { listOf(story()) },
            emitted = emitted,
            onMark = { marked += 1 },
        ).runOnce()

        assertEquals(MvpSyncPassResult.Success, result)
        assertEquals(emptyList<LocalCompletionNotification>(), emitted)
        assertEquals(0, marked)
    }

    @Test
    fun foregroundDispatcherEmitsAndMarksReadyContent() = runBlocking {
        val row = notification(LocalNotificationKind.TravelReady, "travel-ready")
        val emitted = mutableListOf<LocalCompletionNotification>()
        val marked = mutableListOf<LocalCompletionNotification>()
        CompletionNotificationDispatcher(
            notificationsAllowed = { true },
            loadNotifications = { _, _ -> listOf(row) },
            emitter = LocalNotificationEmitter { emitted += it; true },
            markNotified = { _, notification -> marked += notification },
        ).dispatch("owner-a", "pet-a")

        assertEquals(listOf(row), emitted)
        assertEquals(listOf(row), marked)
    }

    @Test
    fun crashAfterPostRetriesWithSameStableNotificationId() = runBlocking {
        val row = story()
        val emittedIds = mutableListOf<Int>()
        var firstMark = true
        val runner = MvpSyncPassRunner(
            sessionProvider = { session() },
            petProvider = { pet() },
            featureSync = { _, _ -> null },
            notificationsAllowed = { true },
            loadNotifications = { _, _ -> listOf(row) },
            emitter = LocalNotificationEmitter {
                emittedIds += stableNotificationId(it)
                true
            },
            markNotified = { _, _ ->
                if (firstMark) {
                    firstMark = false
                    error("process died before durable mark")
                }
            },
        )

        assertEquals(MvpSyncPassResult.Retry, runner.runOnce())
        assertEquals(MvpSyncPassResult.Success, runner.runOnce())
        assertEquals(2, emittedIds.size)
        assertEquals(emittedIds[0], emittedIds[1])
    }

    @Test
    fun transientFailureRetriesButInvalidSessionDoesNotNotify() = runBlocking {
        assertEquals(
            MvpSyncPassResult.Retry,
            runner(onSync = { FeatureFailure(FeatureFailureKind.Network) }).runOnce(),
        )
        val emitted = mutableListOf<LocalCompletionNotification>()
        assertEquals(
            MvpSyncPassResult.Success,
            runner(
                onSync = { FeatureFailure(FeatureFailureKind.SessionInvalid) },
                notifications = { listOf(story()) },
                emitted = emitted,
            ).runOnce(),
        )
        assertEquals(emptyList<LocalCompletionNotification>(), emitted)
    }

    private fun runner(
        session: Session? = session(),
        pet: PetDashboardState? = pet(),
        permission: Boolean = true,
        onSync: suspend () -> FeatureFailure? = { null },
        notifications: suspend () -> List<LocalCompletionNotification> = { emptyList() },
        emitted: MutableList<LocalCompletionNotification> = mutableListOf(),
        onMark: suspend (LocalCompletionNotification) -> Unit = {},
    ) = MvpSyncPassRunner(
        sessionProvider = { session },
        petProvider = { pet },
        featureSync = { _, _ -> onSync() },
        notificationsAllowed = { permission },
        loadNotifications = { _, _ -> notifications() },
        emitter = LocalNotificationEmitter {
            emitted += it
            true
        },
        markNotified = { _, notification -> onMark(notification) },
    )

    private fun notification(kind: LocalNotificationKind, key: String) =
        LocalCompletionNotification(kind, key, "Готово", "Открой приложение")

    private fun story() = LocalCompletionNotification(
        LocalNotificationKind.ScheduledStory,
        "android-story-1234567890abcdef1234567890abcdef",
        "История",
        "Новое приключение",
        "android-story-1234567890abcdef1234567890abcdef",
    )

    private fun session() = Session(
        "owner-a",
        SensitiveToken.of("access"),
        null,
        Long.MAX_VALUE,
    )

    private fun pet() = PetDashboardState(
        "pet-a", "asset-a", "dragon", "Гига", "baby", "Малыш", "idle",
        0, 50, 50, 50, "",
    )
}
