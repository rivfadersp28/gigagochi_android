package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.AccountStartupDestination
import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.InteractiveStoryReceipt
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.feature.events.storyEventKey
import com.gigagochi.app.feature.events.travelEventKey
import com.gigagochi.app.feature.travel.OnboardingBatCorrectChoice
import com.gigagochi.app.feature.travel.ScheduledStoryChoiceResult
import com.gigagochi.app.feature.travel.ScheduledStoryChoicePreparationResult
import com.gigagochi.app.feature.travel.ScheduledStoryDueResult
import com.gigagochi.app.feature.travel.onboardingBatStory
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableEventStoryRuntimeTest {
    @Test
    fun `mixed feed is newest first excludes unconsumed travel and keeps unanswered badge`() =
        runBlocking {
            val oldAnswered = story(
                suffix = "1",
                createdAt = "2026-07-20T10:00:00Z",
                selectedChoice = "b",
            )
            val latestUnanswered = story(
                suffix = "2",
                createdAt = "2026-07-20T12:00:00Z",
            )
            val consumed = travel(
                requestKey = "travel-consumed",
                completedAt = Instant.parse("2026-07-20T11:00:00Z").toEpochMilli(),
                consumedAt = 10L,
            )
            val unconsumed = travel(
                requestKey = "travel-unconsumed",
                completedAt = Instant.parse("2026-07-20T13:00:00Z").toEpochMilli(),
                consumedAt = null,
            )
            val gateway = FakeEventStoryGateway(
                pet = pet(),
                stories = mutableListOf(oldAnswered, latestUnanswered),
                travelAssets = mutableListOf(consumed, unconsumed),
                lastViewedAt = consumed.completedAtEpochMillis,
            )
            val runtime = DurableEventStoryRuntime(PetId, gateway)

            val initial = runtime.snapshot().successValue()

            assertEquals(
                listOf(
                    storyEventKey(latestUnanswered.story.storyId),
                    travelEventKey(consumed.requestKey),
                    storyEventKey(oldAnswered.story.storyId),
                ),
                initial.history.items.map { it.key },
            )
            assertEquals(1, initial.badgeCount)
            assertEquals(
                Instant.parse("2026-07-20T12:00:00Z").toEpochMilli(),
                initial.latestEventAtEpochMillis,
            )

            val marked = runtime.markViewed(requireNotNull(initial.latestEventAtEpochMillis))
                .successValue()
            assertEquals(initial.latestEventAtEpochMillis, marked.lastViewedAtEpochMillis)
            assertEquals(1, marked.badgeCount)

            val rejected = runtime.markViewed(Long.MAX_VALUE)
            assertEquals(
                EventStoryFailureKind.Conflict,
                (rejected as EventStoryResult.Failure).kind,
            )
            assertEquals(initial.latestEventAtEpochMillis, gateway.lastViewedAt)
        }

    @Test
    fun `scheduled choice survives recreation and retry reuses durable winner identity`() =
        runBlocking {
            val gateway = FakeEventStoryGateway(
                pet = pet(experience = 100),
                stories = mutableListOf(story("3", "2026-07-20T12:00:00Z")),
                failFirstScheduledSubmission = true,
            )
            val storyId = gateway.stories.single().story.storyId
            val firstRuntime = DurableEventStoryRuntime(PetId, gateway)

            val failed = firstRuntime.chooseStory(storyId, "b", WinnerKey).successValue()

            assertEquals(DurableStoryPhase.Retryable, failed.story.phase)
            assertEquals(WinnerKey, failed.story.durableRequestKey)
            assertEquals("b", failed.story.pendingChoice)
            assertEquals(WinnerKey, gateway.stories.single().choiceRequestKey)
            assertEquals("b", gateway.stories.single().pendingChoice)

            val restarted = DurableEventStoryRuntime(PetId, gateway)
            val recovered = restarted.openStory(storyId).successValue()
            assertEquals(DurableStoryPhase.Retryable, recovered.story.phase)
            assertEquals(WinnerKey, recovered.story.durableRequestKey)
            assertEquals("b", recovered.story.pendingChoice)

            val retried = restarted.retryStory(storyId).successValue()
            assertEquals(DurableStoryPhase.Result, retried.story.phase)
            assertEquals(WinnerKey, retried.story.durableRequestKey)
            assertNull(retried.story.pendingChoice)
            assertEquals(listOf(WinnerKey, WinnerKey), gateway.scheduledSubmissionKeys)
            assertEquals(120, retried.app.pet.experience)
            assertEquals(1, gateway.storyReceipts.size)

            val replay = restarted.chooseStory(storyId, "b", LoserKey).successValue()
            assertEquals(WinnerKey, replay.story.durableRequestKey)
            assertEquals(120, replay.app.pet.experience)
            assertEquals(1, gateway.storyReceipts.size)
            assertEquals(listOf(WinnerKey, WinnerKey), gateway.scheduledSubmissionKeys)

            val conflict = restarted.chooseStory(storyId, "c", LoserKey)
            assertEquals(
                EventStoryFailureKind.Conflict,
                (conflict as EventStoryResult.Failure).kind,
            )
            assertTrue(restarted.finishStory(storyId) is EventStoryResult.Success)
        }

    @Test
    fun `scheduled preparation is Room only and recreation exposes exact winner as retryable`() =
        runBlocking {
            val gateway = FakeEventStoryGateway(
                pet = pet(experience = 100),
                stories = mutableListOf(story("prepared", "2026-07-20T12:00:00Z")),
            )
            val storyId = gateway.stories.single().story.storyId
            val runtime = DurableEventStoryRuntime(PetId, gateway)

            val prepared = runtime.prepareStoryChoice(storyId, "b", WinnerKey).successValue()

            assertEquals(DurableStoryPhase.ChoicePending, prepared.opened.story.phase)
            assertEquals(WinnerKey, prepared.opened.story.durableRequestKey)
            assertEquals("b", prepared.opened.story.pendingChoice)
            assertEquals(WinnerKey, prepared.execution?.requestKey)
            assertTrue(gateway.scheduledSubmissionKeys.isEmpty())
            assertEquals(WinnerKey, gateway.stories.single().choiceRequestKey)

            val recreated = DurableEventStoryRuntime(PetId, gateway)
            val recovered = recreated.openStory(storyId).successValue()
            assertEquals(DurableStoryPhase.Retryable, recovered.story.phase)
            assertEquals(WinnerKey, recovered.story.durableRequestKey)
            assertEquals("b", recovered.story.pendingChoice)

            val finished = recreated.executePreparedStoryChoice(
                requireNotNull(prepared.execution),
            ).successValue()
            assertEquals(DurableStoryPhase.Result, finished.story.phase)
            assertEquals(listOf(WinnerKey), gateway.scheduledSubmissionKeys)
            assertEquals(120, finished.app.pet.experience)
            assertEquals(1, gateway.storyReceipts.size)
        }

    @Test
    fun `onboarding bat restores winner rewards once caps xp and finishes idempotently`() =
        runBlocking {
            val gateway = FakeEventStoryGateway(
                pet = pet(experience = 2_900),
                firstSession = firstSession(FirstSessionStage.AwaitingTravel),
            )
            val storyId = onboardingBatStory(PetId).travelId
            val firstRuntime = DurableEventStoryRuntime(PetId, gateway)

            val question = firstRuntime.openStory(storyId).successValue()
            assertEquals(DurableStoryPhase.Question, question.story.phase)

            val chosen = firstRuntime.chooseStory(
                storyId,
                OnboardingBatCorrectChoice,
                WinnerKey,
            ).successValue()
            assertEquals(DurableStoryPhase.Result, chosen.story.phase)
            assertEquals(WinnerKey, chosen.story.durableRequestKey)
            assertEquals(3_000, chosen.app.pet.experience)

            val duplicate = firstRuntime.chooseStory(
                storyId,
                OnboardingBatCorrectChoice,
                LoserKey,
            ).successValue()
            assertEquals(WinnerKey, duplicate.story.durableRequestKey)
            assertEquals(3_000, duplicate.app.pet.experience)
            assertEquals(1, gateway.storyReceipts.size)
            assertEquals(1, gateway.onboardingRewardApplications)

            val restarted = DurableEventStoryRuntime(PetId, gateway)
            val recovered = restarted.openStory(storyId).successValue()
            assertEquals(WinnerKey, recovered.story.result?.requestKey)
            assertEquals(3_000, recovered.app.pet.experience)

            val finished = restarted.finishStory(storyId).successValue()
            assertEquals(FirstSessionStage.AwaitingCompletionMessage, finished.firstSession?.stage)
            val replayedFinish = restarted.finishStory(storyId).successValue()
            assertEquals(
                FirstSessionStage.AwaitingCompletionMessage,
                replayedFinish.firstSession?.stage,
            )
            assertEquals(1, gateway.onboardingFinishApplications)
            assertEquals(1, gateway.storyReceipts.size)
        }

    @Test
    fun `dashboard bootstrap always schedules mvp sync and completion only for attached work`() {
        var mvpCalls = 0
        var completionCalls = 0
        val plain = dashboard(pet())

        dispatchWebDashboardBootstrapEffects(
            destination = plain,
            enqueueCompletionSync = { completionCalls += 1 },
            enqueueMvpSync = { mvpCalls += 1 },
        )
        dispatchWebDashboardBootstrapEffects(
            destination = plain.copy(
                pendingOutfit = LocalPendingOutfit(
                    ownerId = "owner-a",
                    petId = PetId,
                    requestKey = WinnerKey,
                    localJobId = "local-outfit",
                    backendJobId = "backend-outfit",
                    prompt = "шарф",
                    baseAssetSetId = "asset-a",
                    acceptedAtEpochMillis = 1L,
                    backendState = PendingBackendState.Attached,
                ),
            ),
            enqueueCompletionSync = { completionCalls += 1 },
            enqueueMvpSync = { mvpCalls += 1 },
        )

        assertEquals(2, mvpCalls)
        assertEquals(1, completionCalls)
    }

    @Test
    fun `user invocation isolation schedules no background feature worker`() {
        var mvpCalls = 0
        var completionCalls = 0
        val attached = dashboard(pet()).copy(
            pendingOutfit = LocalPendingOutfit(
                ownerId = "owner-a",
                petId = PetId,
                requestKey = WinnerKey,
                localJobId = "local-outfit",
                backendJobId = "backend-outfit",
                prompt = "шарф",
                baseAssetSetId = "asset-a",
                acceptedAtEpochMillis = 1L,
                backendState = PendingBackendState.Attached,
            ),
        )

        dispatchWebDashboardBootstrapEffects(
            destination = attached,
            enqueueCompletionSync = { completionCalls += 1 },
            enqueueMvpSync = { mvpCalls += 1 },
            generationPolicy = WebAppGenerationPolicy.UserInvocationsOnly,
        )

        assertEquals(0, mvpCalls)
        assertEquals(0, completionCalls)
    }

    @Test
    fun `user invocation isolation blocks ambient and due generation but preserves event reads`() =
        runBlocking {
            var ambientCalls = 0
            val ambient = dispatchWebAutomaticAmbientGeneration(
                WebAppGenerationPolicy.UserInvocationsOnly,
            ) {
                ambientCalls += 1
                WebAmbientGenerationResult.Failure
            }
            val delegate = FakeEventStoryGateway(
                pet = pet(),
                travelAssets = mutableListOf(
                    travel(
                        requestKey = "travel-isolation",
                        completedAt = 2L,
                        consumedAt = 3L,
                    ),
                ),
                dueResult = ScheduledStoryDueResult.Pending,
            )
            val isolated = webEventStoryGatewayForGenerationPolicy(
                WebAppGenerationPolicy.UserInvocationsOnly,
                delegate,
            )

            val loaded = requireNotNull(isolated.load(PetId))
            val due = isolated.checkDue(delegate.pet)

            assertEquals(WebAmbientGenerationResult.Failure, ambient)
            assertEquals(0, ambientCalls)
            assertEquals(1, loaded.travelVideoAssets.size)
            assertEquals(ScheduledStoryDueResult.NotDue, due)
            assertEquals(0, delegate.dueChecks)
        }

    @Test
    fun `production generation policy delegates ambient and due generation`() = runBlocking {
        var ambientCalls = 0
        dispatchWebAutomaticAmbientGeneration(WebAppGenerationPolicy.Production) {
            ambientCalls += 1
            WebAmbientGenerationResult.Failure
        }
        val delegate = FakeEventStoryGateway(
            pet = pet(),
            dueResult = ScheduledStoryDueResult.Pending,
        )
        val production = webEventStoryGatewayForGenerationPolicy(
            WebAppGenerationPolicy.Production,
            delegate,
        )

        assertEquals(ScheduledStoryDueResult.Pending, production.checkDue(delegate.pet))
        assertEquals(1, ambientCalls)
        assertEquals(1, delegate.dueChecks)
    }

    @Test
    fun `due refresh preserves pending status for one shot scheduler integration`() = runBlocking {
        val gateway = FakeEventStoryGateway(
            pet = pet(),
            dueResult = ScheduledStoryDueResult.Pending,
        )

        val refreshed = DurableEventStoryRuntime(PetId, gateway)
            .refreshDueStory()
            .successValue()

        assertEquals(DueStoryRefreshStatus.Pending, refreshed.status)
        assertTrue(refreshed.snapshot.history.isEmpty)
    }

    private class FakeEventStoryGateway(
        var pet: PetDashboardState,
        var firstSession: LocalFirstSession? = null,
        val stories: MutableList<LocalScheduledStory> = mutableListOf(),
        val travelAssets: MutableList<LocalTravelVideoAsset> = mutableListOf(),
        val storyReceipts: MutableList<InteractiveStoryReceipt> = mutableListOf(),
        var lastViewedAt: Long? = null,
        private var failFirstScheduledSubmission: Boolean = false,
        var dueResult: ScheduledStoryDueResult = ScheduledStoryDueResult.NotDue,
    ) : EventStoryGateway {
        val scheduledSubmissionKeys = mutableListOf<String>()
        var dueChecks = 0
        var onboardingRewardApplications = 0
        var onboardingFinishApplications = 0
        private val finishKeys = mutableSetOf<String>()

        override suspend fun load(petId: String): EventStoryRecovery? =
            if (petId != pet.petId) null else EventStoryRecovery(
                pet = pet,
                firstSession = firstSession,
                scheduledStories = stories.toList(),
                travelVideoAssets = travelAssets.toList(),
                storyReceipts = storyReceipts.toList(),
                lastViewedAtEpochMillis = lastViewedAt,
            )

        override suspend fun markViewed(petId: String, viewedAtEpochMillis: Long): Boolean {
            if (petId != pet.petId) return false
            lastViewedAt = maxOf(lastViewedAt ?: Long.MIN_VALUE, viewedAtEpochMillis)
            return true
        }

        override suspend fun checkDue(pet: PetDashboardState): ScheduledStoryDueResult {
            dueChecks += 1
            return dueResult
        }

        override suspend fun prepareScheduledStoryChoice(
            storyId: String,
            choice: String,
            proposedRequestKey: String,
        ): ScheduledStoryChoicePreparationResult {
            val index = stories.indexOfFirst { it.story.storyId == storyId }
            if (index < 0) return preparationFailure(FeatureFailureKind.NotFound)
            val local = stories[index]
            local.story.selectedChoice?.let { selected ->
                return if (selected == choice && local.choiceRequestKey != null) {
                    ScheduledStoryChoicePreparationResult.Prepared(local.choiceRequestKey, choice)
                } else {
                    preparationFailure(FeatureFailureKind.Conflict)
                }
            }
            if (local.choiceRequestKey != null && local.pendingChoice != choice) {
                return preparationFailure(FeatureFailureKind.Conflict)
            }
            val winnerKey = local.choiceRequestKey ?: proposedRequestKey
            if (local.choiceRequestKey == null) {
                stories[index] = local.copy(
                    choiceRequestKey = winnerKey,
                    pendingChoice = choice,
                )
            }
            return ScheduledStoryChoicePreparationResult.Prepared(winnerKey, choice)
        }

        override suspend fun executePreparedScheduledStoryChoice(
            storyId: String,
            requestKey: String,
            choice: String,
        ): ScheduledStoryChoiceResult {
            val index = stories.indexOfFirst { it.story.storyId == storyId }
            if (index < 0) return scheduledFailure(FeatureFailureKind.NotFound)
            val local = stories[index]
            if (local.choiceRequestKey != requestKey) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            local.story.selectedChoice?.let { selected ->
                return if (selected == choice) savedScheduled(local) else {
                    scheduledFailure(FeatureFailureKind.Conflict)
                }
            }
            if (local.pendingChoice != choice) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            scheduledSubmissionKeys += requestKey
            if (failFirstScheduledSubmission) {
                failFirstScheduledSubmission = false
                return scheduledFailure(FeatureFailureKind.Network)
            }
            val selected = stories[index].copy(
                story = stories[index].story.copy(
                    selectedChoice = choice,
                    result = ScheduledStoryResult(
                        text = "Результат",
                        reaction = "Реакция",
                        consequence = "Последствие",
                        experienceGained = 20,
                    ),
                ),
                pendingChoice = null,
            )
            stories[index] = selected
            applyScheduledReceipt(selected)
            return savedScheduled(selected)
        }

        override suspend fun reconcileScheduledStory(storyId: String): ScheduledStoryChoiceResult {
            val local = stories.singleOrNull { it.story.storyId == storyId }
                ?: return scheduledFailure(FeatureFailureKind.NotFound)
            if (local.story.selectedChoice == null) {
                return scheduledFailure(FeatureFailureKind.Conflict)
            }
            applyScheduledReceipt(local)
            return savedScheduled(local)
        }

        override suspend fun commitOnboardingBatChoice(
            petId: String,
            requestKey: String,
        ): FirstSessionMutationResult {
            if (petId != pet.petId || firstSession == null) {
                return FirstSessionMutationResult.Missing
            }
            val travelId = onboardingBatStory(petId).travelId
            val prior = storyReceipts.singleOrNull {
                it.travelId == travelId && it.partKey == "choice-result"
            }
            if (prior != null) {
                return FirstSessionMutationResult.AlreadyApplied(requireNotNull(firstSession), pet)
            }
            if (firstSession?.stage != FirstSessionStage.AwaitingTravel) {
                return FirstSessionMutationResult.WrongStage
            }
            storyReceipts += receipt(requestKey, travelId, 200)
            pet = pet.copy(experience = (pet.experience + 200).coerceAtMost(3_000))
            onboardingRewardApplications += 1
            return FirstSessionMutationResult.Applied(requireNotNull(firstSession), pet)
        }

        override suspend fun finishOnboardingBat(
            petId: String,
            actionKey: String,
        ): FirstSessionMutationResult {
            val session = firstSession ?: return FirstSessionMutationResult.Missing
            if (actionKey in finishKeys) {
                return FirstSessionMutationResult.AlreadyApplied(session, pet)
            }
            if (
                petId != pet.petId ||
                session.stage != FirstSessionStage.AwaitingTravel ||
                storyReceipts.none {
                    it.travelId == onboardingBatStory(petId).travelId &&
                        it.partKey == "choice-result"
                }
            ) {
                return FirstSessionMutationResult.WrongStage
            }
            finishKeys += actionKey
            onboardingFinishApplications += 1
            firstSession = session.copy(
                stage = FirstSessionStage.AwaitingCompletionMessage,
                lastActionKey = actionKey,
            )
            return FirstSessionMutationResult.Applied(requireNotNull(firstSession), pet)
        }

        private fun applyScheduledReceipt(local: LocalScheduledStory) {
            val requestKey = requireNotNull(local.choiceRequestKey)
            if (storyReceipts.any { it.travelId == local.story.storyId }) return
            val delta = requireNotNull(local.story.result).experienceGained
            storyReceipts += receipt(requestKey, local.story.storyId, delta)
            pet = pet.copy(experience = (pet.experience + delta).coerceAtMost(3_000))
        }

        private fun savedScheduled(local: LocalScheduledStory) =
            ScheduledStoryChoiceResult.Saved(
                story = local.story,
                requestKey = requireNotNull(local.choiceRequestKey),
                committedExperience = pet.experience,
                committedTravelIds = setOf(local.story.storyId),
            )
    }

    private companion object {
        const val PetId = "pet-a"
        const val WinnerKey = "123e4567-e89b-42d3-a456-426614174000"
        const val LoserKey = "223e4567-e89b-42d3-a456-426614174001"

        fun <T> EventStoryResult<T>.successValue(): T =
            (this as EventStoryResult.Success<T>).value

        fun scheduledFailure(kind: FeatureFailureKind) = ScheduledStoryChoiceResult.Failure(
            FeatureFailure(kind),
        )

        fun preparationFailure(kind: FeatureFailureKind) =
            ScheduledStoryChoicePreparationResult.Failure(FeatureFailure(kind))

        fun receipt(requestKey: String, travelId: String, experience: Int) =
            InteractiveStoryReceipt(
                ownerId = "owner-a",
                petId = PetId,
                receiptKey = requestKey,
                travelId = travelId,
                partKey = "choice-result",
                experienceDelta = experience,
                hungerDelta = 0,
                happinessDelta = 0,
                energyDelta = 0,
                appliedAtEpochMillis = 1L,
            )

        fun story(
            suffix: String,
            createdAt: String,
            selectedChoice: String? = null,
        ): LocalScheduledStory {
            val result = selectedChoice?.let {
                ScheduledStoryResult("Результат", "Реакция", "Последствие", 20)
            }
            return LocalScheduledStory(
                ownerId = "owner-a",
                story = ScheduledStory(
                    storyId = "android-story-${suffix.padStart(32, '0')}",
                    petId = PetId,
                    title = "История $suffix",
                    text = "Ситуация",
                    question = "Что делать?",
                    choices = listOf("a", "b", "c", "d"),
                    createdAt = createdAt,
                    imageUrl = "/static/story-$suffix.png?v=1",
                    videoUrl = null,
                    selectedChoice = selectedChoice,
                    result = result,
                ),
                choiceRequestKey = selectedChoice?.let { "story-result-$suffix" },
            )
        }

        fun travel(
            requestKey: String,
            completedAt: Long,
            consumedAt: Long?,
        ) = LocalTravelVideoAsset(
            ownerId = "owner-a",
            petId = PetId,
            requestKey = requestKey,
            backendJobId = "backend-$requestKey",
            prompt = "Путешествие",
            title = "Видео",
            scenario = "Сценарий",
            imageUrl = "/static/$requestKey.png?v=1",
            videoUrl = "/static/$requestKey.mp4?v=1",
            completedAtEpochMillis = completedAt,
            consumedAtEpochMillis = consumedAt,
        )

        fun pet(experience: Int = 100) = PetDashboardState(
            petId = PetId,
            assetSetId = "asset-a",
            description = "Дракон",
            name = "Тото",
            stage = "baby",
            stageLabel = "Малыш",
            mood = "idle",
            experience = experience,
            hunger = 80,
            happiness = 80,
            energy = 80,
            message = "Привет",
        )

        fun firstSession(stage: FirstSessionStage) = LocalFirstSession(
            ownerId = "owner-a",
            petId = PetId,
            stage = stage,
            updatedAtEpochMillis = 1L,
        )

        fun dashboard(pet: PetDashboardState) = AccountStartupDestination.Dashboard(
            pet = pet,
            pendingOutfit = null,
            pendingTravel = null,
            storyReceipts = emptyList(),
            firstSession = null,
            pendingChat = null,
        )
    }
}
