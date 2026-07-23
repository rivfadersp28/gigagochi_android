package com.gigagochi.app.core.security

import com.gigagochi.app.core.database.LocalTravelVideoAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TravelVideoShareResolverTest {
    @Test
    fun `forged or noncanonical request keys are invalid without storage access`() = runBlocking {
        var calls = 0
        val resolver = resolver { _, _, _ ->
            calls += 1
            readyAsset()
        }
        val forgedKeys = listOf(
            null,
            "",
            " $RequestKey",
            RequestKey.uppercase(),
            "123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-42d3-7456-426614174000",
            "123e4567-e89b-42d3-a456-42661417400z",
        )

        forgedKeys.forEach { forged ->
            assertSame(TravelVideoShareLookupResult.Invalid, resolver.resolve(forged))
        }
        assertEquals(0, calls)
    }

    @Test
    fun `lookup is always scoped to current native owner and active pet`() = runBlocking {
        var captured: Triple<String, String, String>? = null
        val resolver = TravelVideoShareResolver(
            ownerId = OwnerId,
            activePetId = PetId,
            store = ScopedTravelVideoAssetStore { ownerId, petId, requestKey ->
                captured = Triple(ownerId, petId, requestKey)
                readyAsset()
            },
        )

        val result = resolver.resolve(RequestKey)

        assertEquals(Triple(OwnerId, PetId, RequestKey), captured)
        assertEquals(TravelVideoShareLookupResult.Ready(readyAsset()), result)
    }

    @Test
    fun `missing and foreign local assets do not leak through share`() = runBlocking {
        assertSame(
            TravelVideoShareLookupResult.Missing,
            resolver { _, _, _ -> null }.resolve(RequestKey),
        )
        listOf(
            readyAsset().copy(ownerId = "owner-foreign"),
            readyAsset().copy(petId = "pet-foreign"),
            readyAsset().copy(requestKey = OtherRequestKey),
        ).forEach { forgedAsset ->
            assertSame(
                TravelVideoShareLookupResult.Missing,
                resolver { _, _, _ -> forgedAsset }.resolve(RequestKey),
            )
        }
    }

    @Test
    fun `unconsumed asset or missing video is not ready`() = runBlocking {
        assertSame(
            TravelVideoShareLookupResult.NotReady,
            resolver { _, _, _ -> readyAsset().copy(consumedAtEpochMillis = null) }
                .resolve(RequestKey),
        )
        assertSame(
            TravelVideoShareLookupResult.NotReady,
            resolver { _, _, _ -> readyAsset().copy(videoUrl = "  ") }.resolve(RequestKey),
        )
    }

    @Test
    fun `consumed owned asset with video is ready`() = runBlocking {
        val asset = readyAsset()

        assertEquals(
            TravelVideoShareLookupResult.Ready(asset),
            resolver { _, _, _ -> asset }.resolve(RequestKey),
        )
    }

    private fun resolver(
        find: suspend (String, String, String) -> LocalTravelVideoAsset?,
    ) = TravelVideoShareResolver(
        ownerId = OwnerId,
        activePetId = PetId,
        store = ScopedTravelVideoAssetStore(find),
    )

    private fun readyAsset() = LocalTravelVideoAsset(
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

    private companion object {
        const val OwnerId = "owner-current"
        const val PetId = "pet-current"
        const val RequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val OtherRequestKey = "223e4567-e89b-42d3-a456-426614174000"
    }
}
