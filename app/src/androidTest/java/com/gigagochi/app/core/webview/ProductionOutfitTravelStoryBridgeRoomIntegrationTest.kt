package com.gigagochi.app.core.webview

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.DashboardOutfitReservationResult
import com.gigagochi.app.core.database.DashboardTravelReservationResult
import com.gigagochi.app.core.database.FirstSessionEntity
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalNotificationKind
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.database.PetStatFullDecayMillis
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.network.DueStoryRequestDto
import com.gigagochi.app.core.network.DueStoryResponseDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.GenerationAssetDto
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobDto
import com.gigagochi.app.core.network.GenerationJobPhaseDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.OutfitJobRequestDto
import com.gigagochi.app.core.network.OutfitSimplifyRequestDto
import com.gigagochi.app.core.network.OutfitSimplifyResponseDto
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.ScheduledStoryResultDto
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.network.TestAndroidFeatureService
import com.gigagochi.app.core.network.TravelVideoDto
import com.gigagochi.app.core.network.TravelVideoRequestDto
import com.gigagochi.app.core.network.TravelVideoStatusDto
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.DashboardOutcomeApplicationCoordinator
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.RealDashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardTravelAdapter
import com.gigagochi.app.feature.travel.OnboardingBatCorrectChoice
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
 * Production reducer → bridge → real Room repository evidence for the durable mechanics that are
 * easiest to accidentally replace with an in-memory Web fixture.
 *
 * Authentication and HTTP are the only substituted boundaries. Outfit/travel use the production
 * feature adapters and scheduled/onboarding stories use [AndroidEventStoryGateway].
 */
@RunWith(AndroidJUnit4::class)
class ProductionOutfitTravelStoryBridgeRoomIntegrationTest {
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
    fun outfitChargeAndAttachedPendingSurviveRestartThenReplayCannotDebitOrDispatchAgain() =
        runBlocking {
            val clock = MutableEpochClock(FixedEpochMillis)
            val repository = PetLocalRepository(database, clock::now)
            repository.replacePetSnapshot(initialPet(experience = 500))
            val service = ScriptedFeatureService(holdFirstOutfitPoll = true)
            val ids = CanonicalUuidSequence(1_000L)

            val first = runtimeHarness(repository, service, clock, ids.next())
            val initial = bootstrap(first, ids.next())
            val attachedEvent = async(start = CoroutineStart.UNDISPATCHED) {
                first.awaitSnapshot {
                    it.pending.outfit?.requestKey == OutfitRequestKey &&
                        it.pending.outfit?.status == "attached"
                }
            }
            val accepted = dispatch(
                first,
                ids.next(),
                BridgeProductCommand(
                    type = "OUTFIT_SUBMIT",
                    requestKey = OutfitRequestKey,
                    expectedSnapshotRevision = initial.revision,
                    payload = buildJsonObject { put("prompt", OutfitPrompt) },
                ),
            )
            assertEquals(300, accepted.pet?.experience)
            assertEquals(OutfitRequestKey, accepted.pending.outfit?.requestKey)
            assertEquals("pending", accepted.pending.outfit?.status)
            val attached = attachedEvent.await()
            assertEquals(300, attached.pet?.experience)
            assertEquals("attached", attached.pending.outfit?.status)
            withTimeout(5_000L) { service.firstOutfitPollStarted.await() }

            val storedAttached = repository.getPendingOutfits(OwnerId).single()
            assertEquals(OutfitRequestKey, storedAttached.requestKey)
            assertEquals(OutfitJobId, storedAttached.backendJobId)
            assertEquals(PendingBackendState.Attached, storedAttached.backendState)
            assertEquals(300, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
            assertEquals(1, service.outfitSimplifyCalls.get())
            assertEquals(1, service.outfitSubmitCalls.get())

            first.close()
            withTimeout(5_000L) { service.firstOutfitPollCancelled.await() }

            val restarted = runtimeHarness(repository, service, clock, ids.next())
            try {
                val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    restarted.awaitSnapshot {
                        it.pending.outfit == null &&
                            it.pet?.experience == 300 &&
                            service.outfitPollCalls.get() >= 2
                    }
                }
                val recovered = bootstrap(restarted, ids.next())
                assertEquals(300, recovered.pet?.experience)
                assertEquals(OutfitRequestKey, recovered.pending.outfit?.requestKey)
                assertEquals("attached", recovered.pending.outfit?.status)

                val completed = completionEvent.await()
                assertNull(completed.pending.outfit)
                assertEquals(300, completed.pet?.experience)
                assertEquals(NewOutfitAssetSetId, repository.getPetSnapshot(OwnerId, PetId)?.pet?.assetSetId)
                assertTrue(repository.getPendingOutfits(OwnerId).isEmpty())
                assertTrue(repository.getOutfitMediaOutcomes(OwnerId, PetId).isEmpty())
                val appliedReceipt = requireNotNull(
                    database.gigagochiDao().getAppliedOutfitReceipt(OwnerId, OutfitRequestKey),
                )
                assertEquals(PetId, appliedReceipt.petId)
                assertEquals(NewOutfitAssetSetId, appliedReceipt.assetSetId)
                assertEquals("лунный плащ", appliedReceipt.displayItem)

                val callsBeforeReplay = service.outfitCallVector()
                val replayed = dispatch(
                    restarted,
                    ids.next(),
                    BridgeProductCommand(
                        type = "OUTFIT_SUBMIT",
                        requestKey = OutfitRequestKey,
                        expectedSnapshotRevision = completed.revision,
                        payload = buildJsonObject { put("prompt", OutfitPrompt) },
                    ),
                )
                assertEquals(300, replayed.pet?.experience)
                assertNull(replayed.pending.outfit)
                assertEquals(callsBeforeReplay, service.outfitCallVector())
                assertEquals(
                    appliedReceipt,
                    database.gigagochiDao().getAppliedOutfitReceipt(
                        OwnerId,
                        OutfitRequestKey,
                    ),
                )
                assertEquals(
                    listOf(LocalNotificationKind.OutfitReady),
                    repository.getUnnotifiedNotifications(OwnerId, PetId).map { it.kind },
                )
                assertEquals(
                    1,
                    repository.recentCharacterExperiences(OwnerId, PetId)
                        .count { it.kind == "character_outfit" },
                )
            } finally {
                restarted.close()
            }
        }

