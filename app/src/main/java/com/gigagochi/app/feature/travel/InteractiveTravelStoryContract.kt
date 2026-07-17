package com.gigagochi.app.feature.travel

const val OnboardingBatStoryTitle = "Малыш летучей мыши"
const val OnboardingBatCorrectChoice = "Млекопитающие"
const val OnboardingBatExperienceGain = 200
const val OnboardingBatTravelIdPrefix = "onboarding-bat-help-v1-"
const val TravelStoryChoiceFailureMessage =
    "Не получилось продолжить историю. Попробуй ещё раз."

val OnboardingBatChoices = listOf(
    "Птицы",
    OnboardingBatCorrectChoice,
    "Насекомые",
    "Пресмыкающиеся",
)

data class InteractiveTravelStory(
    val travelId: String,
    val title: String,
    val storyText: String,
    val challenge: String,
    val choices: List<String>,
    val enabledChoice: String,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
)

data class InteractiveTravelStoryResult(
    val travelId: String,
    val requestKey: String,
    val answer: String,
    val text: String,
    val reaction: String,
    val consequence: String,
    val experienceGained: Int,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
)

data class PendingTravelStoryChoice(
    val travelId: String,
    val requestKey: String,
    val choice: String,
)

fun onboardingBatStory(petId: String): InteractiveTravelStory = InteractiveTravelStory(
    travelId = "$OnboardingBatTravelIdPrefix$petId".take(160),
    title = OnboardingBatStoryTitle,
    storyText =
        "Ой, что это? Под крышей пищит детёныш летучей мыши. Его мама рядом и хочет " +
            "его накормить. Чтобы понять, чем она кормит малыша, нужно узнать, к какой " +
            "группе относится летучая мышь.",
    challenge = "К какой группе относится летучая мышь?",
    choices = OnboardingBatChoices,
    enabledChoice = OnboardingBatCorrectChoice,
)

fun onboardingBatStoryResult(
    story: InteractiveTravelStory,
    requestKey: String,
): InteractiveTravelStoryResult = InteractiveTravelStoryResult(
    travelId = story.travelId,
    requestKey = requestKey,
    answer = OnboardingBatCorrectChoice,
    text =
        "Летучая мышь относится к млекопитающим. Мама добралась до малыша, согрела его " +
            "и накормила молоком.",
    reaction = "Получилось! Малыш снова рядом с мамой.",
    consequence = "Малыш в безопасности рядом с мамой.",
    experienceGained = OnboardingBatExperienceGain,
)

fun isOnboardingBatStory(story: InteractiveTravelStory): Boolean =
    story.travelId.startsWith(OnboardingBatTravelIdPrefix)

fun onboardingBatQuestionParagraphs(story: InteractiveTravelStory): List<String> = listOf(
    "Ой, что это?",
    "Под крышей пищит детёныш летучей мыши.",
    "Его мама рядом и хочет его накормить.",
    "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
    story.challenge,
)

fun onboardingBatResultParagraphs(result: InteractiveTravelStoryResult): List<String> = listOf(
    "Летучая мышь относится к млекопитающим.",
    "Мама добралась до малыша, согрела его и накормила молоком.",
)
