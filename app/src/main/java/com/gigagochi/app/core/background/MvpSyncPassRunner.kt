package com.gigagochi.app.core.background

import com.gigagochi.app.core.database.LocalCompletionNotification
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.Session
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import kotlinx.coroutines.CancellationException

enum class MvpSyncPassResult { Success, Retry }

fun interface LocalNotificationEmitter {
    fun emit(notification: LocalCompletionNotification): Boolean
}

class MvpSyncPassRunner(
    private val sessionProvider: suspend () -> Session?,
    private val petProvider: suspend (ownerId: String) -> PetDashboardState?,
    private val featureSync: suspend (Session, PetDashboardState) -> FeatureFailure?,
    private val notificationsAllowed: () -> Boolean,
    private val loadNotifications: suspend (ownerId: String, petId: String) ->
        List<LocalCompletionNotification>,
    private val emitter: LocalNotificationEmitter,
    private val markNotified: suspend (ownerId: String, LocalCompletionNotification) -> Unit,
) {
    suspend fun runOnce(): MvpSyncPassResult {
        return try {
            val session = sessionProvider() ?: return MvpSyncPassResult.Success
            val pet = petProvider(session.accountId) ?: return MvpSyncPassResult.Success
            val failure = featureSync(session, pet)
            if (failure?.kind == FeatureFailureKind.SessionInvalid) {
                return MvpSyncPassResult.Success
            }
            CompletionNotificationDispatcher(
                notificationsAllowed,
                loadNotifications,
                emitter,
                markNotified,
            ).dispatch(session.accountId, pet.petId)
            if (failure?.kind in RetryableSyncFailures) {
                MvpSyncPassResult.Retry
            } else {
                MvpSyncPassResult.Success
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MvpSyncPassResult.Retry
        }
    }
}

class CompletionNotificationDispatcher(
    private val notificationsAllowed: () -> Boolean,
    private val loadNotifications: suspend (ownerId: String, petId: String) ->
        List<LocalCompletionNotification>,
    private val emitter: LocalNotificationEmitter,
    private val markNotified: suspend (ownerId: String, LocalCompletionNotification) -> Unit,
) {
    suspend fun dispatch(ownerId: String, petId: String) {
        if (!notificationsAllowed()) return
        loadNotifications(ownerId, petId).forEach { notification ->
            if (emitter.emit(notification)) markNotified(ownerId, notification)
        }
    }
}

private val RetryableSyncFailures = setOf(
    FeatureFailureKind.Network,
    FeatureFailureKind.Server,
    FeatureFailureKind.RefreshUnavailable,
    FeatureFailureKind.Storage,
    FeatureFailureKind.InProgress,
)
