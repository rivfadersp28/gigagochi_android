package com.gigagochi.app.core.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.auth.HttpSessionRefreshExchange
import com.gigagochi.app.core.auth.InMemoryAuthHeaderProvider
import com.gigagochi.app.core.auth.SessionBootstrapCoordinator
import com.gigagochi.app.core.auth.SessionBootstrapOutcome
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.network.AndroidFeatureApi
import com.gigagochi.app.core.network.AuthenticatedFeatureClient
import com.gigagochi.app.core.network.UrlConnectionFeatureHttpTransport
import com.gigagochi.app.feature.create.DurableCreateRecoveryCoordinator
import com.gigagochi.app.feature.create.DurableCreateRecoveryResult
import java.util.concurrent.TimeUnit

internal const val CreateSyncUniqueWorkName = "gigagochi-create-sync"
internal const val CreateSyncBackoffSeconds = 10L
internal val CreateSyncExistingPolicy = ExistingWorkPolicy.KEEP

object CreateSyncScheduler {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<CreateSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                CreateSyncBackoffSeconds,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CreateSyncUniqueWorkName,
            CreateSyncExistingPolicy,
            request,
        )
    }
}

class CreateSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionRepository = androidSessionRepository(applicationContext)
        val refresh = HttpSessionRefreshExchange(BuildConfig.BACKEND_BASE_URL, BuildConfig.DEBUG)
        val session = when (
            val outcome = SessionBootstrapCoordinator(sessionRepository, refresh).bootstrap()
        ) {
            is SessionBootstrapOutcome.Authenticated -> outcome.session
            SessionBootstrapOutcome.Unauthenticated -> return Result.retry()
        }
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
        val api = AndroidFeatureApi(client, BuildConfig.BACKEND_BASE_URL, BuildConfig.DEBUG)
        val database = GigagochiDatabase.build(applicationContext)
        return try {
            val repository = PetLocalRepository(database)
            when (
                DurableCreateRecoveryCoordinator(
                    session.accountId,
                    repository,
                    repository,
                    api,
                    AndroidLocalNotificationEmitter(applicationContext),
                ).recoverOnce()
            ) {
                DurableCreateRecoveryResult.Complete,
                DurableCreateRecoveryResult.Terminal,
                -> Result.success()
                DurableCreateRecoveryResult.Retry -> Result.retry()
            }
        } finally {
            database.close()
        }
    }
}
