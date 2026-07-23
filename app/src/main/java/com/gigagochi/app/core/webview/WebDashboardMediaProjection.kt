package com.gigagochi.app.core.webview

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.feature.dashboard.DashboardMediaProjection
import com.gigagochi.app.feature.dashboard.projectDashboardMedia

internal fun projectDashboardWebMedia(
    pet: PetDashboardState,
    registry: WebMediaReferenceRegistry,
): WebPetMediaSnapshot {
    val projection = projectDashboardMedia(
        pet = pet,
        resolveUrl = registry::resolveSource,
        fixtureOnly = false,
    )
    return when (projection) {
        is DashboardMediaProjection.RemoteVideo -> {
            val videoRef = registry.register(
                projection.videoUrl,
                WebMediaKind.Video,
                slot = "dashboard:${pet.petId}:video",
                priority = WebMediaProjectionPriority.Current,
            )
            val posterRef = registry.register(
                projection.posterUrl,
                WebMediaKind.Image,
                slot = "dashboard:${pet.petId}:poster",
                priority = WebMediaProjectionPriority.Current,
            )
            when {
                videoRef != null -> WebPetMediaSnapshot(
                    videoRef = videoRef,
                    posterRef = posterRef,
                )

                posterRef != null -> WebPetMediaSnapshot(posterRef = posterRef)
                else -> FixtureWebPetMedia
            }
        }

        is DashboardMediaProjection.RemotePoster -> {
            registry.register(
                projection.posterUrl,
                WebMediaKind.Image,
                slot = "dashboard:${pet.petId}:poster",
                priority = WebMediaProjectionPriority.Current,
            )
                ?.let { WebPetMediaSnapshot(posterRef = it) }
                ?: FixtureWebPetMedia
        }

        DashboardMediaProjection.Fixture -> FixtureWebPetMedia
    }
}

private val FixtureWebPetMedia = WebPetMediaSnapshot(
    videoRef = "/assets/media/openai-normal.mp4",
)
