package com.gigagochi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.feature.dashboard.DashboardDurableOperations
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.RealDashboardChatAdapter
import com.gigagochi.app.feature.dashboard.DashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.DurableOutfitResult
import com.gigagochi.app.feature.dashboard.PendingOutfitGeneration
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.UnavailableDashboardTravelAdapter
import com.gigagochi.app.core.webview.WebChatExecutionResult
import com.gigagochi.app.core.webview.completeDashboardFirstSessionChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetLocalRepositoryTest {
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cleanV1DatabaseCreatesAndReopensWithMvpSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "mvp-v1-reopen-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, GigagochiDatabase::class.java, name).build()
        PetLocalRepository(first).replacePetSnapshot(snapshot(experience = 321, petTapProgress = 4))
        first.close()

        val reopened = Room.databaseBuilder(context, GigagochiDatabase::class.java, name).build()
        assertEquals(321, PetLocalRepository(reopened).getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(4, PetLocalRepository(reopened).getPetSnapshot(OwnerId, PetId)?.pet?.petTapProgress)
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test
    fun ownerScopedReadsAndDeletesNeverCrossAccounts() = runBlocking {
        repository.replacePetSnapshot(snapshot(ownerId = "owner-a", experience = 100))
        repository.replacePetSnapshot(snapshot(ownerId = "owner-b", experience = 900))

        assertEquals(100, repository.getPetSnapshot("owner-a", PetId)?.pet?.experience)
        assertEquals(900, repository.getPetSnapshot("owner-b", PetId)?.pet?.experience)

        repository.deleteOwnerData("owner-a")

        assertNull(repository.getPetSnapshot("owner-a", PetId))
        assertNotNull(repository.getPetSnapshot("owner-b", PetId))
    }

    @Test
    fun notificationOutboxDurablyDeduplicatesEveryNotificationKind() = runBlocking {
        val notifications = LocalNotificationKind.entries.mapIndexed { index, kind ->
            LocalCompletionNotification(
                kind = kind,
                stableKey = "notification-$index",
                title = "Заголовок $index",
                body = "Сообщение $index",
                storyId = "story-$index".takeIf { kind == LocalNotificationKind.ScheduledStory },
                travelRequestKey = "travel-$index".takeIf {
                    kind == LocalNotificationKind.TravelReady
                },
            )
        }
        notifications.forEachIndexed { index, notification ->
            assertTrue(
                repository.enqueueNotification(
                    OwnerId,
                    PetId,
                    notification,
                    index.toLong(),
                ),
            )
            assertTrue(
                repository.enqueueNotification(
                    OwnerId,
                    PetId,
                    notification,
                    (index + 100).toLong(),
                ),
            )
        }

        assertEquals(notifications, repository.getUnnotifiedNotifications(OwnerId, PetId))
        notifications.forEachIndexed { index, notification ->
            assertTrue(repository.markNotificationSent(OwnerId, notification, 1_000L + index))
        }
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
    }

    @Test
    fun decayPersistsIndependentStatClocksAndResetsOnlyChangedStat() = runBlocking {
        val startedAt = 1_000_000L
        var now = startedAt + PetStatFullDecayMillis / 2
        repository = PetLocalRepository(database) { now }
        repository.replacePetSnapshot(snapshot().copy(updatedAtEpochMillis = startedAt))

        val halfway = requireNotNull(repository.decayPetSnapshot(OwnerId, PetId))
        assertEquals(50, halfway.pet.hunger)
        assertEquals(59, halfway.pet.happiness)
        assertEquals(67, halfway.pet.energy)

        repository.replacePetSnapshot(
            halfway.copy(
                pet = halfway.pet.copy(hunger = 75),
                updatedAtEpochMillis = now,
            ),
        )
        now = startedAt + PetStatFullDecayMillis
        val finished = requireNotNull(repository.decayPetSnapshot(OwnerId, PetId))

        assertEquals(25, finished.pet.hunger)
        assertEquals(9, finished.pet.happiness)
        assertEquals(17, finished.pet.energy)
        assertEquals("hungry", finished.pet.mood)
    }

    @Test
    fun atomicMutationUsesLatestDecayedPetAndRejectsStalePreconditions() = runBlocking {
        val startedAt = 1_000_000L
        var now = startedAt + PetStatFullDecayMillis / 2
        repository = PetLocalRepository(database) { now }
        repository.replacePetSnapshot(
            snapshot(petTapProgress = 2).copy(updatedAtEpochMillis = startedAt),
        )

        val applied = repository.mutatePetSnapshot(OwnerId, PetId, AssetSetId) { current ->
            assertEquals(50, current.hunger)
            assertEquals(2, current.petTapProgress)
            current.copy(petTapProgress = 3)
        }
        assertTrue(applied is PetSnapshotMutationResult.Applied)
        assertEquals(3, repository.getPetSnapshot(OwnerId, PetId)?.pet?.petTapProgress)
        assertEquals(50, repository.getPetSnapshot(OwnerId, PetId)?.pet?.hunger)

        now += 1
        val conflict = repository.mutatePetSnapshot(OwnerId, PetId, AssetSetId) { current ->
            current.takeIf { it.petTapProgress == 2 }?.copy(petTapProgress = 4)
        }
        assertEquals(PetSnapshotMutationResult.Conflict, conflict)
        assertEquals(3, repository.getPetSnapshot(OwnerId, PetId)?.pet?.petTapProgress)

        val assetConflict = repository.mutatePetSnapshot(OwnerId, PetId, "stale-assets") {
            it.copy(petTapProgress = 4)
        }
        assertEquals(PetSnapshotMutationResult.Conflict, assetConflict)
        assertEquals(3, repository.getPetSnapshot(OwnerId, PetId)?.pet?.petTapProgress)
    }

    @Test
    fun dashboardOutfitReservationChargesExactlyOnceAndFencesPayload() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))

        val accepted = repository.reserveDashboardOutfit(
            OwnerId,
            PetId,
            AssetSetId,
            "outfit-command",
            "Красный шарф",
        )
        assertTrue(accepted is DashboardOutfitReservationResult.Accepted)
        accepted as DashboardOutfitReservationResult.Accepted
        assertTrue(accepted.newlyAccepted)
        assertEquals(300, accepted.pet.experience)
        assertEquals(200, accepted.request.experienceCost)

        val replay = repository.reserveDashboardOutfit(
            OwnerId,
            PetId,
            AssetSetId,
            "outfit-command",
            "Красный шарф",
        )
        assertTrue(replay is DashboardOutfitReservationResult.Accepted)
        replay as DashboardOutfitReservationResult.Accepted
        assertEquals(false, replay.newlyAccepted)
        assertEquals(300, replay.pet.experience)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)

        assertEquals(
            DashboardOutfitReservationResult.Conflict,
            repository.reserveDashboardOutfit(
                OwnerId,
                PetId,
                AssetSetId,
                "outfit-command",
                "Другой шарф",
            ),
        )
        assertTrue(
            repository.reserveDashboardOutfit(
                OwnerId,
                PetId,
                AssetSetId,
                "outfit-second",
                "Плащ",
            ) is DashboardOutfitReservationResult.Busy,
        )
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun dashboardOutfitReservationRejectsInsufficientExperienceWithoutDurableSideEffect() =
        runBlocking {
            repository.replacePetSnapshot(snapshot(experience = 199))

            val result = repository.reserveDashboardOutfit(
                OwnerId,
                PetId,
                AssetSetId,
                "outfit-insufficient",
                "Шляпа",
            )

            assertTrue(result is DashboardOutfitReservationResult.InsufficientExperience)
            assertEquals(199, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
            assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())
            assertTrue(repository.loadOwnerRecovery(OwnerId).pendingOutfits.isEmpty())
        }

    @Test
    fun dashboardTravelReservationIsIdempotentAndFencesPayload() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 321))

        val accepted = repository.reserveDashboardTravel(
            OwnerId,
            PetId,
            AssetSetId,
            "travel-command",
            "Ночной рынок духов",
        )
        assertTrue(accepted is DashboardTravelReservationResult.Accepted)
        accepted as DashboardTravelReservationResult.Accepted
        assertTrue(accepted.newlyAccepted)

        val replay = repository.reserveDashboardTravel(
            OwnerId,
            PetId,
            AssetSetId,
            "travel-command",
            "Ночной рынок духов",
        )
        assertTrue(replay is DashboardTravelReservationResult.Accepted)
        replay as DashboardTravelReservationResult.Accepted
        assertEquals(false, replay.newlyAccepted)
        assertEquals(1, repository.getPendingTravels(OwnerId).size)
        assertEquals(321, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(
            DashboardTravelReservationResult.Conflict,
            repository.reserveDashboardTravel(
                OwnerId,
                PetId,
                AssetSetId,
                "travel-command",
                "Луна",
            ),
        )
    }

    @Test
    fun dashboardManualGenerationReservationsRespectFirstSessionGates() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat.storageValue,
                null,
                null,
                1,
            ),
        )

        assertEquals(
            DashboardOutfitReservationResult.WrongStage,
            repository.reserveDashboardOutfit(
                OwnerId,
                PetId,
                AssetSetId,
                "outfit-too-early",
                "Шарф",
            ),
        )
        assertEquals(
            DashboardTravelReservationResult.WrongStage,
            repository.reserveDashboardTravel(
                OwnerId,
                PetId,
                AssetSetId,
                "travel-too-early",
                "Луна",
            ),
        )

        database.gigagochiDao().upsertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingCompletionMessage.storageValue,
                null,
                null,
                2,
            ),
        )
        assertTrue(
            repository.reserveDashboardOutfit(
                OwnerId,
                PetId,
                AssetSetId,
                "outfit-onboarding",
                "Шарф",
            ) is DashboardOutfitReservationResult.Accepted,
        )
        assertEquals(
            DashboardTravelReservationResult.WrongStage,
            repository.reserveDashboardTravel(
                OwnerId,
                PetId,
                AssetSetId,
                "travel-before-complete",
                "Луна",
            ),
        )
        database.gigagochiDao().upsertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.Completed.storageValue,
                null,
                null,
                3,
            ),
        )
        assertTrue(
            repository.reserveDashboardTravel(
                OwnerId,
                PetId,
                AssetSetId,
                "travel-after-complete",
                "Луна",
            ) is DashboardTravelReservationResult.Accepted,
        )
    }

    @Test
    fun dashboardChatReservationDurablyFencesMessageAndSurvivesPresentationAck() = runBlocking {
        repository.replacePetSnapshot(snapshot())

        val accepted = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            "chat-reserved",
            "Привет",
            FirstSessionStage.AwaitingChat,
        ) as DashboardChatReservationResult.Pending
        assertTrue(accepted.newlyAccepted)
        assertEquals(FirstSessionStage.AwaitingChat, accepted.originFirstSessionStage)
        val replay = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            "chat-reserved",
            "Привет",
            FirstSessionStage.AwaitingChatFollowup,
        ) as DashboardChatReservationResult.Pending
        assertEquals(false, replay.newlyAccepted)
        assertEquals(FirstSessionStage.AwaitingChat, replay.originFirstSessionStage)
        assertEquals(
            DashboardChatReservationResult.Conflict,
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                "chat-reserved",
                "Другой payload",
            ),
        )

        repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-reserved",
            listOf(
                LocalChatMessage("chat-user", "user", "Привет", 20),
                LocalChatMessage("chat-pet", "pet", "Привет!", 21),
            ),
            happinessDelta = 0,
            complimentKey = null,
            appliedAtEpochMillis = 22,
        )
        assertTrue(repository.deletePendingChat(OwnerId, "chat-reserved"))
        assertTrue(
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                "chat-reserved",
                "Привет",
            ) is DashboardChatReservationResult.Finished,
        )
        assertEquals(
            DashboardChatReservationResult.Conflict,
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                "chat-reserved",
                "Другой payload",
            ),
        )
        database.gigagochiDao().insertFirstSessionActionReceipt(
            FirstSessionActionReceiptEntity(
                OwnerId,
                PetId,
                "onboarding-key-already-used",
                "food:berry-bowl",
                23,
            ),
        )
        assertEquals(
            DashboardChatReservationResult.Conflict,
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                "onboarding-key-already-used",
                "Новый чат",
                FirstSessionStage.AwaitingChat,
            ),
        )
    }

    @Test
    fun mismatchedChatReceiptCannotImposeOnboardingRecoveryProjection() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = LocalPendingChat(
            ownerId = OwnerId,
            petId = PetId,
            requestKey = "chat-mismatched-receipt",
            message = "Настоящее сообщение",
            createdAtEpochMillis = 20,
            responseText = "Сохранённый ответ",
            completedAtEpochMillis = 21,
        )
        assertTrue(repository.savePendingChat(pending))
        assertTrue(
            database.gigagochiDao().insertDashboardCommandReceipt(
                DashboardCommandReceiptEntity(
                    ownerId = OwnerId,
                    petId = PetId,
                    requestKey = pending.requestKey,
                    commandType = "chat-send",
                    payloadFingerprint = "fingerprint-for-a-different-message",
                    originFirstSessionStage = FirstSessionStage.AwaitingChat.storageValue,
                    food = null,
                    audioIndex = null,
                    reply = null,
                    explicitPortionsJson = null,
                    autoAdvanceDelayMillis = null,
                    createdAtEpochMillis = 20,
                ),
            ) != -1L,
        )

        assertNull(repository.getPendingChat(OwnerId, pending.requestKey)?.originFirstSessionStage)
        assertNull(
            repository.loadOwnerRecovery(OwnerId).pendingChats.single().originFirstSessionStage,
        )
        assertEquals(
            DashboardChatReservationResult.Conflict,
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                pending.requestKey,
                pending.message,
                FirstSessionStage.AwaitingChat,
            ),
        )
    }

    @Test
    fun dashboardChatQueueIsOrderedDurableAndAtomicallyReplacesOnlyQueuedRow() = runBlocking {
        repository = PetLocalRepository(database) { 100L }
        repository.replacePetSnapshot(snapshot())
        val activeKey = "ffffffff-ffff-4fff-8fff-fffffffffff1"
        val replacedKey = "00000000-0000-4000-8000-000000000002"
        val latestKey = "11111111-1111-4111-8111-111111111113"

        val active = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            activeKey,
            "Первое",
        ) as DashboardChatReservationResult.Pending
        val queued = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            replacedKey,
            "Второе",
            queueAnchorRequestKey = activeKey,
        ) as DashboardChatReservationResult.Pending

        // Both calls observe the same test clock. The queue still preserves acceptance order and
        // never falls back to lexical UUID order (which would place replacedKey first).
        assertTrue(queued.request.createdAtEpochMillis > active.request.createdAtEpochMillis)
        var recovered = repository.loadOwnerRecovery(OwnerId).pendingChats
        assertEquals(listOf(activeKey, replacedKey), recovered.map(LocalPendingChat::requestKey))

        val latest = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            latestKey,
            "Третье",
            queueAnchorRequestKey = activeKey,
            replacingQueuedRequestKey = replacedKey,
        ) as DashboardChatReservationResult.Pending

        assertTrue(latest.request.createdAtEpochMillis > active.request.createdAtEpochMillis)
        recovered = repository.loadOwnerRecovery(OwnerId).pendingChats
        assertEquals(listOf(activeKey, latestKey), recovered.map(LocalPendingChat::requestKey))
        assertEquals(2, recovered.size)
        assertTrue(repository.getPendingChat(OwnerId, replacedKey) == null)
        assertTrue(
            repository.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                replacedKey,
                "Второе",
            ) is DashboardChatReservationResult.Finished,
        )

        // Presentation ack is exact-key deletion: acknowledging active cannot delete the newer
        // durable queue row.
        assertTrue(repository.deletePendingChat(OwnerId, activeKey))
        assertEquals(
            listOf(latestKey),
            repository.loadOwnerRecovery(OwnerId).pendingChats.map(LocalPendingChat::requestKey),
        )
    }

    @Test
    fun dashboardFirstSessionChatRecoversBothCrashWindowsOfflineFromStoredOrigin() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat.storageValue,
                null,
                null,
                10,
            ),
        )
        var networkCalls = 0
        val offlineApi = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto):
                com.gigagochi.app.core.network.FeatureApiResult<com.gigagochi.app.core.network.ChatResponseDto> {
                networkCalls += 1
                error("completed pending must not hit the network")
            }
        }

        val firstRequest = PendingChatRequest("chat-first-crash", "Меня зовут Серёжа")
        val firstReservation = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            firstRequest.requestKey,
            firstRequest.message,
            FirstSessionStage.AwaitingChat,
        ) as DashboardChatReservationResult.Pending
        repository.applyChatResponse(
            OwnerId,
            PetId,
            firstRequest.requestKey,
            listOf(
                LocalChatMessage("first-user", "user", firstRequest.message, 20),
                LocalChatMessage("first-pet", "pet", "Очень приятно!", 21),
            ),
            happinessDelta = 0,
            complimentKey = null,
            appliedAtEpochMillis = 22,
        )
        val firstAdapterResult = RealDashboardChatAdapter(
            offlineApi,
            OwnerId,
            repository,
            this,
        ).reply(firstRequest, firstReservation.pet)
        val afterResponseCrash = completeDashboardFirstSessionChat(
            ownerId = OwnerId,
            repository = repository,
            request = firstRequest,
            adapterResult = firstAdapterResult,
            originFirstSessionStage = firstReservation.originFirstSessionStage,
            nowEpochMillis = 23,
        ) as WebChatExecutionResult.Success
        assertEquals(FirstSessionStage.AwaitingChatFollowup, afterResponseCrash.firstSession?.stage)
        assertEquals(2, afterResponseCrash.result.explicitPortions?.size)
        assertEquals(FirstSessionAfterName, afterResponseCrash.result.explicitPortions?.last())
        assertEquals("Очень приятно!", afterResponseCrash.pendingChat?.responseText)
        assertEquals(
            "chat:awaiting-chat",
            database.gigagochiDao().getFirstSessionActionReceipt(
                OwnerId,
                PetId,
                firstRequest.requestKey,
            )?.actionKind,
        )

        val secondRequest = PendingChatRequest("chat-stage-crash", "Я люблю рисовать")
        val secondReservation = repository.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            secondRequest.requestKey,
            secondRequest.message,
            FirstSessionStage.AwaitingChatFollowup,
        ) as DashboardChatReservationResult.Pending
        repository.applyChatResponse(
            OwnerId,
            PetId,
            secondRequest.requestKey,
            listOf(
                LocalChatMessage("second-user", "user", secondRequest.message, 30),
                LocalChatMessage("second-pet", "pet", "Это здорово!", 31),
            ),
            happinessDelta = 0,
            complimentKey = null,
            appliedAtEpochMillis = 32,
        )
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChatFollowup,
                FirstSessionStage.AwaitingFirstFood,
                secondRequest.requestKey,
                nowEpochMillis = 33,
            ) is FirstSessionMutationResult.Applied,
        )
        assertEquals(
            "stage",
            database.gigagochiDao().getFirstSessionActionReceipt(
                OwnerId,
                PetId,
                secondRequest.requestKey,
            )?.actionKind,
        )

        val restarted = PetLocalRepository(database)
        val replayReservation = restarted.reserveDashboardChat(
            OwnerId,
            PetId,
            AssetSetId,
            secondRequest.requestKey,
            secondRequest.message,
            originFirstSessionStage = FirstSessionStage.AwaitingFirstFood,
        ) as DashboardChatReservationResult.Pending
        assertEquals(
            FirstSessionStage.AwaitingChatFollowup,
            replayReservation.originFirstSessionStage,
        )
        val replayAdapterResult = RealDashboardChatAdapter(
            offlineApi,
            OwnerId,
            restarted,
            this,
        ).reply(secondRequest, replayReservation.pet)
        val afterStageCrash = completeDashboardFirstSessionChat(
            ownerId = OwnerId,
            repository = restarted,
            request = secondRequest,
            adapterResult = replayAdapterResult,
            originFirstSessionStage = replayReservation.originFirstSessionStage,
            nowEpochMillis = 34,
        ) as WebChatExecutionResult.Success
        assertEquals(FirstSessionStage.AwaitingFirstFood, afterStageCrash.firstSession?.stage)
        assertEquals(2, afterStageCrash.result.explicitPortions?.size)
        assertEquals(FirstSessionAfterChat, afterStageCrash.result.explicitPortions?.last())
        assertEquals("Это здорово!", afterStageCrash.pendingChat?.responseText)
        assertEquals(0, networkCalls)
        assertEquals(
            DashboardChatReservationResult.Conflict,
            restarted.reserveDashboardChat(
                OwnerId,
                PetId,
                AssetSetId,
                secondRequest.requestKey,
                "Другой payload",
                FirstSessionStage.AwaitingFirstFood,
            ),
        )
        assertEquals(
            FirstSessionMutationResult.WrongStage,
            restarted.advanceDashboardChatFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChatFollowup,
                secondRequest.requestKey,
                "Другой payload",
                35,
            ),
        )
        assertEquals(
            FirstSessionMutationResult.WrongStage,
            restarted.applyFirstSessionFood(
                OwnerId,
                PetId,
                "berry-bowl",
                secondRequest.requestKey,
                36,
            ),
        )
    }

    @Test
    fun dashboardFeedRapidActionsAreCumulativeAndReplayCannotApplyTwice() = runBlocking {
        repository = PetLocalRepository(database) { 10L }
        repository.replacePetSnapshot(snapshot(hunger = 40, energy = 30))

        val berry = applyFeed("feed-berry", "berry-bowl", 0)
            as DashboardFeedApplicationResult.Applied
        val leaf = applyFeed("feed-leaf", "leaf-crunch", 1)
            as DashboardFeedApplicationResult.Applied
        assertTrue(berry.newlyApplied)
        assertTrue(leaf.newlyApplied)
        assertEquals(65, leaf.pet.hunger)
        assertEquals(55, leaf.pet.energy)

        val replay = applyFeed("feed-berry", "berry-bowl", 2)
            as DashboardFeedApplicationResult.Applied
        assertEquals(false, replay.newlyApplied)
        assertEquals(0, replay.receipt.audioIndex)
        assertEquals(65, replay.pet.hunger)
        assertEquals(55, replay.pet.energy)
        assertEquals(
            DashboardFeedApplicationResult.Conflict,
            applyFeed("feed-berry", "leaf-crunch", 2),
        )
        assertEquals(65, repository.getPetSnapshot(OwnerId, PetId)?.pet?.hunger)
        assertEquals(55, repository.getPetSnapshot(OwnerId, PetId)?.pet?.energy)
    }

    @Test
    fun dashboardFeedPreservesExactFourRowFirstSessionFoodMatrix() = runBlocking {
        repository = PetLocalRepository(database) { 10L }
        repository.replacePetSnapshot(snapshot(hunger = 40, energy = 30))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingFirstFood.storageValue,
                null,
                null,
                1,
            ),
        )

        val wrongFirst = applyFeed("food-1", "leaf-crunch", 0)
            as DashboardFeedApplicationResult.Applied
        assertEquals(FirstSessionStage.AwaitingFirstFood, wrongFirst.firstSession?.stage)
        assertEquals("Обычный лист", wrongFirst.receipt.reply)
        assertEquals(55, wrongFirst.pet.energy)

        val firstFood = applyFeed("food-2", "berry-bowl", 1)
            as DashboardFeedApplicationResult.Applied
        assertEquals(FirstSessionStage.AwaitingRemedy, firstFood.firstSession?.stage)
        assertEquals("После первой еды", firstFood.receipt.reply)
        assertEquals(65, firstFood.pet.hunger)

        val wrongRemedy = applyFeed("food-3", "berry-bowl", 2)
            as DashboardFeedApplicationResult.Applied
        assertEquals(FirstSessionStage.AwaitingRemedy, wrongRemedy.firstSession?.stage)
        assertEquals("Обычная ягода", wrongRemedy.receipt.reply)
        assertEquals(90, wrongRemedy.pet.hunger)

        val remedy = applyFeed("food-4", "leaf-crunch", 0)
            as DashboardFeedApplicationResult.Applied
        assertEquals(FirstSessionStage.AwaitingTravel, remedy.firstSession?.stage)
        assertEquals("После лекарства", remedy.receipt.reply)
        assertEquals(listOf("Часть 1", "Часть 2"), remedy.receipt.explicitPortions)
        assertEquals(80, remedy.pet.energy)

        val replay = applyFeed("food-1", "leaf-crunch", 2)
            as DashboardFeedApplicationResult.Applied
        assertEquals(false, replay.newlyApplied)
        assertEquals(90, replay.pet.hunger)
        assertEquals(80, replay.pet.energy)
    }

    @Test
    fun chatComplimentRewardAndLedgerAreDurableAndIdempotent() = runBlocking {
        repository.replacePetSnapshot(snapshot(happiness = 10))
        val messages = listOf(
            LocalChatMessage("user-1", "user", "Ты очень смелый", 20),
            LocalChatMessage("pet-1", "pet", "Спасибо!", 21),
        )

        val ordinary = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-ordinary",
            messages,
            happinessDelta = 30,
            complimentKey = "смелый и добрый",
            appliedAtEpochMillis = 22,
        )
        assertEquals(40, ordinary.happiness)
        assertEquals(listOf("смелый и добрый"), repository.complimentHistory(OwnerId, PetId))

        val replay = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-ordinary",
            messages,
            happinessDelta = 100,
            complimentKey = "другой ключ",
            appliedAtEpochMillis = 23,
        )
        assertEquals(40, replay.happiness)
        assertEquals(listOf("смелый и добрый"), repository.complimentHistory(OwnerId, PetId))

        val exceptional = repository.applyChatResponse(
            OwnerId,
            PetId,
            "chat-exceptional",
            emptyList(),
            happinessDelta = 100,
            complimentKey = "самый внимательный хранитель",
            appliedAtEpochMillis = 24,
        )
        assertEquals(100, exceptional.happiness)
        assertEquals(
            listOf("смелый и добрый", "самый внимательный хранитель"),
            repository.complimentHistory(OwnerId, PetId),
        )
    }

    @Test
    fun pendingChatSurvivesResponseUntilUiAcknowledgesPresentation() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = LocalPendingChat(
            ownerId = OwnerId,
            petId = PetId,
            requestKey = "chat-durable",
            message = "Ты меня слышишь?",
            createdAtEpochMillis = 20,
        )

        assertTrue(repository.savePendingChat(pending))
        assertTrue(repository.savePendingChat(pending.copy(createdAtEpochMillis = 99)))
        val startup = AccountPetLifecycle(repository).startup(OwnerId)
            as AccountStartupDestination.Dashboard
        assertEquals(pending, startup.pendingChat)

        repository.applyChatResponse(
            OwnerId,
            PetId,
            pending.requestKey,
            listOf(
                LocalChatMessage("user-durable", "user", pending.message, 20),
                LocalChatMessage("pet-durable", "pet", "Слышу.", 21),
            ),
            happinessDelta = 0,
            complimentKey = null,
            appliedAtEpochMillis = 22,
        )

        val completedStartup = AccountPetLifecycle(repository).startup(OwnerId)
            as AccountStartupDestination.Dashboard
        assertEquals(pending.requestKey, completedStartup.pendingChat?.requestKey)
        assertEquals(
            listOf("Ты меня слышишь?", "Слышу."),
            repository.recentChatMessages(OwnerId, PetId).map(LocalChatMessage::text),
        )
        assertTrue(repository.deletePendingChat(OwnerId, pending.requestKey))
        assertNull(
            (AccountPetLifecycle(repository).startup(OwnerId) as AccountStartupDestination.Dashboard)
                .pendingChat,
        )
    }

    @Test
    fun cancelledChatCallIsRecoveredWithTheSameRequestKey() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val request = PendingChatRequest("chat-cancelled", "Ты меня слышишь?")
        val enteredApi = CompletableDeferred<Unit>()
        val cancelledApi = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto):
                com.gigagochi.app.core.network.FeatureApiResult<com.gigagochi.app.core.network.ChatResponseDto> {
                enteredApi.complete(Unit)
                CompletableDeferred<Unit>().await()
                error("unreachable")
            }
        }
        val firstAttempt = launch {
            RealDashboardChatAdapter(cancelledApi, OwnerId, repository, this)
                .reply(request, requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet)
        }
        enteredApi.await()
        firstAttempt.cancelAndJoin()

        val restored = AccountPetLifecycle(repository).startup(OwnerId)
            as AccountStartupDestination.Dashboard
        assertEquals(request.requestKey, restored.pendingChat?.requestKey)
        assertEquals(request.message, restored.pendingChat?.message)

        var recoveredRequestKey: String? = null
        val successfulApi = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto):
                com.gigagochi.app.core.network.FeatureApiResult<com.gigagochi.app.core.network.ChatResponseDto> {
                recoveredRequestKey = request.requestKey
                return com.gigagochi.app.core.network.FeatureApiResult.Success(
                    com.gigagochi.app.core.network.ChatResponseDto("Слышу."),
                )
            }
        }
        val result = RealDashboardChatAdapter(successfulApi, OwnerId, repository, this)
            .reply(request, restored.pet)

        assertEquals(request.requestKey, recoveredRequestKey)
        assertEquals("Слышу.", result.reply)
        assertEquals("Слышу.", repository.getPendingChat(OwnerId, request.requestKey)?.responseText)
        val offlineRecoveryApi = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto):
                com.gigagochi.app.core.network.FeatureApiResult<com.gigagochi.app.core.network.ChatResponseDto> =
                error("completed pending must not hit the network")
        }
        val offlineResult = RealDashboardChatAdapter(offlineRecoveryApi, OwnerId, repository, this)
            .reply(request, restored.pet)
        assertEquals("Слышу.", offlineResult.reply)
        assertEquals(
            request.requestKey,
            (AccountPetLifecycle(repository).startup(OwnerId) as AccountStartupDestination.Dashboard)
                .pendingChat?.requestKey,
        )
    }

    @Test
    fun networkChatFailureRetainsPendingAndReplaysTheSameRequestKey() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val request = PendingChatRequest("chat-response-lost", "Ответ потерялся?")
        val requestKeys = mutableListOf<String>()
        var responseLost = true
        val api = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto):
                com.gigagochi.app.core.network.FeatureApiResult<com.gigagochi.app.core.network.ChatResponseDto> {
                requestKeys += request.requestKey
                return if (responseLost) {
                    com.gigagochi.app.core.network.FeatureApiResult.Failure(
                        com.gigagochi.app.core.network.FeatureFailure(
                            com.gigagochi.app.core.network.FeatureFailureKind.Network,
                        ),
                    )
                } else {
                    com.gigagochi.app.core.network.FeatureApiResult.Success(
                        com.gigagochi.app.core.network.ChatResponseDto("Слышу."),
                    )
                }
            }
        }
        val adapter = RealDashboardChatAdapter(api, OwnerId, repository, this)
        try {
            adapter.reply(request, requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet)
        } catch (_: com.gigagochi.app.feature.create.FeatureAdapterException) {
            Unit
        }

        val retained = requireNotNull(repository.getPendingChat(OwnerId, request.requestKey))
        assertEquals(request.message, retained.message)
        responseLost = false
        val replayed = adapter.reply(request, requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet)

        assertEquals(listOf(request.requestKey, request.requestKey), requestKeys)
        assertEquals("Слышу.", replayed.reply)
        assertEquals("Слышу.", repository.getPendingChat(OwnerId, request.requestKey)?.responseText)
    }

    @Test
    fun definitiveChatRejectionDeletesPendingRequest() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val request = PendingChatRequest("chat-rejected", "Некорректный запрос")
        val api = object : com.gigagochi.app.core.network.TestAndroidFeatureService() {
            override suspend fun chat(request: com.gigagochi.app.core.network.ChatRequestDto) =
                com.gigagochi.app.core.network.FeatureApiResult.Failure(
                    com.gigagochi.app.core.network.FeatureFailure(
                        com.gigagochi.app.core.network.FeatureFailureKind.BadRequest,
                    ),
                )
        }
        try {
            RealDashboardChatAdapter(api, OwnerId, repository, this).reply(
                request,
                requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet,
            )
        } catch (_: com.gigagochi.app.feature.create.FeatureAdapterException) {
            Unit
        }

        assertNull(repository.getPendingChat(OwnerId, request.requestKey))
    }

    @Test
    fun existingPetWithoutFirstSessionRowKeepsOrdinaryFlow() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))

        assertNull(repository.getFirstSession(OwnerId, PetId))
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun debugRestartFirstSessionRewindsDurableStageAndClearsActionReceipts() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat.storageValue,
                null,
                null,
                1,
            ),
        )
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 2,
            ) is FirstSessionMutationResult.Applied,
        )

        val restarted = repository.restartFirstSession(OwnerId, PetId, 3)
        assertEquals(FirstSessionStage.AwaitingChat, restarted?.stage)
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 4,
            ) is FirstSessionMutationResult.Applied,
        )

        assertTrue(repository.disableFirstSession(OwnerId, PetId))
        assertNull(repository.getFirstSession(OwnerId, PetId))
        repository.restartFirstSession(OwnerId, PetId, 5)
        assertTrue(
            repository.advanceFirstSession(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingChat,
                FirstSessionStage.AwaitingChatFollowup,
                "chat-name",
                nowEpochMillis = 6,
            ) is FirstSessionMutationResult.Applied,
        )
    }

    @Test
    fun onboardingOutfitRetryReusesPendingAndCompletesOnlyAfterAttach() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 200))
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                OwnerId,
                PetId,
                FirstSessionStage.AwaitingCompletionMessage.storageValue,
                null,
                null,
                1,
            ),
        )
        var calls = 0
        val adapter = object : DashboardOutfitAdapter {
            override suspend fun queue(
                request: PendingOutfitRequest,
                pet: PetDashboardState,
            ): PendingOutfitGeneration {
                calls += 1
                if (calls == 1) error("definite network failure")
                assertEquals("outfit-retry", request.requestKey)
                return PendingOutfitGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    displayItem = "Плащ",
                    localJobId = "outfit-${request.requestKey}",
                    backendJobId = "backend-retry",
                    createdAtEpochMillis = 10,
                )
            }
        }
        val first = DashboardDurableOperations(
            OwnerId, repository, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 10 },
        )
        assertTrue(first.acceptOutfit(
            PendingOutfitRequest("outfit-retry", "Зелёный плащ"),
            repository.getPetSnapshot(OwnerId, PetId)!!.pet,
        ) is DurableOutfitResult.PersistedButQueueFailed)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )

        val restarted = DashboardDurableOperations(
            OwnerId, repository, adapter, UnavailableDashboardTravelAdapter(), nowEpochMillis = { 20 },
        )
        assertTrue(restarted.acceptOutfit(
            PendingOutfitRequest("ignored-new-key", "Другой наряд"),
            repository.getPetSnapshot(OwnerId, PetId)!!.pet,
        ) is DurableOutfitResult.Queued)

        assertEquals(2, calls)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)
        assertEquals("outfit-retry", repository.getPendingOutfits(OwnerId).single().requestKey)
        assertEquals("backend-retry", repository.getPendingOutfits(OwnerId).single().backendJobId)
        assertEquals(FirstSessionStage.Completed, repository.getFirstSession(OwnerId, PetId)?.stage)
    }

    @Test
    fun firstSessionSurvivesRecreationAndIsAtomicIdempotentThroughCompletion() = runBlocking {
        repository.savePendingCreate(create())
        assertTrue(repository.finalizeCreatedPet(snapshot(experience = 0, hunger = 40), "create-request"))
        assertEquals(FirstSessionStage.AwaitingChat, repository.getFirstSession(OwnerId, PetId)?.stage)

        val firstChat = repository.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChat,
            FirstSessionStage.AwaitingChatFollowup, "chat-1", nowEpochMillis = 20,
        )
        assertTrue(firstChat is FirstSessionMutationResult.Applied)
        val recreatedAfterName = PetLocalRepository(database)
        assertEquals(
            FirstSessionStage.AwaitingChatFollowup,
            recreatedAfterName.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertTrue(recreatedAfterName.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChat,
            FirstSessionStage.AwaitingChatFollowup, "chat-1", nowEpochMillis = 21,
        ) is FirstSessionMutationResult.AlreadyApplied)

        assertTrue(repository.advanceFirstSession(
            OwnerId, PetId, FirstSessionStage.AwaitingChatFollowup,
            FirstSessionStage.AwaitingFirstFood, "chat-2", nowEpochMillis = 22,
        ) is FirstSessionMutationResult.Applied)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "leaf-crunch", "leaf-before-berry", 23,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(
            FirstSessionStage.AwaitingFirstFood,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-1", 24,
        ) is FirstSessionMutationResult.Applied)
        val hungerAfterBerry = repository.getPetSnapshot(OwnerId, PetId)!!.pet.hunger
        assertEquals(65, hungerAfterBerry)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-1", 25,
        ) is FirstSessionMutationResult.AlreadyApplied)
        assertEquals(hungerAfterBerry, repository.getPetSnapshot(OwnerId, PetId)!!.pet.hunger)

        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "berry-bowl", "berry-remedy", 26,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(FirstSessionStage.AwaitingRemedy, repository.getFirstSession(OwnerId, PetId)?.stage)
        assertTrue(repository.applyFirstSessionFood(
            OwnerId, PetId, "leaf-crunch", "leaf-remedy", 27,
        ) is FirstSessionMutationResult.Applied)

        assertTrue(repository.commitFirstSessionBatChoice(
            OwnerId, PetId, "bat-choice", 28,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(200, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(FirstSessionStage.AwaitingTravel, repository.getFirstSession(OwnerId, PetId)?.stage)
        val recreatedAfterChoice = PetLocalRepository(database)
        assertTrue(recreatedAfterChoice.commitFirstSessionBatChoice(
            OwnerId, PetId, "bat-choice", 29,
        ) is FirstSessionMutationResult.AlreadyApplied)
        assertEquals(200, recreatedAfterChoice.getPetSnapshot(OwnerId, PetId)?.pet?.experience)

        assertTrue(recreatedAfterChoice.finishFirstSessionBat(
            OwnerId, PetId, "bat-finish", 30,
        ) is FirstSessionMutationResult.Applied)
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(
            FirstSessionStage.AwaitingCompletionMessage,
            repository.getFirstSession(OwnerId, PetId)?.stage,
        )
        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachOutfitBackendJob(OwnerId, "outfit-request", "outfit-backend"),
        )
        assertEquals(FirstSessionStage.Completed, repository.getFirstSession(OwnerId, PetId)?.stage)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(outfit()))
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun createFinalizationIsIdempotentAndMergesLateBackgroundMedia() = runBlocking {
        repository.savePendingCreate(create())
        val foregroundMedia = PetGeneratedMedia(
            generatedAt = "2026-07-20T10:00:00Z",
            videoUrl = "https://gigagochi.serega.works/static/normal.mp4",
            moodImages = mediaImages("normal"),
        )
        assertTrue(repository.finalizeCreatedPet(
            snapshot(generatedMedia = foregroundMedia),
            "create-request",
            keepPendingCreate = true,
        ))

        val completeMedia = foregroundMedia.copy(
            sadVideoUrl = "https://gigagochi.serega.works/static/sad.mp4",
            happyVideoUrl = "https://gigagochi.serega.works/static/happy.mp4",
        )
        assertTrue(repository.finalizeCreatedPet(
            snapshot(generatedMedia = completeMedia),
            "create-request",
        ))
        assertTrue(repository.finalizeCreatedPet(
            snapshot(generatedMedia = completeMedia),
            "create-request",
        ))

        val saved = repository.getPetSnapshot(OwnerId, PetId)!!.pet.generatedMedia
        assertEquals(foregroundMedia.videoUrl, saved.videoUrl)
        assertEquals(completeMedia.sadVideoUrl, saved.sadVideoUrl)
        assertEquals(completeMedia.happyVideoUrl, saved.happyVideoUrl)
        assertTrue(repository.loadOwnerRecovery(OwnerId).pendingCreates.isEmpty())
    }

    @Test
    fun scheduledEpisodeIsOwnerScopedAndChoiceIsSingleRowIdempotent() = runBlocking {
        val base = ScheduledStory(
            "android-story-1234567890abcdef1234567890abcdef",
            PetId,
            "История",
            "Ситуация",
            "Что делать?",
            listOf("a", "b", "c", "d"),
            "2026-07-17T10:11:12Z",
            "https://gigagochi.serega.works/static/story.png?v=1",
            null,
        )
        assertTrue(repository.saveScheduledStory(LocalScheduledStory("owner-a", base)))
        assertTrue(repository.saveScheduledStory(LocalScheduledStory("owner-b", base)))
        assertEquals(1, repository.getScheduledStories("owner-a", PetId).size)
        assertEquals(1, repository.getScheduledStories("owner-b", PetId).size)
        val storyNotification = repository.getUnnotifiedNotifications("owner-a", PetId).single()
        assertEquals(LocalNotificationKind.ScheduledStory, storyNotification.kind)
        assertTrue(repository.markNotificationSent("owner-a", storyNotification, 20))
        assertTrue(repository.getUnnotifiedNotifications("owner-a", PetId).isEmpty())
        assertEquals(1, repository.getUnnotifiedNotifications("owner-b", PetId).size)

        val claim = repository.claimScheduledStoryChoice("owner-a", base.storyId, ChoiceKey, "b")
        assertTrue(claim is ScheduledStoryChoiceClaim.Claimed)
        assertTrue(
            repository.saveScheduledStory(
                LocalScheduledStory(
                    "owner-a",
                    base.copy(
                        selectedChoice = "b",
                        result = ScheduledStoryResult("result", "reaction", "consequence", 20),
                    ),
                    ChoiceKey,
                ),
            ),
        )
        assertTrue(
            repository.claimScheduledStoryChoice("owner-a", base.storyId, "other-key", "b")
                is ScheduledStoryChoiceClaim.Completed,
        )
        assertTrue(repository.getUnnotifiedNotifications("owner-a", PetId).isEmpty())
        assertNull(repository.getScheduledStory("owner-b", base.storyId)?.story?.selectedChoice)
    }

    @Test
    fun outfitAcceptanceChargesOnceAndBackendJobAttachRejectsConflict() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        val pending = outfit()

        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(pending))
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(pending))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)

        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.AlreadyAttached,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.Conflict,
            repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "backend-outfit-b"),
        )
        assertEquals("backend-outfit-a", repository.getPendingOutfits(OwnerId).single().backendJobId)
    }

    @Test
    fun duplicateStoryReceiptRewardsOnceAndClampsAtProductCaps() = runBlocking {
        repository.replacePetSnapshot(
            snapshot(
                experience = 2_950,
                hunger = 95,
                happiness = 5,
                energy = 100,
            ),
        )
        val receipt = storyReceipt(
            experienceDelta = 200,
            hungerDelta = 25,
            happinessDelta = -10,
            energyDelta = 20,
        )

        assertEquals(StoryApplicationResult.Applied, repository.applyInteractiveStoryReceipt(receipt))
        assertEquals(
            StoryApplicationResult.AlreadyApplied,
            repository.applyInteractiveStoryReceipt(receipt.copy(receiptKey = "different-key")),
        )

        val pet = requireNotNull(repository.getPetSnapshot(OwnerId, PetId)).pet
        assertEquals(3_000, pet.experience)
        assertEquals(100, pet.hunger)
        assertEquals(0, pet.happiness)
        assertEquals(100, pet.energy)
        assertEquals(1, repository.getStoryReceipts(OwnerId).size)
    }

    @Test
    fun replacingPetPreservesAllUnrelatedPendingRows() = runBlocking {
        repository.replacePetSnapshot(snapshot(experience = 500))
        repository.savePendingCreate(create())
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(IdempotentInsertResult.Inserted, repository.savePendingTravel(travel()))

        repository.replacePetSnapshot(snapshot(experience = 700, message = "Обновлено"))

        assertEquals(1, repository.getPendingCreates(OwnerId).size)
        assertEquals(1, repository.getPendingOutfits(OwnerId).size)
        assertEquals(1, repository.getPendingTravels(OwnerId).size)
        assertEquals("Обновлено", repository.getPetSnapshot(OwnerId, PetId)?.pet?.message)

        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachTravelBackendJob(OwnerId, "travel-request", "backend-travel-a"),
        )
        assertEquals(
            BackendJobAttachmentResult.Conflict,
            repository.attachTravelBackendJob(OwnerId, "travel-request", "backend-travel-b"),
        )
        assertEquals("backend-travel-a", repository.getPendingTravels(OwnerId).single().backendJobId)
    }

    @Test
    fun terminalCreateRetryAtomicallyReplacesFailedRequest() = runBlocking {
        repository.savePendingCreate(
            create(
                requestKey = "failed-request",
                backendJobId = "failed-job",
                backendState = PendingBackendState.Failed,
            ),
        )
        val replacement = create(requestKey = "retry-request")

        assertTrue(
            repository.replaceFailedPendingCreate(
                OwnerId,
                "failed-request",
                replacement,
            ),
        )

        val pending = repository.getPendingCreates(OwnerId).single()
        assertEquals("retry-request", pending.requestKey)
        assertNull(pending.backendJobId)
        assertEquals(PendingBackendState.Pending, pending.backendState)
    }

    @Test
    fun staleCreateAnswerWritePreservesAttachedAndTerminalBackendProgress() = runBlocking {
        val initial = create().copy(
            backendJobId = null,
            stage = PendingCreateStage.Requested,
            name = null,
            personality = null,
            fear = null,
            favoriteItem = null,
            currentStep = 1,
            updatedAtEpochMillis = 100L,
            backendState = PendingBackendState.Pending,
            backendErrorCode = null,
        )
        repository.savePendingCreate(initial)

        // This is the answer snapshot captured before the backend coroutine attaches its job.
        val staleBackendButNewerAnswer = initial.copy(
            stage = PendingCreateStage.Generating,
            name = "Тото",
            personality = "Добрый",
            fear = "Пауков",
            favoriteItem = "Вантуз",
            currentStep = 5,
            updatedAtEpochMillis = 200L,
        )
        val releaseStaleWrite = CompletableDeferred<Unit>()
        val staleWriter = launch {
            releaseStaleWrite.await()
            repository.savePendingCreate(staleBackendButNewerAnswer)
        }

        assertTrue(
            repository.updateCreateBackendState(
                OwnerId,
                "create-request",
                PendingBackendState.Dispatching,
            ),
        )
        assertEquals(
            BackendJobAttachmentResult.Attached,
            repository.attachCreateBackendJob(
                OwnerId,
                "create-request",
                "backend-attached",
            ),
        )
        assertTrue(
            repository.updateCreateBackendState(
                OwnerId,
                "create-request",
                PendingBackendState.Attached,
            ),
        )
        releaseStaleWrite.complete(Unit)
        staleWriter.join()

        val attached = repository.getPendingCreates(OwnerId).single()
        assertEquals("backend-attached", attached.backendJobId)
        assertEquals(PendingBackendState.Attached, attached.backendState)
        assertNull(attached.backendErrorCode)
        assertEquals(PendingCreateStage.Generating, attached.stage)
        assertEquals("Тото", attached.name)
        assertEquals("Добрый", attached.personality)
        assertEquals("Пауков", attached.fear)
        assertEquals("Вантуз", attached.favoriteItem)
        assertEquals(5, attached.currentStep)
        assertEquals(200L, attached.updatedAtEpochMillis)

        // A lower-step snapshot cannot roll answers back, even with the same timestamp.
        repository.savePendingCreate(
            staleBackendButNewerAnswer.copy(
                stage = PendingCreateStage.Requested,
                name = null,
                personality = null,
                fear = null,
                favoriteItem = null,
                currentStep = 1,
                updatedAtEpochMillis = 200L,
            ),
        )
        assertEquals(attached, repository.getPendingCreates(OwnerId).single())

        // Nor can an older lower-step snapshot roll progress or its logical timestamp backwards.
        repository.savePendingCreate(
            staleBackendButNewerAnswer.copy(
                stage = PendingCreateStage.Requested,
                name = null,
                personality = null,
                fear = null,
                favoriteItem = null,
                currentStep = 1,
                updatedAtEpochMillis = 150L,
            ),
        )
        assertEquals(attached, repository.getPendingCreates(OwnerId).single())

        repository.deletePendingCreate(OwnerId, "create-request")
        listOf(
            PendingBackendState.Ready to null,
            PendingBackendState.OutcomeUnknown to "SUBMIT_UNKNOWN",
            PendingBackendState.Failed to "GENERATION_FAILED",
        ).forEachIndexed { index, (terminalState, errorCode) ->
            val requestKey = "terminal-create-$index"
            repository.savePendingCreate(
                initial.copy(
                    requestKey = requestKey,
                    backendJobId = "terminal-job-$index",
                    updatedAtEpochMillis = 300L + index,
                    backendState = terminalState,
                    backendErrorCode = errorCode,
                ),
            )
            repository.savePendingCreate(
                staleBackendButNewerAnswer.copy(
                    requestKey = requestKey,
                    backendJobId = null,
                    updatedAtEpochMillis = 400L + index,
                    backendState = PendingBackendState.Pending,
                    backendErrorCode = null,
                ),
            )

            val terminal = repository.getPendingCreates(OwnerId).single {
                it.requestKey == requestKey
            }
            assertEquals("terminal-job-$index", terminal.backendJobId)
            assertEquals(terminalState, terminal.backendState)
            assertEquals(errorCode, terminal.backendErrorCode)
            assertEquals(PendingCreateStage.Generating, terminal.stage)
            assertEquals(5, terminal.currentStep)
            assertEquals(400L + index, terminal.updatedAtEpochMillis)
        }
    }

    @Test
    fun invalidDataIsRejectedBeforeAnyDatabaseWrite() = runBlocking {
        assertRejected {
            repository.replacePetSnapshot(snapshot(experience = 3_001))
        }
        assertRejected {
            repository.savePendingTravel(travel(prompt = "x".repeat(1_001)))
        }
        assertRejected {
            repository.savePendingCreate(create(ownerId = " "))
        }

        assertTrue(repository.getPetSnapshots(OwnerId).isEmpty())
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())
        assertTrue(repository.getPendingCreates(OwnerId).isEmpty())
    }

    @Test
    fun ownerRecoveryTransactionReturnsOnlyRequestedOwnerRows() = runBlocking {
        repository.replacePetSnapshot(snapshot(ownerId = OwnerId, experience = 500))
        repository.replacePetSnapshot(snapshot(ownerId = "owner-b", experience = 900))
        repository.savePendingCreate(create())
        repository.savePendingCreate(create(ownerId = "owner-b"))
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(outfit()))
        assertEquals(
            OutfitAcceptanceResult.Applied,
            repository.acceptOutfit(
                outfit().copy(
                    ownerId = "owner-b",
                ),
            ),
        )
        repository.savePendingTravel(travel())
        repository.savePendingTravel(
            travel().copy(
                ownerId = "owner-b",
            ),
        )
        repository.applyInteractiveStoryReceipt(
            storyReceipt(10, 0, 0, 0),
        )
        repository.applyInteractiveStoryReceipt(
            storyReceipt(20, 0, 0, 0).copy(
                ownerId = "owner-b",
            ),
        )

        val recovery = repository.loadOwnerRecovery(OwnerId)
        assertEquals(setOf(OwnerId), recovery.petSnapshots.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingCreates.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingOutfits.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.pendingTravels.map { it.ownerId }.toSet())
        assertEquals(setOf(OwnerId), recovery.storyReceipts.map { it.ownerId }.toSet())
        assertEquals(1, recovery.petSnapshots.size)
        assertEquals(1, recovery.pendingCreates.size)
        assertEquals(1, recovery.pendingOutfits.size)
        assertEquals(1, recovery.pendingTravels.size)
        assertEquals(1, recovery.storyReceipts.size)
        val ownerBRecovery = repository.loadOwnerRecovery("owner-b")
        assertEquals(setOf("owner-b"), ownerBRecovery.petSnapshots.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingCreates.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingOutfits.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.pendingTravels.map { it.ownerId }.toSet())
        assertEquals(setOf("owner-b"), ownerBRecovery.storyReceipts.map { it.ownerId }.toSet())
    }

    @Test
    fun outfitOutcomesKeepRequestScopedImagesAndNeverPrematurelyMutateSnapshot() = runBlocking {
        val activeImages = mediaImages("active")
        repository.replacePetSnapshot(
            snapshot().copy(
                pet = snapshot().pet.copy(
                    generatedMedia = PetGeneratedMedia(moodImages = activeImages),
                ),
            ),
        )
        repository.saveOutfitMediaOutcome(outcome("request-b", "job-b", mediaImages("b")))
        repository.saveOutfitMediaOutcome(outcome("request-c", "job-c", mediaImages("c")))

        assertEquals(activeImages, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.moodImages)
        val outcomes = repository.getOutfitMediaOutcomes(OwnerId, PetId).associateBy { it.requestKey }
        assertEquals(mediaImages("b"), outcomes.getValue("request-b").media.moodImages)
        assertEquals(mediaImages("c"), outcomes.getValue("request-c").media.moodImages)
    }

    @Test
    fun characterBibleAboveLegacy64KiBRoundTripsButOver256KiBIsRejectedBeforeWrite() = runBlocking {
        val accepted = "{\"lore\":\"${"я".repeat(100_000)}\"}"
        repository.replacePetSnapshot(
            snapshot().copy(
                pet = snapshot().pet.copy(
                    generatedMedia = PetGeneratedMedia(characterBibleJson = accepted),
                ),
            ),
        )
        assertEquals(accepted, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.characterBibleJson)

        val rejected = "{\"lore\":\"${"я".repeat(140_000)}\"}"
        assertRejected {
            repository.replacePetSnapshot(
                snapshot().copy(
                    pet = snapshot().pet.copy(
                        generatedMedia = PetGeneratedMedia(characterBibleJson = rejected),
                    ),
                ),
            )
        }
        assertEquals(accepted, repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia?.characterBibleJson)
    }

    @Test
    fun outfitApplyIsAtomicOwnerFencedIdempotentAndRejectsStaleSnapshotSave() = runBlocking {
        val previousMedia = PetGeneratedMedia(
            videoUrl = "https://gigagochi.serega.works/static/previous-idle.mp4",
            sadVideoUrl = "https://gigagochi.serega.works/static/previous-sad.mp4",
            happyVideoUrl = "https://gigagochi.serega.works/static/previous-happy.mp4",
            moodImages = mediaImages("previous"),
        )
        repository.replacePetSnapshot(
            snapshot(experience = 500).copy(
                pet = snapshot(experience = 500).pet.copy(generatedMedia = previousMedia),
            ),
        )
        val pending = outfit()
        assertEquals(OutfitAcceptanceResult.Applied, repository.acceptOutfit(pending))
        val pendingOutfitExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character_outfit", pendingOutfitExperience.kind)
        assertEquals("fact", pendingOutfitExperience.memoryClass)
        assertTrue(pendingOutfitExperience.id.startsWith("character-outfit-pending:"))
        assertTrue(pendingOutfitExperience.text.contains("ещё не переоделся"))
        assertTrue(pendingOutfitExperience.text.contains("футболку Metallica"))
        repository.attachOutfitBackendJob(OwnerId, pending.requestKey, "job-ready")
        repository.updateOutfitBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        repository.saveOutfitMediaOutcome(
            outcome(pending.requestKey, "job-ready", mediaImages("applied")),
        )
        val readyOutfitExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character-outfit:${pending.requestKey}", readyOutfitExperience.id)
        assertEquals("episode", readyOutfitExperience.memoryClass)
        assertTrue(readyOutfitExperience.text.contains("уже переоделся"))
        assertTrue(readyOutfitExperience.text.contains("плащ"))

        assertEquals(
            OutfitOutcomeApplicationResult.NotReady,
            repository.applyOutfitOutcome("owner-b", PetId, pending.requestKey),
        )
        val applied = repository.applyOutfitOutcome(OwnerId, PetId, pending.requestKey)
        assertTrue(applied is OutfitOutcomeApplicationResult.Applied)
        assertEquals("asset-${pending.requestKey}", repository.getPetSnapshot(OwnerId, PetId)?.pet?.assetSetId)
        val appliedMedia = requireNotNull(repository.getPetSnapshot(OwnerId, PetId)?.pet?.generatedMedia)
        assertEquals("https://gigagochi.serega.works/static/${pending.requestKey}-idle.mp4", appliedMedia.videoUrl)
        assertEquals(false, appliedMedia.moodImages.any { it.url.contains("previous") })
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())
        val outfitNotification = repository.getUnnotifiedNotifications(OwnerId, PetId).single()
        assertEquals(LocalNotificationKind.OutfitReady, outfitNotification.kind)
        assertTrue(repository.markNotificationSent(OwnerId, outfitNotification, 30))
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
        assertTrue(repository.getOutfitMediaOutcomes(OwnerId, PetId).isEmpty())
        val outfitExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character_outfit", outfitExperience.kind)
        assertTrue(outfitExperience.text.contains("плащ"))
        assertTrue(repository.applyOutfitOutcome(OwnerId, PetId, pending.requestKey) is OutfitOutcomeApplicationResult.AlreadyApplied)
        assertEquals(OutfitAcceptanceResult.AlreadyApplied, repository.acceptOutfit(pending))
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())

        val stale = snapshot(experience = 999)
        assertEquals(false, repository.replacePetSnapshotIfAssetCurrent(stale))
        assertEquals("asset-${pending.requestKey}", repository.getPetSnapshot(OwnerId, PetId)?.pet?.assetSetId)
        assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    @Test
    fun travelConsumptionKeepsReadableAssetAndDeletesPendingExactlyOnce() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = travel()
        repository.savePendingTravel(pending)
        val pendingTravelExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character_travel", pendingTravelExperience.kind)
        assertEquals("fact", pendingTravelExperience.memoryClass)
        assertTrue(pendingTravelExperience.id.startsWith("character-travel-pending:"))
        assertTrue(pendingTravelExperience.text.contains("ещё не вернулся"))
        assertTrue(pendingTravelExperience.text.contains("ночной рынок"))
        repository.attachTravelBackendJob(OwnerId, pending.requestKey, "travel-job")
        repository.updateTravelBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        repository.saveTravelVideoAsset(
            LocalTravelVideoAsset(
                OwnerId,
                PetId,
                pending.requestKey,
                "travel-job",
                pending.prompt,
                "Ночной рынок",
                "Сценарий",
                "https://gigagochi.serega.works/static/travel.jpg",
                "https://gigagochi.serega.works/static/travel.mp4",
                20,
            ),
        )
        val readyTravelExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character-travel:${pending.requestKey}", readyTravelExperience.id)
        assertEquals("episode", readyTravelExperience.memoryClass)
        assertTrue(readyTravelExperience.text.contains("уже вернулся"))
        assertTrue(readyTravelExperience.text.contains("Ночной рынок"))

        assertEquals(
            TravelAssetConsumptionResult.NotReady,
            repository.consumeTravelAsset("owner-b", PetId, pending.requestKey, 21),
        )
        val consumed = repository.consumeTravelAsset(OwnerId, PetId, pending.requestKey, 21)
        assertTrue(consumed is TravelAssetConsumptionResult.Consumed)
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())
        val recovered = repository.getTravelVideoAssets(OwnerId, PetId).single()
        assertEquals(21L, recovered.consumedAtEpochMillis)
        val travelExperience = repository.recentCharacterExperiences(OwnerId, PetId).single()
        assertEquals("character_travel", travelExperience.kind)
        assertTrue(travelExperience.text.contains("Ночной рынок"))
        assertTrue(travelExperience.text.contains("Сценарий"))
        repository.saveTravelVideoAsset(recovered.copy(consumedAtEpochMillis = null))
        assertEquals(21L, repository.getTravelVideoAssets(OwnerId, PetId).single().consumedAtEpochMillis)
        val travelNotification = repository.getUnnotifiedNotifications(OwnerId, PetId).single()
        assertEquals(LocalNotificationKind.TravelReady, travelNotification.kind)
        assertEquals(pending.requestKey, travelNotification.travelRequestKey)
        assertTrue(repository.markNotificationSent(OwnerId, travelNotification, 31))
        assertTrue(repository.getUnnotifiedNotifications(OwnerId, PetId).isEmpty())
        assertTrue(
            repository.consumeTravelAsset(OwnerId, PetId, pending.requestKey, 22) is
                TravelAssetConsumptionResult.AlreadyConsumed,
        )
        assertEquals(21L, repository.getTravelVideoAssets(OwnerId, PetId).single().consumedAtEpochMillis)
    }

    @Test
    fun applyConflictCasTransitionsOnlyReadyPendingToDurableFailed() = runBlocking {
        repository.replacePetSnapshot(snapshot())
        val pending = outfit()
        repository.acceptOutfit(pending)
        assertEquals(false, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
        repository.updateOutfitBackendState(OwnerId, pending.requestKey, PendingBackendState.Ready)
        assertEquals(true, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
        val failed = repository.getPendingOutfits(OwnerId).single()
        assertEquals(PendingBackendState.Failed, failed.backendState)
        assertEquals("APPLY_CONFLICT", failed.backendErrorCode)
        assertEquals(false, repository.markOutfitApplyConflict(OwnerId, pending.requestKey))
    }

    private suspend fun applyFeed(
        requestKey: String,
        food: String,
        audioIndex: Int,
    ): DashboardFeedApplicationResult = repository.applyDashboardFeed(
        ownerId = OwnerId,
        petId = PetId,
        expectedAssetSetId = AssetSetId,
        requestKey = requestKey,
        food = food,
        audioIndex = audioIndex,
        defaultPresentation = LocalDashboardFeedPresentation(
            reply = if (food == "berry-bowl") "Обычная ягода" else "Обычный лист",
            autoAdvanceDelayMillis = 6_000,
        ),
        firstFoodPresentation = LocalDashboardFeedPresentation(
            reply = "После первой еды",
            autoAdvanceDelayMillis = 6_000,
        ),
        remedyPresentation = LocalDashboardFeedPresentation(
            reply = "После лекарства",
            explicitPortions = listOf("Часть 1", "Часть 2"),
            autoAdvanceDelayMillis = 6_000,
        ),
    )

    private fun snapshot(
        ownerId: String = OwnerId,
        experience: Int = 500,
        hunger: Int = 100,
        happiness: Int = 100,
        energy: Int = 100,
        message: String = "Как тебя зовут?",
        petTapProgress: Int = 0,
        generatedMedia: PetGeneratedMedia = PetGeneratedMedia(),
    ) = OwnedPetSnapshot(
        ownerId = ownerId,
        pet = PetDashboardState(
            petId = PetId,
            assetSetId = AssetSetId,
            description = "Ледяной дракон",
            name = "Без имени",
            stage = "baby",
            stageLabel = "Уровень: Малыш",
            mood = "idle",
            experience = experience,
            hunger = hunger,
            happiness = happiness,
            energy = energy,
            message = message,
            petTapProgress = petTapProgress,
            generatedMedia = generatedMedia,
        ),
        updatedAtEpochMillis = 10,
    )

    private fun create(
        ownerId: String = OwnerId,
        requestKey: String = "create-request",
        backendJobId: String? = null,
        backendState: PendingBackendState = PendingBackendState.Pending,
    ) = LocalPendingCreateGeneration(
        ownerId = ownerId,
        petId = PetId,
        requestKey = requestKey,
        backendJobId = backendJobId,
        stage = PendingCreateStage.Generating,
        description = "Ледяной дракон",
        name = "Тото",
        personality = "Добрый",
        fear = "Пауков",
        favoriteItem = null,
        currentStep = 4,
        updatedAtEpochMillis = 11,
        backendState = backendState,
    )

    private fun outfit() = LocalPendingOutfit(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = "outfit-request",
        localJobId = "outfit-local",
        backendJobId = null,
        prompt = "В футболку Metallica",
        baseAssetSetId = AssetSetId,
        acceptedAtEpochMillis = 12,
    )

    private fun travel(prompt: String = "На ночной рынок духов") = LocalPendingTravelVideo(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = "travel-request",
        localJobId = "travel-local",
        backendJobId = null,
        prompt = prompt,
        acceptedAtEpochMillis = 13,
    )

    private fun storyReceipt(
        experienceDelta: Int,
        hungerDelta: Int,
        happinessDelta: Int,
        energyDelta: Int,
    ) = InteractiveStoryReceipt(
        ownerId = OwnerId,
        petId = PetId,
        receiptKey = "story-receipt",
        travelId = "travel-story",
        partKey = "result-1",
        experienceDelta = experienceDelta,
        hungerDelta = hungerDelta,
        happinessDelta = happinessDelta,
        energyDelta = energyDelta,
        appliedAtEpochMillis = 14,
    )

    private fun mediaImages(label: String) = listOf(
        PetMoodImage("baby", "idle", "https://gigagochi.serega.works/static/$label.png"),
    )

    private fun outcome(requestKey: String, jobId: String, images: List<PetMoodImage>) =
        LocalOutfitMediaOutcome(
            OwnerId,
            PetId,
            requestKey,
            jobId,
            "плащ",
            "asset-$requestKey",
            PetGeneratedMedia(
                generatedAt = "2026-07-17T10:11:12Z",
                videoUrl = "https://gigagochi.serega.works/static/$requestKey-idle.mp4",
                sadVideoUrl = "https://gigagochi.serega.works/static/$requestKey-sad.mp4",
                happyVideoUrl = "https://gigagochi.serega.works/static/$requestKey-happy.mp4",
                moodImages = images,
            ),
            20,
        )

    private suspend fun assertRejected(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected validation rejection", rejected)
    }

    private companion object {
        const val OwnerId = "owner-a"
        const val PetId = "pet-a"
        const val AssetSetId = "debug-test-pet-seedance-forest-mouse-v1"
        const val ChoiceKey = "123e4567-e89b-42d3-a456-426614174000"
    }
}
