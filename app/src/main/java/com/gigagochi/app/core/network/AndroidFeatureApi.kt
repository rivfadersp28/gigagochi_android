package com.gigagochi.app.core.network

import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.PetGeneratedMedia
import com.gigagochi.app.core.model.PetMoodImage
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.net.URI
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val FeatureJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = true
    encodeDefaults = true
}
private val UUID4Pattern = Regex(
    "^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$",
)
private val StoryIdPattern = Regex("^android-story-[a-f0-9]{32}$")

enum class FeatureFailureKind {
    Network,
    Storage,
    SessionInvalid,
    RefreshUnavailable,
    BadRequest,
    Conflict,
    RateLimited,
    InProgress,
    OutcomeUnknown,
    NotFound,
    Server,
    Protocol,
}

data class FeatureFailure(
    val kind: FeatureFailureKind,
    val code: String? = null,
) {
    override fun toString(): String = "FeatureFailure(kind=$kind,code=${code ?: "none"},<redacted>)"
}

sealed interface FeatureApiResult<out T> {
    data class Success<T>(val value: T) : FeatureApiResult<T>
    data class Failure(val failure: FeatureFailure) : FeatureApiResult<Nothing>
}

@Serializable
data class FeaturePetStatsDto(val hunger: Int, val happiness: Int, val energy: Int)

@Serializable
data class FeaturePetDto(
    val petId: String,
    val name: String? = null,
    val description: String,
    val stage: String,
    val mood: String,
    val stats: FeaturePetStatsDto,
    val characterBible: JsonObject? = null,
    val assetImages: Map<String, Map<String, String>>? = null,
)

@Serializable
data class GenerationAssetDto(
    val assetSetId: String,
    val generatedAt: String,
    val images: Map<String, Map<String, String>>,
    val videoUrl: String? = null,
    val sadVideoUrl: String? = null,
    val happyVideoUrl: String? = null,
    val blinkImageUrl: String? = null,
    val spriteSheetUrl: String? = null,
    val characterBible: JsonObject? = null,
)

@Serializable
enum class GenerationJobPhaseDto {
    @SerialName("queued") Queued,
    @SerialName("generating_images") GeneratingImages,
    @SerialName("generating_video") GeneratingVideo,
    @SerialName("generating_sad_image") GeneratingSadImage,
    @SerialName("generating_sad_video") GeneratingSadVideo,
    @SerialName("generating_happy_image") GeneratingHappyImage,
    @SerialName("generating_happy_video") GeneratingHappyVideo,
    @SerialName("generating_kandinsky") GeneratingKandinsky,
    @SerialName("completed") Completed,
}

@Serializable
enum class GenerationJobStatusDto {
    @SerialName("queued") Queued,
    @SerialName("running") Running,
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
}

@Serializable
data class GenerationJobDto(
    val jobId: String,
    val status: GenerationJobStatusDto,
    val phase: GenerationJobPhaseDto,
    val createdAt: String,
    val updatedAt: String,
    val result: GenerationAssetDto? = null,
    val error: JsonObject? = null,
    val backgroundError: JsonObject? = null,
    val comparisonError: JsonObject? = null,
) {
    init {
        requireValidJobId(jobId)
        requireIsoTimestamp(createdAt)
        requireIsoTimestamp(updatedAt)
    }
}

@Serializable
data class GenerationEnvelopeDto(
    val requestKey: String? = null,
    val petId: String? = null,
    val job: GenerationJobDto,
)

@Serializable
data class CreateJobRequestDto(val requestKey: String, val petId: String, val description: String)

@Serializable
data class ChatRequestDto(
    val requestKey: String,
    val message: String,
    val pet: FeaturePetDto,
)

@Serializable
data class ChatResponseDto(
    val reply: String,
    val moodHint: String? = null,
    val happinessDelta: Int = 0,
    val complimentKey: String? = null,
    val innerThought: String? = null,
    val faceHint: String? = null,
    val petPatch: JsonObject? = null,
    val storyLibraryPatch: JsonObject? = null,
    val debug: JsonObject? = null,
)

@Serializable
data class OutfitSimplifyRequestDto(
    val requestKey: String,
    val request: String,
    val petDescription: String,
)

