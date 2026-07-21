@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.HttpGuestSessionExchange
import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.AndroidInstallationIdProvider
import com.gigagochi.app.core.auth.LocalSessionBootstrapCoordinator
import com.gigagochi.app.core.auth.LocalSessionBootstrapOutcome
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.background.MvpSyncScheduler
import com.gigagochi.app.core.background.CreateSyncScheduler
import com.gigagochi.app.core.background.RequestNotificationPermissionOnce
import com.gigagochi.app.core.background.AndroidLocalNotificationEmitter
import com.gigagochi.app.core.background.CompletionNotificationDispatcher
import com.gigagochi.app.core.background.ManualGenerationKind
import com.gigagochi.app.core.background.manualGenerationFailedNotification
import com.gigagochi.app.core.background.notificationsAllowed
import com.gigagochi.app.core.background.petReadyNotification
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.network.AndroidFeatureApi
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.network.AuthenticatedFeatureClient
import com.gigagochi.app.core.network.UrlConnectionFeatureHttpTransport
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.create.CreateDebugState
import com.gigagochi.app.feature.create.CreateBackgroundMediaCoordinator
import com.gigagochi.app.feature.create.CreatePetRoute
import com.gigagochi.app.feature.create.CreateFinalizationCoordinator
import com.gigagochi.app.feature.create.CreatePendingCoordinator
import com.gigagochi.app.feature.create.RealPetGenerationAdapter
import com.gigagochi.app.feature.dashboard.DashboardRoute
import com.gigagochi.app.feature.dashboard.DashboardDebugState
import com.gigagochi.app.feature.dashboard.DashboardDurableOperations
import com.gigagochi.app.feature.dashboard.DeterministicLocalDashboardFeedAdapter
import com.gigagochi.app.feature.dashboard.ForegroundPendingRecoveryCoordinator
import com.gigagochi.app.feature.dashboard.ForegroundRecoverySignal
import com.gigagochi.app.feature.dashboard.DashboardOutcomeApplicationCoordinator
import com.gigagochi.app.feature.dashboard.RealDashboardChatAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardTravelAdapter
import com.gigagochi.app.feature.dashboard.toUi
import com.gigagochi.app.feature.dashboard.durableDashboardRequestKey
import com.gigagochi.app.feature.events.EventHistoryScreen
import com.gigagochi.app.feature.events.AndroidTravelVideoSharer
import com.gigagochi.app.feature.events.eventHistoryUiState
import com.gigagochi.app.feature.travel.TravelDebugState
import com.gigagochi.app.feature.travel.TravelEntryRoute
import com.gigagochi.app.feature.travel.ScheduledStoryCoordinator
import com.gigagochi.app.feature.travel.ScheduledStoryRoute
import com.gigagochi.app.feature.travel.StoryReceiptCoordinator
import com.gigagochi.app.feature.travel.TravelEntryPet
import com.gigagochi.app.feature.travel.onboardingBatStory
import com.gigagochi.app.feature.travel.onboardingBatStoryResult
import com.gigagochi.app.feature.travel.rememberTravelReducedMotionPreference
import com.gigagochi.app.feature.travel.DurableOnboardingBatChoiceAdapter
import com.gigagochi.app.feature.travel.DurableOnboardingBatFinishAdapter
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.debugmenu.DebugMenuBindings
import com.gigagochi.app.debugmenu.DebugMenuHost
import com.gigagochi.app.debugmenu.scheduleDebugPush
import com.gigagochi.app.debugmenu.clearDebugFixtureSelection
import com.gigagochi.app.debugmenu.debugFixturePet
import com.gigagochi.app.debugmenu.debugDeadPetId
import com.gigagochi.app.debugmenu.debugPreferredPetId
import com.gigagochi.app.debugmenu.debugSavedPetId
import com.gigagochi.app.debugmenu.debugGenerationAdapter
import com.gigagochi.app.debugmenu.debugChatAdapter
import com.gigagochi.app.debugmenu.debugOutfitAdapter
import com.gigagochi.app.debugmenu.debugTravelAdapter
import com.gigagochi.app.debugmenu.debugScheduledStoryService
import com.gigagochi.app.debugmenu.ensureDebugFixtureStories
import com.gigagochi.app.debugmenu.setDebugFixtureSelection
import com.gigagochi.app.debugmenu.setDebugDeadPetId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal enum class AppRoute { Create, Dashboard, Events, Travel, Story, ConnectionError, LocalDataError }

private const val AppRouteTransitionMillis = 300
private val AppRouteTransitionEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

internal fun appRouteDepth(route: AppRoute?): Int = when (route) {
    AppRoute.Events,
    AppRoute.Travel,
    -> 1
    AppRoute.Story -> 2
    AppRoute.Create,
    AppRoute.Dashboard,
    AppRoute.ConnectionError,
    AppRoute.LocalDataError,
    null,
    -> 0
}

internal fun isForwardAppRouteTransition(initial: AppRoute?, target: AppRoute?): Boolean =
    appRouteDepth(target) > appRouteDepth(initial)

internal fun isDashboardStackRoute(route: AppRoute?): Boolean = when (route) {
    AppRoute.Dashboard,
    AppRoute.Events,
    AppRoute.Travel,
    AppRoute.Story,
    -> true
    AppRoute.Create,
    AppRoute.ConnectionError,
    AppRoute.LocalDataError,
    null,
    -> false
}

