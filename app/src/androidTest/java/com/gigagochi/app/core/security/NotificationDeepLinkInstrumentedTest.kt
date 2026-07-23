package com.gigagochi.app.core.security

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.GigagochiWebViewActivity
import com.gigagochi.app.notificationDeepLinkExtras
import com.gigagochi.app.core.database.GigagochiDatabase
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.ScheduledStory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationDeepLinkInstrumentedTest {
    private lateinit var database: GigagochiDatabase
    private lateinit var repository: PetLocalRepository
    private lateinit var resolver: NotificationDeepLinkResolver

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(
            context,
            GigagochiDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = PetLocalRepository(database) { Now }
        resolver = NotificationDeepLinkResolver(
            ownerId = OwnerId,
            activePetId = PetId,
            store = PetLocalRepositoryNotificationDeepLinkStore(repository),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun coldAndWarmIntentsAreValidatedThenResolvedOnlyAgainstOwnedRoomRows() = runBlocking {
        val story = LocalScheduledStory(
            ownerId = OwnerId,
            story = ScheduledStory(
                storyId = StoryId,
                petId = PetId,
                title = "Шорох у дерева",
                text = "Питомец услышал странный звук.",
                question = "Что делать?",
                choices = listOf("Подойти", "Позвать", "Спрятаться", "Подождать"),
                createdAt = "2026-07-23T10:00:00Z",
                imageUrl = null,
                videoUrl = null,
            ),
        )
        val travel = readyTravel()
        assertTrue(repository.saveScheduledStory(story))
        repository.saveTravelVideoAsset(travel)

        val coldIntent = notificationIntent().apply {
            putExtra(NotificationStoryIdExtra, StoryId)
            putExtra(NotificationTravelRequestKeyExtra, RequestKey)
        }
        val coldExtras = requireNotNull(notificationDeepLinkExtras(coldIntent))
        assertEquals(
            ParsedNotificationDeepLink.Story(StoryId),
            parseNotificationDeepLink(coldExtras),
        )
        assertEquals(
            NotificationDeepLinkDestination.Story(story),
            resolver.resolve(coldExtras),
        )

        val warmIntent = notificationIntent().apply {
            putExtra(NotificationTravelRequestKeyExtra, RequestKey)
        }
        val warmExtras = requireNotNull(notificationDeepLinkExtras(warmIntent))
        assertEquals(
            ParsedNotificationDeepLink.Travel(RequestKey),
            parseNotificationDeepLink(warmExtras),
        )
        assertEquals(
            NotificationDeepLinkDestination.Events(travel),
            resolver.resolve(warmExtras),
        )

        val packageManager = InstrumentationRegistry.getInstrumentation()
            .targetContext.packageManager
        val activityInfo = packageManager.getActivityInfo(
            ComponentName(
                InstrumentationRegistry.getInstrumentation().targetContext,
                GigagochiWebViewActivity::class.java,
            ),
            0,
        )
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, activityInfo.launchMode)
        assertTrue(activityInfo.exported)
    }

    @Test
    fun malformedStaleForeignAndNotReadyIntentTargetsFailClosedToDashboard() = runBlocking {
        val notReadyTravel = readyTravel().copy(consumedAtEpochMillis = null)
        repository.saveTravelVideoAsset(notReadyTravel)
        val presentNull = Bundle().apply {
            putString(NotificationStoryIdExtra, null)
        }
        val malformedIntents = listOf(
            notificationIntent().putExtra(NotificationStoryIdExtra, 7),
            notificationIntent().putExtra(NotificationStoryIdExtra, " $StoryId"),
            notificationIntent().putExtra(
                NotificationTravelRequestKeyExtra,
                RequestKey.uppercase(),
            ),
            notificationIntent().putExtras(presentNull),
        )

        malformedIntents.forEach { intent ->
            val extras = requireNotNull(notificationDeepLinkExtras(intent))
            assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(extras))
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver.resolve(extras),
            )
        }

        val staleStory = requireNotNull(
            notificationDeepLinkExtras(
                notificationIntent().putExtra(NotificationStoryIdExtra, StoryId),
            ),
        )
        val notReady = requireNotNull(
            notificationDeepLinkExtras(
                notificationIntent().putExtra(NotificationTravelRequestKeyExtra, RequestKey),
            ),
        )
        assertSame(NotificationDeepLinkDestination.Dashboard, resolver.resolve(staleStory))
        assertSame(NotificationDeepLinkDestination.Dashboard, resolver.resolve(notReady))

        val readyTravel = readyTravel().copy(requestKey = OtherRequestKey)
        repository.saveTravelVideoAsset(readyTravel)
        val ready = requireNotNull(
            notificationDeepLinkExtras(
                notificationIntent().putExtra(
                    NotificationTravelRequestKeyExtra,
                    OtherRequestKey,
                ),
            ),
        )
        assertEquals(
            NotificationDeepLinkDestination.Events(readyTravel),
            resolver.resolve(ready),
        )

        val noSupportedExtras = notificationDeepLinkExtras(notificationIntent())
        assertNull(noSupportedExtras)
    }

    private fun notificationIntent() = Intent(
        InstrumentationRegistry.getInstrumentation().targetContext,
        GigagochiWebViewActivity::class.java,
    )

    private fun readyTravel() = LocalTravelVideoAsset(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = RequestKey,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Полететь к морю",
        title = "Море",
        scenario = null,
        imageUrl = null,
        videoUrl = "https://gigagochi.test/static/travel.mp4",
        completedAtEpochMillis = Now - 1L,
        consumedAtEpochMillis = Now,
    )

    private companion object {
        const val OwnerId = "owner-deeplink-qa"
        const val PetId = "pet-deeplink-qa"
        const val StoryId = "android-story-deeplink-qa-000000000001"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val OtherRequestKey = "223e4567-e89b-42d3-a456-426614174000"
        const val Now = 1_900_000_000_000L
    }
}
