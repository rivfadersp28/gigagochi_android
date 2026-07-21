package com.gigagochi.app.feature.events

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EventHistoryScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nativeBackIsVisibleAndHelpButtonMatchesPaperLeftLane() {
        assertEquals(86.dp, EventCardSpacing)
        var backCalls = 0
        composeRule.setContent {
            GigagochiTheme {
                EventHistoryScreen(
                    stories = listOf(activeStory()),
                    mediaUrlPolicy = StaticMediaUrlPolicy("https://gigagochi.test", true),
                    onHelp = {},
                    onBack = { backCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Назад")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, backCalls)

        val backBottom = composeRule.onNodeWithContentDescription("Назад")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val titleTop = composeRule.onNodeWithText("События")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue("title top=$titleTop back bottom=$backBottom", titleTop - backBottom >= 18f)

        val mediaBounds = composeRule.onNodeWithContentDescription("Видео события: Шорох у старого дерева")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val mediaLeft = mediaBounds.left
        val helpLeft = composeRule.onNodeWithContentDescription("Помочь")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val storyTextLeft = composeRule
            .onNodeWithText("Тото услышал шорох и заметил, что кому-то нужна помощь.")
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val expectedLeft = with(composeRule.density) { EventScreenHorizontalPadding.toPx() }
        val screenRight = composeRule.onNodeWithContentDescription("История событий")
            .fetchSemanticsNode()
            .boundsInRoot
            .right
        assertTrue("media left=$mediaLeft expected=$expectedLeft", abs(mediaLeft - expectedLeft) <= 1f)
        assertTrue(
            "media right=${mediaBounds.right} expected=${screenRight - expectedLeft}",
            abs(mediaBounds.right - (screenRight - expectedLeft)) <= 1f,
        )
        assertTrue(
            "help left=$helpLeft media left=$mediaLeft",
            abs(helpLeft - mediaLeft) <= 3f * composeRule.density.density,
        )
        assertTrue(
            "story text left=$storyTextLeft media left=$mediaLeft",
            abs(storyTextLeft - mediaLeft) <= 1f,
        )
    }

    @Test
    fun experienceRewardUsesTheSameLeftLaneAsResultText() {
        composeRule.setContent {
            GigagochiTheme {
                EventHistoryScreen(
                    stories = listOf(answeredStory()),
                    mediaUrlPolicy = StaticMediaUrlPolicy("https://gigagochi.test", true),
                    onHelp = {},
                    onBack = {},
                )
            }
        }

        val resultTextLeft = composeRule.onNodeWithText("Всё получилось")
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val rewardLeft = composeRule.onNodeWithContentDescription("Получено 125 монет")
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .left

        assertTrue(
            "reward left=$rewardLeft result text left=$resultTextLeft",
            abs(rewardLeft - resultTextLeft) <= 1f,
        )
    }

    @Test
    fun travelVideoKeepsNineBySixteenRatioAndOpensShareAction() {
        var shareCalls = 0
        composeRule.setContent {
            GigagochiTheme {
                EventHistoryScreen(
                    stories = emptyList(),
                    travelVideos = listOf(travelVideo()),
                    mediaUrlPolicy = StaticMediaUrlPolicy("https://gigagochi.test", true),
                    travelVideoSharer = TravelVideoSharer {
                        shareCalls += 1
                        TravelVideoShareResult.Opened
                    },
                    onHelp = {},
                    onBack = {},
                )
            }
        }

        val mediaBounds = composeRule
            .onNodeWithContentDescription("Видео путешествия: Путешествие к маяку")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "video bounds=$mediaBounds",
            abs(mediaBounds.height / mediaBounds.width - 16f / 9f) < .03f,
        )
        composeRule.onNodeWithContentDescription("Поделиться видео")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitUntil { shareCalls == 1 }
    }

    @Test
    fun notificationFocusScrollsToRequestedTravelVideo() {
        val newest = travelVideo(
            requestKey = "123e4567-e89b-42d3-a456-426614174001",
            title = "Новое путешествие",
            completedAt = 20L,
        )
        val requested = travelVideo(
            requestKey = "123e4567-e89b-42d3-a456-426614174002",
            title = "Нужное путешествие",
            completedAt = 10L,
        )
        composeRule.setContent {
            GigagochiTheme {
                EventHistoryScreen(
                    stories = emptyList(),
                    travelVideos = listOf(newest, requested),
                    mediaUrlPolicy = StaticMediaUrlPolicy("https://gigagochi.test", true),
                    initialFocusTravelRequestKey = requested.requestKey,
                    onHelp = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Видео путешествия: Нужное путешествие")
            .assertIsDisplayed()
    }

    private fun activeStory() = LocalScheduledStory(
        ownerId = "owner-a",
        story = ScheduledStory(
            storyId = "android-story-active-fixture-000000000",
            petId = "pet-a",
            title = "Шорох у старого дерева",
            text = "Тото услышал шорох и заметил, что кому-то нужна помощь.",
            question = "Что ему сделать?",
            choices = listOf("Подойти", "Позвать", "Спрятаться", "Подождать"),
            createdAt = "2026-07-19T10:00:00Z",
            imageUrl = null,
            videoUrl = null,
        ),
    )

    private fun answeredStory() = activeStory().copy(
        story = activeStory().story.copy(
            selectedChoice = "Подойти",
            result = ScheduledStoryResult(
                text = "Герой помог",
                reaction = "Спасибо",
                consequence = "Всё получилось",
                experienceGained = 125,
            ),
        ),
        choiceRequestKey = "123e4567-e89b-42d3-a456-426614174000",
    )

    private fun travelVideo(
        requestKey: String = "123e4567-e89b-42d3-a456-426614174000",
        title: String = "Путешествие к маяку",
        completedAt: Long = 10L,
    ) = LocalTravelVideoAsset(
        ownerId = "owner-a",
        petId = "pet-a",
        requestKey = requestKey,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Хочу увидеть море",
        title = title,
        scenario = null,
        imageUrl = null,
        videoUrl = "https://gigagochi.test/static/travel.mp4",
        completedAtEpochMillis = completedAt,
        consumedAtEpochMillis = completedAt + 1,
    )
}
