package com.gigagochi.app.core.webview

import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.feature.events.EventHistoryItem
import com.gigagochi.app.feature.travel.InteractiveTravelStory
import com.gigagochi.app.feature.travel.InteractiveTravelStoryResult
import com.gigagochi.app.feature.travel.onboardingBatQuestionParagraphs
import com.gigagochi.app.feature.travel.onboardingBatResultParagraphs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val WebOnboardingBatStoryId = "onboarding-bat-help-v1"

@Serializable
internal data class WebScheduledStoryResultSnapshot(
    val text: String,
    val reaction: String,
    val consequence: String,
    val experienceGained: Int,
)

@Serializable
internal data class WebScheduledStorySnapshot(
    val storyId: String,
    val title: String,
    val text: String,
    val question: String,
    val choices: List<String>,
    val createdAt: String,
    val imageRef: String? = null,
    val videoRef: String? = null,
    val selectedChoice: String? = null,
    val result: WebScheduledStoryResultSnapshot? = null,
    val resultImageRef: String? = null,
    val resultVideoRef: String? = null,
)

/** Owner and durable choice bookkeeping intentionally stay on the native side. */
@Serializable
internal data class WebScheduledStoryEventSnapshot(
    val story: WebScheduledStorySnapshot,
)

/**
 * Public travel card data. Sharing sends [requestKey] back to native; no owner, pet, backend job or
 * source URL crosses the bridge.
 */
@Serializable
internal data class WebTravelVideoEventSnapshot(
    val requestKey: String,
    val prompt: String,
    val title: String? = null,
    val scenario: String? = null,
    val imageRef: String? = null,
    val videoRef: String? = null,
    val completedAtEpochMillis: Long,
)

@Serializable
internal data class WebEventsSnapshot(
    val stories: List<WebScheduledStoryEventSnapshot>,
    val travelVideos: List<WebTravelVideoEventSnapshot>,
    val badgeCount: Int,
    val latestEventAtEpochMillis: Long? = null,
    val lastViewedAtEpochMillis: Long? = null,
    val initialFocusTravelRequestKey: String? = null,
)

@Serializable
internal enum class WebDurableStoryPhase {
    @SerialName("question")
    Question,

    @SerialName("choicePending")
    ChoicePending,

    @SerialName("retryable")
    Retryable,

    @SerialName("result")
    Result,
}

@Serializable
internal enum class WebDurableStoryKind {
    @SerialName("scheduled")
    Scheduled,

    @SerialName("onboardingBat")
    OnboardingBat,
}

@Serializable
internal enum class WebDurableStoryOrigin {
    @SerialName("events")
    Events,

    @SerialName("dashboard")
    Dashboard,
}

@Serializable
internal data class WebOpenedStoryContentSnapshot(
    val storyId: String,
    val title: String,
    val text: String,
    val question: String,
    val choices: List<String>,
    val enabledChoice: String? = null,
    val questionParagraphs: List<String>,
    val imageRef: String? = null,
    val videoRef: String? = null,
)

@Serializable
internal data class WebOpenedStoryResultSnapshot(
    val requestKey: String,
    val answer: String,
    val text: String,
    val reaction: String,
    val consequence: String,
    val experienceGained: Int,
    val paragraphs: List<String>,
    val imageRef: String? = null,
    val videoRef: String? = null,
)

@Serializable
internal data class WebOpenedStorySnapshot(
    val phase: WebDurableStoryPhase,
    val kind: WebDurableStoryKind,
    val origin: WebDurableStoryOrigin,
    val story: WebOpenedStoryContentSnapshot,
    val durableRequestKey: String? = null,
    val pendingChoice: String? = null,
    val result: WebOpenedStoryResultSnapshot? = null,
    val error: String? = null,
)

