package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.LocalDashboardFeedReceipt
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.database.LocalPendingChat
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.security.NotificationDeepLinkDestination
import com.gigagochi.app.core.security.NotificationDeepLinkExtras
import com.gigagochi.app.core.security.ParsedNotificationDeepLink
import com.gigagochi.app.core.security.TravelVideoShareLookupResult
import com.gigagochi.app.core.security.parseNotificationDeepLink
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.CreationQuestions
import com.gigagochi.app.feature.create.CreationBackgroundPhase
import com.gigagochi.app.feature.create.GenerationStatus
import com.gigagochi.app.feature.create.MaxCustomPromptLength
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationExecutionResult
import com.gigagochi.app.feature.create.answer
import com.gigagochi.app.feature.create.markGenerationFailed
import com.gigagochi.app.feature.create.markGenerationReady
import com.gigagochi.app.feature.create.markTransitionComplete
import com.gigagochi.app.feature.create.retryGeneration
import com.gigagochi.app.feature.dashboard.DashboardChatResult
import com.gigagochi.app.feature.dashboard.DashboardAmbientResult
import com.gigagochi.app.feature.dashboard.DashboardEvent
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.ChatFailureMessage
import com.gigagochi.app.feature.dashboard.DashboardMode
import com.gigagochi.app.feature.dashboard.DashboardMinimumThinkingMillis
import com.gigagochi.app.feature.dashboard.DashboardSaveMaxAttempts
import com.gigagochi.app.feature.dashboard.DashboardSaveRetryDelayMillis
import com.gigagochi.app.feature.dashboard.OutfitExperienceCost
import com.gigagochi.app.feature.dashboard.OutfitFailureMessage
import com.gigagochi.app.feature.dashboard.OutfitInsufficientMessage
import com.gigagochi.app.feature.dashboard.DashboardPromptMaxLength
import com.gigagochi.app.feature.dashboard.DashboardReply
import com.gigagochi.app.feature.dashboard.DashboardUiState
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingFeedRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.PetTapThanksReplies
import com.gigagochi.app.feature.dashboard.PetTapThanksVisibleMillis
import com.gigagochi.app.feature.dashboard.canonicalOutfitDisplayItem
import com.gigagochi.app.feature.dashboard.dashboardIdleMessage
import com.gigagochi.app.feature.dashboard.firstSessionIdleReply
import com.gigagochi.app.feature.dashboard.isFirstSessionReplyPending
import com.gigagochi.app.feature.dashboard.outfitQueuedReply
import com.gigagochi.app.feature.dashboard.reduceDashboard
import com.gigagochi.app.feature.dashboard.toUi
import com.gigagochi.app.feature.dashboard.travelQueuedReply
import com.gigagochi.app.feature.dashboard.TravelFailureMessage
import com.gigagochi.app.feature.events.eventHistoryUiState
import com.gigagochi.app.feature.events.EventHistoryItem
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChatFallback
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import com.gigagochi.app.feature.onboarding.FirstSessionAfterNameFallback
import com.gigagochi.app.feature.onboarding.FirstSessionSensitiveTopicFallback
import com.gigagochi.app.feature.onboarding.firstSessionDashboardMessagePortions
import com.gigagochi.app.feature.onboarding.firstSessionMainAction
import com.gigagochi.app.feature.onboarding.firstSessionReactionReply
import com.gigagochi.app.feature.travel.onboardingBatStory
import com.gigagochi.app.feature.travel.TravelStoryChoiceFailureMessage
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

internal sealed interface WebRuntimeDestination {
    data class Create(val state: CreatePetState) : WebRuntimeDestination
    data class Dashboard(
        val destination: AccountStartupDestination.Dashboard,
        val operations: WebDashboardOperationState = WebDashboardOperationState(),
    ) : WebRuntimeDestination
}

internal data class WebDashboardOperationState(
    val outfit: LocalPendingOutfit? = null,
    val travel: LocalPendingTravelVideo? = null,
    val latestTravelResult: LocalTravelVideoAsset? = null,
)

internal data class WebDashboardRecovery(
    val destination: AccountStartupDestination.Dashboard,
    val operations: WebDashboardOperationState,
)

internal sealed interface WebRuntimeBootstrapResult {
    data class Ready(val destination: WebRuntimeDestination) : WebRuntimeBootstrapResult
    data object ConnectionError : WebRuntimeBootstrapResult
    data object LocalDataError : WebRuntimeBootstrapResult
}

internal interface WebAppDataGateway : AutoCloseable {
    suspend fun bootstrap(): WebRuntimeBootstrapResult

    suspend fun persistCreate(state: CreatePetState): WebCreatePersistenceResult

    suspend fun generateCreate(request: PendingPetGeneration): PetGenerationExecutionResult

    suspend fun finalizeCreate(
        state: CreatePetState,
        foregroundHandled: Boolean,
    ): WebCreateFinalizationResult

    suspend fun applyPetTap(expectedPet: PetDashboardState): WebPetTapMutationResult

    suspend fun reserveChat(
        request: PendingChatRequest,
        expectedPet: PetDashboardState,
        originFirstSessionStage: FirstSessionStage?,
        queueAnchorRequestKey: String? = null,
        replacingQueuedRequestKey: String? = null,
    ): WebChatReservationResult

    suspend fun executeChat(
        request: PendingChatRequest,
        expectedPet: PetDashboardState,
        expectedFirstSessionStage: FirstSessionStage?,
    ): WebChatExecutionResult

    suspend fun acknowledgeChat(requestKey: String): Boolean

    suspend fun applyFeed(
        requestKey: String,
        food: DashboardFood,
        audioIndex: Int,
        expectedPet: PetDashboardState,
    ): WebFeedMutationResult

    suspend fun reserveOutfit(
        request: PendingOutfitRequest,
        expectedPet: PetDashboardState,
    ): WebOutfitReservationResult

    suspend fun executeOutfit(
        request: LocalPendingOutfit,
        expectedPet: PetDashboardState,
    ): WebDashboardOperationExecutionResult

    suspend fun reserveTravel(
        request: PendingTravelRequest,
        expectedPet: PetDashboardState,
    ): WebTravelReservationResult

    suspend fun executeTravel(
        request: LocalPendingTravelVideo,
        expectedPet: PetDashboardState,
    ): WebDashboardOperationExecutionResult

    suspend fun refreshDashboardOperations(
        petId: String,
    ): WebDashboardOperationExecutionResult

    suspend fun refreshDashboardForForeground(
        petId: String,
    ): WebDashboardForegroundRefreshResult

    suspend fun generateAmbientReply(
        requestKey: String,
        expectedPet: PetDashboardState,
    ): WebAmbientGenerationResult = WebAmbientGenerationResult.Failure

    suspend fun persistAmbientReply(
        expectedPet: PetDashboardState,
        result: DashboardAmbientResult,
    ): WebAmbientPersistenceResult = WebAmbientPersistenceResult.LocalDataError

    suspend fun resolveTravelVideoShare(
        requestKey: String,
    ): TravelVideoShareLookupResult = TravelVideoShareLookupResult.Invalid

    suspend fun resolveNotificationDeepLink(
        extras: NotificationDeepLinkExtras,
    ): NotificationDeepLinkDestination = NotificationDeepLinkDestination.Dashboard

    /** Available only after an authenticated Dashboard bootstrap. */
    fun authenticatedEventStoryGateway(): EventStoryGateway? = null

    fun enqueueEventStoryCompletionSync() = Unit

    override fun close() = Unit
}

internal enum class WebCreatePersistenceResult { Persisted, Failure }

internal sealed interface WebCreateFinalizationResult {
    data class Success(
        val destination: AccountStartupDestination.Dashboard,
    ) : WebCreateFinalizationResult

    data object Failure : WebCreateFinalizationResult
}

internal sealed interface WebPetTapMutationResult {
    data class Applied(val pet: PetDashboardState) : WebPetTapMutationResult
    data object Missing : WebPetTapMutationResult
    data object Conflict : WebPetTapMutationResult
    data object LocalDataError : WebPetTapMutationResult
}

internal sealed interface WebAmbientGenerationResult {
    data class Success(val result: DashboardAmbientResult) : WebAmbientGenerationResult
    data object Failure : WebAmbientGenerationResult
}

internal sealed interface WebAmbientPersistenceResult {
    data class Applied(val pet: PetDashboardState) : WebAmbientPersistenceResult
    data object Missing : WebAmbientPersistenceResult
    data object Conflict : WebAmbientPersistenceResult
    data object LocalDataError : WebAmbientPersistenceResult
}

internal sealed interface WebDashboardForegroundRefreshResult {
    data class Updated(val recovery: WebDashboardRecovery) :
        WebDashboardForegroundRefreshResult

    data object Missing : WebDashboardForegroundRefreshResult
    data object LocalDataError : WebDashboardForegroundRefreshResult
}

internal sealed interface WebChatReservationResult {
    data class Pending(
        val pet: PetDashboardState,
        val pendingChat: LocalPendingChat,
        val originFirstSessionStage: FirstSessionStage?,
    ) : WebChatReservationResult

    data class Finished(val pet: PetDashboardState) : WebChatReservationResult
    data object Missing : WebChatReservationResult
    data object Conflict : WebChatReservationResult
    data object LocalDataError : WebChatReservationResult
}

internal sealed interface WebChatExecutionResult {
    data class Success(
        val result: DashboardChatResult,
        val firstSession: LocalFirstSession?,
        val pendingChat: LocalPendingChat?,
    ) : WebChatExecutionResult

    data class RetryableFailure(
        val pendingChat: LocalPendingChat?,
    ) : WebChatExecutionResult

    data object Failure : WebChatExecutionResult
}

internal sealed interface WebFeedMutationResult {
    data class Applied(
        val pet: PetDashboardState,
        val firstSession: LocalFirstSession?,
        val receipt: LocalDashboardFeedReceipt,
        val newlyApplied: Boolean,
    ) : WebFeedMutationResult

    data object Missing : WebFeedMutationResult
    data object Conflict : WebFeedMutationResult
    data object WrongStage : WebFeedMutationResult
    data object LocalDataError : WebFeedMutationResult
}

internal sealed interface WebOutfitReservationResult {
    data class Accepted(
        val pet: PetDashboardState,
        val request: LocalPendingOutfit,
        val newlyAccepted: Boolean,
    ) : WebOutfitReservationResult

    data class Finished(val pet: PetDashboardState) : WebOutfitReservationResult
    data class Busy(
        val pet: PetDashboardState,
        val request: LocalPendingOutfit,
    ) : WebOutfitReservationResult

    data class InsufficientExperience(val pet: PetDashboardState) :
        WebOutfitReservationResult
    data object Missing : WebOutfitReservationResult
    data object WrongStage : WebOutfitReservationResult
    data object Conflict : WebOutfitReservationResult
    data object LocalDataError : WebOutfitReservationResult
}

internal sealed interface WebTravelReservationResult {
    data class Accepted(
        val pet: PetDashboardState,
        val request: LocalPendingTravelVideo,
        val newlyAccepted: Boolean,
    ) : WebTravelReservationResult

    data class Finished(
        val pet: PetDashboardState,
        val result: LocalTravelVideoAsset,
    ) : WebTravelReservationResult

    data class Busy(
        val pet: PetDashboardState,
        val request: LocalPendingTravelVideo,
    ) : WebTravelReservationResult
    data object Missing : WebTravelReservationResult
    data object WrongStage : WebTravelReservationResult
    data object Conflict : WebTravelReservationResult
    data object LocalDataError : WebTravelReservationResult
}

internal sealed interface WebDashboardOperationExecutionResult {
    data class Updated(val recovery: WebDashboardRecovery) :
        WebDashboardOperationExecutionResult
    data object Failure : WebDashboardOperationExecutionResult
}

private const val CreatePersistenceErrorMessage =
    "Не удалось сохранить создание. Попробуйте ещё раз."
private const val CreateFinalizationErrorMessage =
    "Не удалось сохранить питомца. Попробуйте ещё раз."
private const val DashboardOperationPollDelayMillis = 3_000L
private const val DashboardOperationMaxPollAttempts = 200
private const val ForegroundRefreshIntervalMillis = 15 * 60 * 1_000L
private val CanonicalCommandRequestKey = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val DispatchableDashboardOperationStates = setOf(
    PendingBackendState.Pending,
    PendingBackendState.Retryable,
)
private val PollableDashboardOperationStates = setOf(
    PendingBackendState.Attached,
    PendingBackendState.ForegroundReady,
    PendingBackendState.Ready,
)
private val TerminalDashboardOperationStates = setOf(
    PendingBackendState.OutcomeUnknown,
    PendingBackendState.Failed,
)

private enum class CreateRetryTarget(val webValue: String) {
    Persistence("persistence"),
    Generation("generation"),
    Finalization("finalization"),
}

private data class CreateEffectFailure(
    val target: CreateRetryTarget,
    val message: String,
)

/**
 * UI-neutral owner of the canonical WebView product snapshot.
 *
 * The gateway deliberately keeps account/session identity outside this class so none of it can be
 * projected into the JavaScript boundary by accident. React receives only the DTOs below.
 */
