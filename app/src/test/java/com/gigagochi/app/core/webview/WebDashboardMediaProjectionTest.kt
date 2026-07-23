package com.gigagochi.app.core.webview

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDashboardMediaProjectionTest {
    @Test
    fun nativeMoodProjectionSelectsHappyMediaButWebReceivesOpaqueReferences() {
        val materializer = RecordingTestMediaMaterializer()
        val registry = WebMediaReferenceRegistry(Policy, materializer = materializer)
        val pet = pet().copy(
            hunger = 70,
            happiness = 70,
            energy = 70,
            generatedMedia = PetGeneratedMedia(
                videoUrl = "$BackendOrigin/static/idle.mp4",
                happyVideoUrl = "$BackendOrigin/static/happy.mp4?v=2",
                moodImages = listOf(
                    PetMoodImage("baby", "idle", "$BackendOrigin/static/idle.png"),
                    PetMoodImage("baby", "happy", "$BackendOrigin/static/happy.webp?v=2"),
                ),
            ),
        )

        val projected = projectDashboardWebMedia(pet, registry)

        assertTrue(requireNotNull(projected.videoRef).startsWith("/media/v1/"))
        assertTrue(requireNotNull(projected.posterRef).startsWith("/media/v1/"))
        assertEquals("video/mp4", registry.resolveRequest(
            "$GigagochiWebOrigin${projected.videoRef}",
        )?.media?.mimeType)
        assertEquals("image/webp", registry.resolveRequest(
            "$GigagochiWebOrigin${projected.posterRef}",
        )?.media?.mimeType)
        assertEquals(
            listOf(
                "$BackendOrigin/static/happy.mp4?v=2",
                "$BackendOrigin/static/happy.webp?v=2",
            ),
            materializer.sources,
        )
        assertNull(projected.sadVideoRef)
        assertNull(projected.happyVideoRef)
    }

    @Test
    fun unsupportedOrUnsafeRemoteMediaFallsBackToTheExistingLocalFixture() {
        val registry = WebMediaReferenceRegistry(Policy)
        val unsupported = pet().copy(
            generatedMedia = PetGeneratedMedia(
                videoUrl = "$BackendOrigin/static/pet.mov",
                moodImages = listOf(
                    PetMoodImage("baby", "idle", "https://evil.example/static/pet.png"),
                ),
            ),
        )

        val projected = projectDashboardWebMedia(unsupported, registry)

        assertEquals("/assets/media/openai-normal.mp4", projected.videoRef)
        assertNull(projected.posterRef)
    }

    private fun pet() = PetDashboardState(
        petId = "pet",
        assetSetId = "asset",
        description = "description",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 0,
        hunger = 50,
        happiness = 50,
        energy = 50,
        message = "message",
    )

    private companion object {
        const val BackendOrigin = "https://gigagochi.serega.works"
        val Policy = StaticMediaUrlPolicy("$BackendOrigin/", false)
    }
}
