package com.gigagochi.app.core.webview

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.DashboardChatReservationResult
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalOperationResult
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingCreateStage
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.network.AmbientRequestDto
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.ChatRequestDto
import com.gigagochi.app.core.network.ChatResponseDto
import com.gigagochi.app.core.network.CreateJobRequestDto
import com.gigagochi.app.core.network.DueStoryRequestDto
import com.gigagochi.app.core.network.DueStoryResponseDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.GenerationAssetDto
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobDto
import com.gigagochi.app.core.network.GenerationJobPhaseDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.MemoryConsolidationRequestDto
import com.gigagochi.app.core.network.MemoryConsolidationResponseDto
import com.gigagochi.app.core.network.MemoryExtractionRequestDto
import com.gigagochi.app.core.network.MemoryExtractionResponseDto
import com.gigagochi.app.core.network.OutfitJobRequestDto
import com.gigagochi.app.core.network.OutfitSimplifyRequestDto
import com.gigagochi.app.core.network.OutfitSimplifyResponseDto
import com.gigagochi.app.core.network.ProactiveRequestDto
import com.gigagochi.app.core.network.ProactiveResponseDto
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.network.TravelVideoDto
import com.gigagochi.app.core.network.TravelVideoRequestDto
import com.gigagochi.app.feature.create.CreateFinalizationCoordinator
import com.gigagochi.app.feature.create.CreateFinalizationResult
import com.gigagochi.app.feature.create.CreatePendingCoordinator
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.create.RealPetGenerationAdapter
import com.gigagochi.app.feature.create.executePetGeneration
import com.gigagochi.app.feature.dashboard.DashboardAmbientResult
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.RealDashboardChatAdapter
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end local fixture for the production WebView reducer and the durable Android data path.
 *
 * Authentication and HTTP transport are deliberately replaced at the composition boundary. The
 * test still uses the production bridge, runtime, create coordinators, feature adapters, Room
 * repository, first-session mutations, and replay receipts. The scripted feature service never
 * opens a socket.
 */