internal class ProductionWebAppRuntime(
    private val gateway: WebAppDataGateway,
    private val appVersion: String,
    private val webBundleVersion: String,
    private val reducedMotion: () -> Boolean = { false },
    private val notificationPermission: () -> String = { "unknown" },
    private val mediaProjection: (PetDashboardState) -> WebPetMediaSnapshot = {
        WebPetMediaSnapshot()
    },
    private val mediaRegistry: WebMediaReferenceRegistry,
    private val onPetTapFeedback: () -> Unit = {},
    private val onFeedFeedback: (audioIndex: Int) -> Unit = {},
    private val choosePetTapThanks: () -> String = { PetTapThanksReplies.random() },
    private val runtimeId: String = UUID.randomUUID().toString(),
    generationScope: CoroutineScope? = null,
    private val petIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val createForegroundHandled: () -> Boolean = { true },
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val foregroundRefreshDelayMillis: suspend (Long) -> Unit = { delay(it) },
    initialNotificationDeepLink: NotificationDeepLinkExtras? = null,
) : WebAppRuntime, AutoCloseable {
    private val mutex = Mutex()
    private val generationSupervisor = SupervisorJob(generationScope?.coroutineContext?.get(Job))
    private val asyncScope = CoroutineScope(
        (generationScope?.coroutineContext ?: Dispatchers.IO).minusKey(Job) + generationSupervisor,
    )
    private val runtimeEvents = MutableSharedFlow<WebAppRuntimeEvent>(extraBufferCapacity = 16)
    private val thankedPetIds = mutableSetOf<String>()
    private var runtimeState: RuntimeState = RuntimeState.Uninitialized
    private var revisionSequence = 0L
    private var activeGeneration: GenerationIdentity? = null
    private var activeChatExecution: ChatExecutionIdentity? = null
    private var activeChatJob: Job? = null
    private var chatExecutionSequence = 0L
    private var chatRetryBlockedRequestKey: String? = null
    private val preparedChatReservations = mutableMapOf<String, WebChatReservationResult.Pending>()
    @Volatile
    private var activeAmbientExecution: AmbientExecutionIdentity? = null
    @Volatile
    private var ambientJob: Job? = null
    private val ambientPersistenceJobs = mutableSetOf<Job>()
    private var ambientActivationSequence = 0L
    private var attemptedAmbientActivationSequence = Long.MIN_VALUE
    private var ambientObservation: AmbientObservation? = null
    private val notificationDeepLinkLock = Any()
    private var notificationDeepLinkSequence = if (initialNotificationDeepLink == null) 0L else 1L
    private var pendingNotificationDeepLink = initialNotificationDeepLink?.let {
        PendingNotificationDeepLink(notificationDeepLinkSequence, it)
    }
    private var activeOutfitExecution: DashboardOperationIdentity? = null
    private var activeOutfitJob: Job? = null
    private var activeTravelExecution: DashboardOperationIdentity? = null
    private var activeTravelJob: Job? = null
    private var dashboardRecoveryJob: Job? = null
    private var dueStoryRefreshJob: Job? = null
    private var eventStoryRuntime: DurableEventStoryRuntime? = null
    private val activeStoryExecutions = mutableMapOf<String, StoryExecutionIdentity>()
    private val activeStoryJobs = mutableMapOf<String, Job>()
    private var storyExecutionSequence = 0L
    private val attemptedDueStoryRefreshPetIds = mutableSetOf<String>()
    @Volatile
    private var foregroundRefreshJob: Job? = null
    @Volatile
    private var foreground = false
    private val attemptedOutfitDispatchKeys = mutableSetOf<String>()
    private val attemptedTravelDispatchKeys = mutableSetOf<String>()
    private val commandReceipts = object : LinkedHashMap<String, CommandReceipt>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CommandReceipt>?): Boolean =
            size > 256
    }
    @Volatile
    private var closed = false
    @Volatile
    private var safeArea = WebSafeAreaSnapshot()

    init {
        mediaRegistry.setPublicationListener(::onMediaPublication)
    }

    override val events: Flow<WebAppRuntimeEvent> = runtimeEvents.asSharedFlow()

    override suspend fun snapshot(): WebAppSnapshot = mutex.withLock {
        ensureOpen()
        if (runtimeState.shouldRetryBootstrap()) {
            runtimeState = loadRuntimeState()
            revisionSequence += 1
        }
        ensureCreateGenerationScheduledLocked()
        ensureChatScheduledLocked()
        ensureDashboardOperationsScheduledLocked()
        ensureDueStoryRefreshScheduledLocked()
        ensureForegroundRefreshScheduledLocked()
        ensureStoryExecutionsScheduledLocked()
        snapshotFor(runtimeState)
    }

    override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot = mutex.withLock {
        ensureOpen()
        if (runtimeState.shouldRetryBootstrap()) {
            runtimeState = loadRuntimeState()
            revisionSequence += 1
            ensureCreateGenerationScheduledLocked()
            ensureChatScheduledLocked()
            ensureDashboardOperationsScheduledLocked()
        }
        if (!CanonicalCommandRequestKey.matches(command.requestKey)) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val fingerprint = command.fingerprint()
        commandReceipts[command.requestKey]?.let { receipt ->
            if (receipt.fingerprint != fingerprint) {
                throw WebAppRuntimeException("INVALID_PAYLOAD")
            }
            return@withLock snapshotFor(runtimeState, receipt.petTapFeedback)
        }
        val before = snapshotFor(runtimeState)
        if (command.expectedSnapshotRevision != before.revision) {
            throw WebAppRuntimeException(
                bridgeCode = "STATE_CONFLICT",
                retryable = true,
                snapshot = before,
            )
        }
        val petTapFeedback = when (command.type) {
            "PET_TAP" -> applyPetTap(command)
            "DASHBOARD_OPEN_MODE" -> {
                applyDashboardOpenMode(command)
                null
            }
            "DASHBOARD_CLOSE_MODE" -> {
                applyDashboardCloseMode(command)
                null
            }
            "DASHBOARD_UPDATE_DRAFT" -> {
                applyDashboardUpdateDraft(command)
                null
            }
            "CHAT_SEND" -> {
                applyChatSend(command)
                null
            }
            "CHAT_RETRY" -> {
                applyChatRetry(command)
                null
            }
            "FEED_CONSUME" -> {
                applyFeedConsume(command)
                null
            }
            "OUTFIT_SUBMIT" -> {
                applyOutfitSubmit(command)
                null
            }
            "OUTFIT_RETRY" -> {
                applyOutfitRetry(command)
                null
            }
            "TRAVEL_SUBMIT" -> {
                applyTravelSubmit(command)
                null
            }
            "TRAVEL_RETRY" -> {
                applyTravelRetry(command)
                null
            }
            "REPLY_ADVANCE" -> {
                applyReplyAdvance(command)
                null
            }
            "REPLY_COMPLETE" -> {
                applyReplyComplete(command)
                null
            }
            "CHAT_REPLY_PRESENTED" -> {
                applyChatReplyPresented(command)
                null
            }
            "CREATE_ANSWER" -> {
                applyCreateAnswer(command)
                null
            }
            "CREATE_RETRY" -> {
                applyCreateRetry(command)
                null
            }
            "CREATE_FINISH" -> {
                applyCreateFinish(command)
                null
            }
            "CREATE_BACKGROUND_COMPLETE" -> {
                applyCreateBackgroundComplete(command)
                null
            }
            "NAVIGATE" -> {
                applyNavigate(command)
                null
            }
            "STORY_OPEN" -> {
                applyStoryOpen(command)
                null
            }
            "STORY_CHOOSE" -> {
                applyStoryChoose(command)
                null
            }
            "STORY_RETRY" -> {
                applyStoryRetry(command)
                null
            }
            "STORY_FINISH" -> {
                applyStoryFinish(command)
                null
            }
            "EVENTS_MARK_VIEWED" -> {
                applyEventsMarkViewed(command)
                null
            }
            "BACK" -> {
                applyBack(command)
                null
            }
            else -> throw WebAppRuntimeException("UNSUPPORTED_METHOD")
        }
        revisionSequence += 1
        val after = snapshotFor(runtimeState, petTapFeedback)
        commandReceipts[command.requestKey] = CommandReceipt(fingerprint, petTapFeedback)
        ensureChatScheduledLocked()
        ensureDashboardOperationsScheduledLocked()
        ensureDueStoryRefreshScheduledLocked()
        ensureForegroundRefreshScheduledLocked()
        ensureStoryExecutionsScheduledLocked()
        after
    }

    override fun updateSafeArea(safeArea: WebSafeAreaSnapshot) {
        this.safeArea = safeArea
    }

    override fun onForeground() {
        if (closed) return
        mediaRegistry.retryFailedOnForeground()
        val becameForeground = !foreground
        foreground = true
        asyncScope.launch {
            mutex.withLock {
                if (becameForeground && foreground) {
                    ambientActivationSequence += 1L
                }
                val dashboard = runtimeState as? RuntimeState.Dashboard
                val blockedRequestKey = chatRetryBlockedRequestKey
                dashboard?.let { current ->
                    if (
                        current.uiState.activeChat?.requestKey == blockedRequestKey &&
                        current.uiState.chatError == ChatFailureMessage
                    ) {
                        runtimeState = current.withUiState(
                            current.uiState.copy(
                                chatError = null,
                            ),
                        )
                    }
                }
                chatRetryBlockedRequestKey = null
                (runtimeState as? RuntimeState.Dashboard)?.uiState?.pet?.petId?.let {
                    attemptedDueStoryRefreshPetIds.remove(it)
                }
                ensureChatScheduledLocked()
                ensureAmbientScheduledLocked()
                ensureDueStoryRefreshScheduledLocked()
                ensureForegroundRefreshScheduledLocked()
            }
        }
    }

    override fun onBackground() {
        foreground = false
        foregroundRefreshJob?.cancel()
        activeAmbientExecution = null
        ambientJob?.cancel()
        ambientJob = null
    }

    fun offerNotificationDeepLink(extras: NotificationDeepLinkExtras) {
        val pending = synchronized(notificationDeepLinkLock) {
            if (closed) return
            notificationDeepLinkSequence += 1L
            PendingNotificationDeepLink(notificationDeepLinkSequence, extras).also {
                pendingNotificationDeepLink = it
            }
        }
        asyncScope.launch {
            applyWarmNotificationDeepLink(pending)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        foreground = false
        foregroundRefreshJob?.cancel()
        dueStoryRefreshJob?.cancel()
        activeAmbientExecution = null
        ambientJob?.cancel()
        ambientJob = null
        synchronized(ambientPersistenceJobs) {
            ambientPersistenceJobs.forEach { it.cancel() }
            ambientPersistenceJobs.clear()
        }
        synchronized(notificationDeepLinkLock) {
            notificationDeepLinkSequence += 1L
            pendingNotificationDeepLink = null
        }
        mediaRegistry.setPublicationListener(WebMediaPublicationListener { })
        generationSupervisor.cancel()
        gateway.close()
    }

    private suspend fun loadRuntimeState(): RuntimeState = when (val result = gateway.bootstrap()) {
        WebRuntimeBootstrapResult.ConnectionError -> {
            eventStoryRuntime = null
            RuntimeState.ConnectionError
        }
        WebRuntimeBootstrapResult.LocalDataError -> {
            eventStoryRuntime = null
            RuntimeState.LocalDataError
        }
        is WebRuntimeBootstrapResult.Ready -> when (val destination = result.destination) {
            is WebRuntimeDestination.Create -> {
                eventStoryRuntime = null
                RuntimeState.Create(destination.state)
            }
            is WebRuntimeDestination.Dashboard -> applyPendingNotificationDeepLinkCold(
                dashboardRuntimeState(
                    destination = destination.destination,
                    operations = destination.operations,
                ),
            )
        }
    }

    private suspend fun dashboardRuntimeState(
        destination: AccountStartupDestination.Dashboard,
        operations: WebDashboardOperationState,
    ): RuntimeState.Dashboard {
        val durableRuntime = gateway.authenticatedEventStoryGateway()?.let { eventGateway ->
            DurableEventStoryRuntime(destination.pet.petId, eventGateway)
        }
        eventStoryRuntime = durableRuntime
        attemptedDueStoryRefreshPetIds.remove(destination.pet.petId)
        val eventSnapshot = when (val loaded = durableRuntime?.snapshot()) {
            is EventStoryResult.Success -> loaded.value
            is EventStoryResult.Failure,
            null,
            -> emptyEventStorySnapshot(destination)
        }
        return RuntimeState.Dashboard(
            destination = destination,
            uiState = recoveredDashboardState(destination),
            operations = operations,
            eventSnapshot = eventSnapshot,
        ).withEventSnapshot(eventSnapshot)
    }

    private suspend fun applyPendingNotificationDeepLinkCold(
        initial: RuntimeState.Dashboard,
    ): RuntimeState.Dashboard {
        var state = initial
        while (true) {
            val pending = currentPendingNotificationDeepLink() ?: return state
            val resolution = resolveNotificationDeepLink(
                extras = pending.extras,
                expectedPetId = state.uiState.pet.petId,
                durableRuntime = eventStoryRuntime,
            )
            if (!consumePendingNotificationDeepLink(pending.token)) continue
            state = state.applyNotificationDeepLinkResolution(resolution)
            return state
        }
    }

    private suspend fun applyWarmNotificationDeepLink(
        pending: PendingNotificationDeepLink,
    ) {
        val context = mutex.withLock {
            if (closed || !isPendingNotificationDeepLink(pending.token)) return
            val dashboard = runtimeState as? RuntimeState.Dashboard ?: return
            NotificationDeepLinkContext(
                pet = dashboard.uiState.pet,
                firstSession = dashboard.uiState.firstSession,
                durableRuntime = eventStoryRuntime,
            )
        }
        val resolution = resolveNotificationDeepLink(
            extras = pending.extras,
            expectedPetId = context.pet.petId,
            durableRuntime = context.durableRuntime,
        )
        val changedSnapshot = mutex.withLock {
            if (closed || !isPendingNotificationDeepLink(pending.token)) {
                return@withLock null
            }
            if (eventStoryRuntime !== context.durableRuntime) {
                consumePendingNotificationDeepLink(pending.token)
                return@withLock null
            }
            val latest = runtimeState as? RuntimeState.Dashboard ?: run {
                consumePendingNotificationDeepLink(pending.token)
                return@withLock null
            }
            if (latest.uiState.pet.petId != context.pet.petId) {
                consumePendingNotificationDeepLink(pending.token)
                return@withLock null
            }
            if (!consumePendingNotificationDeepLink(pending.token)) return@withLock null
            val authoritativeResolution = if (
                latest.uiState.pet == context.pet &&
                latest.uiState.firstSession == context.firstSession
            ) {
                resolution
            } else {
                resolution.rebasedOn(latest.uiState)
            }
            runtimeState = latest.applyNotificationDeepLinkResolution(
                authoritativeResolution,
            )
            revisionSequence += 1L
            snapshotFor(runtimeState)
        }
        changedSnapshot?.let { emitStateChanged(it) }
    }

    private suspend fun resolveNotificationDeepLink(
        extras: NotificationDeepLinkExtras,
        expectedPetId: String,
        durableRuntime: DurableEventStoryRuntime?,
    ): NotificationDeepLinkResolution {
        val parsed = parseNotificationDeepLink(extras)
        val destination = try {
            gateway.resolveNotificationDeepLink(extras)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NotificationDeepLinkDestination.Dashboard
        }
        return when (destination) {
            NotificationDeepLinkDestination.Dashboard ->
                dashboardDeepLinkResolution(expectedPetId, durableRuntime)
            is NotificationDeepLinkDestination.Story -> {
                val storyId = destination.item.story.storyId
                if (
                    parsed !is ParsedNotificationDeepLink.Story ||
                    parsed.storyId != storyId ||
                    destination.item.story.petId != expectedPetId
                ) {
                    return dashboardDeepLinkResolution(expectedPetId, durableRuntime)
                }
                val opened = durableRuntime?.openStory(storyId)
                val canonical = (opened as? EventStoryResult.Success)?.value
                if (
                    canonical == null ||
                    canonical.app.pet.petId != expectedPetId ||
                    canonical.story.kind != DurableStoryKind.Scheduled ||
                    canonical.story.story.travelId != storyId ||
                    canonical.app.history.items.none { item ->
                        item is EventHistoryItem.ScheduledStory &&
                            item.item.story.petId == expectedPetId &&
                            item.item.story.storyId == storyId
                    }
                ) {
                    dashboardDeepLinkResolution(expectedPetId, durableRuntime)
                } else {
                    NotificationDeepLinkResolution.Story(canonical)
                }
            }
            is NotificationDeepLinkDestination.Events -> {
                val requestKey = destination.item.requestKey
                if (
                    parsed !is ParsedNotificationDeepLink.Travel ||
                    parsed.requestKey != requestKey ||
                    destination.item.petId != expectedPetId ||
                    destination.item.consumedAtEpochMillis == null ||
                    destination.item.videoUrl.isBlank()
                ) {
                    return dashboardDeepLinkResolution(expectedPetId, durableRuntime)
                }
                val refreshed = durableRuntime?.snapshot()
                val canonical = (refreshed as? EventStoryResult.Success)?.value
                val containsTravel = canonical?.history?.items?.any { item ->
                    item is EventHistoryItem.TravelVideo &&
                        item.asset.petId == expectedPetId &&
                        item.asset.requestKey == requestKey &&
                        item.asset.consumedAtEpochMillis != null &&
                        item.asset.videoUrl.isNotBlank()
                } == true
                if (canonical?.pet?.petId != expectedPetId || !containsTravel) {
                    dashboardDeepLinkResolution(expectedPetId, durableRuntime)
                } else {
                    NotificationDeepLinkResolution.Events(canonical, requestKey)
                }
            }
        }
    }

    private suspend fun dashboardDeepLinkResolution(
        expectedPetId: String,
        durableRuntime: DurableEventStoryRuntime?,
    ): NotificationDeepLinkResolution.Dashboard {
        val refreshed = durableRuntime?.snapshot()
        val snapshot = (refreshed as? EventStoryResult.Success)?.value
            ?.takeIf { it.pet.petId == expectedPetId }
        return NotificationDeepLinkResolution.Dashboard(snapshot)
    }

    private fun RuntimeState.Dashboard.applyNotificationDeepLinkResolution(
        resolution: NotificationDeepLinkResolution,
    ): RuntimeState.Dashboard {
        var root = copy(
            route = DashboardRoute.Dashboard,
            openedStory = null,
            storyOrigin = null,
            initialFocusTravelRequestKey = null,
        )
        root = when (resolution) {
            is NotificationDeepLinkResolution.Dashboard ->
                resolution.snapshot?.let { snapshot -> root.withEventSnapshot(snapshot) } ?: root
            is NotificationDeepLinkResolution.Story ->
                root.withEventSnapshot(resolution.opened.app).copy(
                    route = DashboardRoute.Story,
                    openedStory = resolution.opened.story,
                    storyOrigin = WebDurableStoryOrigin.Dashboard,
                )
            is NotificationDeepLinkResolution.Events ->
                root.withEventSnapshot(resolution.snapshot).copy(
                    route = DashboardRoute.Events,
                    initialFocusTravelRequestKey = resolution.requestKey,
                )
        }
        return root
    }

    private fun NotificationDeepLinkResolution.rebasedOn(
        uiState: DashboardUiState,
    ): NotificationDeepLinkResolution {
        fun EventStorySnapshot.rebased(): EventStorySnapshot = copy(
            pet = uiState.pet,
            firstSession = uiState.firstSession,
        )
        return when (this) {
            is NotificationDeepLinkResolution.Dashboard -> copy(snapshot = snapshot?.rebased())
            is NotificationDeepLinkResolution.Story -> copy(
                opened = opened.copy(app = opened.app.rebased()),
            )
            is NotificationDeepLinkResolution.Events -> copy(snapshot = snapshot.rebased())
        }
    }

    private fun currentPendingNotificationDeepLink(): PendingNotificationDeepLink? =
        synchronized(notificationDeepLinkLock) { pendingNotificationDeepLink }

    private fun currentPendingDeepLinkTarget(): String? =
        currentPendingNotificationDeepLink()?.extras?.let { extras ->
            when (parseNotificationDeepLink(extras)) {
                is ParsedNotificationDeepLink.Story -> "story"
                is ParsedNotificationDeepLink.Travel -> "events"
                ParsedNotificationDeepLink.Invalid,
                ParsedNotificationDeepLink.None,
                -> "dashboard"
            }
        }

    private fun isPendingNotificationDeepLink(token: Long): Boolean =
        synchronized(notificationDeepLinkLock) {
            pendingNotificationDeepLink?.token == token
        }

    private fun consumePendingNotificationDeepLink(token: Long): Boolean =
        synchronized(notificationDeepLinkLock) {
            if (pendingNotificationDeepLink?.token != token) return@synchronized false
            pendingNotificationDeepLink = null
            true
        }

    private suspend fun applyCreateAnswer(command: BridgeProductCommand) {
        val current = runtimeState as? RuntimeState.Create
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (current.state.isFinal) throw WebAppRuntimeException("WRONG_STAGE")
        val answer = command.requiredCreateAnswer()
        if (answer.step != current.state.step) throw WebAppRuntimeException("WRONG_STAGE")
        val nextState = current.state.answer(
            rawAnswer = answer.value,
            reducedMotion = reducedMotion(),
            requestKeyFactory = { command.requestKey },
            petIdFactory = petIdFactory,
        )
        if (nextState == current.state) throw WebAppRuntimeException("INVALID_PAYLOAD")
        val persisted = gateway.persistCreate(nextState)
        runtimeState = RuntimeState.Create(
            state = nextState,
            effectFailure = if (persisted == WebCreatePersistenceResult.Persisted) {
                current.effectFailure?.takeUnless { it.target == CreateRetryTarget.Persistence }
            } else {
                CreateEffectFailure(
                    CreateRetryTarget.Persistence,
                    CreatePersistenceErrorMessage,
                )
            },
        )
        if (persisted == WebCreatePersistenceResult.Persisted) {
            ensureCreateGenerationScheduledLocked()
        }
    }

    private suspend fun applyCreateRetry(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Create
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        when (current.retryTarget()) {
            CreateRetryTarget.Persistence -> {
                val persisted = gateway.persistCreate(current.state)
                runtimeState = if (persisted == WebCreatePersistenceResult.Persisted) {
                    current.copy(effectFailure = null)
                } else {
                    current.copy(
                        effectFailure = CreateEffectFailure(
                            CreateRetryTarget.Persistence,
                            CreatePersistenceErrorMessage,
                        ),
                    )
                }
                if (persisted == WebCreatePersistenceResult.Persisted) {
                    ensureCreateGenerationScheduledLocked()
                }
            }
            CreateRetryTarget.Generation -> {
                if (current.state.generation !is GenerationStatus.Error) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                val retried = current.state.retryGeneration { command.requestKey }
                if (retried === current.state) throw WebAppRuntimeException("WRONG_STAGE")
                val persisted = gateway.persistCreate(retried)
                runtimeState = RuntimeState.Create(
                    state = retried,
                    effectFailure = if (persisted == WebCreatePersistenceResult.Persisted) {
                        null
                    } else {
                        CreateEffectFailure(
                            CreateRetryTarget.Persistence,
                            CreatePersistenceErrorMessage,
                        )
                    },
                )
                if (persisted == WebCreatePersistenceResult.Persisted) {
                    ensureCreateGenerationScheduledLocked()
                }
            }
            CreateRetryTarget.Finalization -> finalizeCreateLocked(current)
            null -> throw WebAppRuntimeException("WRONG_STAGE")
        }
    }

    private suspend fun applyCreateFinish(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Create
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (!current.state.canNavigate || current.effectFailure != null) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        finalizeCreateLocked(current)
    }

    private fun applyCreateBackgroundComplete(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Create
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (current.state.backgroundPhase != CreationBackgroundPhase.Transition) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        runtimeState = current.copy(state = current.state.markTransitionComplete())
    }

    private suspend fun finalizeCreateLocked(current: RuntimeState.Create) {
        when (
            val finalized = gateway.finalizeCreate(
                state = current.state,
                foregroundHandled = createForegroundHandled(),
            )
        ) {
            WebCreateFinalizationResult.Failure -> runtimeState = current.copy(
                effectFailure = CreateEffectFailure(
                    CreateRetryTarget.Finalization,
                    CreateFinalizationErrorMessage,
                ),
            )
            is WebCreateFinalizationResult.Success -> runtimeState =
                applyPendingNotificationDeepLinkCold(
                    dashboardRuntimeState(
                        destination = finalized.destination,
                        operations = WebDashboardOperationState(),
                    ),
                )
        }
    }

    private suspend fun applyNavigate(command: BridgeProductCommand) {
        val requestedRoute = command.requiredStringPayload("route", maxLength = 16)
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        if (
            current.uiState.mode != DashboardMode.Idle ||
            isFirstSessionReplyPending(current.uiState)
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        when (requestedRoute) {
            DashboardRoute.Events.webValue -> {
                val refreshed = refreshEventSnapshot(current)
                runtimeState = refreshed.copy(
                    route = DashboardRoute.Events,
                    openedStory = null,
                    storyOrigin = null,
                    initialFocusTravelRequestKey = null,
                )
            }
            "travel" -> {
                if (current.uiState.firstSession?.stage !in OnboardingBatEntryStages) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                val storyId = onboardingBatStory(current.uiState.pet.petId).travelId
                val opened = eventRuntimeOrThrow().openStory(storyId).valueOrThrow()
                if (
                    opened.story.kind != DurableStoryKind.OnboardingBat ||
                    opened.story.story.travelId != storyId
                ) {
                    throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
                }
                runtimeState = current.withEventSnapshot(opened.app).copy(
                    route = DashboardRoute.Story,
                    openedStory = opened.story,
                    storyOrigin = WebDurableStoryOrigin.Dashboard,
                    initialFocusTravelRequestKey = null,
                )
            }
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private suspend fun applyStoryOpen(command: BridgeProductCommand) {
        val storyId = command.requiredIdentifierPayload("storyId")
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            current.route != DashboardRoute.Events ||
            current.openedStory != null ||
            current.eventSnapshot.history.items.none { item ->
                item is EventHistoryItem.ScheduledStory && item.item.story.storyId == storyId
            }
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val opened = eventRuntimeOrThrow().openStory(storyId).valueOrThrow()
        if (
            opened.story.kind != DurableStoryKind.Scheduled ||
            opened.story.story.travelId != storyId
        ) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        runtimeState = current.withEventSnapshot(opened.app).copy(
            route = DashboardRoute.Story,
            openedStory = opened.story,
            storyOrigin = WebDurableStoryOrigin.Events,
        )
    }

    private suspend fun applyStoryChoose(command: BridgeProductCommand) {
        val requested = command.requiredStoryChoicePayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        val target = current.requireOpenedStory(requested.storyId)
        val opened = target.snapshot
        if (opened.phase != DurableStoryPhase.Question) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val enabledChoice = opened.story.enabledChoice.trim().takeIf(String::isNotEmpty)
        if (
            requested.choice !in opened.story.choices ||
            (enabledChoice != null && requested.choice != enabledChoice)
        ) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val durableRuntime = eventRuntimeOrThrow()
        val prepared = durableRuntime.prepareStoryChoice(
            storyId = target.internalStoryId,
            choice = requested.choice,
            proposedRequestKey = command.requestKey,
        ).valueOrThrow()
        val chosen = prepared.opened
        if (
            chosen.story.kind != opened.kind ||
            chosen.story.story.travelId != target.internalStoryId
        ) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        runtimeState = current.withEventSnapshot(chosen.app).copy(openedStory = chosen.story)
        prepared.execution?.let { execution ->
            registerStoryExecutionLocked(
                petId = current.uiState.pet.petId,
                visibleStoryId = requested.storyId,
                durableRuntime = durableRuntime,
                execution = execution,
            )
        }
    }

    private suspend fun applyStoryRetry(command: BridgeProductCommand) {
        val storyId = command.requiredIdentifierPayload("storyId")
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        val target = current.requireOpenedStory(storyId)
        val opened = target.snapshot
        if (opened.phase != DurableStoryPhase.Retryable) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val durableRuntime = eventRuntimeOrThrow()
        val prepared = durableRuntime.prepareStoryRetry(target.internalStoryId).valueOrThrow()
        val retried = prepared.opened
        if (
            retried.story.kind != opened.kind ||
            retried.story.story.travelId != target.internalStoryId
        ) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        runtimeState = current.withEventSnapshot(retried.app).copy(openedStory = retried.story)
        prepared.execution?.let { execution ->
            registerStoryExecutionLocked(
                petId = current.uiState.pet.petId,
                visibleStoryId = storyId,
                durableRuntime = durableRuntime,
                execution = execution,
            )
        }
    }

    private suspend fun applyStoryFinish(command: BridgeProductCommand) {
        val storyId = command.requiredIdentifierPayload("storyId")
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        val target = current.requireOpenedStory(storyId)
        val opened = target.snapshot
        val origin = current.storyOrigin ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            opened.phase != DurableStoryPhase.Result ||
            (opened.kind == DurableStoryKind.OnboardingBat &&
                origin != WebDurableStoryOrigin.Dashboard)
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val finished = eventRuntimeOrThrow().finishStory(target.internalStoryId).valueOrThrow()
        runtimeState = current.withEventSnapshot(finished).copy(
            route = origin.toDashboardRoute(),
            openedStory = null,
            storyOrigin = null,
            initialFocusTravelRequestKey = null,
        )
    }

    private suspend fun applyEventsMarkViewed(command: BridgeProductCommand) {
        val requestedViewedAt = command.requiredLongPayload("viewedAt")
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (current.route != DashboardRoute.Events) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val renderedLatest = current.eventSnapshot.latestEventAtEpochMillis
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (requestedViewedAt != renderedLatest) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        val marked = eventRuntimeOrThrow().markViewed(requestedViewedAt).valueOrThrow()
        runtimeState = current.withEventSnapshot(marked)
    }

    private suspend fun applyBack(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        val refreshed = refreshEventSnapshotBestEffort(current)
        runtimeState = when (current.route) {
            DashboardRoute.Dashboard -> throw WebAppRuntimeException("WRONG_STAGE")
            DashboardRoute.Events -> refreshed.copy(
                route = DashboardRoute.Dashboard,
                openedStory = null,
                storyOrigin = null,
                initialFocusTravelRequestKey = null,
            )
            DashboardRoute.Story -> {
                val origin = current.storyOrigin ?: throw WebAppRuntimeException("WRONG_STAGE")
                current.requireOpenedStory()
                refreshed.copy(
                    route = origin.toDashboardRoute(),
                    openedStory = null,
                    storyOrigin = null,
                    initialFocusTravelRequestKey = null,
                )
            }
        }
    }

    private suspend fun refreshEventSnapshot(
        current: RuntimeState.Dashboard,
    ): RuntimeState.Dashboard = current.withEventSnapshot(
        eventRuntimeOrThrow().snapshot().valueOrThrow(),
    )

    private suspend fun refreshEventSnapshotBestEffort(
        current: RuntimeState.Dashboard,
    ): RuntimeState.Dashboard = when (val refreshed = eventStoryRuntime?.snapshot()) {
        is EventStoryResult.Success -> current.withEventSnapshot(refreshed.value)
        is EventStoryResult.Failure,
        null,
        -> current
    }

    private fun registerStoryExecutionLocked(
        petId: String,
        visibleStoryId: String,
        durableRuntime: DurableEventStoryRuntime,
        execution: ScheduledStoryExecution,
    ) {
        val existing = activeStoryExecutions[execution.requestKey]
        if (existing != null) {
            if (
                existing.petId != petId ||
                existing.internalStoryId != execution.storyId ||
                existing.visibleStoryId != visibleStoryId ||
                existing.choice != execution.choice ||
                existing.durableRuntime !== durableRuntime
            ) {
                throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
            }
            return
        }
        storyExecutionSequence += 1L
        activeStoryExecutions[execution.requestKey] = StoryExecutionIdentity(
            petId = petId,
            internalStoryId = execution.storyId,
            visibleStoryId = visibleStoryId,
            requestKey = execution.requestKey,
            choice = execution.choice,
            sequence = storyExecutionSequence,
            durableRuntime = durableRuntime,
        )
    }

    /** Starts only work whose Room claim has already been reflected in the returned snapshot. */
    private fun ensureStoryExecutionsScheduledLocked() {
        if (closed) return
        activeStoryExecutions.values.toList().forEach { identity ->
            if (activeStoryJobs[identity.requestKey] != null) return@forEach
            activeStoryJobs[identity.requestKey] = asyncScope.launch {
                // This barrier cannot pass until the dispatch/snapshot that published
                // ChoicePending has released the runtime mutex.
                val canStart = try {
                    mutex.withLock {
                        !closed && activeStoryExecutions[identity.requestKey] == identity
                    }
                } catch (cancelled: CancellationException) {
                    clearCancelledStoryExecution(identity)
                    throw cancelled
                }
                if (!canStart) return@launch
                val result = try {
                    identity.durableRuntime.executePreparedStoryChoice(
                        ScheduledStoryExecution(
                            storyId = identity.internalStoryId,
                            requestKey = identity.requestKey,
                            choice = identity.choice,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    clearCancelledStoryExecution(identity)
                    throw cancelled
                }
                val changedSnapshot = mutex.withLock {
                    if (
                        closed ||
                        activeStoryExecutions[identity.requestKey] != identity
                    ) {
                        return@withLock null
                    }
                    activeStoryExecutions.remove(identity.requestKey)
                    activeStoryJobs.remove(identity.requestKey)
                    val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                    if (
                        latest.uiState.pet.petId != identity.petId ||
                        eventStoryRuntime !== identity.durableRuntime
                    ) {
                        return@withLock null
                    }
                    val currentlyShowingSameExecution = latest.route == DashboardRoute.Story &&
                        latest.openedStory?.externalStoryId() == identity.visibleStoryId &&
                        latest.openedStory.durableRequestKey == identity.requestKey
                    val opened = (result as? EventStoryResult.Success)?.value
                    val validOpened = opened?.takeIf {
                        it.app.pet.petId == identity.petId &&
                            it.story.story.travelId == identity.internalStoryId &&
                            it.story.durableRequestKey == identity.requestKey &&
                            when (it.story.phase) {
                                DurableStoryPhase.Retryable ->
                                    it.story.pendingChoice == identity.choice
                                DurableStoryPhase.Result ->
                                    it.story.result?.answer == identity.choice
                                DurableStoryPhase.Question,
                                DurableStoryPhase.ChoicePending,
                                -> false
                            }
                    }
                    var next = if (validOpened != null) {
                        latest.withEventSnapshot(validOpened.app)
                    } else if (result is EventStoryResult.Failure) {
                        when (val refreshed = identity.durableRuntime.snapshot()) {
                            is EventStoryResult.Success -> if (
                                refreshed.value.pet.petId == identity.petId
                            ) {
                                latest.withEventSnapshot(refreshed.value)
                            } else {
                                latest
                            }
                            is EventStoryResult.Failure -> latest
                        }
                    } else {
                        latest
                    }
                    if (currentlyShowingSameExecution) {
                        next = next.copy(
                            openedStory = validOpened?.story ?: requireNotNull(latest.openedStory).copy(
                                phase = DurableStoryPhase.Retryable,
                                result = null,
                                error = TravelStoryChoiceFailureMessage,
                            ),
                        )
                    } else if (validOpened == null) {
                        return@withLock null
                    }
                    runtimeState = next
                    revisionSequence += 1L
                    snapshotFor(runtimeState)
                } ?: return@launch
                emitStateChanged(changedSnapshot)
            }
        }
    }

    private suspend fun clearCancelledStoryExecution(identity: StoryExecutionIdentity) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (activeStoryExecutions[identity.requestKey] == identity) {
                    activeStoryExecutions.remove(identity.requestKey)
                    activeStoryJobs.remove(identity.requestKey)
                }
            }
        }
    }

    private fun eventRuntimeOrThrow(): DurableEventStoryRuntime = eventStoryRuntime
        ?: throw WebAppRuntimeException("LOCAL_DATA_ERROR", retryable = true)

    private fun <T> EventStoryResult<T>.valueOrThrow(): T = when (this) {
        is EventStoryResult.Success -> value
        is EventStoryResult.Failure -> when (kind) {
            EventStoryFailureKind.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            EventStoryFailureKind.WrongStage -> throw WebAppRuntimeException("WRONG_STAGE")
            EventStoryFailureKind.Conflict -> throw WebAppRuntimeException(
                "STATE_CONFLICT",
                retryable = true,
            )
            EventStoryFailureKind.Retryable -> throw WebAppRuntimeException(
                "OFFLINE_NOT_QUEUED",
                retryable = true,
            )
            EventStoryFailureKind.LocalData -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
    }

    private fun ensureCreateGenerationScheduledLocked() {
        if (closed || activeGeneration != null) return
        val current = runtimeState as? RuntimeState.Create ?: return
        val request = current.state.pending ?: return
        if (
            current.state.generation !is GenerationStatus.Running ||
            current.state.generationAttempt == 0 ||
            current.effectFailure?.target == CreateRetryTarget.Persistence
        ) {
            return
        }
        val identity = GenerationIdentity(
            petId = request.petId,
            requestKey = request.requestKey,
            attempt = current.state.generationAttempt,
        )
        activeGeneration = identity
        asyncScope.launch {
            val result = try {
                gateway.generateCreate(request)
            } catch (cancelled: CancellationException) {
                clearCancelledGeneration(identity)
                throw cancelled
            } catch (_: Exception) {
                PetGenerationExecutionResult.Failure(newRequestRequired = false)
            }
            val changedSnapshot = mutex.withLock {
                if (activeGeneration == identity) activeGeneration = null
                if (closed) return@withLock null
                val latest = runtimeState as? RuntimeState.Create ?: return@withLock null
                if (!latest.state.matches(identity)) return@withLock null
                val nextState = when (result) {
                    is PetGenerationExecutionResult.Failure -> latest.state.markGenerationFailed(
                        newRequestRequired = result.newRequestRequired,
                    )
                    is PetGenerationExecutionResult.Success -> latest.state.markGenerationReady(result.pet)
                }
                runtimeState = latest.copy(state = nextState)
                revisionSequence += 1
                snapshotFor(runtimeState)
            } ?: return@launch
            runtimeEvents.emit(
                WebAppRuntimeEvent(
                    type = "stateChanged",
                    payload = BridgeCodec.json.encodeToJsonElement(changedSnapshot),
                ),
            )
        }
    }

    private suspend fun clearCancelledGeneration(identity: GenerationIdentity) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (activeGeneration == identity) activeGeneration = null
            }
        }
    }

    private fun ensureOpen() {
        if (closed) throw WebAppRuntimeException("LOCAL_DATA_ERROR", retryable = true)
    }

    private suspend fun applyPetTap(command: BridgeProductCommand): WebPetTapFeedbackSnapshot {
        if (command.payload.isNotEmpty()) throw WebAppRuntimeException("INVALID_PAYLOAD")
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (current.route != DashboardRoute.Dashboard) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (current.uiState.mode != DashboardMode.Idle) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val pet = current.uiState.pet
        val willReward = (pet.petTapProgress + 1) % 5 == 0
        val appliedPet = when (val result = gateway.applyPetTap(pet)) {
            is WebPetTapMutationResult.Applied -> result.pet.also {
                runCatching(onPetTapFeedback)
            }
            WebPetTapMutationResult.Conflict -> throw WebAppRuntimeException(
                "STATE_CONFLICT",
                retryable = true,
            )
            WebPetTapMutationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            WebPetTapMutationResult.LocalDataError -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
        val thanks = if (willReward && thankedPetIds.add(pet.petId)) choosePetTapThanks() else null
        val nextUi = current.uiState.copy(pet = appliedPet)
        runtimeState = current.copy(
            destination = current.destination.copy(pet = nextUi.pet),
            uiState = nextUi,
        )
        return WebPetTapFeedbackSnapshot(
            eventId = command.requestKey,
            rewarded = willReward,
            thanks = thanks,
            visibleMillis = PetTapThanksVisibleMillis,
        )
    }

    private fun applyDashboardOpenMode(command: BridgeProductCommand) {
        val mode = command.requiredStringPayload("mode", maxLength = 16)
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            current.route != DashboardRoute.Dashboard ||
            (mode == "travel" &&
                current.uiState.firstSession?.stage in OnboardingBatEntryStages)
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (current.uiState.mode != DashboardMode.Idle) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val event = when (mode) {
            "chat" -> DashboardEvent.OpenChat
            "feed" -> DashboardEvent.OpenFeed
            "outfit" -> DashboardEvent.OpenOutfit
            "travel" -> DashboardEvent.OpenTravel
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val recoveredUi = current.uiState.projectDurableChat(
            current.destination.pendingChat,
            current.destination.queuedChat,
        )
        val reduced = reduceDashboard(recoveredUi, event)
        val opened = if (mode == "chat") {
            reduced.copy(
                chatReply = recoveredUi.chatReply,
                activeChat = recoveredUi.activeChat,
                queuedChat = recoveredUi.queuedChat,
            )
        } else {
            reduced
        }
        val next = when (mode) {
            "outfit" -> current.operations.outfit?.takeIf { durable ->
                durable.petId == current.uiState.pet.petId &&
                    durable.backendJobId == null &&
                    durable.backendState in DispatchableDashboardOperationStates &&
                    durable.requestKey in attemptedOutfitDispatchKeys &&
                    activeOutfitExecution?.requestKey != durable.requestKey
            }?.let { durable ->
                opened.copy(
                    outfitError = OutfitFailureMessage,
                    activeOutfit = null,
                    pendingOutfit = durable.toRuntimeUi(),
                )
            } ?: opened
            "travel" -> current.operations.travel?.takeIf { durable ->
                durable.petId == current.uiState.pet.petId &&
                    durable.backendJobId == null &&
                    durable.backendState in DispatchableDashboardOperationStates &&
                    durable.requestKey in attemptedTravelDispatchKeys &&
                    activeTravelExecution?.requestKey != durable.requestKey
            }?.let { durable ->
                opened.copy(
                    travelError = TravelFailureMessage,
                    activeTravel = null,
                    pendingTravel = durable.toUi(),
                )
            } ?: opened
            else -> opened
        }
        if (next.mode == DashboardMode.Idle) throw WebAppRuntimeException("WRONG_STAGE")
        if (mode == "chat") chatRetryBlockedRequestKey = null
        runtimeState = current.withUiState(next)
    }

    private fun applyDashboardCloseMode(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val closed = reduceDashboard(current.uiState, DashboardEvent.CloseMode).copy(
            chatReply = current.uiState.chatReply,
            activeChat = current.uiState.activeChat,
            queuedChat = current.uiState.queuedChat,
        ).projectDurableChat(
            current.destination.pendingChat,
            current.destination.queuedChat,
        )
        runtimeState = current.withUiState(
            closed,
        )
    }

    private fun applyDashboardUpdateDraft(command: BridgeProductCommand) {
        val requested = command.requiredDashboardDraftPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val event = when (requested.mode) {
            "chat" -> {
                if (current.uiState.mode != DashboardMode.Chat) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                if (
                    chatRetryBlockedRequestKey != null &&
                    current.uiState.activeChat?.requestKey == chatRetryBlockedRequestKey
                ) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                DashboardEvent.UpdateChatDraft(requested.value)
            }
            "outfit" -> {
                if (current.uiState.mode != DashboardMode.Outfit) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                DashboardEvent.UpdateOutfitDraft(requested.value)
            }
            "travel" -> {
                if (current.uiState.mode != DashboardMode.Travel) {
                    throw WebAppRuntimeException("WRONG_STAGE")
                }
                DashboardEvent.UpdateTravelDraft(requested.value)
            }
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        runtimeState = current.withUiState(reduceDashboard(current.uiState, event))
    }

    private suspend fun applyChatSend(command: BridgeProductCommand) {
        val message = command.requiredStringPayload(
            key = "message",
            maxLength = DashboardPromptMaxLength,
            trim = true,
        )
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (current.route != DashboardRoute.Dashboard) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        var ui = current.uiState.projectDurableChat(
            current.destination.pendingChat,
            current.destination.queuedChat,
        )
        if (ui.mode == DashboardMode.Idle) {
            val recoveredUi = ui
            val opened = reduceDashboard(ui, DashboardEvent.OpenChat).copy(
                chatReply = recoveredUi.chatReply,
                activeChat = recoveredUi.activeChat,
                queuedChat = recoveredUi.queuedChat,
            )
            if (opened.mode != DashboardMode.Chat) throw WebAppRuntimeException("WRONG_STAGE")
            ui = opened
            chatRetryBlockedRequestKey = null
        } else if (ui.mode != DashboardMode.Chat) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (
            chatRetryBlockedRequestKey != null &&
            ui.activeChat?.requestKey == chatRetryBlockedRequestKey
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        ui = reduceDashboard(ui, DashboardEvent.UpdateChatDraft(message))
        if (ui.activeChat != null) {
            val request = PendingChatRequest(command.requestKey, message)
            val reservation = gateway.reserveChat(
                request = request,
                expectedPet = ui.pet,
                originFirstSessionStage = queuedChatOriginStage(ui.firstSession?.stage),
                queueAnchorRequestKey = ui.activeChat.requestKey,
                replacingQueuedRequestKey = ui.queuedChat?.requestKey,
            )
            val pending = when (reservation) {
                is WebChatReservationResult.Pending -> reservation.pendingChat
                is WebChatReservationResult.Finished -> {
                    runtimeState = current.withUiState(
                        current.uiState.copy(pet = reservation.pet),
                    )
                    return
                }
                WebChatReservationResult.Conflict -> throw WebAppRuntimeException(
                    "INVALID_PAYLOAD",
                )
                WebChatReservationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
                WebChatReservationResult.LocalDataError -> throw WebAppRuntimeException(
                    "LOCAL_DATA_ERROR",
                    retryable = true,
                )
            }
            if (
                pending.petId != ui.pet.petId ||
                pending.requestKey != request.requestKey ||
                pending.message != request.message
            ) {
                throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
            }
            ui.queuedChat?.requestKey?.let(preparedChatReservations::remove)
            preparedChatReservations[request.requestKey] = reservation
            val queued = reduceDashboard(
                ui.copy(pet = (reservation as WebChatReservationResult.Pending).pet),
                DashboardEvent.SubmitChat(command.requestKey),
            )
            if (queued.queuedChat?.requestKey != command.requestKey) {
                throw WebAppRuntimeException("WRONG_STAGE")
            }
            runtimeState = current.withUiState(
                queued,
                queuedChat = pending,
            )
            return
        }

        val request = PendingChatRequest(command.requestKey, message)
        val reservation = gateway.reserveChat(
            request,
            ui.pet,
            originFirstSessionStage = chatOriginStage(ui.firstSession?.stage),
        )
        val reservedPet = when (reservation) {
            is WebChatReservationResult.Pending -> reservation.pet
            is WebChatReservationResult.Finished -> {
                runtimeState = current.withUiState(
                    current.uiState.copy(pet = reservation.pet),
                )
                return
            }
            WebChatReservationResult.Conflict -> throw WebAppRuntimeException(
                "INVALID_PAYLOAD",
            )
            WebChatReservationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            WebChatReservationResult.LocalDataError -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
        ui = reduceDashboard(ui.copy(pet = reservedPet), DashboardEvent.SubmitChat(command.requestKey))
        if (ui.activeChat?.requestKey != command.requestKey) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val pending = (reservation as WebChatReservationResult.Pending).pendingChat
        if (
            pending.petId != ui.pet.petId ||
            pending.requestKey != request.requestKey ||
            pending.message != request.message
        ) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        preparedChatReservations[request.requestKey] = reservation
        val durableQueue = current.destination.withUpdatedChat(request.requestKey, pending)
        runtimeState = current.withUiState(
            ui,
            pendingChat = durableQueue.pendingChat,
            queuedChat = durableQueue.queuedChat,
        )
    }

    private fun applyChatRetry(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val request = current.uiState.activeChat
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            current.uiState.mode != DashboardMode.Chat ||
            current.uiState.chatError != ChatFailureMessage ||
            chatRetryBlockedRequestKey != request.requestKey ||
            activeChatExecution != null
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val durable = current.destination.pendingChat
            ?.takeIf { it.requestKey == request.requestKey }
            ?: current.destination.queuedChat?.takeIf { it.requestKey == request.requestKey }
            ?: throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        if (durable.requestKey != request.requestKey || durable.message != request.message) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        chatRetryBlockedRequestKey = null
        runtimeState = current.withUiState(
            current.uiState.copy(
                chatError = null,
            ),
        )
    }

    private suspend fun applyFeedConsume(command: BridgeProductCommand) {
        val food = when (command.requiredStringPayload("food", maxLength = 32)) {
            DashboardFood.BerryBowl.routeValue -> DashboardFood.BerryBowl
            DashboardFood.LeafCrunch.routeValue -> DashboardFood.LeafCrunch
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        var ui = current.uiState
        if (ui.mode == DashboardMode.Idle) {
            val opened = reduceDashboard(ui, DashboardEvent.OpenFeed)
            if (opened.mode != DashboardMode.Feed) throw WebAppRuntimeException("WRONG_STAGE")
            ui = opened
        } else if (ui.mode != DashboardMode.Feed) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val startedAt = elapsedRealtimeMillis()
        val result = gateway.applyFeed(
            requestKey = command.requestKey,
            food = food,
            audioIndex = ui.nextFeedAudioIndex,
            expectedPet = ui.pet,
        )
        val applied = when (result) {
            is WebFeedMutationResult.Applied -> result
            WebFeedMutationResult.Conflict -> throw WebAppRuntimeException("INVALID_PAYLOAD")
            WebFeedMutationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            WebFeedMutationResult.WrongStage -> throw WebAppRuntimeException("WRONG_STAGE")
            WebFeedMutationResult.LocalDataError -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
        if (applied.newlyApplied) runCatching {
            onFeedFeedback(applied.receipt.audioIndex)
        }
        if (applied.firstSession != null && applied.firstSession != ui.firstSession) {
            ui = reduceDashboard(
                ui,
                DashboardEvent.FirstSessionSynced(applied.firstSession, applied.pet),
            )
        } else {
            ui = ui.copy(pet = applied.pet, firstSession = applied.firstSession)
        }
        ui = reduceDashboard(ui, DashboardEvent.TapFood(food, command.requestKey)).copy(
            pet = applied.pet,
            firstSession = applied.firstSession,
            activeFeed = PendingFeedRequest(
                command.requestKey,
                food,
                applied.receipt.audioIndex,
            ),
            nextFeedAudioIndex = (applied.receipt.audioIndex + 1) % 3,
        )
        runtimeState = current.withUiState(ui)
        scheduleFeedCompletionLocked(applied.receipt, startedAt)
    }

    private suspend fun applyOutfitSubmit(command: BridgeProductCommand) {
        val prompt = command.requiredStringPayload(
            key = "prompt",
            maxLength = DashboardPromptMaxLength,
            trim = true,
        )
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        var ui = current.uiState
        if (ui.mode == DashboardMode.Idle) {
            ui = reduceDashboard(ui, DashboardEvent.OpenOutfit)
        } else if (ui.mode != DashboardMode.Outfit) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (ui.activeOutfit != null || current.destination.pendingOutfit != null) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        ui = reduceDashboard(ui, DashboardEvent.UpdateOutfitDraft(prompt))
        val request = PendingOutfitRequest(command.requestKey, prompt)
        when (val reservation = gateway.reserveOutfit(request, ui.pet)) {
            is WebOutfitReservationResult.Accepted -> {
                val durable = reservation.request
                if (
                    durable.petId != ui.pet.petId ||
                    durable.requestKey != command.requestKey ||
                    durable.prompt != prompt
                ) {
                    throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
                }
                val canDispatch = durable.backendJobId == null &&
                    durable.backendState in DispatchableDashboardOperationStates
                val pendingUi = durable.toRuntimeUi()
                ui = if (canDispatch) {
                    ui.copy(
                        pet = reservation.pet,
                        outfitError = null,
                        activeOutfit = request,
                        pendingOutfit = pendingUi,
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + durable.requestKey,
                    )
                } else if (
                    durable.backendJobId != null &&
                    durable.backendState !in TerminalDashboardOperationStates
                ) {
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        outfitDraft = "",
                        outfitError = null,
                        activeOutfit = null,
                        pendingOutfit = pendingUi,
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + durable.requestKey,
                        transientReply = DashboardReply(
                            durable.requestKey,
                            outfitQueuedReply(pendingUi.displayItem),
                        ),
                    )
                } else {
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        outfitError = OutfitFailureMessage,
                        activeOutfit = null,
                        pendingOutfit = pendingUi,
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + durable.requestKey,
                    )
                }
                runtimeState = current.withUiState(
                    ui,
                    pendingOutfit = durable.takeUnless {
                        it.backendState == PendingBackendState.Failed
                    },
                    operations = current.operations.copy(outfit = durable),
                )
            }
            is WebOutfitReservationResult.Finished -> {
                runtimeState = current.withUiState(
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        outfitDraft = "",
                        outfitError = null,
                        activeOutfit = null,
                        pendingOutfit = null,
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + command.requestKey,
                    ),
                    pendingOutfit = null,
                    operations = current.operations.copy(outfit = null),
                )
            }
            is WebOutfitReservationResult.InsufficientExperience -> {
                runtimeState = current.withUiState(
                    ui.copy(
                        pet = reservation.pet,
                        outfitError = OutfitInsufficientMessage,
                        activeOutfit = null,
                    ),
                )
            }
            is WebOutfitReservationResult.Busy -> throw WebAppRuntimeException("WRONG_STAGE")
            WebOutfitReservationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            WebOutfitReservationResult.WrongStage -> throw WebAppRuntimeException("WRONG_STAGE")
            WebOutfitReservationResult.Conflict -> throw WebAppRuntimeException("INVALID_PAYLOAD")
            WebOutfitReservationResult.LocalDataError -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
    }

    private fun applyOutfitRetry(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val durable = current.operations.outfit
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            durable.petId != current.uiState.pet.petId ||
            durable.backendJobId != null ||
            durable.backendState !in DispatchableDashboardOperationStates
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        attemptedOutfitDispatchKeys.remove(durable.requestKey)
        runtimeState = current.withUiState(
            current.uiState.copy(
                mode = DashboardMode.Outfit,
                outfitError = null,
                activeOutfit = PendingOutfitRequest(durable.requestKey, durable.prompt),
                pendingOutfit = durable.toRuntimeUi(),
                chargedOutfitRequestKeys = current.uiState.chargedOutfitRequestKeys +
                    durable.requestKey,
                transientReply = null,
            ),
            pendingOutfit = durable,
        )
    }

    private suspend fun applyTravelSubmit(command: BridgeProductCommand) {
        val prompt = command.requiredStringPayload(
            key = "prompt",
            maxLength = DashboardPromptMaxLength,
            trim = true,
        )
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            current.route != DashboardRoute.Dashboard ||
            current.uiState.firstSession?.stage in OnboardingBatEntryStages
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        var ui = current.uiState
        if (ui.mode == DashboardMode.Idle) {
            val opened = reduceDashboard(ui, DashboardEvent.OpenTravel)
            if (opened.mode != DashboardMode.Travel) {
                throw WebAppRuntimeException("WRONG_STAGE")
            }
            ui = opened
        } else if (ui.mode != DashboardMode.Travel) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (ui.activeTravel != null || current.destination.pendingTravel != null) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        ui = reduceDashboard(ui, DashboardEvent.UpdateTravelDraft(prompt))
        val request = PendingTravelRequest(command.requestKey, prompt)
        when (val reservation = gateway.reserveTravel(request, ui.pet)) {
            is WebTravelReservationResult.Accepted -> {
                val durable = reservation.request
                if (
                    durable.petId != ui.pet.petId ||
                    durable.requestKey != command.requestKey ||
                    durable.prompt != prompt
                ) {
                    throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
                }
                val canDispatch = durable.backendJobId == null &&
                    durable.backendState in DispatchableDashboardOperationStates
                val pendingUi = durable.toUi()
                ui = if (canDispatch) {
                    ui.copy(
                        pet = reservation.pet,
                        travelError = null,
                        activeTravel = request,
                        pendingTravel = pendingUi,
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + durable.requestKey,
                    )
                } else if (
                    durable.backendJobId != null &&
                    durable.backendState !in TerminalDashboardOperationStates
                ) {
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        travelDraft = "",
                        travelError = null,
                        activeTravel = null,
                        pendingTravel = pendingUi,
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + durable.requestKey,
                        transientReply = DashboardReply(
                            durable.requestKey,
                            travelQueuedReply(durable.prompt),
                        ),
                    )
                } else {
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        travelError = TravelFailureMessage,
                        activeTravel = null,
                        pendingTravel = pendingUi,
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + durable.requestKey,
                    )
                }
                runtimeState = current.withUiState(
                    ui,
                    pendingTravel = durable.takeUnless {
                        it.backendState == PendingBackendState.Failed
                    },
                    operations = current.operations.copy(travel = durable),
                )
            }
            is WebTravelReservationResult.Finished -> {
                runtimeState = current.withUiState(
                    ui.copy(
                        pet = reservation.pet,
                        mode = DashboardMode.Idle,
                        travelDraft = "",
                        travelError = null,
                        activeTravel = null,
                        pendingTravel = null,
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + command.requestKey,
                    ),
                    pendingTravel = null,
                    operations = current.operations.copy(
                        travel = null,
                        latestTravelResult = reservation.result,
                    ),
                )
            }
            is WebTravelReservationResult.Busy -> throw WebAppRuntimeException("WRONG_STAGE")
            WebTravelReservationResult.Missing -> throw WebAppRuntimeException("NOT_FOUND")
            WebTravelReservationResult.WrongStage -> throw WebAppRuntimeException("WRONG_STAGE")
            WebTravelReservationResult.Conflict -> throw WebAppRuntimeException("INVALID_PAYLOAD")
            WebTravelReservationResult.LocalDataError -> throw WebAppRuntimeException(
                "LOCAL_DATA_ERROR",
                retryable = true,
            )
        }
    }

    private fun applyTravelRetry(command: BridgeProductCommand) {
        command.requireEmptyPayload()
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        if (current.uiState.firstSession?.stage in OnboardingBatEntryStages) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val durable = current.operations.travel
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            durable.petId != current.uiState.pet.petId ||
            durable.backendJobId != null ||
            durable.backendState !in DispatchableDashboardOperationStates
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        attemptedTravelDispatchKeys.remove(durable.requestKey)
        runtimeState = current.withUiState(
            current.uiState.copy(
                mode = DashboardMode.Travel,
                travelError = null,
                activeTravel = PendingTravelRequest(durable.requestKey, durable.prompt),
                pendingTravel = durable.toUi(),
                queuedTravelRequestKeys = current.uiState.queuedTravelRequestKeys +
                    durable.requestKey,
                transientReply = null,
            ),
            pendingTravel = durable,
        )
    }

    private fun applyReplyAdvance(command: BridgeProductCommand) {
        val requestKey = command.requiredStringPayload("requestKey", maxLength = 200)
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val next = reduceDashboard(current.uiState, DashboardEvent.AdvanceReply(requestKey))
        if (next == current.uiState) throw WebAppRuntimeException("WRONG_STAGE")
        runtimeState = current.withUiState(next)
    }

    private fun applyReplyComplete(command: BridgeProductCommand) {
        val requestKey = command.requiredStringPayload("requestKey", maxLength = 200)
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val next = reduceDashboard(current.uiState, DashboardEvent.CompleteReply(requestKey))
        if (next == current.uiState) throw WebAppRuntimeException("WRONG_STAGE")
        runtimeState = current.withUiState(next)
    }

    private suspend fun applyChatReplyPresented(command: BridgeProductCommand) {
        val requestKey = command.requiredStringPayload("requestKey", maxLength = 200)
        val current = runtimeState as? RuntimeState.Dashboard
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        current.requireDashboardRoute()
        val projected = current.uiState.projectDurableChat(
            current.destination.pendingChat,
            current.destination.queuedChat,
        )
        if (projected.chatReply?.requestKey != requestKey) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (!gateway.acknowledgeChat(requestKey)) {
            throw WebAppRuntimeException("LOCAL_DATA_ERROR", retryable = true)
        }
        val durableQueue = current.destination.withUpdatedChat(requestKey, replacement = null)
        runtimeState = current.copy(destination = durableQueue).copy(
            uiState = projected.projectDurableChat(
                durableQueue.pendingChat,
                durableQueue.queuedChat,
            ),
        )
    }

    private fun ensureAmbientScheduledLocked() {
        if (closed) {
            cancelAmbientLocked()
            return
        }
        val current = runtimeState as? RuntimeState.Dashboard
        if (current == null) {
            ambientObservation = null
            cancelAmbientLocked()
            return
        }
        val observation = AmbientObservation(
            petId = current.uiState.pet.petId,
            route = current.route,
            firstSessionStage = current.uiState.firstSession?.stage,
            recoveryGeneration = current.ambientRecoveryGeneration,
        )
        ambientObservation?.let { previous ->
            val returnedToDashboard = previous.route != DashboardRoute.Dashboard &&
                observation.route == DashboardRoute.Dashboard
            val completedFirstSession = previous.petId == observation.petId &&
                previous.firstSessionStage != null &&
                previous.firstSessionStage != FirstSessionStage.Completed &&
                observation.firstSessionStage == FirstSessionStage.Completed
            val changedPet = previous.petId != observation.petId &&
                observation.route == DashboardRoute.Dashboard
            val recoveredQueuedReply = previous.petId == observation.petId &&
                previous.recoveryGeneration != observation.recoveryGeneration &&
                observation.route == DashboardRoute.Dashboard
            if (
                returnedToDashboard ||
                completedFirstSession ||
                changedPet ||
                recoveredQueuedReply
            ) {
                ambientActivationSequence += 1L
            }
        }
        ambientObservation = observation

        if (!foreground || current.route != DashboardRoute.Dashboard) {
            cancelAmbientLocked()
            return
        }
        if (activeAmbientExecution != null || ambientJob?.isActive == true) return
        current.uiState.activeAmbientRequestKey?.let { staleRequestKey ->
            runtimeState = current.withUiState(
                reduceDashboard(current.uiState, DashboardEvent.AmbientFailed(staleRequestKey)),
            )
        }
        if (attemptedAmbientActivationSequence == ambientActivationSequence) return
        attemptedAmbientActivationSequence = ambientActivationSequence

        val eligible = runtimeState as? RuntimeState.Dashboard ?: return
        if (
            eligible.route != DashboardRoute.Dashboard ||
            eligible.uiState.mode != DashboardMode.Idle ||
            eligible.uiState.transientReply != null ||
            eligible.uiState.firstSession?.stage !in setOf(null, FirstSessionStage.Completed)
        ) {
            return
        }
        val requestKey = UUID.randomUUID().toString()
        check(CanonicalCommandRequestKey.matches(requestKey))
        val startedUi = reduceDashboard(
            eligible.uiState,
            DashboardEvent.AmbientStarted(requestKey),
        )
        if (startedUi.activeAmbientRequestKey != requestKey) return
        val identity = AmbientExecutionIdentity(
            activation = ambientActivationSequence,
            requestKey = requestKey,
            expectedPet = eligible.uiState.pet,
        )
        runtimeState = eligible.withUiState(startedUi)
        activeAmbientExecution = identity
        val nextJob = asyncScope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                val generated = try {
                    gateway.generateAmbientReply(identity.requestKey, identity.expectedPet)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    WebAmbientGenerationResult.Failure
                }
                val changedSnapshot = mutex.withLock {
                    val latest = runtimeState as? RuntimeState.Dashboard
                    if (
                        generated !is WebAmbientGenerationResult.Success ||
                        latest == null ||
                        !latest.isAmbientAuthoritative(identity) ||
                        generated.result.reply.isBlank() ||
                        generated.result.pet != identity.expectedPet.copy(
                            message = generated.result.reply,
                        )
                    ) {
                        clearAmbientUiLocked(identity)
                        return@withLock null
                    }
                    val nextUi = reduceDashboard(
                        latest.uiState,
                        DashboardEvent.AmbientSucceeded(
                            identity.requestKey,
                            generated.result,
                        ),
                    )
                    if (
                        nextUi.pet != generated.result.pet ||
                        nextUi.transientReply?.requestKey != identity.requestKey
                    ) {
                        clearAmbientUiLocked(identity)
                        return@withLock null
                    }
                    activeAmbientExecution = null
                    runtimeState = latest.withUiState(nextUi).copy(
                        eventSnapshot = latest.eventSnapshot.copy(pet = generated.result.pet),
                    )
                    revisionSequence += 1
                    snapshotFor(runtimeState)
                }
                if (
                    generated is WebAmbientGenerationResult.Success &&
                    changedSnapshot != null
                ) {
                    scheduleAmbientPersistence(identity.expectedPet, generated.result)
                }
                changedSnapshot?.let { emitStateChanged(it) }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (ambientJob === self) ambientJob = null
                        if (activeAmbientExecution == identity) {
                            activeAmbientExecution = null
                        }
                        clearAmbientUiLocked(identity)
                    }
                }
            }
        }
        ambientJob = nextJob
        nextJob.start()
    }

    private fun scheduleAmbientPersistence(
        expectedPet: PetDashboardState,
        result: DashboardAmbientResult,
    ) {
        if (closed) return
        val nextJob = asyncScope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                persistAmbientReplyBestEffort(expectedPet, result)
            } finally {
                synchronized(ambientPersistenceJobs) {
                    ambientPersistenceJobs.remove(self)
                }
            }
        }
        synchronized(ambientPersistenceJobs) {
            if (closed) {
                nextJob.cancel()
                return
            }
            ambientPersistenceJobs += nextJob
        }
        nextJob.start()
    }

    private suspend fun persistAmbientReplyBestEffort(
        expectedPet: PetDashboardState,
        result: DashboardAmbientResult,
    ) {
        repeat(DashboardSaveMaxAttempts) { attempt ->
            val persisted = try {
                gateway.persistAmbientReply(expectedPet, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WebAmbientPersistenceResult.LocalDataError
            }
            when (persisted) {
                is WebAmbientPersistenceResult.Applied,
                WebAmbientPersistenceResult.Missing,
                WebAmbientPersistenceResult.Conflict,
                -> return
                WebAmbientPersistenceResult.LocalDataError -> if (
                    attempt + 1 < DashboardSaveMaxAttempts
                ) {
                    delayMillis(DashboardSaveRetryDelayMillis)
                }
            }
        }
    }

    private fun RuntimeState.Dashboard.isAmbientAuthoritative(
        identity: AmbientExecutionIdentity,
    ): Boolean = !closed &&
        foreground &&
        activeAmbientExecution == identity &&
        route == DashboardRoute.Dashboard &&
        uiState.mode == DashboardMode.Idle &&
        uiState.transientReply == null &&
        uiState.activeAmbientRequestKey == identity.requestKey &&
        uiState.pet == identity.expectedPet &&
        uiState.firstSession?.stage in setOf(null, FirstSessionStage.Completed)

    private fun cancelAmbientLocked() {
        val identity = activeAmbientExecution
        activeAmbientExecution = null
        ambientJob?.cancel()
        ambientJob = null
        identity?.let(::clearAmbientUiLocked)
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        current.uiState.activeAmbientRequestKey?.let { staleRequestKey ->
            runtimeState = current.withUiState(
                reduceDashboard(current.uiState, DashboardEvent.AmbientFailed(staleRequestKey)),
            )
        }
    }

    private fun clearAmbientUiLocked(identity: AmbientExecutionIdentity) {
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        if (current.uiState.activeAmbientRequestKey != identity.requestKey) return
        runtimeState = current.withUiState(
            reduceDashboard(current.uiState, DashboardEvent.AmbientFailed(identity.requestKey)),
        )
    }

    private fun ensureDueStoryRefreshScheduledLocked() {
        if (closed || dueStoryRefreshJob?.isActive == true) return
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        val durableRuntime = eventStoryRuntime ?: return
        val petId = current.uiState.pet.petId
        if (!attemptedDueStoryRefreshPetIds.add(petId)) return
        val nextJob = asyncScope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                val refreshed = try {
                    durableRuntime.refreshDueStory()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EventStoryResult.Failure(EventStoryFailureKind.LocalData)
                }
                if (
                    refreshed is EventStoryResult.Success &&
                    refreshed.value.status == DueStoryRefreshStatus.Pending
                ) {
                    gateway.enqueueEventStoryCompletionSync()
                }
                val changedSnapshot = mutex.withLock {
                    if (closed || eventStoryRuntime !== durableRuntime) return@withLock null
                    val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                    if (latest.uiState.pet.petId != petId) return@withLock null
                    val successful = refreshed as? EventStoryResult.Success ?: return@withLock null
                    val next = latest.withEventSnapshot(successful.value.snapshot)
                    if (next == latest) return@withLock null
                    runtimeState = next
                    revisionSequence += 1
                    snapshotFor(runtimeState)
                }
                changedSnapshot?.let { emitStateChanged(it) }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (dueStoryRefreshJob === self) dueStoryRefreshJob = null
                    }
                }
            }
        }
        dueStoryRefreshJob = nextJob
        nextJob.start()
    }

    private fun ensureForegroundRefreshScheduledLocked() {
        if (
            closed ||
            !foreground ||
            foregroundRefreshJob?.isActive == true ||
            runtimeState !is RuntimeState.Dashboard
        ) {
            return
        }
        val nextJob = asyncScope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            try {
                while (currentCoroutineContext().isActive && foreground && !closed) {
                    refreshDashboardForForegroundOnce()
                    if (!currentCoroutineContext().isActive || !foreground || closed) break
                    foregroundRefreshDelayMillis(ForegroundRefreshIntervalMillis)
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (foregroundRefreshJob === self) foregroundRefreshJob = null
                    }
                }
            }
        }
        foregroundRefreshJob = nextJob
        nextJob.start()
    }

    private suspend fun refreshDashboardForForegroundOnce() {
        val target = mutex.withLock {
            if (!foreground || closed) {
                null
            } else {
                (runtimeState as? RuntimeState.Dashboard)?.let { latest ->
                    latest.uiState.pet.petId to eventStoryRuntime
                }
            }
        } ?: return
        val petId = target.first
        val dashboardResult = try {
            gateway.refreshDashboardForForeground(petId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WebDashboardForegroundRefreshResult.LocalDataError
        }
        val eventResult = try {
            target.second?.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EventStoryResult.Failure(EventStoryFailureKind.LocalData)
        }
        val changedSnapshot = mutex.withLock {
            if (!foreground || closed) return@withLock null
            val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
            if (latest.uiState.pet.petId != petId) return@withLock null
            val dashboardUpdate = dashboardResult as? WebDashboardForegroundRefreshResult.Updated
            var next = dashboardUpdate
                ?.let { updated -> mergeDashboardRecovery(latest, updated.recovery) }
                ?: latest
            if (eventResult is EventStoryResult.Success) {
                next = next.withEventSnapshot(eventResult.value)
            }
            // Preserve the pre-existing foreground contract: an authoritative Dashboard refresh
            // advances the revision even when decay happens to produce identical values.
            if (next == latest && dashboardUpdate == null) return@withLock null
            runtimeState = next
            revisionSequence += 1
            ensureChatScheduledLocked()
            ensureDashboardOperationsScheduledLocked()
            snapshotFor(runtimeState)
        }
        changedSnapshot?.let { emitStateChanged(it) }
    }

    private fun ensureDashboardOperationsScheduledLocked() {
        if (closed) return
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        val outfit = current.operations.outfit
        if (
            activeOutfitExecution == null &&
            outfit != null &&
            outfit.petId == current.uiState.pet.petId &&
            outfit.backendJobId == null &&
            outfit.backendState in DispatchableDashboardOperationStates &&
            attemptedOutfitDispatchKeys.add(outfit.requestKey)
        ) {
            scheduleOutfitExecutionLocked(current, outfit)
        }
        val travel = current.operations.travel
        if (
            activeTravelExecution == null &&
            travel != null &&
            travel.petId == current.uiState.pet.petId &&
            travel.backendJobId == null &&
            travel.backendState in DispatchableDashboardOperationStates &&
            attemptedTravelDispatchKeys.add(travel.requestKey)
        ) {
            scheduleTravelExecutionLocked(current, travel)
        }
        ensureDashboardRecoveryScheduledLocked()
    }

    private fun scheduleOutfitExecutionLocked(
        current: RuntimeState.Dashboard,
        request: LocalPendingOutfit,
    ) {
        val identity = DashboardOperationIdentity(
            petId = request.petId,
            requestKey = request.requestKey,
            prompt = request.prompt,
        )
        activeOutfitExecution = identity
        activeOutfitJob = asyncScope.launch {
            val result = try {
                gateway.executeOutfit(request, current.uiState.pet)
            } catch (cancelled: CancellationException) {
                clearCancelledDashboardOperation(DashboardOperationKind.Outfit, identity)
                throw cancelled
            } catch (_: Exception) {
                WebDashboardOperationExecutionResult.Failure
            }
            val changedSnapshot = mutex.withLock {
                if (activeOutfitExecution != identity || closed) return@withLock null
                activeOutfitExecution = null
                activeOutfitJob = null
                val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                if (
                    latest.uiState.pet.petId != identity.petId ||
                    latest.operations.outfit?.requestKey != identity.requestKey
                ) {
                    ensureDashboardOperationsScheduledLocked()
                    return@withLock null
                }
                val next = when (result) {
                    is WebDashboardOperationExecutionResult.Updated -> mergeDashboardRecovery(
                        latest = latest,
                        recovery = result.recovery,
                        completedKind = DashboardOperationKind.Outfit,
                        completedIdentity = identity,
                    )
                    WebDashboardOperationExecutionResult.Failure ->
                        latest.markDashboardExecutionFailure(DashboardOperationKind.Outfit, identity)
                }
                runtimeState = next
                val changed = next != latest
                if (changed) revisionSequence += 1
                ensureDashboardOperationsScheduledLocked()
                if (changed) snapshotFor(runtimeState) else null
            }
            changedSnapshot?.let { emitStateChanged(it) }
        }
    }

    private fun scheduleTravelExecutionLocked(
        current: RuntimeState.Dashboard,
        request: LocalPendingTravelVideo,
    ) {
        val identity = DashboardOperationIdentity(
            petId = request.petId,
            requestKey = request.requestKey,
            prompt = request.prompt,
        )
        activeTravelExecution = identity
        activeTravelJob = asyncScope.launch {
            val result = try {
                gateway.executeTravel(request, current.uiState.pet)
            } catch (cancelled: CancellationException) {
                clearCancelledDashboardOperation(DashboardOperationKind.Travel, identity)
                throw cancelled
            } catch (_: Exception) {
                WebDashboardOperationExecutionResult.Failure
            }
            val changedSnapshot = mutex.withLock {
                if (activeTravelExecution != identity || closed) return@withLock null
                activeTravelExecution = null
                activeTravelJob = null
                val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                if (
                    latest.uiState.pet.petId != identity.petId ||
                    latest.operations.travel?.requestKey != identity.requestKey
                ) {
                    ensureDashboardOperationsScheduledLocked()
                    return@withLock null
                }
                val next = when (result) {
                    is WebDashboardOperationExecutionResult.Updated -> mergeDashboardRecovery(
                        latest = latest,
                        recovery = result.recovery,
                        completedKind = DashboardOperationKind.Travel,
                        completedIdentity = identity,
                    )
                    WebDashboardOperationExecutionResult.Failure ->
                        latest.markDashboardExecutionFailure(DashboardOperationKind.Travel, identity)
                }
                runtimeState = next
                val changed = next != latest
                if (changed) revisionSequence += 1
                ensureDashboardOperationsScheduledLocked()
                if (changed) snapshotFor(runtimeState) else null
            }
            changedSnapshot?.let { emitStateChanged(it) }
        }
    }

    private fun ensureDashboardRecoveryScheduledLocked() {
        if (
            closed ||
            dashboardRecoveryJob?.isActive == true ||
            activeOutfitExecution != null ||
            activeTravelExecution != null
        ) {
            return
        }
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        if (!current.hasPollableDashboardOperation()) return
        val petId = current.uiState.pet.petId
        dashboardRecoveryJob = asyncScope.launch {
            try {
                var keepPolling = true
                var attempts = 0
                while (keepPolling && !closed && attempts < DashboardOperationMaxPollAttempts) {
                    attempts += 1
                    val result = try {
                        gateway.refreshDashboardOperations(petId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        WebDashboardOperationExecutionResult.Failure
                    }
                    val update = mutex.withLock {
                        if (closed) return@withLock RecoveryLoopUpdate.Stop
                        val latest = runtimeState as? RuntimeState.Dashboard
                            ?: return@withLock RecoveryLoopUpdate.Stop
                        if (latest.uiState.pet.petId != petId) {
                            return@withLock RecoveryLoopUpdate.Stop
                        }
                        val next = when (result) {
                            is WebDashboardOperationExecutionResult.Updated ->
                                mergeDashboardRecovery(latest, result.recovery)
                            WebDashboardOperationExecutionResult.Failure -> latest
                        }
                        runtimeState = next
                        val changed = next != latest
                        if (changed) revisionSequence += 1
                        RecoveryLoopUpdate(
                            continuePolling = next.hasPollableDashboardOperation(),
                            snapshot = if (changed) snapshotFor(runtimeState) else null,
                        )
                    }
                    update.snapshot?.let { emitStateChanged(it) }
                    keepPolling = update.continuePolling
                    if (keepPolling) delayMillis(DashboardOperationPollDelayMillis)
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        dashboardRecoveryJob = null
                    }
                }
            }
        }
    }

    private fun mergeDashboardRecovery(
        latest: RuntimeState.Dashboard,
        recovery: WebDashboardRecovery,
        completedKind: DashboardOperationKind? = null,
        completedIdentity: DashboardOperationIdentity? = null,
    ): RuntimeState.Dashboard {
        if (recovery.destination.pet.petId != latest.uiState.pet.petId) return latest
        val recoveredSession = recovery.destination.firstSession
        val firstSessionStageChanged = latest.uiState.firstSession?.stage != recoveredSession?.stage
        var ui = latest.uiState.copy(
            pet = recovery.destination.pet,
            firstSession = recoveredSession,
            firstSessionIdleReply = if (firstSessionStageChanged) {
                recoveredSession?.let { firstSessionIdleReply(recovery.destination.pet, it) }
            } else {
                latest.uiState.firstSessionIdleReply
            },
            settledFirstSessionReply = if (firstSessionStageChanged) {
                null
            } else {
                latest.uiState.settledFirstSessionReply
            },
            pendingOutfit = recovery.destination.pendingOutfit?.toRuntimeUi(),
            pendingTravel = recovery.destination.pendingTravel?.toUi(),
        ).projectDurableChat(
            recovery.destination.pendingChat,
            recovery.destination.queuedChat,
        )
        if (completedKind == DashboardOperationKind.Outfit && completedIdentity != null) {
            val prior = latest.operations.outfit?.takeIf {
                it.requestKey == completedIdentity.requestKey
            }
            val durable = recovery.operations.outfit?.takeIf {
                it.requestKey == completedIdentity.requestKey
            }
            if (ui.activeOutfit?.requestKey == completedIdentity.requestKey) {
                ui = when {
                    durable == null -> ui.copy(
                        mode = DashboardMode.Idle,
                        outfitDraft = "",
                        outfitError = null,
                        activeOutfit = null,
                        pendingOutfit = null,
                        transientReply = DashboardReply(
                            completedIdentity.requestKey,
                            outfitQueuedReply(
                                prior?.toRuntimeUi()?.displayItem ?: completedIdentity.prompt,
                            ),
                        ),
                    )
                    durable.backendJobId != null &&
                        durable.backendState !in TerminalDashboardOperationStates -> ui.copy(
                        mode = DashboardMode.Idle,
                        outfitDraft = "",
                        outfitError = null,
                        activeOutfit = null,
                        pendingOutfit = durable.toRuntimeUi(),
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + durable.requestKey,
                        transientReply = DashboardReply(
                            durable.requestKey,
                            outfitQueuedReply(durable.toRuntimeUi().displayItem),
                        ),
                    )
                    else -> ui.copy(
                        mode = DashboardMode.Idle,
                        outfitError = OutfitFailureMessage,
                        activeOutfit = null,
                        pendingOutfit = durable.toRuntimeUi(),
                        chargedOutfitRequestKeys = ui.chargedOutfitRequestKeys + durable.requestKey,
                    )
                }
            }
        } else if (completedKind == DashboardOperationKind.Travel && completedIdentity != null) {
            val durable = recovery.operations.travel?.takeIf {
                it.requestKey == completedIdentity.requestKey
            }
            if (ui.activeTravel?.requestKey == completedIdentity.requestKey) {
                ui = when {
                    durable == null -> ui.copy(
                        mode = DashboardMode.Idle,
                        travelDraft = "",
                        travelError = null,
                        activeTravel = null,
                        pendingTravel = null,
                        transientReply = DashboardReply(
                            completedIdentity.requestKey,
                            travelQueuedReply(completedIdentity.prompt),
                        ),
                    )
                    durable.backendJobId != null &&
                        durable.backendState !in TerminalDashboardOperationStates -> ui.copy(
                        mode = DashboardMode.Idle,
                        travelDraft = "",
                        travelError = null,
                        activeTravel = null,
                        pendingTravel = durable.toUi(),
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + durable.requestKey,
                        transientReply = DashboardReply(
                            durable.requestKey,
                            travelQueuedReply(durable.prompt),
                        ),
                    )
                    else -> ui.copy(
                        mode = DashboardMode.Idle,
                        travelError = TravelFailureMessage,
                        activeTravel = null,
                        pendingTravel = durable.toUi(),
                        queuedTravelRequestKeys = ui.queuedTravelRequestKeys + durable.requestKey,
                    )
                }
            }
        }
        if (recovery.operations.outfit?.backendState == PendingBackendState.Failed) {
            ui = ui.copy(outfitError = OutfitFailureMessage, pendingOutfit = null)
        }
        if (recovery.operations.travel?.backendState == PendingBackendState.Failed) {
            ui = ui.copy(travelError = TravelFailureMessage, pendingTravel = null)
        }
        val priorRecoveredReplyKeys = setOfNotNull(
            latest.uiState.pendingOutfit?.requestKey,
            latest.uiState.pendingTravel?.requestKey,
        )
        val activeRecoveredReplyKeys = setOfNotNull(
            ui.pendingOutfit?.requestKey,
            ui.pendingTravel?.requestKey,
        )
        val staleRecoveredReplyCleared = completedKind == null &&
            ui.transientReply?.requestKey in priorRecoveredReplyKeys &&
            ui.transientReply?.requestKey !in activeRecoveredReplyKeys
        if (staleRecoveredReplyCleared) {
            ui = ui.copy(transientReply = null)
        }
        return latest.copy(
            destination = recovery.destination,
            uiState = ui,
            operations = recovery.operations,
            ambientRecoveryGeneration = if (staleRecoveredReplyCleared) {
                latest.ambientRecoveryGeneration + 1L
            } else {
                latest.ambientRecoveryGeneration
            },
        )
    }

    private fun RuntimeState.Dashboard.markDashboardExecutionFailure(
        kind: DashboardOperationKind,
        identity: DashboardOperationIdentity,
    ): RuntimeState.Dashboard {
        val nextUi = when (kind) {
            DashboardOperationKind.Outfit -> operations.outfit?.takeIf {
                it.petId == identity.petId &&
                    it.requestKey == identity.requestKey &&
                    it.prompt == identity.prompt
            }?.let { durable ->
                uiState.copy(
                    mode = if (uiState.mode == DashboardMode.Outfit) {
                        DashboardMode.Idle
                    } else {
                        uiState.mode
                    },
                    activeOutfit = uiState.activeOutfit?.takeUnless {
                        it.requestKey == identity.requestKey
                    },
                    outfitError = OutfitFailureMessage,
                    pendingOutfit = durable.toRuntimeUi(),
                )
            } ?: uiState
            DashboardOperationKind.Travel -> operations.travel?.takeIf {
                it.petId == identity.petId &&
                    it.requestKey == identity.requestKey &&
                    it.prompt == identity.prompt
            }?.let { durable ->
                uiState.copy(
                    mode = if (uiState.mode == DashboardMode.Travel) {
                        DashboardMode.Idle
                    } else {
                        uiState.mode
                    },
                    activeTravel = uiState.activeTravel?.takeUnless {
                        it.requestKey == identity.requestKey
                    },
                    travelError = TravelFailureMessage,
                    pendingTravel = durable.toUi(),
                )
            } ?: uiState
        }
        return withUiState(nextUi)
    }

    private fun RuntimeState.Dashboard.hasPollableDashboardOperation(): Boolean =
        operations.outfit?.isPollable() == true || operations.travel?.isPollable() == true

    private fun LocalPendingOutfit.isPollable(): Boolean =
        backendJobId != null && backendState in PollableDashboardOperationStates

    private fun LocalPendingTravelVideo.isPollable(): Boolean =
        backendJobId != null && backendState in PollableDashboardOperationStates

    private suspend fun clearCancelledDashboardOperation(
        kind: DashboardOperationKind,
        identity: DashboardOperationIdentity,
    ) {
        withContext(NonCancellable) {
            mutex.withLock {
                when (kind) {
                    DashboardOperationKind.Outfit -> if (activeOutfitExecution == identity) {
                        activeOutfitExecution = null
                        activeOutfitJob = null
                    }
                    DashboardOperationKind.Travel -> if (activeTravelExecution == identity) {
                        activeTravelExecution = null
                        activeTravelJob = null
                    }
                }
            }
        }
    }

    private fun ensureChatScheduledLocked() {
        if (closed) return
        val current = runtimeState as? RuntimeState.Dashboard ?: return
        val request = current.uiState.activeChat
        activeChatExecution?.let { active ->
            if (
                request != null &&
                active.requestKey == request.requestKey &&
                active.message == request.message
            ) {
                return
            }
            cancelActiveChatLocked()
        }
        if (request == null || chatRetryBlockedRequestKey == request.requestKey) return
        if (chatRetryBlockedRequestKey != null) chatRetryBlockedRequestKey = null
        chatExecutionSequence += 1
        val identity = ChatExecutionIdentity(
            sequence = chatExecutionSequence,
            requestKey = request.requestKey,
            message = request.message,
        )
        activeChatExecution = identity
        val expectedPet = current.uiState.pet
        val expectedStage = current.uiState.firstSession?.stage
        val preparedReservation = preparedChatReservations.remove(request.requestKey)?.takeIf {
            it.pendingChat.requestKey == request.requestKey &&
                it.pendingChat.message == request.message
        }
        activeChatJob = asyncScope.launch {
            val startedAt = elapsedRealtimeMillis()
            val result = try {
                when (
                    val reservation = preparedReservation ?: gateway.reserveChat(
                        request,
                        expectedPet,
                        originFirstSessionStage = expectedStage,
                    )
                ) {
                    is WebChatReservationResult.Pending -> gateway.executeChat(
                        request = PendingChatRequest(
                            reservation.pendingChat.requestKey,
                            reservation.pendingChat.message,
                        ),
                        expectedPet = reservation.pet,
                        expectedFirstSessionStage = reservation.originFirstSessionStage,
                    )
                    is WebChatReservationResult.Finished -> WebChatExecutionResult.Failure
                    WebChatReservationResult.Conflict,
                    WebChatReservationResult.Missing,
                    -> WebChatExecutionResult.Failure
                    WebChatReservationResult.LocalDataError ->
                        WebChatExecutionResult.RetryableFailure(
                            current.destination.chatByRequestKey(identity.requestKey)?.takeIf {
                                it.message == identity.message
                            },
                        )
                }
            } catch (cancelled: CancellationException) {
                clearCancelledChat(identity)
                throw cancelled
            } catch (_: Exception) {
                WebChatExecutionResult.RetryableFailure(
                    current.destination.chatByRequestKey(identity.requestKey)?.takeIf {
                        it.message == identity.message
                    },
                )
            }
            delayRemainingThinking(startedAt)
            val changedSnapshot = mutex.withLock {
                if (activeChatExecution != identity || closed) return@withLock null
                activeChatExecution = null
                activeChatJob = null
                val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                if (latest.uiState.activeChat?.requestKey != identity.requestKey) {
                    ensureChatScheduledLocked()
                    return@withLock null
                }
                val recoveredRetryableChat = if (result is WebChatExecutionResult.RetryableFailure) {
                    result.pendingChat?.takeIf {
                        it.requestKey == identity.requestKey && it.message == identity.message
                    } ?: latest.destination.chatByRequestKey(identity.requestKey)
                } else {
                    null
                }
                val recoveredCompletedResponse = recoveredRetryableChat?.responseText
                    ?.takeIf(String::isNotBlank) != null
                val nextState = when (result) {
                    is WebChatExecutionResult.Success -> {
                        var nextUi = latest.uiState
                        result.firstSession?.let { session ->
                            if (session != nextUi.firstSession) {
                                nextUi = reduceDashboard(
                                    nextUi,
                                    DashboardEvent.FirstSessionSynced(session, result.result.pet),
                                )
                            }
                        }
                        nextUi = reduceChatResult(
                            nextUi,
                            DashboardEvent.ChatSucceeded(identity.requestKey, result.result),
                        )
                        val durableQueue = latest.destination.withUpdatedChat(
                            identity.requestKey,
                            result.pendingChat,
                        )
                        latest.withUiState(
                            nextUi.projectDurableChat(
                                durableQueue.pendingChat,
                                durableQueue.queuedChat,
                            ),
                            pendingChat = durableQueue.pendingChat,
                            queuedChat = durableQueue.queuedChat,
                        )
                    }
                    is WebChatExecutionResult.RetryableFailure -> {
                        val durableQueue = latest.destination.withUpdatedChat(
                            identity.requestKey,
                            recoveredRetryableChat,
                        )
                        val recoveredUi = if (recoveredCompletedResponse) {
                            latest.uiState.copy(
                                chatDraft = "",
                                chatError = null,
                            )
                        } else {
                            latest.uiState.copy(
                                chatDraft = latest.uiState.chatDraft.ifBlank { identity.message },
                                chatError = ChatFailureMessage,
                            )
                        }
                        latest.withUiState(
                            recoveredUi.projectDurableChat(
                                durableQueue.pendingChat,
                                durableQueue.queuedChat,
                            ),
                            pendingChat = durableQueue.pendingChat,
                            queuedChat = durableQueue.queuedChat,
                        )
                    }
                    WebChatExecutionResult.Failure -> {
                        val durableQueue = latest.destination.withUpdatedChat(
                            identity.requestKey,
                            replacement = null,
                        )
                        latest.withUiState(
                            reduceChatResult(
                                latest.uiState,
                                DashboardEvent.ChatFailed(identity.requestKey),
                            ),
                            pendingChat = durableQueue.pendingChat,
                            queuedChat = durableQueue.queuedChat,
                        )
                    }
                }
                runtimeState = nextState
                revisionSequence += 1
                if (result is WebChatExecutionResult.RetryableFailure && !recoveredCompletedResponse) {
                    chatRetryBlockedRequestKey = identity.requestKey
                } else {
                    if (chatRetryBlockedRequestKey == identity.requestKey) {
                        chatRetryBlockedRequestKey = null
                    }
                    ensureChatScheduledLocked()
                }
                snapshotFor(runtimeState)
            } ?: return@launch
            emitStateChanged(changedSnapshot)
        }
    }

    private fun reduceChatResult(
        ui: DashboardUiState,
        event: DashboardEvent,
    ): DashboardUiState {
        if (ui.mode == DashboardMode.Chat) return reduceDashboard(ui, event)
        return reduceDashboard(ui.copy(mode = DashboardMode.Chat), event).copy(mode = ui.mode)
    }

    private fun scheduleFeedCompletionLocked(
        receipt: LocalDashboardFeedReceipt,
        startedAt: Long,
    ) {
        asyncScope.launch {
            delayRemainingThinking(startedAt)
            val changedSnapshot = mutex.withLock {
                if (closed) return@withLock null
                val latest = runtimeState as? RuntimeState.Dashboard ?: return@withLock null
                if (
                    latest.uiState.mode != DashboardMode.Feed ||
                    latest.uiState.activeFeed?.requestKey != receipt.requestKey
                ) {
                    return@withLock null
                }
                val next = reduceDashboard(
                    latest.uiState,
                    DashboardEvent.FeedSucceeded(
                        requestKey = receipt.requestKey,
                        reply = receipt.reply,
                        explicitPortions = receipt.explicitPortions,
                        autoAdvanceDelayMillis = receipt.autoAdvanceDelayMillis,
                    ),
                )
                runtimeState = latest.withUiState(next)
                revisionSequence += 1
                snapshotFor(runtimeState)
            } ?: return@launch
            emitStateChanged(changedSnapshot)
        }
    }

    private suspend fun delayRemainingThinking(startedAt: Long) {
        val elapsed = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0L)
        val remaining = (DashboardMinimumThinkingMillis - elapsed).coerceAtLeast(0L)
        if (remaining > 0L) delayMillis(remaining)
    }

    private suspend fun emitStateChanged(snapshot: WebAppSnapshot) {
        runtimeEvents.emit(
            WebAppRuntimeEvent(
                type = "stateChanged",
                payload = BridgeCodec.json.encodeToJsonElement(snapshot),
            ),
        )
    }

    private fun onMediaPublication(publication: WebMediaPublication) {
        if (closed) return
        asyncScope.launch {
            val changedSnapshot = mutex.withLock {
                if (closed || !mediaRegistry.isPublicationCurrent(publication)) {
                    return@withLock null
                }
                revisionSequence += 1L
                snapshotFor(runtimeState)
            }
            changedSnapshot?.let { emitStateChanged(it) }
        }
    }

    private fun cancelActiveChatLocked() {
        activeChatExecution?.requestKey?.let(preparedChatReservations::remove)
        activeChatExecution = null
        activeChatJob?.cancel()
        activeChatJob = null
    }

    private suspend fun clearCancelledChat(identity: ChatExecutionIdentity) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (activeChatExecution == identity) {
                    activeChatExecution = null
                    activeChatJob = null
                }
            }
        }
    }

    private fun snapshotFor(
        state: RuntimeState,
        petTapFeedback: WebPetTapFeedbackSnapshot? = null,
    ): WebAppSnapshot = mediaRegistry.projectSnapshot {
        projectSnapshotFor(state, petTapFeedback)
    }

    private fun projectSnapshotFor(
        state: RuntimeState,
        petTapFeedback: WebPetTapFeedbackSnapshot? = null,
    ): WebAppSnapshot {
        ensureAmbientScheduledLocked()
        val createState = state as? RuntimeState.Create
        val create = createState?.state?.toWebSnapshot(createState.effectFailure)
        val dashboard = state as? RuntimeState.Dashboard
        val pet = dashboard?.uiState?.let { ui ->
            ui.pet.toWebSnapshot(
                message = dashboardIdleMessage(ui).orEmpty(),
                media = mediaProjection(ui.pet),
            )
        }
        val firstSession = dashboard?.uiState?.firstSession?.let {
            WebFirstSessionSnapshot(
                stage = it.stage.storageValue,
                allowedAction = firstSessionMainAction(it)?.name?.lowercase(),
                messagePortions = firstSessionDashboardMessagePortions(
                    dashboard.uiState.pet,
                    it,
                ),
                selectedDestination = it.selectedDestination,
            )
        }
        val dashboardSnapshot = dashboard?.let {
            it.uiState.toWebDashboardSnapshot(it.operations)
        }
        val eventsSnapshot = dashboard?.let {
            projectEventStorySnapshotForWeb(
                snapshot = it.eventSnapshot,
                registry = mediaRegistry,
                initialFocusTravelRequestKey = it.initialFocusTravelRequestKey,
            )
        }
        val openedStorySnapshot = dashboard?.openedStory?.let { opened ->
            projectDurableStorySnapshotForWeb(
                snapshot = opened,
                origin = requireNotNull(dashboard.storyOrigin),
                registry = mediaRegistry,
            )
        }
        val pending = dashboard?.destination?.toWebPendingOperations()
            ?: WebPendingOperationsSnapshot()
        val route = when (state) {
            RuntimeState.Uninitialized -> "localDataError"
            RuntimeState.ConnectionError -> "connectionError"
            RuntimeState.LocalDataError -> "localDataError"
            is RuntimeState.Create -> "create"
            is RuntimeState.Dashboard -> state.route.webValue
        }
        val dashboardMode = dashboard?.uiState?.mode?.routeValue() ?: "idle"
        val contentHash = revisionFor(
            WebRevisionProjection(
                route = route,
                dashboardMode = dashboardMode,
                create = create,
                pet = pet,
                firstSession = firstSession,
                dashboard = dashboardSnapshot,
                events = eventsSnapshot,
                story = openedStorySnapshot,
                pending = pending,
            ),
        )
        return WebAppSnapshot(
            appVersion = appVersion,
            webBundleVersion = webBundleVersion,
            revision = "r1:$runtimeId:$revisionSequence:$contentHash",
            route = route,
            dashboardMode = dashboardMode,
            pendingDeepLinkTarget = currentPendingDeepLinkTarget(),
            reducedMotion = reducedMotion(),
            safeArea = safeArea,
            notificationPermission = notificationPermission().takeIf {
                it in setOf("unknown", "granted", "denied")
            } ?: "unknown",
            create = create,
            pet = pet,
            firstSession = firstSession,
            dashboard = dashboardSnapshot,
            events = eventsSnapshot,
            story = openedStorySnapshot,
            pending = pending,
            petTapFeedback = petTapFeedback,
        )
    }

    private sealed interface RuntimeState {
        data object Uninitialized : RuntimeState
        data object ConnectionError : RuntimeState
        data object LocalDataError : RuntimeState
        data class Create(
            val state: CreatePetState,
            val effectFailure: CreateEffectFailure? = null,
        ) : RuntimeState
        data class Dashboard(
            val destination: AccountStartupDestination.Dashboard,
            val uiState: DashboardUiState,
            val operations: WebDashboardOperationState = WebDashboardOperationState(),
            val eventSnapshot: EventStorySnapshot,
            val route: DashboardRoute = DashboardRoute.Dashboard,
            val openedStory: DurableStorySnapshot? = null,
            val storyOrigin: WebDurableStoryOrigin? = null,
            val initialFocusTravelRequestKey: String? = null,
            val ambientRecoveryGeneration: Long = 0L,
        ) : RuntimeState {
            fun withUiState(
                next: DashboardUiState,
                pendingChat: LocalPendingChat? = destination.pendingChat,
                queuedChat: LocalPendingChat? = destination.queuedChat,
                pendingOutfit: LocalPendingOutfit? = destination.pendingOutfit,
                pendingTravel: LocalPendingTravelVideo? = destination.pendingTravel,
                operations: WebDashboardOperationState = this.operations,
            ): Dashboard = copy(
                destination = destination.copy(
                    pet = next.pet,
                    firstSession = next.firstSession,
                    pendingChat = pendingChat,
                    queuedChat = queuedChat,
                    pendingOutfit = pendingOutfit,
                    pendingTravel = pendingTravel,
                ),
                uiState = next,
                operations = operations,
            )
        }
    }

    private data class CommandReceipt(
        val fingerprint: String,
        val petTapFeedback: WebPetTapFeedbackSnapshot?,
    )

    private data class GenerationIdentity(
        val petId: String,
        val requestKey: String,
        val attempt: Int,
    )

    private data class ChatExecutionIdentity(
        val sequence: Long,
        val requestKey: String,
        val message: String,
    )

    private data class AmbientExecutionIdentity(
        val activation: Long,
        val requestKey: String,
        val expectedPet: PetDashboardState,
    )

    private data class AmbientObservation(
        val petId: String,
        val route: DashboardRoute,
        val firstSessionStage: FirstSessionStage?,
        val recoveryGeneration: Long,
    )

    private data class PendingNotificationDeepLink(
        val token: Long,
        val extras: NotificationDeepLinkExtras,
    )

    private data class NotificationDeepLinkContext(
        val pet: PetDashboardState,
        val firstSession: LocalFirstSession?,
        val durableRuntime: DurableEventStoryRuntime?,
    )

    private sealed interface NotificationDeepLinkResolution {
        data class Dashboard(
            val snapshot: EventStorySnapshot?,
        ) : NotificationDeepLinkResolution

        data class Story(
            val opened: OpenedEventStory,
        ) : NotificationDeepLinkResolution

        data class Events(
            val snapshot: EventStorySnapshot,
            val requestKey: String,
        ) : NotificationDeepLinkResolution
    }

    private data class DashboardOperationIdentity(
        val petId: String,
        val requestKey: String,
        val prompt: String,
    )

    private data class StoryExecutionIdentity(
        val petId: String,
        val internalStoryId: String,
        val visibleStoryId: String,
        val requestKey: String,
        val choice: String,
        val sequence: Long,
        val durableRuntime: DurableEventStoryRuntime,
    )

    private enum class DashboardOperationKind { Outfit, Travel }

    private enum class DashboardRoute(val webValue: String) {
        Dashboard("dashboard"),
        Events("events"),
        Story("story"),
    }

    private data class RecoveryLoopUpdate(
        val continuePolling: Boolean,
        val snapshot: WebAppSnapshot?,
    ) {
        companion object {
            val Stop = RecoveryLoopUpdate(false, null)
        }
    }

    private fun RuntimeState.Create.retryTarget(): CreateRetryTarget? =
        effectFailure?.target ?: if (state.generation is GenerationStatus.Error) {
            CreateRetryTarget.Generation
        } else {
            null
        }

    private fun CreatePetState.matches(identity: GenerationIdentity): Boolean =
        generation is GenerationStatus.Running &&
            generationAttempt == identity.attempt &&
            pending?.petId == identity.petId &&
            pending?.requestKey == identity.requestKey

    private fun RuntimeState.Dashboard.requireDashboardRoute() {
        if (route != DashboardRoute.Dashboard || openedStory != null || storyOrigin != null) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
    }

    private fun RuntimeState.Dashboard.requireOpenedStory(
        requestedStoryId: String? = null,
    ): OpenedStoryCommandTarget {
        val opened = openedStory
        val origin = storyOrigin
        if (
            route != DashboardRoute.Story ||
            opened == null ||
            origin == null ||
            (requestedStoryId != null && opened.externalStoryId() != requestedStoryId) ||
            (opened.kind == DurableStoryKind.OnboardingBat &&
                origin != WebDurableStoryOrigin.Dashboard)
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        return OpenedStoryCommandTarget(
            snapshot = opened,
            internalStoryId = opened.story.travelId,
        )
    }

    private fun RuntimeState.Dashboard.withEventSnapshot(
        snapshot: EventStorySnapshot,
    ): RuntimeState.Dashboard {
        if (snapshot.pet.petId != uiState.pet.petId) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        val stageChanged = uiState.firstSession?.stage != snapshot.firstSession?.stage
        val nextUi = uiState.copy(
            pet = snapshot.pet,
            firstSession = snapshot.firstSession,
            firstSessionIdleReply = if (stageChanged) {
                snapshot.firstSession?.let { firstSessionIdleReply(snapshot.pet, it) }
            } else {
                uiState.firstSessionIdleReply
            },
            settledFirstSessionReply = if (stageChanged) {
                null
            } else {
                uiState.settledFirstSessionReply
            },
        )
        return copy(
            destination = destination.copy(
                pet = snapshot.pet,
                firstSession = snapshot.firstSession,
            ),
            uiState = nextUi,
            eventSnapshot = snapshot,
        )
    }

    private fun WebDurableStoryOrigin.toDashboardRoute(): DashboardRoute = when (this) {
        WebDurableStoryOrigin.Events -> DashboardRoute.Events
        WebDurableStoryOrigin.Dashboard -> DashboardRoute.Dashboard
    }

    private fun RuntimeState.shouldRetryBootstrap(): Boolean = this == RuntimeState.Uninitialized ||
        this == RuntimeState.ConnectionError || this == RuntimeState.LocalDataError
}

