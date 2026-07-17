package com.gigagochi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.SessionBootstrapOutcome
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.network.AndroidFeatureApi
import com.gigagochi.app.core.network.AuthenticatedFeatureClient
import com.gigagochi.app.core.network.UrlConnectionFeatureHttpTransport
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.auth.AuthDebugState
import com.gigagochi.app.feature.auth.GoogleAuthRoute
import com.gigagochi.app.feature.create.CreateDebugState
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
import com.gigagochi.app.feature.travel.TravelDebugState
import com.gigagochi.app.feature.travel.TravelEntryRoute
import kotlinx.coroutines.launch

internal enum class AppRoute { Auth, Create, Dashboard, Travel, LocalDataError }

internal fun appRouteForOutcomeApplyConflict(): AppRoute = AppRoute.LocalDataError

internal fun appRouteFromValue(value: String?): AppRoute = when (value) {
    "auth" -> AppRoute.Auth
    "create" -> AppRoute.Create
    "dashboard" -> AppRoute.Dashboard
    "travel" -> AppRoute.Travel
    else -> AppRoute.Auth
}

internal fun shouldUseProductionRoom(explicitRouteValue: String?): Boolean =
    explicitRouteValue == null

internal enum class FeatureAdapterMode { RealAuthenticated, ExplicitDebugFixture }

internal fun featureAdapterMode(explicitRouteValue: String?): FeatureAdapterMode =
    if (explicitRouteValue == null) FeatureAdapterMode.RealAuthenticated
    else FeatureAdapterMode.ExplicitDebugFixture

internal val ForegroundRecoveryMinimumLifecycle = Lifecycle.State.STARTED

