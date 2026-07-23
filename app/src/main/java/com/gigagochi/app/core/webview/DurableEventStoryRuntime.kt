package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.feature.events.EventHistoryUiState
import com.gigagochi.app.feature.events.eventHistoryUiState
import com.gigagochi.app.feature.travel.InteractiveTravelStory
import com.gigagochi.app.feature.travel.InteractiveTravelStoryResult
import com.gigagochi.app.feature.travel.OnboardingBatCorrectChoice
import com.gigagochi.app.feature.travel.ScheduledStoryChoiceResult
import com.gigagochi.app.feature.travel.ScheduledStoryChoicePreparationResult
import com.gigagochi.app.feature.travel.ScheduledStoryDueResult
import com.gigagochi.app.feature.travel.TravelStoryChoiceFailureMessage
import com.gigagochi.app.feature.travel.onboardingBatStory
import com.gigagochi.app.feature.travel.onboardingBatStoryResult
import com.gigagochi.app.feature.travel.toTravelState
import kotlinx.coroutines.CancellationException

/**
 * Room-authoritative input for the UI-neutral Events/Story runtime.
 *
 * Owner identity deliberately remains inside the gateway and is never part of a projected state.
 */
internal data class EventStoryRecovery(
    val pet: PetDashboardState,
    val firstSession: LocalFirstSession?,
    val scheduledStories: List<LocalScheduledStory>,
    val travelVideoAssets: List<LocalTravelVideoAsset>,
    val storyReceipts: List<InteractiveStoryReceipt>,
    val lastViewedAtEpochMillis: Long?,
)

internal interface EventStoryGateway {
    suspend fun load(petId: String): EventStoryRecovery?

    suspend fun markViewed(petId: String, viewedAtEpochMillis: Long): Boolean

    suspend fun checkDue(pet: PetDashboardState): ScheduledStoryDueResult

    suspend fun prepareScheduledStoryChoice(
        storyId: String,
        choice: String,
        proposedRequestKey: String,
    ): ScheduledStoryChoicePreparationResult

    suspend fun executePreparedScheduledStoryChoice(
        storyId: String,
        requestKey: String,
        choice: String,
    ): ScheduledStoryChoiceResult

    suspend fun reconcileScheduledStory(storyId: String): ScheduledStoryChoiceResult

    suspend fun commitOnboardingBatChoice(
        petId: String,
        requestKey: String,
    ): FirstSessionMutationResult

    suspend fun finishOnboardingBat(
        petId: String,
        actionKey: String,
    ): FirstSessionMutationResult
}

internal enum class EventStoryFailureKind {
    Missing,
    WrongStage,
    Conflict,
    Retryable,
    LocalData,
}

internal sealed interface EventStoryResult<out T> {
    data class Success<T>(val value: T) : EventStoryResult<T>
    data class Failure(
        val kind: EventStoryFailureKind,
        val featureFailure: FeatureFailure? = null,
    ) : EventStoryResult<Nothing>
}

internal data class EventStorySnapshot(
    val pet: PetDashboardState,
    val firstSession: LocalFirstSession?,
    val history: EventHistoryUiState,
    val lastViewedAtEpochMillis: Long?,
    val badgeCount: Int,
) {
    val latestEventAtEpochMillis: Long? = history.latestTimestampMillis
}

internal enum class DueStoryRefreshStatus { NotDue, Pending, Saved }

internal data class DueStoryRefresh(
    val status: DueStoryRefreshStatus,
    val snapshot: EventStorySnapshot,
)

internal enum class DurableStoryKind { Scheduled, OnboardingBat }

internal enum class DurableStoryPhase { Question, ChoicePending, Retryable, Result }

internal data class DurableStorySnapshot(
    val kind: DurableStoryKind,
    val story: InteractiveTravelStory,
    val phase: DurableStoryPhase,
    val durableRequestKey: String? = null,
    val pendingChoice: String? = null,
    val result: InteractiveTravelStoryResult? = null,
    val error: String? = null,
) {
    init {
        require(
            when (phase) {
                DurableStoryPhase.ChoicePending,
                DurableStoryPhase.Retryable,
                -> !pendingChoice.isNullOrBlank() && !durableRequestKey.isNullOrBlank()
                DurableStoryPhase.Question,
                DurableStoryPhase.Result,
                -> pendingChoice == null
            },
        ) { "pendingChoice and durableRequestKey must exist only for an executing/retryable story" }
    }
}

internal data class OpenedEventStory(
    val app: EventStorySnapshot,
    val story: DurableStorySnapshot,
)

