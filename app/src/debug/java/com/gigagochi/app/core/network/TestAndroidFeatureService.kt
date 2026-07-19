package com.gigagochi.app.core.network

import com.gigagochi.app.core.model.PetGeneratedMedia

open class TestAndroidFeatureService : AndroidFeatureService {
    private fun <T> unavailable(): FeatureApiResult<T> =
        FeatureApiResult.Failure(FeatureFailure(FeatureFailureKind.Server))

    override suspend fun submitCreate(request: CreateJobRequestDto) = unavailable<GenerationEnvelopeDto>()
    override suspend fun pollCreate(jobId: String) = unavailable<GenerationEnvelopeDto>()
    override suspend fun chat(request: ChatRequestDto) = unavailable<ChatResponseDto>()
    override suspend fun extractMemory(request: MemoryExtractionRequestDto) =
        unavailable<MemoryExtractionResponseDto>()
    override suspend fun consolidateMemory(request: MemoryConsolidationRequestDto) =
        unavailable<MemoryConsolidationResponseDto>()
    override suspend fun proactive(request: ProactiveRequestDto) = unavailable<ProactiveResponseDto>()
    override suspend fun simplifyOutfit(request: OutfitSimplifyRequestDto) =
        unavailable<OutfitSimplifyResponseDto>()
    override suspend fun submitOutfit(request: OutfitJobRequestDto) = unavailable<GenerationEnvelopeDto>()
    override suspend fun pollOutfit(jobId: String) = unavailable<GenerationEnvelopeDto>()
    override suspend fun submitTravel(request: TravelVideoRequestDto) = unavailable<TravelVideoDto>()
    override suspend fun pollTravel(jobId: String) = unavailable<TravelVideoDto>()
    override suspend fun dueStory(request: DueStoryRequestDto) = unavailable<DueStoryResponseDto>()
    override suspend fun chooseStory(storyId: String, request: ScheduledStoryChoiceRequestDto) =
        unavailable<ScheduledStoryDto>()
    override fun resolveMediaUrl(value: String?): String? = value
    override fun media(dto: GenerationAssetDto): PetGeneratedMedia? = PetGeneratedMedia(
        generatedAt = dto.generatedAt,
        moodImages = dto.images.flatMap { (stage, moods) ->
            moods.map { (mood, url) ->
                com.gigagochi.app.core.model.PetMoodImage(stage, mood, url)
            }
        },
    )
    override fun story(dto: ScheduledStoryDto) = com.gigagochi.app.core.model.ScheduledStory(
        dto.storyId,
        dto.petId,
        dto.title,
        dto.text,
        dto.question,
        dto.choices,
        dto.createdAt,
        dto.imageUrl,
        dto.videoUrl,
        dto.selectedChoice,
        dto.result?.let {
            com.gigagochi.app.core.model.ScheduledStoryResult(
                it.text,
                it.reaction,
                it.consequence,
                it.experienceGained,
            )
        },
        dto.resultImageUrl,
        dto.resultVideoUrl,
    )
}
