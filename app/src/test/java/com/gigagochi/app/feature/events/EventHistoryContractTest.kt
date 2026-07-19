package com.gigagochi.app.feature.events

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHistoryContractTest {
    @Test
    fun storiesAndConsumedTravelVideosFormOneNewestFirstChronology() {
        val state = eventHistoryUiState(
            stories = listOf(
                story("answered-old", "2026-07-18T18:00:00Z", answered = true),
                story("pending-new", "2026-07-19T19:00:00+03:00"),
                story("answered-new", "2026-07-19T15:00:00Z", answered = true),
                story("pending-old", "2026-07-19T10:00:00Z"),
            ),
            travelVideos = listOf(
                travel("ready", completedAt = Long.MAX_VALUE - 1, consumed = true),
                travel("not-consumed", completedAt = Long.MAX_VALUE, consumed = false),
            ),
        )

        assertEquals(
            listOf(
                travelEventKey("ready"),
                storyEventKey(storyId("pending-new")),
                storyEventKey(storyId("answered-new")),
                storyEventKey(storyId("pending-old")),
                storyEventKey(storyId("answered-old")),
            ),
            state.items.map(EventHistoryItem::key),
        )
        assertEquals(2, state.unansweredCount)
    }

    @Test
    fun travelCaptionPrefersGeneratedTitleAndFallsBackToPrompt() {
        assertEquals(
            "Путешествие к маяку",
            travelEventCaption(travel("one", title = "  Путешествие к маяку  ")),
        )
        assertEquals(
            "Хочу увидеть море",
            travelEventCaption(travel("two", title = " ")),
        )
    }

    @Test
    fun emptyHistoryHasNoBadgeCount() {
        val state = eventHistoryUiState(emptyList())

        assertTrue(state.isEmpty)
        assertEquals(0, state.unansweredCount)
    }

    private fun story(
        id: String,
        createdAt: String,
        answered: Boolean = false,
    ) = LocalScheduledStory(
        ownerId = "owner-a",
        story = ScheduledStory(
            storyId = storyId(id),
            petId = "pet-a",
            title = "Событие",
            text = "Питомцу нужна помощь",
            question = "Что делать?",
            choices = listOf("a", "b", "c", "d"),
            createdAt = createdAt,
            imageUrl = null,
            videoUrl = null,
            selectedChoice = if (answered) "a" else null,
            result = if (answered) {
                ScheduledStoryResult("Итог", "Спасибо", "Всё получилось", 125)
            } else null,
        ),
        choiceRequestKey = if (answered) "123e4567-e89b-42d3-a456-426614174000" else null,
    )

    private fun storyId(id: String) = "android-story-${id.padEnd(32, '0').take(32)}"

    private fun travel(
        key: String,
        completedAt: Long = 100L,
        consumed: Boolean = true,
        title: String? = null,
    ) = LocalTravelVideoAsset(
        ownerId = "owner-a",
        petId = "pet-a",
        requestKey = key,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Хочу увидеть море",
        title = title,
        scenario = null,
        imageUrl = "https://gigagochi.test/static/poster.png",
        videoUrl = "https://gigagochi.test/static/video.mp4",
        completedAtEpochMillis = completedAt,
        consumedAtEpochMillis = if (consumed) completedAt + 1 else null,
    )
}