private fun appRouteTransition(
    initial: AppRoute?,
    target: AppRoute?,
    reducedMotion: Boolean,
): ContentTransform {
    if (initial == null || target == null || reducedMotion) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    if (appRouteDepth(initial) == appRouteDepth(target)) {
        return fadeIn(tween(180)) togetherWith fadeOut(tween(120))
    }
    val forward = isForwardAppRouteTransition(initial, target)
    val enter = slideInHorizontally(
        animationSpec = tween(AppRouteTransitionMillis, easing = AppRouteTransitionEasing),
        initialOffsetX = { width -> if (forward) width else -width / 4 },
    )
    val exit = slideOutHorizontally(
        animationSpec = tween(AppRouteTransitionMillis, easing = AppRouteTransitionEasing),
        targetOffsetX = { width -> if (forward) -width / 4 else width },
    )
    return enter togetherWith exit
}

private fun dashboardOverlayTransition(
    initial: AppRoute?,
    target: AppRoute?,
    reducedMotion: Boolean,
): ContentTransform = appRouteTransition(
    initial = initial ?: AppRoute.Dashboard,
    target = target ?: AppRoute.Dashboard,
    reducedMotion = reducedMotion,
)

internal fun contextualNavigationForAppRoute(
    route: AppRoute,
): ContextualNavigationAction? = when (route) {
    AppRoute.Travel -> ContextualNavigationAction.Back
    AppRoute.Story,
    AppRoute.Events,
    -> ContextualNavigationAction.Back
    AppRoute.Create,
    AppRoute.Dashboard,
    AppRoute.ConnectionError,
    AppRoute.LocalDataError,
    -> null
}

internal fun appRouteForOutcomeApplyConflict(): AppRoute = AppRoute.LocalDataError

internal fun appRouteFromValue(value: String?): AppRoute = when (value) {
    "auth" -> AppRoute.Create
    "create" -> AppRoute.Create
    "dashboard" -> AppRoute.Dashboard
    "travel" -> AppRoute.Travel
    else -> AppRoute.Create
}

internal fun debugExtraValue(value: String?, isDebugBuild: Boolean): String? =
    value.takeIf { isDebugBuild }

internal fun shouldUseProductionRoom(explicitRouteValue: String?): Boolean =
    explicitRouteValue == null

internal enum class FeatureAdapterMode { RealBackend, ExplicitDebugFixture }

internal fun featureAdapterMode(explicitRouteValue: String?): FeatureAdapterMode =
    if (explicitRouteValue == null) FeatureAdapterMode.RealBackend
    else FeatureAdapterMode.ExplicitDebugFixture

internal fun shouldEnqueueBackgroundSync(
    explicitDebugRoute: Boolean,
    destination: AccountStartupDestination,
): Boolean = !explicitDebugRoute && destination is AccountStartupDestination.Dashboard

internal val ForegroundRecoveryMinimumLifecycle = Lifecycle.State.STARTED

internal fun appRouteForAccountStartup(destination: AccountStartupDestination): AppRoute =
    when (destination) {
        is AccountStartupDestination.Create -> AppRoute.Create
        is AccountStartupDestination.Dashboard -> AppRoute.Dashboard
        AccountStartupDestination.Failure -> AppRoute.LocalDataError
    }

internal fun usesDarkNavigationBarIcons(route: AppRoute?): Boolean = route == AppRoute.Dashboard

internal fun navigationBarBackground(route: AppRoute?): Color =
    if (route == AppRoute.Dashboard) Color(0xFFBDBBB3) else Color.Black

class MainActivity : ComponentActivity() {
    private val incomingIntentRevision = MutableStateFlow(0)
    private var darkNavigationBarIcons = false

