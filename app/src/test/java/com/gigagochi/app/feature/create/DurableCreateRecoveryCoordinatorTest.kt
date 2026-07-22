package com.gigagochi.app.feature.create

import com.gigagochi.app.core.background.LocalNotificationEmitter
import com.gigagochi.app.core.database.InMemoryFeatureStore
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.PendingCreateStage
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.GenerationAssetDto
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobDto
import com.gigagochi.app.core.network.GenerationJobPhaseDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableCreateRecoveryCoordinatorTest {
    @Test
    fun runningForegroundResultCreatesPetNotifiesOnceAndKeepsPending() = runBlocking {
        val store = RecoveryStore()
        val emitted = mutableListOf<String>()

        val result = coordinator(store, runningEnvelope(), emitted).recoverOnce()

        assertEquals(DurableCreateRecoveryResult.Retry, result)
        assertNotNull(store.snapshot)
        assertEquals(PendingBackendState.ForegroundReady, store.creates.single().backendState)
        assertEquals(listOf(Key), emitted)
    }

    @Test
    fun succeededResultAfterForegroundUpdatesMediaAndDeletesPendingWithoutDuplicateNotification() = runBlocking {
        val store = RecoveryStore(PendingBackendState.ForegroundReady).apply {
            snapshot = OwnedPetSnapshot(
                OwnerId,
                requireNotNull(createdPetFromPending(creates.single(), fixture(background = true))),
                1L,
            )
        }
        val emitted = mutableListOf<String>()

        val result = coordinator(store, succeededEnvelope(), emitted).recoverOnce()

        assertEquals(DurableCreateRecoveryResult.Complete, result)
        assertTrue(store.creates.isEmpty())
        assertEquals(emptyList<String>(), emitted)
        assertEquals(FullHappyVideo, store.snapshot?.pet?.generatedMedia?.happyVideoUrl)
    }

    @Test
    fun unavailableNotificationsDoNotBlockCompletedCreate() = runBlocking {
        val store = RecoveryStore()
        val api = api(succeededEnvelope())
        val result = DurableCreateRecoveryCoordinator(
            OwnerId,
            store,
            store,
            api,
            LocalNotificationEmitter { false },
        ).recoverOnce()

        assertEquals(DurableCreateRecoveryResult.Complete, result)
        assertNotNull(store.snapshot)
        assertTrue(store.creates.isEmpty())
    }

    @Test
    fun terminalFailureEmitsCreateFailureNotification() = runBlocking {
        val store = RecoveryStore()
        val notifications = mutableListOf<com.gigagochi.app.core.database.LocalCompletionNotification>()
        val result = DurableCreateRecoveryCoordinator(
            OwnerId,
            store,
            store,
            api(envelope(GenerationJobStatusDto.Failed, GenerationJobPhaseDto.Completed, asset(false))),
            LocalNotificationEmitter {
                notifications += it
                true
            },
        ).recoverOnce()

        assertEquals(DurableCreateRecoveryResult.Terminal, result)
        assertEquals(PendingBackendState.Failed, store.creates.single().backendState)
        assertEquals("Не получилось создать персонажа, попробуй еще раз", notifications.single().body)
    }

    @Test
    fun unavailableFailureNotificationStillStopsTerminalPolling() = runBlocking {
        val store = RecoveryStore()
        val result = DurableCreateRecoveryCoordinator(
            OwnerId,
            store,
            store,
            api(envelope(GenerationJobStatusDto.Failed, GenerationJobPhaseDto.Completed, asset(false))),
            LocalNotificationEmitter { false },
        ).recoverOnce()

        assertEquals(DurableCreateRecoveryResult.Terminal, result)
        assertEquals(PendingBackendState.Failed, store.creates.single().backendState)
    }

    private fun coordinator(
        store: RecoveryStore,
        envelope: GenerationEnvelopeDto,
        emitted: MutableList<String>,
    ) = DurableCreateRecoveryCoordinator(
        OwnerId,
        store,
        store,
        api(envelope),
        LocalNotificationEmitter {
            emitted += it.stableKey
            true
        },
        nowEpochMillis = { 99L },
    )

    private fun api(envelope: GenerationEnvelopeDto) = object : TestAndroidFeatureService() {
        override suspend fun pollCreate(jobId: String) = FeatureApiResult.Success(envelope)
    }

    private class RecoveryStore(
        state: PendingBackendState = PendingBackendState.Attached,
    ) : InMemoryFeatureStore() {
        var snapshot: OwnedPetSnapshot? = null

        init {
            creates += LocalPendingCreateGeneration(
                OwnerId, PetId, Key, JobId, PendingCreateStage.Generating,
                "dragon", "Toto", "kind", "spiders", "ball", FinalCreationStep, 1L, state,
            )
        }

        override suspend fun loadOwnerRecovery(ownerId: String) =
            super.loadOwnerRecovery(ownerId).copy(petSnapshots = listOfNotNull(snapshot))

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
        const val JobId = "job-a"
        const val Key = "123e4567-e89b-42d3-a456-426614174000"
        const val NormalVideo = "https://gigagochi.serega.works/static/generated/asset-a/normal.mp4"
        const val FullHappyVideo = "https://gigagochi.serega.works/static/generated/asset-a/happy.mp4"

        fun runningEnvelope() = envelope(
            GenerationJobStatusDto.Running,
            GenerationJobPhaseDto.GeneratingSadImage,
            asset(background = false),
        )

        fun succeededEnvelope() = envelope(
            GenerationJobStatusDto.Succeeded,
            GenerationJobPhaseDto.Completed,
            asset(background = true),
        )

        fun fixture(background: Boolean) = GeneratedPetFixture(
            "dragon",
            PetId,
            "asset-a",
            generatedMedia = com.gigagochi.app.core.model.PetGeneratedMedia(
                generatedAt = "2026-07-17T10:11:12Z",
                videoUrl = NormalVideo,
                happyVideoUrl = FullHappyVideo.takeIf { background },
            ),
            backgroundGenerationPending = !background,
        )

        fun envelope(
            status: GenerationJobStatusDto,
            phase: GenerationJobPhaseDto,
            asset: GenerationAssetDto,
        ) = GenerationEnvelopeDto(
            Key,
            PetId,
            GenerationJobDto(
                JobId,
                status,
                phase,
                "2026-07-17T10:11:12Z",
                "2026-07-17T10:20:12Z",
                asset,
            ),
        )

        fun asset(background: Boolean): GenerationAssetDto {
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
                videoUrl = NormalVideo,
                sadVideoUrl = "https://gigagochi.serega.works/static/generated/asset-a/sad.mp4"
                    .takeIf { background },
                happyVideoUrl = FullHappyVideo.takeIf { background },
            )
        }
    }
}