private fun BridgeProductCommand.fingerprint(): String =
    "$type:${BridgeCodec.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)}"

private data class WebCreateAnswerPayload(
    val value: String,
    val step: Int,
)

private data class WebStoryChoicePayload(
    val storyId: String,
    val choice: String,
)

private data class WebDashboardDraftPayload(
    val mode: String,
    val value: String,
)

private fun BridgeProductCommand.requiredCreateAnswer(): WebCreateAnswerPayload {
    if (payload.keys != setOf("answer", "step")) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    val primitive = payload["answer"] as? JsonPrimitive
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    if (!primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
    val raw = primitive.content
    if (raw.length !in 1..MaxCustomPromptLength || raw.trim().isEmpty()) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    val stepPrimitive = payload["step"] as? JsonPrimitive
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    if (stepPrimitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
    val step = stepPrimitive.content.toIntOrNull()
        ?.takeIf { it in CreationQuestions.indices }
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    return WebCreateAnswerPayload(raw, step)
}

private fun BridgeProductCommand.requiredStringPayload(
    key: String,
    maxLength: Int,
    trim: Boolean = false,
): String {
    if (payload.keys != setOf(key)) throw WebAppRuntimeException("INVALID_PAYLOAD")
    val primitive = payload[key] as? JsonPrimitive
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    if (!primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
    val raw = primitive.content
    val value = if (trim) raw.trim() else raw
    if (value.isEmpty() || value.length > maxLength) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    return value
}

private fun BridgeProductCommand.requiredDashboardDraftPayload(): WebDashboardDraftPayload {
    if (payload.keys != setOf("mode", "value")) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    fun stringValue(key: String): String {
        val primitive = payload[key] as? JsonPrimitive
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (!primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
        return primitive.content
    }
    val mode = stringValue("mode")
    val value = stringValue("value")
    if (mode !in DashboardDraftModes || value.length > DashboardPromptMaxLength) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    return WebDashboardDraftPayload(mode, value)
}

private fun BridgeProductCommand.requiredIdentifierPayload(key: String): String {
    val value = requiredStringPayload(key = key, maxLength = 160)
    if (value != value.trim() || value.any { it.isISOControl() }) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    return value
}

private fun BridgeProductCommand.requiredStoryChoicePayload(): WebStoryChoicePayload {
    if (payload.keys != setOf("storyId", "choice")) {
        throw WebAppRuntimeException("INVALID_PAYLOAD")
    }
    fun requiredValue(key: String, maxLength: Int): String {
        val primitive = payload[key] as? JsonPrimitive
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (!primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
        val raw = primitive.content
        val value = raw.trim()
        if (
            raw != value ||
            value.isEmpty() ||
            value.length > maxLength ||
            value.any { it.isISOControl() }
        ) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        return value
    }
    return WebStoryChoicePayload(
        storyId = requiredValue("storyId", 160),
        choice = requiredValue("choice", 240),
    )
}

private fun BridgeProductCommand.requiredLongPayload(key: String): Long {
    if (payload.keys != setOf(key)) throw WebAppRuntimeException("INVALID_PAYLOAD")
    val primitive = payload[key] as? JsonPrimitive
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    if (primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
    return primitive.content.toLongOrNull()?.takeIf { it >= 0L }
        ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
}

private fun BridgeProductCommand.requireEmptyPayload() {
    if (payload.isNotEmpty()) throw WebAppRuntimeException("INVALID_PAYLOAD")
}

private fun LocalPendingOutfit.toRuntimeUi() =
    toUi(preparedDisplayItem ?: canonicalOutfitDisplayItem(prompt))

private fun DashboardUiState.projectDurableChat(
    pendingChat: LocalPendingChat?,
    queuedDurableChat: LocalPendingChat?,
): DashboardUiState {
    val durableRows = listOfNotNull(pendingChat, queuedDurableChat)
    val unfinished = durableRows.filter { it.responseText == null }
    val durableActive = unfinished.getOrNull(0)?.toPendingRequest()
    val durableQueued = unfinished.getOrNull(1)?.toPendingRequest()
    val recoveredReply = durableRows.firstOrNull { it.responseText != null }
        ?.toRecoveredDashboardReply(pet.name)
    val projectedReply = when {
        recoveredReply == null -> chatReply
        chatReply?.requestKey == recoveredReply.requestKey -> chatReply
        else -> recoveredReply
    }
    return copy(
        activeChat = durableActive,
        queuedChat = durableQueued,
        // A Room row is the presentation queue. Always keep the oldest completed response on
        // screen until it is acknowledged; otherwise a fast second completion can hide it. When
        // the in-memory reply is for that exact row it is richer than Room's raw responseText:
        // it owns mandatory onboarding portions and the current presentation index.
        chatReply = projectedReply,
    )
}

private fun recoveredDashboardState(
    destination: AccountStartupDestination.Dashboard,
): DashboardUiState {
    val durableChats = listOfNotNull(destination.pendingChat, destination.queuedChat)
    val unfinishedChats = durableChats.filter { it.responseText == null }
    val pendingChat = unfinishedChats.getOrNull(0)?.toPendingRequest()
    val queuedChat = unfinishedChats.getOrNull(1)?.toPendingRequest()
    val recoveredChatReply = durableChats.firstOrNull { it.responseText != null }
        ?.toRecoveredDashboardReply(destination.pet.name)
    val pendingOutfit = destination.pendingOutfit?.toRuntimeUi()
    val pendingTravel = destination.pendingTravel?.toUi()
    return DashboardUiState(
        pet = destination.pet,
        firstSession = destination.firstSession,
        firstSessionIdleReply = destination.firstSession?.let {
            firstSessionIdleReply(destination.pet, it)
        },
        mode = if (durableChats.isNotEmpty()) DashboardMode.Chat else DashboardMode.Idle,
        activeChat = pendingChat,
        queuedChat = queuedChat,
        chatReply = recoveredChatReply,
        pendingOutfit = pendingOutfit,
        chargedOutfitRequestKeys = pendingOutfit?.let { setOf(it.requestKey) }.orEmpty(),
        pendingTravel = pendingTravel,
        queuedTravelRequestKeys = pendingTravel?.let { setOf(it.requestKey) }.orEmpty(),
        transientReply = pendingOutfit?.let {
            DashboardReply(it.requestKey, outfitQueuedReply(it.displayItem))
        } ?: pendingTravel?.let {
            DashboardReply(it.requestKey, travelQueuedReply(it.prompt))
        },
    )
}

private fun LocalPendingChat.toPendingRequest() = PendingChatRequest(requestKey, message)

private fun LocalPendingChat.toRecoveredDashboardReply(petName: String): DashboardReply {
    val durableResponse = requireNotNull(responseText)
    val explicitPortions = when (originFirstSessionStage) {
        FirstSessionStage.AwaitingChat -> listOf(
            firstSessionReactionReply(
                reply = durableResponse,
                fallback = FirstSessionAfterNameFallback,
                petName = petName,
                unsafeReplyFallback = FirstSessionAfterNameFallback,
            ),
            FirstSessionAfterName,
        )
        FirstSessionStage.AwaitingChatFollowup -> listOf(
            firstSessionReactionReply(
                reply = durableResponse,
                fallback = FirstSessionAfterChatFallback,
                petName = petName,
                unsafeReplyFallback = FirstSessionSensitiveTopicFallback,
            ),
            FirstSessionAfterChat,
        )
        else -> null
    }
    return DashboardReply(
        requestKey = requestKey,
        text = explicitPortions?.joinToString(" ") ?: durableResponse,
        explicitPortions = explicitPortions,
    )
}

private fun AccountStartupDestination.Dashboard.chatByRequestKey(
    requestKey: String,
): LocalPendingChat? = when (requestKey) {
    pendingChat?.requestKey -> pendingChat
    queuedChat?.requestKey -> queuedChat
    else -> null
}

private fun AccountStartupDestination.Dashboard.withUpdatedChat(
    requestKey: String,
    replacement: LocalPendingChat?,
): AccountStartupDestination.Dashboard {
    require(replacement == null || replacement.requestKey == requestKey)
    val rows = listOfNotNull(pendingChat, queuedChat).toMutableList()
    val index = rows.indexOfFirst { it.requestKey == requestKey }
    when {
        index >= 0 && replacement == null -> rows.removeAt(index)
        index >= 0 -> rows[index] = requireNotNull(replacement)
        replacement != null && rows.size < MaxRuntimeChatQueueSize -> rows += replacement
    }
    return copy(
        pendingChat = rows.getOrNull(0),
        queuedChat = rows.getOrNull(1),
    )
}

private fun chatOriginStage(stage: FirstSessionStage?): FirstSessionStage? = stage.takeIf {
    it == FirstSessionStage.AwaitingChat || it == FirstSessionStage.AwaitingChatFollowup
}

private fun queuedChatOriginStage(stage: FirstSessionStage?): FirstSessionStage? = when (stage) {
    FirstSessionStage.AwaitingChat -> FirstSessionStage.AwaitingChatFollowup
    FirstSessionStage.AwaitingChatFollowup -> null
    else -> null
}

private const val MaxRuntimeChatQueueSize = 2

private fun emptyEventStorySnapshot(
    destination: AccountStartupDestination.Dashboard,
): EventStorySnapshot = EventStorySnapshot(
    pet = destination.pet,
    firstSession = destination.firstSession,
    history = eventHistoryUiState(emptyList()),
    lastViewedAtEpochMillis = null,
    badgeCount = 0,
)

private fun CreatePetState.toWebSnapshot(
    effectFailure: CreateEffectFailure?,
): WebCreateSnapshot {
    val generationError = generation as? GenerationStatus.Error
    return WebCreateSnapshot(
        step = step,
        title = question?.title ?: "Твой новый друг уже рядом",
        options = question?.options.orEmpty(),
        nextQuestion = CreationQuestions.getOrNull(step + 1)?.let {
            WebCreateQuestionSnapshot(it.title, it.options)
        },
        phase = when (backgroundPhase) {
            CreationBackgroundPhase.Initial -> "initial"
            CreationBackgroundPhase.Transition -> "transition"
            CreationBackgroundPhase.Formed -> "formed"
        },
        generation = effectFailure?.let { "retryable" } ?: when (val value = generation) {
            GenerationStatus.Idle -> "idle"
            GenerationStatus.Running -> "running"
            is GenerationStatus.Ready -> "ready"
            is GenerationStatus.Error -> if (value.newRequestRequired) "failed" else "retryable"
        },
        error = effectFailure?.message ?: generationError?.message,
        retryTarget = effectFailure?.target?.webValue ?: generationError?.let { "generation" },
    )
}

private fun PetDashboardState.toWebSnapshot(
    message: String,
    media: WebPetMediaSnapshot,
): WebPetSnapshot = WebPetSnapshot(
    name = name,
    stageLabel = stageLabel,
    experience = experience,
    hunger = hunger,
    happiness = happiness,
    energy = energy,
    message = message,
    petTapProgress = petTapProgress,
    media = media,
)

private data class OpenedStoryCommandTarget(
    val snapshot: DurableStorySnapshot,
    val internalStoryId: String,
)

private fun AccountStartupDestination.Dashboard.toWebPendingOperations() =
    WebPendingOperationsSnapshot(
        chat = pendingChat?.let {
            WebPendingOperationSnapshot(
                requestKey = it.requestKey,
                status = if (it.responseText == null) "pending" else "completed",
                prompt = it.message,
            )
        },
        outfit = pendingOutfit?.let {
            WebPendingOperationSnapshot(
                requestKey = it.requestKey,
                status = it.backendState.webValue(it.backendErrorCode),
                prompt = it.prompt,
            )
        },
        travel = pendingTravel?.let {
            WebPendingOperationSnapshot(
                requestKey = it.requestKey,
                status = it.backendState.webValue(it.backendErrorCode),
                prompt = it.prompt,
            )
        },
    )

private fun DashboardUiState.toWebDashboardSnapshot(
    operations: WebDashboardOperationState,
): WebDashboardSnapshot =
    WebDashboardSnapshot(
        reply = projectedReply(),
        chat = WebDashboardChatSnapshot(
            draft = chatDraft,
            error = chatError,
            activeRequestKey = activeChat?.requestKey,
            queuedRequestKey = queuedChat?.requestKey,
            thinking = activeChat != null && chatError == null,
        ),
        feed = WebDashboardFeedSnapshot(
            error = feedError,
            activeRequestKey = activeFeed?.requestKey,
            activeFood = activeFeed?.food?.routeValue,
            audioIndex = activeFeed?.audioIndex,
            pulseId = feedPulseId,
            thinking = activeFeed != null,
        ),
        outfit = WebDashboardOutfitSnapshot(
            draft = outfitDraft,
            error = outfitError,
            activeRequestKey = activeOutfit?.requestKey,
            thinking = activeOutfit != null,
            experienceCost = OutfitExperienceCost,
            pending = operations.outfit?.let { pending ->
                WebDashboardOutfitPendingSnapshot(
                    requestKey = pending.requestKey,
                    status = pending.backendState.webValue(pending.backendErrorCode),
                    prompt = pending.prompt,
                    displayItem = pending.toRuntimeUi().displayItem,
                    experienceCost = pending.experienceCost,
                )
            },
        ),
        travel = WebDashboardTravelSnapshot(
            draft = travelDraft,
            error = travelError,
            activeRequestKey = activeTravel?.requestKey,
            thinking = activeTravel != null,
            pending = operations.travel?.let { pending ->
                WebDashboardTravelPendingSnapshot(
                    requestKey = pending.requestKey,
                    status = pending.backendState.webValue(pending.backendErrorCode),
                    prompt = pending.prompt,
                )
            },
        ),
    )

private fun DashboardUiState.projectedReply(): WebDashboardReplySnapshot? {
    val projected = when (mode) {
        DashboardMode.Chat -> chatReply?.let { "chat" to it }
            ?: settledFirstSessionReply?.let { "settled" to it }
        DashboardMode.Feed -> feedReply?.let { "feed" to it }
            ?: settledFirstSessionReply?.let { "settled" to it }
        DashboardMode.Idle -> transientReply?.let { "transient" to it }
            ?: firstSessionIdleReply?.let { "firstSession" to it }
            ?: settledFirstSessionReply?.let { "settled" to it }
        DashboardMode.Outfit,
        DashboardMode.Travel,
        -> null
    } ?: return null
    val (source, reply) = projected
    return WebDashboardReplySnapshot(
        source = source,
        requestKey = reply.requestKey,
        portions = reply.portions,
        portionIndex = reply.portionIndex,
        hasNextPortion = reply.hasNextPortion,
        autoAdvanceDelayMillis = reply.autoAdvanceDelayMillis,
    )
}

private fun PendingBackendState.webValue(errorCode: String?): String = when (this) {
    PendingBackendState.Pending -> "pending"
    PendingBackendState.Dispatching -> "dispatching"
    PendingBackendState.Attached -> "attached"
    PendingBackendState.ForegroundReady,
    PendingBackendState.Ready,
    -> "ready"
    PendingBackendState.Retryable -> "retryable"
    PendingBackendState.OutcomeUnknown -> "outcomeUnknown"
    PendingBackendState.Failed -> if (errorCode == "APPLY_CONFLICT") "applyConflict" else "failed"
}

private fun DashboardMode.routeValue(): String = name.lowercase()

@Serializable
private data class WebRevisionProjection(
    val route: String,
    val dashboardMode: String,
    val create: WebCreateSnapshot?,
    val pet: WebPetSnapshot?,
    val firstSession: WebFirstSessionSnapshot?,
    val dashboard: WebDashboardSnapshot?,
    val events: WebEventsSnapshot?,
    val story: WebOpenedStorySnapshot?,
    val pending: WebPendingOperationsSnapshot,
)

private fun revisionFor(projection: WebRevisionProjection): String {
    val encoded = BridgeCodec.json.encodeToString(projection).encodeToByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    val hex = buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HexDigits[value ushr 4])
            append(HexDigits[value and 0x0f])
        }
    }
    return "sha256:$hex"
}

private const val HexDigits = "0123456789abcdef"
private val DashboardDraftModes = setOf("chat", "outfit", "travel")
private val OnboardingBatEntryStages = setOf(
    FirstSessionStage.AwaitingTravel,
    FirstSessionStage.ConfirmingTravel,
)
