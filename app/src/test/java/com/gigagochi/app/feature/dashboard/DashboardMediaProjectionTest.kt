package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.LocalTravelVideoAsset
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
            mood = "happy",
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
            projectDashboardMedia(pet, null, resolve, false),
        )
    }

    @Test
    fun consumedTravelOverridesPetButForeignOrUnconsumedTravelDoesNot() {
        val pet = pet().copy(
            generatedMedia = PetGeneratedMedia(videoUrl = "https://safe/pet.mp4"),
        )
        val travel = LocalTravelVideoAsset(
            "owner", pet.petId, "request", "job", "prompt", null, null,
            "https://safe/travel.jpg", "https://safe/travel.mp4", 1, 2,
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo(
                "https://safe/travel.mp4",
                "https://safe/travel.jpg",
            ),
            projectDashboardMedia(pet, travel, resolve, false),
        )
        assertEquals(
            DashboardMediaProjection.RemoteVideo("https://safe/pet.mp4", null),
            projectDashboardMedia(pet, travel.copy(petId = "foreign"), resolve, false),
        )
    }

    @Test
    fun unsafeOrMissingRemoteMediaFallsBackOnlyToFixtureProjection() {
        val unsafe = pet().copy(
            generatedMedia = PetGeneratedMedia(videoUrl = "https://evil/media.mp4"),
        )
        assertEquals(DashboardMediaProjection.Fixture, projectDashboardMedia(unsafe, null, resolve, false))
        assertEquals(DashboardMediaProjection.Fixture, projectDashboardMedia(pet(), null, resolve, true))
    }

    private fun pet() = PetDashboardState(
        "pet", "asset", "description", "name", "baby", "Малыш", "idle",
        0, 100, 100, 100, "message", false,
    )
}