@Serializable
data class OutfitSimplifyResponseDto(
    val item: String,
    val displayItem: String,
    val generationDescription: String,
)

@Serializable
data class OutfitJobRequestDto(
    val requestKey: String,
    val petId: String,
    val prompt: String,
    val idleImageUrl: String,
    val sadImageUrl: String,
    val happyImageUrl: String,
)

@Serializable
data class TravelVideoRequestDto(
    val requestKey: String,
    val pet: FeaturePetDto,
    val prompt: String,
)

@Serializable
enum class TravelVideoStatusDto {
    @SerialName("queued") Queued,
    @SerialName("writing") Writing,
    @SerialName("illustrating") Illustrating,
    @SerialName("animating") Animating,
    @SerialName("ready") Ready,
    @SerialName("failed") Failed,
}

@Serializable
data class TravelVideoDto(
    val jobId: String,
    val status: TravelVideoStatusDto,
    val prompt: String,
    val title: String? = null,
    val scenario: String? = null,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val error: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        requireValidJobId(jobId)
        requireIsoTimestamp(createdAt)
        requireIsoTimestamp(updatedAt)
    }
}

@Serializable
data class DueStoryRequestDto(val pet: FeaturePetDto)

@Serializable
data class ScheduledStoryResultDto(
    val text: String,
    val adviceAssessment: String,
    val reaction: String,
    val reactionTone: String,
    val consequence: String,
    val outcomeValence: String,
    val experienceGained: Int,
) {
    init {
        require(text.isNotBlank() && text.length <= 700)
        require(adviceAssessment in setOf("helpful", "harmful", "ambiguous"))
        require(reaction.isNotBlank() && reaction.length <= 220)
        require(
            reactionTone in setOf(
                "enthusiastic", "confused", "worried", "amused", "indignant",
                "determined", "surprised",
            ),
        )
        require(consequence.isNotBlank() && consequence.length <= 280)
        require(outcomeValence in setOf("positive", "negative"))
        require(experienceGained in 0..150)
    }
}

@Serializable
data class ScheduledStoryDto(
    val storyId: String,
    val petId: String,
    val title: String,
    val text: String,
    val question: String,
    val choices: List<String>,
    val createdAt: String,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val selectedChoice: String? = null,
    val result: ScheduledStoryResultDto? = null,
    val resultImageUrl: String? = null,
    val resultVideoUrl: String? = null,
) {
    init {
        require(StoryIdPattern.matches(storyId))
        require(petId.isNotBlank() && petId.length <= 120)
        require(title.isNotBlank() && title.length <= 120)
        require(text.isNotBlank() && text.length <= 700)
        require(question.isNotBlank() && question.length <= 280)
        require(choices.size == 4 && choices.toSet().size == 4)
        require(choices.all { it.isNotBlank() && it.length <= 280 })
        requireIsoTimestamp(createdAt)
        require((selectedChoice == null) == (result == null))
        require(selectedChoice == null || selectedChoice in choices)
        require(resultImageUrl == null || selectedChoice != null)
        require(resultVideoUrl == null || selectedChoice != null)
    }
}

@Serializable
data class DueStoryResponseDto(val story: ScheduledStoryDto? = null)

@Serializable
data class ScheduledStoryChoiceRequestDto(
    val requestKey: String,
    val choice: String,
) {
    init {
        require(UUID4Pattern.matches(requestKey))
        require(choice.isNotBlank() && choice.length <= 280)
    }
}

interface AndroidFeatureService {
    suspend fun submitCreate(request: CreateJobRequestDto): FeatureApiResult<GenerationEnvelopeDto>
    suspend fun pollCreate(jobId: String): FeatureApiResult<GenerationEnvelopeDto>
    suspend fun chat(request: ChatRequestDto): FeatureApiResult<ChatResponseDto>
    suspend fun simplifyOutfit(request: OutfitSimplifyRequestDto): FeatureApiResult<OutfitSimplifyResponseDto>
    suspend fun submitOutfit(request: OutfitJobRequestDto): FeatureApiResult<GenerationEnvelopeDto>
    suspend fun pollOutfit(jobId: String): FeatureApiResult<GenerationEnvelopeDto>
    suspend fun submitTravel(request: TravelVideoRequestDto): FeatureApiResult<TravelVideoDto>
    suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto>
    suspend fun dueStory(request: DueStoryRequestDto): FeatureApiResult<DueStoryResponseDto>
    suspend fun chooseStory(
        storyId: String,
        request: ScheduledStoryChoiceRequestDto,
    ): FeatureApiResult<ScheduledStoryDto>
    fun resolveMediaUrl(value: String?): String?
    fun media(dto: GenerationAssetDto): PetGeneratedMedia?
    fun story(dto: ScheduledStoryDto): ScheduledStory?
}