@RunWith(AndroidJUnit4::class)
class ProductionCreateChatBridgeRoomIntegrationTest {
    private lateinit var database: GigagochiDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createAndQueuedChatsSurviveProcessRestartsAndReplayExactlyOnce() = runBlocking {
        val durableClock = DeterministicEpochClock(FixedEpochMillis)
        val bridgeIds = CanonicalUuidSequence(10_000L)
        val service = ScriptedFeatureService()
        val dao = database.gigagochiDao()

        // Process 1: persist every answer, submit one create job, then die while polling.
        val createProcess = runtimeHarness(
            repository = PetLocalRepository(database, durableClock::next),
            service = service,
            durableClock = durableClock,
            runtimeId = FirstRuntimeId,
        )
        var snapshot = bootstrap(createProcess, bridgeIds.next())
        assertEquals("create", snapshot.route)
        assertEquals(0, snapshot.create?.step)

        val answers = listOf(
            "Ледяного дракона",
            "Тото",
            "Добрый",
            "Пауков",
            "Вантуз",
        )
        answers.forEachIndexed { index, answer ->
            snapshot = dispatch(
                harness = createProcess,
                transportRequestId = bridgeIds.next(),
                command = BridgeProductCommand(
                    type = "CREATE_ANSWER",
                    requestKey = if (index == 0) {
                        DurableCreateRequestKey
                    } else {
                        bridgeIds.next()
                    },
                    expectedSnapshotRevision = snapshot.revision,
                    payload = buildJsonObject {
                        put("answer", answer)
                        put("step", index)
                    },
                ),
            )
        }
        assertEquals(5, snapshot.create?.step)
        assertEquals("running", snapshot.create?.generation)
        assertEquals("formed", snapshot.create?.phase)

        withTimeout(5_000L) { service.firstCreatePollStarted.await() }
        val pendingCreate = requireNotNull(
            dao.getPendingCreate(OwnerId, DurableCreateRequestKey),
        )
        assertEquals(OwnerId, pendingCreate.ownerId)
        assertEquals(PetId, pendingCreate.petId)
        assertEquals(CreateJobId, pendingCreate.backendJobId)
        assertEquals(PendingCreateStage.Generating, pendingCreate.stage)
        assertEquals(PendingBackendState.Attached, pendingCreate.backendState)
        assertEquals(answers[0], pendingCreate.description)
        assertEquals(answers[1], pendingCreate.name)
        assertEquals(answers[2], pendingCreate.personality)
        assertEquals(answers[3], pendingCreate.fear)
        assertEquals(answers[4], pendingCreate.favoriteItem)
        assertEquals(5, pendingCreate.currentStep)
        // Bootstrap plus each coordinator load uses the same deterministic repository clock;
        // the fifth answer is therefore the eleventh tick.
        assertEquals(FixedEpochMillis + 10L, pendingCreate.updatedAtEpochMillis)
        assertEquals(
            listOf(
                CreateJobRequestDto(
                    requestKey = DurableCreateRequestKey,
                    petId = PetId,
                    description = answers[0],
                ),
            ),
            service.createSubmissions.toList(),
        )

        createProcess.close()
        withTimeout(5_000L) { service.firstCreatePollCancelled.await() }

        // Process 2: recover the same attached create job, finish it, and enter Dashboard.
        val chatSubmissionProcess = runtimeHarness(
            repository = PetLocalRepository(database, durableClock::next),
            service = service,
            durableClock = durableClock,
            runtimeId = SecondRuntimeId,
        )
        val recoveredCreate = bootstrap(chatSubmissionProcess, bridgeIds.next())
        assertEquals("create", recoveredCreate.route)
        assertEquals(5, recoveredCreate.create?.step)
        assertEquals("running", recoveredCreate.create?.generation)
        assertEquals(DurableCreateRequestKey, pendingCreate.requestKey)
        withTimeout(5_000L) { service.secondCreatePollStarted.await() }

        val createReadyEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                chatSubmissionProcess.runtime.events.first { it.type == "stateChanged" }
            }
        }
        service.releaseSecondCreatePoll.complete(Unit)
        val generated = decodeStateChanged(createReadyEvent.await())
        assertEquals("ready", generated.create?.generation)
        assertEquals("formed", generated.create?.phase)

        snapshot = dispatch(
            harness = chatSubmissionProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "CREATE_FINISH",
                requestKey = CreateFinishCommandKey,
                expectedSnapshotRevision = generated.revision,
                payload = buildJsonObject {},
            ),
        )
        assertEquals("dashboard", snapshot.route)
        assertEquals("idle", snapshot.dashboardMode)
        assertEquals("Тото", snapshot.pet?.name)
        assertEquals(50, snapshot.pet?.hunger)
        assertEquals(50, snapshot.pet?.happiness)
        assertEquals(50, snapshot.pet?.energy)
        assertEquals(FirstSessionStage.AwaitingChat.storageValue, snapshot.firstSession?.stage)
        assertEquals(listOf("chat"), listOfNotNull(snapshot.firstSession?.allowedAction))

        assertNull(dao.getPendingCreate(OwnerId, DurableCreateRequestKey))
        assertTrue(dao.getPendingCreates(OwnerId).isEmpty())
        val createdPet = requireNotNull(dao.getPet(OwnerId, PetId))
        assertEquals(GeneratedAssetSetId, createdPet.assetSetId)
        assertEquals("Ледяного дракона", createdPet.description)
        assertEquals("Тото", createdPet.name)
        assertEquals(0, createdPet.experience)
        assertEquals(50, createdPet.hunger)
        assertEquals(50, createdPet.happiness)
        assertEquals(50, createdPet.energy)
        // Finalization writes at +13; the immediately following lifecycle bootstrap owns the
        // canonical +14 decay/read revision that is projected into Dashboard.
        assertEquals(FixedEpochMillis + 14L, createdPet.updatedAtEpochMillis)
        assertEquals(
            FirstSessionStage.AwaitingChat.storageValue,
            requireNotNull(dao.getFirstSession(OwnerId, PetId)).stage,
        )
        assertEquals(1, service.createSubmissions.size)
        assertEquals(2, service.createPolls.get())

        snapshot = settleReply(chatSubmissionProcess, bridgeIds, snapshot, source = "firstSession")
        snapshot = dispatch(
            harness = chatSubmissionProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "DASHBOARD_OPEN_MODE",
                requestKey = bridgeIds.next(),
                expectedSnapshotRevision = snapshot.revision,
                payload = buildJsonObject { put("mode", "chat") },
            ),
        )
        snapshot = dispatchDraft(
            chatSubmissionProcess,
            bridgeIds,
            snapshot,
            FirstChatMessage,
        )
        snapshot = dispatch(
            harness = chatSubmissionProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "CHAT_SEND",
                requestKey = FirstChatRequestKey,
                expectedSnapshotRevision = snapshot.revision,
                payload = buildJsonObject { put("message", FirstChatMessage) },
            ),
        )
        assertEquals(FirstChatRequestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertTrue(snapshot.dashboard?.chat?.thinking == true)
        withTimeout(5_000L) { service.firstChatAttemptStarted.await() }

        snapshot = dispatchDraft(
            chatSubmissionProcess,
            bridgeIds,
            snapshot,
            SecondChatMessage,
        )
        snapshot = dispatch(
            harness = chatSubmissionProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "CHAT_SEND",
                requestKey = SecondChatRequestKey,
                expectedSnapshotRevision = snapshot.revision,
                payload = buildJsonObject { put("message", SecondChatMessage) },
            ),
        )
        assertEquals(FirstChatRequestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(SecondChatRequestKey, snapshot.dashboard?.chat?.queuedRequestKey)

        val queuedBeforeDeath = dao.getPendingChats(OwnerId)
        assertEquals(listOf(FirstChatRequestKey, SecondChatRequestKey), queuedBeforeDeath.map { it.requestKey })
        assertEquals(listOf(FirstChatMessage, SecondChatMessage), queuedBeforeDeath.map { it.message })
        assertEquals(listOf(null, null), queuedBeforeDeath.map { it.responseText })
        assertTrue(queuedBeforeDeath[0].createdAtEpochMillis < queuedBeforeDeath[1].createdAtEpochMillis)
        assertEquals(
            FirstSessionStage.AwaitingChat.storageValue,
            requireNotNull(dao.getFirstSession(OwnerId, PetId)).stage,
        )
        assertChatReceipt(
            requestKey = FirstChatRequestKey,
            message = FirstChatMessage,
            origin = FirstSessionStage.AwaitingChat,
        )
        assertChatReceipt(
            requestKey = SecondChatRequestKey,
            message = SecondChatMessage,
            origin = FirstSessionStage.AwaitingChatFollowup,
        )

        chatSubmissionProcess.close()
        withTimeout(5_000L) { service.firstChatAttemptCancelled.await() }
        assertEquals(
            listOf(FirstChatRequestKey),
            service.chatRequests.map(ChatRequestDto::requestKey),
        )
        assertTrue(dao.getRecentChatMessages(OwnerId, PetId, 200).isEmpty())
        assertNull(dao.getAppliedChatResponse(OwnerId, FirstChatRequestKey))
        assertNull(dao.getAppliedChatResponse(OwnerId, SecondChatRequestKey))

        // Process 3: recover the first durable chat, then die after its successful Room commit but
        // before the reply is acknowledged. The second queued request is also cancelled in-flight.
        val recoveryProcess = runtimeHarness(
            repository = PetLocalRepository(database, durableClock::next),
            service = service,
            durableClock = durableClock,
            runtimeId = ThirdRuntimeId,
        )
        snapshot = bootstrap(recoveryProcess, bridgeIds.next())
        assertEquals("dashboard", snapshot.route)
        assertEquals(FirstChatRequestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(SecondChatRequestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertTrue(snapshot.dashboard?.chat?.thinking == true)
        withTimeout(5_000L) { service.recoveredFirstChatStarted.await() }

        val firstChatEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                recoveryProcess.runtime.events.first { it.type == "stateChanged" }
            }
        }
        service.releaseRecoveredFirstChat.complete(Unit)
        val firstCompleted = decodeStateChanged(firstChatEvent.await())
        assertEquals(FirstSessionStage.AwaitingChatFollowup.storageValue, firstCompleted.firstSession?.stage)
        assertEquals(FirstChatRequestKey, firstCompleted.dashboard?.reply?.requestKey)
        assertEquals(
            listOf(FirstChatBackendReply, FirstSessionAfterName),
            firstCompleted.dashboard?.reply?.portions,
        )
        withTimeout(5_000L) { service.firstSecondChatAttemptStarted.await() }
        recoveryProcess.close()
        withTimeout(5_000L) { service.firstSecondChatAttemptCancelled.await() }

        val firstCompletedBeforeColdRestart = dao.getPendingChats(OwnerId)
        assertEquals(
            listOf(FirstChatBackendReply, null),
            firstCompletedBeforeColdRestart.map { it.responseText },
        )
        assertEquals(
            FirstSessionStage.AwaitingChatFollowup.storageValue,
            requireNotNull(dao.getFirstSession(OwnerId, PetId)).stage,
        )

        // Process 4: cold Room bootstrap must reconstruct the first onboarding portions from the
        // persisted receipt origin, then retry and finish the second request.
        val presentationRecoveryProcess = runtimeHarness(
            repository = PetLocalRepository(database, durableClock::next),
            service = service,
            durableClock = durableClock,
            runtimeId = FourthRuntimeId,
        )
        snapshot = bootstrap(presentationRecoveryProcess, bridgeIds.next())
        assertEquals(FirstChatRequestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(
            listOf(FirstChatBackendReply, FirstSessionAfterName),
            snapshot.dashboard?.reply?.portions,
        )
        assertEquals(SecondChatRequestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertTrue(snapshot.dashboard?.chat?.thinking == true)
        withTimeout(5_000L) { service.recoveredSecondChatStarted.await() }
        val secondChatEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                presentationRecoveryProcess.runtime.events.first { it.type == "stateChanged" }
            }
        }
        service.releaseRecoveredSecondChat.complete(Unit)
        val bothCompleted = decodeStateChanged(secondChatEvent.await())
        assertEquals(FirstSessionStage.AwaitingFirstFood.storageValue, bothCompleted.firstSession?.stage)
        assertEquals(FirstChatRequestKey, bothCompleted.dashboard?.reply?.requestKey)
        assertEquals(FirstChatRequestKey, bothCompleted.pending.chat?.requestKey)
        assertEquals("completed", bothCompleted.pending.chat?.status)

        val completedRows = dao.getPendingChats(OwnerId)
        assertEquals(listOf(FirstChatRequestKey, SecondChatRequestKey), completedRows.map { it.requestKey })
        assertEquals(
            listOf(FirstChatBackendReply, SecondChatBackendReply),
            completedRows.map { it.responseText },
        )
        assertTrue(completedRows.all { it.completedAtEpochMillis != null })
        assertEquals(
            FirstSessionStage.AwaitingFirstFood.storageValue,
            requireNotNull(dao.getFirstSession(OwnerId, PetId)).stage,
        )
        assertEquals(
            "chat:${FirstSessionStage.AwaitingChat.storageValue}",
            requireNotNull(
                dao.getFirstSessionActionReceipt(OwnerId, PetId, FirstChatRequestKey),
            ).actionKind,
        )
        assertEquals(
            "chat:${FirstSessionStage.AwaitingChatFollowup.storageValue}",
            requireNotNull(
                dao.getFirstSessionActionReceipt(OwnerId, PetId, SecondChatRequestKey),
            ).actionKind,
        )

        val chronologicalMessages = dao.getRecentChatMessages(OwnerId, PetId, 200).asReversed()
        assertEquals(4, chronologicalMessages.size)
        assertEquals(listOf("user", "pet", "user", "pet"), chronologicalMessages.map { it.role })
        assertEquals(
            listOf(
                FirstChatMessage,
                FirstChatBackendReply,
                SecondChatMessage,
                SecondChatBackendReply,
            ),
            chronologicalMessages.map { it.text },
        )
        assertEquals(4, chronologicalMessages.map { it.messageId }.toSet().size)
        assertEquals(
            30,
            requireNotNull(dao.getAppliedChatResponse(OwnerId, FirstChatRequestKey)).happinessDelta,
        )
        assertEquals(
            FirstComplimentKey,
            requireNotNull(dao.getAppliedChatResponse(OwnerId, FirstChatRequestKey)).complimentKey,
        )
        assertEquals(
            0,
            requireNotNull(dao.getAppliedChatResponse(OwnerId, SecondChatRequestKey)).happinessDelta,
        )
        assertEquals(80, requireNotNull(dao.getPet(OwnerId, PetId)).happiness)
        assertEquals(
            listOf(
                FirstChatRequestKey,
                FirstChatRequestKey,
                SecondChatRequestKey,
                SecondChatRequestKey,
            ),
            service.chatRequests.map(ChatRequestDto::requestKey),
        )

        snapshot = dispatch(
            harness = presentationRecoveryProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "CHAT_REPLY_PRESENTED",
                requestKey = bridgeIds.next(),
                expectedSnapshotRevision = bothCompleted.revision,
                payload = buildJsonObject { put("requestKey", FirstChatRequestKey) },
            ),
        )
        assertEquals(SecondChatRequestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(
            listOf(SecondChatBackendReply, FirstSessionAfterChat),
            snapshot.dashboard?.reply?.portions,
        )
        presentationRecoveryProcess.close()

        // Process 5: die once more after deleting the first row but before acknowledging the
        // follow-up. Cold bootstrap must also reconstruct the mandatory hunger prompt.
        val followupPresentationRecoveryProcess = runtimeHarness(
            repository = PetLocalRepository(database, durableClock::next),
            service = service,
            durableClock = durableClock,
            runtimeId = FifthRuntimeId,
        )
        snapshot = bootstrap(followupPresentationRecoveryProcess, bridgeIds.next())
        assertEquals(SecondChatRequestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(
            listOf(SecondChatBackendReply, FirstSessionAfterChat),
            snapshot.dashboard?.reply?.portions,
        )
        snapshot = dispatch(
            harness = followupPresentationRecoveryProcess,
            transportRequestId = bridgeIds.next(),
            command = BridgeProductCommand(
                type = "CHAT_REPLY_PRESENTED",
                requestKey = bridgeIds.next(),
                expectedSnapshotRevision = snapshot.revision,
                payload = buildJsonObject { put("requestKey", SecondChatRequestKey) },
            ),
        )
        assertNull(snapshot.pending.chat)
        assertTrue(dao.getPendingChats(OwnerId).isEmpty())
        snapshot = settleReply(
            followupPresentationRecoveryProcess,
            bridgeIds,
            snapshot,
            source = "chat",
        )

        val messagesBeforeReplay = dao.getRecentChatMessages(OwnerId, PetId, 200)
        val firstAppliedBeforeReplay =
            requireNotNull(dao.getAppliedChatResponse(OwnerId, FirstChatRequestKey))
        val secondAppliedBeforeReplay =
            requireNotNull(dao.getAppliedChatResponse(OwnerId, SecondChatRequestKey))
        val firstSessionBeforeReplay = requireNotNull(dao.getFirstSession(OwnerId, PetId))
        val backendCallsBeforeReplay = service.chatRequests.size

        assertChatReplayRejectedAfterOnboardingAdvanced(
            followupPresentationRecoveryProcess,
            bridgeIds,
            snapshot,
            requestKey = FirstChatRequestKey,
            message = FirstChatMessage,
        )
        assertChatReplayRejectedAfterOnboardingAdvanced(
            followupPresentationRecoveryProcess,
            bridgeIds,
            snapshot,
            requestKey = SecondChatRequestKey,
            message = SecondChatMessage,
        )

        assertEquals(backendCallsBeforeReplay, service.chatRequests.size)
        assertEquals(messagesBeforeReplay, dao.getRecentChatMessages(OwnerId, PetId, 200))
        assertEquals(
            firstAppliedBeforeReplay,
            dao.getAppliedChatResponse(OwnerId, FirstChatRequestKey),
        )
        assertEquals(
            secondAppliedBeforeReplay,
            dao.getAppliedChatResponse(OwnerId, SecondChatRequestKey),
        )
        assertEquals(firstSessionBeforeReplay, dao.getFirstSession(OwnerId, PetId))
        assertEquals(80, snapshot.pet?.happiness)
        assertTrue(dao.getPendingChats(OwnerId).isEmpty())
        assertFalse(service.networkTouched)

        followupPresentationRecoveryProcess.close()
    }

    private fun runtimeHarness(
        repository: PetLocalRepository,
        service: ScriptedFeatureService,
        durableClock: DeterministicEpochClock,
        runtimeId: String,
    ): RuntimeHarness {
        val mediaRegistry = WebMediaReferenceRegistry(
            StaticMediaUrlPolicy("https://example.test/", allowDebugLoopbackHttp = false),
        )
        val gateway = RoomCreateChatGateway(repository, service, durableClock)
        val runtime = ProductionWebAppRuntime(
            gateway = gateway,
            appVersion = "production-create-chat-integration",
            webBundleVersion = BridgeWebBundleVersion,
            reducedMotion = { true },
            mediaRegistry = mediaRegistry,
            runtimeId = runtimeId,
            generationScope = CoroutineScope(Dispatchers.Default),
            petIdFactory = { PetId },
            elapsedRealtimeMillis = { FixedElapsedRealtimeMillis },
            delayMillis = { },
            foregroundRefreshDelayMillis = { awaitCancellation() },
        )
        return RuntimeHarness(
            runtime = runtime,
            dispatcher = BridgeDispatcher(
                runtime = runtime,
                monotonicClock = BridgeMonotonicClock {
                    monotonicNanos.getAndAdd(MonotonicStepNanos)
                },
            ),
            mediaRegistry = mediaRegistry,
        )
    }

    private suspend fun bootstrap(
        harness: RuntimeHarness,
        transportRequestId: String,
    ): WebAppSnapshot {
        harness.documentId = CanonicalUuidSequence.documentFor(transportRequestId)
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = harness.documentId,
                    bridgeSessionId = null,
                    requestId = transportRequestId,
                    method = "bootstrap",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        BridgeBootstrapPayload.serializer(),
                        BridgeBootstrapPayload(
                            supportedProtocolVersions = listOf(BridgeProtocolVersion),
                            webBundleVersion = BridgeWebBundleVersion,
                            schemaHash = BridgeSchemaHash,
                        ),
                    ),
                ),
            ),
        )
        assertTrue("bootstrap error=${response.error}", response.ok)
        harness.bridgeSessionId = requireNotNull(response.bridgeSessionId)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private suspend fun dispatch(
        harness: RuntimeHarness,
        transportRequestId: String,
        command: BridgeProductCommand,
    ): WebAppSnapshot {
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = harness.documentId,
                    bridgeSessionId = requireNotNull(harness.bridgeSessionId),
                    requestId = transportRequestId,
                    method = "dispatch",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        BridgeProductCommand.serializer(),
                        command,
                    ),
                ),
            ),
        )
        assertTrue("bridge error=${response.error}", response.ok)
        assertEquals(transportRequestId, response.requestId)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private suspend fun dispatchDraft(
        harness: RuntimeHarness,
        ids: CanonicalUuidSequence,
        snapshot: WebAppSnapshot,
        value: String,
    ): WebAppSnapshot = dispatch(
        harness = harness,
        transportRequestId = ids.next(),
        command = BridgeProductCommand(
            type = "DASHBOARD_UPDATE_DRAFT",
            requestKey = ids.next(),
            expectedSnapshotRevision = snapshot.revision,
            payload = buildJsonObject {
                put("mode", "chat")
                put("value", value)
            },
        ),
    )

    private suspend fun assertChatReplayRejectedAfterOnboardingAdvanced(
        harness: RuntimeHarness,
        ids: CanonicalUuidSequence,
        snapshot: WebAppSnapshot,
        requestKey: String,
        message: String,
    ) {
        val transportRequestId = ids.next()
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = harness.documentId,
                    bridgeSessionId = requireNotNull(harness.bridgeSessionId),
                    requestId = transportRequestId,
                    method = "dispatch",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        BridgeProductCommand.serializer(),
                        BridgeProductCommand(
                            type = "CHAT_SEND",
                            requestKey = requestKey,
                            expectedSnapshotRevision = snapshot.revision,
                            payload = buildJsonObject { put("message", message) },
                        ),
                    ),
                ),
            ),
        )
        assertFalse(response.ok)
        assertEquals(transportRequestId, response.requestId)
        assertEquals("WRONG_STAGE", response.error?.code)
        assertFalse(response.error?.retryable == true)
    }

    private suspend fun settleReply(
        harness: RuntimeHarness,
        ids: CanonicalUuidSequence,
        initial: WebAppSnapshot,
        source: String,
    ): WebAppSnapshot {
        var snapshot = initial
        repeat(8) {
            val reply = snapshot.dashboard?.reply
            if (reply == null || reply.source != source) return snapshot
            snapshot = dispatch(
                harness = harness,
                transportRequestId = ids.next(),
                command = BridgeProductCommand(
                    type = if (reply.hasNextPortion) "REPLY_ADVANCE" else "REPLY_COMPLETE",
                    requestKey = ids.next(),
                    expectedSnapshotRevision = snapshot.revision,
                    payload = buildJsonObject { put("requestKey", reply.requestKey) },
                ),
            )
        }
        error("Reply did not settle within the bounded fixture")
    }

    private fun assertChatReceipt(
        requestKey: String,
        message: String,
        origin: FirstSessionStage,
    ) {
        val receipt = requireNotNull(
            runBlocking { database.gigagochiDao().getDashboardCommandReceipt(OwnerId, requestKey) },
        )
        assertEquals(OwnerId, receipt.ownerId)
        assertEquals(PetId, receipt.petId)
        assertEquals("chat-send", receipt.commandType)
        assertEquals(chatFingerprint(message), receipt.payloadFingerprint)
        assertEquals(origin.storageValue, receipt.originFirstSessionStage)
        assertNull(receipt.food)
        assertNull(receipt.audioIndex)
        assertNull(receipt.reply)
    }

    private fun bridgeRequest(
        documentId: String,
        bridgeSessionId: String?,
        requestId: String,
        method: String,
        payload: JsonElement,
    ): String = BridgeCodec.json.encodeToString(
        BridgeRequestEnvelope.serializer(),
        BridgeRequestEnvelope(
            kind = "request",
            protocolVersion = BridgeProtocolVersion,
            documentId = documentId,
            bridgeSessionId = bridgeSessionId,
            requestId = requestId,
            method = method,
            payload = payload,
        ),
    )

    private fun bridgeResponse(raw: String): BridgeResponseEnvelope =
        BridgeCodec.json.decodeFromString(raw)

    private fun decodeStateChanged(event: WebAppRuntimeEvent): WebAppSnapshot =
        BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), event.payload)

    private fun chatFingerprint(message: String): String {
        val encoded = "chat-send\u0000$message".encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private class RuntimeHarness(
        val runtime: ProductionWebAppRuntime,
        val dispatcher: BridgeDispatcher,
        private val mediaRegistry: WebMediaReferenceRegistry,
    ) : AutoCloseable {
        var documentId: String = ""
        var bridgeSessionId: String? = null

        override fun close() {
            runtime.close()
            mediaRegistry.close()
        }
    }

    /**
     * Test composition root matching AndroidWebAppDataGateway's Create/Chat implementation while
     * replacing only session/bootstrap and the HTTP-backed AndroidFeatureApi.
     */
    private class RoomCreateChatGateway(
        private val repository: PetLocalRepository,
        private val service: AndroidFeatureService,
        private val durableClock: DeterministicEpochClock,
    ) : WebAppDataGateway {
        private val lifecycle = AccountPetLifecycle(repository, durableClock::next)
        private val postReplyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override suspend fun bootstrap(): WebRuntimeBootstrapResult =
            when (val destination = lifecycle.startup(OwnerId, PetId)) {
                AccountStartupDestination.Failure -> WebRuntimeBootstrapResult.LocalDataError
                is AccountStartupDestination.Create -> WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Create(
                        destination.pending?.let {
                            CreatePendingCoordinator(
                                OwnerId,
                                repository,
                                durableClock::next,
                            ).restore(it)
                        } ?: CreatePetState(),
                    ),
                )
                is AccountStartupDestination.Dashboard -> WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(destination),
                )
            }

        override suspend fun persistCreate(
            state: CreatePetState,
        ): WebCreatePersistenceResult = when (
            CreatePendingCoordinator(
                OwnerId,
                repository,
                durableClock::next,
            ).persist(state)
        ) {
            LocalOperationResult.Failure -> WebCreatePersistenceResult.Failure
            is LocalOperationResult.Success -> WebCreatePersistenceResult.Persisted
        }

        override suspend fun generateCreate(
            request: PendingPetGeneration,
        ): PetGenerationExecutionResult = executePetGeneration(
            RealPetGenerationAdapter(
                ownerId = OwnerId,
                store = repository,
                stateStore = repository,
                api = service,
                pollDelayMillis = 0L,
                maxPollAttempts = 4,
            ),
            request,
        )

        override suspend fun finalizeCreate(
            state: CreatePetState,
            foregroundHandled: Boolean,
        ): WebCreateFinalizationResult {
            val result = CreateFinalizationCoordinator(
                ownerId = OwnerId,
                lifecycle = lifecycle,
                store = repository,
                stateStore = repository,
                nowEpochMillis = durableClock::next,
            ).finalize(state, foregroundHandled)
            if (result !is CreateFinalizationResult.Success) {
                return WebCreateFinalizationResult.Failure
            }
            val destination = lifecycle.startup(OwnerId, result.pet.petId)
                as? AccountStartupDestination.Dashboard
                ?: return WebCreateFinalizationResult.Failure
            return WebCreateFinalizationResult.Success(destination)
        }

        override suspend fun reserveChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            originFirstSessionStage: FirstSessionStage?,
            queueAnchorRequestKey: String?,
            replacingQueuedRequestKey: String?,
        ): WebChatReservationResult = when (
            val result = repository.reserveDashboardChat(
                ownerId = OwnerId,
                petId = expectedPet.petId,
                expectedAssetSetId = expectedPet.assetSetId,
                requestKey = request.requestKey,
                message = request.message,
                originFirstSessionStage = originFirstSessionStage,
                queueAnchorRequestKey = queueAnchorRequestKey,
                replacingQueuedRequestKey = replacingQueuedRequestKey,
            )
        ) {
            is DashboardChatReservationResult.Pending -> WebChatReservationResult.Pending(
                pet = result.pet,
                pendingChat = result.request,
                originFirstSessionStage = result.originFirstSessionStage,
            )
            is DashboardChatReservationResult.Finished ->
                WebChatReservationResult.Finished(result.pet)
            DashboardChatReservationResult.Missing -> WebChatReservationResult.Missing
            DashboardChatReservationResult.Conflict -> WebChatReservationResult.Conflict
        }

        override suspend fun executeChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            expectedFirstSessionStage: FirstSessionStage?,
        ): WebChatExecutionResult {
            val livePet = repository.decayPetSnapshot(OwnerId, expectedPet.petId)?.pet
                ?: return WebChatExecutionResult.Failure
            if (livePet.assetSetId != expectedPet.assetSetId) {
                return WebChatExecutionResult.Failure
            }
            val adapterResult = RealDashboardChatAdapter(
                api = service,
                ownerId = OwnerId,
                repository = repository,
                postReplyScope = postReplyScope,
            ).reply(request, livePet)
            return completeDashboardFirstSessionChat(
                ownerId = OwnerId,
                repository = repository,
                request = request,
                adapterResult = adapterResult,
                originFirstSessionStage = expectedFirstSessionStage,
                nowEpochMillis = durableClock.next(),
            )
        }

        override suspend fun acknowledgeChat(requestKey: String): Boolean {
            val pending = repository.getPendingChat(OwnerId, requestKey)
            return pending == null || repository.deletePendingChat(OwnerId, requestKey)
        }

        override suspend fun applyPetTap(
            expectedPet: PetDashboardState,
        ): WebPetTapMutationResult = unexpected("applyPetTap")

        override suspend fun applyFeed(
            requestKey: String,
            food: DashboardFood,
            audioIndex: Int,
            expectedPet: PetDashboardState,
        ): WebFeedMutationResult = unexpected("applyFeed")

        override suspend fun reserveOutfit(
            request: PendingOutfitRequest,
            expectedPet: PetDashboardState,
        ): WebOutfitReservationResult = unexpected("reserveOutfit")

        override suspend fun executeOutfit(
            request: LocalPendingOutfit,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult = unexpected("executeOutfit")

        override suspend fun reserveTravel(
            request: PendingTravelRequest,
            expectedPet: PetDashboardState,
        ): WebTravelReservationResult = unexpected("reserveTravel")

        override suspend fun executeTravel(
            request: LocalPendingTravelVideo,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult = unexpected("executeTravel")

        override suspend fun refreshDashboardOperations(
            petId: String,
        ): WebDashboardOperationExecutionResult = unexpected("refreshDashboardOperations")

        override suspend fun refreshDashboardForForeground(
            petId: String,
        ): WebDashboardForegroundRefreshResult = unexpected("refreshDashboardForForeground")

        override fun close() {
            postReplyScope.cancel()
        }

        private fun unexpected(operation: String): Nothing =
            error("Unexpected operation in production Create/Chat fixture: $operation")
    }

    private class ScriptedFeatureService : AndroidFeatureService {
        val createSubmissions = CopyOnWriteArrayList<CreateJobRequestDto>()
        val createPolls = AtomicInteger()
        val chatRequests = CopyOnWriteArrayList<ChatRequestDto>()
        val firstCreatePollStarted = CompletableDeferred<Unit>()
        val firstCreatePollCancelled = CompletableDeferred<Unit>()
        val secondCreatePollStarted = CompletableDeferred<Unit>()
        val releaseSecondCreatePoll = CompletableDeferred<Unit>()
        val firstChatAttemptStarted = CompletableDeferred<Unit>()
        val firstChatAttemptCancelled = CompletableDeferred<Unit>()
        val recoveredFirstChatStarted = CompletableDeferred<Unit>()
        val releaseRecoveredFirstChat = CompletableDeferred<Unit>()
        val firstSecondChatAttemptStarted = CompletableDeferred<Unit>()
        val firstSecondChatAttemptCancelled = CompletableDeferred<Unit>()
        val recoveredSecondChatStarted = CompletableDeferred<Unit>()
        val releaseRecoveredSecondChat = CompletableDeferred<Unit>()
        @Volatile var networkTouched: Boolean = false
            private set

        override suspend fun submitCreate(
            request: CreateJobRequestDto,
        ): FeatureApiResult<GenerationEnvelopeDto> {
            createSubmissions += request
            return FeatureApiResult.Success(generationEnvelope(GenerationJobStatusDto.Queued))
        }

        override suspend fun pollCreate(
            jobId: String,
        ): FeatureApiResult<GenerationEnvelopeDto> {
            check(jobId == CreateJobId)
            return when (createPolls.incrementAndGet()) {
                1 -> {
                    firstCreatePollStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCreatePollCancelled.complete(Unit)
                    }
                }
                2 -> {
                    secondCreatePollStarted.complete(Unit)
                    releaseSecondCreatePoll.await()
                    FeatureApiResult.Success(
                        generationEnvelope(
                            status = GenerationJobStatusDto.Succeeded,
                            result = GeneratedAsset,
                        ),
                    )
                }
                else -> error("Create was polled more than the two scripted process attempts")
            }
        }

        override suspend fun chat(
            request: ChatRequestDto,
        ): FeatureApiResult<ChatResponseDto> {
            chatRequests += request
            val attempt = chatRequests.count { it.requestKey == request.requestKey }
            return when (request.requestKey) {
                FirstChatRequestKey -> when (attempt) {
                    1 -> {
                        firstChatAttemptStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            firstChatAttemptCancelled.complete(Unit)
                        }
                    }
                    2 -> {
                        recoveredFirstChatStarted.complete(Unit)
                        releaseRecoveredFirstChat.await()
                        FeatureApiResult.Success(
                            ChatResponseDto(
                                reply = FirstChatBackendReply,
                                happinessDelta = 30,
                                complimentKey = FirstComplimentKey,
                            ),
                        )
                    }
                    else -> error("First chat escaped durable idempotency")
                }
                SecondChatRequestKey -> {
                    when (attempt) {
                        1 -> {
                            firstSecondChatAttemptStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstSecondChatAttemptCancelled.complete(Unit)
                            }
                        }
                        2 -> {
                            recoveredSecondChatStarted.complete(Unit)
                            releaseRecoveredSecondChat.await()
                            FeatureApiResult.Success(ChatResponseDto(reply = SecondChatBackendReply))
                        }
                        else -> error("Second chat escaped durable idempotency")
                    }
                }
                else -> error("Unexpected chat request key")
            }
        }

        override suspend fun extractMemory(
            request: MemoryExtractionRequestDto,
        ): FeatureApiResult<MemoryExtractionResponseDto> =
            FeatureApiResult.Success(MemoryExtractionResponseDto())

        override suspend fun consolidateMemory(
            request: MemoryConsolidationRequestDto,
        ): FeatureApiResult<MemoryConsolidationResponseDto> =
            FeatureApiResult.Success(MemoryConsolidationResponseDto())

        override fun media(dto: GenerationAssetDto): PetGeneratedMedia? {
            check(dto == GeneratedAsset)
            return PetGeneratedMedia(generatedAt = dto.generatedAt)
        }

        override fun resolveMediaUrl(value: String?): String? = value

        override suspend fun ambient(
            request: AmbientRequestDto,
        ): FeatureApiResult<ChatResponseDto> = unexpected("ambient")

        override suspend fun proactive(
            request: ProactiveRequestDto,
        ): FeatureApiResult<ProactiveResponseDto> = unexpected("proactive")

        override suspend fun simplifyOutfit(
            request: OutfitSimplifyRequestDto,
        ): FeatureApiResult<OutfitSimplifyResponseDto> = unexpected("simplifyOutfit")

        override suspend fun submitOutfit(
            request: OutfitJobRequestDto,
        ): FeatureApiResult<GenerationEnvelopeDto> = unexpected("submitOutfit")

        override suspend fun pollOutfit(
            jobId: String,
        ): FeatureApiResult<GenerationEnvelopeDto> = unexpected("pollOutfit")

        override suspend fun submitTravel(
            request: TravelVideoRequestDto,
        ): FeatureApiResult<TravelVideoDto> = unexpected("submitTravel")

        override suspend fun pollTravel(
            jobId: String,
        ): FeatureApiResult<TravelVideoDto> = unexpected("pollTravel")

        override suspend fun dueStory(
            request: DueStoryRequestDto,
        ): FeatureApiResult<DueStoryResponseDto> = unexpected("dueStory")

        override suspend fun chooseStory(
            storyId: String,
            request: ScheduledStoryChoiceRequestDto,
        ): FeatureApiResult<ScheduledStoryDto> = unexpected("chooseStory")

        override fun story(dto: ScheduledStoryDto): ScheduledStory? = unexpected("story")

        private fun generationEnvelope(
            status: GenerationJobStatusDto,
            result: GenerationAssetDto? = null,
        ) = GenerationEnvelopeDto(
            requestKey = DurableCreateRequestKey,
            petId = PetId,
            job = GenerationJobDto(
                jobId = CreateJobId,
                status = status,
                phase = if (status == GenerationJobStatusDto.Succeeded) {
                    GenerationJobPhaseDto.Completed
                } else {
                    GenerationJobPhaseDto.Queued
                },
                createdAt = FixedIsoTimestamp,
                updatedAt = FixedIsoTimestamp,
                result = result,
            ),
        )

        private fun unexpected(operation: String): Nothing =
            error("Network-only feature unexpectedly called by fixture: $operation")
    }

    private class DeterministicEpochClock(start: Long) {
        private val next = AtomicLong(start)
        fun next(): Long = next.getAndIncrement()
    }

    private class CanonicalUuidSequence(start: Long) {
        private val next = AtomicLong(start)

        fun next(): String = canonical(next.getAndIncrement())

        companion object {
            fun documentFor(requestId: String): String {
                val suffix = requestId.substringAfterLast('-').toLong() + 500_000L
                return canonical(suffix)
            }

            private fun canonical(value: Long): String =
                "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
        }
    }

    private companion object {
        const val OwnerId = "production-create-chat-owner"
        const val PetId = "production-create-chat-pet"
        const val CreateJobId = "create-job-production-fixture"
        const val GeneratedAssetSetId = "production-create-assets-v1"
        const val FixedIsoTimestamp = "2026-07-23T10:00:00Z"
        const val FixedEpochMillis = 1_000_000L
        const val FixedElapsedRealtimeMillis = 5_000L
        const val MonotonicStepNanos = 1_000_000_000L

        const val DurableCreateRequestKey = "10000000-0000-4000-8000-000000000001"
        const val CreateFinishCommandKey = "10000000-0000-4000-8000-000000000002"
        const val FirstChatRequestKey = "10000000-0000-4000-8000-000000000003"
        const val SecondChatRequestKey = "10000000-0000-4000-8000-000000000004"
        const val FirstRuntimeId = "10000000-0000-4000-8000-000000000101"
        const val SecondRuntimeId = "10000000-0000-4000-8000-000000000102"
        const val ThirdRuntimeId = "10000000-0000-4000-8000-000000000103"
        const val FourthRuntimeId = "10000000-0000-4000-8000-000000000104"
        const val FifthRuntimeId = "10000000-0000-4000-8000-000000000105"

        const val FirstChatMessage = "Сергей"
        const val SecondChatMessage = "Читать"
        const val FirstChatBackendReply = "Очень приятно познакомиться!"
        const val SecondChatBackendReply = "Это замечательное увлечение!"
        const val FirstComplimentKey = "first-chat-compliment"

        val GeneratedAsset = GenerationAssetDto(
            assetSetId = GeneratedAssetSetId,
            generatedAt = FixedIsoTimestamp,
            images = emptyMap(),
        )

        val monotonicNanos = AtomicLong(9_000_000_000L)
    }
}