    @Test
    fun travelAttachedPendingSurvivesRestartThenAssetHistoryOutboxAndReplayStaySingle() =
        runBlocking {
            val clock = MutableEpochClock(FixedEpochMillis)
            val repository = PetLocalRepository(database, clock::now)
            repository.replacePetSnapshot(initialPet(experience = 500))
            val service = ScriptedFeatureService(holdFirstTravelPoll = true)
            val ids = CanonicalUuidSequence(2_000L)

            val first = runtimeHarness(repository, service, clock, ids.next())
            val initial = bootstrap(first, ids.next())
            val attachedEvent = async(start = CoroutineStart.UNDISPATCHED) {
                first.awaitSnapshot {
                    it.pending.travel?.requestKey == TravelRequestKey &&
                        it.pending.travel?.status == "attached"
                }
            }
            val accepted = dispatch(
                first,
                ids.next(),
                BridgeProductCommand(
                    type = "TRAVEL_SUBMIT",
                    requestKey = TravelRequestKey,
                    expectedSnapshotRevision = initial.revision,
                    payload = buildJsonObject { put("prompt", TravelPrompt) },
                ),
            )
            assertEquals(500, accepted.pet?.experience)
            assertEquals("pending", accepted.pending.travel?.status)
            val attached = attachedEvent.await()
            assertEquals("attached", attached.pending.travel?.status)
            withTimeout(5_000L) { service.firstTravelPollStarted.await() }

            val storedAttached = repository.getPendingTravels(OwnerId).single()
            assertEquals(TravelJobId, storedAttached.backendJobId)
            assertEquals(PendingBackendState.Attached, storedAttached.backendState)
            assertEquals(1, service.travelSubmitCalls.get())

            first.close()
            withTimeout(5_000L) { service.firstTravelPollCancelled.await() }

            val restarted = runtimeHarness(repository, service, clock, ids.next())
            try {
                val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    restarted.awaitSnapshot {
                        it.pending.travel == null &&
                            service.travelPollCalls.get() >= 2
                    }
                }
                val recovered = bootstrap(restarted, ids.next())
                assertEquals(TravelRequestKey, recovered.pending.travel?.requestKey)
                assertEquals("attached", recovered.pending.travel?.status)

                val completed = completionEvent.await()
                assertNull(completed.pending.travel)
                assertEquals(500, completed.pet?.experience)
                val persistedAsset = repository.getTravelVideoAssets(OwnerId, PetId).single()
                assertEquals(TravelRequestKey, persistedAsset.requestKey)
                assertNotNull(persistedAsset.consumedAtEpochMillis)
                assertTrue(repository.getPendingTravels(OwnerId).isEmpty())

                val callsBeforeReplay = service.travelCallVector()
                val replayed = dispatch(
                    restarted,
                    ids.next(),
                    BridgeProductCommand(
                        type = "TRAVEL_SUBMIT",
                        requestKey = TravelRequestKey,
                        expectedSnapshotRevision = completed.revision,
                        payload = buildJsonObject { put("prompt", TravelPrompt) },
                    ),
                )
                assertNull(replayed.pending.travel)
                assertEquals(callsBeforeReplay, service.travelCallVector())

                val events = dispatch(
                    restarted,
                    ids.next(),
                    command("NAVIGATE", ids.next(), replayed) { put("route", "events") },
                )
                assertEquals(
                    listOf(TravelRequestKey),
                    events.events?.travelVideos?.map { it.requestKey },
                )
                assertEquals(
                    listOf(TravelRequestKey),
                    repository.getTravelVideoAssets(OwnerId, PetId).map { it.requestKey },
                )
                assertEquals(
                    listOf(LocalNotificationKind.TravelReady),
                    repository.getUnnotifiedNotifications(OwnerId, PetId).map { it.kind },
                )
                assertEquals(
                    1,
                    repository.recentCharacterExperiences(OwnerId, PetId)
                        .count { it.kind == "character_travel" },
                )
            } finally {
                restarted.close()
            }
        }

    @Test
    fun scheduledAndOnboardingStoryWinnersSurviveRestartAndRewardOutboxExactlyOnce() =
        runBlocking {
            val clock = MutableEpochClock(FixedEpochMillis)
            val repository = PetLocalRepository(database, clock::now)
            repository.replacePetSnapshot(initialPet(experience = 100))
            val service = ScriptedFeatureService(
                dueStory = ScheduledStory,
                holdFirstStoryChoice = true,
            )
            val ids = CanonicalUuidSequence(3_000L)

            val first = runtimeHarness(repository, service, clock, ids.next())
            val dueEvent = async(start = CoroutineStart.UNDISPATCHED) {
                first.awaitSnapshot {
                    it.events?.stories?.singleOrNull()?.story?.storyId == ScheduledStoryId
                }
            }
            bootstrap(first, ids.next())
            var snapshot = dueEvent.await()
            awaitCalls(service.dueStoryCalls, 1)
            assertEquals(1, snapshot.events?.badgeCount)
            assertEquals(
                listOf(LocalNotificationKind.ScheduledStory),
                repository.getUnnotifiedNotifications(OwnerId, PetId).map { it.kind },
            )

            snapshot = dispatch(
                first,
                ids.next(),
                command("NAVIGATE", ids.next(), snapshot) { put("route", "events") },
            )
            snapshot = dispatch(
                first,
                ids.next(),
                command("STORY_OPEN", ids.next(), snapshot) { put("storyId", ScheduledStoryId) },
            )
            val pending = dispatch(
                first,
                ids.next(),
                command("STORY_CHOOSE", ScheduledChoiceRequestKey, snapshot) {
                    put("storyId", ScheduledStoryId)
                    put("choice", ScheduledChoice)
                },
            )
            assertEquals(WebDurableStoryPhase.ChoicePending, pending.story?.phase)
            assertEquals(ScheduledChoiceRequestKey, pending.story?.durableRequestKey)
            withTimeout(5_000L) { service.firstStoryChoiceStarted.await() }
            assertEquals(100, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
            assertEquals(ScheduledChoiceRequestKey, repository.getScheduledStory(OwnerId, ScheduledStoryId)?.choiceRequestKey)

            first.close()
            withTimeout(5_000L) { service.firstStoryChoiceCancelled.await() }

            val restarted = runtimeHarness(repository, service, clock, ids.next())
            val recoveredBootstrap = bootstrap(restarted, ids.next())
            awaitCalls(service.dueStoryCalls, 2)
            snapshot = dispatch(
                restarted,
                ids.next(),
                command("NAVIGATE", ids.next(), recoveredBootstrap) { put("route", "events") },
            )
            snapshot = dispatch(
                restarted,
                ids.next(),
                command("STORY_OPEN", ids.next(), snapshot) { put("storyId", ScheduledStoryId) },
            )
            assertEquals(WebDurableStoryPhase.Retryable, snapshot.story?.phase)
            assertEquals(ScheduledChoiceRequestKey, snapshot.story?.durableRequestKey)
            assertEquals(ScheduledChoice, snapshot.story?.pendingChoice)

            val resultEvent = async(start = CoroutineStart.UNDISPATCHED) {
                restarted.awaitSnapshot {
                    it.story?.story?.storyId == ScheduledStoryId &&
                        it.story?.phase == WebDurableStoryPhase.Result
                }
            }
            val retrying = dispatch(
                restarted,
                ids.next(),
                command("STORY_RETRY", ids.next(), snapshot) { put("storyId", ScheduledStoryId) },
            )
            assertEquals(WebDurableStoryPhase.ChoicePending, retrying.story?.phase)
            val result = resultEvent.await()
            assertEquals(120, result.pet?.experience)
            assertEquals(ScheduledChoiceRequestKey, result.story?.durableRequestKey)
            assertEquals(
                listOf(ScheduledChoiceRequestKey, ScheduledChoiceRequestKey),
                service.storyChoiceRequestKeys,
            )
            assertEquals(1, repository.getStoryReceipts(OwnerId).size)
            assertEquals(
                listOf(LocalNotificationKind.ScheduledStory),
                repository.getUnnotifiedNotifications(OwnerId, PetId).map { it.kind },
            )
            restarted.close()

            val replayRuntime = runtimeHarness(repository, service, clock, ids.next())
            try {
                var replaySnapshot = bootstrap(replayRuntime, ids.next())
                awaitCalls(service.dueStoryCalls, 3)
                replaySnapshot = dispatch(
                    replayRuntime,
                    ids.next(),
                    command("NAVIGATE", ids.next(), replaySnapshot) { put("route", "events") },
                )
                replaySnapshot = dispatch(
                    replayRuntime,
                    ids.next(),
                    command("STORY_OPEN", ids.next(), replaySnapshot) {
                        put("storyId", ScheduledStoryId)
                    },
                )
                assertEquals(WebDurableStoryPhase.Result, replaySnapshot.story?.phase)
                val failed = dispatchResponse(
                    replayRuntime,
                    ids.next(),
                    command("STORY_CHOOSE", ScheduledChoiceRequestKey, replaySnapshot) {
                        put("storyId", ScheduledStoryId)
                        put("choice", ScheduledChoice)
                    },
                )
                assertFalse(failed.ok)
                assertEquals("WRONG_STAGE", failed.error?.code)
                assertEquals(120, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
                assertEquals(2, service.storyChoiceRequestKeys.size)
                assertEquals(1, repository.getStoryReceipts(OwnerId).size)
            } finally {
                replayRuntime.close()
            }

            database.gigagochiDao().upsertFirstSession(
                FirstSessionEntity(
                    ownerId = OwnerId,
                    petId = PetId,
                    stage = FirstSessionStage.AwaitingTravel.storageValue,
                    selectedDestination = null,
                    lastActionKey = null,
                    updatedAtEpochMillis = clock.now(),
                ),
            )
            val onboarding = runtimeHarness(repository, service, clock, ids.next())
            var onboardingSnapshot = settleFirstSessionReply(
                onboarding,
                ids,
                bootstrap(onboarding, ids.next()),
            )
            awaitCalls(service.dueStoryCalls, 4)
            onboardingSnapshot = dispatch(
                onboarding,
                ids.next(),
                command("NAVIGATE", ids.next(), onboardingSnapshot) { put("route", "travel") },
            )
            assertEquals(WebOnboardingBatStoryId, onboardingSnapshot.story?.story?.storyId)
            val onboardingResult = dispatch(
                onboarding,
                ids.next(),
                command("STORY_CHOOSE", OnboardingChoiceRequestKey, onboardingSnapshot) {
                    put("storyId", WebOnboardingBatStoryId)
                    put("choice", OnboardingBatCorrectChoice)
                },
            )
            assertEquals(WebDurableStoryPhase.Result, onboardingResult.story?.phase)
            assertEquals(320, onboardingResult.pet?.experience)
            onboarding.close()

            val onboardingReplay = runtimeHarness(repository, service, clock, ids.next())
            try {
                var restored = settleFirstSessionReply(
                    onboardingReplay,
                    ids,
                    bootstrap(onboardingReplay, ids.next()),
                )
                awaitCalls(service.dueStoryCalls, 5)
                restored = dispatch(
                    onboardingReplay,
                    ids.next(),
                    command("NAVIGATE", ids.next(), restored) { put("route", "travel") },
                )
                assertEquals(WebDurableStoryPhase.Result, restored.story?.phase)
                val replayFailure = dispatchResponse(
                    onboardingReplay,
                    ids.next(),
                    command("STORY_CHOOSE", OnboardingChoiceRequestKey, restored) {
                        put("storyId", WebOnboardingBatStoryId)
                        put("choice", OnboardingBatCorrectChoice)
                    },
                )
                assertFalse(replayFailure.ok)
                assertEquals("WRONG_STAGE", replayFailure.error?.code)
                assertEquals(320, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
                assertEquals(2, repository.getStoryReceipts(OwnerId).size)

                val finished = dispatch(
                    onboardingReplay,
                    ids.next(),
                    command("STORY_FINISH", ids.next(), restored) {
                        put("storyId", WebOnboardingBatStoryId)
                    },
                )
                assertEquals("dashboard", finished.route)
                assertEquals(
                    FirstSessionStage.AwaitingCompletionMessage.storageValue,
                    finished.firstSession?.stage,
                )
                assertEquals(320, finished.pet?.experience)
                assertEquals(
                    listOf(LocalNotificationKind.ScheduledStory),
                    repository.getUnnotifiedNotifications(OwnerId, PetId).map { it.kind },
                )
            } finally {
                onboardingReplay.close()
            }
        }

    @Test
    fun foregroundRefreshDecaysFromThreeIndependentPersistedClocksAndRestartKeepsResult() =
        runBlocking {
            val startedAt = 10_000_000L
            val quarter = startedAt + PetStatFullDecayMillis / 4L
            val third = startedAt + PetStatFullDecayMillis / 3L
            val halfway = startedAt + PetStatFullDecayMillis / 2L
            val clock = MutableEpochClock(startedAt)
            val repository = PetLocalRepository(database, clock::now)
            repository.replacePetSnapshot(
                initialPet(
                    experience = 500,
                    hunger = 100,
                    happiness = 100,
                    energy = 100,
                    updatedAt = startedAt,
                ),
            )
            // Reset energy's product-specific initial 4h grace period without touching the other
            // clocks. Subsequent writes independently reset hunger and then happiness.
            repository.replacePetSnapshot(
                initialPet(
                    experience = 500,
                    hunger = 100,
                    happiness = 100,
                    energy = 99,
                    updatedAt = startedAt,
                ),
            )
            clock.set(quarter)
            repository.replacePetSnapshot(
                initialPet(
                    experience = 500,
                    hunger = 90,
                    happiness = 100,
                    energy = 99,
                    updatedAt = quarter,
                ),
            )
            clock.set(third)
            repository.replacePetSnapshot(
                initialPet(
                    experience = 500,
                    hunger = 90,
                    happiness = 90,
                    energy = 99,
                    updatedAt = third,
                ),
            )

            val service = ScriptedFeatureService()
            val ids = CanonicalUuidSequence(4_000L)
            val first = runtimeHarness(repository, service, clock, ids.next())
            val initial = bootstrap(first, ids.next())
            // Bootstrap itself is a real Room recovery and therefore consumes the elapsed whole
            // points since each independent tick before the foreground refresh below.
            assertEquals(82, initial.pet?.hunger)
            assertEquals(90, initial.pet?.happiness)
            assertEquals(66, initial.pet?.energy)
            val afterBootstrap = requireNotNull(database.gigagochiDao().getPet(OwnerId, PetId))
            assertEquals(
                quarter + 8L * PetStatFullDecayMillis / 100L,
                afterBootstrap.hungerTickAtEpochMillis,
            )
            assertEquals(third, afterBootstrap.happinessTickAtEpochMillis)
            assertEquals(
                startedAt + 33L * PetStatFullDecayMillis / 100L,
                afterBootstrap.energyTickAtEpochMillis,
            )

            clock.set(halfway)
            val decayEvent = async(start = CoroutineStart.UNDISPATCHED) {
                first.awaitSnapshot {
                    it.pet?.hunger == 65 &&
                        it.pet.happiness == 74 &&
                        it.pet.energy == 49
                }
            }
            first.runtime.onForeground()
            val decayed = decayEvent.await()
            first.runtime.onBackground()
            assertEquals("idle", decayed.pet?.let { repository.getPetSnapshot(OwnerId, PetId)?.pet?.mood })

            val stored = requireNotNull(database.gigagochiDao().getPet(OwnerId, PetId))
            assertEquals(65, stored.hunger)
            assertEquals(74, stored.happiness)
            assertEquals(49, stored.energy)
            assertEquals(halfway, stored.hungerTickAtEpochMillis)
            assertEquals(
                third + 16L * PetStatFullDecayMillis / 100L,
                stored.happinessTickAtEpochMillis,
            )
            assertEquals(halfway, stored.energyTickAtEpochMillis)
            first.close()

            val restarted = runtimeHarness(repository, service, clock, ids.next())
            try {
                val recovered = bootstrap(restarted, ids.next())
                assertEquals(65, recovered.pet?.hunger)
                assertEquals(74, recovered.pet?.happiness)
                assertEquals(49, recovered.pet?.energy)
                val afterRestart = requireNotNull(database.gigagochiDao().getPet(OwnerId, PetId))
                assertEquals(stored.hungerTickAtEpochMillis, afterRestart.hungerTickAtEpochMillis)
                assertEquals(stored.happinessTickAtEpochMillis, afterRestart.happinessTickAtEpochMillis)
                assertEquals(stored.energyTickAtEpochMillis, afterRestart.energyTickAtEpochMillis)
            } finally {
                restarted.close()
            }
        }

    private fun runtimeHarness(
        repository: PetLocalRepository,
        service: ScriptedFeatureService,
        clock: MutableEpochClock,
        runtimeId: String,
    ): RuntimeHarness {
        val mediaRegistry = WebMediaReferenceRegistry(
            StaticMediaUrlPolicy(MediaBaseUrl, allowDebugLoopbackHttp = false),
        )
        val runtime = ProductionWebAppRuntime(
            gateway = RoomFeatureGateway(repository, service, clock),
            appVersion = "production-outfit-travel-story-integration",
            webBundleVersion = BridgeWebBundleVersion,
            mediaRegistry = mediaRegistry,
            runtimeId = runtimeId,
            elapsedRealtimeMillis = { FixedElapsedRealtimeMillis },
            delayMillis = { awaitCancellation() },
            foregroundRefreshDelayMillis = { awaitCancellation() },
        )
        return RuntimeHarness(
            runtime = runtime,
            dispatcher = BridgeDispatcher(
                runtime = runtime,
                monotonicClock = BridgeMonotonicClock {
                    MonotonicNanos.addAndGet(1_000_000_000L)
                },
            ),
            documentId = runtimeId,
            mediaRegistry = mediaRegistry,
        )
    }

    private suspend fun bootstrap(
        harness: RuntimeHarness,
        requestId: String,
    ): WebAppSnapshot {
        val response = bridgeResponse(
            harness.dispatcher.handle(
                bridgeRequest(
                    documentId = harness.documentId,
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
        assertTrue("bootstrap error=${response.error}", response.ok)
        harness.bridgeSessionId = requireNotNull(response.bridgeSessionId)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private suspend fun dispatch(
        harness: RuntimeHarness,
        requestId: String,
        command: BridgeProductCommand,
    ): WebAppSnapshot {
        val response = dispatchResponse(harness, requestId, command)
        assertTrue("bridge error=${response.error}", response.ok)
        return BridgeCodec.json.decodeFromJsonElement(WebAppSnapshot.serializer(), response.result)
    }

    private suspend fun dispatchResponse(
        harness: RuntimeHarness,
        requestId: String,
        command: BridgeProductCommand,
    ): BridgeResponseEnvelope = bridgeResponse(
        harness.dispatcher.handle(
            bridgeRequest(
                documentId = harness.documentId,
                bridgeSessionId = requireNotNull(harness.bridgeSessionId),
                requestId = requestId,
                method = "dispatch",
                payload = BridgeCodec.json.encodeToJsonElement(
                    BridgeProductCommand.serializer(),
                    command,
                ),
            ),
        ),
    )

    private fun command(
        type: String,
        requestKey: String,
        snapshot: WebAppSnapshot,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = BridgeProductCommand(
        type = type,
        requestKey = requestKey,
        expectedSnapshotRevision = snapshot.revision,
        payload = buildJsonObject(payload),
    )

    private suspend fun settleFirstSessionReply(
        harness: RuntimeHarness,
        ids: CanonicalUuidSequence,
        initial: WebAppSnapshot,
    ): WebAppSnapshot {
        var snapshot = initial
        while (snapshot.dashboard?.reply?.source == "firstSession") {
            val reply = requireNotNull(snapshot.dashboard?.reply)
            snapshot = dispatch(
                harness,
                ids.next(),
                command(
                    type = if (reply.hasNextPortion) "REPLY_ADVANCE" else "REPLY_COMPLETE",
                    requestKey = ids.next(),
                    snapshot = snapshot,
                ) { put("requestKey", reply.requestKey) },
            )
        }
        return snapshot
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

    private suspend fun awaitCalls(counter: AtomicInteger, expected: Int) {
        withTimeout(5_000L) {
            while (counter.get() < expected) yield()
        }
    }

    private fun initialPet(
        experience: Int,
        hunger: Int = 80,
        happiness: Int = 80,
        energy: Int = 80,
        updatedAt: Long = FixedEpochMillis,
    ) = OwnedPetSnapshot(
        ownerId = OwnerId,
        pet = PetDashboardState(
            petId = PetId,
            assetSetId = InitialAssetSetId,
            description = "Ледяной дракон",
            name = "Тото",
            stage = "baby",
            stageLabel = "Уровень: Малыш",
            mood = "idle",
            experience = experience,
            hunger = hunger,
            happiness = happiness,
            energy = energy,
            message = "Привет",
            generatedMedia = generatedMedia("initial"),
        ),
        updatedAtEpochMillis = updatedAt,
    )

    private class RuntimeHarness(
        val runtime: ProductionWebAppRuntime,
        val dispatcher: BridgeDispatcher,
        val documentId: String,
        private val mediaRegistry: WebMediaReferenceRegistry,
    ) : AutoCloseable {
        var bridgeSessionId: String? = null

        suspend fun awaitSnapshot(predicate: (WebAppSnapshot) -> Boolean): WebAppSnapshot =
            withTimeout(5_000L) {
                runtime.events.mapNotNull { event ->
                    if (event.type == "stateChanged") {
                        BridgeCodec.json.decodeFromJsonElement(
                            WebAppSnapshot.serializer(),
                            event.payload,
                        )
                    } else {
                        null
                    }
                }.first(predicate)
            }

        override fun close() {
            runtime.close()
            mediaRegistry.close()
        }
    }

    /** Test-only production composition root: real Room lifecycle/adapters, scripted HTTP boundary. */
    private class RoomFeatureGateway(
        private val repository: PetLocalRepository,
        private val service: ScriptedFeatureService,
        private val clock: MutableEpochClock,
    ) : WebAppDataGateway {
        override suspend fun bootstrap(): WebRuntimeBootstrapResult =
            when (val destination = AccountPetLifecycle(repository).startup(OwnerId, PetId)) {
                is AccountStartupDestination.Dashboard -> {
                    val recovery = repository.loadOwnerRecovery(OwnerId)
                    WebRuntimeBootstrapResult.Ready(
                        WebRuntimeDestination.Dashboard(
                            destination = destination,
                            operations = recovery.operationState(destination.pet.petId),
                        ),
                    )
                }
                AccountStartupDestination.Failure -> WebRuntimeBootstrapResult.LocalDataError
                is AccountStartupDestination.Create -> error("Seeded production pet disappeared")
            }

        override suspend fun reserveOutfit(
            request: PendingOutfitRequest,
            expectedPet: PetDashboardState,
        ): WebOutfitReservationResult = when (
            val result = repository.reserveDashboardOutfit(
                ownerId = OwnerId,
                petId = expectedPet.petId,
                expectedAssetSetId = expectedPet.assetSetId,
                requestKey = request.requestKey,
                prompt = request.prompt,
            )
        ) {
            is DashboardOutfitReservationResult.Accepted -> WebOutfitReservationResult.Accepted(
                result.pet,
                result.request,
                result.newlyAccepted,
            )
            is DashboardOutfitReservationResult.Finished ->
                WebOutfitReservationResult.Finished(result.pet)
            is DashboardOutfitReservationResult.Busy ->
                WebOutfitReservationResult.Busy(result.pet, result.request)
            is DashboardOutfitReservationResult.InsufficientExperience ->
                WebOutfitReservationResult.InsufficientExperience(result.pet)
            DashboardOutfitReservationResult.Missing -> WebOutfitReservationResult.Missing
            DashboardOutfitReservationResult.WrongStage -> WebOutfitReservationResult.WrongStage
            DashboardOutfitReservationResult.Conflict -> WebOutfitReservationResult.Conflict
        }

        override suspend fun executeOutfit(
            request: LocalPendingOutfit,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult {
            val livePet = repository.decayPetSnapshot(OwnerId, expectedPet.petId)?.pet
                ?: return WebDashboardOperationExecutionResult.Failure
            if (
                livePet.assetSetId != expectedPet.assetSetId ||
                request.petId != livePet.petId ||
                request.baseAssetSetId != livePet.assetSetId
            ) {
                return WebDashboardOperationExecutionResult.Failure
            }
            try {
                outfitAdapter().queue(
                    PendingOutfitRequest(request.requestKey, request.prompt),
                    livePet,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The real adapter has already persisted its retryable/terminal state.
            }
            applyReady(request.petId)
            return loadRecovery(request.petId)
        }

        override suspend fun reserveTravel(
            request: PendingTravelRequest,
            expectedPet: PetDashboardState,
        ): WebTravelReservationResult = when (
            val result = repository.reserveDashboardTravel(
                ownerId = OwnerId,
                petId = expectedPet.petId,
                expectedAssetSetId = expectedPet.assetSetId,
                requestKey = request.requestKey,
                prompt = request.prompt,
            )
        ) {
            is DashboardTravelReservationResult.Accepted -> WebTravelReservationResult.Accepted(
                result.pet,
                result.request,
                result.newlyAccepted,
            )
            is DashboardTravelReservationResult.Finished -> WebTravelReservationResult.Finished(
                result.pet,
                result.result,
            )
            is DashboardTravelReservationResult.Busy ->
                WebTravelReservationResult.Busy(result.pet, result.request)
            DashboardTravelReservationResult.Missing -> WebTravelReservationResult.Missing
            DashboardTravelReservationResult.WrongStage -> WebTravelReservationResult.WrongStage
            DashboardTravelReservationResult.Conflict -> WebTravelReservationResult.Conflict
        }

        override suspend fun executeTravel(
            request: LocalPendingTravelVideo,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult {
            val livePet = repository.decayPetSnapshot(OwnerId, expectedPet.petId)?.pet
                ?: return WebDashboardOperationExecutionResult.Failure
            if (livePet.assetSetId != expectedPet.assetSetId || request.petId != livePet.petId) {
                return WebDashboardOperationExecutionResult.Failure
            }
            try {
                travelAdapter().queue(
                    PendingTravelRequest(request.requestKey, request.prompt),
                    livePet,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The real adapter has already persisted its retryable/terminal state.
            }
            applyReady(request.petId)
            return loadRecovery(request.petId)
        }

        override suspend fun refreshDashboardOperations(
            petId: String,
        ): WebDashboardOperationExecutionResult {
            val recovery = repository.loadOwnerRecovery(OwnerId)
            recovery.pendingOutfits.filter {
                it.petId == petId &&
                    it.backendJobId != null &&
                    it.backendState in PollableStates
            }.forEach { pending ->
                outfitAdapter().pollOnce(pending)
            }
            recovery.pendingTravels.filter {
                it.petId == petId &&
                    it.backendJobId != null &&
                    it.backendState in PollableStates
            }.forEach { pending ->
                travelAdapter().pollOnce(pending)
            }
            applyReady(petId)
            return loadRecovery(petId)
        }

        override suspend fun refreshDashboardForForeground(
            petId: String,
        ): WebDashboardForegroundRefreshResult {
            repository.decayPetSnapshot(OwnerId, petId)
                ?: return WebDashboardForegroundRefreshResult.Missing
            val updated = loadRecovery(petId) as? WebDashboardOperationExecutionResult.Updated
                ?: return WebDashboardForegroundRefreshResult.LocalDataError
            return WebDashboardForegroundRefreshResult.Updated(updated.recovery)
        }

        override fun authenticatedEventStoryGateway(): EventStoryGateway =
            AndroidEventStoryGateway(OwnerId, repository, service, clock::now)

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

        override suspend fun applyFeed(
            requestKey: String,
            food: DashboardFood,
            audioIndex: Int,
            expectedPet: PetDashboardState,
        ): WebFeedMutationResult = unexpected("applyFeed")

        private fun outfitAdapter() = RealDashboardOutfitAdapter(
            ownerId = OwnerId,
            store = repository,
            stateStore = repository,
            outcomeStore = repository,
            api = service,
            nowEpochMillis = clock::now,
        )

        private fun travelAdapter() = RealDashboardTravelAdapter(
            ownerId = OwnerId,
            store = repository,
            stateStore = repository,
            outcomeStore = repository,
            api = service,
            nowEpochMillis = clock::now,
        )

        private suspend fun applyReady(petId: String) {
            DashboardOutcomeApplicationCoordinator(
                ownerId = OwnerId,
                recoveryStore = repository,
                outcomeStore = repository,
                stateStore = repository,
                nowEpochMillis = clock::now,
            ).applyReady(petId)
        }

        private suspend fun loadRecovery(
            petId: String,
        ): WebDashboardOperationExecutionResult {
            val destination = AccountPetLifecycle(repository).startup(OwnerId, petId)
                as? AccountStartupDestination.Dashboard
                ?: return WebDashboardOperationExecutionResult.Failure
            val recovery = repository.loadOwnerRecovery(OwnerId)
            return WebDashboardOperationExecutionResult.Updated(
                WebDashboardRecovery(
                    destination = destination,
                    operations = recovery.operationState(petId),
                ),
            )
        }

        private fun unexpected(operation: String): Nothing =
            error("Unexpected gateway operation in production feature fixture: $operation")
    }

    private class ScriptedFeatureService(
        private val holdFirstOutfitPoll: Boolean = false,
        private val holdFirstTravelPoll: Boolean = false,
        private val dueStory: ScheduledStoryDto? = null,
        private val holdFirstStoryChoice: Boolean = false,
    ) : TestAndroidFeatureService() {
        val outfitSimplifyCalls = AtomicInteger()
        val outfitSubmitCalls = AtomicInteger()
        val outfitPollCalls = AtomicInteger()
        val travelSubmitCalls = AtomicInteger()
        val travelPollCalls = AtomicInteger()
        val storyChoiceRequestKeys = mutableListOf<String>()
        val firstOutfitPollStarted = CompletableDeferred<Unit>()
        val firstOutfitPollCancelled = CompletableDeferred<Unit>()
        val firstTravelPollStarted = CompletableDeferred<Unit>()
        val firstTravelPollCancelled = CompletableDeferred<Unit>()
        val firstStoryChoiceStarted = CompletableDeferred<Unit>()
        val firstStoryChoiceCancelled = CompletableDeferred<Unit>()
        val dueStoryCalls = AtomicInteger()
        private var outfitRequestKey: String? = null

        override suspend fun simplifyOutfit(
            request: OutfitSimplifyRequestDto,
        ): FeatureApiResult<OutfitSimplifyResponseDto> {
            outfitSimplifyCalls.incrementAndGet()
            return FeatureApiResult.Success(
                OutfitSimplifyResponseDto(
                    item = "cloak",
                    displayItem = "лунный плащ",
                    generationDescription = "a moon cloak",
                ),
            )
        }

        override suspend fun submitOutfit(
            request: OutfitJobRequestDto,
        ): FeatureApiResult<GenerationEnvelopeDto> {
            outfitSubmitCalls.incrementAndGet()
            outfitRequestKey = request.requestKey
            return FeatureApiResult.Success(
                outfitEnvelope(
                    requestKey = request.requestKey,
                    status = GenerationJobStatusDto.Queued,
                ),
            )
        }

        override suspend fun pollOutfit(
            jobId: String,
        ): FeatureApiResult<GenerationEnvelopeDto> {
            assertEquals(OutfitJobId, jobId)
            val attempt = outfitPollCalls.incrementAndGet()
            if (holdFirstOutfitPoll && attempt == 1) {
                firstOutfitPollStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstOutfitPollCancelled.complete(Unit)
                }
            }
            return FeatureApiResult.Success(
                outfitEnvelope(
                    requestKey = requireNotNull(outfitRequestKey),
                    status = GenerationJobStatusDto.Succeeded,
                    result = OutfitGenerationAsset,
                ),
            )
        }

        override suspend fun submitTravel(
            request: TravelVideoRequestDto,
        ): FeatureApiResult<TravelVideoDto> {
            travelSubmitCalls.incrementAndGet()
            return FeatureApiResult.Success(
                travelDto(TravelVideoStatusDto.Animating),
            )
        }

        override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> {
            assertEquals(TravelJobId, jobId)
            val attempt = travelPollCalls.incrementAndGet()
            if (holdFirstTravelPoll && attempt == 1) {
                firstTravelPollStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstTravelPollCancelled.complete(Unit)
                }
            }
            return FeatureApiResult.Success(travelDto(TravelVideoStatusDto.Ready))
        }

        override suspend fun dueStory(
            request: DueStoryRequestDto,
        ): FeatureApiResult<DueStoryResponseDto> {
            dueStoryCalls.incrementAndGet()
            return FeatureApiResult.Success(DueStoryResponseDto(story = dueStory))
        }

        override suspend fun chooseStory(
            storyId: String,
            request: ScheduledStoryChoiceRequestDto,
        ): FeatureApiResult<ScheduledStoryDto> {
            assertEquals(ScheduledStoryId, storyId)
            storyChoiceRequestKeys += request.requestKey
            if (holdFirstStoryChoice && storyChoiceRequestKeys.size == 1) {
                firstStoryChoiceStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstStoryChoiceCancelled.complete(Unit)
                }
            }
            return FeatureApiResult.Success(
                requireNotNull(dueStory).copy(
                    selectedChoice = request.choice,
                    result = ScheduledStoryResultDto(
                        text = "Тото помог путнику.",
                        adviceAssessment = "helpful",
                        reaction = "Спасибо!",
                        reactionTone = "enthusiastic",
                        consequence = "Путник нашёл дорогу.",
                        outcomeValence = "positive",
                        experienceGained = 20,
                    ),
                ),
            )
        }

        fun outfitCallVector(): List<Int> = listOf(
            outfitSimplifyCalls.get(),
            outfitSubmitCalls.get(),
            outfitPollCalls.get(),
        )

        fun travelCallVector(): List<Int> = listOf(
            travelSubmitCalls.get(),
            travelPollCalls.get(),
        )
    }

    private class MutableEpochClock(initial: Long) {
        private val value = AtomicLong(initial)
        fun now(): Long = value.get()
        fun set(next: Long) = value.set(next)
    }

    private class CanonicalUuidSequence(start: Long) {
        private val value = AtomicLong(start)
        fun next(): String {
            val suffix = value.getAndIncrement().toString().padStart(12, '0')
            return "00000000-0000-4000-8000-$suffix"
        }
    }

    private companion object {
        const val OwnerId = "production-feature-owner"
        const val PetId = "production-feature-pet"
        const val InitialAssetSetId = "production-feature-assets-v1"
        const val NewOutfitAssetSetId = "production-feature-assets-outfit-v2"
        const val MediaBaseUrl = "https://example.test/"
        const val FixedIsoTimestamp = "2026-07-23T10:00:00Z"
        const val FixedEpochMillis = 1_000_000L
        const val FixedElapsedRealtimeMillis = 5_000L

        const val OutfitRequestKey = "10000000-0000-4000-8000-000000000001"
        const val TravelRequestKey = "10000000-0000-4000-8000-000000000002"
        const val ScheduledChoiceRequestKey = "10000000-0000-4000-8000-000000000003"
        const val OnboardingChoiceRequestKey = "10000000-0000-4000-8000-000000000004"
        const val OutfitJobId = "production-outfit-job"
        const val TravelJobId = "production-travel-job"
        const val OutfitPrompt = "Лунный плащ"
        const val TravelPrompt = "На ночной рынок духов"
        const val ScheduledChoice = "Помочь путнику"
        const val ScheduledStoryId = "android-story-0123456789abcdef0123456789abcdef"

        val MonotonicNanos = AtomicLong(20_000_000_000L)

        val ScheduledStory = ScheduledStoryDto(
            storyId = ScheduledStoryId,
            petId = PetId,
            title = "Ночной перекрёсток",
            text = "Тото встретил заблудившегося путника.",
            question = "Что ему сделать?",
            choices = listOf(
                "Пройти мимо",
                ScheduledChoice,
                "Позвать стражу",
                "Спрятаться",
            ),
            createdAt = FixedIsoTimestamp,
            imageUrl = "${MediaBaseUrl}story.png?v=1",
        )

        val OutfitGenerationAsset = GenerationAssetDto(
            assetSetId = NewOutfitAssetSetId,
            generatedAt = FixedIsoTimestamp,
            images = generatedImages("outfit"),
            videoUrl = "${MediaBaseUrl}outfit-idle.mp4?v=1",
            sadVideoUrl = "${MediaBaseUrl}outfit-sad.mp4?v=1",
            happyVideoUrl = "${MediaBaseUrl}outfit-happy.mp4?v=1",
        )

        fun outfitEnvelope(
            requestKey: String,
            status: GenerationJobStatusDto,
            result: GenerationAssetDto? = null,
        ) = GenerationEnvelopeDto(
            requestKey = requestKey,
            petId = PetId,
            job = GenerationJobDto(
                jobId = OutfitJobId,
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

        fun travelDto(status: TravelVideoStatusDto) = TravelVideoDto(
            jobId = TravelJobId,
            status = status,
            prompt = TravelPrompt,
            title = "Ночной рынок",
            scenario = "Тото знакомится с духами.",
            imageUrl = if (status == TravelVideoStatusDto.Ready) {
                "${MediaBaseUrl}travel.png?v=1"
            } else {
                null
            },
            videoUrl = if (status == TravelVideoStatusDto.Ready) {
                "${MediaBaseUrl}travel.mp4?v=1"
            } else {
                null
            },
            createdAt = FixedIsoTimestamp,
            updatedAt = FixedIsoTimestamp,
        )

        fun generatedMedia(prefix: String) = PetGeneratedMedia(
            generatedAt = FixedIsoTimestamp,
            videoUrl = "${MediaBaseUrl}$prefix-idle.mp4?v=1",
            sadVideoUrl = "${MediaBaseUrl}$prefix-sad.mp4?v=1",
            happyVideoUrl = "${MediaBaseUrl}$prefix-happy.mp4?v=1",
            moodImages = generatedImages(prefix).flatMap { (stage, moods) ->
                moods.map { (mood, url) -> PetMoodImage(stage, mood, url) }
            },
        )

        fun generatedImages(prefix: String): Map<String, Map<String, String>> {
            val moods = listOf("idle", "happy", "hungry", "sad")
            return listOf("baby", "teen", "adult").associateWith { stage ->
                moods.associateWith { mood ->
                    "${MediaBaseUrl}$prefix-$stage-$mood.png?v=1"
                }
            }
        }
    }
}

private fun com.gigagochi.app.core.database.OwnerRecoveryData.operationState(
    petId: String,
) = WebDashboardOperationState(
    outfit = pendingOutfits
        .filter { it.petId == petId }
        .maxByOrNull { it.acceptedAtEpochMillis },
    travel = pendingTravels
        .filter { it.petId == petId }
        .maxByOrNull { it.acceptedAtEpochMillis },
    latestTravelResult = travelVideoAssets
        .filter { it.petId == petId }
        .maxByOrNull { it.completedAtEpochMillis },
)

private val PollableStates = setOf(
    PendingBackendState.Attached,
    PendingBackendState.ForegroundReady,
    PendingBackendState.Ready,
)
