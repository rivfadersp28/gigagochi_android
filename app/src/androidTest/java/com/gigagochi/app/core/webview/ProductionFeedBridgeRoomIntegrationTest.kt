package com.gigagochi.app.core.webview

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.DashboardCommandReceiptEntity
import com.gigagochi.app.core.database.DashboardFeedApplicationResult
import com.gigagochi.app.core.database.FirstSessionActionReceiptEntity
import com.gigagochi.app.core.database.FirstSessionEntity
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalDashboardFeedPresentation
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.dashboard.BerryReply
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.DashboardReplyAutoAdvanceMillis
import com.gigagochi.app.feature.dashboard.OnboardingBlockAutoAdvanceMillis
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.onboarding.FirstSessionAfterFirstFood
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedy
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedyPortions
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionFeedBridgeRoomIntegrationTest {
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
    fun bridgeFeedMutatesRoomExactlyOnceAndDurableRequestSurvivesRuntimeRestart() = runBlocking {
        val seedRepository = PetLocalRepository(database, nowEpochMillis = { FixedEpochMillis })
        seedRepository.replacePetSnapshot(initialSnapshot())
        database.gigagochiDao().insertFirstSession(
            FirstSessionEntity(
                ownerId = OwnerId,
                petId = PetId,
                stage = FirstSessionStage.AwaitingFirstFood.storageValue,
                selectedDestination = null,
                lastActionKey = null,
                updatedAtEpochMillis = FixedEpochMillis,
            ),
        )

        val firstFeedback = mutableListOf<Int>()
        val first = runtimeHarness(
            repository = PetLocalRepository(database, nowEpochMillis = { FixedEpochMillis }),
            runtimeId = FirstRuntimeId,
            onFeedFeedback = firstFeedback::add,
        )
        try {
            val bootstrapped = bootstrap(
                harness = first,
                documentId = FirstDocumentId,
                requestId = FirstBootstrapRequestId,
            )
            assertEquals("dashboard", bootstrapped.route)
            assertEquals("idle", bootstrapped.dashboardMode)
            assertEquals(40, bootstrapped.pet?.hunger)
            assertEquals(60, bootstrapped.pet?.happiness)
            assertEquals(30, bootstrapped.pet?.energy)
            assertEquals(
                FirstSessionStage.AwaitingFirstFood.storageValue,
                bootstrapped.firstSession?.stage,
            )

            val opened = dispatch(
                harness = first,
                documentId = FirstDocumentId,
                bridgeSessionId = requireNotNull(first.bridgeSessionId),
                requestId = FirstOpenTransportRequestId,
                command = BridgeProductCommand(
                    type = "DASHBOARD_OPEN_MODE",
                    requestKey = FirstOpenCommandKey,
                    expectedSnapshotRevision = bootstrapped.revision,
                    payload = buildJsonObject { put("mode", "feed") },
                ),
            )
            assertEquals("feed", opened.dashboardMode)

            val fed = dispatch(
                harness = first,
                documentId = FirstDocumentId,
                bridgeSessionId = requireNotNull(first.bridgeSessionId),
                requestId = FirstFeedTransportRequestId,
                command = BridgeProductCommand(
                    type = "FEED_CONSUME",
                    requestKey = DurableFeedRequestKey,
                    expectedSnapshotRevision = opened.revision,
                    payload = buildJsonObject { put("food", DashboardFood.BerryBowl.routeValue) },
                ),
            )
            assertEquals(65, fed.pet?.hunger)
            assertEquals(60, fed.pet?.happiness)
            assertEquals(30, fed.pet?.energy)
            assertEquals(
                FirstSessionStage.AwaitingRemedy.storageValue,
                fed.firstSession?.stage,
            )
            assertEquals(listOf(0), firstFeedback)
        } finally {
            first.close()
        }

        val dao = database.gigagochiDao()
        val storedAfterFirstFeed = requireNotNull(dao.getPet(OwnerId, PetId))
        assertEquals(77, storedAfterFirstFeed.experience)
        assertEquals(65, storedAfterFirstFeed.hunger)
        assertEquals(60, storedAfterFirstFeed.happiness)
        assertEquals(30, storedAfterFirstFeed.energy)
        assertEquals(FixedEpochMillis + 1L, storedAfterFirstFeed.hungerTickAtEpochMillis)
        assertEquals(
            FixedEpochMillis + InitialHappinessDecayDelayMillis,
            storedAfterFirstFeed.happinessTickAtEpochMillis,
        )
        assertEquals(
            FixedEpochMillis + InitialEnergyDecayDelayMillis,
            storedAfterFirstFeed.energyTickAtEpochMillis,
        )
        assertEquals(FixedEpochMillis + 1L, storedAfterFirstFeed.updatedAtEpochMillis)

        val firstSessionAfterFeed = requireNotNull(dao.getFirstSession(OwnerId, PetId))
        assertEquals(
            FirstSessionEntity(
                ownerId = OwnerId,
                petId = PetId,
                stage = FirstSessionStage.AwaitingRemedy.storageValue,
                selectedDestination = null,
                lastActionKey = DurableFeedRequestKey,
                updatedAtEpochMillis = FixedEpochMillis + 1L,
            ),
            firstSessionAfterFeed,
        )
        val durableReceipt = requireNotNull(
            dao.getDashboardCommandReceipt(OwnerId, DurableFeedRequestKey),
        )
        assertEquals(
            DashboardCommandReceiptEntity(
                ownerId = OwnerId,
                petId = PetId,
                requestKey = DurableFeedRequestKey,
                commandType = "feed-consume",
                payloadFingerprint = BerryFeedPayloadFingerprint,
                originFirstSessionStage = null,
                food = DashboardFood.BerryBowl.routeValue,
                audioIndex = 0,
                reply = FirstSessionAfterFirstFood,
                explicitPortionsJson = null,
                autoAdvanceDelayMillis = DashboardReplyAutoAdvanceMillis,
                createdAtEpochMillis = FixedEpochMillis + 1L,
            ),
            durableReceipt,
        )
        assertEquals(
            FirstSessionActionReceiptEntity(
                ownerId = OwnerId,
                petId = PetId,
                actionKey = DurableFeedRequestKey,
                actionKind = "food:${DashboardFood.BerryBowl.routeValue}",
                appliedAtEpochMillis = FixedEpochMillis + 1L,
            ),
            requireNotNull(
                dao.getFirstSessionActionReceipt(OwnerId, PetId, DurableFeedRequestKey),
            ),
        )

        val replayFeedback = mutableListOf<Int>()
        val restarted = runtimeHarness(
            repository = PetLocalRepository(database, nowEpochMillis = { FixedEpochMillis }),
            runtimeId = SecondRuntimeId,
            onFeedFeedback = replayFeedback::add,
        )
        try {
            val restartedBootstrap = bootstrap(
                harness = restarted,
                documentId = SecondDocumentId,
                requestId = SecondBootstrapRequestId,
            )
            assertEquals(65, restartedBootstrap.pet?.hunger)
            assertEquals(
                FirstSessionStage.AwaitingRemedy.storageValue,
                restartedBootstrap.firstSession?.stage,
            )

            val reopened = dispatch(
                harness = restarted,
                documentId = SecondDocumentId,
                bridgeSessionId = requireNotNull(restarted.bridgeSessionId),
                requestId = SecondOpenTransportRequestId,
                command = BridgeProductCommand(
                    type = "DASHBOARD_OPEN_MODE",
                    requestKey = SecondOpenCommandKey,
                    expectedSnapshotRevision = restartedBootstrap.revision,
                    payload = buildJsonObject { put("mode", "feed") },
                ),
            )
            val replayed = dispatch(
                harness = restarted,
                documentId = SecondDocumentId,
                bridgeSessionId = requireNotNull(restarted.bridgeSessionId),
                requestId = SecondFeedTransportRequestId,
                command = BridgeProductCommand(
                    type = "FEED_CONSUME",
                    requestKey = DurableFeedRequestKey,
                    expectedSnapshotRevision = reopened.revision,
                    payload = buildJsonObject { put("food", DashboardFood.BerryBowl.routeValue) },
                ),
            )
            assertEquals(65, replayed.pet?.hunger)
            assertEquals(60, replayed.pet?.happiness)
            assertEquals(30, replayed.pet?.energy)
            assertEquals(
                FirstSessionStage.AwaitingRemedy.storageValue,
                replayed.firstSession?.stage,
            )
            assertTrue(replayFeedback.isEmpty())
        } finally {
            restarted.close()
        }

        assertEquals(storedAfterFirstFeed, dao.getPet(OwnerId, PetId))
        assertEquals(firstSessionAfterFeed, dao.getFirstSession(OwnerId, PetId))
        assertEquals(
            durableReceipt,
            dao.getDashboardCommandReceipt(OwnerId, DurableFeedRequestKey),
        )
    }

    private fun runtimeHarness(
        repository: PetLocalRepository,
        runtimeId: String,
        onFeedFeedback: (Int) -> Unit,
    ): RuntimeHarness {
        val mediaRegistry = WebMediaReferenceRegistry(
            StaticMediaUrlPolicy("https://example.test/", allowDebugLoopbackHttp = false),
        )
        val runtime = ProductionWebAppRuntime(
            gateway = RoomFeedGateway(repository),
            appVersion = "production-feed-integration",
            webBundleVersion = BridgeWebBundleVersion,
            mediaRegistry = mediaRegistry,
            runtimeId = runtimeId,
            elapsedRealtimeMillis = { FixedElapsedRealtimeMillis },
            delayMillis = { awaitCancellation() },
            foregroundRefreshDelayMillis = { awaitCancellation() },
            onFeedFeedback = onFeedFeedback,
        )
        return RuntimeHarness(
            runtime = runtime,
            dispatcher = BridgeDispatcher(
                runtime = runtime,
                monotonicClock = BridgeMonotonicClock { FixedMonotonicNanos },
            ),
            mediaRegistry = mediaRegistry,
        )
    }

    private suspend fun bootstrap(
        harness: RuntimeHarness,
        documentId: String,
        requestId: String,
    ): WebAppSnapshot {
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = documentId,
                    bridgeSessionId = null,
                    requestId = requestId,
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
        assertTrue(response.ok)
        assertEquals(requestId, response.requestId)
        harness.bridgeSessionId = requireNotNull(response.bridgeSessionId)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private suspend fun dispatch(
        harness: RuntimeHarness,
        documentId: String,
        bridgeSessionId: String,
        requestId: String,
        command: BridgeProductCommand,
    ): WebAppSnapshot {
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = documentId,
                    bridgeSessionId = bridgeSessionId,
                    requestId = requestId,
                    method = "dispatch",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        BridgeProductCommand.serializer(),
                        command,
                    ),
                ),
            ),
        )
        assertTrue("bridge error=${response.error}", response.ok)
        assertEquals(requestId, response.requestId)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private fun bridgeRequest(
        documentId: String,
        bridgeSessionId: String?,
        requestId: String,
        method: String,
        payload: kotlinx.serialization.json.JsonElement,
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

    private fun initialSnapshot() = OwnedPetSnapshot(
        ownerId = OwnerId,
        pet = PetDashboardState(
            petId = PetId,
            assetSetId = AssetSetId,
            description = "Ледяной дракон",
            name = "Тото",
            stage = "baby",
            stageLabel = "Уровень: Малыш",
            mood = "idle",
            experience = 77,
            hunger = 40,
            happiness = 60,
            energy = 30,
            message = "Я немного проголодался",
        ),
        updatedAtEpochMillis = FixedEpochMillis,
    )

    private class RuntimeHarness(
        val runtime: ProductionWebAppRuntime,
        val dispatcher: BridgeDispatcher,
        private val mediaRegistry: WebMediaReferenceRegistry,
    ) : AutoCloseable {
        var bridgeSessionId: String? = null

        override fun close() {
            runtime.close()
            mediaRegistry.close()
        }
    }

    /**
     * Test composition root for the production reducer. Only authentication/network wiring is
     * replaced; bootstrap and feeding are backed by the real lifecycle and Room repository.
     */
    private class RoomFeedGateway(
        private val repository: PetLocalRepository,
    ) : WebAppDataGateway {
        override suspend fun bootstrap(): WebRuntimeBootstrapResult =
            when (val destination = AccountPetLifecycle(repository).startup(OwnerId, PetId)) {
                is AccountStartupDestination.Dashboard -> WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(destination),
                )
                AccountStartupDestination.Failure -> WebRuntimeBootstrapResult.LocalDataError
                is AccountStartupDestination.Create -> error("Seeded production pet disappeared")
            }

        override suspend fun applyFeed(
            requestKey: String,
            food: DashboardFood,
            audioIndex: Int,
            expectedPet: PetDashboardState,
        ): WebFeedMutationResult {
            val defaultPresentation = LocalDashboardFeedPresentation(
                reply = when (food) {
                    DashboardFood.BerryBowl -> BerryReply
                    DashboardFood.LeafCrunch -> com.gigagochi.app.feature.dashboard.LeafReply
                },
                autoAdvanceDelayMillis = DashboardReplyAutoAdvanceMillis,
            )
            return when (
                val result = repository.applyDashboardFeed(
                    ownerId = OwnerId,
                    petId = expectedPet.petId,
                    expectedAssetSetId = expectedPet.assetSetId,
                    requestKey = requestKey,
                    food = food.routeValue,
                    audioIndex = audioIndex,
                    defaultPresentation = defaultPresentation,
                    firstFoodPresentation = LocalDashboardFeedPresentation(
                        reply = FirstSessionAfterFirstFood,
                        autoAdvanceDelayMillis = DashboardReplyAutoAdvanceMillis,
                    ),
                    remedyPresentation = LocalDashboardFeedPresentation(
                        reply = FirstSessionAfterRemedy,
                        explicitPortions = FirstSessionAfterRemedyPortions,
                        autoAdvanceDelayMillis = OnboardingBlockAutoAdvanceMillis,
                    ),
                )
            ) {
                is DashboardFeedApplicationResult.Applied -> WebFeedMutationResult.Applied(
                    pet = result.pet,
                    firstSession = result.firstSession,
                    receipt = result.receipt,
                    newlyApplied = result.newlyApplied,
                )
                DashboardFeedApplicationResult.Missing -> WebFeedMutationResult.Missing
                DashboardFeedApplicationResult.Conflict -> WebFeedMutationResult.Conflict
                DashboardFeedApplicationResult.WrongStage -> WebFeedMutationResult.WrongStage
            }
        }

        override suspend fun persistCreate(state: CreatePetState): WebCreatePersistenceResult =
            unexpected("persistCreate")

        override suspend fun generateCreate(
            request: PendingPetGeneration,
        ): PetGenerationExecutionResult = unexpected("generateCreate")

        override suspend fun finalizeCreate(
            state: CreatePetState,
            foregroundHandled: Boolean,
        ): WebCreateFinalizationResult = unexpected("finalizeCreate")

        override suspend fun applyPetTap(
            expectedPet: PetDashboardState,
        ): WebPetTapMutationResult = unexpected("applyPetTap")

        override suspend fun reserveChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            originFirstSessionStage: FirstSessionStage?,
            queueAnchorRequestKey: String?,
            replacingQueuedRequestKey: String?,
        ): WebChatReservationResult = unexpected("reserveChat")

        override suspend fun executeChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            expectedFirstSessionStage: FirstSessionStage?,
        ): WebChatExecutionResult = unexpected("executeChat")

        override suspend fun acknowledgeChat(requestKey: String): Boolean =
            unexpected("acknowledgeChat")

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

        private fun unexpected(operation: String): Nothing =
            error("Unexpected gateway operation in production feed fixture: $operation")
    }

    private companion object {
        const val OwnerId = "production-feed-owner"
        const val PetId = "production-feed-pet"
        const val AssetSetId = "production-feed-assets-v1"

        const val FixedEpochMillis = 1_000_000L
        const val FixedElapsedRealtimeMillis = 5_000L
        const val FixedMonotonicNanos = 9_000_000_000L
        const val InitialHappinessDecayDelayMillis = 2L * 60L * 60L * 1_000L
        const val InitialEnergyDecayDelayMillis = 4L * 60L * 60L * 1_000L

        const val FirstDocumentId = "00000000-0000-4000-8000-000000000101"
        const val SecondDocumentId = "00000000-0000-4000-8000-000000000102"
        const val FirstBootstrapRequestId = "00000000-0000-4000-8000-000000000201"
        const val FirstOpenTransportRequestId = "00000000-0000-4000-8000-000000000202"
        const val FirstFeedTransportRequestId = "00000000-0000-4000-8000-000000000203"
        const val SecondBootstrapRequestId = "00000000-0000-4000-8000-000000000204"
        const val SecondOpenTransportRequestId = "00000000-0000-4000-8000-000000000205"
        const val SecondFeedTransportRequestId = "00000000-0000-4000-8000-000000000206"

        const val FirstOpenCommandKey = "00000000-0000-4000-8000-000000000301"
        const val SecondOpenCommandKey = "00000000-0000-4000-8000-000000000302"
        const val DurableFeedRequestKey = "00000000-0000-4000-8000-000000000303"
        const val FirstRuntimeId = "00000000-0000-4000-8000-000000000401"
        const val SecondRuntimeId = "00000000-0000-4000-8000-000000000402"

        const val BerryFeedPayloadFingerprint =
            "842abcbd24daf17cf73a1c8c239c8e05a997437df458ac9f959c99157a81a877"
    }
}