internal fun appRouteForAccountStartup(destination: AccountStartupDestination): AppRoute =
    when (destination) {
        is AccountStartupDestination.Create -> AppRoute.Create
        is AccountStartupDestination.Dashboard -> AppRoute.Dashboard
        AccountStartupDestination.Failure -> AppRoute.LocalDataError
    }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            GigagochiTheme {
                val explicitRouteValue = remember(intent) {
                    intent.getStringExtra("gigagochi.route")
                }
                val authDebugState = remember(intent) {
                    AuthDebugState.fromRouteValue(intent.getStringExtra("gigagochi.auth.state"))
                }
                val debugState = remember(intent) {
                    CreateDebugState.fromRouteValue(intent.getStringExtra("gigagochi.create.state"))
                }
                val dashboardDebugState = remember(intent) {
                    DashboardDebugState.fromRouteValue(intent.getStringExtra("gigagochi.dashboard.state"))
                }
                val travelDebugState = remember(intent) {
                    TravelDebugState.fromRouteValue(intent.getStringExtra("gigagochi.travel.state"))
                }
                var route by remember {
                    mutableStateOf(explicitRouteValue?.let(::appRouteFromValue))
                }
                val isExplicitDebugRoute = explicitRouteValue != null
                val usesRealFeatureAdapters = featureAdapterMode(explicitRouteValue) ==
                    FeatureAdapterMode.RealAuthenticated
                val inMemorySession = remember { mutableStateOf<Session?>(null) }
                val activePet = remember { mutableStateOf<PetDashboardState?>(null) }
                val activeStartup = remember { mutableStateOf<AccountStartupDestination?>(null) }
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
                            route = AppRoute.Auth
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

                suspend fun routeAuthenticatedSession(session: Session) {
                    inMemorySession.value = session
                    authHeaderProvider.update(session)
                    when (val destination = petLifecycle?.startup(session.accountId)) {
                        is AccountStartupDestination.Dashboard -> {
                            activePet.value = destination.pet
                            activeStartup.value = destination
                            route = appRouteForAccountStartup(destination)
                        }
                        is AccountStartupDestination.Create -> {
                            activeStartup.value = destination
                            route = appRouteForAccountStartup(destination)
                        }
                        AccountStartupDestination.Failure -> route =
                            appRouteForAccountStartup(destination)
                        null -> route = AppRoute.LocalDataError
                    }
                }
                LaunchedEffect(route) {
                    if (route != null) return@LaunchedEffect
                    val outcome = SessionBootstrapCoordinator(
                        repository = sessionRepository,
                        refreshExchange = HttpSessionRefreshExchange(
                            baseUrl = BuildConfig.BACKEND_BASE_URL,
                            allowDebugLoopbackHttp = BuildConfig.DEBUG,
                        ),
                    ).bootstrap()
                    when (outcome) {
                        is SessionBootstrapOutcome.Authenticated -> {
                            routeAuthenticatedSession(outcome.session)
                        }
                        SessionBootstrapOutcome.Unauthenticated -> {
                            inMemorySession.value = null
                            authHeaderProvider.clear()
                            route = AppRoute.Auth
                        }
                    }
                }
                when (route) {
                    null -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF071219)),
                    )
                    AppRoute.Auth -> GoogleAuthRoute(
                        debugState = authDebugState,
                        sessionRepositoryOverride = sessionRepository,
                        onAuthenticated = { session ->
                            scope.launch { routeAuthenticatedSession(session) }
                        },
                    )
                    AppRoute.Create -> CreatePetRoute(
                        debugState = debugState,
                        initialStateOverride = if (isExplicitDebugRoute) null else {
                            val pending = (activeStartup.value as? AccountStartupDestination.Create)
                                ?.pending
                            val repository = petRepository
                            val session = inMemorySession.value
                            if (pending != null && repository != null && session != null) {
                                CreatePendingCoordinator(session.accountId, repository).restore(pending)
                            } else null
                        },
                        generationAdapter = if (isExplicitDebugRoute) {
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
                                )
                            }
                        },
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
                                )
                            } else {
                                null
                            }
                        },
                        pendingCoordinator = if (isExplicitDebugRoute) null else {
                            val session = inMemorySession.value
                            val repository = petRepository
                            if (session != null && repository != null) {
                                CreatePendingCoordinator(session.accountId, repository)
                            } else null
                        },
                        onPetPersisted = { activePet.value = it },
                        onNavigateDashboard = {
                            val session = inMemorySession.value
                            if (session != null && !isExplicitDebugRoute) {
                                scope.launch { routeAuthenticatedSession(session) }
                            } else route = AppRoute.Dashboard
                        },
                    )
                    AppRoute.Dashboard -> if (isExplicitDebugRoute) {
                        DashboardRoute(debugState = dashboardDebugState)
                    } else {
                        val recovery = activeStartup.value as? AccountStartupDestination.Dashboard
                        val session = requireNotNull(inMemorySession.value)
                        val repository = requireNotNull(petRepository)
                        val api = requireNotNull(featureApi)
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
                                onJobAttached = recoverySignal::request,
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
                            )
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
                            )
                            ForegroundPendingRecoveryCoordinator(
                                session.accountId,
                                repository,
                                outfitAdapter,
                                travelAdapter,
                                recoverySignal,
                                outcomeApplication = outcomeApplication,
                                onOutcomeApplied = {
                                    routeAuthenticatedSession(session)
                                },
                                onOutcomeConflict = {
                                    route = appRouteForOutcomeApplyConflict()
                                },
                            )
                        }
                        val lifecycleOwner = LocalLifecycleOwner.current
                        LaunchedEffect(pendingRecovery, activePet.value?.petId, lifecycleOwner) {
                            lifecycleOwner.lifecycle.repeatOnLifecycle(ForegroundRecoveryMinimumLifecycle) {
                                pendingRecovery.watch(requireNotNull(activePet.value).petId)
                            }
                        }
                        DashboardRoute(
                            debugState = dashboardDebugState,
                            initialPet = requireNotNull(activePet.value),
                            initialPendingOutfit = recovery?.pendingOutfit?.toUi(),
                            initialPendingTravel = recovery?.pendingTravel?.toUi(),
                            travelPresentation = recovery?.travelPresentation,
                            mediaUrlPolicy = mediaUrlPolicy,
                            chatAdapter = remember(api) { RealDashboardChatAdapter(api) },
                            feedAdapter = remember { DeterministicLocalDashboardFeedAdapter() },
                            outfitAdapter = outfitAdapter,
                            travelAdapter = travelAdapter,
                            durableOperations = DashboardDurableOperations(
                                ownerId = requireNotNull(inMemorySession.value).accountId,
                                store = requireNotNull(petRepository),
                                outfitAdapter = outfitAdapter,
                                travelAdapter = travelAdapter,
                            ),
                            requestKeyFactory = ::durableDashboardRequestKey,
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
                    AppRoute.Travel -> {
                        TravelEntryRoute(
                            debugState = travelDebugState,
                            onNavigateDashboard = { route = AppRoute.Dashboard },
                        )
                    }
                    AppRoute.LocalDataError -> LocalDataStartupErrorRoute(
                        onRetry = {
                            val session = inMemorySession.value
                            if (session != null) {
                                scope.launch { routeAuthenticatedSession(session) }
                            }
                        },
                    )
                }
            }
        }
    }
}

internal val LocalDataRetryMinimumTouchTarget = 48.dp

@androidx.compose.runtime.Composable
internal fun LocalDataStartupErrorRoute(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF071219)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Не удалось открыть данные питомца.",
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
