package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.database.LocalPendingChat
import com.gigagochi.app.core.database.LocalDashboardFeedReceipt
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.security.NotificationDeepLinkDestination
import com.gigagochi.app.core.security.NotificationDeepLinkExtras
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.GeneratedPetFixture
import com.gigagochi.app.feature.create.GenerationStatus
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.create.answer
import com.gigagochi.app.feature.create.markGenerationFailed
import com.gigagochi.app.feature.dashboard.BerryReply
import com.gigagochi.app.feature.dashboard.ChatFailureMessage
import com.gigagochi.app.feature.dashboard.DashboardAmbientResult
import com.gigagochi.app.feature.dashboard.DashboardChatResult
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.DashboardPromptMaxLength
import com.gigagochi.app.feature.dashboard.DashboardReplyAutoAdvanceMillis
import com.gigagochi.app.feature.dashboard.DashboardSaveRetryDelayMillis
import com.gigagochi.app.feature.dashboard.LeafReply
import com.gigagochi.app.feature.dashboard.OutfitFailureMessage
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.TravelFailureMessage
import com.gigagochi.app.feature.travel.OnboardingBatCorrectChoice
import com.gigagochi.app.feature.travel.ScheduledStoryChoiceResult
import com.gigagochi.app.feature.travel.ScheduledStoryChoicePreparationResult
import com.gigagochi.app.feature.travel.ScheduledStoryDueResult
import com.gigagochi.app.feature.travel.TravelStoryChoiceFailureMessage
import com.gigagochi.app.feature.travel.onboardingBatStory
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import java.time.Instant
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionWebAppRuntimeTest {
    @Test
    fun `dashboard bootstrap projects first session without owner or tokens`() = runBlocking {
        val pet = pet()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(
                        pet = pet,
                        firstSession = LocalFirstSession(
                            ownerId = "owner-secret",
                            petId = pet.petId,
                            stage = FirstSessionStage.AwaitingChat,
                            updatedAtEpochMillis = 10L,
                        ),
                    ),
                ),
            ),
        )
        val runtime = runtime(gateway)

        val snapshot = runtime.snapshot()
        val encoded = BridgeCodec.json.encodeToString(snapshot)

        assertEquals("dashboard", snapshot.route)
        assertEquals("chat", snapshot.firstSession?.allowedAction)
        assertTrue(snapshot.firstSession?.messagePortions?.isNotEmpty() == true)
        assertFalse(encoded.contains("owner-secret"))
        assertFalse(encoded.contains(pet.petId))
        assertFalse(encoded.contains(pet.assetSetId))
        assertFalse(encoded.contains("\"petId\""))
        assertFalse(encoded.contains("\"assetSetId\""))
        assertFalse(encoded.contains("accessToken"))
        assertFalse(encoded.contains("refreshToken"))
    }

    @Test
    fun `connection error retries bootstrap while ready state remains stable`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.ConnectionError,
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        )
        val runtime = runtime(gateway)

        val failed = runtime.snapshot()
        val ready = runtime.snapshot()
        val repeated = runtime.snapshot()

        assertEquals("connectionError", failed.route)
        assertEquals("dashboard", ready.route)
        assertEquals(2, gateway.bootstrapCalls)
        assertEquals(ready.revision, repeated.revision)
    }

    @Test
    fun `create mapping preserves current question and generation state`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Create(
                    CreatePetState().answer(
                        "Ледяного дракона",
                        reducedMotion = true,
                        requestKeyFactory = { UUID.randomUUID().toString() },
                        petIdFactory = { "pet-created" },
                    ),
                ),
            ),
        )

        val snapshot = runtime(gateway).snapshot()

        assertEquals("create", snapshot.route)
        assertEquals(1, snapshot.create?.step)
        assertEquals("Как его будут звать?", snapshot.create?.title)
        assertEquals("Какой у него характер?", snapshot.create?.nextQuestion?.title)
        assertEquals(listOf("Добрый", "Злой", "Ленивый"), snapshot.create?.nextQuestion?.options)
        assertEquals("running", snapshot.create?.generation)
        assertNull(snapshot.pet)
    }

    @Test
    fun `create background completion is local transition-only and idempotent`() = runBlocking {
        val state = CreatePetState().answer(
            "Ледяного дракона",
            reducedMotion = false,
            requestKeyFactory = { "create-request" },
            petIdFactory = { "pet-created" },
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(state)),
        ).apply {
            generationHandler = { awaitCancellation() }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val transition = runtime.snapshot()
        val command = createCommand("CREATE_BACKGROUND_COMPLETE", transition.revision)

        val formed = runtime.dispatch(command)
        val replay = runtime.dispatch(command)
        val invalid = runCatching {
            runtime.dispatch(createCommand("CREATE_BACKGROUND_COMPLETE", formed.revision))
        }.exceptionOrNull()

        assertEquals("transition", transition.create?.phase)
        assertEquals("formed", formed.create?.phase)
        assertEquals(formed.revision, replay.revision)
        assertEquals("WRONG_STAGE", (invalid as WebAppRuntimeException).bridgeCode)
        assertTrue(gateway.persistedCreates.isEmpty())
        runtime.close()
    }

    @Test
    fun `all dashboard text drafts survive close and reopen without submission`() = runBlocking {
        val runtime = runtime(
            FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(pet())),
                ),
            ),
        )
        var snapshot = runtime.snapshot()
        val drafts = listOf(
            "chat" to "  незавершённое сообщение  ",
            "outfit" to "серебряный плащ",
            "travel" to "ночной рынок духов",
        )

        drafts.forEach { (mode, value) ->
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) { put("mode", mode) },
            )
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", mode)
                    put("value", value)
                },
            )
            val projectedDraft = when (mode) {
                "chat" -> snapshot.dashboard?.chat?.draft
                "outfit" -> snapshot.dashboard?.outfit?.draft
                else -> snapshot.dashboard?.travel?.draft
            }
            assertEquals(value, projectedDraft)

            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision),
            )
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) { put("mode", mode) },
            )
            val reopenedDraft = when (mode) {
                "chat" -> snapshot.dashboard?.chat?.draft
                "outfit" -> snapshot.dashboard?.outfit?.draft
                else -> snapshot.dashboard?.travel?.draft
            }
            assertEquals(value, reopenedDraft)
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision),
            )
        }
        runtime.close()
    }

    @Test
    fun `dashboard draft command enforces exact payload active mode route and max length`() =
        runBlocking {
            val eventGateway = RuntimeEventGateway(pet())
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway)
            var snapshot = runtime.snapshot()

            suspend fun failure(command: BridgeProductCommand): WebAppRuntimeException =
                runCatching { runtime.dispatch(command) }
                    .exceptionOrNull() as WebAppRuntimeException

            assertEquals(
                "WRONG_STAGE",
                failure(dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                    put("value", "draft")
                }).bridgeCode,
            )
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) { put("mode", "chat") },
            )
            assertEquals(
                "WRONG_STAGE",
                failure(dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "outfit")
                    put("value", "draft")
                }).bridgeCode,
            )
            listOf(
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                },
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                    put("value", "draft")
                    put("extra", true)
                },
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "feed")
                    put("value", "draft")
                },
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                    put("value", "x".repeat(DashboardPromptMaxLength + 1))
                },
            ).forEach { command ->
                assertEquals("INVALID_PAYLOAD", failure(command).bridgeCode)
            }

            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                    put("value", "x".repeat(DashboardPromptMaxLength))
                },
            )
            assertEquals(DashboardPromptMaxLength, snapshot.dashboard?.chat?.draft?.length)
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision),
            )
            snapshot = runtime.dispatch(
                dashboardCommand("NAVIGATE", snapshot.revision) { put("route", "events") },
            )
            assertEquals(
                "WRONG_STAGE",
                failure(dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                    put("mode", "chat")
                    put("value", "route draft")
                }).bridgeCode,
            )
            runtime.close()
        }

    @Test
    fun `outfit submit projects charged durable pending before async side effect`() = runBlocking {
        val executionStarted = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            outfitReservationHandler = { request, expectedPet ->
                WebOutfitReservationResult.Accepted(
                    pet = expectedPet.copy(experience = expectedPet.experience - 200),
                    request = pendingOutfit(request, expectedPet, PendingBackendState.Pending),
                    newlyAccepted = true,
                )
            }
            outfitExecutionHandler = { _, _ ->
                executionStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        val accepted = runtime.dispatch(
            dashboardCommand("OUTFIT_SUBMIT", initial.revision) {
                put("prompt", "  футболка Metallica  ")
            },
        )
        withTimeout(1_000) { executionStarted.await() }

        assertEquals(0, accepted.pet?.experience)
        assertEquals("футболка Metallica", gateway.outfitReservations.single().prompt)
        assertEquals("pending", accepted.dashboard?.outfit?.pending?.status)
        assertEquals("футболка Metallica", accepted.dashboard?.outfit?.pending?.prompt)
        assertEquals(200, accepted.dashboard?.outfit?.pending?.experienceCost)
        assertEquals(200, accepted.dashboard?.outfit?.experienceCost)
        assertTrue(accepted.dashboard?.outfit?.thinking == true)
        assertEquals(accepted.dashboard?.outfit?.activeRequestKey, gateway.outfitExecutions.single().requestKey)
        runtime.close()
    }

    @Test
    fun `outfit retry redispatches original identity without reserving or charging again`() = runBlocking {
        val retryablePet = pet().copy(experience = 0)
        val retryable = pendingOutfit(
            PendingOutfitRequest("durable-outfit", "красный шарф"),
            retryablePet,
            PendingBackendState.Retryable,
        )
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    destination = dashboard(retryablePet).copy(pendingOutfit = retryable),
                    operations = WebDashboardOperationState(outfit = retryable),
                ),
            ),
        ).apply {
            outfitExecutionHandler = { request, _ ->
                if (outfitExecutions.size == 1) {
                    firstStarted.complete(Unit)
                    firstRelease.await()
                    WebDashboardOperationExecutionResult.Updated(
                        WebDashboardRecovery(
                            dashboard(retryablePet).copy(pendingOutfit = retryable),
                            WebDashboardOperationState(outfit = retryable),
                        ),
                    )
                } else {
                    secondStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val recovered = runtime.snapshot()
        withTimeout(1_000) { firstStarted.await() }
        firstRelease.complete(Unit)

        val retrying = runtime.dispatch(dashboardCommand("OUTFIT_RETRY", recovered.revision))
        withTimeout(1_000) { secondStarted.await() }

        assertEquals(listOf("durable-outfit", "durable-outfit"), gateway.outfitExecutions.map { it.requestKey })
        assertTrue(gateway.outfitReservations.isEmpty())
        assertEquals(0, retrying.pet?.experience)
        assertEquals("durable-outfit", retrying.dashboard?.outfit?.activeRequestKey)
        runtime.close()
    }

    @Test
    fun `pending outfit failure survives close reopen and retries without second debit`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val chargedPet = pet().copy(experience = 0)
        lateinit var durable: LocalPendingOutfit
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            outfitReservationHandler = { request, expectedPet ->
                durable = pendingOutfit(request, expectedPet, PendingBackendState.Pending)
                WebOutfitReservationResult.Accepted(
                    pet = chargedPet,
                    request = durable,
                    newlyAccepted = true,
                )
            }
            outfitExecutionHandler = { _, _ ->
                if (outfitExecutions.size == 1) {
                    firstStarted.complete(Unit)
                    firstRelease.await()
                    WebDashboardOperationExecutionResult.Failure
                } else {
                    secondStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()
        runtime.dispatch(dashboardCommand("OUTFIT_SUBMIT", initial.revision) {
            put("prompt", "красный шарф")
        })
        withTimeout(1_000) { firstStarted.await() }
        val failureEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        firstRelease.complete(Unit)
        val failed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000) { failureEvent.await() }.payload,
        )
        assertEquals("idle", failed.dashboardMode)
        assertEquals(OutfitFailureMessage, failed.dashboard?.outfit?.error)
        assertEquals("pending", failed.dashboard?.outfit?.pending?.status)

        val opened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", failed.revision) { put("mode", "outfit") },
        )
        assertEquals("outfit", opened.dashboardMode)
        assertEquals(OutfitFailureMessage, opened.dashboard?.outfit?.error)
        assertFalse(opened.dashboard?.outfit?.thinking == true)

        val closed = runtime.dispatch(dashboardCommand("DASHBOARD_CLOSE_MODE", opened.revision))
        assertEquals("idle", closed.dashboardMode)
        assertNull(closed.dashboard?.outfit?.error)
        val reopened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", closed.revision) { put("mode", "outfit") },
        )
        assertEquals(OutfitFailureMessage, reopened.dashboard?.outfit?.error)

        val retrying = runtime.dispatch(dashboardCommand("OUTFIT_RETRY", reopened.revision))
        withTimeout(1_000) { secondStarted.await() }

        assertEquals(listOf(durable.requestKey, durable.requestKey), gateway.outfitExecutions.map { it.requestKey })
        assertEquals(1, gateway.outfitReservations.size)
        assertEquals(0, retrying.pet?.experience)
        assertEquals(durable.requestKey, retrying.dashboard?.outfit?.activeRequestKey)
        assertTrue(retrying.dashboard?.outfit?.thinking == true)
        runtime.close()
    }

    @Test
    fun `travel submit persists trimmed identity before async side effect`() = runBlocking {
        val executionStarted = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            travelReservationHandler = { request, expectedPet ->
                WebTravelReservationResult.Accepted(
                    expectedPet,
                    pendingTravel(request, expectedPet, PendingBackendState.Pending),
                    newlyAccepted = true,
                )
            }
            travelExecutionHandler = { _, _ ->
                executionStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        val accepted = runtime.dispatch(
            dashboardCommand("TRAVEL_SUBMIT", initial.revision) {
                put("prompt", "  ночной рынок духов  ")
            },
        )
        withTimeout(1_000) { executionStarted.await() }

        assertEquals("ночной рынок духов", gateway.travelReservations.single().prompt)
        assertEquals("pending", accepted.dashboard?.travel?.pending?.status)
        assertEquals("ночной рынок духов", accepted.dashboard?.travel?.pending?.prompt)
        assertTrue(accepted.dashboard?.travel?.thinking == true)
        assertEquals(accepted.dashboard?.travel?.activeRequestKey, gateway.travelExecutions.single().requestKey)
        runtime.close()
    }

    @Test
    fun `pending travel failure survives close reopen and retries without second reservation`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val currentPet = pet()
        lateinit var durable: LocalPendingTravelVideo
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(currentPet))),
        ).apply {
            travelReservationHandler = { request, expectedPet ->
                durable = pendingTravel(request, expectedPet, PendingBackendState.Pending)
                WebTravelReservationResult.Accepted(
                    pet = expectedPet,
                    request = durable,
                    newlyAccepted = true,
                )
            }
            travelExecutionHandler = { _, _ ->
                if (travelExecutions.size == 1) {
                    firstStarted.complete(Unit)
                    firstRelease.await()
                    WebDashboardOperationExecutionResult.Failure
                } else {
                    secondStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()
        val submitted = runtime.dispatch(dashboardCommand("TRAVEL_SUBMIT", initial.revision) {
            put("prompt", "ночной рынок")
        })
        withTimeout(1_000) { firstStarted.await() }
        val closedInFlight = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", submitted.revision),
        )
        val reopenedInFlight = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", closedInFlight.revision) {
                put("mode", "travel")
            },
        )
        assertNull(reopenedInFlight.dashboard?.travel?.error)
        val failureEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        firstRelease.complete(Unit)
        val failed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000) { failureEvent.await() }.payload,
        )
        assertEquals("idle", failed.dashboardMode)
        assertEquals(TravelFailureMessage, failed.dashboard?.travel?.error)
        assertEquals("pending", failed.dashboard?.travel?.pending?.status)

        val opened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", failed.revision) { put("mode", "travel") },
        )
        assertEquals("travel", opened.dashboardMode)
        assertEquals(TravelFailureMessage, opened.dashboard?.travel?.error)
        assertFalse(opened.dashboard?.travel?.thinking == true)

        val closed = runtime.dispatch(dashboardCommand("DASHBOARD_CLOSE_MODE", opened.revision))
        assertEquals("idle", closed.dashboardMode)
        assertNull(closed.dashboard?.travel?.error)
        val reopened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", closed.revision) { put("mode", "travel") },
        )
        assertEquals(TravelFailureMessage, reopened.dashboard?.travel?.error)

        val retrying = runtime.dispatch(dashboardCommand("TRAVEL_RETRY", reopened.revision))
        withTimeout(1_000) { secondStarted.await() }

        assertEquals(listOf(durable.requestKey, durable.requestKey), gateway.travelExecutions.map { it.requestKey })
        assertEquals(1, gateway.travelReservations.size)
        assertEquals(currentPet.experience, retrying.pet?.experience)
        assertEquals(durable.requestKey, retrying.dashboard?.travel?.activeRequestKey)
        assertTrue(retrying.dashboard?.travel?.thinking == true)
        runtime.close()
    }

    @Test
    fun `outfit attachment emits authoritative queued state change`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val executionStarted = CompletableDeferred<Unit>()
        val acceptedPet = pet().copy(experience = 0)
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            outfitReservationHandler = { request, expectedPet ->
                val pending = pendingOutfit(request, expectedPet, PendingBackendState.Pending)
                WebOutfitReservationResult.Accepted(acceptedPet, pending, newlyAccepted = true)
            }
            outfitExecutionHandler = { request, _ ->
                executionStarted.complete(Unit)
                release.await()
                val attached = request.copy(
                    backendJobId = "outfit-job",
                    backendState = PendingBackendState.Attached,
                    preparedDisplayItem = "Красный шарф",
                )
                WebDashboardOperationExecutionResult.Updated(
                    WebDashboardRecovery(
                        dashboard(acceptedPet).copy(pendingOutfit = attached),
                        WebDashboardOperationState(outfit = attached),
                    ),
                )
            }
        }
        val runtime = runtime(
            gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            delayMillis = { awaitCancellation() },
        )
        val initial = runtime.snapshot()
        runtime.dispatch(dashboardCommand("OUTFIT_SUBMIT", initial.revision) {
            put("prompt", "красный шарф")
        })
        withTimeout(1_000) { executionStarted.await() }
        val changed = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        release.complete(Unit)
        val snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000) { changed.await() }.payload,
        )

        assertEquals("idle", snapshot.dashboardMode)
        assertEquals("attached", snapshot.dashboard?.outfit?.pending?.status)
        assertEquals("Красный шарф", snapshot.dashboard?.outfit?.pending?.displayItem)
        assertFalse(snapshot.dashboard?.outfit?.thinking == true)
        assertEquals("transient", snapshot.dashboard?.reply?.source)
        assertEquals(
            listOf("Красный шарф? Интересно, давай я позову тебя, когда переоденусь."),
            snapshot.dashboard?.reply?.portions,
        )
        assertEquals(0, snapshot.pet?.experience)
        runtime.close()
    }

    @Test
    fun `travel completion emits ready media after deterministic auto consume`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val executionStarted = CompletableDeferred<Unit>()
        val currentPet = pet()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(currentPet))),
        ).apply {
            travelReservationHandler = { request, expectedPet ->
                WebTravelReservationResult.Accepted(
                    expectedPet,
                    pendingTravel(request, expectedPet, PendingBackendState.Pending),
                    newlyAccepted = true,
                )
            }
            travelExecutionHandler = { request, _ ->
                executionStarted.complete(Unit)
                release.await()
                WebDashboardOperationExecutionResult.Updated(
                    WebDashboardRecovery(
                        dashboard(currentPet),
                        WebDashboardOperationState(
                            latestTravelResult = LocalTravelVideoAsset(
                                ownerId = "owner-test",
                                petId = currentPet.petId,
                                requestKey = request.requestKey,
                                backendJobId = "travel-job",
                                prompt = request.prompt,
                                title = "Ночной рынок",
                                scenario = "Питомец знакомится с духами",
                                imageUrl = "https://example.test/travel.jpg",
                                videoUrl = "https://example.test/travel.mp4",
                                completedAtEpochMillis = 2L,
                                consumedAtEpochMillis = 3L,
                            ),
                        ),
                    ),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()
        runtime.dispatch(dashboardCommand("TRAVEL_SUBMIT", initial.revision) {
            put("prompt", "ночной рынок")
        })
        withTimeout(1_000) { executionStarted.await() }
        val changed = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        release.complete(Unit)
        val snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000) { changed.await() }.payload,
        )

        assertNull(snapshot.dashboard?.travel?.pending)
        val encoded = BridgeCodec.json.encodeToString(snapshot)
        assertFalse(encoded.contains("https://example.test/travel.jpg"))
        assertFalse(encoded.contains("https://example.test/travel.mp4"))
        assertFalse(encoded.contains("travel-job"))
        assertEquals("transient", snapshot.dashboard?.reply?.source)
        runtime.close()
    }

    @Test
    fun `fifth pet tap persists reward but never overwrites base message`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(happiness = 82))),
            ),
        )
        val runtime = runtime(gateway, chooseThanks = { "Щекотно!" })
        var snapshot = runtime.snapshot()

        repeat(5) {
            snapshot = runtime.dispatch(petTap(snapshot.revision))
        }

        assertEquals(5, gateway.tapWrites)
        assertEquals(0, snapshot.pet?.petTapProgress)
        assertEquals(97, snapshot.pet?.happiness)
        assertEquals("Постоянная реплика", snapshot.pet?.message)
        assertEquals("Щекотно!", snapshot.petTapFeedback?.thanks)
        assertEquals(5_000L, snapshot.petTapFeedback?.visibleMillis)
    }

    @Test
    fun `pet tap feedback fires only after the durable mutation is applied`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        var feedbackCount = 0
        val runtime = runtime(
            gateway,
            onPetTapFeedback = { feedbackCount += 1 },
        )
        val initial = runtime.snapshot()

        listOf(
            WebPetTapMutationResult.Conflict to "STATE_CONFLICT",
            WebPetTapMutationResult.Missing to "NOT_FOUND",
            WebPetTapMutationResult.LocalDataError to "LOCAL_DATA_ERROR",
        ).forEach { (failure, expectedCode) ->
            gateway.petTapHandler = { failure }
            val error = runCatching {
                runtime.dispatch(petTap(initial.revision))
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals(expectedCode, error.bridgeCode)
            assertEquals(0, feedbackCount)
        }

        gateway.petTapHandler = { expected ->
            WebPetTapMutationResult.Applied(expected.copy(petTapProgress = 1))
        }
        val applied = runtime.dispatch(petTap(initial.revision))

        assertEquals(1, applied.pet?.petTapProgress)
        assertEquals(1, feedbackCount)
        runtime.close()
    }

    @Test
    fun `stale revision cannot write`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        val runtime = runtime(gateway)
        runtime.snapshot()

        val error = runCatching { runtime.dispatch(petTap("stale")) }.exceptionOrNull()

        assertEquals("STATE_CONFLICT", (error as WebAppRuntimeException).bridgeCode)
        assertEquals(0, gateway.tapWrites)
    }

    @Test
    fun `same request key is idempotent and never rolls UI back after a later tap`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        val runtime = runtime(gateway)
        val initial = runtime.snapshot()
        val firstCommand = petTap(initial.revision)
        val first = runtime.dispatch(firstCommand)
        val second = runtime.dispatch(petTap(first.revision))
        val replayedFirst = runtime.dispatch(firstCommand)

        assertEquals(2, gateway.tapWrites)
        assertEquals(2, second.pet?.petTapProgress)
        assertEquals(second.pet?.petTapProgress, replayedFirst.pet?.petTapProgress)
        assertEquals(second.revision, replayedFirst.revision)
        assertEquals(firstCommand.requestKey, replayedFirst.petTapFeedback?.eventId)
    }

    @Test
    fun `safe area changes do not invalidate product revision`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        val runtime = runtime(gateway)
        val before = runtime.snapshot()

        runtime.updateSafeArea(WebSafeAreaSnapshot(bottom = 24.0, imeHeight = 320.0))
        val after = runtime.snapshot()

        assertEquals(before.revision, after.revision)
        assertNotEquals(before.safeArea, after.safeArea)
    }

    @Test
    fun `five create answers persist every revision while preserving first operation identity`() =
        runBlocking {
            val generation = CompletableDeferred<PetGenerationExecutionResult>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
            ).apply {
                generationHandler = { generation.await() }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                petIdFactory = { "pet-stable" },
            )
            var snapshot = runtime.snapshot()
            val requestKeys = mutableListOf<String>()

            listOf("Ледяного дракона", "Тото", "Добрый", "Пауков", "Вантуз")
                .forEach { answer ->
                    val command = createCommand("CREATE_ANSWER", snapshot.revision) {
                        put("answer", answer)
                        put("step", requireNotNull(snapshot.create).step)
                    }
                    requestKeys += command.requestKey
                    snapshot = runtime.dispatch(command)
                }

            assertEquals(5, gateway.persistedCreates.size)
            assertEquals(listOf(1, 2, 3, 4, 5), gateway.persistedCreates.map { it.step })
            assertEquals(setOf("pet-stable"), gateway.persistedCreates.mapNotNull { it.pending?.petId }.toSet())
            assertEquals(
                setOf(requestKeys.first()),
                gateway.persistedCreates.mapNotNull { it.pending?.requestKey }.toSet(),
            )
            assertEquals(1, gateway.generationRequests.size)
            assertEquals(requestKeys.first(), gateway.generationRequests.single().requestKey)
            assertEquals("running", snapshot.create?.generation)
            assertEquals(5, snapshot.create?.step)

            runtime.close()
        }

    @Test
    fun `create stale and duplicate commands never persist or append twice`() = runBlocking {
        val generation = CompletableDeferred<PetGenerationExecutionResult>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
        ).apply { generationHandler = { generation.await() } }
        val runtime = runtime(
            gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            petIdFactory = { "pet-stable" },
        )
        val initial = runtime.snapshot()
        val command = createCommand("CREATE_ANSWER", initial.revision) {
            put("answer", "Ледяного дракона")
            put("step", 0)
        }
        val accepted = runtime.dispatch(command)

        val replay = runtime.dispatch(command)
        val conflicting = command.copy(
            expectedSnapshotRevision = accepted.revision,
            payload = buildJsonObject {
                put("answer", "Водяной дух")
                put("step", 0)
            },
        )
        val conflictError = runCatching { runtime.dispatch(conflicting) }.exceptionOrNull()
        val staleError = runCatching {
            runtime.dispatch(
                createCommand("CREATE_ANSWER", initial.revision) {
                    put("answer", "Тото")
                    put("step", 0)
                },
            )
        }.exceptionOrNull()
        val oldButtonError = runCatching {
            runtime.dispatch(
                createCommand("CREATE_ANSWER", accepted.revision) {
                    put("answer", "Тото")
                    put("step", 0)
                },
            )
        }.exceptionOrNull()

        assertEquals(1, gateway.persistedCreates.size)
        assertEquals(1, gateway.generationRequests.size)
        assertEquals(accepted.revision, replay.revision)
        assertEquals("INVALID_PAYLOAD", (conflictError as WebAppRuntimeException).bridgeCode)
        assertEquals("STATE_CONFLICT", (staleError as WebAppRuntimeException).bridgeCode)
        assertEquals("WRONG_STAGE", (oldButtonError as WebAppRuntimeException).bridgeCode)
        runtime.close()
    }

    @Test
    fun `create commands reject malformed payloads and wrong stages before side effects`() =
        runBlocking {
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
            )
            val runtime = runtime(gateway)
            val initial = runtime.snapshot()
            val malformed = listOf(
                buildJsonObject {},
                buildJsonObject {
                    put("answer", "   ")
                    put("step", 0)
                },
                buildJsonObject {
                    put("answer", "x".repeat(301))
                    put("step", 0)
                },
                buildJsonObject { put("answer", "Тото") },
                buildJsonObject {
                    put("answer", "Тото")
                    put("step", -1)
                },
                buildJsonObject {
                    put("answer", "Тото")
                    put("step", 5)
                },
                buildJsonObject {
                    put("answer", "Тото")
                    put("step", "0")
                },
                buildJsonObject {
                    put("answer", "Тото")
                    put("step", 0)
                    put("extra", true)
                },
            )

            malformed.forEach { payload ->
                val error = runCatching {
                    runtime.dispatch(
                        BridgeProductCommand(
                            type = "CREATE_ANSWER",
                            requestKey = UUID.randomUUID().toString(),
                            expectedSnapshotRevision = initial.revision,
                            payload = payload,
                        ),
                    )
                }.exceptionOrNull()
                assertEquals("INVALID_PAYLOAD", (error as WebAppRuntimeException).bridgeCode)
            }
            val retryError = runCatching {
                runtime.dispatch(createCommand("CREATE_RETRY", initial.revision))
            }.exceptionOrNull()

            assertEquals("WRONG_STAGE", (retryError as WebAppRuntimeException).bridgeCode)
            assertEquals(0, gateway.persistedCreates.size)
            assertEquals(0, gateway.generationRequests.size)
            runtime.close()

            val dashboardGateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
            )
            val dashboardRuntime = runtime(dashboardGateway)
            val dashboard = dashboardRuntime.snapshot()
            val wrongRoute = runCatching {
                dashboardRuntime.dispatch(
                    createCommand("CREATE_ANSWER", dashboard.revision) {
                        put("answer", "Тото")
                        put("step", 0)
                    },
                )
            }.exceptionOrNull()
            assertEquals("WRONG_STAGE", (wrongRoute as WebAppRuntimeException).bridgeCode)
            dashboardRuntime.close()
        }

    @Test
    fun `create generation is nonblocking and emits ready snapshot event`() = runBlocking {
        val generation = CompletableDeferred<PetGenerationExecutionResult>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
        ).apply { generationHandler = { generation.await() } }
        val runtime = runtime(
            gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            petIdFactory = { "pet-stable" },
        )
        val initial = runtime.snapshot()

        val running = runtime.dispatch(
            createCommand("CREATE_ANSWER", initial.revision) {
                put("answer", "Ледяного дракона")
                put("step", 0)
            },
        )

        assertEquals("running", running.create?.generation)
        assertFalse(generation.isCompleted)
        assertEquals(1, gateway.generationRequests.size)
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        generation.complete(
            PetGenerationExecutionResult.Success(
                GeneratedPetFixture(
                    description = "Ледяного дракона",
                    petId = "pet-stable",
                    assetSetId = "asset-created",
                ),
            ),
        )
        val emitted = event.await()
        val ready = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(emitted.payload)

        assertEquals("stateChanged", emitted.type)
        assertEquals("ready", ready.create?.generation)
        assertEquals(1, ready.create?.step)
        assertNotEquals(running.revision, ready.revision)
        runtime.close()
    }

    @Test
    fun `persistence retry blocks generation until the exact create revision is durable`() =
        runBlocking {
            val generation = CompletableDeferred<PetGenerationExecutionResult>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
            ).apply {
                persistenceResults += WebCreatePersistenceResult.Failure
                persistenceResults += WebCreatePersistenceResult.Persisted
                generationHandler = { generation.await() }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                petIdFactory = { "pet-stable" },
            )
            val initial = runtime.snapshot()
            val failed = runtime.dispatch(
                createCommand("CREATE_ANSWER", initial.revision) {
                    put("answer", "Ледяного дракона")
                    put("step", 0)
                },
            )

            assertEquals("persistence", failed.create?.retryTarget)
            assertEquals(0, gateway.generationRequests.size)
            val retried = runtime.dispatch(createCommand("CREATE_RETRY", failed.revision))

            assertEquals("running", retried.create?.generation)
            assertNull(retried.create?.retryTarget)
            assertEquals(2, gateway.persistedCreates.size)
            assertEquals(1, gateway.generationRequests.size)
            assertEquals(
                gateway.persistedCreates.first().pending?.requestKey,
                gateway.persistedCreates.last().pending?.requestKey,
            )
            runtime.close()
        }

    @Test
    fun `transient generation retry preserves identity while terminal retry replaces only request key`() =
        runBlocking {
            suspend fun verify(terminal: Boolean) {
                val generation = CompletableDeferred<PetGenerationExecutionResult>()
                val failedState = finalCreateState().markGenerationFailed(
                    newRequestRequired = terminal,
                )
                val gateway = FakeGateway(
                    WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(failedState)),
                ).apply { generationHandler = { generation.await() } }
                val runtime = runtime(
                    gateway,
                    generationScope = CoroutineScope(Dispatchers.Unconfined),
                )
                val failed = runtime.snapshot()
                val oldPending = requireNotNull(failedState.pending)
                val retry = createCommand("CREATE_RETRY", failed.revision)

                val running = runtime.dispatch(retry)
                val persisted = gateway.persistedCreates.single()

                assertEquals("running", running.create?.generation)
                assertEquals(oldPending.petId, persisted.pending?.petId)
                assertEquals(
                    if (terminal) retry.requestKey else oldPending.requestKey,
                    persisted.pending?.requestKey,
                )
                assertEquals(persisted.pending, gateway.generationRequests.single())
                runtime.close()
            }

            verify(terminal = false)
            verify(terminal = true)
        }

    @Test
    fun `finish is explicit guarded and idempotent after durable dashboard reload`() = runBlocking {
        val readyState = finalCreateState(
            generation = GenerationStatus.Ready(
                GeneratedPetFixture(
                    description = "Ледяного дракона",
                    petId = "pet-stable",
                    assetSetId = "asset-created",
                ),
            ),
        )
        val destination = dashboard(pet())
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(readyState)),
        ).apply {
            finalizationHandler = { _, _ -> WebCreateFinalizationResult.Success(destination) }
        }
        val runtime = runtime(gateway)
        val ready = runtime.snapshot()

        assertEquals("create", ready.route)
        assertEquals(0, gateway.finalizationCalls)
        val finish = createCommand("CREATE_FINISH", ready.revision)
        val dashboard = runtime.dispatch(finish)
        val replay = runtime.dispatch(finish)

        assertEquals("dashboard", dashboard.route)
        assertEquals(1, gateway.finalizationCalls)
        assertEquals(dashboard.revision, replay.revision)

        val unreadyGateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(CreatePetState())),
        )
        val unreadyRuntime = runtime(unreadyGateway)
        val unready = unreadyRuntime.snapshot()
        val error = runCatching {
            unreadyRuntime.dispatch(createCommand("CREATE_FINISH", unready.revision))
        }.exceptionOrNull()
        assertEquals("WRONG_STAGE", (error as WebAppRuntimeException).bridgeCode)
        assertEquals(0, unreadyGateway.finalizationCalls)
        runtime.close()
        unreadyRuntime.close()
    }

    @Test
    fun `failed finalization exposes target and retry finalizes without generation`() = runBlocking {
        val readyState = finalCreateState(
            generation = GenerationStatus.Ready(
                GeneratedPetFixture(
                    description = "Ледяного дракона",
                    petId = "pet-stable",
                    assetSetId = "asset-created",
                ),
            ),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(readyState)),
        ).apply {
            finalizationResults += WebCreateFinalizationResult.Failure
            finalizationResults += WebCreateFinalizationResult.Success(dashboard(pet()))
        }
        val runtime = runtime(gateway)
        val ready = runtime.snapshot()
        val failed = runtime.dispatch(createCommand("CREATE_FINISH", ready.revision))

        assertEquals("create", failed.route)
        assertEquals("retryable", failed.create?.generation)
        assertEquals("finalization", failed.create?.retryTarget)
        val succeeded = runtime.dispatch(createCommand("CREATE_RETRY", failed.revision))

        assertEquals("dashboard", succeeded.route)
        assertEquals(2, gateway.finalizationCalls)
        assertEquals(0, gateway.generationRequests.size)
        runtime.close()
    }

    @Test
    fun `recovered running create auto resumes and close cancellation is not an error event`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val recovered = finalCreateState(generation = GenerationStatus.Running)
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(recovered)),
            ).apply {
                generationHandler = {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
            )

            val snapshot = runtime.snapshot()
            started.await()
            assertEquals("running", snapshot.create?.generation)
            assertEquals(1, gateway.generationRequests.size)

            runtime.close()
            cancelled.await()
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `chat returns thinking immediately then emits reply after minimum one second gate`() =
        runBlocking {
            val execution = CompletableDeferred<WebChatExecutionResult>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
            ).apply {
                chatExecutionHandler = { _, _, _ -> execution.await() }
            }
            val requestedDelays = mutableListOf<Long>()
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                delayMillis = { requestedDelays += it },
            )
            val initial = runtime.snapshot()
            val command = dashboardCommand("CHAT_SEND", initial.revision) {
                put("message", "Привет")
            }
            val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

            val thinking = runtime.dispatch(command)

            assertEquals("chat", thinking.dashboardMode)
            assertTrue(thinking.dashboard?.chat?.thinking == true)
            assertEquals(command.requestKey, thinking.dashboard?.chat?.activeRequestKey)
            assertNull(thinking.dashboard?.reply)
            val pending = gateway.chatReservations.last()
            execution.complete(
                WebChatExecutionResult.Success(
                    result = DashboardChatResult("Привет-привет!", pet()),
                    firstSession = null,
                    pendingChat = LocalPendingChat(
                        "owner-test",
                        "pet-1",
                        pending.requestKey,
                        pending.message,
                        1L,
                        "Привет-привет!",
                        2L,
                    ),
                ),
            )
            val replied = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000) { event.await() }.payload,
            )

            assertEquals(listOf(1_000L), requestedDelays)
            assertFalse(replied.dashboard?.chat?.thinking == true)
            assertEquals("chat", replied.dashboard?.reply?.source)
            assertEquals(listOf("Привет-привет!"), replied.dashboard?.reply?.portions)
            val acknowledged = runtime.dispatch(
                dashboardCommand("CHAT_REPLY_PRESENTED", replied.revision) {
                    put("requestKey", command.requestKey)
                },
            )
            assertEquals(listOf(command.requestKey), gateway.acknowledgedChats)
            assertNull(acknowledged.pending.chat)
            assertEquals(command.requestKey, acknowledged.dashboard?.reply?.requestKey)
            assertEquals(listOf("Привет-привет!"), acknowledged.dashboard?.reply?.portions)
            runtime.close()
        }

    @Test
    fun `ordinary chat after completed first session does not enter onboarding completion`() =
        runBlocking {
            val initialPet = pet()
            val completedSession = LocalFirstSession(
                ownerId = "owner-test",
                petId = initialPet.petId,
                stage = FirstSessionStage.Completed,
                updatedAtEpochMillis = 1L,
            )
            val executionGate = CompletableDeferred<Unit>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        dashboard(initialPet, completedSession),
                    ),
                ),
            ).apply {
                chatExecutionHandler = { request, expectedPet, origin ->
                    assertNull(origin)
                    executionGate.await()
                    WebChatExecutionResult.Success(
                        result = DashboardChatResult("Обычный ответ", expectedPet),
                        firstSession = completedSession,
                        pendingChat = LocalPendingChat(
                            ownerId = "owner-test",
                            petId = expectedPet.petId,
                            requestKey = request.requestKey,
                            message = request.message,
                            createdAtEpochMillis = 1L,
                            responseText = "Обычный ответ",
                            completedAtEpochMillis = 2L,
                        ),
                    )
                }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
            )
            var snapshot = runtime.snapshot()
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                    put("mode", "chat")
                },
            )
            val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.events.first()
            }
            val thinking = runtime.dispatch(
                dashboardCommand("CHAT_SEND", snapshot.revision) {
                    put("message", "Привет")
                },
            )

            assertEquals(listOf(null), gateway.chatReservationOrigins)
            assertTrue(thinking.dashboard?.chat?.thinking == true)
            executionGate.complete(Unit)
            val replied = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { completionEvent.await() }.payload,
            )
            assertFalse(replied.dashboard?.chat?.thinking == true)
            assertEquals(listOf("Обычный ответ"), replied.dashboard?.reply?.portions)
            runtime.close()
        }

    @Test
    fun recoveredOnboardingChatPreservesExplicitPortionsAndIndex() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174181"
        val pending = pendingChat(requestKey, "Серёжа")
        val completed = pending.copy(
            responseText = "Очень приятно!",
            completedAtEpochMillis = 2L,
        )
        val initialSession = LocalFirstSession(
            ownerId = "owner-test",
            petId = "pet-1",
            stage = FirstSessionStage.AwaitingChat,
            updatedAtEpochMillis = 1L,
        )
        val advancedSession = initialSession.copy(
            stage = FirstSessionStage.AwaitingChatFollowup,
            updatedAtEpochMillis = 2L,
        )
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), initialSession, pendingChat = pending),
                ),
            ),
        ).apply {
            chatExecutionHandler = { _, expectedPet, origin ->
                assertEquals(FirstSessionStage.AwaitingChat, origin)
                executionGate.await()
                WebChatExecutionResult.Success(
                    result = DashboardChatResult(
                        reply = "Очень приятно! $FirstSessionAfterName",
                        pet = expectedPet,
                        explicitPortions = listOf("Очень приятно!", FirstSessionAfterName),
                    ),
                    firstSession = advancedSession,
                    pendingChat = completed,
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val thinking = runtime.snapshot()
        val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }
        executionGate.complete(Unit)
        var snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { completionEvent.await() }.payload,
        )

        assertEquals(
            listOf("Очень приятно!", FirstSessionAfterName),
            snapshot.dashboard?.reply?.portions,
        )
        assertEquals(0, snapshot.dashboard?.reply?.portionIndex)
        snapshot = runtime.dispatch(
            dashboardCommand("REPLY_ADVANCE", snapshot.revision) {
                put("requestKey", requestKey)
            },
        )
        assertEquals(1, snapshot.dashboard?.reply?.portionIndex)
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision),
        )
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                put("mode", "chat")
            },
        )
        assertEquals(
            listOf("Очень приятно!", FirstSessionAfterName),
            snapshot.dashboard?.reply?.portions,
        )
        assertEquals(1, snapshot.dashboard?.reply?.portionIndex)
        runtime.close()

        val coldRuntime = runtime(
            FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        dashboard(pet(), advancedSession, pendingChat = completed),
                    ),
                ),
            ),
        )
        val cold = coldRuntime.snapshot()
        assertEquals(listOf("Очень приятно!"), cold.dashboard?.reply?.portions)
        assertEquals(0, cold.dashboard?.reply?.portionIndex)
        coldRuntime.close()
        assertEquals("chat", thinking.dashboardMode)
    }

    @Test
    fun recoveredFollowupChatPreservesMandatoryHungerPrompt() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174182"
        val pending = pendingChat(requestKey, "Читать")
        val initialSession = LocalFirstSession(
            ownerId = "owner-test",
            petId = "pet-1",
            stage = FirstSessionStage.AwaitingChatFollowup,
            updatedAtEpochMillis = 1L,
        )
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), initialSession, pendingChat = pending),
                ),
            ),
        ).apply {
            chatExecutionHandler = { _, expectedPet, origin ->
                assertEquals(FirstSessionStage.AwaitingChatFollowup, origin)
                executionGate.await()
                WebChatExecutionResult.Success(
                    result = DashboardChatResult(
                        reply = "Звучит здорово! $FirstSessionAfterChat",
                        pet = expectedPet,
                        explicitPortions = listOf("Звучит здорово!", FirstSessionAfterChat),
                    ),
                    firstSession = initialSession.copy(
                        stage = FirstSessionStage.AwaitingFirstFood,
                        updatedAtEpochMillis = 2L,
                    ),
                    pendingChat = pending.copy(
                        responseText = "Звучит здорово!",
                        completedAtEpochMillis = 2L,
                    ),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()
        val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }
        executionGate.complete(Unit)
        val completedSnapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { completionEvent.await() }.payload,
        )

        assertEquals(
            listOf("Звучит здорово!", FirstSessionAfterChat),
            completedSnapshot.dashboard?.reply?.portions,
        )
        assertEquals(
            FirstSessionStage.AwaitingFirstFood.storageValue,
            completedSnapshot.firstSession?.stage,
        )
        runtime.close()
    }

    @Test
    fun ordinaryRecoveredChatKeepsRawDurableReply() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174183"
        val pending = pendingChat(requestKey, "Обычный вопрос")
        val completed = pending.copy(
            responseText = "Обычный ответ",
            completedAtEpochMillis = 2L,
        )
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), pendingChat = pending),
                ),
            ),
        ).apply {
            chatExecutionHandler = { _, expectedPet, origin ->
                assertNull(origin)
                executionGate.await()
                WebChatExecutionResult.Success(
                    result = DashboardChatResult("Обычный ответ", expectedPet),
                    firstSession = null,
                    pendingChat = completed,
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()
        val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }
        executionGate.complete(Unit)
        var snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { completionEvent.await() }.payload,
        )
        assertEquals(listOf("Обычный ответ"), snapshot.dashboard?.reply?.portions)
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision),
        )
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                put("mode", "chat")
            },
        )
        assertEquals(listOf("Обычный ответ"), snapshot.dashboard?.reply?.portions)
        runtime.close()

        val coldRuntime = runtime(
            FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        dashboard(pet(), pendingChat = completed),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("Обычный ответ"),
            coldRuntime.snapshot().dashboard?.reply?.portions,
        )
        coldRuntime.close()
    }

    @Test
    fun completedDurableResponseOnRetryablePathIsPresentedWithoutError() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174184"
        val pending = pendingChat(requestKey, "Сообщение")
        val completed = pending.copy(
            responseText = "Ответ уже сохранён",
            completedAtEpochMillis = 2L,
        )
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), pendingChat = pending),
                ),
            ),
        ).apply {
            chatExecutionHandler = { _, _, _ ->
                executionGate.await()
                WebChatExecutionResult.RetryableFailure(completed)
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()
        val completionEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }
        executionGate.complete(Unit)
        val completedSnapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { completionEvent.await() }.payload,
        )

        assertFalse(completedSnapshot.dashboard?.chat?.thinking == true)
        assertNull(completedSnapshot.dashboard?.chat?.error)
        assertNull(completedSnapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(requestKey, completedSnapshot.dashboard?.reply?.requestKey)
        assertEquals(
            listOf("Ответ уже сохранён"),
            completedSnapshot.dashboard?.reply?.portions,
        )
        runtime.close()
    }

    @Test
    fun `two completed durable chats are presented and acknowledged in order`() = runBlocking {
        val first = pendingChat(
            "123e4567-e89b-42d3-a456-426614174121",
            "Первое",
            "Первый ответ",
        )
        val second = pendingChat(
            "123e4567-e89b-42d3-a456-426614174122",
            "Второе",
            "Второй ответ",
        ).copy(createdAtEpochMillis = 2L, completedAtEpochMillis = 3L)
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), pendingChat = first, queuedChat = second),
                ),
            ),
        )
        val runtime = runtime(gateway)
        var snapshot = runtime.snapshot()

        assertEquals(first.requestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(listOf("Первый ответ"), snapshot.dashboard?.reply?.portions)
        snapshot = runtime.dispatch(
            dashboardCommand("CHAT_REPLY_PRESENTED", snapshot.revision) {
                put("requestKey", first.requestKey)
            },
        )
        assertEquals(second.requestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(listOf("Второй ответ"), snapshot.dashboard?.reply?.portions)
        assertEquals(second.requestKey, snapshot.pending.chat?.requestKey)

        val stale = runCatching {
            runtime.dispatch(
                dashboardCommand("CHAT_REPLY_PRESENTED", snapshot.revision) {
                    put("requestKey", first.requestKey)
                },
            )
        }.exceptionOrNull() as WebAppRuntimeException
        assertEquals("WRONG_STAGE", stale.bridgeCode)
        assertEquals(listOf(first.requestKey), gateway.acknowledgedChats)

        snapshot = runtime.dispatch(
            dashboardCommand("CHAT_REPLY_PRESENTED", snapshot.revision) {
                put("requestKey", second.requestKey)
            },
        )
        assertEquals(second.requestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(listOf("Второй ответ"), snapshot.dashboard?.reply?.portions)
        assertNull(snapshot.pending.chat)
        assertEquals(
            listOf(first.requestKey, second.requestKey),
            gateway.acknowledgedChats,
        )
        runtime.close()
    }

    @Test
    fun `retryable chat never overwrites a newer draft across retry and foreground`() = runBlocking {
        val pending = pendingChat(
            "123e4567-e89b-42d3-a456-426614174123",
            "Исходное сообщение",
        )
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Unit>()
        val retryGate = CompletableDeferred<Unit>()
        val foregroundStarted = CompletableDeferred<Unit>()
        val foregroundGate = CompletableDeferred<Unit>()
        var attempt = 0
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(), pendingChat = pending)),
            ),
        ).apply {
            chatExecutionHandler = { _, expectedPet, _ ->
                attempt += 1
                when (attempt) {
                    1 -> {
                        firstStarted.complete(Unit)
                        firstGate.await()
                        WebChatExecutionResult.RetryableFailure(pending)
                    }
                    2 -> {
                        retryStarted.complete(Unit)
                        retryGate.await()
                        WebChatExecutionResult.RetryableFailure(pending)
                    }
                    else -> {
                        foregroundStarted.complete(Unit)
                        foregroundGate.await()
                        WebChatExecutionResult.Success(
                            DashboardChatResult("Готово", expectedPet),
                            null,
                            pending.copy(responseText = "Готово", completedAtEpochMillis = 2L),
                        )
                    }
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        withTimeout(1_000L) { firstStarted.await() }
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                put("mode", "chat")
                put("value", "Новый пользовательский черновик")
            },
        )

        var failureEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        firstGate.complete(Unit)
        snapshot = BridgeCodec.json.decodeFromJsonElement(
            withTimeout(1_000L) { failureEvent.await() }.payload,
        )
        assertEquals("Новый пользовательский черновик", snapshot.dashboard?.chat?.draft)
        assertEquals(ChatFailureMessage, snapshot.dashboard?.chat?.error)

        snapshot = runtime.dispatch(dashboardCommand("CHAT_RETRY", snapshot.revision))
        withTimeout(1_000L) { retryStarted.await() }
        assertEquals("Новый пользовательский черновик", snapshot.dashboard?.chat?.draft)
        failureEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        retryGate.complete(Unit)
        snapshot = BridgeCodec.json.decodeFromJsonElement(
            withTimeout(1_000L) { failureEvent.await() }.payload,
        )
        assertEquals("Новый пользовательский черновик", snapshot.dashboard?.chat?.draft)

        runtime.onForeground()
        withTimeout(1_000L) { foregroundStarted.await() }
        assertEquals(
            "Новый пользовательский черновик",
            runtime.snapshot().dashboard?.chat?.draft,
        )
        foregroundGate.complete(Unit)
        repeat(3) { yield() }
        assertEquals(
            "Новый пользовательский черновик",
            runtime.snapshot().dashboard?.chat?.draft,
        )
        runtime.close()
    }

    @Test
    fun `chat execution uses durable reservation origin instead of the live onboarding stage`() =
        runBlocking {
            val pet = pet()
            val liveSession = LocalFirstSession(
                ownerId = "owner-test",
                petId = pet.petId,
                stage = FirstSessionStage.AwaitingChatFollowup,
                updatedAtEpochMillis = 20L,
            )
            val executionOrigin = CompletableDeferred<FirstSessionStage?>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(pet, liveSession)),
                ),
            ).apply {
                chatReservationHandler = { request, expectedPet, _ ->
                    WebChatReservationResult.Pending(
                        pet = expectedPet,
                        pendingChat = LocalPendingChat(
                            "owner-test",
                            expectedPet.petId,
                            request.requestKey,
                            request.message,
                            1L,
                        ),
                        originFirstSessionStage = FirstSessionStage.AwaitingChat,
                    )
                }
                chatExecutionHandler = { _, _, origin ->
                    executionOrigin.complete(origin)
                    awaitCancellation()
                }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
            )
            val initial = runtime.snapshot()

            runtime.dispatch(dashboardCommand("CHAT_SEND", initial.revision) {
                put("message", "Сообщение после перезапуска")
            })

            assertEquals(FirstSessionStage.AwaitingChat, executionOrigin.await())
            assertTrue(
                gateway.chatReservationOrigins.all {
                    it == FirstSessionStage.AwaitingChatFollowup
                },
            )
            runtime.close()
        }

    @Test
    fun `close before recovered chat reservation runs keeps one durable execution`() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174101"
        val pending = pendingChat(requestKey, "Восстанови меня")
        val executionStarted = CompletableDeferred<Unit>()
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(), pendingChat = pending)),
            ),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                executionStarted.complete(Unit)
                executionGate.await()
                WebChatExecutionResult.Success(
                    DashboardChatResult("Восстановлено", expectedPet),
                    null,
                    pending.copy(responseText = "Восстановлено", completedAtEpochMillis = 2L),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(coroutineContext))
        val recovered = runtime.snapshot()

        assertTrue(gateway.chatReservations.isEmpty())
        val closed = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", recovered.revision),
        )
        assertEquals("idle", closed.dashboardMode)
        assertEquals(requestKey, closed.dashboard?.chat?.activeRequestKey)
        executionStarted.await()
        assertEquals(listOf(requestKey), gateway.chatExecutions.map { it.requestKey })

        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        executionGate.complete(Unit)
        val completed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { event.await() }.payload,
        )
        assertEquals("idle", completed.dashboardMode)
        assertNull(completed.dashboard?.reply)
        assertEquals("completed", completed.pending.chat?.status)
        val reopened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", completed.revision) { put("mode", "chat") },
        )
        assertEquals(listOf("Восстановлено"), reopened.dashboard?.reply?.portions)
        assertEquals(1, gateway.chatExecutions.size)
        runtime.close()
    }

    @Test
    fun `close after reservation and reopen neither cancels nor duplicates chat`() = runBlocking {
        val executionStarted = CompletableDeferred<Unit>()
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                executionStarted.complete(Unit)
                executionGate.await()
                WebChatExecutionResult.Success(
                    DashboardChatResult("Готово", expectedPet),
                    null,
                    pendingChat(request.requestKey, request.message, "Готово"),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        val send = dashboardCommand("CHAT_SEND", snapshot.revision) { put("message", "Привет") }
        snapshot = runtime.dispatch(send)
        executionStarted.await()

        snapshot = runtime.dispatch(dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision))
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) { put("mode", "chat") },
        )
        assertEquals(send.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(1, gateway.chatExecutions.size)

        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        executionGate.complete(Unit)
        val completed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { event.await() }.payload,
        )
        assertEquals(listOf("Готово"), completed.dashboard?.reply?.portions)
        assertEquals(1, gateway.chatExecutions.size)
        runtime.close()
    }

    @Test
    fun `chat retry reuses durable key and fences duplicate and stale commands`() = runBlocking {
        val requestKey = "123e4567-e89b-42d3-a456-426614174102"
        val pending = pendingChat(requestKey, "Не дублируй меня")
        val retryExecutionGate = CompletableDeferred<Unit>()
        var attempt = 0
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(), pendingChat = pending)),
            ),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                attempt += 1
                if (attempt == 1) {
                    WebChatExecutionResult.RetryableFailure(pending)
                } else {
                    retryExecutionGate.await()
                    WebChatExecutionResult.Success(
                        DashboardChatResult("Один ответ", expectedPet),
                        null,
                        pending.copy(responseText = "Один ответ", completedAtEpochMillis = 2L),
                    )
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        repeat(3) { yield() }
        snapshot = runtime.snapshot()
        assertEquals(1, attempt)
        assertEquals(requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(ChatFailureMessage, snapshot.dashboard?.chat?.error)
        assertEquals(pending.message, snapshot.dashboard?.chat?.draft)
        assertFalse(snapshot.dashboard?.chat?.thinking == true)

        val retryableRevision = snapshot.revision
        val invalidPayload = runCatching {
            runtime.dispatch(dashboardCommand("CHAT_RETRY", retryableRevision) {
                put("message", "Не создавай новый запрос")
            })
        }.exceptionOrNull() as WebAppRuntimeException
        assertEquals("INVALID_PAYLOAD", invalidPayload.bridgeCode)
        assertEquals(1, attempt)

        val retry = dashboardCommand("CHAT_RETRY", retryableRevision)
        snapshot = runtime.dispatch(retry)

        assertEquals(2, attempt)
        assertEquals(listOf(requestKey, requestKey), gateway.chatExecutions.map { it.requestKey })
        assertEquals(listOf(requestKey, requestKey), gateway.chatReservations.map { it.requestKey })
        assertNull(snapshot.dashboard?.chat?.error)
        assertEquals(pending.message, snapshot.dashboard?.chat?.draft)
        assertTrue(snapshot.dashboard?.chat?.thinking == true)

        val duplicate = runtime.dispatch(retry)
        assertEquals(snapshot.revision, duplicate.revision)
        assertEquals(2, attempt)
        val stale = runCatching {
            runtime.dispatch(dashboardCommand("CHAT_RETRY", retryableRevision))
        }.exceptionOrNull() as WebAppRuntimeException
        assertEquals("STATE_CONFLICT", stale.bridgeCode)
        assertEquals(2, attempt)

        retryExecutionGate.complete(Unit)
        repeat(3) { yield() }
        snapshot = runtime.snapshot()
        assertEquals(listOf("Один ответ"), snapshot.dashboard?.reply?.portions)
        runtime.close()
    }

    @Test
    fun `chat retry preserves queued request and schedules each durable identity once`() = runBlocking {
        val first = pendingChat(
            "123e4567-e89b-42d3-a456-426614174103",
            "Первое сообщение",
        )
        val firstStarted = CompletableDeferred<Unit>()
        val firstFailureGate = CompletableDeferred<Unit>()
        val firstRetryGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var firstAttempts = 0
        lateinit var second: BridgeProductCommand
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(), pendingChat = first)),
            ),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                when (request.requestKey) {
                    first.requestKey -> {
                        firstAttempts += 1
                        if (firstAttempts == 1) {
                            firstStarted.complete(Unit)
                            firstFailureGate.await()
                            WebChatExecutionResult.RetryableFailure(first)
                        } else {
                            firstRetryGate.await()
                            WebChatExecutionResult.Success(
                                DashboardChatResult("Первый ответ", expectedPet),
                                null,
                                first.copy(
                                    responseText = "Первый ответ",
                                    completedAtEpochMillis = 2L,
                                ),
                            )
                        }
                    }
                    second.requestKey -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        WebChatExecutionResult.Success(
                            DashboardChatResult("Второй ответ", expectedPet),
                            null,
                            pendingChat(
                                second.requestKey,
                                "Второе сообщение",
                                "Второй ответ",
                            ),
                        )
                    }
                    else -> error("Unexpected chat identity ${request.requestKey}")
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        withTimeout(1_000L) { firstStarted.await() }
        second = dashboardCommand("CHAT_SEND", snapshot.revision) {
            put("message", "Второе сообщение")
        }
        snapshot = runtime.dispatch(second)
        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(second.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)

        val failedEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        firstFailureGate.complete(Unit)
        snapshot = BridgeCodec.json.decodeFromJsonElement(
            withTimeout(1_000L) { failedEvent.await() }.payload,
        )
        assertEquals(ChatFailureMessage, snapshot.dashboard?.chat?.error)
        assertEquals(first.message, snapshot.dashboard?.chat?.draft)
        assertEquals(second.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertFalse(snapshot.dashboard?.chat?.thinking == true)

        snapshot = runtime.dispatch(dashboardCommand("CHAT_RETRY", snapshot.revision))
        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(second.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertEquals(2, firstAttempts)
        assertEquals(
            listOf(first.requestKey, first.requestKey),
            gateway.chatExecutions.map { it.requestKey },
        )

        firstRetryGate.complete(Unit)
        withTimeout(1_000L) { secondStarted.await() }
        assertEquals(
            listOf(first.requestKey, first.requestKey, second.requestKey),
            gateway.chatExecutions.map { it.requestKey },
        )
        assertEquals(
            listOf(first.requestKey, second.requestKey, first.requestKey),
            gateway.chatReservations.map { it.requestKey },
        )

        secondGate.complete(Unit)
        repeat(3) { yield() }
        runtime.close()
    }

    @Test
    fun `foreground refresh projects and starts one authoritative pending chat`() = runBlocking {
        val pending = pendingChat(
            "123e4567-e89b-42d3-a456-426614174104",
            "Вернись из Room",
        )
        val executionStarted = CompletableDeferred<Unit>()
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            foregroundRefreshHandler = {
                WebDashboardForegroundRefreshResult.Updated(
                    WebDashboardRecovery(
                        destination = dashboard(pet(), pendingChat = pending),
                        operations = WebDashboardOperationState(),
                    ),
                )
            }
            chatExecutionHandler = { _, expectedPet, _ ->
                executionStarted.complete(Unit)
                executionGate.await()
                WebChatExecutionResult.Success(
                    DashboardChatResult("После foreground", expectedPet),
                    null,
                    pending.copy(
                        responseText = "После foreground",
                        completedAtEpochMillis = 2L,
                    ),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        assertNull(runtime.snapshot().dashboard?.chat?.activeRequestKey)

        runtime.onForeground()
        executionStarted.await()
        assertEquals(pending.requestKey, runtime.snapshot().dashboard?.chat?.activeRequestKey)
        runtime.onForeground()
        repeat(3) { yield() }
        assertEquals(1, gateway.chatExecutions.size)

        executionGate.complete(Unit)
        repeat(3) { yield() }
        val completed = runtime.snapshot()
        assertEquals("completed", completed.pending.chat?.status)
        val reopened = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", completed.revision) { put("mode", "chat") },
        )
        assertEquals(
            listOf("После foreground"),
            reopened.dashboard?.reply?.portions,
        )
        runtime.close()
    }

    @Test
    fun `runtime reload resumes the same durable chat request key`() = runBlocking {
        val pending = pendingChat(
            "123e4567-e89b-42d3-a456-426614174105",
            "Перезапусти меня",
        )
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        var attempt = 0
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet(), pendingChat = pending)),
            ),
        ).apply {
            chatExecutionHandler = { _, expectedPet, _ ->
                attempt += 1
                if (attempt == 1) {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.complete(Unit)
                    }
                }
                WebChatExecutionResult.Success(
                    DashboardChatResult("После reload", expectedPet),
                    null,
                    pending.copy(responseText = "После reload", completedAtEpochMillis = 2L),
                )
            }
        }
        val firstRuntime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        firstRuntime.snapshot()
        firstStarted.await()
        firstRuntime.close()
        firstCancelled.await()

        val reloaded = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        reloaded.snapshot()
        repeat(3) { yield() }
        val completed = reloaded.snapshot()

        assertEquals(2, attempt)
        assertEquals(
            listOf(pending.requestKey, pending.requestKey),
            gateway.chatExecutions.map { it.requestKey },
        )
        assertEquals(listOf("После reload"), completed.dashboard?.reply?.portions)
        reloaded.close()
    }

    @Test
    fun `process rebootstrap executes two durable chats once each in order`() = runBlocking {
        val first = pendingChat(
            "123e4567-e89b-42d3-a456-426614174124",
            "Первое после перезапуска",
        )
        val second = pendingChat(
            "123e4567-e89b-42d3-a456-426614174125",
            "Второе после перезапуска",
        ).copy(createdAtEpochMillis = 2L)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.LocalDataError,
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), pendingChat = first, queuedChat = second),
                ),
            ),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                when (request.requestKey) {
                    first.requestKey -> {
                        firstStarted.complete(Unit)
                        firstGate.await()
                        WebChatExecutionResult.Success(
                            DashboardChatResult("Первый ответ", expectedPet),
                            null,
                            first.copy(
                                responseText = "Первый ответ",
                                completedAtEpochMillis = 3L,
                            ),
                        )
                    }
                    second.requestKey -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        WebChatExecutionResult.Success(
                            DashboardChatResult("Второй ответ", expectedPet),
                            null,
                            second.copy(
                                responseText = "Второй ответ",
                                completedAtEpochMillis = 4L,
                            ),
                        )
                    }
                    else -> error("Unexpected chat ${request.requestKey}")
                }
            }
        }
        val beforeKill = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        assertEquals("localDataError", beforeKill.snapshot().route)
        beforeKill.close()

        val restarted = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = restarted.snapshot()
        withTimeout(1_000L) { firstStarted.await() }
        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(second.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertEquals(listOf(first.requestKey), gateway.chatExecutions.map { it.requestKey })

        firstGate.complete(Unit)
        withTimeout(1_000L) { secondStarted.await() }
        snapshot = restarted.snapshot()
        assertEquals(second.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(
            listOf(first.requestKey, second.requestKey),
            gateway.chatExecutions.map { it.requestKey },
        )
        assertEquals(
            listOf(first.requestKey, second.requestKey),
            gateway.chatReservations.map { it.requestKey },
        )

        secondGate.complete(Unit)
        repeat(3) { yield() }
        snapshot = restarted.snapshot()
        assertEquals(first.requestKey, snapshot.dashboard?.reply?.requestKey)
        assertEquals(1, gateway.chatExecutions.count { it.requestKey == first.requestKey })
        assertEquals(1, gateway.chatExecutions.count { it.requestKey == second.requestKey })
        restarted.close()
    }

    @Test
    fun `stale replaced chat replay cannot overwrite durable queue or newer draft`() = runBlocking {
        val active = pendingChat(
            "123e4567-e89b-42d3-a456-426614174126",
            "Активное",
        )
        val latest = pendingChat(
            "123e4567-e89b-42d3-a456-426614174127",
            "Последнее в очереди",
        ).copy(createdAtEpochMillis = 2L)
        val replacedKey = "123e4567-e89b-42d3-a456-426614174128"
        val executionGate = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(
                    dashboard(pet(), pendingChat = active, queuedChat = latest),
                ),
            ),
        ).apply {
            chatReservationHandler = { request, expectedPet, origin ->
                if (request.requestKey == replacedKey) {
                    WebChatReservationResult.Finished(expectedPet)
                } else {
                    WebChatReservationResult.Pending(
                        expectedPet,
                        active,
                        origin,
                    )
                }
            }
            chatExecutionHandler = { _, _, _ ->
                executionGate.await()
                WebChatExecutionResult.RetryableFailure(active)
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                put("mode", "chat")
                put("value", "Не потерять этот черновик")
            },
        )
        val stale = BridgeProductCommand(
            type = "CHAT_SEND",
            requestKey = replacedKey,
            expectedSnapshotRevision = snapshot.revision,
            payload = buildJsonObject { put("message", "Устаревшее") },
        )

        snapshot = runtime.dispatch(stale)

        assertEquals(active.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(latest.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertEquals("Не потерять этот черновик", snapshot.dashboard?.chat?.draft)
        assertEquals(
            listOf(active.requestKey, replacedKey),
            gateway.chatReservations.map { it.requestKey },
        )
        assertEquals(listOf(active.requestKey), gateway.chatExecutions.map { it.requestKey })
        runtime.close()
    }

    @Test
    fun `chat runs active plus latest queued and fences the replaced request`() = runBlocking {
        val executions = mutableMapOf<String, CompletableDeferred<WebChatExecutionResult>>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                executions.getOrPut(request.requestKey) { CompletableDeferred() }.await()
            }
        }
        val runtime = runtime(
            gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
        )
        var snapshot = runtime.snapshot()
        val first = dashboardCommand("CHAT_SEND", snapshot.revision) { put("message", "Первое") }
        snapshot = runtime.dispatch(first)
        val replaced = dashboardCommand("CHAT_SEND", snapshot.revision) { put("message", "Второе") }
        snapshot = runtime.dispatch(replaced)
        val latest = dashboardCommand("CHAT_SEND", snapshot.revision) { put("message", "Третье") }
        snapshot = runtime.dispatch(latest)

        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(latest.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertEquals(
            listOf(null, first.requestKey, first.requestKey),
            gateway.chatReservationAnchors,
        )
        assertEquals(
            listOf(null, null, replaced.requestKey),
            gateway.chatReservationReplacements,
        )
        assertEquals(
            listOf(first.requestKey, replaced.requestKey, latest.requestKey),
            gateway.chatReservations.map { it.requestKey },
        )
        snapshot = runtime.dispatch(dashboardCommand("DASHBOARD_CLOSE_MODE", snapshot.revision))
        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(latest.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) { put("mode", "chat") },
        )
        assertEquals(first.requestKey, snapshot.dashboard?.chat?.activeRequestKey)
        assertEquals(latest.requestKey, snapshot.dashboard?.chat?.queuedRequestKey)
        assertEquals(1, gateway.chatExecutions.size)
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.take(2).toList()
        }
        executions.getValue(first.requestKey).complete(
            chatSuccess(first, "Ответ первый"),
        )
        while (latest.requestKey !in executions) kotlinx.coroutines.yield()
        executions.getValue(latest.requestKey).complete(
            chatSuccess(latest, "Ответ третий"),
        )
        val snapshots = withTimeout(1_000) { events.await() }.map {
            BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(it.payload)
        }

        assertEquals(listOf(first.requestKey, latest.requestKey), gateway.chatExecutions.map { it.requestKey })
        assertFalse(gateway.chatExecutions.any { it.requestKey == replaced.requestKey })
        assertTrue(snapshots.first().dashboard?.chat?.thinking == true)
        assertEquals(latest.requestKey, snapshots.first().dashboard?.chat?.activeRequestKey)
        assertEquals(listOf("Ответ первый"), snapshots.last().dashboard?.reply?.portions)
        var presented = runtime.dispatch(
            dashboardCommand("CHAT_REPLY_PRESENTED", snapshots.last().revision) {
                put("requestKey", first.requestKey)
            },
        )
        assertEquals(latest.requestKey, presented.dashboard?.reply?.requestKey)
        assertEquals(listOf("Ответ третий"), presented.dashboard?.reply?.portions)
        presented = runtime.dispatch(
            dashboardCommand("CHAT_REPLY_PRESENTED", presented.revision) {
                put("requestKey", latest.requestKey)
            },
        )
        assertEquals(latest.requestKey, presented.dashboard?.reply?.requestKey)
        assertEquals(listOf("Ответ третий"), presented.dashboard?.reply?.portions)
        assertNull(presented.pending.chat)
        runtime.close()
    }

    @Test
    fun `authoritative refresh fences a stale chat completion`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCompletion = CompletableDeferred<Unit>()
        val secondCompletion = CompletableDeferred<Unit>()
        val replacement = pendingChat(
            "123e4567-e89b-42d3-a456-426614174103",
            "Новый",
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Dashboard(dashboard(pet()))),
        ).apply {
            chatExecutionHandler = { request, expectedPet, _ ->
                if (request.message == "Старый") {
                    firstStarted.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        firstCompletion.await()
                    }
                    WebChatExecutionResult.Success(
                        DashboardChatResult("Старый ответ", expectedPet),
                        null,
                        null,
                    )
                } else {
                    secondCompletion.await()
                    WebChatExecutionResult.Success(
                        DashboardChatResult("Новый ответ", expectedPet),
                        null,
                        replacement.copy(
                            responseText = "Новый ответ",
                            completedAtEpochMillis = 2L,
                        ),
                    )
                }
            }
            foregroundRefreshHandler = {
                WebDashboardForegroundRefreshResult.Updated(
                    WebDashboardRecovery(
                        destination = dashboard(pet(), pendingChat = replacement),
                        operations = WebDashboardOperationState(),
                    ),
                )
            }
        }
        val runtime = runtime(
            gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
        )
        var snapshot = runtime.snapshot()
        snapshot = runtime.dispatch(
            dashboardCommand("CHAT_SEND", snapshot.revision) { put("message", "Старый") },
        )
        firstStarted.await()
        runtime.onForeground()
        while (replacement.requestKey !in gateway.chatExecutions.map { it.requestKey }) yield()
        secondCompletion.complete(Unit)
        repeat(3) { yield() }
        val latest = runtime.snapshot()
        assertEquals(listOf("Новый ответ"), latest.dashboard?.reply?.portions)

        firstCompletion.complete(Unit)
        repeat(3) { kotlinx.coroutines.yield() }
        val afterStaleCompletion = runtime.snapshot()
        assertEquals(listOf("Новый ответ"), afterStaleCompletion.dashboard?.reply?.portions)
        assertEquals(
            listOf(replacement.requestKey),
            gateway.chatExecutions.map { it.requestKey }.filter { it == replacement.requestKey },
        )
        runtime.close()
    }

    @Test
    fun `rapid berry then leaf persists cumulative stats latest reply and feedback once`() =
        runBlocking {
            val delayGates = mutableListOf<CompletableDeferred<Unit>>()
            val requestedDelays = mutableListOf<Long>()
            val feedback = mutableListOf<Int>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        dashboard(pet(hunger = 40, energy = 30)),
                    ),
                ),
            )
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                delayMillis = { requested ->
                    requestedDelays += requested
                    CompletableDeferred<Unit>().also(delayGates::add).await()
                },
                onFeedFeedback = feedback::add,
            )
            var snapshot = runtime.snapshot()
            val berry = dashboardCommand("FEED_CONSUME", snapshot.revision) {
                put("food", "berry-bowl")
            }
            snapshot = runtime.dispatch(berry)
            val leaf = dashboardCommand("FEED_CONSUME", snapshot.revision) {
                put("food", "leaf-crunch")
            }
            snapshot = runtime.dispatch(leaf)

            assertEquals(65, snapshot.pet?.hunger)
            assertEquals(55, snapshot.pet?.energy)
            assertEquals(2, snapshot.dashboard?.feed?.pulseId)
            assertEquals(listOf(0, 1), feedback)
            assertEquals(listOf(1_000L, 1_000L), requestedDelays)
            val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            delayGates.forEach { it.complete(Unit) }
            val replied = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000) { event.await() }.payload,
            )
            assertEquals("feed", replied.dashboard?.reply?.source)
            assertEquals(listOf(LeafReply), replied.dashboard?.reply?.portions)

            val replay = runtime.dispatch(berry)
            assertEquals(2, gateway.feedApplications.size)
            assertEquals(listOf(0, 1), feedback)
            assertEquals(65, replay.pet?.hunger)
            assertEquals(55, replay.pet?.energy)
            val conflict = runCatching {
                runtime.dispatch(
                    berry.copy(
                        expectedSnapshotRevision = replay.revision,
                        payload = buildJsonObject { put("food", "leaf-crunch") },
                    ),
                )
            }.exceptionOrNull()
            assertEquals("INVALID_PAYLOAD", (conflict as WebAppRuntimeException).bridgeCode)
            assertEquals(2, gateway.feedApplications.size)
            runtime.close()
        }

    @Test
    fun `process rebootstrap recovery drops stale outfit reply and starts one fresh ambient`() =
        runBlocking {
            val initialPet = pet()
            val recoveredOutfit = pendingOutfit(
                PendingOutfitRequest("recovered-outfit", "красный шарф"),
                initialPet,
                PendingBackendState.Attached,
            ).copy(
                backendJobId = "outfit-job",
                preparedDisplayItem = "Красный шарф",
            )
            val failedOutfit = recoveredOutfit.copy(
                backendState = PendingBackendState.Failed,
            )
            val recoveryStarted = CompletableDeferred<Unit>()
            val recoveryRelease = CompletableDeferred<Unit>()
            val ambientStarted = CompletableDeferred<Unit>()
            val ambientRelease = CompletableDeferred<Unit>()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        destination = dashboard(initialPet).copy(
                            pendingOutfit = recoveredOutfit,
                        ),
                        operations = WebDashboardOperationState(outfit = recoveredOutfit),
                    ),
                ),
            ).apply {
                dashboardRefreshHandler = {
                    recoveryStarted.complete(Unit)
                    recoveryRelease.await()
                    WebDashboardOperationExecutionResult.Updated(
                        WebDashboardRecovery(
                            destination = dashboard(initialPet),
                            operations = WebDashboardOperationState(outfit = failedOutfit),
                        ),
                    )
                }
                ambientGenerationHandler = { _, expectedPet ->
                    ambientStarted.complete(Unit)
                    ambientRelease.await()
                    val reply = "Свежая фоновая реплика"
                    WebAmbientGenerationResult.Success(
                        DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                    )
                }
            }
            val runtime = runtime(
                gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                delayMillis = { awaitCancellation() },
            )
            val bootstrapped = runtime.snapshot()
            withTimeout(1_000L) { recoveryStarted.await() }

            assertEquals("transient", bootstrapped.dashboard?.reply?.source)
            assertEquals(recoveredOutfit.requestKey, bootstrapped.dashboard?.reply?.requestKey)
            assertEquals("attached", bootstrapped.dashboard?.outfit?.pending?.status)

            runtime.onForeground()
            repeat(3) { yield() }
            assertTrue(gateway.ambientGenerationRequests.isEmpty())
            val recoveryEvent = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.events.first()
            }

            recoveryRelease.complete(Unit)
            val cleared = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { recoveryEvent.await() }.payload,
            )

            assertNull(cleared.pending.outfit)
            assertEquals("failed", cleared.dashboard?.outfit?.pending?.status)
            assertEquals(OutfitFailureMessage, cleared.dashboard?.outfit?.error)
            assertNull(cleared.dashboard?.reply)
            withTimeout(1_000L) { ambientStarted.await() }
            assertEquals(1, gateway.ambientGenerationRequests.size)

            val ambientEvent = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.events.first()
            }
            ambientRelease.complete(Unit)
            val ambient = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { ambientEvent.await() }.payload,
            )

            assertEquals("transient", ambient.dashboard?.reply?.source)
            assertEquals(listOf("Свежая фоновая реплика"), ambient.dashboard?.reply?.portions)
            withTimeout(1_000L) {
                while (gateway.ambientPersistenceRequests.size < 1) yield()
            }
            repeat(3) { runtime.snapshot() }
            runtime.onForeground()
            repeat(3) { yield() }

            assertEquals(1, gateway.dashboardRefreshes.size)
            assertEquals(1, gateway.ambientGenerationRequests.size)
            assertEquals(1, gateway.ambientPersistenceRequests.size)
            runtime.close()
        }

    @Test
    fun `closing an ordinary feed reply does not create an ambient activation`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()
        val feed = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", initial.revision) {
                put("mode", "feed")
            },
        )

        runtime.onForeground()
        repeat(3) { yield() }
        assertTrue(gateway.ambientGenerationRequests.isEmpty())

        val replyEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }
        runtime.dispatch(
            dashboardCommand("FEED_CONSUME", feed.revision) {
                put("food", "berry-bowl")
            },
        )
        val replied = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { replyEvent.await() }.payload,
        )
        assertEquals("feed", replied.dashboard?.reply?.source)
        assertEquals(listOf(BerryReply), replied.dashboard?.reply?.portions)

        val closed = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", replied.revision),
        )
        assertEquals("idle", closed.dashboardMode)
        assertNull(closed.dashboard?.reply)
        repeat(3) { runtime.snapshot() }
        runtime.onForeground()
        repeat(3) { yield() }

        assertTrue(gateway.ambientGenerationRequests.isEmpty())
        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        runtime.close()
    }

    @Test
    fun `ambient initial foreground activation emits once with uuid and ignores duplicate reads`() =
        runBlocking {
            val initialPet = pet()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                ),
            ).apply {
                ambientGenerationHandler = { _, expectedPet ->
                    val reply = "Новая фоновая реплика"
                    WebAmbientGenerationResult.Success(
                        DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                    )
                }
            }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            runtime.snapshot()
            val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

            runtime.onForeground()

            val changed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { event.await() }.payload,
            )
            withTimeout(1_000L) {
                while (gateway.ambientPersistenceRequests.size < 1) yield()
            }
            val (requestKey, expectedPet) = gateway.ambientGenerationRequests.single()
            val parsedRequestKey = UUID.fromString(requestKey)
            assertEquals(4, parsedRequestKey.version())
            assertEquals(2, parsedRequestKey.variant())
            assertEquals(initialPet, expectedPet)
            assertEquals("Новая фоновая реплика", changed.pet?.message)
            assertEquals("transient", changed.dashboard?.reply?.source)
            assertEquals(listOf("Новая фоновая реплика"), changed.dashboard?.reply?.portions)
            assertEquals(requestKey, changed.dashboard?.reply?.requestKey)

            var latest = runtime.snapshot()
            latest = runtime.dispatch(petTap(latest.revision))
            runtime.snapshot()
            runtime.onForeground()
            repeat(3) { yield() }

            assertEquals(1, gateway.ambientGenerationRequests.size)
            assertEquals(1, gateway.ambientPersistenceRequests.size)
            assertEquals(1, latest.pet?.petTapProgress)
            runtime.close()
        }

    @Test
    fun `ambient does not run during onboarding`() = runBlocking {
        val initialPet = pet()
        val session = LocalFirstSession(
            ownerId = "owner-test",
            petId = initialPet.petId,
            stage = FirstSessionStage.AwaitingChat,
            updatedAtEpochMillis = 1L,
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet, session)),
            ),
        )
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))

        runtime.snapshot()
        runtime.onForeground()
        repeat(3) { yield() }
        runtime.snapshot()
        runtime.onBackground()
        runtime.onForeground()
        repeat(3) { yield() }

        assertTrue(gateway.ambientGenerationRequests.isEmpty())
        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        runtime.close()
    }

    @Test
    fun `ambient generation failure is quiet and consumes its activation`() = runBlocking {
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        )
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        runtime.onForeground()
        repeat(3) { yield() }
        val latest = runtime.snapshot()

        assertEquals(initial.revision, latest.revision)
        assertNull(latest.dashboard?.reply)
        assertEquals(1, gateway.ambientGenerationRequests.size)
        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        runtime.onForeground()
        repeat(3) { yield() }
        assertEquals(1, gateway.ambientGenerationRequests.size)
        runtime.close()
    }

    @Test
    fun `ambient gets a new activation when first session becomes completed`() = runBlocking {
        val initialPet = pet()
        val session = LocalFirstSession(
            ownerId = "owner-test",
            petId = initialPet.petId,
            stage = FirstSessionStage.AwaitingCompletionMessage,
            updatedAtEpochMillis = 1L,
        )
        val completed = session.copy(
            stage = FirstSessionStage.Completed,
            updatedAtEpochMillis = 2L,
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet, session)),
            ),
        ).apply {
            foregroundRefreshHandler = {
                WebDashboardForegroundRefreshResult.Updated(
                    WebDashboardRecovery(
                        destination = dashboard(initialPet, completed),
                        operations = WebDashboardOperationState(),
                    ),
                )
            }
            ambientGenerationHandler = { _, expectedPet ->
                val reply = "Онбординг завершён"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))

        runtime.snapshot()
        runtime.onForeground()
        withTimeout(1_000L) {
            while (
                gateway.ambientGenerationRequests.size < 1 ||
                gateway.ambientPersistenceRequests.size < 1
            ) {
                yield()
            }
        }
        val latest = runtime.snapshot()

        assertEquals(1, gateway.ambientGenerationRequests.size)
        assertEquals(FirstSessionStage.Completed.storageValue, latest.firstSession?.stage)
        assertEquals(listOf("Онбординг завершён"), latest.dashboard?.reply?.portions)
        runtime.close()
    }

    @Test
    fun `late ambient result cannot interrupt an opened dashboard mode`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val initialPet = pet()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            ambientGenerationHandler = { _, expectedPet ->
                started.complete(Unit)
                release.await()
                val reply = "Уже не вовремя"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        runtime.onForeground()
        withTimeout(1_000L) { started.await() }
        val outfit = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", initial.revision) {
                put("mode", "outfit")
            },
        )
        release.complete(Unit)
        repeat(3) { yield() }
        val afterLateResult = runtime.snapshot()

        assertEquals("outfit", afterLateResult.dashboardMode)
        assertEquals(initialPet.message, afterLateResult.pet?.message)
        assertNull(afterLateResult.dashboard?.reply)
        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        val closed = runtime.dispatch(
            dashboardCommand("DASHBOARD_CLOSE_MODE", outfit.revision),
        )
        runtime.snapshot()
        repeat(3) { yield() }
        assertEquals("idle", closed.dashboardMode)
        assertEquals(1, gateway.ambientGenerationRequests.size)
        runtime.close()
    }

    @Test
    fun `route cancellation discards ambient and dashboard return starts one new attempt`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initialPet = pet()
            val eventGateway = RuntimeEventGateway(initialPet)
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                ),
            ).apply {
                this.eventGateway = eventGateway
                ambientGenerationHandler = { _, expectedPet ->
                    if (ambientGenerationRequests.size == 1) {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                        }
                        val reply = "Поздний ответ с другого экрана"
                        WebAmbientGenerationResult.Success(
                            DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                        )
                    } else {
                        WebAmbientGenerationResult.Failure
                    }
                }
            }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            val initial = runtime.snapshot()

            runtime.onForeground()
            withTimeout(1_000L) { started.await() }
            val events = runtime.dispatch(dashboardCommand("NAVIGATE", initial.revision) {
                put("route", "events")
            })
            assertEquals("events", events.route)
            release.complete(Unit)
            repeat(3) { yield() }

            val dashboard = runtime.dispatch(dashboardCommand("BACK", events.revision))
            withTimeout(1_000L) {
                while (gateway.ambientGenerationRequests.size < 2) yield()
            }
            assertEquals("dashboard", dashboard.route)
            assertEquals(initialPet.message, dashboard.pet?.message)
            assertTrue(gateway.ambientPersistenceRequests.isEmpty())
            assertEquals(2, gateway.ambientGenerationRequests.size)
            runtime.close()
        }

    @Test
    fun `background cancellation discards ambient and next foreground retries once`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initialPet = pet()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                ),
            ).apply {
                ambientGenerationHandler = { _, expectedPet ->
                    if (ambientGenerationRequests.size == 1) {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                        }
                        val reply = "Поздний ответ из фона"
                        WebAmbientGenerationResult.Success(
                            DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                        )
                    } else {
                        WebAmbientGenerationResult.Failure
                    }
                }
            }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            runtime.snapshot()

            runtime.onForeground()
            withTimeout(1_000L) { started.await() }
            runtime.onBackground()
            release.complete(Unit)
            repeat(3) { yield() }
            runtime.onForeground()
            withTimeout(1_000L) {
                while (gateway.ambientGenerationRequests.size < 2) yield()
            }
            val latest = runtime.snapshot()

            assertEquals(initialPet.message, latest.pet?.message)
            assertNull(latest.dashboard?.reply)
            assertTrue(gateway.ambientPersistenceRequests.isEmpty())
            assertEquals(2, gateway.ambientGenerationRequests.size)
            runtime.close()
        }

    @Test
    fun `ambient persistence merge preserves newer stats and rejects a newer message`() {
        val expected = pet(hunger = 80, progress = 0)
        val latestStats = expected.copy(hunger = 55, petTapProgress = 3)
        val reply = "Сохранённая фоновая реплика"

        val merged = mergeAmbientReplyOntoLatest(latestStats, expected, reply)
        val idempotent = mergeAmbientReplyOntoLatest(
            requireNotNull(merged),
            expected,
            reply,
        )
        val conflict = mergeAmbientReplyOntoLatest(
            latestStats.copy(message = "Более новый ответ чата"),
            expected,
            reply,
        )

        assertEquals(55, merged?.hunger)
        assertEquals(3, merged?.petTapProgress)
        assertEquals(reply, merged?.message)
        assertEquals(merged, idempotent)
        assertNull(conflict)
    }

    @Test
    fun `ambient persistence retries twice without hiding successful reply`() = runBlocking {
        val delays = mutableListOf<Long>()
        val initialPet = pet()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            ambientGenerationHandler = { _, expectedPet ->
                val reply = "Видимая даже без сохранения реплика"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
            ambientPersistenceHandler = { _, _ ->
                WebAmbientPersistenceResult.LocalDataError
            }
        }
        val runtime = runtime(
            gateway = gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            delayMillis = { delays += it },
        )
        runtime.snapshot()
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        runtime.onForeground()

        val changed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { event.await() }.payload,
        )
        withTimeout(1_000L) {
            while (gateway.ambientPersistenceRequests.size < 2) yield()
        }
        val latest = runtime.snapshot()

        assertEquals(2, gateway.ambientPersistenceRequests.size)
        assertEquals(listOf(DashboardSaveRetryDelayMillis), delays)
        assertEquals(changed.revision, latest.revision)
        assertEquals(
            listOf("Видимая даже без сохранения реплика"),
            latest.dashboard?.reply?.portions,
        )
        assertEquals("Видимая даже без сохранения реплика", latest.pet?.message)
        runtime.close()
    }

    @Test
    fun `ambient persistence survives background while waiting to retry`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val retryGate = CompletableDeferred<Unit>()
        val delays = mutableListOf<Long>()
        val initialPet = pet()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            ambientGenerationHandler = { _, expectedPet ->
                val reply = "Реплика перед фоном"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
            ambientPersistenceHandler = { _, result ->
                if (ambientPersistenceRequests.size == 1) {
                    WebAmbientPersistenceResult.LocalDataError
                } else {
                    WebAmbientPersistenceResult.Applied(result.pet)
                }
            }
        }
        val runtime = runtime(
            gateway = gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            delayMillis = { delay ->
                delays += delay
                retryStarted.complete(Unit)
                retryGate.await()
            },
        )
        runtime.snapshot()
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        runtime.onForeground()
        withTimeout(1_000L) { event.await() }
        withTimeout(1_000L) { retryStarted.await() }
        runtime.onBackground()
        retryGate.complete(Unit)
        withTimeout(1_000L) {
            while (gateway.ambientPersistenceRequests.size < 2) yield()
        }

        assertEquals(2, gateway.ambientPersistenceRequests.size)
        assertEquals(listOf(DashboardSaveRetryDelayMillis), delays)
        runtime.close()
    }

    @Test
    fun `closing runtime cancels in flight ambient without persistence`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        ).apply {
            ambientGenerationHandler = { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()

        runtime.onForeground()
        withTimeout(1_000L) { started.await() }
        runtime.close()
        withTimeout(1_000L) { cancelled.await() }

        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        assertEquals(1, gateway.closeCalls)
    }

    @Test
    fun `closing runtime cancels ambient persistence retry`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val retryCancelled = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        ).apply {
            ambientGenerationHandler = { _, expectedPet ->
                val reply = "Реплика перед закрытием"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
            ambientPersistenceHandler = { _, _ ->
                WebAmbientPersistenceResult.LocalDataError
            }
        }
        val runtime = runtime(
            gateway = gateway,
            generationScope = CoroutineScope(Dispatchers.Unconfined),
            delayMillis = {
                retryStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    retryCancelled.complete(Unit)
                }
            },
        )
        runtime.snapshot()
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        runtime.onForeground()
        withTimeout(1_000L) { event.await() }
        withTimeout(1_000L) { retryStarted.await() }
        runtime.close()
        withTimeout(1_000L) { retryCancelled.await() }

        assertEquals(1, gateway.ambientPersistenceRequests.size)
        assertEquals(1, gateway.closeCalls)
    }

    @Test
    fun `newer pet state wins over late ambient result`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val initialPet = pet(progress = 0)
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            ambientGenerationHandler = { _, expectedPet ->
                started.complete(Unit)
                release.await()
                val reply = "Устаревшая реплика"
                WebAmbientGenerationResult.Success(
                    DashboardAmbientResult(reply, expectedPet.copy(message = reply)),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        runtime.onForeground()
        withTimeout(1_000L) { started.await() }
        val tapped = runtime.dispatch(petTap(initial.revision))
        release.complete(Unit)
        repeat(3) { yield() }
        val latest = runtime.snapshot()

        assertEquals(1, tapped.pet?.petTapProgress)
        assertEquals(1, latest.pet?.petTapProgress)
        assertEquals(initialPet.message, latest.pet?.message)
        assertNull(latest.dashboard?.reply)
        assertTrue(gateway.ambientPersistenceRequests.isEmpty())
        assertEquals(1, gateway.ambientGenerationRequests.size)
        runtime.close()
    }

    @Test
    fun `cold notification opens canonical story with priority over travel`() = runBlocking {
        val initialPet = pet()
        val story = runtimeStory("cold")
        val travel = runtimeTravel()
        val eventGateway = RuntimeEventGateway(
            pet = initialPet,
            stories = mutableListOf(story),
            travelAssets = mutableListOf(travel),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            this.eventGateway = eventGateway
            notificationDeepLinkHandler = { extras ->
                if (extras.storyId != null) {
                    NotificationDeepLinkDestination.Story(story)
                } else {
                    NotificationDeepLinkDestination.Events(travel)
                }
            }
        }
        val extras = NotificationDeepLinkExtras(
            storyId = story.story.storyId,
            travelRequestKey = travel.requestKey,
        )
        val runtime = runtime(gateway, initialNotificationDeepLink = extras)

        val snapshot = runtime.snapshot()

        assertEquals("story", snapshot.route)
        assertEquals(WebDurableStoryOrigin.Dashboard, snapshot.story?.origin)
        assertEquals(story.story.storyId, snapshot.story?.story?.storyId)
        assertNull(snapshot.events?.initialFocusTravelRequestKey)
        assertEquals(listOf(extras), gateway.notificationDeepLinkRequests)
        runtime.close()
    }

    @Test
    fun `cold travel notification opens focused canonical event`() = runBlocking {
        val initialPet = pet()
        val travel = runtimeTravel()
        val eventGateway = RuntimeEventGateway(
            pet = initialPet,
            travelAssets = mutableListOf(travel),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            this.eventGateway = eventGateway
            notificationDeepLinkHandler = {
                NotificationDeepLinkDestination.Events(travel)
            }
        }
        val runtime = runtime(
            gateway,
            initialNotificationDeepLink = NotificationDeepLinkExtras(
                travelRequestKey = travel.requestKey,
            ),
        )

        val snapshot = runtime.snapshot()

        assertEquals("events", snapshot.route)
        assertEquals(travel.requestKey, snapshot.events?.initialFocusTravelRequestKey)
        assertNull(snapshot.story)
        runtime.close()
    }

    @Test
    fun `deep link destination is revalidated against durable runtime`() = runBlocking {
        suspend fun verify(destination: NotificationDeepLinkDestination) {
            val initialPet = pet()
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                ),
            ).apply {
                eventGateway = RuntimeEventGateway(initialPet)
                notificationDeepLinkHandler = { destination }
            }
            val runtime = runtime(
                gateway,
                initialNotificationDeepLink = NotificationDeepLinkExtras(
                    storyId = "story-stale",
                ),
            )

            val snapshot = runtime.snapshot()

            assertEquals("dashboard", snapshot.route)
            assertNull(snapshot.story)
            assertNull(snapshot.events?.initialFocusTravelRequestKey)
            runtime.close()
        }

        verify(NotificationDeepLinkDestination.Story(runtimeStory("stale")))
        verify(
            NotificationDeepLinkDestination.Events(
                runtimeTravel().copy(petId = "pet-foreign"),
            ),
        )
    }

    @Test
    fun `warm travel preserves dashboard mode and draft through overlay back`() = runBlocking {
        val initialPet = pet()
        val travel = runtimeTravel()
        val eventGateway = RuntimeEventGateway(
            pet = initialPet,
            travelAssets = mutableListOf(travel),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            this.eventGateway = eventGateway
            notificationDeepLinkHandler = {
                NotificationDeepLinkDestination.Events(travel)
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                put("mode", "outfit")
            },
        )
        snapshot = runtime.dispatch(
            dashboardCommand("DASHBOARD_UPDATE_DRAFT", snapshot.revision) {
                put("mode", "outfit")
                put("value", "красный шарф")
            },
        )
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

        runtime.offerNotificationDeepLink(
            NotificationDeepLinkExtras(travelRequestKey = travel.requestKey),
        )

        val opened = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { event.await() }.payload,
        )
        assertEquals("events", opened.route)
        assertEquals("outfit", opened.dashboardMode)
        assertEquals("красный шарф", opened.dashboard?.outfit?.draft)
        assertEquals(travel.requestKey, opened.events?.initialFocusTravelRequestKey)

        val returned = runtime.dispatch(dashboardCommand("BACK", opened.revision))
        assertEquals("dashboard", returned.route)
        assertEquals("outfit", returned.dashboardMode)
        assertEquals("красный шарф", returned.dashboard?.outfit?.draft)
        assertNull(returned.events?.initialFocusTravelRequestKey)
        runtime.close()
    }

    @Test
    fun `snapshot exposes only the safe route of an unresolved deep link`() = runBlocking {
        val enteredResolution = CompletableDeferred<Unit>()
        val releaseResolution = CompletableDeferred<Unit>()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(pet())),
            ),
        ).apply {
            notificationDeepLinkHandler = {
                enteredResolution.complete(Unit)
                releaseResolution.await()
                NotificationDeepLinkDestination.Dashboard
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()

        runtime.offerNotificationDeepLink(NotificationDeepLinkExtras(storyId = "story-safe"))
        enteredResolution.await()

        val pending = runtime.snapshot()
        assertEquals("story", pending.pendingDeepLinkTarget)
        assertFalse(BridgeCodec.json.encodeToString(pending).contains("story-safe"))

        releaseResolution.complete(Unit)
        withTimeout(1_000L) {
            while (runtime.snapshot().pendingDeepLinkTarget != null) {
                kotlinx.coroutines.yield()
            }
        }
        runtime.close()
    }

    @Test
    fun `warm story keeps active chat recoverable and back returns to chat`() = runBlocking {
        val chatGate = CompletableDeferred<Unit>()
        val initialPet = pet()
        val story = runtimeStory("chat")
        val eventGateway = RuntimeEventGateway(
            pet = initialPet,
            stories = mutableListOf(story),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            this.eventGateway = eventGateway
            notificationDeepLinkHandler = {
                NotificationDeepLinkDestination.Story(story)
            }
            chatExecutionHandler = { request, expectedPet, _ ->
                chatGate.await()
                val reply = "Ответ после уведомления"
                WebChatExecutionResult.Success(
                    result = DashboardChatResult(reply, expectedPet),
                    firstSession = null,
                    pendingChat = LocalPendingChat(
                        ownerId = "owner-test",
                        petId = expectedPet.petId,
                        requestKey = request.requestKey,
                        message = request.message,
                        createdAtEpochMillis = 1L,
                        responseText = reply,
                        completedAtEpochMillis = 2L,
                    ),
                )
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        var snapshot = runtime.snapshot()
        snapshot = runtime.dispatch(
            dashboardCommand("CHAT_SEND", snapshot.revision) {
                put("message", "Как дела?")
            },
        )
        assertTrue(snapshot.dashboard?.chat?.thinking == true)
        val deepLinkEvent = async(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.first()
        }

        runtime.offerNotificationDeepLink(NotificationDeepLinkExtras(storyId = story.story.storyId))

        val opened = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { deepLinkEvent.await() }.payload,
        )
        assertEquals("story", opened.route)
        assertEquals("chat", opened.dashboardMode)
        assertTrue(opened.dashboard?.chat?.thinking == true)

        val chatEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        chatGate.complete(Unit)
        val completed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { chatEvent.await() }.payload,
        )
        assertEquals("story", completed.route)
        assertEquals(listOf("Ответ после уведомления"), completed.dashboard?.reply?.portions)

        val returned = runtime.dispatch(dashboardCommand("BACK", completed.revision))
        assertEquals("dashboard", returned.route)
        assertEquals("chat", returned.dashboardMode)
        assertEquals(listOf("Ответ после уведомления"), returned.dashboard?.reply?.portions)
        runtime.close()
    }

    @Test
    fun `new warm notification token fences a stale async result`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val initialPet = pet()
        val staleStory = runtimeStory("old")
        val travel = runtimeTravel()
        val eventGateway = RuntimeEventGateway(
            pet = initialPet,
            stories = mutableListOf(staleStory),
            travelAssets = mutableListOf(travel),
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            this.eventGateway = eventGateway
            notificationDeepLinkHandler = { extras ->
                if (extras.storyId != null) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                    NotificationDeepLinkDestination.Story(staleStory)
                } else {
                    NotificationDeepLinkDestination.Events(travel)
                }
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        runtime.snapshot()

        runtime.offerNotificationDeepLink(
            NotificationDeepLinkExtras(storyId = staleStory.story.storyId),
        )
        withTimeout(1_000L) { firstStarted.await() }
        val latestEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        runtime.offerNotificationDeepLink(
            NotificationDeepLinkExtras(travelRequestKey = travel.requestKey),
        )
        val latest = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { latestEvent.await() }.payload,
        )
        firstGate.complete(Unit)
        repeat(3) { yield() }
        val afterStale = runtime.snapshot()

        assertEquals("events", latest.route)
        assertEquals(travel.requestKey, latest.events?.initialFocusTravelRequestKey)
        assertEquals(latest.revision, afterStale.revision)
        assertEquals("events", afterStale.route)
        assertEquals(travel.requestKey, afterStale.events?.initialFocusTravelRequestKey)
        runtime.close()
    }

    @Test
    fun `warm deep link never rolls back newer pet state`() = runBlocking {
        val resolverStarted = CompletableDeferred<Unit>()
        val resolverGate = CompletableDeferred<Unit>()
        val initialPet = pet(progress = 0)
        val travel = runtimeTravel()
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            eventGateway = RuntimeEventGateway(
                pet = initialPet,
                travelAssets = mutableListOf(travel),
            )
            notificationDeepLinkHandler = {
                resolverStarted.complete(Unit)
                resolverGate.await()
                NotificationDeepLinkDestination.Events(travel)
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val initial = runtime.snapshot()

        runtime.offerNotificationDeepLink(
            NotificationDeepLinkExtras(travelRequestKey = travel.requestKey),
        )
        withTimeout(1_000L) { resolverStarted.await() }
        val tapped = runtime.dispatch(petTap(initial.revision))
        val event = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
        resolverGate.complete(Unit)
        val opened = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
            withTimeout(1_000L) { event.await() }.payload,
        )

        assertEquals(1, tapped.pet?.petTapProgress)
        assertEquals(1, opened.pet?.petTapProgress)
        assertEquals("events", opened.route)
        assertEquals(travel.requestKey, opened.events?.initialFocusTravelRequestKey)
        runtime.close()
    }

    @Test
    fun `warm notification stays pending in create until dashboard finalization`() = runBlocking {
        val readyState = finalCreateState(
            generation = GenerationStatus.Ready(
                GeneratedPetFixture(
                    description = "Ледяного дракона",
                    petId = "pet-stable",
                    assetSetId = "asset-created",
                ),
            ),
        )
        val destination = dashboard(pet())
        val story = runtimeStory("create")
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(WebRuntimeDestination.Create(readyState)),
        ).apply {
            eventGateway = RuntimeEventGateway(
                pet = destination.pet,
                stories = mutableListOf(story),
            )
            finalizationHandler = { _, _ -> WebCreateFinalizationResult.Success(destination) }
            notificationDeepLinkHandler = {
                NotificationDeepLinkDestination.Story(story)
            }
        }
        val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
        val create = runtime.snapshot()

        runtime.offerNotificationDeepLink(NotificationDeepLinkExtras(storyId = story.story.storyId))
        repeat(3) { yield() }
        assertTrue(gateway.notificationDeepLinkRequests.isEmpty())

        val opened = runtime.dispatch(createCommand("CREATE_FINISH", create.revision))
        assertEquals("story", opened.route)
        assertEquals(WebDurableStoryOrigin.Dashboard, opened.story?.origin)
        assertEquals(1, gateway.notificationDeepLinkRequests.size)
        runtime.close()
    }

    @Test
    fun `foreground refresh delegates decay preserves composer state and owns one resumable loop`() =
        runBlocking {
            val initialPet = pet(hunger = 67, happiness = 58, energy = 49)
            val decayedPet = initialPet.copy(hunger = 0, happiness = 7, energy = 0)
            val refreshedSession = LocalFirstSession(
                ownerId = "owner-test",
                petId = initialPet.petId,
                stage = FirstSessionStage.AwaitingTravel,
                updatedAtEpochMillis = 50L,
            )
            val eventGateway = RuntimeEventGateway(initialPet)
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                ),
            ).apply {
                this.eventGateway = eventGateway
                outfitReservationHandler = { _, expectedPet ->
                    WebOutfitReservationResult.InsufficientExperience(expectedPet)
                }
                foregroundRefreshHandler = {
                    eventGateway.pet = decayedPet
                    eventGateway.firstSession = refreshedSession
                    if (eventGateway.stories.isEmpty()) {
                        eventGateway.stories += runtimeStory("foreground")
                    }
                    WebDashboardForegroundRefreshResult.Updated(
                        WebDashboardRecovery(
                            destination = dashboard(decayedPet, refreshedSession),
                            operations = WebDashboardOperationState(),
                        ),
                    )
                }
            }
            val clock = ManualForegroundRefreshClock()
            val runtime = runtime(
                gateway = gateway,
                generationScope = CoroutineScope(Dispatchers.Unconfined),
                foregroundRefreshDelayMillis = clock::delay,
            )
            var snapshot = runtime.snapshot()
            snapshot = runtime.dispatch(
                dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                    put("mode", "outfit")
                },
            )
            snapshot = runtime.dispatch(
                dashboardCommand("OUTFIT_SUBMIT", snapshot.revision) {
                    put("prompt", "красный шарф")
                },
            )
            val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

            runtime.onForeground()
            val refreshed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { firstEvent.await() }.payload,
            )

            assertEquals(listOf(initialPet.petId), gateway.foregroundRefreshes)
            assertEquals(0, refreshed.pet?.hunger)
            assertEquals(7, refreshed.pet?.happiness)
            assertEquals(0, refreshed.pet?.energy)
            assertEquals("outfit", refreshed.dashboardMode)
            assertEquals("красный шарф", refreshed.dashboard?.outfit?.draft)
            assertEquals("travel", refreshed.firstSession?.allowedAction)
            assertEquals(1, refreshed.events?.badgeCount)
            assertEquals(listOf(15 * 60 * 1_000L), clock.pendingDurations())

            runtime.onForeground()
            assertEquals(1, gateway.foregroundRefreshes.size)
            clock.advance()
            assertEquals(2, gateway.foregroundRefreshes.size)

            runtime.onBackground()
            assertTrue(clock.pendingDurations().isEmpty())
            val callsWhileBackground = gateway.foregroundRefreshes.size

            val resumeEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            runtime.onForeground()
            withTimeout(1_000L) { resumeEvent.await() }
            assertEquals(callsWhileBackground + 1, gateway.foregroundRefreshes.size)
            runtime.close()
        }

    @Test
    fun `events scheduled story keeps origin retries durable winner and returns to dashboard`() =
        runBlocking {
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                stories = mutableListOf(runtimeStory()),
                failFirstScheduledChoice = true,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = runtime.snapshot()
            val storyId = eventGateway.stories.single().story.storyId

            assertEquals(1, snapshot.events?.badgeCount)
            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            assertEquals("events", snapshot.route)
            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            assertEquals("story", snapshot.route)
            assertEquals(WebDurableStoryOrigin.Events, snapshot.story?.origin)

            val choose = dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", "b")
            }
            val failedEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            snapshot = runtime.dispatch(choose)
            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
            assertEquals("b", snapshot.story?.pendingChoice)
            snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { failedEvent.await() }.payload,
            )
            assertEquals(WebDurableStoryPhase.Retryable, snapshot.story?.phase)

            val retry = dashboardCommand("STORY_RETRY", snapshot.revision) {
                put("storyId", storyId)
            }
            val resultEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            snapshot = runtime.dispatch(retry)
            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            snapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { resultEvent.await() }.payload,
            )
            assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
            assertEquals(listOf(choose.requestKey, choose.requestKey), eventGateway.scheduledSubmissionKeys)
            assertEquals(220, snapshot.pet?.experience)

            snapshot = runtime.dispatch(retry)
            assertEquals(2, eventGateway.scheduledSubmissionKeys.size)
            val serialized = BridgeCodec.json.encodeToString(snapshot)
            assertFalse(serialized.contains("/static/story-1.png"))

            snapshot = runtime.dispatch(dashboardCommand("STORY_FINISH", snapshot.revision) {
                put("storyId", storyId)
            })
            assertEquals("events", snapshot.route)
            assertNull(snapshot.story)
            snapshot = runtime.dispatch(dashboardCommand("BACK", snapshot.revision))
            assertEquals("dashboard", snapshot.route)
            assertEquals(220, snapshot.pet?.experience)
            runtime.close()
        }

    @Test
    fun `held scheduled choice returns pending and late success after Back preserves route`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                stories = mutableListOf(runtimeStory()),
                scheduledChoiceGate = gate,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = runtime.snapshot()
            val storyId = eventGateway.stories.single().story.storyId
            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            val choose = dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", "b")
            }

            snapshot = withTimeout(1_000L) { runtime.dispatch(choose) }

            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
            assertEquals(listOf(choose.requestKey), eventGateway.scheduledSubmissionKeys)
            // A duplicate receipt returns immediately and never starts a second remote execution.
            assertEquals(
                WebDurableStoryPhase.ChoicePending,
                withTimeout(1_000L) { runtime.dispatch(choose) }.story?.phase,
            )
            assertEquals(1, eventGateway.scheduledSubmissionKeys.size)

            runtime.onBackground()
            snapshot = withTimeout(1_000L) {
                runtime.dispatch(dashboardCommand("BACK", snapshot.revision))
            }
            assertEquals("events", snapshot.route)
            assertNull(snapshot.story)

            val completion = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            gate.complete(Unit)
            val completed = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                withTimeout(1_000L) { completion.await() }.payload,
            )

            assertEquals("events", completed.route)
            assertNull(completed.story)
            assertEquals(220, completed.pet?.experience)
            assertEquals("b", eventGateway.stories.single().story.selectedChoice)
            assertEquals(1, eventGateway.storyReceipts.size)
            assertEquals(1, eventGateway.scheduledSubmissionKeys.size)
            runtime.close()
        }

    @Test
    fun `bridge event and system Back remain responsive while scheduled network is held`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                stories = mutableListOf(runtimeStory()),
                scheduledChoiceGate = gate,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = runtime.snapshot()
            val storyId = eventGateway.stories.single().story.storyId
            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            val dispatcher = BridgeDispatcher(runtime)
            val documentId = UUID.randomUUID().toString()
            fun bridgeRequest(method: String, payload: String, sessionId: String? = null): String =
                """{"kind":"request","protocolVersion":1,"documentId":"$documentId","bridgeSessionId":${sessionId?.let { "\"$it\"" } ?: "null"},"requestId":"${UUID.randomUUID()}","method":"$method","payload":$payload}"""

            val bootstrap = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                dispatcher.handle(
                    bridgeRequest(
                        "bootstrap",
                        """{"supportedProtocolVersions":[1],"webBundleVersion":"$BridgeWebBundleVersion","schemaHash":"$BridgeSchemaHash"}""",
                    ),
                ),
            )
            val sessionId = requireNotNull(bootstrap.bridgeSessionId)
            assertTrue(
                BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                    dispatcher.handle(
                        bridgeRequest(
                            "navigationReady",
                            """{"canHandleBack":true,"sequence":7}""",
                            sessionId,
                        ),
                    ),
                ).ok,
            )
            val choose = dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", "b")
            }

            val chooseResponse = BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                withTimeout(1_000L) {
                    dispatcher.handle(
                        bridgeRequest(
                            "dispatch",
                            BridgeCodec.json.encodeToString(choose),
                            sessionId,
                        ),
                    )
                },
            )
            val pending = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                chooseResponse.result,
            )
            assertEquals(WebDurableStoryPhase.ChoicePending, pending.story?.phase)
            assertFalse(gate.isCompleted)

            val event = withTimeout(1_000L) {
                dispatcher.event(WebAppRuntimeEvent("probe", buildJsonObject {}))
            }
            assertTrue(event != null)
            val back = dispatcher.requestSystemBack()
            assertTrue(back is WebSystemBackDecision.Dispatch)
            if (back is WebSystemBackDecision.Dispatch) dispatcher.releaseSystemBack(back.eventId)
            assertTrue(
                BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(
                    withTimeout(1_000L) {
                        dispatcher.handle(
                            bridgeRequest(
                                "feedback",
                                """{"kind":"buttonPress","eventId":"${UUID.randomUUID()}"}""",
                                sessionId,
                            ),
                        )
                    },
                ).ok,
            )
            assertEquals(1, eventGateway.scheduledSubmissionKeys.size)
            gate.complete(Unit)
            yield()
            runtime.close()
        }

    @Test
    fun `late scheduled failure after Back reopens retryable and retry keeps winner`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                stories = mutableListOf(runtimeStory()),
                failFirstScheduledChoice = true,
                scheduledChoiceGate = gate,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = runtime.snapshot()
            val storyId = eventGateway.stories.single().story.storyId
            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            val choose = dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", "b")
            }
            snapshot = runtime.dispatch(choose)
            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            snapshot = runtime.dispatch(dashboardCommand("BACK", snapshot.revision))

            val failedEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            gate.complete(Unit)
            snapshot = BridgeCodec.json.decodeFromJsonElement(
                withTimeout(1_000L) { failedEvent.await() }.payload,
            )
            assertEquals("events", snapshot.route)
            assertNull(snapshot.story)

            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            assertEquals(WebDurableStoryPhase.Retryable, snapshot.story?.phase)
            assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
            assertEquals("b", snapshot.story?.pendingChoice)

            val resultEvent = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }
            snapshot = runtime.dispatch(dashboardCommand("STORY_RETRY", snapshot.revision) {
                put("storyId", storyId)
            })
            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            snapshot = BridgeCodec.json.decodeFromJsonElement(
                withTimeout(1_000L) { resultEvent.await() }.payload,
            )
            assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
            assertEquals(choose.requestKey, snapshot.story?.durableRequestKey)
            assertEquals(listOf(choose.requestKey, choose.requestKey), eventGateway.scheduledSubmissionKeys)
            assertEquals(1, eventGateway.storyReceipts.size)
            runtime.close()
        }

    @Test
    fun `generic scheduled execution failure cannot strand visible Story in choice pending`() =
        runBlocking {
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                stories = mutableListOf(runtimeStory()),
                loseLocalDataAfterScheduledExecution = true,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = runtime.snapshot()
            val storyId = eventGateway.stories.single().story.storyId
            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            snapshot = runtime.dispatch(dashboardCommand("STORY_OPEN", snapshot.revision) {
                put("storyId", storyId)
            })
            val completion = async(start = CoroutineStart.UNDISPATCHED) { runtime.events.first() }

            snapshot = runtime.dispatch(dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", "b")
            })

            assertEquals(WebDurableStoryPhase.ChoicePending, snapshot.story?.phase)
            snapshot = BridgeCodec.json.decodeFromJsonElement(
                withTimeout(1_000L) { completion.await() }.payload,
            )
            assertEquals(WebDurableStoryPhase.Retryable, snapshot.story?.phase)
            assertEquals(TravelStoryChoiceFailureMessage, snapshot.story?.error)
            assertEquals("b", snapshot.story?.pendingChoice)
            assertEquals(1, eventGateway.scheduledSubmissionKeys.size)
            runtime.close()
        }

    @Test
    fun `onboarding travel is web story rewards once and finishes into completion message`() =
        runBlocking {
            val initialPet = pet().copy(experience = 200)
            val session = LocalFirstSession(
                ownerId = "owner-test",
                petId = initialPet.petId,
                stage = FirstSessionStage.AwaitingTravel,
                updatedAtEpochMillis = 1L,
            )
            val eventGateway = RuntimeEventGateway(initialPet, firstSession = session)
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet, session)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = settleFirstSessionReply(runtime, runtime.snapshot())

            val inlineTravel = runCatching {
                runtime.dispatch(dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                    put("mode", "travel")
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("WRONG_STAGE", inlineTravel.bridgeCode)

            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "travel")
            })
            val internalStoryId = onboardingBatStory(initialPet.petId).travelId
            val storyId = WebOnboardingBatStoryId
            assertEquals("story", snapshot.route)
            assertEquals(WebDurableStoryKind.OnboardingBat, snapshot.story?.kind)
            assertEquals(WebDurableStoryOrigin.Dashboard, snapshot.story?.origin)
            assertEquals(storyId, snapshot.story?.story?.storyId)
            val serialized = BridgeCodec.json.encodeToString(snapshot)
            assertFalse(serialized.contains(initialPet.petId))
            assertFalse(serialized.contains(initialPet.assetSetId))
            assertFalse(serialized.contains(internalStoryId))

            val foreignIdFailure = runCatching {
                runtime.dispatch(dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                    put("storyId", internalStoryId)
                    put("choice", OnboardingBatCorrectChoice)
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("WRONG_STAGE", foreignIdFailure.bridgeCode)

            val foreignRetryFailure = runCatching {
                runtime.dispatch(dashboardCommand("STORY_RETRY", snapshot.revision) {
                    put("storyId", internalStoryId)
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("WRONG_STAGE", foreignRetryFailure.bridgeCode)

            val choose = dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", storyId)
                put("choice", OnboardingBatCorrectChoice)
            }
            snapshot = runtime.dispatch(choose)
            assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
            assertEquals(400, snapshot.pet?.experience)
            assertEquals(1, eventGateway.onboardingRewardApplications)

            snapshot = runtime.dispatch(choose)
            assertEquals(400, snapshot.pet?.experience)
            assertEquals(1, eventGateway.onboardingRewardApplications)

            val foreignFinishFailure = runCatching {
                runtime.dispatch(dashboardCommand("STORY_FINISH", snapshot.revision) {
                    put("storyId", internalStoryId)
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("WRONG_STAGE", foreignFinishFailure.bridgeCode)

            val finish = dashboardCommand("STORY_FINISH", snapshot.revision) {
                put("storyId", storyId)
            }
            snapshot = runtime.dispatch(finish)
            assertEquals("dashboard", snapshot.route)
            assertEquals(
                FirstSessionStage.AwaitingCompletionMessage.storageValue,
                snapshot.firstSession?.stage,
            )
            assertEquals(400, snapshot.pet?.experience)
            assertEquals(1, eventGateway.onboardingFinishApplications)

            runtime.dispatch(finish)
            assertEquals(1, eventGateway.onboardingFinishApplications)
            runtime.close()
        }

    @Test
    fun `confirming travel upgrade enters opaque onboarding and finishes into completion message`() =
        runBlocking {
            val initialPet = pet().copy(experience = 200)
            val session = LocalFirstSession(
                ownerId = "owner-test",
                petId = initialPet.petId,
                stage = FirstSessionStage.ConfirmingTravel,
                selectedDestination = "Старый маяк",
                updatedAtEpochMillis = 1L,
            )
            val eventGateway = RuntimeEventGateway(initialPet, firstSession = session)
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(initialPet, session)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
            var snapshot = settleFirstSessionReply(runtime, runtime.snapshot())

            val inlineTravelFailure = runCatching {
                runtime.dispatch(dashboardCommand("DASHBOARD_OPEN_MODE", snapshot.revision) {
                    put("mode", "travel")
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("WRONG_STAGE", inlineTravelFailure.bridgeCode)

            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "travel")
            })
            assertEquals("story", snapshot.route)
            assertEquals(WebOnboardingBatStoryId, snapshot.story?.story?.storyId)
            assertEquals("Старый маяк", snapshot.firstSession?.selectedDestination)

            snapshot = runtime.dispatch(dashboardCommand("STORY_CHOOSE", snapshot.revision) {
                put("storyId", WebOnboardingBatStoryId)
                put("choice", OnboardingBatCorrectChoice)
            })
            assertEquals(WebDurableStoryPhase.Result, snapshot.story?.phase)
            assertEquals(400, snapshot.pet?.experience)

            snapshot = runtime.dispatch(dashboardCommand("STORY_FINISH", snapshot.revision) {
                put("storyId", WebOnboardingBatStoryId)
            })
            assertEquals("dashboard", snapshot.route)
            assertEquals(
                FirstSessionStage.AwaitingCompletionMessage.storageValue,
                snapshot.firstSession?.stage,
            )
            assertEquals("outfit", snapshot.firstSession?.allowedAction)
            runtime.close()
        }

    @Test
    fun `onboarding travel navigation rejects every non travel entry stage`() = runBlocking {
        FirstSessionStage.entries
            .filterNot { it in setOf(FirstSessionStage.AwaitingTravel, FirstSessionStage.ConfirmingTravel) }
            .forEach { stage ->
                val initialPet = pet()
                val session = LocalFirstSession(
                    ownerId = "owner-test",
                    petId = initialPet.petId,
                    stage = stage,
                    updatedAtEpochMillis = 1L,
                )
                val eventGateway = RuntimeEventGateway(initialPet, firstSession = session)
                val gateway = FakeGateway(
                    WebRuntimeBootstrapResult.Ready(
                        WebRuntimeDestination.Dashboard(dashboard(initialPet, session)),
                    ),
                ).apply { this.eventGateway = eventGateway }
                val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))
                val snapshot = settleFirstSessionReply(runtime, runtime.snapshot())

                val failure = runCatching {
                    runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                        put("route", "travel")
                    })
                }.exceptionOrNull() as WebAppRuntimeException
                assertEquals("stage=$stage", "WRONG_STAGE", failure.bridgeCode)
                runtime.close()
            }
    }

    @Test
    fun `events watermark must equal rendered latest and due pending schedules completion once per foreground`() =
        runBlocking {
            val travel = runtimeTravel()
            val dueGate = CompletableDeferred<Unit>()
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                travelAssets = mutableListOf(travel),
                dueResult = ScheduledStoryDueResult.Pending,
                dueGate = dueGate,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(gateway, generationScope = CoroutineScope(Dispatchers.Unconfined))

            var snapshot = runtime.snapshot()
            assertEquals(1, eventGateway.dueChecks)
            assertEquals(0, gateway.completionSyncEnqueues)
            assertEquals(1, snapshot.events?.badgeCount)

            snapshot = runtime.dispatch(dashboardCommand("NAVIGATE", snapshot.revision) {
                put("route", "events")
            })
            val latest = requireNotNull(snapshot.events?.latestEventAtEpochMillis)
            snapshot = runtime.dispatch(dashboardCommand("EVENTS_MARK_VIEWED", snapshot.revision) {
                put("viewedAt", latest)
            })
            assertEquals(0, snapshot.events?.badgeCount)
            assertEquals(latest, eventGateway.lastViewedAt)

            val futureWatermark = runCatching {
                runtime.dispatch(dashboardCommand("EVENTS_MARK_VIEWED", snapshot.revision) {
                    put("viewedAt", latest + 1)
                })
            }.exceptionOrNull() as WebAppRuntimeException
            assertEquals("STATE_CONFLICT", futureWatermark.bridgeCode)

            dueGate.complete(Unit)
            withTimeout(1_000L) {
                while (gateway.completionSyncEnqueues < 1) yield()
            }
            runtime.onForeground()
            withTimeout(1_000L) {
                while (eventGateway.dueChecks < 2 || gateway.completionSyncEnqueues < 2) yield()
            }
            assertEquals(2, eventGateway.dueChecks)
            assertEquals(2, gateway.completionSyncEnqueues)
            runtime.close()
        }

    @Test
    fun `held media keeps bridge bootstrap Back and dispatch responsive then publishes opaque ref`() =
        runBlocking {
            val source = "https://example.test/static/held-dashboard.mp4"
            val initialPet = pet().copy(
                generatedMedia = PetGeneratedMedia(videoUrl = source),
            )
            val materializer = RuntimeMediaMaterializer()
            val control = materializer.block(source)
            val registry = WebMediaReferenceRegistry(
                urlPolicy = StaticMediaUrlPolicy("https://example.test/", false),
                materializer = materializer,
                maxInFlight = 1,
            )
            val runtime = runtime(
                gateway = FakeGateway(
                    WebRuntimeBootstrapResult.Ready(
                        WebRuntimeDestination.Dashboard(dashboard(initialPet)),
                    ),
                ),
                generationScope = CoroutineScope(Dispatchers.Default),
                mediaRegistry = registry,
                mediaProjection = { projectedPet ->
                    projectDashboardWebMedia(projectedPet, registry)
                },
            )
            val dispatcher = BridgeDispatcher(runtime)
            val documentId = UUID.randomUUID().toString()
            try {
                val bootstrapResponse = withTimeout(1_000L) {
                    dispatcher.handle(
                        bridgeRequest(
                            method = "bootstrap",
                            documentId = documentId,
                            payload = BridgeCodec.json.encodeToJsonElement(
                                BridgeBootstrapPayload.serializer(),
                                BridgeBootstrapPayload(
                                    supportedProtocolVersions = listOf(BridgeProtocolVersion),
                                    webBundleVersion = BridgeWebBundleVersion,
                                    schemaHash = BridgeSchemaHash,
                                ),
                            ),
                        ),
                    )
                }.let { BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(it) }
                assertTrue(bootstrapResponse.ok)
                assertTrue(control.started.await(1, TimeUnit.SECONDS))
                val sessionId = requireNotNull(bootstrapResponse.bridgeSessionId)
                val initialSnapshot = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                    bootstrapResponse.result,
                )

                val backResponse = withTimeout(1_000L) {
                    dispatcher.handle(
                        bridgeRequest(
                            method = "dispatch",
                            documentId = documentId,
                            bridgeSessionId = sessionId,
                            payload = BridgeCodec.json.encodeToJsonElement(
                                BridgeProductCommand.serializer(),
                                dashboardCommand("BACK", initialSnapshot.revision),
                            ),
                        ),
                    )
                }.let { BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(it) }
                assertFalse(backResponse.ok)
                assertEquals("WRONG_STAGE", backResponse.error?.code)

                val tapResponse = withTimeout(1_000L) {
                    dispatcher.handle(
                        bridgeRequest(
                            method = "dispatch",
                            documentId = documentId,
                            bridgeSessionId = sessionId,
                            payload = BridgeCodec.json.encodeToJsonElement(
                                BridgeProductCommand.serializer(),
                                petTap(initialSnapshot.revision),
                            ),
                        ),
                    )
                }.let { BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(it) }
                assertTrue(tapResponse.ok)
                assertEquals(1, materializer.acquireCount(source))

                val readyEvent = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(2_000L) { runtime.events.first() }
                }
                control.release.countDown()
                val event = readyEvent.await()
                val ready = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(event.payload)
                val reference = requireNotNull(ready.pet?.media?.videoRef)
                assertEquals("stateChanged", event.type)
                assertTrue(reference.startsWith("/media/v1/"))
                assertFalse(reference.contains("example.test"))
                assertTrue(
                    registry.resolveRequest("https://appassets.androidplatform.net$reference") != null,
                )
            } finally {
                control.release.countDown()
                runtime.close()
                registry.close()
            }
        }

    @Test
    fun `stale dashboard media source completion cannot publish over latest slot`() = runBlocking {
        val oldSource = "https://example.test/static/old-dashboard.mp4"
        val newSource = "https://example.test/static/new-dashboard.mp4"
        val initialPet = pet().copy(generatedMedia = PetGeneratedMedia(videoUrl = oldSource))
        val materializer = RuntimeMediaMaterializer()
        val oldControl = materializer.block(oldSource)
        val newControl = materializer.block(newSource)
        val registry = WebMediaReferenceRegistry(
            urlPolicy = StaticMediaUrlPolicy("https://example.test/", false),
            materializer = materializer,
            maxInFlight = 2,
        )
        val gateway = FakeGateway(
            WebRuntimeBootstrapResult.Ready(
                WebRuntimeDestination.Dashboard(dashboard(initialPet)),
            ),
        ).apply {
            petTapHandler = { expected ->
                WebPetTapMutationResult.Applied(
                    expected.copy(
                        petTapProgress = (expected.petTapProgress + 1) % 5,
                        generatedMedia = PetGeneratedMedia(videoUrl = newSource),
                    ),
                )
            }
        }
        val runtime = runtime(
            gateway = gateway,
            generationScope = CoroutineScope(Dispatchers.Default),
            mediaRegistry = registry,
            mediaProjection = { projectedPet -> projectDashboardWebMedia(projectedPet, registry) },
        )
        try {
            val initial = runtime.snapshot()
            assertTrue(oldControl.started.await(1, TimeUnit.SECONDS))
            val afterTap = runtime.dispatch(petTap(initial.revision))
            assertTrue(newControl.started.await(1, TimeUnit.SECONDS))
            assertFalse(afterTap.pet?.media?.videoRef.orEmpty().contains("example.test"))

            oldControl.release.countDown()
            assertTrue(oldControl.finished.await(2, TimeUnit.SECONDS))
            withTimeout(1_000L) {
                while (materializer.releases.get() < 1 || registry.stats().inFlightCount != 1) yield()
            }
            assertNull(withTimeoutOrNull(150L) { runtime.events.first() })

            val readyEvent = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000L) { runtime.events.first() }
            }
            newControl.release.countDown()
            val ready = BridgeCodec.json.decodeFromJsonElement<WebAppSnapshot>(
                readyEvent.await().payload,
            )
            val reference = requireNotNull(ready.pet?.media?.videoRef)
            assertTrue(reference.startsWith("/media/v1/"))
            assertEquals(1, materializer.acquireCount(oldSource))
            assertEquals(1, materializer.acquireCount(newSource))
            assertTrue(
                registry.resolveRequest("https://appassets.androidplatform.net$reference") != null,
            )
        } finally {
            oldControl.release.countDown()
            newControl.release.countDown()
            runtime.close()
            registry.close()
        }
    }

    @Test
    fun `thousand event media sources keep runtime usable within the newest history budget`() =
        runBlocking {
            val materializer = RuntimeMediaMaterializer(holdAll = true)
            val registry = WebMediaReferenceRegistry(
                urlPolicy = StaticMediaUrlPolicy("https://example.test/", false),
                materializer = materializer,
                scopeProvider = { WebMediaOwnerScope("owner-test", "pet-1") },
                maxEntries = 8,
                maxLeasedBytes = 8L,
                maxInFlight = 2,
                maxColdHistorySources = 8,
            )
            val travelAssets = MutableList(1_000) { index ->
                runtimeTravel(
                    requestKey = UUID.nameUUIDFromBytes("travel-$index".toByteArray()).toString(),
                    completedAtEpochMillis = 2_000L + index,
                )
            }
            val eventGateway = RuntimeEventGateway(
                pet = pet(),
                travelAssets = travelAssets,
            )
            val gateway = FakeGateway(
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(dashboard(eventGateway.pet)),
                ),
            ).apply { this.eventGateway = eventGateway }
            val runtime = runtime(
                gateway = gateway,
                generationScope = CoroutineScope(Dispatchers.Default),
                mediaRegistry = registry,
            )
            try {
                val first = withTimeout(2_000L) { runtime.snapshot() }

                assertEquals(1_000, first.events?.travelVideos?.size)
                assertEquals(8, materializer.cacheChecks.size)
                assertTrue(registry.stats().inFlightCount <= 2)
                assertTrue(materializer.totalAcquireCount() <= 2)

                val repeated = withTimeout(2_000L) { runtime.snapshot() }
                assertEquals(1_000, repeated.events?.travelVideos?.size)
                assertEquals(8, materializer.cacheChecks.size)
                assertTrue(materializer.totalAcquireCount() <= 2)
                assertTrue(registry.stats().admittedColdHistoryCount <= 8)
            } finally {
                materializer.releaseAll.countDown()
                runtime.close()
                registry.close()
            }
        }

    private suspend fun settleFirstSessionReply(
        runtime: ProductionWebAppRuntime,
        initial: WebAppSnapshot,
    ): WebAppSnapshot {
        var snapshot = initial
        while (snapshot.dashboard?.reply?.source == "firstSession") {
            val reply = requireNotNull(snapshot.dashboard?.reply)
            snapshot = runtime.dispatch(
                dashboardCommand(
                    type = if (reply.hasNextPortion) "REPLY_ADVANCE" else "REPLY_COMPLETE",
                    revision = snapshot.revision,
                ) {
                    put("requestKey", reply.requestKey)
                },
            )
        }
        return snapshot
    }

    private fun runtime(
        gateway: FakeGateway,
        chooseThanks: () -> String = { "Приятно!" },
        generationScope: CoroutineScope? = null,
        petIdFactory: () -> String = { "pet-created" },
        elapsedRealtimeMillis: () -> Long = { 0L },
        delayMillis: suspend (Long) -> Unit = {},
        foregroundRefreshDelayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.awaitCancellation() },
        onPetTapFeedback: () -> Unit = {},
        onFeedFeedback: (Int) -> Unit = {},
        initialNotificationDeepLink: NotificationDeepLinkExtras? = null,
        mediaRegistry: WebMediaReferenceRegistry = WebMediaReferenceRegistry(
            StaticMediaUrlPolicy("https://example.test/", allowDebugLoopbackHttp = false),
        ),
        mediaProjection: (PetDashboardState) -> WebPetMediaSnapshot = { WebPetMediaSnapshot() },
    ) = ProductionWebAppRuntime(
        gateway = gateway,
        appVersion = "test",
        webBundleVersion = BridgeWebBundleVersion,
        mediaProjection = mediaProjection,
        mediaRegistry = mediaRegistry,
        choosePetTapThanks = chooseThanks,
        runtimeId = "00000000-0000-4000-8000-000000000001",
        generationScope = generationScope,
        petIdFactory = petIdFactory,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        delayMillis = delayMillis,
        foregroundRefreshDelayMillis = foregroundRefreshDelayMillis,
        onPetTapFeedback = onPetTapFeedback,
        onFeedFeedback = onFeedFeedback,
        initialNotificationDeepLink = initialNotificationDeepLink,
    )

    private class RuntimeMediaMaterializer(
        holdAll: Boolean = false,
    ) : WebMediaMaterializer {
        val cacheChecks = CopyOnWriteArrayList<String>()
        val releases = AtomicInteger()
        val releaseAll = CountDownLatch(if (holdAll) 1 else 0)
        private val sequence = AtomicInteger()
        private val controls = ConcurrentHashMap<String, RuntimeMediaControl>()
        private val acquireCounts = ConcurrentHashMap<String, AtomicInteger>()
        private val assets = ConcurrentHashMap<String, VerifiedWebMediaAsset>()

        fun block(sourceUrl: String): RuntimeMediaControl = RuntimeMediaControl().also {
            controls[sourceUrl] = it
        }

        fun acquireCount(sourceUrl: String): Int = acquireCounts[sourceUrl]?.get() ?: 0

        fun totalAcquireCount(): Int = acquireCounts.values.sumOf(AtomicInteger::get)

        override fun acquireCached(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset? {
            cacheChecks += sourceUrl
            return null
        }

        override fun materialize(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset = acquire(sourceUrl, kind)

        override fun acquire(
            sourceUrl: String,
            kind: WebMediaKind,
        ): VerifiedWebMediaAsset {
            acquireCounts.computeIfAbsent(sourceUrl) { AtomicInteger() }.incrementAndGet()
            val control = controls[sourceUrl]
            control?.started?.countDown()
            try {
                val gate = control?.release ?: releaseAll
                if (!gate.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("test media materialization was not released")
                }
                return assets.computeIfAbsent("${kind.name}:$sourceUrl") {
                    val index = sequence.incrementAndGet()
                    val file = File.createTempFile("runtime-media-$index-", ".media").apply {
                        deleteOnExit()
                        writeBytes(byteArrayOf(index.toByte()))
                    }
                    VerifiedWebMediaAsset(
                        file = file,
                        mimeType = if (kind == WebMediaKind.Video) "video/mp4" else "image/png",
                        byteLength = file.length(),
                        version = "runtime-$index",
                    )
                }
            } finally {
                control?.finished?.countDown()
            }
        }

        override fun release(asset: VerifiedWebMediaAsset) {
            releases.incrementAndGet()
        }
    }

    private class RuntimeMediaControl {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }

    private class ManualForegroundRefreshClock {
        private data class Pending(
            val durationMillis: Long,
            val gate: CompletableDeferred<Unit>,
        )

        private val pending = ArrayDeque<Pending>()

        suspend fun delay(durationMillis: Long) {
            val wait = Pending(durationMillis, CompletableDeferred())
            pending.addLast(wait)
            try {
                wait.gate.await()
            } finally {
                pending.remove(wait)
            }
        }

        fun pendingDurations(): List<Long> = pending.map { it.durationMillis }

        fun advance() {
            pending.removeFirst().gate.complete(Unit)
        }
    }

    private fun createCommand(
        type: String,
        revision: String,
        payload: JsonObjectBuilder.() -> Unit = {},
    ) = BridgeProductCommand(
        type = type,
        requestKey = UUID.randomUUID().toString(),
        expectedSnapshotRevision = revision,
        payload = buildJsonObject(payload),
    )

    private fun bridgeRequest(
        method: String,
        documentId: String,
        payload: JsonElement,
        bridgeSessionId: String? = null,
    ): String = BridgeCodec.json.encodeToString(
        BridgeRequestEnvelope.serializer(),
        BridgeRequestEnvelope(
            kind = "request",
            protocolVersion = BridgeProtocolVersion,
            documentId = documentId,
            bridgeSessionId = bridgeSessionId,
            requestId = UUID.randomUUID().toString(),
            method = method,
            payload = payload,
        ),
    )

    private fun dashboardCommand(
        type: String,
        revision: String,
        payload: JsonObjectBuilder.() -> Unit = {},
    ) = BridgeProductCommand(
        type = type,
        requestKey = UUID.randomUUID().toString(),
        expectedSnapshotRevision = revision,
        payload = buildJsonObject(payload),
    )

    private fun chatSuccess(
        command: BridgeProductCommand,
        reply: String,
    ) = WebChatExecutionResult.Success(
        result = DashboardChatResult(reply, pet()),
        firstSession = null,
        pendingChat = LocalPendingChat(
            "owner-test",
            "pet-1",
            command.requestKey,
            command.payload["message"]!!.toString().trim('"'),
            1L,
            reply,
            2L,
        ),
    )

    private fun petTap(revision: String) = BridgeProductCommand(
        type = "PET_TAP",
        requestKey = UUID.randomUUID().toString(),
        expectedSnapshotRevision = revision,
        payload = buildJsonObject {},
    )

    private fun finalCreateState(
        generation: GenerationStatus = GenerationStatus.Running,
    ): CreatePetState {
        var state = CreatePetState().answer(
            "Ледяного дракона",
            requestKeyFactory = { "123e4567-e89b-42d3-a456-426614174000" },
            petIdFactory = { "pet-stable" },
        )
        listOf("Тото", "Добрый", "Пауков", "Вантуз").forEach {
            state = state.answer(it)
        }
        return state.copy(generation = generation)
    }

    private fun dashboard(
        pet: PetDashboardState,
        firstSession: LocalFirstSession? = null,
        pendingChat: LocalPendingChat? = null,
        queuedChat: LocalPendingChat? = null,
    ) = AccountStartupDestination.Dashboard(
        pet = pet,
        pendingOutfit = null,
        pendingTravel = null,
        storyReceipts = emptyList(),
        firstSession = firstSession,
        pendingChat = pendingChat,
        queuedChat = queuedChat,
    )

    private fun pendingOutfit(
        request: PendingOutfitRequest,
        pet: PetDashboardState,
        state: PendingBackendState,
    ) = LocalPendingOutfit(
        ownerId = "owner-test",
        petId = pet.petId,
        requestKey = request.requestKey,
        localJobId = "outfit-${request.requestKey}",
        backendJobId = null,
        prompt = request.prompt,
        baseAssetSetId = pet.assetSetId,
        acceptedAtEpochMillis = 1L,
        backendState = state,
    )

    private fun pendingChat(
        requestKey: String,
        message: String,
        responseText: String? = null,
    ) = LocalPendingChat(
        ownerId = "owner-test",
        petId = "pet-1",
        requestKey = requestKey,
        message = message,
        createdAtEpochMillis = 1L,
        responseText = responseText,
        completedAtEpochMillis = responseText?.let { 2L },
    )

    private fun pendingTravel(
        request: PendingTravelRequest,
        pet: PetDashboardState,
        state: PendingBackendState,
    ) = LocalPendingTravelVideo(
        ownerId = "owner-test",
        petId = pet.petId,
        requestKey = request.requestKey,
        localJobId = "travel-${request.requestKey}",
        backendJobId = null,
        prompt = request.prompt,
        acceptedAtEpochMillis = 1L,
        backendState = state,
    )

    private fun pet(
        happiness: Int = 80,
        progress: Int = 0,
        hunger: Int = 80,
        energy: Int = 80,
    ) = PetDashboardState(
        petId = "pet-1",
        assetSetId = "asset-1",
        description = "Друг",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 200,
        hunger = hunger,
        happiness = happiness,
        energy = energy,
        message = "Постоянная реплика",
        petTapProgress = progress,
    )

    private fun runtimeStory(
        suffix: String = "1",
        selectedChoice: String? = null,
    ): LocalScheduledStory = LocalScheduledStory(
        ownerId = "owner-test",
        story = ScheduledStory(
            storyId = "story-${suffix.padStart(32, '0')}",
            petId = "pet-1",
            title = "История $suffix",
            text = "Ситуация",
            question = "Что делать?",
            choices = listOf("a", "b", "c", "d"),
            createdAt = "2026-07-20T12:00:00Z",
            imageUrl = "/static/story-$suffix.png?v=1",
            videoUrl = null,
            selectedChoice = selectedChoice,
            result = selectedChoice?.let {
                ScheduledStoryResult("Результат", "Реакция", "Последствие", 20)
            },
        ),
        choiceRequestKey = selectedChoice?.let { UUID.randomUUID().toString() },
    )

    private fun runtimeTravel(
        requestKey: String = UUID.randomUUID().toString(),
        completedAtEpochMillis: Long = Instant.parse("2026-07-20T13:00:00Z").toEpochMilli(),
    ): LocalTravelVideoAsset = LocalTravelVideoAsset(
        ownerId = "owner-test",
        petId = "pet-1",
        requestKey = requestKey,
        backendJobId = "backend-$requestKey",
        prompt = "Путешествие",
        title = "Видео",
        scenario = "Сценарий",
        imageUrl = "/static/$requestKey.png?v=1",
        videoUrl = "/static/$requestKey.mp4?v=1",
        completedAtEpochMillis = completedAtEpochMillis,
        consumedAtEpochMillis = completedAtEpochMillis + 1,
    )

    private class RuntimeEventGateway(
        var pet: PetDashboardState,
        var firstSession: LocalFirstSession? = null,
        val stories: MutableList<LocalScheduledStory> = mutableListOf(),
        val travelAssets: MutableList<LocalTravelVideoAsset> = mutableListOf(),
        var lastViewedAt: Long? = null,
        var dueResult: ScheduledStoryDueResult = ScheduledStoryDueResult.NotDue,
        var dueGate: CompletableDeferred<Unit>? = null,
        var failFirstScheduledChoice: Boolean = false,
        var scheduledChoiceGate: CompletableDeferred<Unit>? = null,
        var loseLocalDataAfterScheduledExecution: Boolean = false,
    ) : EventStoryGateway {
        val storyReceipts = mutableListOf<InteractiveStoryReceipt>()
        val scheduledSubmissionKeys = mutableListOf<String>()
        var dueChecks = 0
        var onboardingRewardApplications = 0
        var onboardingFinishApplications = 0
        private var rejectStoryLoads = false

        override suspend fun load(petId: String): EventStoryRecovery? =
            if (petId != pet.petId || rejectStoryLoads) null else EventStoryRecovery(
                pet = pet,
                firstSession = firstSession,
                scheduledStories = stories.toList(),
                travelVideoAssets = travelAssets.toList(),
                storyReceipts = storyReceipts.toList(),
                lastViewedAtEpochMillis = lastViewedAt,
            )

        override suspend fun markViewed(petId: String, viewedAtEpochMillis: Long): Boolean {
            if (petId != pet.petId) return false
            lastViewedAt = maxOf(lastViewedAt ?: Long.MIN_VALUE, viewedAtEpochMillis)
            return true
        }

        override suspend fun checkDue(pet: PetDashboardState): ScheduledStoryDueResult {
            dueChecks += 1
            dueGate?.await()
            return dueResult
        }

        override suspend fun prepareScheduledStoryChoice(
            storyId: String,
            choice: String,
            proposedRequestKey: String,
        ): ScheduledStoryChoicePreparationResult {
            val index = stories.indexOfFirst { it.story.storyId == storyId }
            if (index < 0) return preparationFailure(FeatureFailureKind.NotFound)
            var local = stories[index]
            local.story.selectedChoice?.let { selected ->
                return if (selected == choice && local.choiceRequestKey != null) {
                    ScheduledStoryChoicePreparationResult.Prepared(local.choiceRequestKey, choice)
                } else {
                    preparationFailure(FeatureFailureKind.Conflict)
                }
            }
            if (local.choiceRequestKey != null && local.pendingChoice != choice) {
                return preparationFailure(FeatureFailureKind.Conflict)
            }
            val winnerKey = local.choiceRequestKey ?: proposedRequestKey
            if (local.choiceRequestKey == null) {
                local = local.copy(choiceRequestKey = winnerKey, pendingChoice = choice)
                stories[index] = local
            }
            return ScheduledStoryChoicePreparationResult.Prepared(winnerKey, choice)
        }

        override suspend fun executePreparedScheduledStoryChoice(
            storyId: String,
            requestKey: String,
            choice: String,
        ): ScheduledStoryChoiceResult {
            val index = stories.indexOfFirst { it.story.storyId == storyId }
            if (index < 0) return scheduledFailure(FeatureFailureKind.NotFound)
            var local = stories[index]
            if (local.choiceRequestKey != requestKey) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            local.story.selectedChoice?.let { selected ->
                return if (selected == choice) saved(local) else {
                    scheduledFailure(FeatureFailureKind.Conflict)
                }
            }
            if (local.pendingChoice != choice) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            scheduledSubmissionKeys += requestKey
            scheduledChoiceGate?.await()
            if (loseLocalDataAfterScheduledExecution) {
                rejectStoryLoads = true
                return scheduledFailure(FeatureFailureKind.Network)
            }
            if (failFirstScheduledChoice) {
                failFirstScheduledChoice = false
                return scheduledFailure(FeatureFailureKind.Network)
            }
            local = stories[index].copy(
                story = stories[index].story.copy(
                    selectedChoice = choice,
                    result = ScheduledStoryResult(
                        text = "Результат",
                        reaction = "Реакция",
                        consequence = "Последствие",
                        experienceGained = 20,
                    ),
                ),
                pendingChoice = null,
            )
            stories[index] = local
            applyScheduledReceipt(local)
            return saved(local)
        }

        override suspend fun reconcileScheduledStory(
            storyId: String,
        ): ScheduledStoryChoiceResult {
            val local = stories.singleOrNull { it.story.storyId == storyId }
                ?: return scheduledFailure(FeatureFailureKind.NotFound)
            if (local.story.selectedChoice == null) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            applyScheduledReceipt(local)
            return saved(local)
        }

        override suspend fun commitOnboardingBatChoice(
            petId: String,
            requestKey: String,
        ): FirstSessionMutationResult {
            val session = firstSession ?: return FirstSessionMutationResult.Missing
            if (petId != pet.petId) return FirstSessionMutationResult.Missing
            val storyId = onboardingBatStory(petId).travelId
            if (storyReceipts.any { it.travelId == storyId && it.partKey == "choice-result" }) {
                return FirstSessionMutationResult.AlreadyApplied(session, pet)
            }
            if (session.stage !in setOf(
                    FirstSessionStage.AwaitingTravel,
                    FirstSessionStage.ConfirmingTravel,
                )
            ) {
                return FirstSessionMutationResult.WrongStage
            }
            storyReceipts += receipt(requestKey, storyId, 200)
            pet = pet.copy(experience = (pet.experience + 200).coerceAtMost(3_000))
            onboardingRewardApplications += 1
            return FirstSessionMutationResult.Applied(session, pet)
        }

        override suspend fun finishOnboardingBat(
            petId: String,
            actionKey: String,
        ): FirstSessionMutationResult {
            val session = firstSession ?: return FirstSessionMutationResult.Missing
            if (petId != pet.petId) return FirstSessionMutationResult.Missing
            if (session.stage == FirstSessionStage.AwaitingCompletionMessage) {
                return FirstSessionMutationResult.AlreadyApplied(session, pet)
            }
            if (
                session.stage !in setOf(
                    FirstSessionStage.AwaitingTravel,
                    FirstSessionStage.ConfirmingTravel,
                ) ||
                storyReceipts.none {
                    it.travelId == onboardingBatStory(petId).travelId &&
                        it.partKey == "choice-result"
                }
            ) {
                return FirstSessionMutationResult.WrongStage
            }
            firstSession = session.copy(
                stage = FirstSessionStage.AwaitingCompletionMessage,
                lastActionKey = actionKey,
            )
            onboardingFinishApplications += 1
            return FirstSessionMutationResult.Applied(requireNotNull(firstSession), pet)
        }

        private fun applyScheduledReceipt(local: LocalScheduledStory) {
            if (storyReceipts.any { it.travelId == local.story.storyId }) return
            val requestKey = requireNotNull(local.choiceRequestKey)
            val experience = requireNotNull(local.story.result).experienceGained
            storyReceipts += receipt(requestKey, local.story.storyId, experience)
            pet = pet.copy(experience = (pet.experience + experience).coerceAtMost(3_000))
        }

        private fun saved(local: LocalScheduledStory) = ScheduledStoryChoiceResult.Saved(
            story = local.story,
            requestKey = requireNotNull(local.choiceRequestKey),
            committedExperience = pet.experience,
            committedTravelIds = setOf(local.story.storyId),
        )

        private fun scheduledFailure(kind: FeatureFailureKind) =
            ScheduledStoryChoiceResult.Failure(FeatureFailure(kind))

        private fun preparationFailure(kind: FeatureFailureKind) =
            ScheduledStoryChoicePreparationResult.Failure(FeatureFailure(kind))

        private fun receipt(
            requestKey: String,
            storyId: String,
            experience: Int,
        ) = InteractiveStoryReceipt(
            ownerId = "owner-test",
            petId = pet.petId,
            receiptKey = requestKey,
            travelId = storyId,
            partKey = "choice-result",
            experienceDelta = experience,
            hungerDelta = 0,
            happinessDelta = 0,
            energyDelta = 0,
            appliedAtEpochMillis = 1L,
        )
    }

    private class FakeGateway(
        vararg bootstrapResults: WebRuntimeBootstrapResult,
    ) : WebAppDataGateway {
        private val results = ArrayDeque(bootstrapResults.toList())
        private var lastResult = bootstrapResults.last()
        var bootstrapCalls = 0
        var tapWrites = 0
        var petTapHandler: suspend (PetDashboardState) -> WebPetTapMutationResult = { expectedPet ->
            val current = expectedPet.petTapProgress.coerceIn(0, 4)
            val next = (current + 1) % 5
            val rewarded = next == 0
            WebPetTapMutationResult.Applied(
                expectedPet.copy(
                    petTapProgress = next,
                    happiness = if (rewarded) {
                        (expectedPet.happiness + 15).coerceAtMost(100)
                    } else {
                        expectedPet.happiness
                    },
                ),
            )
        }
        var closeCalls = 0
        val persistedCreates = mutableListOf<CreatePetState>()
        val persistenceResults = ArrayDeque<WebCreatePersistenceResult>()
        val generationRequests = mutableListOf<PendingPetGeneration>()
        var generationHandler: suspend (PendingPetGeneration) -> PetGenerationExecutionResult = {
            PetGenerationExecutionResult.Failure(newRequestRequired = false)
        }
        var finalizationCalls = 0
        val finalizationResults = ArrayDeque<WebCreateFinalizationResult>()
        var finalizationHandler: suspend (
            CreatePetState,
            Boolean,
        ) -> WebCreateFinalizationResult = { _, _ -> WebCreateFinalizationResult.Failure }
        val chatReservations = mutableListOf<PendingChatRequest>()
        val chatReservationOrigins = mutableListOf<FirstSessionStage?>()
        val chatReservationAnchors = mutableListOf<String?>()
        val chatReservationReplacements = mutableListOf<String?>()
        val chatExecutions = mutableListOf<PendingChatRequest>()
        val acknowledgedChats = mutableListOf<String>()
        var chatReservationHandler: suspend (
            PendingChatRequest,
            PetDashboardState,
            FirstSessionStage?,
        ) -> WebChatReservationResult = { request, expectedPet, originFirstSessionStage ->
            WebChatReservationResult.Pending(
                pet = expectedPet,
                pendingChat = LocalPendingChat(
                    ownerId = "owner-test",
                    petId = expectedPet.petId,
                    requestKey = request.requestKey,
                    message = request.message,
                    createdAtEpochMillis = 1L,
                ),
                originFirstSessionStage = originFirstSessionStage,
            )
        }
        var chatExecutionHandler: suspend (
            PendingChatRequest,
            PetDashboardState,
            FirstSessionStage?,
        ) -> WebChatExecutionResult = { request, expectedPet, _ ->
            WebChatExecutionResult.Success(
                result = DashboardChatResult("Ответ", expectedPet),
                firstSession = null,
                pendingChat = LocalPendingChat(
                    ownerId = "owner-test",
                    petId = expectedPet.petId,
                    requestKey = request.requestKey,
                    message = request.message,
                    createdAtEpochMillis = 1L,
                    responseText = "Ответ",
                    completedAtEpochMillis = 2L,
                ),
            )
        }
        val feedApplications = mutableListOf<Triple<String, DashboardFood, Int>>()
        var feedHandler: suspend (
            String,
            DashboardFood,
            Int,
            PetDashboardState,
        ) -> WebFeedMutationResult = { requestKey, food, audioIndex, expectedPet ->
            WebFeedMutationResult.Applied(
                pet = when (food) {
                    DashboardFood.BerryBowl -> expectedPet.copy(
                        hunger = (expectedPet.hunger + 25).coerceAtMost(100),
                    )
                    DashboardFood.LeafCrunch -> expectedPet.copy(
                        energy = (expectedPet.energy + 25).coerceAtMost(100),
                    )
                },
                firstSession = null,
                receipt = LocalDashboardFeedReceipt(
                    requestKey = requestKey,
                    food = food.routeValue,
                    audioIndex = audioIndex,
                    reply = if (food == DashboardFood.BerryBowl) BerryReply else LeafReply,
                    autoAdvanceDelayMillis = DashboardReplyAutoAdvanceMillis,
                ),
                newlyApplied = true,
            )
        }
        val outfitReservations = mutableListOf<PendingOutfitRequest>()
        val outfitExecutions = mutableListOf<LocalPendingOutfit>()
        var outfitReservationHandler: suspend (
            PendingOutfitRequest,
            PetDashboardState,
        ) -> WebOutfitReservationResult = { _, _ ->
            WebOutfitReservationResult.LocalDataError
        }
        var outfitExecutionHandler: suspend (
            LocalPendingOutfit,
            PetDashboardState,
        ) -> WebDashboardOperationExecutionResult = { _, _ ->
            WebDashboardOperationExecutionResult.Failure
        }
        val travelReservations = mutableListOf<PendingTravelRequest>()
        val travelExecutions = mutableListOf<LocalPendingTravelVideo>()
        var travelReservationHandler: suspend (
            PendingTravelRequest,
            PetDashboardState,
        ) -> WebTravelReservationResult = { _, _ ->
            WebTravelReservationResult.LocalDataError
        }
        var travelExecutionHandler: suspend (
            LocalPendingTravelVideo,
            PetDashboardState,
        ) -> WebDashboardOperationExecutionResult = { _, _ ->
            WebDashboardOperationExecutionResult.Failure
        }
        val dashboardRefreshes = mutableListOf<String>()
        var dashboardRefreshHandler: suspend (String) ->
            WebDashboardOperationExecutionResult = {
            WebDashboardOperationExecutionResult.Failure
            }
        val foregroundRefreshes = mutableListOf<String>()
        var foregroundRefreshHandler: suspend (String) ->
            WebDashboardForegroundRefreshResult = {
                WebDashboardForegroundRefreshResult.LocalDataError
            }
        val ambientGenerationRequests =
            mutableListOf<Pair<String, PetDashboardState>>()
        var ambientGenerationHandler: suspend (
            String,
            PetDashboardState,
        ) -> WebAmbientGenerationResult = { _, _ -> WebAmbientGenerationResult.Failure }
        val ambientPersistenceRequests =
            mutableListOf<Pair<PetDashboardState, DashboardAmbientResult>>()
        var ambientPersistenceHandler: suspend (
            PetDashboardState,
            DashboardAmbientResult,
        ) -> WebAmbientPersistenceResult = { _, result ->
            WebAmbientPersistenceResult.Applied(result.pet)
        }
        val notificationDeepLinkRequests = mutableListOf<NotificationDeepLinkExtras>()
        var notificationDeepLinkHandler: suspend (
            NotificationDeepLinkExtras,
        ) -> NotificationDeepLinkDestination = {
            NotificationDeepLinkDestination.Dashboard
        }
        var eventGateway: EventStoryGateway? = null
        var completionSyncEnqueues = 0

        override suspend fun bootstrap(): WebRuntimeBootstrapResult {
            bootstrapCalls += 1
            if (results.isNotEmpty()) lastResult = results.removeFirst()
            return lastResult
        }

        override suspend fun persistCreate(state: CreatePetState): WebCreatePersistenceResult {
            persistedCreates += state
            return if (persistenceResults.isEmpty()) {
                WebCreatePersistenceResult.Persisted
            } else {
                persistenceResults.removeFirst()
            }
        }

        override suspend fun generateCreate(
            request: PendingPetGeneration,
        ): PetGenerationExecutionResult {
            generationRequests += request
            return generationHandler(request)
        }

        override suspend fun finalizeCreate(
            state: CreatePetState,
            foregroundHandled: Boolean,
        ): WebCreateFinalizationResult {
            finalizationCalls += 1
            return if (finalizationResults.isEmpty()) {
                finalizationHandler(state, foregroundHandled)
            } else {
                finalizationResults.removeFirst()
            }
        }

        override suspend fun applyPetTap(
            expectedPet: PetDashboardState,
        ): WebPetTapMutationResult {
            tapWrites += 1
            return petTapHandler(expectedPet)
        }

        override suspend fun reserveChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            originFirstSessionStage: FirstSessionStage?,
            queueAnchorRequestKey: String?,
            replacingQueuedRequestKey: String?,
        ): WebChatReservationResult {
            chatReservations += request
            chatReservationOrigins += originFirstSessionStage
            chatReservationAnchors += queueAnchorRequestKey
            chatReservationReplacements += replacingQueuedRequestKey
            return chatReservationHandler(request, expectedPet, originFirstSessionStage)
        }

        override suspend fun executeChat(
            request: PendingChatRequest,
            expectedPet: PetDashboardState,
            expectedFirstSessionStage: FirstSessionStage?,
        ): WebChatExecutionResult {
            chatExecutions += request
            return chatExecutionHandler(request, expectedPet, expectedFirstSessionStage)
        }

        override suspend fun acknowledgeChat(requestKey: String): Boolean {
            acknowledgedChats += requestKey
            return true
        }

        override suspend fun applyFeed(
            requestKey: String,
            food: DashboardFood,
            audioIndex: Int,
            expectedPet: PetDashboardState,
        ): WebFeedMutationResult {
            feedApplications += Triple(requestKey, food, audioIndex)
            return feedHandler(requestKey, food, audioIndex, expectedPet)
        }

        override suspend fun reserveOutfit(
            request: PendingOutfitRequest,
            expectedPet: PetDashboardState,
        ): WebOutfitReservationResult {
            outfitReservations += request
            return outfitReservationHandler(request, expectedPet)
        }

        override suspend fun executeOutfit(
            request: LocalPendingOutfit,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult {
            outfitExecutions += request
            return outfitExecutionHandler(request, expectedPet)
        }

        override suspend fun reserveTravel(
            request: PendingTravelRequest,
            expectedPet: PetDashboardState,
        ): WebTravelReservationResult {
            travelReservations += request
            return travelReservationHandler(request, expectedPet)
        }

        override suspend fun executeTravel(
            request: LocalPendingTravelVideo,
            expectedPet: PetDashboardState,
        ): WebDashboardOperationExecutionResult {
            travelExecutions += request
            return travelExecutionHandler(request, expectedPet)
        }

        override suspend fun refreshDashboardOperations(
            petId: String,
        ): WebDashboardOperationExecutionResult {
            dashboardRefreshes += petId
            return dashboardRefreshHandler(petId)
        }

        override suspend fun refreshDashboardForForeground(
            petId: String,
        ): WebDashboardForegroundRefreshResult {
            foregroundRefreshes += petId
            return foregroundRefreshHandler(petId)
        }

        override suspend fun generateAmbientReply(
            requestKey: String,
            expectedPet: PetDashboardState,
        ): WebAmbientGenerationResult {
            ambientGenerationRequests += requestKey to expectedPet
            return ambientGenerationHandler(requestKey, expectedPet)
        }

        override suspend fun persistAmbientReply(
            expectedPet: PetDashboardState,
            result: DashboardAmbientResult,
        ): WebAmbientPersistenceResult {
            ambientPersistenceRequests += expectedPet to result
            return ambientPersistenceHandler(expectedPet, result)
        }

        override suspend fun resolveNotificationDeepLink(
            extras: NotificationDeepLinkExtras,
        ): NotificationDeepLinkDestination {
            notificationDeepLinkRequests += extras
            return notificationDeepLinkHandler(extras)
        }

        override fun authenticatedEventStoryGateway(): EventStoryGateway? = eventGateway

        override fun enqueueEventStoryCompletionSync() {
            completionSyncEnqueues += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