internal fun projectEventStorySnapshotForWeb(
    snapshot: EventStorySnapshot,
    registry: WebMediaReferenceRegistry,
    initialFocusTravelRequestKey: String? = null,
): WebEventsSnapshot {
    val stories = mutableListOf<WebScheduledStoryEventSnapshot>()
    val travelVideos = mutableListOf<WebTravelVideoEventSnapshot>()
    // Register in the canonical newest-first history order. The registry admits only the newest
    // bounded media window, irrespective of whether an item is a scheduled story or travel video.
    snapshot.history.items.forEach { historyItem ->
        when (historyItem) {
            is EventHistoryItem.ScheduledStory -> historyItem.item
                .takeIf { item -> item.story.petId == snapshot.pet.petId }
                ?.toWebSnapshot(registry)
                ?.let(stories::add)

            is EventHistoryItem.TravelVideo -> historyItem.asset
                .takeIf { asset ->
                    asset.petId == snapshot.pet.petId && asset.consumedAtEpochMillis != null
                }
                ?.toWebSnapshot(registry)
                ?.let(travelVideos::add)
            }
    }
    return WebEventsSnapshot(
        stories = stories,
        travelVideos = travelVideos,
        badgeCount = snapshot.badgeCount,
        latestEventAtEpochMillis = snapshot.latestEventAtEpochMillis,
        lastViewedAtEpochMillis = snapshot.lastViewedAtEpochMillis,
        initialFocusTravelRequestKey = initialFocusTravelRequestKey?.takeIf { requestedKey ->
            travelVideos.any { item -> item.requestKey == requestedKey }
        },
    )
}

internal fun projectDurableStorySnapshotForWeb(
    snapshot: DurableStorySnapshot,
    origin: WebDurableStoryOrigin,
    registry: WebMediaReferenceRegistry,
): WebOpenedStorySnapshot {
    val isOnboarding = snapshot.kind == DurableStoryKind.OnboardingBat
    require(!isOnboarding || origin == WebDurableStoryOrigin.Dashboard) {
        "Onboarding story can only originate from Dashboard"
    }
    return WebOpenedStorySnapshot(
        phase = snapshot.phase.toWebPhase(),
        kind = snapshot.kind.toWebKind(),
        origin = origin,
        story = snapshot.story.toWebSnapshot(
            registry = registry,
            isOnboarding = isOnboarding,
            externalStoryId = snapshot.externalStoryId(),
        ),
        durableRequestKey = snapshot.durableRequestKey,
        pendingChoice = snapshot.pendingChoice,
        result = snapshot.result?.toWebSnapshot(
            registry,
            isOnboarding,
            snapshot.externalStoryId(),
        ),
        error = snapshot.error,
    )
}

private fun LocalScheduledStory.toWebSnapshot(
    registry: WebMediaReferenceRegistry,
) = WebScheduledStoryEventSnapshot(
    story = WebScheduledStorySnapshot(
        storyId = story.storyId,
        title = story.title,
        text = story.text,
        question = story.question,
        choices = story.choices.toList(),
        createdAt = story.createdAt,
        imageRef = registry.register(
            story.imageUrl,
            WebMediaKind.Image,
            slot = "scheduled:${story.storyId}:question-image",
            scope = WebMediaOwnerScope(ownerId, story.petId),
            priority = WebMediaProjectionPriority.History,
        ),
        videoRef = registry.register(
            story.videoUrl,
            WebMediaKind.Video,
            slot = "scheduled:${story.storyId}:question-video",
            scope = WebMediaOwnerScope(ownerId, story.petId),
            priority = WebMediaProjectionPriority.History,
        ),
        selectedChoice = story.selectedChoice,
        result = story.result?.let { result ->
            WebScheduledStoryResultSnapshot(
                text = result.text,
                reaction = result.reaction,
                consequence = result.consequence,
                experienceGained = result.experienceGained,
            )
        },
        resultImageRef = registry.register(
            story.resultImageUrl,
            WebMediaKind.Image,
            slot = "scheduled:${story.storyId}:result-image",
            scope = WebMediaOwnerScope(ownerId, story.petId),
            priority = WebMediaProjectionPriority.History,
        ),
        resultVideoRef = registry.register(
            story.resultVideoUrl,
            WebMediaKind.Video,
            slot = "scheduled:${story.storyId}:result-video",
            scope = WebMediaOwnerScope(ownerId, story.petId),
            priority = WebMediaProjectionPriority.History,
        ),
    ),
)

private fun LocalTravelVideoAsset.toWebSnapshot(
    registry: WebMediaReferenceRegistry,
): WebTravelVideoEventSnapshot = WebTravelVideoEventSnapshot(
    requestKey = requestKey,
    prompt = prompt,
    title = title,
    scenario = scenario,
    imageRef = registry.register(
        imageUrl,
        WebMediaKind.Image,
        slot = "travel:$requestKey:image",
        scope = WebMediaOwnerScope(ownerId, petId),
        priority = WebMediaProjectionPriority.History,
    ),
    videoRef = registry.register(
        videoUrl,
        WebMediaKind.Video,
        slot = "travel:$requestKey:video",
        scope = WebMediaOwnerScope(ownerId, petId),
        priority = WebMediaProjectionPriority.History,
    ),
    completedAtEpochMillis = completedAtEpochMillis,
)

