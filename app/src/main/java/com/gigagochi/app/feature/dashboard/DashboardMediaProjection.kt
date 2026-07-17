package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.PetDashboardState

sealed interface DashboardMediaProjection {
    data class RemoteVideo(val videoUrl: String, val posterUrl: String?) : DashboardMediaProjection
    data class RemotePoster(val posterUrl: String) : DashboardMediaProjection
    data object Fixture : DashboardMediaProjection
}

fun projectDashboardMedia(
    pet: PetDashboardState,
    travelPresentation: LocalTravelVideoAsset?,
    resolveUrl: (String?) -> String?,
    fixtureOnly: Boolean,
): DashboardMediaProjection {
    if (fixtureOnly) return DashboardMediaProjection.Fixture
    if (travelPresentation?.petId == pet.petId && travelPresentation.consumedAtEpochMillis != null) {
        val video = resolveUrl(travelPresentation.videoUrl)
        val poster = resolveUrl(travelPresentation.imageUrl)
        if (video != null) return DashboardMediaProjection.RemoteVideo(video, poster)
        if (poster != null) return DashboardMediaProjection.RemotePoster(poster)
    }
    val exactMood = pet.generatedMedia.moodImages.singleOrNull {
        it.stage == pet.stage && it.mood == pet.mood
    }?.url
    val idleMood = pet.generatedMedia.moodImages.singleOrNull {
        it.stage == pet.stage && it.mood == "idle"
    }?.url
    val poster = resolveUrl(exactMood) ?: resolveUrl(idleMood) ?: resolveUrl(pet.generatedMedia.blinkImageUrl)
        ?: resolveUrl(pet.generatedMedia.spriteSheetUrl)
    val moodVideo = when (pet.mood) {
        "sad" -> pet.generatedMedia.sadVideoUrl
        "happy" -> pet.generatedMedia.happyVideoUrl
        else -> pet.generatedMedia.videoUrl
    }
    val video = resolveUrl(moodVideo) ?: resolveUrl(pet.generatedMedia.videoUrl)
    return when {
        video != null -> DashboardMediaProjection.RemoteVideo(video, poster)
        poster != null -> DashboardMediaProjection.RemotePoster(poster)
        else -> DashboardMediaProjection.Fixture
    }
}
