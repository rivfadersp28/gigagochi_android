package com.gigagochi.app.core.webview

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.auth.AndroidInstallationIdProvider
import com.gigagochi.app.core.auth.HttpGuestSessionExchange
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.LocalSessionBootstrapCoordinator
import com.gigagochi.app.core.auth.LocalSessionBootstrapOutcome
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.background.AndroidLocalNotificationEmitter
import com.gigagochi.app.core.background.CompletionNotificationDispatcher
import com.gigagochi.app.core.background.CompletionSyncScheduler
import com.gigagochi.app.core.background.CreateSyncScheduler
import com.gigagochi.app.core.background.ManualGenerationKind
import com.gigagochi.app.core.background.MvpSyncScheduler
import com.gigagochi.app.core.background.manualGenerationFailedNotification
import com.gigagochi.app.core.background.notificationsAllowed
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.DashboardChatReservationResult
import com.gigagochi.app.core.database.DashboardFeedApplicationResult
import com.gigagochi.app.core.database.DashboardOutfitReservationResult
import com.gigagochi.app.core.database.DashboardTravelReservationResult
import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalDashboardFeedPresentation
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.LocalOperationResult
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.database.PetSnapshotMutationResult
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureApi
import com.gigagochi.app.core.network.AuthenticatedFeatureClient
import com.gigagochi.app.core.network.UrlConnectionFeatureHttpTransport
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.security.NotificationDeepLinkDestination
import com.gigagochi.app.core.security.NotificationDeepLinkExtras
import com.gigagochi.app.core.security.NotificationDeepLinkResolver
import com.gigagochi.app.core.security.PetLocalRepositoryNotificationDeepLinkStore
import com.gigagochi.app.core.security.PetLocalRepositoryTravelVideoAssetStore
import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.core.security.TravelVideoShareResolver
import com.gigagochi.app.feature.create.CreateFinalizationCoordinator
import com.gigagochi.app.feature.create.CreateFinalizationResult
import com.gigagochi.app.feature.create.CreatePendingCoordinator
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.GenerationStatus
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.create.RealPetGenerationAdapter
import com.gigagochi.app.feature.create.executePetGeneration
import com.gigagochi.app.feature.dashboard.BerryReply
import com.gigagochi.app.feature.dashboard.DashboardAmbientResult
import com.gigagochi.app.feature.dashboard.DashboardChatResult
import com.gigagochi.app.feature.dashboard.DashboardEvent
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.DashboardOutcomeApplicationCoordinator
import com.gigagochi.app.feature.dashboard.DashboardReplyAutoAdvanceMillis
import com.gigagochi.app.feature.dashboard.DashboardUiState
import com.gigagochi.app.feature.dashboard.LeafReply
import com.gigagochi.app.feature.dashboard.OnboardingBlockAutoAdvanceMillis
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.RealDashboardChatAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardAmbientAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardTravelAdapter
import com.gigagochi.app.feature.dashboard.reduceDashboard
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChatFallback
import com.gigagochi.app.feature.onboarding.FirstSessionAfterFirstFood
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import com.gigagochi.app.feature.onboarding.FirstSessionAfterNameFallback
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedy
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedyPortions
import com.gigagochi.app.feature.onboarding.FirstSessionSensitiveTopicFallback
import com.gigagochi.app.feature.onboarding.firstSessionReactionReply
import com.gigagochi.app.feature.travel.ScheduledStoryDueResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Android implementation of the native product-state boundary used by the WebView runtime. */
internal class AndroidWebAppDataGateway(
    context: Context,
    private val preferredPetId: () -> String? = { null },
    private val generationPolicy: WebAppGenerationPolicy = WebAppGenerationPolicy.Production,
) : WebAppDataGateway {
    private val applicationContext = context.applicationContext
    private val sessionRepository = androidSessionRepository(applicationContext)
    private val authHeaderProvider = InMemoryAuthHeaderProvider()
    private val refreshExchange = HttpSessionRefreshExchange(
        baseUrl = BuildConfig.BACKEND_BASE_URL,
        allowDebugLoopbackHttp = BuildConfig.DEBUG,
    )
    private val activeSessionAuthority = WebAppActiveSessionAuthority()
    private val activeOwnerId: String?
        get() = activeSessionAuthority.ownerId()

    internal fun currentMediaScope(): WebMediaOwnerScope? =
        activeSessionAuthority.dashboardScope()?.let { scope ->
            WebMediaOwnerScope(scope.ownerId, scope.petId)
        }
    private val featureClient = AuthenticatedFeatureClient(
        repository = sessionRepository,
        refreshExchange = refreshExchange,
        transport = UrlConnectionFeatureHttpTransport(
            baseUrl = BuildConfig.BACKEND_BASE_URL,
            allowDebugLoopbackHttp = BuildConfig.DEBUG,
        ),
        headerProvider = authHeaderProvider,
        onSessionInvalid = {
            activeSessionAuthority.clear()
            authHeaderProvider.clear()
        },
    )
    private val featureApi = AndroidFeatureApi(
        client = featureClient,
        baseUrl = BuildConfig.BACKEND_BASE_URL,
        allowDebugLoopbackHttp = BuildConfig.DEBUG,
    )
    private val database = GigagochiDatabase.build(applicationContext)
    private val petRepository = PetLocalRepository(database)
    private val petLifecycle = AccountPetLifecycle(petRepository)
    private val travelShareStore = PetLocalRepositoryTravelVideoAssetStore(petRepository)
    private val notificationDeepLinkStore =
        PetLocalRepositoryNotificationDeepLinkStore(petRepository)
    private val postReplyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun bootstrap(): WebRuntimeBootstrapResult {
        activeSessionAuthority.clear()
        val outcome = LocalSessionBootstrapCoordinator(
            sessionBootstrap = SessionBootstrapCoordinator(
                repository = sessionRepository,
                refreshExchange = refreshExchange,
            ),
            sessionRepository = sessionRepository,
            installationIdProvider = AndroidInstallationIdProvider(applicationContext),
            guestSessionExchange = HttpGuestSessionExchange(
                baseUrl = BuildConfig.BACKEND_BASE_URL,
                allowDebugLoopbackHttp = BuildConfig.DEBUG,
            ),
        ).bootstrap()
        val ready = outcome as? LocalSessionBootstrapOutcome.Ready
            ?: return WebRuntimeBootstrapResult.ConnectionError
        activeSessionAuthority.activateOwner(ready.session.accountId)
        authHeaderProvider.update(ready.session)
        return when (val destination = petLifecycle.startup(
            ready.session.accountId,
            preferredPetId(),
        )) {
            AccountStartupDestination.Failure -> WebRuntimeBootstrapResult.LocalDataError
            is AccountStartupDestination.Create -> {
                activeSessionAuthority.activateOwner(ready.session.accountId)
                if (destination.pending?.backendJobId != null) {
                    CreateSyncScheduler.enqueue(applicationContext)
                }
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Create(
                        destination.pending?.let {
                            CreatePendingCoordinator(ready.session.accountId, petRepository).restore(it)
                        } ?: CreatePetState(),
                    ),
                )
            }
            is AccountStartupDestination.Dashboard -> {
                val recovery = petRepository.loadOwnerRecovery(ready.session.accountId)
                dispatchWebDashboardBootstrapEffects(
                    destination = destination,
                    enqueueCompletionSync = {
                        CompletionSyncScheduler.enqueue(applicationContext)
                    },
                    enqueueMvpSync = { MvpSyncScheduler.enqueue(applicationContext) },
                    generationPolicy = generationPolicy,
                )
                activeSessionAuthority.activateDashboard(
                    ownerId = ready.session.accountId,
                    petId = destination.pet.petId,
                )
                WebRuntimeBootstrapResult.Ready(
                    WebRuntimeDestination.Dashboard(
                        destination = destination,
                        operations = recovery.dashboardOperationState(destination.pet.petId),
                    ),
                )
            }
        }
    }

    override suspend fun persistCreate(state: CreatePetState): WebCreatePersistenceResult {
        val ownerId = activeOwnerId ?: return WebCreatePersistenceResult.Failure
        return when (CreatePendingCoordinator(ownerId, petRepository).persist(state)) {
            LocalOperationResult.Failure -> WebCreatePersistenceResult.Failure
            is LocalOperationResult.Success -> WebCreatePersistenceResult.Persisted
        }
    }

    override suspend fun generateCreate(
        request: PendingPetGeneration,
    ): PetGenerationExecutionResult {
        val ownerId = activeOwnerId
            ?: return PetGenerationExecutionResult.Failure(newRequestRequired = false)
        val adapter = RealPetGenerationAdapter(
            ownerId = ownerId,
            store = petRepository,
            stateStore = petRepository,
            api = featureApi,
            onJobAttached = { CreateSyncScheduler.enqueue(applicationContext) },
            onGenerationFailed = { petId, requestKey ->
                enqueueCreateFailure(ownerId, petId, requestKey)
                CreateSyncScheduler.enqueue(applicationContext)
            },
        )
        return executePetGeneration(adapter, request)
    }

    override suspend fun finalizeCreate(
        state: CreatePetState,
        foregroundHandled: Boolean,
    ): WebCreateFinalizationResult {
        val ownerId = activeOwnerId ?: return WebCreateFinalizationResult.Failure
        val result = CreateFinalizationCoordinator(
            ownerId = ownerId,
            lifecycle = petLifecycle,
            store = petRepository,
            stateStore = petRepository,
        ).finalize(state, foregroundHandled)
        if (result !is CreateFinalizationResult.Success) {
            return WebCreateFinalizationResult.Failure
        }
        val generated = (state.generation as? GenerationStatus.Ready)?.pet
        if (generated?.backgroundGenerationPending == true || !foregroundHandled) {
            CreateSyncScheduler.enqueue(applicationContext)
        }
        return when (val destination = petLifecycle.startup(ownerId, result.pet.petId)) {
            is AccountStartupDestination.Dashboard -> {
                activeSessionAuthority.activateDashboard(ownerId, destination.pet.petId)
                WebCreateFinalizationResult.Success(destination)
            }
            else -> WebCreateFinalizationResult.Failure
        }
    }

    override suspend fun applyPetTap(expectedPet: PetDashboardState): WebPetTapMutationResult {
        val ownerId = activeOwnerId ?: return WebPetTapMutationResult.LocalDataError
        return try {
            when (
                val result = petRepository.mutatePetSnapshot(
                    ownerId = ownerId,
                    petId = expectedPet.petId,
                    expectedAssetSetId = expectedPet.assetSetId,
                ) { latest ->
                    if (latest.petTapProgress != expectedPet.petTapProgress) return@mutatePetSnapshot null
                    reduceDashboard(
                        DashboardUiState(latest),
                        DashboardEvent.PetTapped(),
                    ).pet
                }
            ) {
                is PetSnapshotMutationResult.Applied -> WebPetTapMutationResult.Applied(result.pet)
                PetSnapshotMutationResult.Conflict -> WebPetTapMutationResult.Conflict
                PetSnapshotMutationResult.Missing -> WebPetTapMutationResult.Missing
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebPetTapMutationResult.LocalDataError
        }
    }

    override suspend fun reserveChat(
        request: PendingChatRequest,
        expectedPet: PetDashboardState,
        originFirstSessionStage: FirstSessionStage?,
        queueAnchorRequestKey: String?,
        replacingQueuedRequestKey: String?,
    ): WebChatReservationResult {
        val ownerId = activeOwnerId ?: return WebChatReservationResult.LocalDataError
        return try {
            when (
                val result = petRepository.reserveDashboardChat(
                    ownerId = ownerId,
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebChatReservationResult.LocalDataError
        }
    }

    override suspend fun executeChat(
        request: PendingChatRequest,
        expectedPet: PetDashboardState,
        expectedFirstSessionStage: FirstSessionStage?,
    ): WebChatExecutionResult {
        val ownerId = activeOwnerId ?: return WebChatExecutionResult.Failure
        return try {
            val livePet = petRepository.decayPetSnapshot(ownerId, expectedPet.petId)?.pet
                ?: return deletePendingChatAndFail(ownerId, request.requestKey)
            if (livePet.assetSetId != expectedPet.assetSetId) {
                return deletePendingChatAndFail(ownerId, request.requestKey)
            }
            val adapterResult = RealDashboardChatAdapter(
                api = featureApi,
                ownerId = ownerId,
                repository = petRepository,
                postReplyScope = postReplyScope,
            ).reply(request, livePet)
            val completed = completeDashboardFirstSessionChat(
                ownerId = ownerId,
                repository = petRepository,
                request = request,
                adapterResult = adapterResult,
                originFirstSessionStage = expectedFirstSessionStage,
            )
            if (completed is WebChatExecutionResult.Failure) {
                retainedChatFailure(ownerId, request)
            } else {
                completed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retainedChatFailure(ownerId, request)
        }
    }

    private suspend fun retainedChatFailure(
        ownerId: String,
        request: PendingChatRequest,
    ): WebChatExecutionResult {
        val pending = try {
            petRepository.getPendingChat(ownerId, request.requestKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return WebChatExecutionResult.RetryableFailure(null)
        }
        if (pending == null || pending.message != request.message) {
            return WebChatExecutionResult.Failure
        }
        val response = pending.responseText?.takeIf(String::isNotBlank)
            ?: return WebChatExecutionResult.RetryableFailure(pending)
        val pet = try {
            petRepository.getPetSnapshot(ownerId, pending.petId)?.pet
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return WebChatExecutionResult.RetryableFailure(pending)
        return try {
            when (
                val recovered = completeDashboardFirstSessionChat(
                    ownerId = ownerId,
                    repository = petRepository,
                    request = request,
                    adapterResult = DashboardChatResult(response, pet),
                    originFirstSessionStage = pending.originFirstSessionStage,
                )
            ) {
                is WebChatExecutionResult.Success -> recovered
                else -> WebChatExecutionResult.RetryableFailure(pending)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebChatExecutionResult.RetryableFailure(pending)
        }
    }

    private suspend fun deletePendingChatAndFail(
        ownerId: String,
        requestKey: String,
    ): WebChatExecutionResult {
        try {
            petRepository.deletePendingChat(ownerId, requestKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
        return WebChatExecutionResult.Failure
    }

    override suspend fun acknowledgeChat(requestKey: String): Boolean {
        val ownerId = activeOwnerId ?: return false
        return try {
            val pending = petRepository.getPendingChat(ownerId, requestKey)
            pending == null || petRepository.deletePendingChat(ownerId, requestKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun applyFeed(
        requestKey: String,
        food: DashboardFood,
        audioIndex: Int,
        expectedPet: PetDashboardState,
    ): WebFeedMutationResult {
        val ownerId = activeOwnerId ?: return WebFeedMutationResult.LocalDataError
        val defaultPresentation = LocalDashboardFeedPresentation(
            reply = when (food) {
                DashboardFood.BerryBowl -> BerryReply
                DashboardFood.LeafCrunch -> LeafReply
            },
            autoAdvanceDelayMillis = DashboardReplyAutoAdvanceMillis,
        )
        return try {
            when (
                val result = petRepository.applyDashboardFeed(
                    ownerId = ownerId,
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebFeedMutationResult.LocalDataError
        }
    }

    override suspend fun reserveOutfit(
        request: PendingOutfitRequest,
        expectedPet: PetDashboardState,
    ): WebOutfitReservationResult {
        val ownerId = activeOwnerId ?: return WebOutfitReservationResult.LocalDataError
        return try {
            when (
                val result = petRepository.reserveDashboardOutfit(
                    ownerId = ownerId,
                    petId = expectedPet.petId,
                    expectedAssetSetId = expectedPet.assetSetId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                )
            ) {
                is DashboardOutfitReservationResult.Accepted ->
                    WebOutfitReservationResult.Accepted(
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebOutfitReservationResult.LocalDataError
        }
    }

    override suspend fun executeOutfit(
        request: LocalPendingOutfit,
        expectedPet: PetDashboardState,
    ): WebDashboardOperationExecutionResult {
        val ownerId = activeOwnerId ?: return WebDashboardOperationExecutionResult.Failure
        try {
            val livePet = petRepository.decayPetSnapshot(ownerId, expectedPet.petId)?.pet
                ?: return WebDashboardOperationExecutionResult.Failure
            if (
                livePet.assetSetId != expectedPet.assetSetId ||
                request.petId != livePet.petId ||
                request.baseAssetSetId != livePet.assetSetId
            ) {
                return WebDashboardOperationExecutionResult.Failure
            }
            try {
                outfitAdapter(ownerId).queue(
                    PendingOutfitRequest(request.requestKey, request.prompt),
                    livePet,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The adapter persisted a retryable/terminal state before surfacing the failure.
            }
            applyReadyDashboardOutcomes(ownerId, request.petId)
            return loadDashboardRecovery(ownerId, request.petId)
                ?: WebDashboardOperationExecutionResult.Failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return loadDashboardRecovery(ownerId, request.petId)
                ?: WebDashboardOperationExecutionResult.Failure
        }
    }

    override suspend fun reserveTravel(
        request: PendingTravelRequest,
        expectedPet: PetDashboardState,
    ): WebTravelReservationResult {
        val ownerId = activeOwnerId ?: return WebTravelReservationResult.LocalDataError
        return try {
            when (
                val result = petRepository.reserveDashboardTravel(
                    ownerId = ownerId,
                    petId = expectedPet.petId,
                    expectedAssetSetId = expectedPet.assetSetId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                )
            ) {
                is DashboardTravelReservationResult.Accepted ->
                    WebTravelReservationResult.Accepted(
                        result.pet,
                        result.request,
                        result.newlyAccepted,
                    )
                is DashboardTravelReservationResult.Finished ->
                    WebTravelReservationResult.Finished(result.pet, result.result)
                is DashboardTravelReservationResult.Busy ->
                    WebTravelReservationResult.Busy(result.pet, result.request)
                DashboardTravelReservationResult.Missing -> WebTravelReservationResult.Missing
                DashboardTravelReservationResult.WrongStage -> WebTravelReservationResult.WrongStage
                DashboardTravelReservationResult.Conflict -> WebTravelReservationResult.Conflict
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebTravelReservationResult.LocalDataError
        }
    }

    override suspend fun executeTravel(
        request: LocalPendingTravelVideo,
        expectedPet: PetDashboardState,
    ): WebDashboardOperationExecutionResult {
        val ownerId = activeOwnerId ?: return WebDashboardOperationExecutionResult.Failure
        try {
            val livePet = petRepository.decayPetSnapshot(ownerId, expectedPet.petId)?.pet
                ?: return WebDashboardOperationExecutionResult.Failure
            if (livePet.assetSetId != expectedPet.assetSetId || request.petId != livePet.petId) {
                return WebDashboardOperationExecutionResult.Failure
            }
            try {
                travelAdapter(ownerId).queue(
                    PendingTravelRequest(request.requestKey, request.prompt),
                    livePet,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The adapter persisted a retryable/terminal state before surfacing the failure.
            }
            applyReadyDashboardOutcomes(ownerId, request.petId)
            return loadDashboardRecovery(ownerId, request.petId)
                ?: WebDashboardOperationExecutionResult.Failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return loadDashboardRecovery(ownerId, request.petId)
                ?: WebDashboardOperationExecutionResult.Failure
        }
    }

    override suspend fun refreshDashboardOperations(
        petId: String,
    ): WebDashboardOperationExecutionResult {
        val ownerId = activeOwnerId ?: return WebDashboardOperationExecutionResult.Failure
        return try {
            val recovery = petRepository.loadOwnerRecovery(ownerId)
            val outfit = outfitAdapter(ownerId)
            val travel = travelAdapter(ownerId)
            recovery.pendingOutfits.filter {
                it.petId == petId && it.backendJobId != null &&
                    it.backendState in PollableGatewayDashboardStates
            }.forEach { pending ->
                try {
                    outfit.pollOnce(pending)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Durable adapter state is authoritative and will be returned below.
                }
            }
            recovery.pendingTravels.filter {
                it.petId == petId && it.backendJobId != null &&
                    it.backendState in PollableGatewayDashboardStates
            }.forEach { pending ->
                try {
                    travel.pollOnce(pending)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Durable adapter state is authoritative and will be returned below.
                }
            }
            applyReadyDashboardOutcomes(ownerId, petId)
            loadDashboardRecovery(ownerId, petId)
                ?: WebDashboardOperationExecutionResult.Failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadDashboardRecovery(ownerId, petId)
                ?: WebDashboardOperationExecutionResult.Failure
        }
    }

    override suspend fun refreshDashboardForForeground(
        petId: String,
    ): WebDashboardForegroundRefreshResult {
        val ownerId = activeOwnerId ?: return WebDashboardForegroundRefreshResult.LocalDataError
        return try {
            petRepository.decayPetSnapshot(ownerId, petId)
                ?: return WebDashboardForegroundRefreshResult.Missing
            val refreshed = loadDashboardRecovery(ownerId, petId)
                ?: return WebDashboardForegroundRefreshResult.LocalDataError
            WebDashboardForegroundRefreshResult.Updated(refreshed.recovery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebDashboardForegroundRefreshResult.LocalDataError
        }
    }

    override suspend fun generateAmbientReply(
        requestKey: String,
        expectedPet: PetDashboardState,
    ): WebAmbientGenerationResult = dispatchWebAutomaticAmbientGeneration(generationPolicy) {
        val ownerId = activeOwnerId
            ?: return@dispatchWebAutomaticAmbientGeneration WebAmbientGenerationResult.Failure
        try {
            WebAmbientGenerationResult.Success(
                RealDashboardAmbientAdapter(
                    api = featureApi,
                    ownerId = ownerId,
                    repository = petRepository,
                ).reply(requestKey, expectedPet),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebAmbientGenerationResult.Failure
        }
    }

    override suspend fun persistAmbientReply(
        expectedPet: PetDashboardState,
        result: DashboardAmbientResult,
    ): WebAmbientPersistenceResult {
        val ownerId = activeOwnerId ?: return WebAmbientPersistenceResult.LocalDataError
        if (
            result.reply.isBlank() ||
            result.pet.petId != expectedPet.petId ||
            result.pet.assetSetId != expectedPet.assetSetId ||
            result.pet != expectedPet.copy(message = result.reply)
        ) {
            return WebAmbientPersistenceResult.Conflict
        }
        return try {
            when (
                val persisted = petRepository.mutatePetSnapshot(
                    ownerId = ownerId,
                    petId = expectedPet.petId,
                    expectedAssetSetId = expectedPet.assetSetId,
                ) { latest ->
                    // Merge only the generated message onto the latest decayed snapshot. This
                    // cannot roll back taps, food, XP, pending outcomes, or a newer asset set.
                    mergeAmbientReplyOntoLatest(latest, expectedPet, result.reply)
                }
            ) {
                is PetSnapshotMutationResult.Applied ->
                    WebAmbientPersistenceResult.Applied(persisted.pet)
                PetSnapshotMutationResult.Missing -> WebAmbientPersistenceResult.Missing
                PetSnapshotMutationResult.Conflict -> WebAmbientPersistenceResult.Conflict
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebAmbientPersistenceResult.LocalDataError
        }
    }

    override suspend fun resolveTravelVideoShare(
        requestKey: String,
    ): TravelVideoShareLookupResult {
        val scope = activeSessionAuthority.dashboardScope()
            ?: return TravelVideoShareLookupResult.Invalid
        val resolved = try {
            TravelVideoShareResolver(
                ownerId = scope.ownerId,
                activePetId = scope.petId,
                store = travelShareStore,
            ).resolve(requestKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TravelVideoShareLookupResult.Missing
        }
        return if (activeSessionAuthority.isCurrent(scope)) {
            resolved
        } else {
            TravelVideoShareLookupResult.Invalid
        }
    }

    override suspend fun resolveNotificationDeepLink(
        extras: NotificationDeepLinkExtras,
    ): NotificationDeepLinkDestination {
        val scope = activeSessionAuthority.dashboardScope()
            ?: return NotificationDeepLinkDestination.Dashboard
        val resolved = try {
            NotificationDeepLinkResolver(
                ownerId = scope.ownerId,
                activePetId = scope.petId,
                store = notificationDeepLinkStore,
            ).resolve(extras)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationDeepLinkDestination.Dashboard
        }
        return if (activeSessionAuthority.isCurrent(scope)) {
            resolved
        } else {
            NotificationDeepLinkDestination.Dashboard
        }
    }

    override fun authenticatedEventStoryGateway(): EventStoryGateway? {
        val ownerId = activeOwnerId ?: return null
        return webEventStoryGatewayForGenerationPolicy(
            generationPolicy = generationPolicy,
            delegate = AndroidEventStoryGateway(
                ownerId = ownerId,
                repository = petRepository,
                api = featureApi,
            ),
        )
    }

    override fun enqueueEventStoryCompletionSync() {
        if (activeOwnerId != null && generationPolicy.backgroundFeatureSyncEnabled) {
            CompletionSyncScheduler.enqueue(applicationContext)
        }
    }

    private fun outfitAdapter(ownerId: String) = RealDashboardOutfitAdapter(
        ownerId = ownerId,
        store = petRepository,
        stateStore = petRepository,
        outcomeStore = petRepository,
        api = featureApi,
        onJobAttached = { enqueueCompletionSyncIfEnabled() },
        onOutfitFailed = { petId, requestKey ->
            enqueueManualFailure(ownerId, petId, requestKey, ManualGenerationKind.Outfit)
            enqueueCompletionSyncIfEnabled()
        },
    )

    private fun travelAdapter(ownerId: String) = RealDashboardTravelAdapter(
        ownerId = ownerId,
        store = petRepository,
        stateStore = petRepository,
        outcomeStore = petRepository,
        api = featureApi,
        onJobAttached = { enqueueCompletionSyncIfEnabled() },
        onTravelFailed = { petId, requestKey ->
            enqueueManualFailure(ownerId, petId, requestKey, ManualGenerationKind.Travel)
            enqueueCompletionSyncIfEnabled()
        },
    )

    private fun enqueueCompletionSyncIfEnabled() {
        if (generationPolicy.backgroundFeatureSyncEnabled) {
            CompletionSyncScheduler.enqueue(applicationContext)
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun applyReadyDashboardOutcomes(ownerId: String, petId: String) {
        DashboardOutcomeApplicationCoordinator(
            ownerId = ownerId,
            recoveryStore = petRepository,
            outcomeStore = petRepository,
            stateStore = petRepository,
            onMediaReplaced = StaticMediaCache::evict,
        ).applyReady(petId)
        dispatchCompletionNotifications(ownerId, petId)
    }

    private suspend fun loadDashboardRecovery(
        ownerId: String,
        petId: String,
    ): WebDashboardOperationExecutionResult.Updated? = try {
        val destination = petLifecycle.startup(ownerId, petId)
            as? AccountStartupDestination.Dashboard ?: return null
        val recovery = petRepository.loadOwnerRecovery(ownerId)
        WebDashboardOperationExecutionResult.Updated(
            WebDashboardRecovery(
                destination = destination,
                operations = recovery.dashboardOperationState(petId),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override fun close() {
        activeSessionAuthority.clear()
        authHeaderProvider.clear()
        postReplyScope.cancel()
        database.close()
    }

    private suspend fun enqueueCreateFailure(
        ownerId: String,
        petId: String,
        requestKey: String,
    ) {
        enqueueManualFailure(ownerId, petId, requestKey, ManualGenerationKind.Create)
    }

    private suspend fun enqueueManualFailure(
        ownerId: String,
        petId: String,
        requestKey: String,
        kind: ManualGenerationKind,
    ) {
        try {
            val notification = manualGenerationFailedNotification(kind, requestKey)
            if (!petRepository.enqueueNotification(ownerId, petId, notification)) return
            dispatchCompletionNotifications(ownerId, petId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable failed pending remains recoverable even if the outbox is unavailable.
        }
    }

    private suspend fun dispatchCompletionNotifications(ownerId: String, petId: String) {
        CompletionNotificationDispatcher(
            notificationsAllowed = { notificationsAllowed(applicationContext) },
            loadNotifications = { notificationOwnerId, notificationPetId ->
                petRepository.getUnnotifiedNotifications(notificationOwnerId, notificationPetId)
            },
            emitter = AndroidLocalNotificationEmitter(applicationContext),
            markNotified = { notificationOwnerId, pendingNotification ->
                petRepository.markNotificationSent(
                    notificationOwnerId,
                    pendingNotification,
                    System.currentTimeMillis(),
                )
            },
        ).dispatch(ownerId, petId)
    }
}

internal data class WebAppDashboardSessionScope(
    val token: Long,
    val ownerId: String,
    val petId: String,
)

/** Atomically publishes owner+pet so a resolver can never combine two different sessions. */
internal class WebAppActiveSessionAuthority {
    private data class ActiveSession(
        val token: Long,
        val ownerId: String,
        val petId: String?,
    )

    private val lock = Any()
    private var tokenSequence = 0L

    @Volatile
    private var active: ActiveSession? = null

    fun activateOwner(ownerId: String) {
        require(ownerId.isNotBlank())
        synchronized(lock) {
            tokenSequence += 1L
            active = ActiveSession(tokenSequence, ownerId, null)
        }
    }

    fun activateDashboard(ownerId: String, petId: String) {
        require(ownerId.isNotBlank())
        require(petId.isNotBlank())
        synchronized(lock) {
            tokenSequence += 1L
            active = ActiveSession(tokenSequence, ownerId, petId)
        }
    }

    fun clear() {
        synchronized(lock) {
            tokenSequence += 1L
            active = null
        }
    }

    fun ownerId(): String? = active?.ownerId

    fun dashboardScope(): WebAppDashboardSessionScope? = active?.let { current ->
        val petId = current.petId ?: return null
        WebAppDashboardSessionScope(current.token, current.ownerId, petId)
    }

    fun isCurrent(scope: WebAppDashboardSessionScope): Boolean {
        val current = active ?: return false
        return current.token == scope.token &&
            current.ownerId == scope.ownerId &&
            current.petId == scope.petId
    }
}

internal fun mergeAmbientReplyOntoLatest(
    latest: PetDashboardState,
    expectedPet: PetDashboardState,
    reply: String,
): PetDashboardState? {
    if (
        reply.isBlank() ||
        latest.petId != expectedPet.petId ||
        latest.assetSetId != expectedPet.assetSetId ||
        (latest.message != expectedPet.message && latest.message != reply)
    ) {
        return null
    }
    return latest.copy(message = reply)
}

/** Injectable pure seam for production Dashboard bootstrap side effects. */
internal fun dispatchWebDashboardBootstrapEffects(
    destination: AccountStartupDestination.Dashboard,
    enqueueCompletionSync: () -> Unit,
    enqueueMvpSync: () -> Unit,
    generationPolicy: WebAppGenerationPolicy = WebAppGenerationPolicy.Production,
) {
    if (!generationPolicy.backgroundFeatureSyncEnabled) return
    if (
        destination.pendingOutfit?.backendJobId != null ||
        destination.pendingTravel?.backendJobId != null
    ) {
        enqueueCompletionSync()
    }
    // Native production keeps this unique KEEP work alive for decay, due stories, proactive
    // messages and the durable notification outbox. Replaying bootstrap is therefore safe.
    enqueueMvpSync()
}

internal suspend fun dispatchWebAutomaticAmbientGeneration(
    generationPolicy: WebAppGenerationPolicy,
    generate: suspend () -> WebAmbientGenerationResult,
): WebAmbientGenerationResult =
    if (generationPolicy.automaticGenerationEnabled) {
        generate()
    } else {
        WebAmbientGenerationResult.Failure
    }

internal fun webEventStoryGatewayForGenerationPolicy(
    generationPolicy: WebAppGenerationPolicy,
    delegate: EventStoryGateway,
): EventStoryGateway =
    if (generationPolicy.automaticGenerationEnabled) {
        delegate
    } else {
        object : EventStoryGateway by delegate {
            override suspend fun checkDue(
                pet: PetDashboardState,
            ): ScheduledStoryDueResult = ScheduledStoryDueResult.NotDue
        }
    }

private val PollableGatewayDashboardStates = setOf(
    PendingBackendState.Attached,
    PendingBackendState.ForegroundReady,
    PendingBackendState.Ready,
)

private fun OwnerRecoveryData.dashboardOperationState(petId: String) =
    WebDashboardOperationState(
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

/**
 * Completes the local onboarding side of a durable chat response. Keeping this separate from the
 * network adapter makes both crash windows replayable: response persisted before stage advance,
 * and stage advanced before the reply snapshot reached WebView.
 */
internal suspend fun completeDashboardFirstSessionChat(
    ownerId: String,
    repository: PetLocalRepository,
    request: PendingChatRequest,
    adapterResult: DashboardChatResult,
    originFirstSessionStage: FirstSessionStage?,
    nowEpochMillis: Long = System.currentTimeMillis(),
): WebChatExecutionResult {
    if (originFirstSessionStage == null) {
        return WebChatExecutionResult.Success(
            result = adapterResult,
            firstSession = repository.getFirstSession(ownerId, adapterResult.pet.petId),
            pendingChat = repository.getPendingChat(ownerId, request.requestKey),
        )
    }
    if (
        originFirstSessionStage != FirstSessionStage.AwaitingChat &&
        originFirstSessionStage != FirstSessionStage.AwaitingChatFollowup
    ) {
        return WebChatExecutionResult.Failure
    }
    val mutation = repository.advanceDashboardChatFirstSession(
        ownerId = ownerId,
        petId = adapterResult.pet.petId,
        originFirstSessionStage = originFirstSessionStage,
        requestKey = request.requestKey,
        message = request.message,
        nowEpochMillis = nowEpochMillis,
    )
    val durablePet = when (mutation) {
        is FirstSessionMutationResult.Applied -> mutation.pet
        is FirstSessionMutationResult.AlreadyApplied -> mutation.pet
        FirstSessionMutationResult.Missing,
        FirstSessionMutationResult.WrongStage,
        -> return WebChatExecutionResult.Failure
    }
    val durableSession = when (mutation) {
        is FirstSessionMutationResult.Applied -> mutation.session
        is FirstSessionMutationResult.AlreadyApplied -> mutation.session
        else -> error("unreachable")
    }
    val fallback = if (originFirstSessionStage == FirstSessionStage.AwaitingChat) {
        FirstSessionAfterNameFallback
    } else {
        FirstSessionAfterChatFallback
    }
    val reaction = firstSessionReactionReply(
        reply = adapterResult.reply,
        fallback = fallback,
        petName = durablePet.name,
        unsafeReplyFallback = if (
            originFirstSessionStage == FirstSessionStage.AwaitingChatFollowup
        ) {
            FirstSessionSensitiveTopicFallback
        } else {
            FirstSessionAfterNameFallback
        },
    )
    val prompt = if (originFirstSessionStage == FirstSessionStage.AwaitingChat) {
        FirstSessionAfterName
    } else {
        FirstSessionAfterChat
    }
    return WebChatExecutionResult.Success(
        result = DashboardChatResult(
            reply = "$reaction $prompt",
            pet = durablePet,
            explicitPortions = listOf(reaction, prompt),
        ),
        firstSession = durableSession,
        pendingChat = repository.getPendingChat(ownerId, request.requestKey),
    )
}