private fun InteractiveTravelStory.toWebSnapshot(
    registry: WebMediaReferenceRegistry,
    isOnboarding: Boolean,
    externalStoryId: String,
): WebOpenedStoryContentSnapshot = WebOpenedStoryContentSnapshot(
    storyId = externalStoryId,
    title = title,
    text = storyText,
    question = challenge,
    choices = choices.toList(),
    enabledChoice = enabledChoice.takeIf(String::isNotBlank),
    questionParagraphs = if (isOnboarding) {
        onboardingBatQuestionParagraphs(this)
    } else {
        listOf(storyText, challenge)
    },
    imageRef = registry.register(
        imageUrl,
        WebMediaKind.Image,
        slot = "opened:$externalStoryId:question-image",
        priority = WebMediaProjectionPriority.Current,
    )
        ?: bundledOnboardingRefOrNull(imageUrl, isOnboarding, OnboardingQuestionImageRef),
    videoRef = registry.register(
        videoUrl,
        WebMediaKind.Video,
        slot = "opened:$externalStoryId:question-video",
        priority = WebMediaProjectionPriority.Current,
    )
        ?: bundledOnboardingRefOrNull(videoUrl, isOnboarding, OnboardingQuestionVideoRef),
)

internal fun DurableStorySnapshot.externalStoryId(): String = when (kind) {
    DurableStoryKind.Scheduled -> story.travelId
    DurableStoryKind.OnboardingBat -> WebOnboardingBatStoryId
}

private fun InteractiveTravelStoryResult.toWebSnapshot(
    registry: WebMediaReferenceRegistry,
    isOnboarding: Boolean,
    externalStoryId: String,
): WebOpenedStoryResultSnapshot = WebOpenedStoryResultSnapshot(
    requestKey = requestKey,
    answer = answer,
    text = text,
    reaction = reaction,
    consequence = consequence,
    experienceGained = experienceGained,
    paragraphs = if (isOnboarding) {
        onboardingBatResultParagraphs(this)
    } else {
        listOf(text, consequence)
    },
    imageRef = registry.register(
        imageUrl,
        WebMediaKind.Image,
        slot = "opened:$externalStoryId:result-image",
        priority = WebMediaProjectionPriority.Current,
    )
        ?: bundledOnboardingRefOrNull(imageUrl, isOnboarding, OnboardingResultImageRef),
    videoRef = registry.register(
        videoUrl,
        WebMediaKind.Video,
        slot = "opened:$externalStoryId:result-video",
        priority = WebMediaProjectionPriority.Current,
    )
        ?: bundledOnboardingRefOrNull(videoUrl, isOnboarding, OnboardingResultVideoRef),
)

private fun bundledOnboardingRefOrNull(
    sourceValue: String?,
    isOnboarding: Boolean,
    bundledRef: String,
): String? = bundledRef.takeIf { isOnboarding && sourceValue == null }

private fun DurableStoryPhase.toWebPhase(): WebDurableStoryPhase = when (this) {
    DurableStoryPhase.Question -> WebDurableStoryPhase.Question
    DurableStoryPhase.ChoicePending -> WebDurableStoryPhase.ChoicePending
    DurableStoryPhase.Retryable -> WebDurableStoryPhase.Retryable
    DurableStoryPhase.Result -> WebDurableStoryPhase.Result
}

private fun DurableStoryKind.toWebKind(): WebDurableStoryKind = when (this) {
    DurableStoryKind.Scheduled -> WebDurableStoryKind.Scheduled
    DurableStoryKind.OnboardingBat -> WebDurableStoryKind.OnboardingBat
}

private const val OnboardingQuestionImageRef = "/res/onboarding_bat_situation.png"
private const val OnboardingQuestionVideoRef = "/assets/media/onboarding-bat-situation.mp4"
private const val OnboardingResultImageRef = "/res/onboarding_bat_success.png"
private const val OnboardingResultVideoRef = "/assets/media/onboarding-bat-success.mp4"
