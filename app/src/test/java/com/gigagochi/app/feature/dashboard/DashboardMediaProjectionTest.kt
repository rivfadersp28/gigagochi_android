package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardMediaProjectionTest {
    private val resolve: (String?) -> String? = { it?.takeIf { value -> value.startsWith("https://safe/") } }

    @Test
    fun exactAgeMoodSelectsMoodVideoAndPosterBeforeIdle() {
        val pet = pet().copy(
            generatedMedia = PetGeneratedMedia(
                videoUrl = "https://safe/normal.mp4",
                happyVideoUrl = "https://safe/happy.mp4",
                moodImages = listOf(
                    PetMoodImage("baby", "idle", "https://safe/idle.jpg"),
                    PetMoodImage("baby", "happy", "https://safe/happy.jpg"),
                    PetMoodImage("adult", "happy", "https://safe/adult.jpg"),
                ),
            ),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo(
                "https://safe/happy.mp4",
                "https://safe/happy.jpg",
            ),
            projectDashboardMedia(pet, resolve, false),
        )
    }

    @Test
    fun statThresholdsSelectSadHappyAndNormalWithSadPriority() {
        val media = PetGeneratedMedia(
            videoUrl = "https://safe/normal.mp4",
            sadVideoUrl = "https://safe/sad.mp4",
            happyVideoUrl = "https://safe/happy.mp4",
            moodImages = listOf(
                PetMoodImage("baby", "idle", "https://safe/idle.jpg"),
                PetMoodImage("baby", "sad", "https://safe/sad.jpg"),
                PetMoodImage("baby", "happy", "https://safe/happy.jpg"),
            ),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/sad.mp4", "https://safe/sad.jpg"),
            projectDashboardMedia(pet().copy(hunger = 29, generatedMedia = media), resolve, false),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/normal.mp4", "https://safe/idle.jpg"),
            projectDashboardMedia(
                pet().copy(hunger = 30, happiness = 69, energy = 100, generatedMedia = media),
                resolve,
                false,
            ),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/happy.mp4", "https://safe/happy.jpg"),
            projectDashboardMedia(
                pet().copy(hunger = 70, happiness = 70, energy = 70, generatedMedia = media),
                resolve,
                false,
            ),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/sad.mp4", "https://safe/sad.jpg"),
            projectDashboardMedia(
                pet().copy(hunger = 100, happiness = 100, energy = 29, generatedMedia = media),
                resolve,
                false,
            ),
        )
    }

    @Test
    fun incompleteDerivedMediaFallsBackToNormal() {
        val media = PetGeneratedMedia(
            videoUrl = "https://safe/normal.mp4",
            happyVideoUrl = null,
            moodImages = listOf(
                PetMoodImage("baby", "idle", "https://safe/idle.jpg"),
                PetMoodImage("baby", "happy", "https://safe/happy.jpg"),
            ),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/normal.mp4", "https://safe/idle.jpg"),
            projectDashboardMedia(pet().copy(generatedMedia = media), resolve, false),
        )
    }

    @Test
    fun dashboardProjectionUsesOnlyPetMedia() {
        val pet = pet().copy(
            generatedMedia = PetGeneratedMedia(videoUrl = "https://safe/pet.mp4"),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/pet.mp4", null),
            projectDashboardMedia(pet, resolve, false),
        )
    }

    @Test
    fun unsafeOrMissingRemoteMediaFallsBackOnlyToFixtureProjection() {
        val unsafe = pet().copy(
            generatedMedia = PetGeneratedMedia(videoUrl = "https://evil/media.mp4"),
        )
        assertEquals(DashboardMediaProjection.Fixture, projectDashboardMedia(unsafe, resolve, false))
        assertEquals(DashboardMediaProjection.Fixture, projectDashboardMedia(pet(), resolve, true))
    }

    private fun pet() = PetDashboardState(
        "pet", "asset", "description", "name", "baby", "Малыш", "idle",
        0, 100, 100, 100, "message",
    )
}
