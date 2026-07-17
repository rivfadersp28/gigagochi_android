package com.gigagochi.app.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.TestAndroidFeatureService
import com.gigagochi.app.core.network.TravelVideoDto
import com.gigagochi.app.core.network.TravelVideoRequestDto
import com.gigagochi.app.core.network.TravelVideoStatusDto
import com.gigagochi.app.feature.create.CreateFinalizationCoordinator
import com.gigagochi.app.feature.create.CreateFinalizationResult
import com.gigagochi.app.feature.create.CreatePetState
import com.gigagochi.app.feature.create.FinalCreationStep
import com.gigagochi.app.feature.create.GeneratedPetFixture
import com.gigagochi.app.feature.create.GenerationStatus
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.dashboard.DashboardDurableOperations
import com.gigagochi.app.feature.dashboard.DashboardOutcomeApplicationCoordinator
import com.gigagochi.app.feature.dashboard.DashboardOutcomeRecoveryResult
import com.gigagochi.app.feature.dashboard.DurableTravelResult
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import com.gigagochi.app.feature.dashboard.RealDashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.RealDashboardTravelAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MvpCoreFlowIntegrationTest {
    @Test
    fun authenticatedCreateTravelApplyRestartAndReplayStayDurable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "mvp-2-core-flow.db"
        context.deleteDatabase(databaseName)
        var database = Room.databaseBuilder(context, GigagochiDatabase::class.java, databaseName).build()
        var repository = PetLocalRepository(database)
        val lifecycle = AccountPetLifecycle(repository, nowEpochMillis = { 10L })

        repository.savePendingCreate(pendingCreate())
        val finalized = CreateFinalizationCoordinator(OwnerId, lifecycle, repository)
            .finalize(readyCreateState())
        assertTrue(finalized is CreateFinalizationResult.Success)
        assertTrue(repository.getPendingCreates(OwnerId).isEmpty())

        val firstStartup = lifecycle.startup(OwnerId) as AccountStartupDestination.Dashboard
        assertEquals(PetId, firstStartup.pet.petId)
        assertEquals(InitialAssetSetId, firstStartup.pet.assetSetId)
        assertEquals(0, firstStartup.pet.experience)

        val api = ReadyTravelFeatureService()
        val travelAdapter = RealDashboardTravelAdapter(
            OwnerId,
            repository,
            repository,
            repository,
            api,
            nowEpochMillis = { 30L },
        )
        val durable = DashboardDurableOperations(
            OwnerId,
            repository,
            RealDashboardOutfitAdapter(OwnerId, repository, repository, repository, api),
            travelAdapter,
            nowEpochMillis = { 20L },
        )
        val request = PendingTravelRequest(TravelRequestKey, "Полёт к Луне")
        val queued = durable.acceptTravel(request, firstStartup.pet)
        assertTrue(queued is DurableTravelResult.Queued)
        assertEquals(0, repository.getPetSnapshot(OwnerId, PetId)?.pet?.experience)
        assertEquals(1, api.submitCalls)
        assertEquals(TravelRequestKey, api.submittedRequestKey)

        val attached = repository.getPendingTravels(OwnerId).single()
        assertEquals(BackendJobId, attached.backendJobId)
        assertTrue(travelAdapter.pollOnce(attached))
        assertEquals(1, api.pollCalls)

        val applied = DashboardOutcomeApplicationCoordinator(
            OwnerId,
            repository,
            repository,
            repository,
            nowEpochMillis = { 40L },
        ).applyReady(PetId)
        assertTrue(applied is DashboardOutcomeRecoveryResult.Changed)
        assertEquals(0, (applied as DashboardOutcomeRecoveryResult.Changed).pet.experience)
        assertNotNull(applied.travelPresentation)
        assertEquals(VideoUrl, applied.travelPresentation?.videoUrl)
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())

        database.close()
        database = Room.databaseBuilder(context, GigagochiDatabase::class.java, databaseName).build()
        repository = PetLocalRepository(database)
        val restarted = AccountPetLifecycle(repository).startup(OwnerId) as AccountStartupDestination.Dashboard
        assertEquals(PetId, restarted.pet.petId)
        assertEquals(InitialAssetSetId, restarted.pet.assetSetId)
        assertEquals(0, restarted.pet.experience)
        assertEquals(null, restarted.pendingTravel)
        assertEquals(VideoUrl, restarted.travelPresentation?.videoUrl)
        assertEquals(40L, restarted.travelPresentation?.consumedAtEpochMillis)

        val replayAdapter = RealDashboardTravelAdapter(
            OwnerId,
            repository,
            repository,
            repository,
            api,
        )
        val replay = DashboardDurableOperations(
            OwnerId,
            repository,
            RealDashboardOutfitAdapter(OwnerId, repository, repository, repository, api),
            replayAdapter,
        ).acceptTravel(request, restarted.pet)
        assertEquals(DurableTravelResult.Failure, replay)
        assertTrue(repository.getPendingTravels(OwnerId).isEmpty())
        assertEquals(1, api.submitCalls)
        assertEquals(1, api.pollCalls)
        assertEquals(
            DashboardOutcomeRecoveryResult.Unchanged,
            DashboardOutcomeApplicationCoordinator(OwnerId, repository, repository, repository)
                .applyReady(PetId),
        )

        database.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun pendingCreate() = LocalPendingCreateGeneration(
        OwnerId,
        PetId,
        CreateRequestKey,
        "create-job",
        PendingCreateStage.Generating,
        "Ледяной дракон",
        "Тото",
        "Добрый",
        "Пауков",
        "Вантуз",
        FinalCreationStep,
        5L,
        PendingBackendState.Ready,
    )

    private fun readyCreateState() = CreatePetState(
        step = FinalCreationStep,
        answers = listOf("Ледяной дракон", "Тото", "Добрый", "Пауков", "Вантуз"),
        description = "Ледяной дракон",
        generation = GenerationStatus.Ready(
            GeneratedPetFixture(
                description = "Ледяной дракон",
                petId = PetId,
                assetSetId = InitialAssetSetId,
                generatedMedia = initialMedia(),
            ),
        ),
        pending = PendingPetGeneration(PetId, "Ледяной дракон", CreateRequestKey),
    )

    private class ReadyTravelFeatureService : TestAndroidFeatureService() {
        var submitCalls = 0
        var pollCalls = 0
        var submittedRequestKey: String? = null

        override suspend fun submitTravel(request: TravelVideoRequestDto): FeatureApiResult<TravelVideoDto> {
            submitCalls += 1
            submittedRequestKey = request.requestKey
            return FeatureApiResult.Success(travel(TravelVideoStatusDto.Animating))
        }

        override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> {
            pollCalls += 1
            assertEquals(BackendJobId, jobId)
            return FeatureApiResult.Success(travel(TravelVideoStatusDto.Ready))
        }

        private fun travel(status: TravelVideoStatusDto) = TravelVideoDto(
            BackendJobId,
            status,
            "Полёт к Луне",
            "Тото увидел Землю с орбиты",
            imageUrl = if (status == TravelVideoStatusDto.Ready) PosterUrl else null,
            videoUrl = if (status == TravelVideoStatusDto.Ready) VideoUrl else null,
            createdAt = Timestamp,
            updatedAt = Timestamp,
        )
    }

    private companion object {
        const val OwnerId = "account-canonical-owner"
        const val PetId = "pet-mvp-2"
        const val CreateRequestKey = "123e4567-e89b-42d3-a456-426614174001"
        const val TravelRequestKey = "123e4567-e89b-42d3-a456-426614174002"
        const val BackendJobId = "job-mvp-2-travel"
        const val InitialAssetSetId = "asset-initial"
        const val Timestamp = "2026-07-17T10:11:12Z"
        const val PosterUrl = "https://gigagochi.serega.works/static/mvp-2-poster.png?v=1"
        const val VideoUrl = "https://gigagochi.serega.works/static/mvp-2-video.mp4?v=1"

        fun initialMedia() = PetGeneratedMedia(
            generatedAt = Timestamp,
            moodImages = listOf("baby", "teen", "adult").flatMap { stage ->
                listOf("idle", "happy", "hungry", "sad").map { mood ->
                    PetMoodImage(
                        stage,
                        mood,
                        "https://gigagochi.serega.works/static/initial-$stage-$mood.png?v=1",
                    )
                }
            },
        )
    }
}