internal data class ScheduledStoryExecution(
    val storyId: String,
    val requestKey: String,
    val choice: String,
)

internal data class PreparedEventStoryChoice(
    val opened: OpenedEventStory,
    val execution: ScheduledStoryExecution?,
)

/**
 * UI-neutral, restart-safe Events and one-part Story state machine.
 *
 * It never trusts route-local state for choice identity or rewards. Every mutation is followed by a
 * fresh gateway load, and a pending scheduled choice always retries the request key won in Room.
 */
internal class DurableEventStoryRuntime(
    private val petId: String,
    private val gateway: EventStoryGateway,
) {
    suspend fun snapshot(): EventStoryResult<EventStorySnapshot> = protected {
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        success(recovery.toSnapshot())
    }

    suspend fun refreshDueStory(): EventStoryResult<DueStoryRefresh> = protected {
        val before = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        when (val due = gateway.checkDue(before.pet)) {
            ScheduledStoryDueResult.NotDue -> snapshot().withDueStatus(DueStoryRefreshStatus.NotDue)
            ScheduledStoryDueResult.Pending -> snapshot().withDueStatus(DueStoryRefreshStatus.Pending)
            is ScheduledStoryDueResult.Saved -> snapshot().withDueStatus(DueStoryRefreshStatus.Saved)
            is ScheduledStoryDueResult.Failure -> failure(due.failure.toRuntimeFailure(), due.failure)
        }
    }

    suspend fun markViewed(
        requestedViewedAtEpochMillis: Long,
    ): EventStoryResult<EventStorySnapshot> = protected {
        if (requestedViewedAtEpochMillis < 0L) {
            return@protected failure(EventStoryFailureKind.Conflict)
        }
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        val latest = recovery.toSnapshot().latestEventAtEpochMillis
            ?: return@protected success(recovery.toSnapshot())
        // The Web document may acknowledge only content from the snapshot it actually rendered.
        // Rejecting a future watermark prevents a compromised/stale document hiding later events.
        if (requestedViewedAtEpochMillis > latest) {
            return@protected failure(EventStoryFailureKind.Conflict)
        }
        if (!gateway.markViewed(petId, requestedViewedAtEpochMillis)) {
            return@protected failure(EventStoryFailureKind.LocalData)
        }
        val updated = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        success(updated.toSnapshot())
    }

    suspend fun openStory(storyId: String): EventStoryResult<OpenedEventStory> = protected {
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        if (storyId == onboardingBatStory(petId).travelId) {
            return@protected openOnboardingBat(recovery)
        }
        val local = recovery.scheduledStories.singleOrNull {
            it.story.petId == petId && it.story.storyId == storyId
        } ?: return@protected failure(EventStoryFailureKind.Missing)
        if (local.story.selectedChoice != null) {
            when (val reconciled = gateway.reconcileScheduledStory(storyId)) {
                is ScheduledStoryChoiceResult.Failure -> {
                    return@protected failure(
                        reconciled.failure.toRuntimeFailure(),
                        reconciled.failure,
                    )
                }
                is ScheduledStoryChoiceResult.Saved -> Unit
            }
        }
        loadOpenedScheduledStory(storyId)
    }

    suspend fun chooseStory(
        storyId: String,
        choice: String,
        proposedRequestKey: String,
    ): EventStoryResult<OpenedEventStory> = protected {
        when (val prepared = prepareStoryChoice(storyId, choice, proposedRequestKey)) {
            is EventStoryResult.Failure -> prepared
            is EventStoryResult.Success -> prepared.value.execution?.let {
                executePreparedStoryChoice(it)
            } ?: success(prepared.value.opened)
        }
    }

    /** Claims the durable winner and returns before scheduled-story network work starts. */
    suspend fun prepareStoryChoice(
        storyId: String,
        choice: String,
        proposedRequestKey: String,
    ): EventStoryResult<PreparedEventStoryChoice> = protected {
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        if (storyId == onboardingBatStory(petId).travelId) {
            return@protected chooseOnboardingBat(
                recovery,
                choice.trim(),
                proposedRequestKey,
            ).mapPreparedWithoutExecution()
        }
        val local = recovery.scheduledStories.singleOrNull {
            it.story.petId == petId && it.story.storyId == storyId
        } ?: return@protected failure(EventStoryFailureKind.Missing)
        val normalizedChoice = choice.trim()
        if (normalizedChoice !in local.story.choices) {
            return@protected failure(EventStoryFailureKind.Conflict)
        }
        when (
            val prepared = gateway.prepareScheduledStoryChoice(
                storyId,
                normalizedChoice,
                proposedRequestKey,
            )
        ) {
            is ScheduledStoryChoicePreparationResult.Failure ->
                return@protected failure(prepared.failure.toRuntimeFailure(), prepared.failure)
            is ScheduledStoryChoicePreparationResult.Prepared -> {
                val claimed = gateway.load(petId)
                    ?: return@protected failure(EventStoryFailureKind.Missing)
                val durable = claimed.scheduledStories.singleOrNull {
                    it.story.petId == petId && it.story.storyId == storyId
                } ?: return@protected failure(EventStoryFailureKind.Missing)
                if (
                    durable.choiceRequestKey != prepared.requestKey ||
                    (durable.story.selectedChoice ?: durable.pendingChoice) != prepared.choice
                ) {
                    return@protected failure(EventStoryFailureKind.Conflict)
                }
                return@protected success(
                    PreparedEventStoryChoice(
                        opened = OpenedEventStory(
                            claimed.toSnapshot(),
                            durable.toChoicePendingStory(claimed.pet, prepared.requestKey, prepared.choice),
                        ),
                        execution = ScheduledStoryExecution(
                            storyId = storyId,
                            requestKey = prepared.requestKey,
                            choice = prepared.choice,
                        ),
                    ),
                )
            }
        }
    }

    suspend fun retryStory(storyId: String): EventStoryResult<OpenedEventStory> = protected {
        when (val prepared = prepareStoryRetry(storyId)) {
            is EventStoryResult.Failure -> prepared
            is EventStoryResult.Success -> prepared.value.execution?.let {
                executePreparedStoryChoice(it)
            } ?: success(prepared.value.opened)
        }
    }

    /** Restores the exact Room winner for a retry without claiming a new identity. */
    suspend fun prepareStoryRetry(
        storyId: String,
    ): EventStoryResult<PreparedEventStoryChoice> = protected {
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        if (storyId == onboardingBatStory(petId).travelId) {
            val receipt = recovery.onboardingBatReceipt()
                ?: return@protected failure(EventStoryFailureKind.WrongStage)
            return@protected success(
                PreparedEventStoryChoice(
                    opened = OpenedEventStory(
                        recovery.toSnapshot(),
                        recovery.toOnboardingBatStory(receipt),
                    ),
                    execution = null,
                ),
            )
        }
        val local = recovery.scheduledStories.singleOrNull {
            it.story.petId == petId && it.story.storyId == storyId
        } ?: return@protected failure(EventStoryFailureKind.Missing)
        if (local.story.selectedChoice != null) {
            return@protected openStory(storyId).mapPreparedWithoutExecution()
        }
        val requestKey = local.choiceRequestKey
            ?: return@protected failure(EventStoryFailureKind.WrongStage)
        val choice = local.pendingChoice
            ?: return@protected failure(EventStoryFailureKind.WrongStage)
        success(
            PreparedEventStoryChoice(
                opened = OpenedEventStory(
                    recovery.toSnapshot(),
                    local.toChoicePendingStory(recovery.pet, requestKey, choice),
                ),
                execution = ScheduledStoryExecution(storyId, requestKey, choice),
            ),
        )
    }

    /** Runs backend/reconciliation for an already claimed winner; it never creates a new claim. */
    suspend fun executePreparedStoryChoice(
        execution: ScheduledStoryExecution,
    ): EventStoryResult<OpenedEventStory> = protected {
        when (
            val chosen = gateway.executePreparedScheduledStoryChoice(
                storyId = execution.storyId,
                requestKey = execution.requestKey,
                choice = execution.choice,
            )
        ) {
            is ScheduledStoryChoiceResult.Saved -> loadOpenedScheduledStory(execution.storyId)
            is ScheduledStoryChoiceResult.Failure -> {
                val afterFailure = gateway.load(petId)
                    ?: return@protected failure(EventStoryFailureKind.Missing)
                val durable = afterFailure.scheduledStories.singleOrNull {
                    it.story.petId == petId && it.story.storyId == execution.storyId
                }
                if (
                    durable?.story?.selectedChoice == null &&
                    durable?.choiceRequestKey == execution.requestKey &&
                    durable.pendingChoice == execution.choice
                ) {
                    success(
                        OpenedEventStory(
                            afterFailure.toSnapshot(),
                            durable.toDurableStory(afterFailure.pet),
                        ),
                    )
                } else {
                    failure(chosen.failure.toRuntimeFailure(), chosen.failure)
                }
            }
        }
    }

    suspend fun finishStory(storyId: String): EventStoryResult<EventStorySnapshot> = protected {
        val recovery = gateway.load(petId)
            ?: return@protected failure(EventStoryFailureKind.Missing)
        if (storyId == onboardingBatStory(petId).travelId) {
            if (recovery.onboardingBatReceipt() == null) {
                return@protected failure(EventStoryFailureKind.WrongStage)
            }
            when (
                gateway.finishOnboardingBat(
                    petId,
                    onboardingBatFinishActionKey(storyId),
                )
            ) {
                is FirstSessionMutationResult.Applied,
                is FirstSessionMutationResult.AlreadyApplied,
                -> Unit
                FirstSessionMutationResult.Missing ->
                    return@protected failure(EventStoryFailureKind.Missing)
                FirstSessionMutationResult.WrongStage ->
                    return@protected failure(EventStoryFailureKind.WrongStage)
            }
            return@protected snapshot()
        }
        val opened = openStory(storyId)
        when (opened) {
            is EventStoryResult.Failure -> opened
            is EventStoryResult.Success -> if (
                opened.value.story.phase == DurableStoryPhase.Result
            ) {
                success(opened.value.app)
            } else {
                failure(EventStoryFailureKind.WrongStage)
            }
        }
    }

    private suspend fun loadOpenedScheduledStory(
        storyId: String,
    ): EventStoryResult<OpenedEventStory> {
        val recovery = gateway.load(petId)
            ?: return failure(EventStoryFailureKind.Missing)
        val local = recovery.scheduledStories.singleOrNull {
            it.story.petId == petId && it.story.storyId == storyId
        } ?: return failure(EventStoryFailureKind.Missing)
        return success(
            OpenedEventStory(
                recovery.toSnapshot(),
                local.toDurableStory(recovery.pet),
            ),
        )
    }

    private fun openOnboardingBat(
        recovery: EventStoryRecovery,
    ): EventStoryResult<OpenedEventStory> {
        if (recovery.firstSession?.stage !in OnboardingBatEntryStages) {
            return failure(EventStoryFailureKind.WrongStage)
        }
        return success(
            OpenedEventStory(
                recovery.toSnapshot(),
                recovery.toOnboardingBatStory(recovery.onboardingBatReceipt()),
            ),
        )
    }

    private suspend fun chooseOnboardingBat(
        recovery: EventStoryRecovery,
        choice: String,
        proposedRequestKey: String,
    ): EventStoryResult<OpenedEventStory> {
        if (
            recovery.firstSession?.stage !in OnboardingBatEntryStages ||
            choice != OnboardingBatCorrectChoice
        ) {
            return failure(EventStoryFailureKind.WrongStage)
        }
        when (gateway.commitOnboardingBatChoice(petId, proposedRequestKey)) {
            is FirstSessionMutationResult.Applied,
            is FirstSessionMutationResult.AlreadyApplied,
            -> Unit
            FirstSessionMutationResult.Missing ->
                return failure(EventStoryFailureKind.Missing)
            FirstSessionMutationResult.WrongStage ->
                return failure(EventStoryFailureKind.WrongStage)
        }
        val committed = gateway.load(petId)
            ?: return failure(EventStoryFailureKind.Missing)
        val durableReceipt = committed.onboardingBatReceipt()
            ?: return failure(EventStoryFailureKind.LocalData)
        return success(
            OpenedEventStory(
                committed.toSnapshot(),
                committed.toOnboardingBatStory(durableReceipt),
            ),
        )
    }

    private suspend inline fun <T> protected(
        crossinline block: suspend () -> EventStoryResult<T>,
    ): EventStoryResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        failure(EventStoryFailureKind.LocalData)
    }
}

