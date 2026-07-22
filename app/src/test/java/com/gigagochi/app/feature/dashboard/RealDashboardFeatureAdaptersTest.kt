package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.InMemoryFeatureStore
import com.gigagochi.app.core.database.LocalPendingOutfit
import com.gigagochi.app.core.database.LocalPendingTravelVideo
import com.gigagochi.app.core.database.PendingBackendState
import com.gigagochi.app.core.database.DashboardOutcomeStore
import com.gigagochi.app.core.database.OutfitOutcomeApplicationResult
import com.gigagochi.app.core.database.OwnerRecoveryData
import com.gigagochi.app.core.database.OwnedPetSnapshot
import com.gigagochi.app.core.database.TravelAssetConsumptionResult
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.GenerationEnvelopeDto
import com.gigagochi.app.core.network.GenerationJobDto
import com.gigagochi.app.core.network.GenerationJobPhaseDto
import com.gigagochi.app.core.network.GenerationJobStatusDto
import com.gigagochi.app.core.network.OutfitSimplifyResponseDto
import com.gigagochi.app.core.network.TestAndroidFeatureService
import com.gigagochi.app.core.network.TravelVideoDto
import com.gigagochi.app.core.network.TravelVideoStatusDto
import com.gigagochi.app.feature.create.FeatureAdapterException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDashboardFeatureAdaptersTest {
    @Test
    fun localMediaPreconditionFailsBeforeDispatchOrFirstApiCall() = runBlocking {
        val store = InMemoryFeatureStore().apply { outfits += outfit() }
        var calls = 0
        val api = object : TestAndroidFeatureService() {
            override suspend fun simplifyOutfit(request: com.gigagochi.app.core.network.OutfitSimplifyRequestDto): FeatureApiResult<OutfitSimplifyResponseDto> {
                calls += 1
                error("must not call")
            }
        }
        val adapter = RealDashboardOutfitAdapter("owner-a", store, store, store, api)
        var failed = false
        try {
            adapter.queue(PendingOutfitRequest(Key, "В плащ"), pet(media = PetGeneratedMedia()))
        } catch (_: FeatureAdapterException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(0, calls)
        assertEquals(PendingBackendState.Pending, store.outfits.single().backendState)
    }

    @Test
    fun preparedDisplayItemSurvivesAttachAndIsUsedByRecoveryOutcome() = runBlocking {
        val store = InMemoryFeatureStore().apply { outfits += outfit() }
        var pollCount = 0
        var submittedRequestKey: String? = null
        val api = object : TestAndroidFeatureService() {
            override suspend fun simplifyOutfit(request: com.gigagochi.app.core.network.OutfitSimplifyRequestDto) =
                FeatureApiResult.Success(OutfitSimplifyResponseDto("cloak", "лунный плащ", "moon cloak"))
            override suspend fun submitOutfit(request: com.gigagochi.app.core.network.OutfitJobRequestDto): FeatureApiResult<GenerationEnvelopeDto> {
                submittedRequestKey = request.requestKey
                return FeatureApiResult.Success(envelope("job-outfit", GenerationJobStatusDto.Queued))
            }
            override suspend fun pollOutfit(jobId: String): FeatureApiResult<GenerationEnvelopeDto> {
                pollCount += 1
                return FeatureApiResult.Success(
                    envelope(
                        jobId,
                        GenerationJobStatusDto.Succeeded,
                        RealPetGenerationAdapterTestAsset.asset(),
                    ),
                )
            }
        }
        val adapter = RealDashboardOutfitAdapter("owner-a", store, store, store, api)
        adapter.queue(PendingOutfitRequest(Key, "В неканонический плащ"), pet())

        val recovered = store.outfits.single()
        assertEquals(Key, submittedRequestKey)
        assertEquals("лунный плащ", recovered.preparedDisplayItem)
        adapter.pollOnce(recovered)
        assertEquals(1, pollCount)
        assertEquals("лунный плащ", store.outfitOutcomes.single().displayItem)
    }

    @Test
    fun succeededOutfitWaitsForAllThreeDashboardVideos() = runBlocking {
        val store = InMemoryFeatureStore().apply {
            outfits += outfit().copy(
                backendJobId = "job-outfit",
                backendState = PendingBackendState.Attached,
            )
        }
        var complete = false
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollOutfit(jobId: String) = FeatureApiResult.Success(
                envelope(
                    jobId,
                    GenerationJobStatusDto.Succeeded,
                    RealPetGenerationAdapterTestAsset.asset(complete),
                ),
            )
        }
        val adapter = RealDashboardOutfitAdapter("owner-a", store, store, store, api)

        assertEquals(false, adapter.pollOnce(store.outfits.single()))
        assertTrue(store.outfitOutcomes.isEmpty())
        assertEquals(PendingBackendState.Attached, store.outfits.single().backendState)

        complete = true
        assertEquals(true, adapter.pollOnce(store.outfits.single()))
        assertEquals(PendingBackendState.Ready, store.outfits.single().backendState)
        assertTrue(store.outfitOutcomes.single().media.videoUrl != null)
        assertTrue(store.outfitOutcomes.single().media.sadVideoUrl != null)
        assertTrue(store.outfitOutcomes.single().media.happyVideoUrl != null)
    }

    @Test
    fun identityMismatchBecomesDurableOutcomeUnknown() = runBlocking {
        val store = InMemoryFeatureStore().apply { outfits += outfit() }
        val api = object : TestAndroidFeatureService() {
            override suspend fun simplifyOutfit(request: com.gigagochi.app.core.network.OutfitSimplifyRequestDto) =
                FeatureApiResult.Success(OutfitSimplifyResponseDto("cloak", "плащ", "cloak"))
            override suspend fun submitOutfit(request: com.gigagochi.app.core.network.OutfitJobRequestDto) =
                FeatureApiResult.Success(envelope("job-outfit", GenerationJobStatusDto.Queued).copy(petId = "other"))
        }
        val adapter = RealDashboardOutfitAdapter("owner-a", store, store, store, api)
        try {
            adapter.queue(PendingOutfitRequest(Key, "В плащ"), pet())
        } catch (_: FeatureAdapterException) {
            Unit
        }
        assertEquals(PendingBackendState.OutcomeUnknown, store.outfits.single().backendState)
        assertEquals("IDENTITY_MISMATCH", store.outfits.single().backendErrorCode)
    }

    @Test
    fun watcherEnteredEmptyIsTriggeredByNewTravelAndPollsUntilReady() = runBlocking {
        val store = InMemoryFeatureStore()
        val signal = ForegroundRecoverySignal()
        var polls = 0
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> {
                polls += 1
                return FeatureApiResult.Success(
                    travelDto(
                        jobId,
                        if (polls < 3) TravelVideoStatusDto.Animating else TravelVideoStatusDto.Ready,
                    ),
                )
            }
        }
        val outfitAdapter = RealDashboardOutfitAdapter("owner-a", store, store, store, api)
        val travelAdapter = RealDashboardTravelAdapter("owner-a", store, store, store, api)
        val coordinator = ForegroundPendingRecoveryCoordinator(
            "owner-a", store, outfitAdapter, travelAdapter, signal, 1, 10,
        )
        val watcher = launch { coordinator.watch("pet-a") }
        delay(10)
        store.travels += travel(backendJobId = "job-travel", state = PendingBackendState.Attached)
        signal.request()
        repeat(100) {
            if (store.travelAssets.isNotEmpty()) return@repeat
            delay(2)
        }

        assertEquals(3, polls)
        assertEquals(PendingBackendState.Ready, store.travels.single().backendState)
        assertEquals("https://gigagochi.serega.works/static/video.mp4?v=1", store.travelAssets.single().videoUrl)
        watcher.cancelAndJoin()
    }

    @Test
    fun failedReadyStatusIsNotReportedAsQueuedSuccess() = runBlocking {
        val store = InMemoryFeatureStore().apply { travels += travel() }
        val api = object : TestAndroidFeatureService() {
            override suspend fun submitTravel(request: com.gigagochi.app.core.network.TravelVideoRequestDto) =
                FeatureApiResult.Success(travelDto("job-travel", TravelVideoStatusDto.Failed))
        }
        val failedNotifications = mutableListOf<String>()
        val adapter = RealDashboardTravelAdapter(
            "owner-a",
            store,
            store,
            store,
            api,
            onTravelFailed = { _, requestKey -> failedNotifications += requestKey },
        )
        var failed = false
        try {
            adapter.queue(PendingTravelRequest(Key, "Луна"), pet())
        } catch (_: FeatureAdapterException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(PendingBackendState.Failed, store.travels.single().backendState)
        assertEquals(listOf(Key), failedNotifications)
    }

    @Test
    fun failedOutfitStatusEmitsTerminalFailureCallback() = runBlocking {
        val store = InMemoryFeatureStore().apply {
            outfits += outfit().copy(
                backendJobId = "job-outfit",
                backendState = PendingBackendState.Attached,
            )
        }
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollOutfit(jobId: String) =
                FeatureApiResult.Success(envelope(jobId, GenerationJobStatusDto.Failed))
        }
        val failedNotifications = mutableListOf<String>()
        val adapter = RealDashboardOutfitAdapter(
            "owner-a",
            store,
            store,
            store,
            api,
            onOutfitFailed = { _, requestKey -> failedNotifications += requestKey },
        )

        var failed = false
        try {
            adapter.pollOnce(store.outfits.single())
        } catch (_: FeatureAdapterException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(PendingBackendState.Failed, store.outfits.single().backendState)
        assertEquals(listOf(Key), failedNotifications)
    }

    @Test
    fun recoveryReloadsOnceOnlyForNewOutfitAndTravelTerminalFailures() = runBlocking {
        val oldFailed = outfit().copy(
            requestKey = "old-failed-outfit",
            backendState = PendingBackendState.Failed,
        )
        val store = InMemoryFeatureStore().apply {
            outfits += oldFailed
            outfits += outfit().copy(
                backendJobId = "job-outfit",
                backendState = PendingBackendState.Attached,
            )
            travels += travel(
                backendJobId = "job-travel",
                state = PendingBackendState.Attached,
            )
        }
        var outfitPolls = 0
        var travelPolls = 0
        var terminalCallbacks = 0
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollOutfit(jobId: String): FeatureApiResult<GenerationEnvelopeDto> {
                outfitPolls += 1
                return FeatureApiResult.Success(envelope(jobId, GenerationJobStatusDto.Failed))
            }

            override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> {
                travelPolls += 1
                return FeatureApiResult.Success(travelDto(jobId, TravelVideoStatusDto.Failed))
            }
        }
        val coordinator = ForegroundPendingRecoveryCoordinator(
            "owner-a",
            store,
            RealDashboardOutfitAdapter("owner-a", store, store, store, api),
            RealDashboardTravelAdapter("owner-a", store, store, store, api),
            ForegroundRecoverySignal(),
            pollDelayMillis = 1,
            maxPollAttempts = 3,
            onTerminalFailure = { terminalCallbacks += 1 },
        )

        coordinator.recoverForeground("pet-a")
        coordinator.recoverForeground("pet-a")

        assertEquals(1, outfitPolls)
        assertEquals(1, travelPolls)
        assertEquals(1, terminalCallbacks)
        assertEquals(PendingBackendState.Failed, store.outfits.last().backendState)
        assertEquals(PendingBackendState.Failed, store.travels.single().backendState)
        assertEquals(PendingBackendState.Failed, oldFailed.backendState)
    }

    @Test
    fun readyApplyStorageFailureRetriesPersistenceOnlyThenSucceeds() = runBlocking {
        val store = FlakyOutcomeStore().apply {
            travels += travel(backendJobId = "job-travel", state = PendingBackendState.Ready)
            travelAssets += LocalTravelVideoAsset(
                "owner-a", "pet-a", Key, "job-travel", "Луна", null, null, null,
                "https://gigagochi.serega.works/static/video.mp4", 2,
            )
        }
        var providerCalls = 0
        val api = object : TestAndroidFeatureService() {
            override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> {
                providerCalls += 1
                error("ready apply retry must not poll")
            }
            override suspend fun submitTravel(request: com.gigagochi.app.core.network.TravelVideoRequestDto): FeatureApiResult<TravelVideoDto> {
                providerCalls += 1
                error("ready apply retry must not submit")
            }
        }
        val coordinator = ForegroundPendingRecoveryCoordinator(
            "owner-a",
            store,
            RealDashboardOutfitAdapter("owner-a", store, store, store, api),
            RealDashboardTravelAdapter("owner-a", store, store, store, api),
            ForegroundRecoverySignal(),
            pollDelayMillis = 1,
            maxPollAttempts = 3,
            outcomeApplication = DashboardOutcomeApplicationCoordinator(
                "owner-a", store, store, store, nowEpochMillis = { 3 },
            ),
        )

        coordinator.recoverForeground("pet-a")

        assertEquals(2, store.consumeCalls)
        assertEquals(0, providerCalls)
        assertTrue(store.travels.isEmpty())
        assertEquals(3L, store.travelAssets.single().consumedAtEpochMillis)
    }

    @Test
    fun readyApplyConflictUsesDedicatedTerminalCas() = runBlocking {
        val store = ConflictOutcomeStore().apply {
            outfits += outfit().copy(backendState = PendingBackendState.Ready)
        }
        val result = DashboardOutcomeApplicationCoordinator(
            "owner-a", store, store, store,
        ).applyReady("pet-a")

        assertEquals(DashboardOutcomeRecoveryResult.Conflict, result)
        assertEquals(PendingBackendState.Failed, store.outfits.single().backendState)
        assertEquals("APPLY_CONFLICT", store.outfits.single().backendErrorCode)
    }

    @Test
    fun foregroundReloadsPetWhenBackgroundWorkerWonOutcomeApplyRace() = runBlocking {
        val store = AlreadyAppliedOutcomeStore()

        val result = DashboardOutcomeApplicationCoordinator(
            "owner-a", store, store, store,
        ).applyReady("pet-a")

        assertTrue(result is DashboardOutcomeRecoveryResult.Changed)
        assertEquals(
            "asset-new",
            (result as DashboardOutcomeRecoveryResult.Changed).pet.assetSetId,
        )
    }

    private fun outfit() = LocalPendingOutfit(
        "owner-a", "pet-a", Key, "local-outfit", null, "В плащ", "asset-a", 1,
    )

    private fun travel(
        backendJobId: String? = null,
        state: PendingBackendState = PendingBackendState.Pending,
    ) = LocalPendingTravelVideo(
        "owner-a", "pet-a", Key, "local-travel", backendJobId, "Луна", 1,
        state,
    )

    private fun pet(media: PetGeneratedMedia = fullMedia()) = PetDashboardState(
        "pet-a", "asset-a", "dragon", "Toto", "baby", "Малыш", "idle",
        500, 100, 100, 100, "hi", media,
    )

    private fun fullMedia(): PetGeneratedMedia {
        val moods = listOf("idle", "happy", "hungry", "sad")
        return PetGeneratedMedia(
            moodImages = listOf("baby", "teen", "adult").flatMap { stage ->
                moods.map { mood ->
                    PetMoodImage(stage, mood, "https://gigagochi.serega.works/static/$stage-$mood.png")
                }
            },
        )
    }

    private fun envelope(
        jobId: String,
        status: GenerationJobStatusDto,
        result: com.gigagochi.app.core.network.GenerationAssetDto? = null,
    ) = GenerationEnvelopeDto(
        Key,
        "pet-a",
        GenerationJobDto(
            jobId,
            status,
            if (status == GenerationJobStatusDto.Succeeded) GenerationJobPhaseDto.Completed
            else GenerationJobPhaseDto.Queued,
            Timestamp,
            Timestamp,
            result,
        ),
    )

    private fun travelDto(jobId: String, status: TravelVideoStatusDto) = TravelVideoDto(
        jobId,
        status,
        "Луна",
        videoUrl = if (status == TravelVideoStatusDto.Ready) {
            "https://gigagochi.serega.works/static/video.mp4?v=1"
        } else null,
        createdAt = Timestamp,
        updatedAt = Timestamp,
    )

    private companion object {
        const val Key = "123e4567-e89b-42d3-a456-426614174000"
        const val Timestamp = "2026-07-17T10:11:12Z"
    }
}