    private fun configureSystemBars(darkNavigationBarIcons: Boolean = this.darkNavigationBarIcons) {
        this.darkNavigationBarIcons = darkNavigationBarIcons
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightNavigationBars = darkNavigationBarIcons
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntentRevision.value += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureSystemBars()
        setContent {
            GigagochiTheme {
                val latestIntentRevision by incomingIntentRevision.collectAsState()
                val explicitRouteValue = remember(intent, BuildConfig.DEBUG) {
                    debugExtraValue(
                        intent.getStringExtra("gigagochi.route"),
                        BuildConfig.DEBUG,
                    )
                }
                val debugState = remember(intent, BuildConfig.DEBUG) {
                    CreateDebugState.fromRouteValue(
                        debugExtraValue(
                            intent.getStringExtra("gigagochi.create.state"),
                            BuildConfig.DEBUG,
                        ),
                    )
                }
                val dashboardDebugState = remember(intent, BuildConfig.DEBUG) {
                    DashboardDebugState.fromRouteValue(
                        debugExtraValue(
                            intent.getStringExtra("gigagochi.dashboard.state"),
                            BuildConfig.DEBUG,
                        ),
                    )
                }
                val travelDebugState = remember(intent, BuildConfig.DEBUG) {
                    TravelDebugState.fromRouteValue(
                        debugExtraValue(
                            intent.getStringExtra("gigagochi.travel.state"),
                            BuildConfig.DEBUG,
                        ),
                    )
                }
                var pendingStoryDeepLink by remember(intent) {
                    mutableStateOf(intent.getStringExtra("gigagochi.storyId"))
                }
                var pendingTravelDeepLink by remember(intent) {
                    mutableStateOf(intent.getStringExtra("gigagochi.travelRequestKey"))
                }
                var focusedTravelRequestKey by remember { mutableStateOf<String?>(null) }
                var route by remember {
                    mutableStateOf(explicitRouteValue?.let(::appRouteFromValue))
                }
                LaunchedEffect(route) {
                    configureSystemBars(usesDarkNavigationBarIcons(route))
                }
                val isExplicitDebugRoute = explicitRouteValue != null
                val usesRealFeatureAdapters = featureAdapterMode(explicitRouteValue) ==
                    FeatureAdapterMode.RealBackend
                val inMemorySession = remember { mutableStateOf<Session?>(null) }
                val activePet = remember { mutableStateOf<PetDashboardState?>(null) }
                val activeStartup = remember { mutableStateOf<AccountStartupDestination?>(null) }
                val activeStory = remember { mutableStateOf<LocalScheduledStory?>(null) }
                val scheduledStories = remember {
                    mutableStateOf<List<LocalScheduledStory>>(emptyList())
                }
                val travelVideoAssets = remember {
                    mutableStateOf<List<com.gigagochi.app.core.database.LocalTravelVideoAsset>>(
                        emptyList(),
                    )
                }
                var eventHistoryLastViewed by remember { mutableStateOf<Long?>(null) }
                var dashboardRecoveryRevision by remember { mutableIntStateOf(0) }
                var debugTravelDemo by remember { mutableStateOf(false) }
                var debugVisualMoodOverride by remember { mutableStateOf<String?>(null) }
                var debugDeadRevision by remember { mutableIntStateOf(0) }
                val sessionRepository = remember {
                    androidSessionRepository(applicationContext)
                }
                val authHeaderProvider = remember { InMemoryAuthHeaderProvider() }
                val database = remember(isExplicitDebugRoute) {
                    if (shouldUseProductionRoom(explicitRouteValue)) {
                        GigagochiDatabase.build(applicationContext)
                    } else null
                }
                DisposableEffect(database) {
                    onDispose { database?.close() }
                }
                val petRepository = remember(database) { database?.let(::PetLocalRepository) }
                val petLifecycle = remember(petRepository) {
                    petRepository?.let(::AccountPetLifecycle)
                }
                val scope = rememberCoroutineScope()
                val featureClient = remember(isExplicitDebugRoute, sessionRepository) {
                    if (!usesRealFeatureAdapters) null else AuthenticatedFeatureClient(
                        repository = sessionRepository,
                        refreshExchange = HttpSessionRefreshExchange(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            allowDebugLoopbackHttp = BuildConfig.DEBUG,
                        ),
                        transport = UrlConnectionFeatureHttpTransport(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            allowDebugLoopbackHttp = BuildConfig.DEBUG,
                        ),
                        headerProvider = authHeaderProvider,
                        onSessionInvalid = {
                            inMemorySession.value = null
                            authHeaderProvider.clear()
                            sessionRepository.clear()
                            route = null
                        },
                    )
                }
                val featureApi = remember(featureClient) {
                    featureClient?.let {
                        AndroidFeatureApi(
                            client = it,
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            allowDebugLoopbackHttp = BuildConfig.DEBUG,
                        )
                    }
                }

                LaunchedEffect(
                    petRepository,
                    inMemorySession.value?.accountId,
                    activePet.value?.petId,
                ) {
                    val repository = petRepository
                    val session = inMemorySession.value
                    val pet = activePet.value
                    if (repository == null || session == null || pet == null) {
                        scheduledStories.value = emptyList()
                        travelVideoAssets.value = emptyList()
                        eventHistoryLastViewed = null
                        return@LaunchedEffect
                    }
                    scheduledStories.value = emptyList()
                    travelVideoAssets.value = emptyList()
                    eventHistoryLastViewed = null
                    launch {
                        repository.observeScheduledStories(session.accountId, pet.petId).collect {
                            scheduledStories.value = it
                        }
                    }
                    launch {
                        repository.observeTravelVideoAssets(session.accountId, pet.petId).collect {
                            travelVideoAssets.value = it
                        }
                    }
                    launch {
                        repository.observeEventHistoryLastViewed(session.accountId, pet.petId)
                            .collect { eventHistoryLastViewed = it }
                    }
                }

                suspend fun routeLocalSession(session: Session) {
                    inMemorySession.value = session
                    authHeaderProvider.update(session)
                    when (val destination = petLifecycle?.startup(
                        session.accountId,
                        debugPreferredPetId(applicationContext),
                    )) {
                        is AccountStartupDestination.Dashboard -> {
                            val repository = requireNotNull(petRepository)
                            if (!ensureDebugFixtureStories(
                                    session.accountId,
                                    destination.pet,
                                    repository,
                                )
                            ) {
                                route = AppRoute.LocalDataError
                                return
                            }
                            activePet.value = destination.pet
                            activeStartup.value = destination
                            val storyId = pendingStoryDeepLink
                            val story = if (storyId != null) {
                                petRepository?.getScheduledStory(session.accountId, storyId)
                            } else null
                            val travelRequestKey = pendingTravelDeepLink
                            val travelAsset = if (travelRequestKey != null) {
                                petRepository?.getTravelVideoAsset(
                                    session.accountId,
                                    travelRequestKey,
                                )
                            } else null
                            if (storyId != null) {
                                pendingStoryDeepLink = null
                                activeStory.value = story
                            }
                            if (travelRequestKey != null) {
                                pendingTravelDeepLink = null
                                focusedTravelRequestKey = travelAsset
                                    ?.takeIf { it.petId == destination.pet.petId }
                                    ?.requestKey
                            }
                            if (
                                shouldEnqueueBackgroundSync(isExplicitDebugRoute, destination) &&
                                destination.pet.petId != debugFixturePet()?.petId
                            ) {
                                MvpSyncScheduler.enqueue(applicationContext)
                            }
                            route = when {
                                story != null && story.story.petId == destination.pet.petId ->
                                    AppRoute.Story
                                focusedTravelRequestKey != null -> AppRoute.Events
                                else -> appRouteForAccountStartup(destination)
                            }
                        }
                        is AccountStartupDestination.Create -> {
                            activeStartup.value = destination
                            if (destination.pending?.backendJobId != null) {
                                CreateSyncScheduler.enqueue(applicationContext)
                            }
                            route = appRouteForAccountStartup(destination)
                        }
                        AccountStartupDestination.Failure -> route =
                            appRouteForAccountStartup(destination)
                        null -> route = AppRoute.LocalDataError
                    }
                }
                LaunchedEffect(latestIntentRevision) {
                    if (latestIntentRevision == 0) return@LaunchedEffect
                    pendingStoryDeepLink = intent.getStringExtra("gigagochi.storyId")
                    pendingTravelDeepLink = intent.getStringExtra("gigagochi.travelRequestKey")
                    inMemorySession.value?.let { routeLocalSession(it) }
                }
                LaunchedEffect(route) {
                    if (route != null) return@LaunchedEffect
                    val outcome = LocalSessionBootstrapCoordinator(
                        sessionBootstrap = SessionBootstrapCoordinator(
                            repository = sessionRepository,
                            refreshExchange = HttpSessionRefreshExchange(
                                baseUrl = BuildConfig.BACKEND_BASE_URL,
                                allowDebugLoopbackHttp = BuildConfig.DEBUG,
                            ),
                        ),
                        sessionRepository = sessionRepository,
                        installationIdProvider = AndroidInstallationIdProvider(applicationContext),
                        guestSessionExchange = HttpGuestSessionExchange(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            allowDebugLoopbackHttp = BuildConfig.DEBUG,
                        ),
                    ).bootstrap()
                    when (outcome) {
                        is LocalSessionBootstrapOutcome.Ready -> {
                            routeLocalSession(outcome.session)
                        }
                        LocalSessionBootstrapOutcome.Unavailable -> {
                            inMemorySession.value = null
                            authHeaderProvider.clear()
                            route = AppRoute.ConnectionError
                        }
                    }
                }
                @Composable
                fun DashboardLayer(active: Boolean) {
                    val dashboardModifier = if (active) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { }
                    }
                    if (isExplicitDebugRoute) {
                        DashboardRoute(
                            debugState = dashboardDebugState,
                            onEvents = {
                                focusedTravelRequestKey = null
                                route = AppRoute.Events
                            },
                            mediaActive = active,
                            modifier = dashboardModifier,
                        )
                    } else {
                        val recovery = activeStartup.value as? AccountStartupDestination.Dashboard
                        val session = requireNotNull(inMemorySession.value)
                        val repository = requireNotNull(petRepository)
                        val api = requireNotNull(featureApi)
                        val completionNotifications = remember(session.accountId, repository) {
                            CompletionNotificationDispatcher(
                                notificationsAllowed = { notificationsAllowed(applicationContext) },
                                loadNotifications = repository::getUnnotifiedNotifications,
                                emitter = AndroidLocalNotificationEmitter(applicationContext),
                                markNotified = { ownerId, notification ->
                                    repository.markNotificationSent(
                                        ownerId,
                                        notification,
                                        System.currentTimeMillis(),
                                    )
                                },
                            )
                        }
                        RequestNotificationPermissionOnce(enabled = true) { granted ->
                            if (granted) scope.launch {
                                activePet.value?.petId?.let { petId ->
                                    completionNotifications.dispatch(session.accountId, petId)
                                }
                            }
                        }
                        val recoverySignal = remember(session.accountId) { ForegroundRecoverySignal() }
                        val mediaUrlPolicy = remember {
                            StaticMediaUrlPolicy(
                                BuildConfig.BACKEND_BASE_URL,
                                BuildConfig.DEBUG,
                            )
                        }
                        val outfitAdapter = remember(session.accountId, repository, api, recoverySignal) {
                            RealDashboardOutfitAdapter(
                                session.accountId,
                                repository,
                                repository,
                                repository,
                                api,
                                onJobAttached = {
                                    recoverySignal.request()
                                    scope.launch { routeLocalSession(session) }
                                },
                                onOutfitFailed = { requestKey ->
                                    AndroidLocalNotificationEmitter(applicationContext).emit(
                                        manualGenerationFailedNotification(
                                            ManualGenerationKind.Outfit,
                                            requestKey,
                                        ),
                                    )
                                },
                            )
                        }
                        val travelAdapter = remember(session.accountId, repository, api, recoverySignal) {
                            RealDashboardTravelAdapter(
                                session.accountId,
                                repository,
                                repository,
                                repository,
                                api,
                                onJobAttached = recoverySignal::request,
                                onTravelFailed = { requestKey ->
                                    AndroidLocalNotificationEmitter(applicationContext).emit(
                                        manualGenerationFailedNotification(
                                            ManualGenerationKind.Travel,
                                            requestKey,
                                        ),
                                    )
                                },
                            )
                        }
                        val debugDashboardOutfitAdapter = remember(outfitAdapter) {
                            debugOutfitAdapter(outfitAdapter)
                        }
                        val debugDashboardTravelAdapter = remember(travelAdapter) {
                            debugTravelAdapter(travelAdapter)
                        }
                        val pendingRecovery = remember(
                            session.accountId,
                            repository,
                            outfitAdapter,
                            travelAdapter,
                        ) {
                            val outcomeApplication = DashboardOutcomeApplicationCoordinator(
                                session.accountId,
                                repository,
                                repository,
                                repository,
                                onMediaReplaced = StaticMediaCache::evict,
                            )
                            ForegroundPendingRecoveryCoordinator(
                                session.accountId,
                                repository,
                                outfitAdapter,
                                travelAdapter,
                                recoverySignal,
                                outcomeApplication = outcomeApplication,
                                onOutcomeApplied = {
                                    routeLocalSession(session)
                                    completionNotifications.dispatch(
                                        session.accountId,
                                        requireNotNull(activePet.value).petId,
                                    )
                                },
                                onOutcomeConflict = {
                                    route = appRouteForOutcomeApplyConflict()
                                },
                                onTerminalFailure = {
                                    routeLocalSession(session)
                                    dashboardRecoveryRevision += 1
                                },
                            )
                        }
                        val createBackgroundMedia = remember(session.accountId, repository, api) {
                            CreateBackgroundMediaCoordinator(
                                session.accountId,
                                repository,
                                repository,
                                api,
                                onMediaReady = { pet -> activePet.value = pet },
                                onGenerationFailed = { requestKey ->
                                    AndroidLocalNotificationEmitter(applicationContext).emit(
                                        manualGenerationFailedNotification(
                                            ManualGenerationKind.Create,
                                            requestKey,
                                        ),
                                    )
                                },
                            )
                        }
                        val lifecycleOwner = LocalLifecycleOwner.current
                        val storyApi = remember(api) { debugScheduledStoryService(api) }
                        val scheduledStoryCoordinator = remember(session.accountId, repository, storyApi) {
                            ScheduledStoryCoordinator(session.accountId, repository, storyApi)
                        }
                        LaunchedEffect(
                            scheduledStoryCoordinator,
                            activePet.value?.petId,
                            lifecycleOwner,
                        ) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(ForegroundRecoveryMinimumLifecycle) {
                                scheduledStoryCoordinator.checkDue(requireNotNull(activePet.value))
                                completionNotifications.dispatch(
                                    session.accountId,
                                    requireNotNull(activePet.value).petId,
                                )
                            }
                        }
                        LaunchedEffect(pendingRecovery, activePet.value?.petId, lifecycleOwner) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(ForegroundRecoveryMinimumLifecycle) {
                                pendingRecovery.watch(requireNotNull(activePet.value).petId)
                            }
                        }
                        LaunchedEffect(createBackgroundMedia, activePet.value?.petId, lifecycleOwner) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(ForegroundRecoveryMinimumLifecycle) {
                                createBackgroundMedia.recover(requireNotNull(activePet.value).petId)
                            }
                        }
                        DashboardRoute(
                            debugState = dashboardDebugState,
                            recoveryRevision = dashboardRecoveryRevision,
                            initialPet = requireNotNull(activePet.value).let { pet ->
                                debugVisualMoodOverride?.let(pet::copyMood) ?: pet
                            },
                            initialPendingOutfit = recovery?.pendingOutfit?.toUi(),
                            initialPendingTravel = recovery?.pendingTravel?.toUi(),
                            initialFirstSession = recovery?.firstSession,
                            firstSessionOwnerId = session.accountId,
                            firstSessionStore = repository,
                            onFirstSessionTravel = { route = AppRoute.Travel },
                            onFirstSessionChanged = { firstSession ->
                                val current = activeStartup.value as? AccountStartupDestination.Dashboard
                                if (current != null) activeStartup.value = current.copy(
                                    firstSession = firstSession,
                                )
                            },
                            mediaUrlPolicy = mediaUrlPolicy,
                            chatAdapter = remember(api, session.accountId, repository, scope) {
                                debugChatAdapter(
                                    RealDashboardChatAdapter(
                                        api,
                                        session.accountId,
                                        repository,
                                        scope,
                                    ),
                                )
                            },
                            feedAdapter = remember { DeterministicLocalDashboardFeedAdapter() },
                            outfitAdapter = debugDashboardOutfitAdapter,
                            travelAdapter = debugDashboardTravelAdapter,
                            durableOperations = DashboardDurableOperations(
                                ownerId = requireNotNull(inMemorySession.value).accountId,
                                store = requireNotNull(petRepository),
                                outfitAdapter = debugDashboardOutfitAdapter,
                                travelAdapter = debugDashboardTravelAdapter,
                            ),
                            requestKeyFactory = ::durableDashboardRequestKey,
                            unansweredEventCount = eventHistoryUiState(
                                scheduledStories.value,
                                travelVideoAssets.value,
                            ).badgeCount(eventHistoryLastViewed),
                            onEvents = {
                                focusedTravelRequestKey = null
                                route = AppRoute.Events
                            },
                            mediaActive = active,
                            modifier = dashboardModifier,
                            onPetChanged = { pet ->
                                val session = inMemorySession.value
                                val lifecycle = petLifecycle
                                if (session != null && lifecycle != null) {
                                    lifecycle.save(session.accountId, pet).also { saved ->
                                        if (saved) activePet.value = pet
                                    }
                                } else {
                                    false
                                }
                            },
                        )
                    }
                }

                @Composable
                fun DashboardOverlay(overlayRoute: AppRoute?) {
                    when (overlayRoute) {
                        null,
                        AppRoute.Dashboard,
                        -> Box(Modifier.fillMaxSize())
                        AppRoute.Events -> {
                        val mediaUrlPolicy = remember {
                            StaticMediaUrlPolicy(BuildConfig.BACKEND_BASE_URL, BuildConfig.DEBUG)
                        }
                        val latestEventTimestamp = eventHistoryUiState(
                            scheduledStories.value,
                            travelVideoAssets.value,
                        ).latestTimestampMillis
                        LaunchedEffect(latestEventTimestamp, inMemorySession.value?.accountId) {
                            val timestamp = latestEventTimestamp ?: return@LaunchedEffect
                            val session = inMemorySession.value ?: return@LaunchedEffect
                            val pet = activePet.value ?: return@LaunchedEffect
                            petRepository?.markEventHistoryViewed(
                                session.accountId,
                                pet.petId,
                                timestamp,
                            )
                        }
                        EventHistoryScreen(
                            stories = scheduledStories.value,
                            travelVideos = travelVideoAssets.value,
                            mediaUrlPolicy = mediaUrlPolicy,
                            travelVideoSharer = remember(mediaUrlPolicy) {
                                AndroidTravelVideoSharer(this@MainActivity, mediaUrlPolicy)
                            },
                            initialFocusTravelRequestKey = focusedTravelRequestKey,
                            onHelp = { story ->
                                activeStory.value = story
                                route = AppRoute.Story
                            },
                            onBack = {
                                focusedTravelRequestKey = null
                                route = AppRoute.Dashboard
                            },
                        )
                        }
                        AppRoute.Travel -> {
                        val session = inMemorySession.value
                        val repository = petRepository
                        val pet = activePet.value
                        val recovery = activeStartup.value as? AccountStartupDestination.Dashboard
                        val firstSession = recovery?.firstSession
                        val isOnboardingBat = !debugTravelDemo && !isExplicitDebugRoute &&
                            session != null && repository != null && pet != null &&
                            firstSession?.stage == FirstSessionStage.AwaitingTravel
                        val batStory = when {
                            debugTravelDemo && pet != null -> onboardingBatStory(pet.petId)
                            isOnboardingBat -> onboardingBatStory(pet!!.petId)
                            else -> null
                        }
                        val batReceipt = recovery?.storyReceipts?.firstOrNull {
                            it.travelId == batStory?.travelId && it.partKey == "choice-result"
                        }
                        TravelEntryRoute(
                            debugState = travelDebugState,
                            initialPet = pet?.let { TravelEntryPet(it.petId, it.name, it.experience) }
                                ?: TravelEntryPet("debug-test-pet", "Без имени"),
                            initialStory = batStory,
                            initialStoryResult = if (batStory != null && batReceipt != null) {
                                onboardingBatStoryResult(batStory, batReceipt.receiptKey)
                            } else null,
                            storyChoiceAdapter = if (isOnboardingBat) {
                                DurableOnboardingBatChoiceAdapter(
                                    session!!.accountId, pet!!.petId, repository!!,
                                )
                            } else com.gigagochi.app.feature.travel.FakeOnboardingTravelStoryAdapter(),
                            resultConsumptionAdapter = if (isOnboardingBat) {
                                DurableOnboardingBatFinishAdapter(
                                    session!!.accountId, pet!!.petId, repository!!,
                                )
                            } else com.gigagochi.app.feature.travel.ImmediateTravelResultConsumptionAdapter,
                            requestKeyFactory = ::durableDashboardRequestKey,
                            navigationAction = requireNotNull(
                                contextualNavigationForAppRoute(AppRoute.Travel),
                            ),
                            onNavigateDashboard = {
                                debugTravelDemo = false
                                route = AppRoute.Dashboard
                                if (session != null && !isExplicitDebugRoute) {
                                    scope.launch { routeLocalSession(session) }
                                }
                            },
                        )
                        }
                        AppRoute.Story -> {
                        val session = requireNotNull(inMemorySession.value)
                        val repository = requireNotNull(petRepository)
                        val api = requireNotNull(featureApi)
                        val story = activeStory.value
                        val mediaUrlPolicy = remember {
                            StaticMediaUrlPolicy(BuildConfig.BACKEND_BASE_URL, BuildConfig.DEBUG)
                        }
                        if (story != null) ScheduledStoryRoute(
                            pet = requireNotNull(activePet.value),
                            initialStory = story,
                            coordinator = remember(session.accountId, repository, api) {
                                ScheduledStoryCoordinator(
                                    session.accountId,
                                    repository,
                                    debugScheduledStoryService(api),
                                    StoryReceiptCoordinator(
                                        session.accountId,
                                        requireNotNull(activePet.value).petId,
                                        repository,
                                    ),
                                )
                            },
                            mediaUrlPolicy = mediaUrlPolicy,
                            navigationAction = requireNotNull(
                                contextualNavigationForAppRoute(AppRoute.Story),
                            ),
                            onNavigateDashboard = {
                                route = AppRoute.Dashboard
                                scope.launch { routeLocalSession(session) }
                            },
                        )
                        }
                        AppRoute.Create,
                        AppRoute.ConnectionError,
                        AppRoute.LocalDataError,
                        -> Unit
                    }
                }

                @Composable
                fun RootRouteContent(rootRoute: AppRoute?) {
                    when (rootRoute) {
                        null -> Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color(0xFF071219)),
                        )
                        AppRoute.Create -> {
                            RequestNotificationPermissionOnce(enabled = !isExplicitDebugRoute)
                            CreatePetRoute(
                                debugState = debugState,
                                initialStateOverride = if (isExplicitDebugRoute) null else {
                                    val pending =
                                        (activeStartup.value as? AccountStartupDestination.Create)
                                            ?.pending
                                    val repository = petRepository
                                    val session = inMemorySession.value
                                    if (pending != null && repository != null && session != null) {
                                        CreatePendingCoordinator(session.accountId, repository)
                                            .restore(pending)
                                    } else null
                                },
                                generationAdapter = debugGenerationAdapter(
                                    if (isExplicitDebugRoute) {
                                        com.gigagochi.app.feature.create.FakePetGenerationAdapter()
                                    } else {
                                        val session = requireNotNull(inMemorySession.value)
                                        val repository = requireNotNull(petRepository)
                                        remember(session.accountId, repository, featureApi) {
                                            RealPetGenerationAdapter(
                                                session.accountId,
                                                repository,
                                                repository,
                                                requireNotNull(featureApi),
                                                onJobAttached = {
                                                    CreateSyncScheduler.enqueue(applicationContext)
                                                },
                                                onGenerationFailed = { requestKey ->
                                                    AndroidLocalNotificationEmitter(applicationContext).emit(
                                                        manualGenerationFailedNotification(
                                                            ManualGenerationKind.Create,
                                                            requestKey,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    },
                                ),
                                finalizationCoordinator = if (isExplicitDebugRoute) {
                                    null
                                } else {
                                    val session = inMemorySession.value
                                    val lifecycle = petLifecycle
                                    if (session != null && lifecycle != null) {
                                        CreateFinalizationCoordinator(
                                            session.accountId,
                                            lifecycle,
                                            requireNotNull(petRepository),
                                            requireNotNull(petRepository),
                                        )
                                    } else null
                                },
                                pendingCoordinator = if (isExplicitDebugRoute) null else {
                                    val session = inMemorySession.value
                                    val repository = petRepository
                                    if (session != null && repository != null) {
                                        CreatePendingCoordinator(session.accountId, repository)
                                    } else null
                                },
                                onPetReadyInBackground = if (isExplicitDebugRoute) {
                                    { true }
                                } else {
                                    { pending ->
                                        AndroidLocalNotificationEmitter(applicationContext).emit(
                                            petReadyNotification(pending.requestKey),
                                        )
                                    }
                                },
                                onPetPersisted = { activePet.value = it },
                                onNavigateDashboard = {
                                    val session = inMemorySession.value
                                    if (session != null && !isExplicitDebugRoute) {
                                        scope.launch { routeLocalSession(session) }
                                    } else route = AppRoute.Dashboard
                                },
                            )
                        }
                        AppRoute.ConnectionError -> StartupConnectionErrorRoute(
                            onRetry = { route = null },
                        )
                        AppRoute.LocalDataError -> LocalDataStartupErrorRoute(
                            onRetry = {
                                val session = inMemorySession.value
                                if (session != null) {
                                    scope.launch { routeLocalSession(session) }
                                }
                            },
                        )
                        AppRoute.Dashboard,
                        AppRoute.Events,
                        AppRoute.Travel,
                        AppRoute.Story,
                        -> Unit
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(navigationBarBackground(route))
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                        .background(Color.Black),
                ) {
                    val reducedMotion = rememberTravelReducedMotionPreference()
                    AnimatedContent(
                        targetState = isDashboardStackRoute(route),
                        transitionSpec = {
                            if (reducedMotion) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "app-root-transition",
                    ) { dashboardStackActive ->
                        if (dashboardStackActive) {
                            Box(Modifier.fillMaxSize()) {
                                DashboardLayer(active = route == AppRoute.Dashboard)
                                AnimatedContent(
                                    targetState = route.takeUnless { it == AppRoute.Dashboard },
                                    transitionSpec = {
                                        dashboardOverlayTransition(
                                            initialState,
                                            targetState,
                                            reducedMotion,
                                        )
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                    label = "dashboard-overlay-transition",
                                ) { overlayRoute ->
                                    DashboardOverlay(overlayRoute)
                                }
                            }
                        } else {
                            AnimatedContent(
                                targetState = route,
                                transitionSpec = {
                                    appRouteTransition(initialState, targetState, reducedMotion)
                                },
                                modifier = Modifier.fillMaxSize(),
                                label = "root-route-transition",
                            ) { rootRoute ->
                                RootRouteContent(rootRoute)
                            }
                        }
                    }
                }
                DebugMenuHost(
                    DebugMenuBindings(
                        routeName = route?.name ?: "Startup",
                        pet = activePet.value,
                        firstSession = (activeStartup.value as? AccountStartupDestination.Dashboard)
                            ?.firstSession,
                        onboardingActive = (activeStartup.value as? AccountStartupDestination.Dashboard)
                            ?.firstSession
                            ?.stage
                            ?.let { it != FirstSessionStage.Completed } == true,
                        savedPetAvailable = debugSavedPetId(applicationContext) != null,
                        fixtureActive = activePet.value?.petId == debugFixturePet()?.petId,
                        visualMoodOverride = debugVisualMoodOverride,
                        isPetDead = debugDeadRevision.let {
                            activePet.value?.petId?.let { petId ->
                                debugDeadPetId(applicationContext) == petId
                            } == true
                        },
                        onToggleOnboarding = {
                            val session = inMemorySession.value
                            val pet = activePet.value
                            val repository = petRepository
                            if (session != null && pet != null && repository != null) scope.launch {
                                val active = (activeStartup.value as? AccountStartupDestination.Dashboard)
                                    ?.firstSession
                                    ?.stage
                                    ?.let { it != FirstSessionStage.Completed } == true
                                if (active) {
                                    repository.disableFirstSession(session.accountId, pet.petId)
                                } else {
                                    repository.restartFirstSession(session.accountId, pet.petId)
                                }
                                routeLocalSession(session)
                                dashboardRecoveryRevision += 1
                            }
                        },
                        onOpenFixture = {
                            val session = inMemorySession.value
                            val repository = petRepository
                            val fixture = debugFixturePet()
                            if (session != null && repository != null && fixture != null) scope.launch {
                                setDebugFixtureSelection(
                                    applicationContext,
                                    fixture.petId,
                                    activePet.value?.petId,
                                )
                                repository.replacePetSnapshot(
                                    OwnedPetSnapshot(
                                        session.accountId,
                                        fixture,
                                        System.currentTimeMillis(),
                                    ),
                                )
                                routeLocalSession(session)
                            }
                        },
                        onRestoreSavedPet = {
                            val session = inMemorySession.value
                            val repository = petRepository
                            val fixtureId = debugPreferredPetId(applicationContext)
                            if (session != null && repository != null) scope.launch {
                                clearDebugFixtureSelection(applicationContext)
                                if (fixtureId != null) {
                                    repository.deletePetSnapshot(session.accountId, fixtureId)
                                }
                                routeLocalSession(session)
                            }
                        },
                        onOpenTravelDemo = {
                            debugTravelDemo = true
                            route = AppRoute.Travel
                        },
                        onResetStats = {
                            val session = inMemorySession.value
                            val pet = activePet.value
                            val lifecycle = petLifecycle
                            if (session != null && pet != null && lifecycle != null) scope.launch {
                                val reset = pet.copy(
                                    hunger = 0,
                                    happiness = 0,
                                    energy = 0,
                                    mood = "sad",
                                )
                                if (lifecycle.save(session.accountId, reset)) {
                                    activePet.value = reset
                                    routeLocalSession(session)
                                }
                            }
                        },
                        onVisualMoodOverride = { mood ->
                            debugVisualMoodOverride = mood
                            dashboardRecoveryRevision += 1
                            if (activePet.value != null) route = AppRoute.Dashboard
                        },
                        onKillPet = {
                            val session = inMemorySession.value
                            val pet = activePet.value
                            val lifecycle = petLifecycle
                            if (session != null && pet != null && lifecycle != null) scope.launch {
                                val dead = pet.copy(hunger = 0, happiness = 0, energy = 0, mood = "sad")
                                if (lifecycle.save(session.accountId, dead)) {
                                    activePet.value = dead
                                    setDebugDeadPetId(applicationContext, pet.petId)
                                    debugDeadRevision += 1
                                }
                            }
                        },
                        onRevivePet = {
                            val session = inMemorySession.value
                            val pet = activePet.value
                            val lifecycle = petLifecycle
                            if (session != null && pet != null && lifecycle != null) scope.launch {
                                val revived = pet.copy(hunger = 80, happiness = 80, energy = 80, mood = "idle")
                                if (lifecycle.save(session.accountId, revived)) {
                                    activePet.value = revived
                                    setDebugDeadPetId(applicationContext, null)
                                    debugDeadRevision += 1
                                    routeLocalSession(session)
                                }
                            }
                        },
                        onCreateNewPet = {
                            debugTravelDemo = false
                            route = AppRoute.Create
                        },
                        onSendPush = {
                            scheduleDebugPush(applicationContext)
                        },
                    ),
                    )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureSystemBars()
    }
}

private fun PetDashboardState.copyMood(mood: String): PetDashboardState = copy(mood = mood)

internal val LocalDataRetryMinimumTouchTarget = 48.dp

@androidx.compose.runtime.Composable
internal fun StartupConnectionErrorRoute(onRetry: () -> Unit) {
    StartupErrorRoute(
        message = "Не удалось подключиться к серверу. Проверьте интернет.",
        onRetry = onRetry,
    )
}

@androidx.compose.runtime.Composable
internal fun LocalDataStartupErrorRoute(onRetry: () -> Unit) {
    StartupErrorRoute(
        message = "Не удалось открыть данные питомца.",
        onRetry = onRetry,
    )
}

@androidx.compose.runtime.Composable
private fun StartupErrorRoute(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF071219)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                color = Color.White,
                fontSize = 16.sp,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = LocalDataRetryMinimumTouchTarget)
                    .semantics { role = Role.Button },
            ) {
                Text("Повторить")
            }
        }
    }
}
