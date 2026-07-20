package com.gigagochi.app.feature.create

import com.gigagochi.app.core.database.InMemoryFeatureStore
import com.gigagochi.app.core.database.LocalPendingCreateGeneration
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
import org.junit.Assert.assertTrue
import org.junit.Test

class RealPetGenerationAdapterTest {
    @Test
    fun attachedRecoveryPollsExactJobWithoutResubmitting() = runBlocking {
        val store = InMemoryFeatureStore().apply {
            creates += pending(backendJobId = "job-safe", state = PendingBackendState.Attached)
        }
        var submits = 0
        var polledId: String? = null
        val api = object : TestAndroidFeatureService() {
            override suspend fun submitCreate(request: com.gigagochi.app.core.network.CreateJobRequestDto): FeatureApiResult<GenerationEnvelopeDto> {
                submits += 1
                error("must not submit")
            }
            override suspend fun pollCreate(jobId: String): FeatureApiResult<GenerationEnvelopeDto> {
                polledId = jobId
                return FeatureApiResult.Success(envelope(jobId, GenerationJobStatusDto.Succeeded, asset()))
            }
        }
        val adapter = RealPetGenerationAdapter("owner-a", store, store, api, 0, 1)

        val result = adapter.generate(PendingPetGeneration("pet-a", "dragon", Key))

        assertEquals(0, submits)
        assertEquals("job-safe", polledId)
        assertEquals("asset-a", result.assetSetId)
        assertEquals(PendingBackendState.Ready, store.creates.single().backendState)
    }

    @Test
    fun foregroundResultReturnsBeforeBackgroundGenerationCompletes() = runBlocking {
        val store = InMemoryFeatureStore().apply {
            creates += pending(backendJobId = "job-fast", state = PendingBackendState.Attached)
        }
        val foreground = asset().copy(
            videoUrl = "https://gigagochi.serega.works/static/generated/asset-a/normal.mp4",
        )
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollCreate(jobId: String) = FeatureApiResult.Success(
                envelope(
                    jobId,
                    GenerationJobStatusDto.Running,
                    foreground,
                    GenerationJobPhaseDto.GeneratingSadImage,
                ),
            )
        }

        val result = RealPetGenerationAdapter("owner-a", store, store, api, 0, 1)
            .generate(PendingPetGeneration("pet-a", "dragon", Key))

        assertTrue(result.backgroundGenerationPending)
        assertEquals(foreground.videoUrl, result.generatedMedia.videoUrl)
        assertEquals(PendingBackendState.Attached, store.creates.single().backendState)
    }

    @Test
    fun dispatchingRestartNeverRepeatsAmbiguousSubmit() = runBlocking {
        val store = InMemoryFeatureStore().apply {
            creates += pending(backendJobId = null, state = PendingBackendState.Dispatching)
        }
        var submits = 0
        val api = object : TestAndroidFeatureService() {
            override suspend fun submitCreate(request: com.gigagochi.app.core.network.CreateJobRequestDto): FeatureApiResult<GenerationEnvelopeDto> {
                submits += 1
                return FeatureApiResult.Success(envelope("job-safe", GenerationJobStatusDto.Queued))
            }
        }
        val adapter = RealPetGenerationAdapter("owner-a", store, store, api, 0, 1)
        var failed = false
        try {
            adapter.generate(PendingPetGeneration("pet-a", "dragon", Key))
        } catch (error: FeatureAdapterException) {
            failed = true
            assertEquals(com.gigagochi.app.core.network.FeatureFailureKind.OutcomeUnknown, error.failure.kind)
        }
        assertTrue(failed)
        assertEquals(0, submits)
    }

    private fun pending(backendJobId: String?, state: PendingBackendState) =
        LocalPendingCreateGeneration(
            "owner-a", "pet-a", Key, backendJobId, PendingCreateStage.Generating,
            "dragon", "Toto", "kind", "spiders", "ball", 5, 1,
            state,
        )

    private fun asset(): GenerationAssetDto {
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
        )
    }

    private fun envelope(
        jobId: String,
        status: GenerationJobStatusDto,
        result: GenerationAssetDto? = null,
        phase: GenerationJobPhaseDto = if (status == GenerationJobStatusDto.Succeeded) {
            GenerationJobPhaseDto.Completed
        } else {
            GenerationJobPhaseDto.Queued
        },
    ) = GenerationEnvelopeDto(
        Key,
        "pet-a",
        GenerationJobDto(
            jobId,
            status,
            phase,
            "2026-07-17T10:11:12Z",
            "2026-07-17T10:11:12Z",
            result,
        ),
    )

    private companion object {
        const val Key = "123e4567-e89b-42d3-a456-426614174000"
    }
}
