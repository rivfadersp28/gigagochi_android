package com.gigagochi.app.debugmenu

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.ScheduledStoryStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.model.ScheduledStoryResult
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.DueStoryRequestDto
import com.gigagochi.app.core.network.DueStoryResponseDto
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.ScheduledStoryChoiceRequestDto
import com.gigagochi.app.core.network.ScheduledStoryDto
import com.gigagochi.app.core.network.ScheduledStoryResultDto
import com.gigagochi.app.debug.DebugTestPetId

internal const val DebugAppleLegacyAnsweredStoryId =
    "android-story-a11e0000000000000000000000000001"
internal const val DebugAppleLegacyActiveStoryId =
    "android-story-a11e0000000000000000000000000002"
internal const val DebugAppleActiveStoryId =
    "android-story-a11e0000000000000000000000000003"

private const val DemoTravelMediaBase =
    "https://gigagochi.serega.works/static/demo/interactive-travel"
private const val BatStoryMediaBase =
    "https://gigagochi.serega.works/static/onboarding/bat-help"

private val ActiveChoices = listOf(
    "Пышную шерсть",
    "Тонкое железо",
    "Мокрый шёлк",
    "Каменную плиту",
)

private val ActiveOutcomeImageUrls = listOf(
    "$DemoTravelMediaBase/part-02.png",
    "$DemoTravelMediaBase/part-03.png",
    "$BatStoryMediaBase/situation.png",
    "$BatStoryMediaBase/success.png",
)

private val ActiveOutcomeVideoUrls = listOf(
    "$DemoTravelMediaBase/part-02.mp4",
    "$DemoTravelMediaBase/part-03.mp4",
    "$BatStoryMediaBase/situation.mp4",
    "$BatStoryMediaBase/success.mp4",
)

internal fun debugAppleStoryFixtures(ownerId: String): List<LocalScheduledStory> = listOf(
    LocalScheduledStory(
        ownerId = ownerId,
        story = activeDebugAppleStory(),
        notifiedAtEpochMillis = 1L,
    ),
)

private fun activeDebugAppleStory(): ScheduledStory = ScheduledStory(
    storyId = DebugAppleActiveStoryId,
    petId = DebugTestPetId,
    title = "Холодный привал",
    text = "Я оказался в холодном лагере. Я увидел замёрзшего спутника без тёплой одежды. Чтобы сохранить тепло и продолжить путь, нужно выбрать подходящую подкладку.",
    question = "Какую подкладку выбрать?",
    choices = ActiveChoices,
    createdAt = "2026-07-19T16:24:14Z",
    imageUrl = "$DemoTravelMediaBase/part-01.png",
    videoUrl = "$DemoTravelMediaBase/part-01.mp4",
)

suspend fun ensureDebugFixtureStories(
    ownerId: String,
    pet: PetDashboardState,
    store: ScheduledStoryStore,
): Boolean {
    if (pet.petId != DebugTestPetId) return true
    store.deleteScheduledStory(ownerId, DebugAppleLegacyAnsweredStoryId)
    store.deleteScheduledStory(ownerId, DebugAppleLegacyActiveStoryId)
    return debugAppleStoryFixtures(ownerId).fold(true) { saved, story ->
        store.saveScheduledStory(story) && saved
    }
}

fun debugScheduledStoryService(delegate: AndroidFeatureService): AndroidFeatureService =
    object : AndroidFeatureService by delegate {
        override suspend fun dueStory(request: DueStoryRequestDto): FeatureApiResult<DueStoryResponseDto> =
            delegate.dueStory(request)

        override suspend fun chooseStory(
            storyId: String,
            request: ScheduledStoryChoiceRequestDto,
        ): FeatureApiResult<ScheduledStoryDto> {
            if (storyId != DebugAppleActiveStoryId) {
                return delegate.chooseStory(storyId, request)
            }
            val story = activeDebugAppleStory()
            if (request.choice !in story.choices) {
                return delegate.chooseStory(storyId, request)
            }
            return FeatureApiResult.Success(
                story.copy(
                    selectedChoice = request.choice,
                    result = debugAppleChoiceResult(request.choice),
                    resultImageUrl = ActiveOutcomeImageUrls[story.choices.indexOf(request.choice)],
                    resultVideoUrl = ActiveOutcomeVideoUrls[story.choices.indexOf(request.choice)],
                ).toDto(),
            )
        }
    }

private fun debugAppleChoiceResult(choice: String): ScheduledStoryResult = when (choice) {
    ActiveChoices[0] -> ScheduledStoryResult(
        text = "Я выбрал пышную шерсть! Замёрзший путник укутался в неё, согрелся и поблагодарил меня.",
        reaction = "Тото облегчённо улыбается и поправляет тёплую подкладку.",
        consequence = "Воздух между волокнами шерсти плохо проводит тепло и замедляет охлаждение.",
        experienceGained = 50,
    )
    ActiveChoices[1] -> ScheduledStoryResult(
        text = "Я выбрал тонкое железо. Холодная пластина только сильнее остудила спутника.",
        reaction = "Тото хмурится и убирает железо подальше от привала.",
        consequence = "Для сохранения тепла нужна шерсть: воздух между её волокнами плохо проводит тепло.",
        experienceGained = 12,
    )
    ActiveChoices[2] -> ScheduledStoryResult(
        text = "Я выбрал мокрый шёлк. Ткань остыла на ветру, и спутник задрожал ещё сильнее.",
        reaction = "Тото сразу разводит костёр и меняет мокрую ткань на сухую.",
        consequence = "Мокрая ткань ускоряет потерю тепла, а шерсть удерживает тёплый воздух.",
        experienceGained = 8,
    )
    else -> ScheduledStoryResult(
        text = "Я выбрал каменную плиту. Спутник едва успел отскочить, а плита разбила котелок.",
        reaction = "Тото проверяет, что никто не пострадал, и ищет мягкую шерсть.",
        consequence = "Камень не сохраняет тепло так, как слой шерсти с воздухом между волокнами.",
        experienceGained = 5,
    )
}

private fun ScheduledStory.toDto() = ScheduledStoryDto(
    storyId = storyId,
    petId = petId,
    title = title,
    text = text,
    question = question,
    choices = choices,
    createdAt = createdAt,
    imageUrl = imageUrl,
    videoUrl = videoUrl,
    selectedChoice = selectedChoice,
    result = result?.let {
        ScheduledStoryResultDto(
            text = it.text,
            adviceAssessment = "helpful",
            reaction = it.reaction,
            reactionTone = "enthusiastic",
            consequence = it.consequence,
            outcomeValence = "positive",
            experienceGained = it.experienceGained,
        )
    },
    resultImageUrl = resultImageUrl,
    resultVideoUrl = resultVideoUrl,
)