private fun EventStoryRecovery.toSnapshot(): EventStorySnapshot {
    val history = eventHistoryUiState(
        stories = scheduledStories.filter { it.story.petId == pet.petId },
        travelVideos = travelVideoAssets.filter { it.petId == pet.petId },
    )
    return EventStorySnapshot(
        pet = pet,
        firstSession = firstSession?.takeIf { it.petId == pet.petId },
        history = history,
        lastViewedAtEpochMillis = lastViewedAtEpochMillis,
        badgeCount = history.badgeCount(lastViewedAtEpochMillis),
    )
}

private fun LocalScheduledStory.toDurableStory(
    pet: PetDashboardState,
): DurableStorySnapshot {
    val requestKey = choiceRequestKey
    val travelState = story.toTravelState(
        pet = pet,
        resultRequestKey = requestKey.takeIf { story.selectedChoice != null },
    )
    val projectedStory = requireNotNull(travelState.story)
    return when {
        story.selectedChoice != null -> DurableStorySnapshot(
            kind = DurableStoryKind.Scheduled,
            story = projectedStory,
            phase = DurableStoryPhase.Result,
            durableRequestKey = requireNotNull(requestKey),
            result = requireNotNull(travelState.storyResult),
        )
        requestKey != null && pendingChoice != null -> DurableStorySnapshot(
            kind = DurableStoryKind.Scheduled,
            story = projectedStory,
            phase = DurableStoryPhase.Retryable,
            durableRequestKey = requestKey,
            pendingChoice = pendingChoice,
            error = TravelStoryChoiceFailureMessage,
        )
        else -> DurableStorySnapshot(
            kind = DurableStoryKind.Scheduled,
            story = projectedStory,
            phase = DurableStoryPhase.Question,
        )
    }
}

