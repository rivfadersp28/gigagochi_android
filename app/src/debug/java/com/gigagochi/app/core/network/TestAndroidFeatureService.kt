package com.gigagochi.app.core.network

import com.gigagochi.app.core.model.PetGeneratedMedia

open class TestAndroidFeatureService : AndroidFeatureService {
    private fun <T> unavailable(): FeatureApiResult<T> =
        FeatureApiResult.Failure(FeatureFailure(FeatureFailureKind.Server))

    override suspend fun submitCreate(request: CreateJobRequestDto) = unavailable<GenerationEnvelopeDto>()
    override suspend fun pollCreate(jobId: String) = unavailable<GenerationEnvelopeDto>()
    override suspend fun chat(request: ChatRequestDto) = unavailable<ChatResponseDto>()
    override suspend fun simplifyOutfit(request: OutfitSimplifyRequestDto) =
        unavailable<OutfitSimplifyResponseDto>()
    override suspend fun submitOutfit(request: OutfitJobRequestDto) = unavailable<GenerationEnvelopeDto>()
    override suspend fun pollOutfit(jobId: String) = unavailable<GenerationEnvelopeDto>()
    override suspend fun submitTravel(request: TravelVideoRequestDto) = unavailable<TravelVideoDto>()
    override suspend fun pollTravel(jobId: String) = unavailable<TravelVideoDto>()
    override fun resolveMediaUrl(value: String?): String? = value
    override fun media(dto: GenerationAssetDto): PetGeneratedMedia? = PetGeneratedMedia(
        generatedAt = dto.generatedAt,
        moodImages = dto.images.flatMap { (stage, moods) ->
            moods.map { (mood, url) ->
                com.gigagochi.app.core.model.PetMoodImage(stage, mood, url)
            }
        },
    )
}
