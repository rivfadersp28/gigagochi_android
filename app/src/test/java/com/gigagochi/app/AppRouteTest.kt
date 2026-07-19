package com.gigagochi.app


import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.model.PetDashboardState
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.lifecycle.Lifecycle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType

class AppRouteTest {
    @Test
    fun routeDepthDefinesForwardAndBackSharedAxisDirection() {
        assertEquals(true, isForwardAppRouteTransition(AppRoute.Dashboard, AppRoute.Events))
        assertEquals(true, isForwardAppRouteTransition(AppRoute.Events, AppRoute.Story))
        assertEquals(false, isForwardAppRouteTransition(AppRoute.Events, AppRoute.Dashboard))
        assertEquals(false, isForwardAppRouteTransition(AppRoute.Story, AppRoute.Dashboard))
        assertEquals(0, appRouteDepth(AppRoute.Dashboard))
        assertEquals(1, appRouteDepth(AppRoute.Events))
        assertEquals(2, appRouteDepth(AppRoute.Story))
    }

    @Test
    fun dashboardChildrenShareOnePersistentRootStack() {
        assertEquals(true, isDashboardStackRoute(AppRoute.Dashboard))
        assertEquals(true, isDashboardStackRoute(AppRoute.Events))
        assertEquals(true, isDashboardStackRoute(AppRoute.Travel))
        assertEquals(true, isDashboardStackRoute(AppRoute.Story))
        assertEquals(false, isDashboardStackRoute(AppRoute.Create))
        assertEquals(false, isDashboardStackRoute(AppRoute.ConnectionError))
        assertEquals(false, isDashboardStackRoute(null))
    }

    @Test
    fun contextualNavigationMatchesRouteSemanticsWithoutDecorativeForward() {
        assertEquals(null, contextualNavigationForAppRoute(AppRoute.Create))
        assertEquals(null, contextualNavigationForAppRoute(AppRoute.Dashboard))
        assertEquals(
            ContextualNavigationAction.Back,
            contextualNavigationForAppRoute(AppRoute.Events),
        )
        assertEquals(null, contextualNavigationForAppRoute(AppRoute.ConnectionError))
        assertEquals(null, contextualNavigationForAppRoute(AppRoute.LocalDataError))
        assertEquals(
            ContextualNavigationAction.Back,
            contextualNavigationForAppRoute(AppRoute.Travel),
        )
        assertEquals(
            ContextualNavigationAction.Back,
            contextualNavigationForAppRoute(AppRoute.Story),
        )
    }

    @Test
    fun productionStartupNeverRoutesToFourPartInteractiveTravel() {
        val destination = AccountStartupDestination.Dashboard(
            pet = PetDashboardState(
                "pet", "assets", "лис", "Тото", "baby", "Малыш", "idle",
                0, 100, 100, 100, "Привет",
            ),
            pendingOutfit = null,
            pendingTravel = null,
            storyReceipts = emptyList(),
        )
        assertEquals(AppRoute.Dashboard, appRouteForAccountStartup(destination))
    }
    @Test
    fun outcomeApplyConflictUsesRecoverableLocalDataRoute() {
        assertEquals(AppRoute.LocalDataError, appRouteForOutcomeApplyConflict())
    }

    @Test
    fun foregroundRecoveryIsStrictlyLifecycleGatedAtStarted() {
        assertEquals(Lifecycle.State.STARTED, ForegroundRecoveryMinimumLifecycle)
    }
    @Test
    fun routeExtraKeepsTravelSeparateFromDashboardInlineTravel() {
        assertEquals(AppRoute.Create, appRouteFromValue("auth"))
        assertEquals(AppRoute.Create, appRouteFromValue("create"))
        assertEquals(AppRoute.Travel, appRouteFromValue("travel"))
        assertEquals(AppRoute.Dashboard, appRouteFromValue("dashboard"))
        assertEquals(AppRoute.Create, appRouteFromValue(null))
        assertEquals(AppRoute.Create, appRouteFromValue("unknown"))
    }

    @Test
    fun allDebugExtrasAreIgnoredOutsideDebugBuilds() {
        val debugExtraValues = listOf(
            "dashboard",
            "credential-pending",
            "loader",
            "chat-thinking",
            "story-question-video",
        )
        debugExtraValues.forEach { value ->
            assertEquals(value, debugExtraValue(value, true))
            assertEquals(null, debugExtraValue(value, false))
        }
        assertEquals(null, debugExtraValue(null, true))
    }

    @Test
    fun localDataFailureHasDistinctRecoverableRouteInsteadOfAuth() {
        assertEquals(
            AppRoute.LocalDataError,
            appRouteForAccountStartup(AccountStartupDestination.Failure),
        )
    }

    @Test
    fun explicitDebugRoutesNeverUseProductionRoom() {
        assertEquals(true, shouldUseProductionRoom(null))
        assertEquals(false, shouldUseProductionRoom("create"))
        assertEquals(false, shouldUseProductionRoom("dashboard"))
        assertEquals(false, shouldUseProductionRoom("travel"))
    }

    @Test
    fun defaultUsesBackendFeaturesWithoutUserLoginAndEveryExplicitRouteUsesFixtures() {
        assertEquals(FeatureAdapterMode.RealBackend, featureAdapterMode(null))
        listOf("auth", "create", "dashboard", "travel").forEach {
            assertEquals(FeatureAdapterMode.ExplicitDebugFixture, featureAdapterMode(it))
        }
    }

    @Test
    fun backgroundSyncEnqueuesOnlyForProductionDashboard() {
        val dashboard = AccountStartupDestination.Dashboard(
            PetDashboardState(
                "pet", "asset", "лис", "Тото", "baby", "Малыш", "idle",
                0, 100, 100, 100, "",
            ),
            null,
            null,
            emptyList(),
        )
        assertEquals(true, shouldEnqueueBackgroundSync(false, dashboard))
        assertEquals(false, shouldEnqueueBackgroundSync(true, dashboard))
        assertEquals(
            false,
            shouldEnqueueBackgroundSync(false, AccountStartupDestination.Create()),
        )
        assertEquals(15L, com.gigagochi.app.core.background.MvpSyncIntervalMinutes)
        assertEquals(1, com.gigagochi.app.core.background.MvpWorkerMaxPollAttempts)
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            com.gigagochi.app.core.background.MvpSyncExistingPolicy,
        )
        assertEquals(
            NetworkType.CONNECTED,
            com.gigagochi.app.core.background.MvpSyncNetworkConstraint,
        )
    }

}