private class FlakyOutcomeStore : InMemoryFeatureStore(), DashboardOutcomeStore {
    var consumeCalls = 0

    override suspend fun loadOwnerRecovery(ownerId: String): OwnerRecoveryData {
        val base = super.loadOwnerRecovery(ownerId)
        return base.copy(
            petSnapshots = listOf(OwnedPetSnapshot(ownerId, testPet(), 1)),
        )
    }

    override suspend fun applyOutfitOutcome(
        ownerId: String,
        petId: String,
        requestKey: String,
    ) = OutfitOutcomeApplicationResult.NotReady

    override suspend fun consumeTravelAsset(
        ownerId: String,
        petId: String,
        requestKey: String,
        consumedAtEpochMillis: Long,
    ): TravelAssetConsumptionResult {
        consumeCalls += 1
        if (consumeCalls == 1) error("transient Room failure")
        val index = travelAssets.indexOfFirst { it.ownerId == ownerId && it.requestKey == requestKey }
        val consumed = travelAssets[index].copy(consumedAtEpochMillis = consumedAtEpochMillis)
        travelAssets[index] = consumed
        travels.removeAll { it.ownerId == ownerId && it.requestKey == requestKey }
        return TravelAssetConsumptionResult.Consumed(consumed)
    }

    private fun testPet() = PetDashboardState(
        "pet-a", "asset-a", "dragon", "Toto", "baby", "Малыш", "idle",
        500, 100, 100, 100, "hi",
    )
}