private fun LocalScheduledStory.toChoicePendingStory(
    pet: PetDashboardState,
    requestKey: String,
    choice: String,
): DurableStorySnapshot {
    require(choiceRequestKey == requestKey)
    require((story.selectedChoice ?: pendingChoice) == choice)
    val projected = toDurableStory(pet)
    return projected.copy(
        phase = DurableStoryPhase.ChoicePending,
        durableRequestKey = requestKey,
        pendingChoice = choice,
        result = null,
        error = null,
    )
}

private fun EventStoryRecovery.toOnboardingBatStory(
    receipt: InteractiveStoryReceipt?,
): DurableStorySnapshot {
    val story = onboardingBatStory(pet.petId)
    return if (receipt == null) {
        DurableStorySnapshot(
            kind = DurableStoryKind.OnboardingBat,
            story = story,
            phase = DurableStoryPhase.Question,
        )
    } else {
        DurableStorySnapshot(
            kind = DurableStoryKind.OnboardingBat,
            story = story,
            phase = DurableStoryPhase.Result,
            durableRequestKey = receipt.receiptKey,
            result = onboardingBatStoryResult(story, receipt.receiptKey),
        )
    }
}

private fun EventStoryRecovery.onboardingBatReceipt(): InteractiveStoryReceipt? {
    val travelId = onboardingBatStory(pet.petId).travelId
    return storyReceipts.singleOrNull {
        it.petId == pet.petId &&
            it.travelId == travelId &&
            it.partKey == "choice-result"
    }
}

