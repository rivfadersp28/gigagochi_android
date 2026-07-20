package com.gigagochi.app.debugmenu

import com.gigagochi.app.core.database.LocalPersistenceValidation
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.ScheduledStoryChoiceClaim
import com.gigagochi.app.core.database.ScheduledStoryStore
import com.gigagochi.app.core.network.DueStoryRequestDto
import com.gigagochi.app.core.network.DueStoryResponseDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import com.gigagochi.app.core.network.toFeaturePetDto
import com.gigagochi.app.debug.DebugTestPetId
import com.gigagochi.app.debug.debugTestPetFixture
import com.gigagochi.app.feature.events.eventHistoryUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAppleStoryFixtureTest {
    @Test
    fun fixtureContainsOnePlayableTaskBankStoryWithStoryMedia() {
        val rows = debugAppleStoryFixtures("owner-debug")
        rows.forEach(LocalPersistenceValidation::scheduledStory)
        val history = eventHistoryUiState(rows)

        assertEquals(1, rows.size)
        assertTrue(rows.all { it.story.petId == DebugTestPetId })
        assertEquals(DebugAppleActiveStoryId, history.unanswered.single().story.storyId)
        assertNull(history.unanswered.single().story.selectedChoice)
        assertTrue(history.answered.isEmpty())
        assertEquals(4, history.unanswered.single().story.choices.size)
        assertEquals("Каменная наковальня", history.unanswered.single().story.title)
        assertEquals(
            "Что выдра может использовать, чтобы разбить раковину?",
            history.unanswered.single().story.question,
        )
        assertTrue(history.unanswered.single().story.videoUrl?.contains("/static/generated/") == true)
    }

    @Test
    fun roomSeedIsIdempotentAndScopedToAppleFixture() = runBlocking {
        val store = MemoryStoryStore()

        assertTrue(ensureDebugFixtureStories("owner-debug", debugTestPetFixture(), store))
        assertTrue(ensureDebugFixtureStories("owner-debug", debugTestPetFixture(), store))

        assertEquals(1, store.rows.size)
        assertEquals(
            setOf(DebugAppleActiveStoryId),
            store.rows.mapTo(mutableSetOf()) { it.story.storyId },
        )
    }

    @Test
    fun activeFixtureChoiceResolvesLocallyWithoutCallingBackend() = runBlocking {
        val delegate = CountingStoryService()
        val service = debugScheduledStoryService(delegate)
        val pet = debugTestPetFixture()

        service.dueStory(DueStoryRequestDto(pet.toFeaturePetDto()))
        assertEquals(1, delegate.dueCalls)

        val active = debugAppleStoryFixtures("owner-debug")
            .single { it.story.storyId == DebugAppleActiveStoryId }
            .story
        val selected = service.chooseStory(
            DebugAppleActiveStoryId,
            ScheduledStoryChoiceRequestDto(
                requestKey = "a11e0000-0000-4000-8000-000000000002",
                choice = active.choices.first(),
            ),
        )

        assertTrue(selected is FeatureApiResult.Success)
        val resolved = service.story(
            (selected as FeatureApiResult.Success<ScheduledStoryDto>).value,
        )
        assertEquals(active.choices.first(), resolved?.selectedChoice)
        assertTrue(resolved?.result != null)
        assertTrue(resolved?.resultVideoUrl?.contains("outcome-0.mp4") == true)
        assertTrue(requireNotNull(resolved?.result?.experienceGained) in 0..150)
        assertEquals(0, delegate.choiceCalls)
    }

    @Test
    fun everyTaskBankChoiceHasItsOwnOutcomeVideo() = runBlocking {
        val service = debugScheduledStoryService(CountingStoryService())
        val story = debugAppleStoryFixtures("owner-debug").single().story

        val videos = story.choices.mapIndexed { index, choice ->
            val selected = service.chooseStory(
                DebugAppleActiveStoryId,
                ScheduledStoryChoiceRequestDto(
                    requestKey = "a11e0000-0000-4000-8000-00000000000${index + 3}",
                    choice = choice,
                ),
            ) as FeatureApiResult.Success<ScheduledStoryDto>
            selected.value.resultVideoUrl
        }

        assertEquals(4, videos.filterNotNull().toSet().size)
    }

    private class MemoryStoryStore : ScheduledStoryStore {
        val rows = mutableListOf<LocalScheduledStory>()

        override suspend fun saveScheduledStory(story: LocalScheduledStory): Boolean {
            if (rows.none { it.ownerId == story.ownerId && it.story.storyId == story.story.storyId }) {
                rows += story
            }
            return true
        }

        override suspend fun deleteScheduledStory(ownerId: String, storyId: String): Boolean =
            rows.removeAll { it.ownerId == ownerId && it.story.storyId == storyId }

        override suspend fun getScheduledStory(ownerId: String, storyId: String) =
            rows.singleOrNull { it.ownerId == ownerId && it.story.storyId == storyId }

        override suspend fun getScheduledStories(ownerId: String, petId: String) =
            rows.filter { it.ownerId == ownerId && it.story.petId == petId }

        override suspend fun claimScheduledStoryChoice(
            ownerId: String,
            storyId: String,
            requestKey: String,
            choice: String,
        ): ScheduledStoryChoiceClaim = ScheduledStoryChoiceClaim.Missing
    }

    private class CountingStoryService : TestAndroidFeatureService() {
        var dueCalls = 0
        var choiceCalls = 0

        override suspend fun dueStory(request: DueStoryRequestDto): FeatureApiResult<DueStoryResponseDto> {
            dueCalls += 1
            return super.dueStory(request)
        }

        override suspend fun chooseStory(
            storyId: String,
            request: ScheduledStoryChoiceRequestDto,
        ): FeatureApiResult<ScheduledStoryDto> {
            choiceCalls += 1
            return super.chooseStory(storyId, request)
        }
    }
}
