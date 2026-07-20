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
    val requestedMood = when {
        pet.hunger < 30 || pet.happiness < 30 || pet.energy < 30 -> "sad"
        pet.hunger >= 70 && pet.happiness >= 70 && pet.energy >= 70 -> "happy"
        else -> "idle"
    }
    val idleMood = pet.generatedMedia.moodImages.singleOrNull {
        it.stage == pet.stage && it.mood == "idle"
    }?.url
    val idlePoster = resolveUrl(idleMood) ?: resolveUrl(pet.generatedMedia.blinkImageUrl)
        ?: resolveUrl(pet.generatedMedia.spriteSheetUrl)
    val requestedPoster = pet.generatedMedia.moodImages.singleOrNull {
        it.stage == pet.stage && it.mood == requestedMood
    }?.url?.let(resolveUrl)
    val normalVideo = resolveUrl(pet.generatedMedia.videoUrl)
    val requestedVideo = when (requestedMood) {
        "sad" -> pet.generatedMedia.sadVideoUrl
        "happy" -> pet.generatedMedia.happyVideoUrl
        else -> pet.generatedMedia.videoUrl
    }?.let(resolveUrl)
    val derivedReady = requestedMood == "idle" || (
        requestedPoster != null &&
            requestedPoster != idlePoster &&
            (requestedVideo != null || normalVideo == null)
        )
    val poster = if (derivedReady) requestedPoster ?: idlePoster else idlePoster
    val video = if (derivedReady) requestedVideo ?: normalVideo else normalVideo
    return when {
        video != null -> DashboardMediaProjection.RemoteVideo(video, poster)
        poster != null -> DashboardMediaProjection.RemotePoster(poster)
        else -> DashboardMediaProjection.Fixture
    }
}
