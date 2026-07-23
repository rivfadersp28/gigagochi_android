package com.gigagochi.app.core.security

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.ScheduledStory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NotificationDeepLinkResolverTest {
    @Test
    fun `untyped Android extras preserve absence wrong type and unreadable values`() {
        assertSame(
            null,
            notificationDeepLinkExtrasFromUntypedPresence(),
        )
        val wrongStoryType = requireNotNull(
            notificationDeepLinkExtrasFromUntypedPresence(
                storyIdPresent = true,
                storyIdValue = 7,
                travelRequestKeyPresent = true,
                travelRequestKeyValue = RequestKey,
            ),
        )
        val unreadableTravel = requireNotNull(
            notificationDeepLinkExtrasFromUntypedPresence(
                travelRequestKeyPresent = true,
                travelRequestKeyValue = RequestKey,
                travelRequestKeyReadable = false,
            ),
        )

        assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(wrongStoryType))
        assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(unreadableTravel))
        assertEquals(
            ParsedNotificationDeepLink.Travel(RequestKey),
            parseNotificationDeepLink(
                requireNotNull(
                    notificationDeepLinkExtrasFromUntypedPresence(
                        travelRequestKeyPresent = true,
                        travelRequestKeyValue = RequestKey,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `present null story keeps priority over valid travel and routes to dashboard`() =
        runBlocking {
            val store = FakeStore(travelResult = travel())
            val extras = NotificationDeepLinkExtras.fromPresence(
                storyIdPresent = true,
                storyId = null,
                travelRequestKeyPresent = true,
                travelRequestKey = RequestKey,
            )

            assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(extras))
            assertSame(NotificationDeepLinkDestination.Dashboard, resolver(store).resolve(extras))
            assertEquals(0, store.storyCalls)
            assertEquals(0, store.travelCalls)
        }

    @Test
    fun `wrong type story is invalid without storage access or travel fallback`() = runBlocking {
        val store = FakeStore(travelResult = travel())
        val extras = NotificationDeepLinkExtras.fromPresence(
            storyIdPresent = true,
            storyIdTypeValid = false,
            travelRequestKeyPresent = true,
            travelRequestKey = RequestKey,
        )

        assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(extras))
        assertSame(NotificationDeepLinkDestination.Dashboard, resolver(store).resolve(extras))
        assertEquals(0, store.storyCalls)
        assertEquals(0, store.travelCalls)
    }

    @Test
    fun `absent story allows valid present travel`() = runBlocking {
        val item = travel()
        val store = FakeStore(travelResult = item)
        val extras = NotificationDeepLinkExtras.fromPresence(
            storyIdPresent = false,
            travelRequestKeyPresent = true,
            travelRequestKey = RequestKey,
        )

        assertEquals(
            ParsedNotificationDeepLink.Travel(RequestKey),
            parseNotificationDeepLink(extras),
        )
        assertEquals(
            NotificationDeepLinkDestination.Events(item),
            resolver(store).resolve(extras),
        )
        assertEquals(0, store.storyCalls)
        assertEquals(1, store.travelCalls)
    }

    @Test
    fun `both absent extras parse as none`() = runBlocking {
        val store = FakeStore()
        val extras = NotificationDeepLinkExtras.fromPresence()

        assertSame(ParsedNotificationDeepLink.None, parseNotificationDeepLink(extras))
        assertSame(NotificationDeepLinkDestination.Dashboard, resolver(store).resolve(extras))
        assertEquals(0, store.storyCalls)
        assertEquals(0, store.travelCalls)
    }

    @Test
    fun `present malformed travel never becomes none or reaches storage`() = runBlocking {
        val malformed = listOf(
            NotificationDeepLinkExtras.fromPresence(
                travelRequestKeyPresent = true,
                travelRequestKey = null,
            ),
            NotificationDeepLinkExtras.fromPresence(
                travelRequestKeyPresent = true,
                travelRequestKeyTypeValid = false,
            ),
        )

        malformed.forEach { extras ->
            val store = FakeStore(travelResult = travel())
            assertSame(ParsedNotificationDeepLink.Invalid, parseNotificationDeepLink(extras))
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver(store).resolve(extras),
            )
            assertEquals(0, store.storyCalls)
            assertEquals(0, store.travelCalls)
        }
    }

    @Test
    fun `story has priority when both notification extras are present`() = runBlocking {
        val item = story()
        val store = FakeStore(storyResult = item, travelResult = travel())

        val destination = resolver(store).resolve(extras(storyId = StoryId, travelKey = RequestKey))

        assertEquals(NotificationDeepLinkDestination.Story(item), destination)
        assertEquals(1, store.storyCalls)
        assertEquals(0, store.travelCalls)
    }

    @Test
    fun `invalid selected story cannot fall through to a travel extra`() = runBlocking {
        val store = FakeStore(travelResult = travel())
        val malformedStories = listOf("", " $StoryId", "x".repeat(97), "story\nforged")

        malformedStories.forEach { malformed ->
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver(store).resolve(extras(storyId = malformed, travelKey = RequestKey)),
            )
        }
        assertEquals(0, store.storyCalls)
        assertEquals(0, store.travelCalls)
    }

    @Test
    fun `forged travel UUID routes to dashboard without storage access`() = runBlocking {
        val store = FakeStore(travelResult = travel())
        val forgedKeys = listOf(
            RequestKey.uppercase(),
            "123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-42d3-7456-426614174000",
            "$RequestKey ",
        )

        forgedKeys.forEach { forged ->
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver(store).resolve(extras(travelKey = forged)),
            )
        }
        assertEquals(0, store.travelCalls)
    }

    @Test
    fun `missing stale story and foreign story route to dashboard`() = runBlocking {
        val candidates = listOf<LocalScheduledStory?>(
            null,
            story().copy(ownerId = "owner-foreign"),
            story(storyId = "android-story-${"b".repeat(32)}"),
            story(petId = "pet-foreign"),
        )

        candidates.forEach { candidate ->
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver(FakeStore(storyResult = candidate)).resolve(extras(storyId = StoryId)),
            )
        }
    }

    @Test
    fun `valid story lookup is scoped to current owner and active pet`() = runBlocking {
        val item = story()
        val store = FakeStore(storyResult = item)

        val destination = resolver(store).resolve(extras(storyId = StoryId))

        assertEquals(NotificationDeepLinkDestination.Story(item), destination)
        assertEquals(Triple(OwnerId, PetId, StoryId), store.lastStoryLookup)
    }

    @Test
    fun `missing stale foreign or not-ready travel routes to dashboard`() = runBlocking {
        val candidates = listOf<LocalTravelVideoAsset?>(
            null,
            travel().copy(ownerId = "owner-foreign"),
            travel().copy(petId = "pet-foreign"),
            travel().copy(requestKey = OtherRequestKey),
            travel().copy(consumedAtEpochMillis = null),
            travel().copy(videoUrl = ""),
        )

        candidates.forEach { candidate ->
            assertSame(
                NotificationDeepLinkDestination.Dashboard,
                resolver(FakeStore(travelResult = candidate)).resolve(extras(travelKey = RequestKey)),
            )
        }
    }

    @Test
    fun `valid travel lookup routes to events and is scoped to current pet`() = runBlocking {
        val item = travel()
        val store = FakeStore(travelResult = item)

        val destination = resolver(store).resolve(extras(travelKey = RequestKey))

        assertEquals(NotificationDeepLinkDestination.Events(item), destination)
        assertEquals(Triple(OwnerId, PetId, RequestKey), store.lastTravelLookup)
    }

    @Test
    fun `notification without supported extras routes to dashboard`() = runBlocking {
        assertSame(
            NotificationDeepLinkDestination.Dashboard,
            resolver(FakeStore()).resolve(extras()),
        )
    }

    private fun resolver(store: FakeStore) = NotificationDeepLinkResolver(
        ownerId = OwnerId,
        activePetId = PetId,
        store = store,
    )

    private fun extras(
        storyId: String? = null,
        travelKey: String? = null,
    ) = NotificationDeepLinkExtras(storyId, travelKey)

    private fun story(
        storyId: String = StoryId,
        petId: String = PetId,
    ) = LocalScheduledStory(
        ownerId = OwnerId,
        story = ScheduledStory(
            storyId = storyId,
            petId = petId,
            title = "Событие",
            text = "Питомцу нужна помощь",
            question = "Что делать?",
            choices = listOf("a", "b", "c", "d"),
            createdAt = "2026-07-22T10:00:00Z",
            imageUrl = null,
            videoUrl = null,
        ),
    )

    private fun travel() = LocalTravelVideoAsset(
        ownerId = OwnerId,
        petId = PetId,
        requestKey = RequestKey,
        backendJobId = "travel-video-prototype-${"a".repeat(32)}",
        prompt = "Полететь к морю",
        title = "Море",
        scenario = null,
        imageUrl = null,
        videoUrl = "https://gigagochi.test/static/travel.mp4",
        completedAtEpochMillis = 100L,
        consumedAtEpochMillis = 101L,
    )

    private class FakeStore(
        private val storyResult: LocalScheduledStory? = null,
        private val travelResult: LocalTravelVideoAsset? = null,
    ) : ScopedNotificationDeepLinkStore {
        var storyCalls = 0
        var travelCalls = 0
        var lastStoryLookup: Triple<String, String, String>? = null
        var lastTravelLookup: Triple<String, String, String>? = null

        override suspend fun findStory(
            ownerId: String,
            petId: String,
            storyId: String,
        ): LocalScheduledStory? {
            storyCalls += 1
            lastStoryLookup = Triple(ownerId, petId, storyId)
            return storyResult
        }

        override suspend fun findTravelVideo(
            ownerId: String,
            petId: String,
            requestKey: String,
        ): LocalTravelVideoAsset? {
            travelCalls += 1
            lastTravelLookup = Triple(ownerId, petId, requestKey)
            return travelResult
        }
    }

    private companion object {
        const val OwnerId = "owner-current"
        const val PetId = "pet-current"
        const val StoryId = "android-story-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val OtherRequestKey = "223e4567-e89b-42d3-a456-426614174000"
    }
}