private fun FeatureFailure.toRuntimeFailure(): EventStoryFailureKind = when (kind) {
    FeatureFailureKind.NotFound -> EventStoryFailureKind.Missing
    FeatureFailureKind.Conflict,
    FeatureFailureKind.BadRequest,
    FeatureFailureKind.Protocol,
    -> EventStoryFailureKind.Conflict
    FeatureFailureKind.Network,
    FeatureFailureKind.RateLimited,
    FeatureFailureKind.InProgress,
    FeatureFailureKind.OutcomeUnknown,
    FeatureFailureKind.Server,
    FeatureFailureKind.RefreshUnavailable,
    -> EventStoryFailureKind.Retryable
    FeatureFailureKind.Storage,
    FeatureFailureKind.SessionInvalid,
    -> EventStoryFailureKind.LocalData
}

private fun onboardingBatFinishActionKey(storyId: String): String = "finish-$storyId".take(160)

private val OnboardingBatEntryStages = setOf(
    FirstSessionStage.AwaitingTravel,
    FirstSessionStage.ConfirmingTravel,
)

private fun <T> success(value: T): EventStoryResult<T> = EventStoryResult.Success(value)

private fun EventStoryResult<OpenedEventStory>.mapPreparedWithoutExecution():
    EventStoryResult<PreparedEventStoryChoice> = when (this) {
    is EventStoryResult.Failure -> this
    is EventStoryResult.Success -> success(PreparedEventStoryChoice(value, execution = null))
}

private fun EventStoryResult<EventStorySnapshot>.withDueStatus(
    status: DueStoryRefreshStatus,
): EventStoryResult<DueStoryRefresh> = when (this) {
    is EventStoryResult.Failure -> this
    is EventStoryResult.Success -> success(DueStoryRefresh(status, value))
}

private fun failure(
    kind: EventStoryFailureKind,
    featureFailure: FeatureFailure? = null,
): EventStoryResult.Failure = EventStoryResult.Failure(kind, featureFailure)
