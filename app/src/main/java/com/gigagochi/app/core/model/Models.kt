package com.gigagochi.app.core.model

const val CharacterBibleMaxUtf8Bytes = 262_144

data class PetDashboardState(
    val petId: String,
    val assetSetId: String,
    val description: String,
    val name: String,
    val stage: String,
    val stageLabel: String,
    val mood: String,
    val experience: Int,
    val hunger: Int,
    val happiness: Int,
    val energy: Int,
    val message: String,
    val firstSessionActive: Boolean,
    val generatedMedia: PetGeneratedMedia = PetGeneratedMedia(),
)

data class PetMoodImage(
    val stage: String,
    val mood: String,
    val url: String,
)

data class PetGeneratedMedia(
    val generatedAt: String? = null,
    val videoUrl: String? = null,
    val sadVideoUrl: String? = null,
    val happyVideoUrl: String? = null,
    val blinkImageUrl: String? = null,
    val spriteSheetUrl: String? = null,
    val characterBibleJson: String? = null,
    val moodImages: List<PetMoodImage> = emptyList(),
)

data class ScheduledStoryResult(
    val text: String,
    val reaction: String,
    val consequence: String,
    val experienceGained: Int,
)

data class ScheduledStory(
    val storyId: String,
    val petId: String,
    val title: String,
    val text: String,
    val question: String,
    val choices: List<String>,
    val createdAt: String,
    val imageUrl: String?,
    val videoUrl: String?,
    val selectedChoice: String? = null,
    val result: ScheduledStoryResult? = null,
    val resultImageUrl: String? = null,
    val resultVideoUrl: String? = null,
)

class SensitiveToken private constructor(
    private val rawValue: String,
) {
    val length: Int get() = rawValue.length

    fun isBlank(): Boolean = rawValue.isBlank()

    internal fun reveal(): String = rawValue

    override fun equals(other: Any?): Boolean =
        other is SensitiveToken && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = "<redacted>"

    companion object {
        fun of(rawValue: String): SensitiveToken = SensitiveToken(rawValue)
    }
}

data class Session(
    val accountId: String,
    val accessToken: SensitiveToken,
    val refreshToken: SensitiveToken?,
    val expiresAtEpochMillis: Long,
)
