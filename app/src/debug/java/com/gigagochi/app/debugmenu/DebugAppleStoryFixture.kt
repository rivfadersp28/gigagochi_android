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
internal const val DebugApplePreviousActiveStoryId =
    "android-story-a11e0000000000000000000000000003"
internal const val DebugAppleActiveStoryId =
    "android-story-88ea8985973b4bdbb13bbf31b3a8387e"

private const val GeneratedStoryMediaBase =
    "https://gigagochi.serega.works/static/generated/" +
        "interactive-travel-android-story-88ea8985973b4bdbb13bbf31b3a8387e"

private val ActiveChoices = listOf(
    "Камень",
    "Перо",
    "Лист",
    "Комок водорослей",
)

private val ActiveOutcomeImageUrls = List(4) { index ->
    "$GeneratedStoryMediaBase/interactive-travel-part-01-outcome-$index.png"
}

private val ActiveOutcomeVideoUrls = List(4) { index ->
    "$GeneratedStoryMediaBase/interactive-travel-part-01-outcome-$index.mp4"
}

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
    title = "Каменная наковальня",
    text = "Я шёл по новой тропе и встретил животное, которому требовалась помощь. " +
        "Морская выдра принесла закрытую раковину и выбирает инструмент. Чтобы " +
        "преодолеть препятствие и продолжить путь, мне нужно было определить, что " +
        "выдра может использовать, чтобы разбить раковину.",
    question = "Что выдра может использовать, чтобы разбить раковину?",
    choices = ActiveChoices,
    createdAt = "2026-07-19T17:04:20.818240Z",
    imageUrl = "$GeneratedStoryMediaBase/interactive-travel-part-01.png",
    videoUrl = "$GeneratedStoryMediaBase/interactive-travel-part-01.mp4",
)

suspend fun ensureDebugFixtureStories(
    ownerId: String,
    pet: PetDashboardState,
    store: ScheduledStoryStore,
): Boolean {
    if (pet.petId != DebugTestPetId) return true
    store.deleteScheduledStory(ownerId, DebugAppleLegacyAnsweredStoryId)
    store.deleteScheduledStory(ownerId, DebugAppleLegacyActiveStoryId)
    store.deleteScheduledStory(ownerId, DebugApplePreviousActiveStoryId)
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
        text = "Камень стал наковальней, и раковина раскрылась. Тропа освободилась, и путешествие продолжилось.",
        reaction = "Тото довольно кивает выдре и убирает камень с тропы.",
        consequence = "Морские выдры действительно используют камни, чтобы разбивать твёрдые раковины.",
        experienceGained = 50,
    )
    ActiveChoices[1] -> ScheduledStoryResult(
        text = "Перо только защекотало створки. Течение унесло мой запас еды, поэтому до привала я добрался голодным.",
        reaction = "Тото ловит перо и смотрит вслед унесённому припасу.",
        consequence = "Перо слишком мягкое: твёрдую раковину удобнее разбивать камнем.",
        experienceGained = 12,
    )
    ActiveChoices[2] -> ScheduledStoryResult(
        text = "Лист порвался о твёрдую раковину. Шум распугал проводников, и я остался у закрытого прохода.",
        reaction = "Тото собирает обрывки листа и прислушивается к стихшему берегу.",
        consequence = "Лист не выдерживает удара; для раковины нужен твёрдый камень.",
        experienceGained = 8,
    )
    else -> ScheduledStoryResult(
        text = "Водоросли обмотали добычу, но не открыли её. Я промочил карту, заблудился и заночевал на сыром берегу.",
        reaction = "Тото распутывает водоросли и сушит карту у маленького огня.",
        consequence = "Водоросли гнутся и не разбивают раковину; правильный инструмент — камень.",
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