class AndroidFeatureApi(
    private val client: AuthenticatedFeatureClient,
    baseUrl: String,
    allowDebugLoopbackHttp: Boolean,
) : AndroidFeatureService {
    private val base = validatedFeatureBaseUrl(baseUrl, allowDebugLoopbackHttp)
        ?: throw IllegalArgumentException("Invalid backend base URL")
    private val mediaUrlPolicy = StaticMediaUrlPolicy(baseUrl, allowDebugLoopbackHttp)

    override suspend fun submitCreate(request: CreateJobRequestDto): FeatureApiResult<GenerationEnvelopeDto> =
        post("/api/android/create/jobs", request)

    override suspend fun pollCreate(jobId: String): FeatureApiResult<GenerationEnvelopeDto> = poll(
        "/api/android/create/jobs/",
        jobId,
    )

    override suspend fun chat(request: ChatRequestDto): FeatureApiResult<ChatResponseDto> =
        post("/api/android/chat", request)

    override suspend fun simplifyOutfit(
        request: OutfitSimplifyRequestDto,
    ): FeatureApiResult<OutfitSimplifyResponseDto> = post("/api/android/outfit/simplify", request)

    override suspend fun submitOutfit(
        request: OutfitJobRequestDto,
    ): FeatureApiResult<GenerationEnvelopeDto> = post("/api/android/outfit/jobs", request)

    override suspend fun pollOutfit(jobId: String): FeatureApiResult<GenerationEnvelopeDto> = poll(
        "/api/android/outfit/jobs/",
        jobId,
    )

    override suspend fun submitTravel(request: TravelVideoRequestDto): FeatureApiResult<TravelVideoDto> =
        post("/api/android/travel-video/jobs", request)

    override suspend fun pollTravel(jobId: String): FeatureApiResult<TravelVideoDto> = poll(
        "/api/android/travel-video/jobs/",
        jobId,
    )

    override suspend fun dueStory(
        request: DueStoryRequestDto,
    ): FeatureApiResult<DueStoryResponseDto> = post("/api/android/stories/due", request)

    override suspend fun chooseStory(
        storyId: String,
        request: ScheduledStoryChoiceRequestDto,
    ): FeatureApiResult<ScheduledStoryDto> {
        val validated = storyId.takeIf(StoryIdPattern::matches)
            ?: return failure(FeatureFailureKind.Protocol)
        return post("/api/android/stories/$validated/choice", request)
    }

    override fun resolveMediaUrl(value: String?): String? = mediaUrlPolicy.resolve(value)

    override fun media(dto: GenerationAssetDto): PetGeneratedMedia? = runCatching {
        require(dto.assetSetId.isNotBlank())
        requireIsoTimestamp(dto.generatedAt)
        require(dto.images.keys == RequiredAssetStages)
        require(dto.images.values.all { it.keys == RequiredAssetMoods })
        val images = dto.images.flatMap { (stage, moods) ->
            moods.map { (mood, url) ->
                PetMoodImage(stage, mood, requireNotNull(resolveMediaUrl(url)))
            }
        }
        require(images.isNotEmpty())
        PetGeneratedMedia(
            generatedAt = dto.generatedAt,
            videoUrl = resolveOptionalMediaUrl(dto.videoUrl),
            sadVideoUrl = resolveOptionalMediaUrl(dto.sadVideoUrl),
            happyVideoUrl = resolveOptionalMediaUrl(dto.happyVideoUrl),
            blinkImageUrl = resolveOptionalMediaUrl(dto.blinkImageUrl),
            spriteSheetUrl = resolveOptionalMediaUrl(dto.spriteSheetUrl),
            characterBibleJson = dto.characterBible?.toString(),
            moodImages = images,
        )
    }.getOrNull()

    override fun story(dto: ScheduledStoryDto): ScheduledStory? = runCatching {
        ScheduledStory(
            storyId = dto.storyId,
            petId = dto.petId,
            title = dto.title,
            text = dto.text,
            question = dto.question,
            choices = dto.choices,
            createdAt = dto.createdAt,
            imageUrl = dto.imageUrl?.let { requireNotNull(resolveMediaUrl(it)) },
            videoUrl = dto.videoUrl?.let { requireNotNull(resolveMediaUrl(it)) },
            selectedChoice = dto.selectedChoice,
            result = dto.result?.let {
                ScheduledStoryResult(
                    text = it.text,
                    reaction = it.reaction,
                    consequence = it.consequence,
                    experienceGained = it.experienceGained,
                )
            },
            resultImageUrl = dto.resultImageUrl?.let { requireNotNull(resolveMediaUrl(it)) },
            resultVideoUrl = dto.resultVideoUrl?.let { requireNotNull(resolveMediaUrl(it)) },
        )
    }.getOrNull()

    private fun resolveOptionalMediaUrl(value: String?): String? =
        value?.let { requireNotNull(resolveMediaUrl(it)) }

    private suspend inline fun <reified Request : Any, reified Response : Any> post(
        path: String,
        request: Request,
    ): FeatureApiResult<Response> = call(
        FeatureHttpRequest(
            method = "POST",
            path = path,
            body = FeatureJson.encodeToString(request).toByteArray(StandardCharsets.UTF_8),
        ),
    )

    private suspend inline fun <reified Response : Any> get(path: String): FeatureApiResult<Response> =
        call(FeatureHttpRequest(method = "GET", path = path))

    private suspend inline fun <reified Response : Any> poll(
        prefix: String,
        jobId: String,
    ): FeatureApiResult<Response> {
        val validated = runCatching { validatedJobId(jobId) }.getOrNull()
            ?: return failure(FeatureFailureKind.Protocol)
        return get(prefix + validated)
    }

    private suspend inline fun <reified Response : Any> call(
        request: FeatureHttpRequest,
    ): FeatureApiResult<Response> = when (val result = client.execute(request)) {
        AuthenticatedFeatureResult.NetworkFailure -> failure(FeatureFailureKind.Network)
        AuthenticatedFeatureResult.RefreshUnavailable -> failure(FeatureFailureKind.RefreshUnavailable)
        AuthenticatedFeatureResult.SessionUnavailable -> failure(FeatureFailureKind.Storage)
        AuthenticatedFeatureResult.SessionInvalid -> failure(FeatureFailureKind.SessionInvalid)
        is AuthenticatedFeatureResult.Response -> decodeResponse(result.response)
    }

    private inline fun <reified Response : Any> decodeResponse(
        response: FeatureHttpResponse,
    ): FeatureApiResult<Response> {
        val text = strictUtf8(response.body) ?: return failure(FeatureFailureKind.Protocol)
        if (response.statusCode in 200..299) {
            val value = try {
                FeatureJson.decodeFromString<Response>(text)
            } catch (_: SerializationException) {
                return failure(FeatureFailureKind.Protocol)
            } catch (_: IllegalArgumentException) {
                return failure(FeatureFailureKind.Protocol)
            }
            return FeatureApiResult.Success(value)
        }
        val code = stableBackendCode(text)
        return FeatureApiResult.Failure(backendFailure(response.statusCode, code))
    }

    private fun backendFailure(status: Int, code: String?): FeatureFailure = FeatureFailure(
        kind = when (code) {
            "AUTH_INVALID" -> FeatureFailureKind.SessionInvalid
            "RATE_LIMITED" -> FeatureFailureKind.RateLimited
            "REQUEST_IN_PROGRESS", "SESSION_BUSY" -> FeatureFailureKind.InProgress
            "OUTCOME_UNKNOWN", "SESSION_OUTCOME_UNKNOWN" -> FeatureFailureKind.OutcomeUnknown
            "JOB_NOT_FOUND", "SESSION_NOT_FOUND", "PART_NOT_FOUND" -> FeatureFailureKind.NotFound
            "IDEMPOTENCY_CONFLICT", "GENERATION_ALREADY_ACTIVE", "PET_MISMATCH",
            "SESSION_CONFLICT", -> FeatureFailureKind.Conflict
            "GENERATION_QUEUE_FULL" -> FeatureFailureKind.Server
            else -> when (status) {
                401 -> FeatureFailureKind.SessionInvalid
                400, 422 -> FeatureFailureKind.BadRequest
                404 -> FeatureFailureKind.NotFound
                409 -> FeatureFailureKind.Conflict
                429 -> FeatureFailureKind.RateLimited
                in 500..599 -> FeatureFailureKind.Server
                else -> FeatureFailureKind.Protocol
            }
        },
        code = code?.take(80),
    )

    private fun stableBackendCode(text: String): String? = runCatching {
        val root = FeatureJson.parseToJsonElement(text).jsonObject
        val detail = root["detail"] as? JsonObject ?: return@runCatching null
        detail["code"]?.jsonPrimitive?.content?.takeIf {
            it.length in 1..80 && it.all { character -> character.isUpperCase() || character == '_' }
        }
    }.getOrNull()

    private fun validatedJobId(value: String): String {
        require(value.length in 1..255 && value == value.trim())
        require(value.all { it.isLetterOrDigit() || it in "-_" })
        return value
    }
}