private class ConflictOutcomeStore : InMemoryFeatureStore(), DashboardOutcomeStore {
    override suspend fun applyOutfitOutcome(
        ownerId: String,
        petId: String,
        requestKey: String,
    ) = OutfitOutcomeApplicationResult.Conflict

    override suspend fun consumeTravelAsset(
        ownerId: String,
        petId: String,
        requestKey: String,
        consumedAtEpochMillis: Long,
    ) = TravelAssetConsumptionResult.NotReady
}

private class AlreadyAppliedOutcomeStore : InMemoryFeatureStore(), DashboardOutcomeStore {
    private var recoveryLoads = 0
    private val oldPet = PetDashboardState(
        "pet-a", "asset-a", "dragon", "Toto", "baby", "Малыш", "idle",
        500, 100, 100, 100, "hi",
    )
    private val newPet = oldPet.copy(assetSetId = "asset-new")
    private val ready = LocalPendingOutfit(
        "owner-a", "pet-a", "outfit-race", "local-outfit", "job-outfit",
        "Футболка Mayhem", "asset-a", 1, backendState = PendingBackendState.Ready,
    )

    override suspend fun loadOwnerRecovery(ownerId: String): OwnerRecoveryData {
        recoveryLoads += 1
        return OwnerRecoveryData(
            petSnapshots = listOf(
                OwnedPetSnapshot(ownerId, if (recoveryLoads == 1) oldPet else newPet, recoveryLoads.toLong()),
            ),
            pendingCreates = emptyList(),
            pendingOutfits = if (recoveryLoads == 1) listOf(ready) else emptyList(),
            pendingTravels = emptyList(),
            storyReceipts = emptyList(),
        )
    }

