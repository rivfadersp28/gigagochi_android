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

        val mediaLeft = composeRule.onNodeWithContentDescription("Видео события: Шорох у старого дерева")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val helpLeft = composeRule.onNodeWithContentDescription("Помочь")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        assertTrue("help left=$helpLeft media left=$mediaLeft", abs(helpLeft - mediaLeft) <= 3f)
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
