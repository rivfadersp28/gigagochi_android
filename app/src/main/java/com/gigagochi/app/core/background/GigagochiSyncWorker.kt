@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app.core.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.SessionBootstrapOutcome
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.database.AccountPetLifecycle
import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.network.AndroidFeatureApi
import com.gigagochi.app.core.network.AuthenticatedFeatureClient
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.network.UrlConnectionFeatureHttpTransport
import com.gigagochi.app.feature.dashboard.DashboardOutcomeApplicationCoordinator
import com.gigagochi.app.feature.dashboard.DailyProactiveCoordinator
import com.gigagochi.app.feature.dashboard.ForegroundPendingRecoveryCoordinator
import com.gigagochi.app.feature.dashboard.ForegroundRecoverySignal
import com.gigagochi.app.feature.dashboard.RealDashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardTravelAdapter
import com.gigagochi.app.feature.travel.ScheduledStoryCoordinator
import com.gigagochi.app.debugmenu.debugScheduledStoryService
import java.util.concurrent.TimeUnit

const val MvpSyncIntervalMinutes = 15L
internal const val MvpWorkerMaxPollAttempts = 1
internal const val MvpSyncUniqueWorkName = "gigagochi-mvp-sync"
internal const val CompletionSyncUniqueWorkName = "gigagochi-story-sync"
internal const val CompletionSyncBackoffSeconds = 10L
internal val MvpSyncExistingPolicy = ExistingPeriodicWorkPolicy.KEEP
internal val CompletionSyncExistingPolicy = ExistingWorkPolicy.KEEP
internal val MvpSyncNetworkConstraint = NetworkType.CONNECTED

object MvpSyncScheduler {
    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<GigagochiSyncWorker>(
            MvpSyncIntervalMinutes,
            TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(MvpSyncNetworkConstraint)
                .build(),
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MvpSyncUniqueWorkName,
            MvpSyncExistingPolicy,
            request,
        )
    }
}

object CompletionSyncScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<GigagochiSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(MvpSyncNetworkConstraint)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                CompletionSyncBackoffSeconds,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CompletionSyncUniqueWorkName,
            CompletionSyncExistingPolicy,
            request,
        )
    }
}

class GigagochiSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionRepository = androidSessionRepository(applicationContext)
        val refresh = HttpSessionRefreshExchange(
            BuildConfig.BACKEND_BASE_URL,
            BuildConfig.DEBUG,
        )
        val database = GigagochiDatabase.build(applicationContext)
        return try {
            val repository = PetLocalRepository(database)
            val lifecycle = AccountPetLifecycle(repository)
            val runner = MvpSyncPassRunner(
                sessionProvider = {
                    when (
                        val outcome = SessionBootstrapCoordinator(
                            sessionRepository,
                            refresh,
                        ).bootstrap()
                    ) {
                        is SessionBootstrapOutcome.Authenticated -> outcome.session
                        is SessionBootstrapOutcome.Offline -> null
                        SessionBootstrapOutcome.Unauthenticated -> null
                    }
                },
                petProvider = { ownerId ->
                    (lifecycle.startup(ownerId) as? AccountStartupDestination.Dashboard)?.pet
                },
                featureSync = { session, pet ->
                    runFeaturePass(session, pet, repository, sessionRepository, refresh)
                },
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
            when (runner.runOnce()) {
                MvpSyncPassResult.Success -> Result.success()
                MvpSyncPassResult.Retry -> Result.retry()
            }
        } finally {
            database.close()
        }
    }

    private suspend fun runFeaturePass(
        session: Session,
        pet: PetDashboardState,
        repository: PetLocalRepository,
        sessionRepository: com.gigagochi.app.core.auth.SessionRepository,
        refresh: HttpSessionRefreshExchange,
    ): FeatureFailure? {
        val headers = InMemoryAuthHeaderProvider().apply { update(session) }
        val client = AuthenticatedFeatureClient(
            repository = sessionRepository,
            refreshExchange = refresh,
            transport = UrlConnectionFeatureHttpTransport(
                BuildConfig.BACKEND_BASE_URL,
                BuildConfig.DEBUG,
            ),
            headerProvider = headers,
            onSessionInvalid = headers::clear,
        )
        val api = AndroidFeatureApi(
            client,
            BuildConfig.BACKEND_BASE_URL,
            BuildConfig.DEBUG,
        )
        val storyFailure = when (
            val story = ScheduledStoryCoordinator(
                session.accountId,
                repository,
                debugScheduledStoryService(api),
            ).checkDue(pet)
        ) {
            com.gigagochi.app.feature.travel.ScheduledStoryDueResult.Pending ->
                FeatureFailure(FeatureFailureKind.InProgress, "STORY_GENERATING")
            is com.gigagochi.app.feature.travel.ScheduledStoryDueResult.Failure -> story.failure
            else -> null
        }
        val outfit = RealDashboardOutfitAdapter(
            session.accountId,
            repository,
            repository,
            repository,
            api,
            onOutfitFailed = { petId, requestKey ->
                repository.enqueueNotification(
                    session.accountId,
                    petId,
                    manualGenerationFailedNotification(
                        ManualGenerationKind.Outfit, requestKey,
                    ),
                )
            },
        )
        val travel = RealDashboardTravelAdapter(
            session.accountId,
            repository,
            repository,
            repository,
            api,
            onTravelFailed = { petId, requestKey ->
                repository.enqueueNotification(
                    session.accountId,
                    petId,
                    manualGenerationFailedNotification(
                        ManualGenerationKind.Travel, requestKey,
                    ),
                )
            },
        )
        ForegroundPendingRecoveryCoordinator(
            session.accountId,
            repository,
            outfit,
            travel,
            ForegroundRecoverySignal(),
            maxPollAttempts = MvpWorkerMaxPollAttempts,
            outcomeApplication = DashboardOutcomeApplicationCoordinator(
                session.accountId,
                repository,
                repository,
                repository,
                onMediaReplaced = StaticMediaCache::evict,
            ),
        ).recoverForeground(pet.petId)
        DailyProactiveCoordinator(session.accountId, repository, api).generateIfDue(pet)
        val recovery = repository.loadOwnerRecovery(session.accountId)
        recovery.pendingOutfits.filter {
            it.petId == pet.petId && it.backendState == PendingBackendState.Failed &&
                it.backendErrorCode == "GENERATION_FAILED"
        }.forEach {
            check(
                repository.enqueueNotification(
                    session.accountId,
                    pet.petId,
                    manualGenerationFailedNotification(ManualGenerationKind.Outfit, it.requestKey),
                ),
            )
        }
        recovery.pendingTravels.filter {
            it.petId == pet.petId && it.backendState == PendingBackendState.Failed &&
                it.backendErrorCode == "GENERATION_FAILED"
        }.forEach {
            check(
                repository.enqueueNotification(
                    session.accountId,
                    pet.petId,
                    manualGenerationFailedNotification(ManualGenerationKind.Travel, it.requestKey),
                ),
            )
        }
        val manualCompletionPending = recovery.hasPendingManualCompletion(pet.petId)
        return when {
            storyFailure?.kind == FeatureFailureKind.SessionInvalid -> storyFailure
            manualCompletionPending -> FeatureFailure(
                FeatureFailureKind.InProgress,
                "MANUAL_GENERATION_PENDING",
            )
            else -> storyFailure
        }
    }
}

internal fun OwnerRecoveryData.hasPendingManualCompletion(petId: String): Boolean =
    pendingOutfits.any { it.petId == petId && it.awaitsBackgroundCompletion() } ||
        pendingTravels.any { it.petId == petId && it.awaitsBackgroundCompletion() }

private fun LocalPendingOutfit.awaitsBackgroundCompletion(): Boolean =
    awaitsBackgroundCompletion(backendJobId, backendState)

private fun LocalPendingTravelVideo.awaitsBackgroundCompletion(): Boolean =
    awaitsBackgroundCompletion(backendJobId, backendState)

private fun awaitsBackgroundCompletion(
    backendJobId: String?,
    backendState: PendingBackendState,
): Boolean = backendState == PendingBackendState.Ready ||
    (backendJobId != null && backendState != PendingBackendState.Failed) ||
    (backendJobId == null && backendState in setOf(
        PendingBackendState.Pending,
        PendingBackendState.Retryable,
    ))