private inline fun <T, R> FeatureApiResult<T>.flatMapProtocol(transform: (T) -> R?): FeatureApiResult<R> = when (this) {
    is FeatureApiResult.Failure -> this
    is FeatureApiResult.Success -> transform(value)?.let { FeatureApiResult.Success(it) }
        ?: failure(FeatureFailureKind.Protocol)
}

private val UuidV4Regex = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
)

private fun requireUuidV4(value: String) {
    require(UuidV4Regex.matches(value))
}

private val RequiredAssetStages = setOf("baby", "teen", "adult")
private val RequiredAssetMoods = setOf("idle", "happy", "hungry", "sad")
private val CacheVersionRegex = Regex("^[A-Za-z0-9_-]{1,64}$")
private val IsoTimestampRegex = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})$",
)

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun validateCacheQuery(rawQuery: String?) {
    if (rawQuery == null) return
    val pairs = rawQuery.split('&')
    require(pairs.size == 1)
    val components = pairs.single().split('=', limit = 2)
    require(components.size == 2 && components[0] == "v")
    require(CacheVersionRegex.matches(components[1]))
}

private fun requireIsoTimestamp(value: String) {
    require(value.length in 20..80)
    val match = requireNotNull(IsoTimestampRegex.matchEntire(value))
    val zoneText = match.groupValues[7]
    if (zoneText != "Z") {
        require(zoneText.substring(1, 3).toInt() <= 23)
        require(zoneText.substring(4, 6).toInt() <= 59)
    }
    val zone = if (zoneText == "Z") TimeZone.getTimeZone("UTC") else {
        TimeZone.getTimeZone("GMT$zoneText")
    }
    GregorianCalendar(zone).apply {
        isLenient = false
        clear()
        set(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt() - 1,
            match.groupValues[3].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt(),
        )
        timeInMillis
    }
}

private fun requireValidJobId(value: String) {
    require(value.length in 1..255 && value == value.trim())
    require(value.all { it.isLetterOrDigit() || it == '-' || it == '_' })
}

private fun strictUtf8(bytes: ByteArray): String? = try {
    require(bytes.isNotEmpty() && bytes.size <= FeatureResponseMaxBytes)
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    null
}

private fun failure(kind: FeatureFailureKind): FeatureApiResult.Failure =
    FeatureApiResult.Failure(FeatureFailure(kind))

fun PetDashboardState.toFeaturePetDto(): FeaturePetDto = FeaturePetDto(
    petId = petId,
    name = name,
    description = description,
    stage = stage,
    mood = mood,
    stats = FeaturePetStatsDto(hunger, happiness, energy),
    characterBible = generatedMedia.characterBibleJson?.let { raw ->
        runCatching { FeatureJson.parseToJsonElement(raw) as JsonObject }.getOrNull()
    },
    assetImages = generatedMedia.moodImages.groupBy(PetMoodImage::stage).mapValues { (_, values) ->
        values.associate { it.mood to it.url }
    }.takeIf { it.isNotEmpty() },
)
