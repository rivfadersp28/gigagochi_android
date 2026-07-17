package com.gigagochi.app


import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.model.PetDashboardState
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.lifecycle.Lifecycle

class AppRouteTest {
    @Test
    fun productionStartupNeverRoutesToFourPartInteractiveTravel() {
        val destination = AccountStartupDestination.Dashboard(
            pet = PetDashboardState(
                "pet", "assets", "лис", "Тото", "baby", "Малыш", "idle",
                0, 100, 100, 100, "Привет", false,
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
        assertEquals(AppRoute.Auth, appRouteFromValue("auth"))
        assertEquals(AppRoute.Create, appRouteFromValue("create"))
        assertEquals(AppRoute.Travel, appRouteFromValue("travel"))
        assertEquals(AppRoute.Dashboard, appRouteFromValue("dashboard"))
        assertEquals(AppRoute.Auth, appRouteFromValue(null))
        assertEquals(AppRoute.Auth, appRouteFromValue("unknown"))
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
    fun defaultUsesAuthenticatedRealFeaturesAndEveryExplicitRouteUsesFixtures() {
        assertEquals(FeatureAdapterMode.RealAuthenticated, featureAdapterMode(null))
        listOf("auth", "create", "dashboard", "travel").forEach {
            assertEquals(FeatureAdapterMode.ExplicitDebugFixture, featureAdapterMode(it))
        }
    }

}
