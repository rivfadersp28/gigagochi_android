package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.InMemoryFeatureStore
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingCreateStage
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.GenerationAssetDto
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobDto
import com.gigagochi.app.core.network.GenerationJobPhaseDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateBackgroundMediaCoordinatorTest {
    @Test
    fun completedBackgroundMediaUpdatesPetAndClearsPendingJob() = runBlocking {
        val store = BackgroundStore()
        var delivered: PetDashboardState? = null
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollCreate(jobId: String) = FeatureApiResult.Success(
                GenerationEnvelopeDto(
                    Key,
                    PetId,
                    GenerationJobDto(
                        jobId,
                        GenerationJobStatusDto.Succeeded,
                        GenerationJobPhaseDto.Completed,
                        "2026-07-17T10:11:12Z",
                        "2026-07-17T10:20:12Z",
                        completedAsset(),
                    ),
                ),
            )
        }

        CreateBackgroundMediaCoordinator(
            OwnerId,
            store,
            store,
            api,
            pollDelayMillis = 0,
            maxPollAttempts = 1,
            nowEpochMillis = { 99L },
            onMediaReady = { delivered = it },
        ).recover(PetId)

        assertTrue(store.creates.isEmpty())
        assertEquals(
            "https://gigagochi.serega.works/static/generated/asset-a/happy.mp4",
            store.snapshot.pet.generatedMedia.happyVideoUrl,
        )
        assertEquals(99L, store.snapshot.updatedAtEpochMillis)
        assertEquals(store.snapshot.pet, delivered)
    }

    private class BackgroundStore : InMemoryFeatureStore() {
        var snapshot = OwnedPetSnapshot(
            OwnerId,
            PetDashboardState(
                PetId, "asset-a", "dragon", "Toto", "baby", "Малыш", "idle",
                0, 100, 100, 100, "hello",
            ),
            1L,
        )

        init {
            creates += LocalPendingCreateGeneration(
                OwnerId, PetId, Key, "job-a", PendingCreateStage.Generating,
                "dragon", "Toto", "kind", "spiders", "ball", 5, 1L,
                PendingBackendState.Attached,
            )
        }

        override suspend fun loadOwnerRecovery(ownerId: String) =
            super.loadOwnerRecovery(ownerId).copy(petSnapshots = listOf(snapshot))

        override suspend fun replacePetSnapshotIfAssetCurrent(snapshot: OwnedPetSnapshot): Boolean {
            this.snapshot = snapshot
            return true
        }

        override suspend fun deletePendingCreate(ownerId: String, requestKey: String): Boolean =
            creates.removeAll { it.ownerId == ownerId && it.requestKey == requestKey }
    }

    private companion object {
        const val OwnerId = "owner-a"
        const val PetId = "pet-a"
        const val Key = "123e4567-e89b-42d3-a456-426614174000"

        fun completedAsset(): GenerationAssetDto {
            val moods = mapOf(
                "idle" to "https://gigagochi.serega.works/static/i.png",
                "happy" to "https://gigagochi.serega.works/static/h.png",
                "hungry" to "https://gigagochi.serega.works/static/u.png",
                "sad" to "https://gigagochi.serega.works/static/s.png",
            )
            return GenerationAssetDto(
                "asset-a",
                "2026-07-17T10:11:12Z",
                mapOf("baby" to moods, "teen" to moods, "adult" to moods),
                videoUrl = "https://gigagochi.serega.works/static/generated/asset-a/normal.mp4",
                sadVideoUrl = "https://gigagochi.serega.works/static/generated/asset-a/sad.mp4",
                happyVideoUrl = "https://gigagochi.serega.works/static/generated/asset-a/happy.mp4",
            )
        }
    }
}
