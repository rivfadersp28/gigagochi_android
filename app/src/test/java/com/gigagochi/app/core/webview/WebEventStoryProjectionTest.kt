package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.events.eventHistoryUiState
import com.gigagochi.app.feature.travel.InteractiveTravelStory
import com.gigagochi.app.feature.travel.InteractiveTravelStoryResult
import com.gigagochi.app.feature.travel.onboardingBatQuestionParagraphs
import com.gigagochi.app.feature.travel.onboardingBatResultParagraphs
import com.gigagochi.app.feature.travel.onboardingBatStory
import com.gigagochi.app.feature.travel.onboardingBatStoryResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebEventStoryProjectionTest {
    @Test
    fun `events projection keeps exact UI data and exposes only opaque media capabilities`() {
        val materializer = RecordingTestMediaMaterializer()
        val registry = registry(materializer)
        val scheduled = scheduledStory()
        val travel = travelVideo()
        val source = eventSnapshot(scheduled, travel, badgeCount = 7, lastViewedAt = 42L)

        val projected = projectEventStorySnapshotForWeb(
            snapshot = source,
            registry = registry,
            initialFocusTravelRequestKey = TravelRequestKey,
        )

        assertEquals(7, projected.badgeCount)
        assertEquals(source.latestEventAtEpochMillis, projected.latestEventAtEpochMillis)
        assertEquals(42L, projected.lastViewedAtEpochMillis)
        assertEquals(TravelRequestKey, projected.initialFocusTravelRequestKey)

        val story = projected.stories.single().story
        assertEquals(StoryId, story.storyId)
        assertEquals(scheduled.story.title, story.title)
        assertEquals(scheduled.story.text, story.text)
        assertEquals(scheduled.story.question, story.question)
        assertEquals(scheduled.story.choices, story.choices)
        assertEquals(scheduled.story.selectedChoice, story.selectedChoice)
        assertEquals(
            WebScheduledStoryResultSnapshot(
                text = requireNotNull(scheduled.story.result).text,
                reaction = requireNotNull(scheduled.story.result).reaction,
                consequence = requireNotNull(scheduled.story.result).consequence,
                experienceGained = requireNotNull(scheduled.story.result).experienceGained,
            ),
            story.result,
        )
        listOf(
            story.imageRef,
            story.videoRef,
            story.resultImageRef,
            story.resultVideoRef,
        ).forEach { reference ->
            assertTrue(requireNotNull(reference).startsWith("/media/v1/"))
        }

        val travelItem = projected.travelVideos.single()
        assertEquals(TravelRequestKey, travelItem.requestKey)
        assertEquals(travel.prompt, travelItem.prompt)
        assertEquals(travel.title, travelItem.title)
        assertEquals(travel.scenario, travelItem.scenario)
        assertEquals(travel.completedAtEpochMillis, travelItem.completedAtEpochMillis)
        assertTrue(requireNotNull(travelItem.imageRef).startsWith("/media/v1/"))
        assertTrue(requireNotNull(travelItem.videoRef).startsWith("/media/v1/"))
        assertEquals("video/mp4", registry.resolveRequest(
            "$WebOrigin${travelItem.videoRef}",
        )?.media?.mimeType)
        assertEquals(travel.videoUrl, materializer.sources.last())

        val encoded = Json.encodeToString(projected)
        assertFalse(encoded.contains("owner-secret"))
        assertFalse(encoded.contains("pet-secret"))
        assertFalse(encoded.contains("backend-job-secret"))
        assertFalse(encoded.contains("choice-request-secret"))
        assertFalse(encoded.contains(BackendOrigin))
        assertFalse(encoded.contains("ownerId"))
        assertFalse(encoded.contains("petId"))
        assertFalse(encoded.contains("backendJobId"))
        assertFalse(encoded.contains("consumedAtEpochMillis"))
    }

    @Test
    fun `invalid event media URLs become null and stale initial focus is dropped`() {
        val scheduled = scheduledStory().copy(
            story = scheduledStory().story.copy(
                imageUrl = "https://evil.example/static/story.png",
                videoUrl = "$BackendOrigin/static/story.mov",
                resultImageUrl = "$BackendOrigin/static/story.gif",
                resultVideoUrl = "http://gigagochi.serega.works/static/story.mp4",
            ),
        )
        val travel = travelVideo().copy(
            imageUrl = "data:image/png;base64,forged",
            videoUrl = "$BackendOrigin/static/travel.webm",
        )

        val projected = projectEventStorySnapshotForWeb(
            eventSnapshot(scheduled, travel),
            registry(),
            initialFocusTravelRequestKey = "stale-request-key",
        )

        val story = projected.stories.single().story
        assertNull(story.imageRef)
        assertNull(story.videoRef)
        assertNull(story.resultImageRef)
        assertNull(story.resultVideoRef)
        assertNull(projected.travelVideos.single().imageRef)
        assertNull(projected.travelVideos.single().videoRef)
        assertNull(projected.initialFocusTravelRequestKey)
    }

    @Test
    fun `scheduled opened story preserves phase kind origin and exact result`() {
        val sourceStory = InteractiveTravelStory(
            travelId = StoryId,
            title = "Шорох у дерева",
            storyText = "Первая строка.\nВторая строка без нормализации.",
            challenge = "Что сделать?",
            choices = listOf("Подойти", "Позвать", "Спрятаться", "Подождать"),
            enabledChoice = "",
            imageUrl = "$BackendOrigin/static/scheduled.png?v=image_1",
            videoUrl = "$BackendOrigin/static/scheduled.mp4?v=video_1",
        )
        val sourceResult = InteractiveTravelStoryResult(
            travelId = StoryId,
            requestKey = StoryRequestKey,
            answer = "Подойти",
            text = "Герой помог малышу.",
            reaction = "Спасибо!",
            consequence = "Все снова вместе.",
            experienceGained = 125,
            imageUrl = "$BackendOrigin/static/result.webp",
            videoUrl = "$BackendOrigin/static/result.mp4",
        )

        val projected = projectDurableStorySnapshotForWeb(
            DurableStorySnapshot(
                kind = DurableStoryKind.Scheduled,
                story = sourceStory,
                phase = DurableStoryPhase.Result,
                durableRequestKey = StoryRequestKey,
                result = sourceResult,
            ),
            WebDurableStoryOrigin.Events,
            registry(),
        )

        assertEquals(WebDurableStoryPhase.Result, projected.phase)
        assertEquals(WebDurableStoryKind.Scheduled, projected.kind)
        assertEquals(WebDurableStoryOrigin.Events, projected.origin)
        assertEquals(StoryRequestKey, projected.durableRequestKey)
        assertEquals(sourceStory.travelId, projected.story.storyId)
        assertEquals(sourceStory.title, projected.story.title)
        assertEquals(sourceStory.storyText, projected.story.text)
        assertEquals(sourceStory.challenge, projected.story.question)
        assertEquals(sourceStory.choices, projected.story.choices)
        assertNull(projected.story.enabledChoice)
        assertEquals(listOf(sourceStory.storyText, sourceStory.challenge), projected.story.questionParagraphs)
        val result = requireNotNull(projected.result)
        assertEquals(sourceResult.requestKey, result.requestKey)
        assertEquals(sourceResult.answer, result.answer)
        assertEquals(sourceResult.text, result.text)
        assertEquals(sourceResult.reaction, result.reaction)
        assertEquals(sourceResult.consequence, result.consequence)
        assertEquals(sourceResult.experienceGained, result.experienceGained)
        assertEquals(listOf(sourceResult.text, sourceResult.consequence), result.paragraphs)
        listOf(
            projected.story.imageRef,
            projected.story.videoRef,
            result.imageRef,
            result.videoRef,
        ).forEach { reference ->
            assertTrue(requireNotNull(reference).startsWith("/media/v1/"))
        }
        val encoded = Json.encodeToString(projected)
        assertTrue(encoded.contains("\"phase\":\"result\""))
        assertTrue(encoded.contains("\"kind\":\"scheduled\""))
        assertTrue(encoded.contains("\"origin\":\"events\""))
        assertFalse(encoded.contains(BackendOrigin))
    }

    @Test
    fun `all durable phases retain distinct bridge values`() {
        val story = InteractiveTravelStory(
            travelId = StoryId,
            title = "История",
            storyText = "Текст",
            challenge = "Вопрос",
            choices = listOf("a", "b", "c", "d"),
            enabledChoice = "",
        )
        val expected = mapOf(
            DurableStoryPhase.Question to WebDurableStoryPhase.Question,
            DurableStoryPhase.ChoicePending to WebDurableStoryPhase.ChoicePending,
            DurableStoryPhase.Retryable to WebDurableStoryPhase.Retryable,
            DurableStoryPhase.Result to WebDurableStoryPhase.Result,
        )

        expected.forEach { (sourcePhase, webPhase) ->
            val hasPendingChoice = sourcePhase in setOf(
                DurableStoryPhase.ChoicePending,
                DurableStoryPhase.Retryable,
            )
            val pendingChoice = "b".takeIf { hasPendingChoice }
            val projected = projectDurableStorySnapshotForWeb(
                DurableStorySnapshot(
                    kind = DurableStoryKind.Scheduled,
                    story = story,
                    phase = sourcePhase,
                    durableRequestKey = StoryRequestKey.takeIf { hasPendingChoice },
                    pendingChoice = pendingChoice,
                ),
                WebDurableStoryOrigin.Events,
                registry(),
            )

            assertEquals(webPhase, projected.phase)
            assertEquals(pendingChoice, projected.pendingChoice)
        }
    }

    @Test
    fun `scheduled story keeps explicit dashboard or events navigation origin`() {
        val snapshot = DurableStorySnapshot(
            kind = DurableStoryKind.Scheduled,
            story = InteractiveTravelStory(
                travelId = StoryId,
                title = "История",
                storyText = "Текст",
                challenge = "Вопрос",
                choices = listOf("a", "b", "c", "d"),
                enabledChoice = "",
            ),
            phase = DurableStoryPhase.Question,
        )
        val expectedWireValues = mapOf(
            WebDurableStoryOrigin.Events to "events",
            WebDurableStoryOrigin.Dashboard to "dashboard",
        )

        expectedWireValues.forEach { (origin, wireValue) ->
            val projected = projectDurableStorySnapshotForWeb(
                snapshot = snapshot,
                origin = origin,
                registry = registry(),
            )

            assertEquals(origin, projected.origin)
            assertTrue(Json.encodeToString(projected).contains("\"origin\":\"$wireValue\""))
        }
    }

    @Test
    fun `onboarding bat keeps native five and two paragraph content with bundled media`() {
        val sourceStory = onboardingBatStory("pet-onboarding")
        val sourceResult = onboardingBatStoryResult(sourceStory, StoryRequestKey)
        val sourceSnapshot = DurableStorySnapshot(
            kind = DurableStoryKind.OnboardingBat,
            story = sourceStory,
            phase = DurableStoryPhase.Result,
            durableRequestKey = StoryRequestKey,
            result = sourceResult,
        )
        val projected = projectDurableStorySnapshotForWeb(
            sourceSnapshot,
            WebDurableStoryOrigin.Dashboard,
            registry(),
        )

        assertEquals(WebDurableStoryKind.OnboardingBat, projected.kind)
        assertEquals(WebDurableStoryOrigin.Dashboard, projected.origin)
        assertEquals(WebOnboardingBatStoryId, projected.story.storyId)
        assertFalse(Json.encodeToString(projected).contains(sourceStory.travelId))
        assertEquals(sourceStory.title, projected.story.title)
        assertEquals(sourceStory.storyText, projected.story.text)
        assertEquals(sourceStory.challenge, projected.story.question)
        assertEquals(sourceStory.choices, projected.story.choices)
        assertEquals(sourceStory.enabledChoice, projected.story.enabledChoice)
        assertEquals(5, projected.story.questionParagraphs.size)
        assertEquals(
            onboardingBatQuestionParagraphs(sourceStory),
            projected.story.questionParagraphs,
        )
        assertEquals("/res/onboarding_bat_situation.png", projected.story.imageRef)
        assertEquals(
            "/assets/media/onboarding-bat-situation.mp4",
            projected.story.videoRef,
        )

        val result = requireNotNull(projected.result)
        assertEquals(sourceResult.requestKey, result.requestKey)
        assertEquals(sourceResult.answer, result.answer)
        assertEquals(sourceResult.text, result.text)
        assertEquals(sourceResult.reaction, result.reaction)
        assertEquals(sourceResult.consequence, result.consequence)
        assertEquals(sourceResult.experienceGained, result.experienceGained)
        assertEquals(2, result.paragraphs.size)
        assertEquals(onboardingBatResultParagraphs(sourceResult), result.paragraphs)
        assertEquals("/res/onboarding_bat_success.png", result.imageRef)
        assertEquals("/assets/media/onboarding-bat-success.mp4", result.videoRef)
        assertTrue(
            runCatching {
                projectDurableStorySnapshotForWeb(
                    sourceSnapshot,
                    WebDurableStoryOrigin.Events,
                    registry(),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `invalid explicit opened-story URLs are null rather than echoed or disguised`() {
        val source = DurableStorySnapshot(
            kind = DurableStoryKind.Scheduled,
            story = InteractiveTravelStory(
                travelId = StoryId,
                title = "История",
                storyText = "Текст",
                challenge = "Вопрос",
                choices = listOf("a", "b", "c", "d"),
                enabledChoice = "",
                imageUrl = "https://evil.example/static/story.png",
                videoUrl = "$BackendOrigin/static/story.mov",
            ),
            phase = DurableStoryPhase.Result,
            result = InteractiveTravelStoryResult(
                travelId = StoryId,
                requestKey = StoryRequestKey,
                answer = "a",
                text = "Итог",
                reaction = "Реакция",
                consequence = "Последствие",
                experienceGained = 1,
                imageUrl = "$BackendOrigin/private/result.png",
                videoUrl = "javascript:alert(1)",
            ),
        )

        val projected = projectDurableStorySnapshotForWeb(
            source,
            WebDurableStoryOrigin.Events,
            registry(),
        )

        assertNull(projected.story.imageRef)
        assertNull(projected.story.videoRef)
        assertNull(requireNotNull(projected.result).imageRef)
        assertNull(requireNotNull(projected.result).videoRef)
        val encoded = Json.encodeToString(projected)
        assertFalse(encoded.contains("evil.example"))
        assertFalse(encoded.contains("javascript:"))
        assertFalse(encoded.contains(BackendOrigin))
    }

    private fun eventSnapshot(
        story: LocalScheduledStory,
        travel: LocalTravelVideoAsset,
        badgeCount: Int = 2,
        lastViewedAt: Long? = null,
    ): EventStorySnapshot = EventStorySnapshot(
        pet = pet(),
        firstSession = null,
        history = eventHistoryUiState(listOf(story), listOf(travel)),
        lastViewedAtEpochMillis = lastViewedAt,
        badgeCount = badgeCount,
    )

    private fun scheduledStory() = LocalScheduledStory(
        ownerId = "owner-secret",
        story = ScheduledStory(
            storyId = StoryId,
            petId = "pet-secret",
            title = "Шорох у старого дерева",
            text = "Тото услышал шорох и заметил, что кому-то нужна помощь.",
            question = "Что ему сделать?",
            choices = listOf("Подойти", "Позвать", "Спрятаться", "Подождать"),
            createdAt = "2026-07-22T10:00:00Z",
            imageUrl = "$BackendOrigin/static/story.png",
            videoUrl = "$BackendOrigin/static/story.mp4",
            selectedChoice = "Подойти",
            result = ScheduledStoryResult(
                text = "Герой помог малышу.",
                reaction = "Спасибо!",
                consequence = "Все снова вместе.",
                experienceGained = 125,
            ),
            resultImageUrl = "$BackendOrigin/static/story-result.webp",
            resultVideoUrl = "$BackendOrigin/static/story-result.mp4",
        ),
        choiceRequestKey = "choice-request-secret",
    )

    private fun travelVideo() = LocalTravelVideoAsset(
        ownerId = "owner-secret",
        petId = "pet-secret",
        requestKey = TravelRequestKey,
        backendJobId = "backend-job-secret",
        prompt = "Хочу увидеть море",
        title = "Путешествие к маяку",
        scenario = "Тото встречает рассвет у воды",
        imageUrl = "$BackendOrigin/static/travel.png",
        videoUrl = "$BackendOrigin/static/travel.mp4",
        completedAtEpochMillis = 100L,
        consumedAtEpochMillis = 101L,
    )

    private fun pet() = PetDashboardState(
        petId = "pet-secret",
        assetSetId = "asset-secret",
        description = "description",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 100,
        hunger = 50,
        happiness = 60,
        energy = 70,
        message = "Привет",
    )

    private fun registry(
        materializer: WebMediaMaterializer = RecordingTestMediaMaterializer(),
    ) = WebMediaReferenceRegistry(
        StaticMediaUrlPolicy("$BackendOrigin/", false),
        materializer = materializer,
        scopeProvider = { WebMediaOwnerScope("owner-secret", "pet-secret") },
    )

    private companion object {
        const val BackendOrigin = "https://gigagochi.serega.works"
        const val WebOrigin = "https://appassets.androidplatform.net"
        const val StoryId = "android-story-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val TravelRequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val StoryRequestKey = "223e4567-e89b-42d3-a456-426614174000"
    }
}
