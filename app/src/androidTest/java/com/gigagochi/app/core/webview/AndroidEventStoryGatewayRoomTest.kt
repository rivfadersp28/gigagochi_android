package com.gigagochi.app.core.webview

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.database.FirstSessionEntity
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.ScheduledStoryResultDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import com.gigagochi.app.feature.events.storyEventKey
import com.gigagochi.app.feature.events.travelEventKey
import com.gigagochi.app.feature.travel.OnboardingBatCorrectChoice
import com.gigagochi.app.feature.travel.onboardingBatStory
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEventStoryGatewayRoomTest {
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database) { Now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun roomProjectionUsesConsumedTravelAndMonotonicViewedWatermark() = runBlocking {
        repository.replacePetSnapshot(OwnedPetSnapshot(OwnerId, pet(), Now))
        val olderStory = storyDto("1", "2026-07-20T10:00:00Z")
        val latestStory = storyDto("2", "2026-07-20T12:00:00Z")
        val service = ChoiceService(latestStory)
        assertTrue(repository.saveScheduledStory(LocalScheduledStory(OwnerId, service.story(olderStory)!!)))
        assertTrue(repository.saveScheduledStory(LocalScheduledStory(OwnerId, service.story(latestStory)!!)))
        val consumed = travel(
            "travel-consumed",
            Instant.parse("2026-07-20T11:00:00Z").toEpochMilli(),
            consumedAt = 10L,
        )
        repository.saveTravelVideoAsset(consumed)
        repository.saveTravelVideoAsset(
            travel(
                "travel-unconsumed",
                Instant.parse("2026-07-20T13:00:00Z").toEpochMilli(),
                consumedAt = null,
            ),
        )
        repository.markEventHistoryViewed(OwnerId, PetId, consumed.completedAtEpochMillis)
        val runtime = DurableEventStoryRuntime(
            PetId,
            AndroidEventStoryGateway(OwnerId, repository, service) { Now },
        )

        val initial = runtime.snapshot().successValue()

        assertEquals(
            listOf(
                storyEventKey(latestStory.storyId),
                travelEventKey(consumed.requestKey),
                storyEventKey(olderStory.storyId),
            ),
            initial.history.items.map { it.key },
        )
        assertEquals(2, initial.badgeCount)

        val latest = requireNotNull(initial.latestEventAtEpochMillis)
        val marked = runtime.markViewed(latest).successValue()
        assertEquals(latest, marked.lastViewedAtEpochMillis)
        // Both scheduled stories are still unanswered and remain badged after viewing.
        assertEquals(2, marked.badgeCount)

        repository.markEventHistoryViewed(OwnerId, PetId, consumed.completedAtEpochMillis - 1L)
        assertEquals(latest, runtime.snapshot().successValue().lastViewedAtEpochMillis)
    }

    @Test
    fun scheduledPendingChoiceSurvivesDatabaseReopenAndRetryCannotForkOrDoubleReward() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "web-event-story-reopen.db"
            context.deleteDatabase(databaseName)
            val service = ChoiceService(storyDto("3", "2026-07-20T12:00:00Z"), failFirst = true)
            var diskDatabase = Room.databaseBuilder(
                context,
                GigagochiDatabase::class.java,
                databaseName,
            ).build()
            try {
                var diskRepository = PetLocalRepository(diskDatabase) { Now }
                diskRepository.replacePetSnapshot(OwnedPetSnapshot(OwnerId, pet(experience = 100), Now))
                assertTrue(
                    diskRepository.saveScheduledStory(
                        LocalScheduledStory(OwnerId, service.story(service.baseStory)!!),
                    ),
                )
                val firstRuntime = DurableEventStoryRuntime(
                    PetId,
                    AndroidEventStoryGateway(OwnerId, diskRepository, service) { Now },
                )
                val first = firstRuntime.chooseStory(
                    service.baseStory.storyId,
                    "b",
                    WinnerKey,
                ).successValue()
                assertEquals(DurableStoryPhase.Retryable, first.story.phase)
                assertEquals(WinnerKey, first.story.durableRequestKey)

                diskDatabase.close()
                diskDatabase = Room.databaseBuilder(
                    context,
                    GigagochiDatabase::class.java,
                    databaseName,
                ).build()
                diskRepository = PetLocalRepository(diskDatabase) { Now }
                val restarted = DurableEventStoryRuntime(
                    PetId,
                    AndroidEventStoryGateway(OwnerId, diskRepository, service) { Now },
                )

                val conflict = restarted.chooseStory(
                    service.baseStory.storyId,
                    "c",
                    LoserKey,
                )
                assertEquals(
                    EventStoryFailureKind.Conflict,
                    (conflict as EventStoryResult.Failure).kind,
                )
                assertEquals(listOf(WinnerKey), service.choiceRequestKeys)

                val retried = restarted.retryStory(service.baseStory.storyId).successValue()
                assertEquals(DurableStoryPhase.Result, retried.story.phase)
                assertEquals(WinnerKey, retried.story.durableRequestKey)
                assertEquals(listOf(WinnerKey, WinnerKey), service.choiceRequestKeys)
                assertEquals(120, diskRepository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
                assertEquals(1, diskRepository.getStoryReceipts(OwnerId).size)

                val replay = restarted.chooseStory(
                    service.baseStory.storyId,
                    "b",
                    LoserKey,
                ).successValue()
                assertEquals(WinnerKey, replay.story.durableRequestKey)
                assertEquals(listOf(WinnerKey, WinnerKey), service.choiceRequestKeys)
                assertEquals(120, replay.app.pet.experience)
                assertEquals(1, diskRepository.getStoryReceipts(OwnerId).size)
            } finally {
                diskDatabase.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun onboardingDuplicateChoiceAndFinishUseOneReceiptAndCapExperience() = runBlocking {
        repository.replacePetSnapshot(OwnedPetSnapshot(OwnerId, pet(experience = 2_900), Now))
        database.gigagochiDao().upsertFirstSession(
            FirstSessionEntity(
                ownerId = OwnerId,
                petId = PetId,
                stage = FirstSessionStage.AwaitingTravel.storageValue,
                selectedDestination = null,
                lastActionKey = null,
                updatedAtEpochMillis = Now,
            ),
        )
        val service = ChoiceService(storyDto("4", "2026-07-20T12:00:00Z"))
        val gateway = AndroidEventStoryGateway(OwnerId, repository, service) { Now }
        val storyId = onboardingBatStory(PetId).travelId

        val first = DurableEventStoryRuntime(PetId, gateway).chooseStory(
            storyId,
            OnboardingBatCorrectChoice,
            WinnerKey,
        ).successValue()
        val replay = DurableEventStoryRuntime(PetId, gateway).chooseStory(
            storyId,
            OnboardingBatCorrectChoice,
            LoserKey,
        ).successValue()

        assertEquals(WinnerKey, first.story.durableRequestKey)
        assertEquals(WinnerKey, replay.story.durableRequestKey)
        assertEquals(3_000, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, repository.getStoryReceipts(OwnerId).size)

        val finished = DurableEventStoryRuntime(PetId, gateway).finishStory(storyId).successValue()
        val finishReplay = DurableEventStoryRuntime(PetId, gateway).finishStory(storyId).successValue()
        assertEquals(FirstSessionStage.AwaitingCompletionMessage, finished.firstSession?.stage)
        assertEquals(FirstSessionStage.AwaitingCompletionMessage, finishReplay.firstSession?.stage)
        assertEquals(1, repository.getStoryReceipts(OwnerId).size)
        assertEquals(3_000, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
    }

    private class ChoiceService(
        val baseStory: ScheduledStoryDto,
        private var failFirst: Boolean = false,
    ) : TestAndroidFeatureService() {
        val choiceRequestKeys = mutableListOf<String>()

        override suspend fun chooseStory(
            storyId: String,
            request: ScheduledStoryChoiceRequestDto,
        ): FeatureApiResult<ScheduledStoryDto> {
            choiceRequestKeys += request.requestKey
            if (failFirst) {
                failFirst = false
                return FeatureApiResult.Failure(FeatureFailure(FeatureFailureKind.Network))
            }
            return FeatureApiResult.Success(
                baseStory.copy(
                    selectedChoice = request.choice,
                    result = ScheduledStoryResultDto(
                        text = "Результат",
                        adviceAssessment = "helpful",
                        reaction = "Реакция",
                        reactionTone = "enthusiastic",
                        consequence = "Последствие",
                        outcomeValence = "positive",
                        experienceGained = 20,
                    ),
                ),
            )
        }
    }

    private companion object {
        const val OwnerId = "owner-a"
        const val PetId = "pet-a"
        const val WinnerKey = "123e4567-e89b-42d3-a456-426614174000"
        const val LoserKey = "223e4567-e89b-42d3-a456-426614174001"
        const val Now = 1_800_000_000_000L

        fun <T> EventStoryResult<T>.successValue(): T =
            (this as EventStoryResult.Success<T>).value

        fun storyDto(suffix: String, createdAt: String) = ScheduledStoryDto(
            storyId = "android-story-${suffix.padStart(32, '0')}",
            petId = PetId,
            title = "История $suffix",
            text = "Ситуация",
            question = "Что делать?",
            choices = listOf("a", "b", "c", "d"),
            createdAt = createdAt,
            imageUrl = "https://example.test/static/story-$suffix.png?v=1",
        )

        fun travel(
            requestKey: String,
            completedAt: Long,
            consumedAt: Long?,
        ) = LocalTravelVideoAsset(
            ownerId = OwnerId,
            petId = PetId,
            requestKey = requestKey,
            backendJobId = "backend-$requestKey",
            prompt = "Путешествие",
            title = "Видео",
            scenario = "Сценарий",
            imageUrl = "https://example.test/static/$requestKey.png?v=1",
            videoUrl = "https://example.test/static/$requestKey.mp4?v=1",
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
    }
}