    override suspend fun applyOutfitOutcome(
        ownerId: String,
        petId: String,
        requestKey: String,
    ) = OutfitOutcomeApplicationResult.AlreadyApplied(newPet)

    override suspend fun consumeTravelAsset(
        ownerId: String,
        petId: String,
        requestKey: String,
        consumedAtEpochMillis: Long,
    ) = TravelAssetConsumptionResult.NotReady
}

private object RealPetGenerationAdapterTestAsset {
    fun asset(completeVideos: Boolean = true): com.gigagochi.app.core.network.GenerationAssetDto {
        val moods = mapOf(
            "idle" to "https://gigagochi.serega.works/static/i.png",
            "happy" to "https://gigagochi.serega.works/static/h.png",
            "hungry" to "https://gigagochi.serega.works/static/u.png",
            "sad" to "https://gigagochi.serega.works/static/s.png",
        )
        return com.gigagochi.app.core.network.GenerationAssetDto(
            "asset-new", "2026-07-17T10:11:12Z",
            mapOf("baby" to moods, "teen" to moods, "adult" to moods),
            videoUrl = "https://gigagochi.serega.works/static/idle.mp4",
            sadVideoUrl = if (completeVideos) "https://gigagochi.serega.works/static/sad.mp4" else null,
            happyVideoUrl = if (completeVideos) "https://gigagochi.serega.works/static/happy.mp4" else null,
        )
    }
}
