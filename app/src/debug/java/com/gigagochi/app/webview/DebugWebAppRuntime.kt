package com.gigagochi.app.webview

import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.webview.BridgeProductCommand
import com.gigagochi.app.core.webview.WebAppRuntime
import com.gigagochi.app.core.webview.WebAppRuntimeException
import com.gigagochi.app.core.webview.WebAppSnapshot
import com.gigagochi.app.core.webview.WebAppGenerationPolicy
import com.gigagochi.app.core.webview.WebCreateSnapshot
import com.gigagochi.app.core.webview.WebCreateQuestionSnapshot
import com.gigagochi.app.core.webview.WebDashboardChatSnapshot
import com.gigagochi.app.core.webview.WebDashboardFeedSnapshot
import com.gigagochi.app.core.webview.WebDashboardOutfitPendingSnapshot
import com.gigagochi.app.core.webview.WebDashboardOutfitSnapshot
import com.gigagochi.app.core.webview.WebDashboardReplySnapshot
import com.gigagochi.app.core.webview.WebDashboardSnapshot
import com.gigagochi.app.core.webview.WebDashboardTravelPendingSnapshot
import com.gigagochi.app.core.webview.WebDashboardTravelSnapshot
import com.gigagochi.app.core.webview.WebDurableStoryKind
import com.gigagochi.app.core.webview.WebDurableStoryOrigin
import com.gigagochi.app.core.webview.WebDurableStoryPhase
import com.gigagochi.app.core.webview.WebEventsSnapshot
import com.gigagochi.app.core.webview.WebOpenedStoryContentSnapshot
import com.gigagochi.app.core.webview.WebOpenedStoryResultSnapshot
import com.gigagochi.app.core.webview.WebOpenedStorySnapshot
import com.gigagochi.app.core.webview.WebPendingOperationSnapshot
import com.gigagochi.app.core.webview.WebPendingOperationsSnapshot
import com.gigagochi.app.core.webview.WebPetMediaSnapshot
import com.gigagochi.app.core.webview.WebPetSnapshot
import com.gigagochi.app.core.webview.WebPetTapFeedbackSnapshot
import com.gigagochi.app.core.webview.WebSafeAreaSnapshot
import com.gigagochi.app.core.webview.WebScheduledStoryEventSnapshot
import com.gigagochi.app.core.webview.WebScheduledStoryResultSnapshot
import com.gigagochi.app.core.webview.WebScheduledStorySnapshot
import com.gigagochi.app.core.webview.WebTravelVideoEventSnapshot
import com.gigagochi.app.feature.create.CreateGenerationFailureMessage
import com.gigagochi.app.feature.dashboard.ChatFailureMessage
import com.gigagochi.app.feature.dashboard.FeedFailureMessage
import com.gigagochi.app.feature.dashboard.canonicalOutfitDisplayItem
import com.gigagochi.app.feature.dashboard.outfitQueuedReply
import com.gigagochi.app.feature.dashboard.travelQueuedReply
import com.gigagochi.app.feature.travel.TravelStoryChoiceFailureMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal object DebugWebAppFixtureRouting {
    const val IntentExtra = "gigagochi.web.fixture"

    val supportedFixtures = setOf(
        "dashboard",
        "dashboard-reduced-motion",
        "dashboard-chat",
        "dashboard-chat-thinking",
        "dashboard-chat-queued",
        "dashboard-chat-error",
        "dashboard-feed",
        "dashboard-feed-thinking",
        "dashboard-feed-error",
        "dashboard-outfit",
        "outfit-thinking",
        "dashboard-travel",
        "travel-thinking",
        "outfit-pending",
        "outfit-ready",
        "outfit-outcome-unknown",
        "outfit-apply-conflict",
        "outfit-error",
        "travel-pending",
        "travel-ready",
        "travel-outcome-unknown",
        "travel-apply-conflict",
        "travel-error",
        "create",
        "create-initial",
        "create-name",
        "create-custom",
        "create-custom-ime",
        "create-loader",
        "create-final",
        "create-error",
        "create-recovery",
        "create-retry",
        "events",
        "events-notification-focus",
        "notification-permission-granted",
        "notification-permission-denied",
        "story",
        "story-question",
        "story-retryable",
        "story-result",
        "onboarding-story",
    )

    fun resolve(rawFixture: String?): String {
        val fixture = rawFixture?.trim().orEmpty()
        return fixture.takeIf { it in supportedFixtures } ?: "dashboard"
    }
}

internal object DebugProductionRuntimeRouting {
    const val ProductionRuntimeIntentExtra = "gigagochi.web.production"
    const val UserInvocationsOnlyIntentExtra = "gigagochi.web.user_initiated_only"

    fun generationPolicy(
        useProductionRuntime: Boolean,
        userInvocationsOnlyRequested: Boolean,
    ): WebAppGenerationPolicy =
        if (useProductionRuntime && userInvocationsOnlyRequested) {
            WebAppGenerationPolicy.UserInvocationsOnly
        } else {
            WebAppGenerationPolicy.Production
        }
}

/**
 * Deterministic bridge/UI projection used by screenshot automation.
 *
 * This runtime is intentionally not evidence for Room durability, WorkManager delivery, process
 * recovery, or notification/renderer lifecycle. Those gates must run through
 * [ProductionWebAppRuntime] and the Android persistence/host layers.
 */
internal class DebugWebAppRuntime(
    initialRoute: String,
) : WebAppRuntime {
    private var route = "dashboard"
    private var revisionNumber = 0
    private var safeArea = WebSafeAreaSnapshot()
    private var createStep = 0
    private var createTransitionComplete = false
    private var createGenerationOverride: String? = null
    private var createError: String? = null
    private var createRetryTarget: String? = null
    private var dashboardMode = "idle"
    private var dashboardReply: WebDashboardReplySnapshot? = null
    private var chatDraft = ""
    private var chatError: String? = null
    private var chatActiveRequestKey: String? = null
    private var chatQueuedRequestKey: String? = null
    private var chatThinking = false
    private var feedPulseId = 0
    private var feedAudioIndex = 0
    private var activeFeedFood: String? = null
    private var feedError: String? = null
    private var feedActiveRequestKey: String? = null
    private var feedThinking = false
    private var outfitDraft = ""
    private var outfitError: String? = null
    private var outfitPending: WebDashboardOutfitPendingSnapshot? = null
    private var outfitActiveRequestKey: String? = null
    private var outfitThinking = false
    private var travelDraft = ""
    private var travelError: String? = null
    private var travelPending: WebDashboardTravelPendingSnapshot? = null
    private var travelActiveRequestKey: String? = null
    private var travelThinking = false
    private var reducedMotion = false
    private var notificationPermission = "unknown"
    private var eventsSnapshot = fixtureEventsSnapshot()
    private var openedStory: WebOpenedStorySnapshot? = null
    private var pet = WebPetSnapshot(
        name = "Тото",
        stageLabel = "Малыш",
        experience = 240,
        hunger = 74,
        happiness = 82,
        energy = 68,
        message = "Рад тебя видеть! Чем займёмся сегодня?",
        petTapProgress = 0,
        media = WebPetMediaSnapshot(videoRef = "/assets/media/openai-normal.mp4"),
    )

    init {
        when (DebugWebAppFixtureRouting.resolve(initialRoute)) {
            "dashboard-reduced-motion" -> reducedMotion = true
            "dashboard-chat" -> configureDashboardModeFixture("chat")
            "dashboard-chat-thinking" -> configureChatStateFixture(queued = false, error = false)
            "dashboard-chat-queued" -> configureChatStateFixture(queued = true, error = false)
            "dashboard-chat-error" -> configureChatStateFixture(queued = false, error = true)
            "dashboard-feed" -> configureDashboardModeFixture("feed")
            "dashboard-feed-thinking" -> configureFeedStateFixture(error = false)
            "dashboard-feed-error" -> configureFeedStateFixture(error = true)
            "dashboard-outfit" -> configureDashboardModeFixture("outfit")
            "outfit-thinking" -> configurePromptThinkingFixture(mode = "outfit")
            "dashboard-travel" -> configureDashboardModeFixture("travel")
            "travel-thinking" -> configurePromptThinkingFixture(mode = "travel")
            "outfit-pending" -> configureOutfitFixture(error = false)
            "outfit-ready" -> configureOutfitStatusFixture(status = "ready")
            "outfit-outcome-unknown" -> configureOutfitStatusFixture(status = "outcomeUnknown")
            "outfit-apply-conflict" -> configureOutfitStatusFixture(
                status = "applyConflict",
                error = "Наряд готов, но не удалось применить его.",
            )
            "outfit-error" -> configureOutfitFixture(error = true)
            "travel-pending" -> configureTravelFixture(error = false)
            "travel-ready" -> configureTravelStatusFixture(status = "ready")
            "travel-outcome-unknown" -> configureTravelStatusFixture(status = "outcomeUnknown")
            "travel-apply-conflict" -> configureTravelStatusFixture(
                status = "applyConflict",
                error = "Путешествие готово, но не удалось сохранить результат.",
            )
            "travel-error" -> configureTravelFixture(error = true)
            "create",
            "create-initial",
            -> route = "create"
            "create-name" -> configureCreateFixture(step = 1, generation = "running")
            // Custom-input visibility/IME are Web-local. These aliases provide the canonical
            // source question; UI automation must open "Свой вариант" before capture.
            "create-custom",
            "create-custom-ime",
            -> configureCreateFixture(step = 0, generation = "idle")
            "create-loader",
            "create-recovery",
            -> configureCreateFixture(step = CreationQuestions.size, generation = "running")
            "create-final" -> configureCreateFixture(
                step = CreationQuestions.size,
                generation = "ready",
            )
            "create-error",
            "create-retry",
            -> configureCreateFixture(
                step = CreationQuestions.size,
                generation = "retryable",
                error = CreateGenerationFailureMessage,
                retryTarget = "generation",
            )
            "events" -> route = "events"
            "events-notification-focus" -> {
                route = "events"
                eventsSnapshot = eventsSnapshot.copy(
                    initialFocusTravelRequestKey = FixtureTravelRequestKey,
                )
            }
            "notification-permission-granted" -> notificationPermission = "granted"
            "notification-permission-denied" -> notificationPermission = "denied"
            "story",
            "story-question",
            -> {
                route = "story"
                openedStory = scheduledQuestionStory()
            }
            "story-retryable" -> {
                route = "story"
                openedStory = scheduledQuestionStory().copy(
                    phase = WebDurableStoryPhase.Retryable,
                    durableRequestKey = FixtureStoryResultRequestKey,
                    pendingChoice = FixtureRetryChoice,
                    error = TravelStoryChoiceFailureMessage,
                )
            }
            "story-result" -> {
                route = "story"
                openedStory = scheduledQuestionStory().asResult(
                    requestKey = FixtureStoryResultRequestKey,
                    answer = FixtureSuccessfulChoice,
                ).also(::markScheduledStoryAnswered)
            }
            "onboarding-story" -> {
                route = "story"
                openedStory = onboardingQuestionStory()
            }
        }
    }

    private fun configureDashboardModeFixture(mode: String) {
        route = "dashboard"
        dashboardMode = mode
        when (mode) {
            "chat" -> {
                chatDraft = "Расскажи, что интересного ты сегодня видел"
                dashboardReply = fixtureReply(
                    source = "chat",
                    requestKey = FixtureChatRequestKey,
                    text = "Сегодня я нашёл камешек, похожий на маленькую звезду!",
                )
            }
            "outfit" -> outfitDraft = FixtureOutfitPrompt
            "travel" -> travelDraft = FixtureTravelPrompt
        }
    }

    private fun configureChatStateFixture(
        queued: Boolean,
        error: Boolean,
    ) {
        route = "dashboard"
        dashboardMode = "chat"
        chatActiveRequestKey = FixtureChatRequestKey
        chatQueuedRequestKey = FixtureQueuedChatRequestKey.takeIf { queued }
        chatThinking = !error
        chatError = ChatFailureMessage.takeIf { error }
    }

    private fun configureFeedStateFixture(error: Boolean) {
        route = "dashboard"
        dashboardMode = "feed"
        feedActiveRequestKey = FixtureFeedRequestKey
        activeFeedFood = "berry-bowl"
        feedPulseId = 1
        feedAudioIndex = 1
        feedThinking = !error
        feedError = FeedFailureMessage.takeIf { error }
    }

    private fun configurePromptThinkingFixture(mode: String) {
        route = "dashboard"
        dashboardMode = mode
        when (mode) {
            "outfit" -> {
                outfitActiveRequestKey = FixtureOutfitRequestKey
                outfitThinking = true
            }
            "travel" -> {
                travelActiveRequestKey = FixtureTravelPendingRequestKey
                travelThinking = true
            }
            else -> error("Unsupported prompt fixture mode: $mode")
        }
    }

    private fun configureOutfitFixture(error: Boolean) {
        route = "dashboard"
        dashboardMode = "outfit"
        outfitPending = WebDashboardOutfitPendingSnapshot(
            requestKey = FixtureOutfitRequestKey,
            status = if (error) "retryable" else "pending",
            prompt = FixtureOutfitPrompt,
            displayItem = canonicalOutfitDisplayItem(FixtureOutfitPrompt),
            experienceCost = OutfitExperienceCost,
        )
        outfitError = if (error) "Не удалось создать наряд." else null
        pet = pet.copy(experience = (pet.experience - OutfitExperienceCost).coerceAtLeast(0))
    }

    private fun configureOutfitStatusFixture(
        status: String,
        error: String? = null,
    ) {
        configureOutfitFixture(error = false)
        outfitPending = outfitPending?.copy(status = status)
        outfitError = error
    }

    private fun configureTravelFixture(error: Boolean) {
        route = "dashboard"
        dashboardMode = "travel"
        travelPending = WebDashboardTravelPendingSnapshot(
            requestKey = FixtureTravelPendingRequestKey,
            status = if (error) "retryable" else "pending",
            prompt = FixtureTravelPrompt,
        )
        travelError = if (error) "Не удалось подготовить путешествие." else null
    }

    private fun configureTravelStatusFixture(
        status: String,
        error: String? = null,
    ) {
        configureTravelFixture(error = false)
        travelPending = travelPending?.copy(status = status)
        travelError = error
    }

    private fun configureCreateFixture(
        step: Int,
        generation: String,
        error: String? = null,
        retryTarget: String? = null,
    ) {
        route = "create"
        createStep = step
        createTransitionComplete = step != 0
        createGenerationOverride = generation
        createError = error
        createRetryTarget = retryTarget
    }

    override suspend fun snapshot(): WebAppSnapshot = currentSnapshot()

    override suspend fun dispatch(command: BridgeProductCommand): WebAppSnapshot {
        if (command.expectedSnapshotRevision != revision()) {
            throw WebAppRuntimeException(
                bridgeCode = "STATE_CONFLICT",
                retryable = true,
                snapshot = currentSnapshot(),
            )
        }
        val feedback = when (command.type) {
            "PET_TAP" -> applyPetTap(command.requestKey)
            "DASHBOARD_OPEN_MODE" -> openDashboardMode(
                command.payload["mode"]?.jsonPrimitive?.content,
            )
            "DASHBOARD_CLOSE_MODE" -> closeDashboardMode()
            "DASHBOARD_UPDATE_DRAFT" -> updateDashboardDraft(command)
            "CHAT_SEND" -> applyChat(
                command.requestKey,
                command.payload["message"]?.jsonPrimitive?.content,
            )
            "CHAT_RETRY" -> {
                if (command.payload.isNotEmpty()) {
                    throw WebAppRuntimeException("INVALID_PAYLOAD")
                }
                throw WebAppRuntimeException("WRONG_STAGE")
            }
            "FEED_CONSUME" -> applyFeed(
                command.requestKey,
                command.payload["food"]?.jsonPrimitive?.content,
            )
            "OUTFIT_SUBMIT" -> applyOutfit(
                command.requestKey,
                command.payload["prompt"]?.jsonPrimitive?.content,
            )
            "OUTFIT_RETRY" -> retryOutfit()
            "TRAVEL_SUBMIT" -> applyTravel(
                command.requestKey,
                command.payload["prompt"]?.jsonPrimitive?.content,
            )
            "TRAVEL_RETRY" -> retryTravel()
            "REPLY_ADVANCE" -> advanceReply(
                command.payload["requestKey"]?.jsonPrimitive?.content,
            )
            "REPLY_COMPLETE",
            "CHAT_REPLY_PRESENTED",
            -> Unit
            "CREATE_ANSWER" -> applyCreateAnswer(command)
            "CREATE_RETRY" -> applyCreateRetry(command)
            "CREATE_BACKGROUND_COMPLETE" -> completeCreateBackground()
            "CREATE_FINISH" -> route = "dashboard"
            "NAVIGATE" -> applyNavigate(command)
            "STORY_OPEN" -> applyStoryOpen(command)
            "STORY_CHOOSE" -> applyStoryChoose(command)
            "STORY_RETRY" -> applyStoryRetry(command)
            "STORY_FINISH" -> applyStoryFinish(command)
            "EVENTS_MARK_VIEWED" -> applyEventsMarkViewed(command)
            "BACK" -> applyBack(command)
            else -> throw WebAppRuntimeException("UNSUPPORTED_METHOD")
        }
        revisionNumber += 1
        return currentSnapshot(feedback as? WebPetTapFeedbackSnapshot)
    }

    override fun updateSafeArea(safeArea: WebSafeAreaSnapshot) {
        this.safeArea = safeArea
    }

    private fun applyPetTap(requestKey: String): WebPetTapFeedbackSnapshot {
        val rewarded = pet.petTapProgress == 4
        pet = pet.copy(
            petTapProgress = if (rewarded) 0 else pet.petTapProgress + 1,
            happiness = if (rewarded) (pet.happiness + 15).coerceAtMost(100) else pet.happiness,
        )
        return WebPetTapFeedbackSnapshot(
            eventId = requestKey,
            rewarded = rewarded,
            thanks = if (rewarded) "Приятно!" else null,
            visibleMillis = 5_000L,
        )
    }

    private fun openDashboardMode(mode: String?) {
        if (route != "dashboard" || dashboardMode != "idle") {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        dashboardMode = mode?.takeIf { it in DashboardModes }
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        dashboardReply = null
    }

    private fun closeDashboardMode() {
        if (route != "dashboard") throw WebAppRuntimeException("WRONG_STAGE")
        dashboardMode = "idle"
        dashboardReply = null
        activeFeedFood = null
    }

    private fun updateDashboardDraft(command: BridgeProductCommand) {
        if (command.payload.keys != setOf("mode", "value")) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val mode = (command.payload["mode"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        val value = (command.payload["value"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (mode != dashboardMode || mode !in setOf("chat", "outfit", "travel")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (value.length > 1_000) throw WebAppRuntimeException("INVALID_PAYLOAD")
        when (mode) {
            "chat" -> chatDraft = value
            "outfit" -> outfitDraft = value
            "travel" -> travelDraft = value
        }
    }

    private fun applyChat(requestKey: String, message: String?) {
        if (route != "dashboard" || dashboardMode !in setOf("idle", "chat")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (message.isNullOrBlank()) throw WebAppRuntimeException("INVALID_PAYLOAD")
        dashboardMode = "chat"
        chatDraft = ""
        chatError = null
        chatActiveRequestKey = requestKey
        chatQueuedRequestKey = null
        chatThinking = false
        dashboardReply = fixtureReply("chat", requestKey, "Слышал, почему лёд плавает?")
    }

    private fun applyFeed(requestKey: String, food: String?) {
        if (route != "dashboard" || dashboardMode !in setOf("idle", "feed")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        dashboardMode = "feed"
        pet = when (food) {
            "berry-bowl" -> pet.copy(
                hunger = (pet.hunger + 25).coerceAtMost(100),
                message = "Ням-ням!",
            )
            "leaf-crunch" -> pet.copy(
                energy = (pet.energy + 25).coerceAtMost(100),
                message = "Мне легче, но это ужасная гадость!!",
            )
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        feedPulseId += 1
        activeFeedFood = food
        feedError = null
        feedActiveRequestKey = requestKey
        feedThinking = false
        dashboardReply = fixtureReply("feed", requestKey, pet.message)
        feedAudioIndex = (feedAudioIndex + 1) % 3
    }

    private fun applyOutfit(requestKey: String, rawPrompt: String?) {
        if (route != "dashboard" || dashboardMode !in setOf("idle", "outfit")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (outfitPending?.status !in setOf(null, "failed", "applyConflict")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val prompt = requiredPrompt(rawPrompt)
        if (pet.experience < OutfitExperienceCost) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val displayItem = canonicalOutfitDisplayItem(prompt)
        pet = pet.copy(experience = pet.experience - OutfitExperienceCost)
        outfitPending = WebDashboardOutfitPendingSnapshot(
            requestKey = requestKey,
            status = "attached",
            prompt = prompt,
            displayItem = displayItem,
            experienceCost = OutfitExperienceCost,
        )
        outfitDraft = ""
        outfitError = null
        outfitActiveRequestKey = requestKey
        outfitThinking = false
        dashboardMode = "idle"
        dashboardReply = fixtureReply(
            "transient",
            requestKey,
            outfitQueuedReply(displayItem),
        )
    }

    private fun retryOutfit() {
        val pending = outfitPending?.takeIf { it.status in setOf("pending", "retryable") }
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        outfitPending = pending.copy(status = "attached")
        outfitError = null
        dashboardMode = "idle"
        dashboardReply = fixtureReply(
            "transient",
            pending.requestKey,
            outfitQueuedReply(pending.displayItem),
        )
    }

    private fun applyTravel(requestKey: String, rawPrompt: String?) {
        if (route != "dashboard" || dashboardMode !in setOf("idle", "travel")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (travelPending?.status !in setOf(null, "failed", "applyConflict")) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        val prompt = requiredPrompt(rawPrompt)
        travelPending = WebDashboardTravelPendingSnapshot(
            requestKey = requestKey,
            status = "attached",
            prompt = prompt,
        )
        travelDraft = ""
        travelError = null
        travelActiveRequestKey = requestKey
        travelThinking = false
        dashboardMode = "idle"
        dashboardReply = fixtureReply(
            "transient",
            requestKey,
            travelQueuedReply(prompt),
        )
    }

    private fun retryTravel() {
        val pending = travelPending?.takeIf { it.status in setOf("pending", "retryable") }
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        travelPending = pending.copy(status = "attached")
        travelError = null
        dashboardMode = "idle"
        dashboardReply = fixtureReply(
            "transient",
            pending.requestKey,
            travelQueuedReply(pending.prompt),
        )
    }

    private fun requiredPrompt(rawPrompt: String?): String {
        val prompt = rawPrompt?.trim().orEmpty()
        if (prompt.isEmpty() || prompt.length > 1_000) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        return prompt
    }

    private fun advanceReply(requestKey: String?) {
        val reply = dashboardReply
            ?.takeIf { it.requestKey == requestKey && it.hasNextPortion }
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        dashboardReply = reply.copy(
            portionIndex = reply.portionIndex + 1,
            hasNextPortion = reply.portionIndex + 1 < reply.portions.lastIndex,
        )
    }

    private fun applyCreateAnswer(command: BridgeProductCommand) {
        if (route != "create" || createStep >= CreationQuestions.lastIndex + 1) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        if (command.payload.keys != setOf("answer", "step")) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        val answer = command.payload["answer"]?.jsonPrimitive
        val step = command.payload["step"]?.jsonPrimitive
        if (answer == null || !answer.isString || answer.content.trim().isEmpty()) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        if (step == null || step.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
        val sourceStep = step.content.toIntOrNull()
            ?.takeIf { it in CreationQuestions.indices }
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (sourceStep != createStep) throw WebAppRuntimeException("WRONG_STAGE")
        createStep += 1
        createGenerationOverride = null
        createError = null
        createRetryTarget = null
        if (createStep != 1) createTransitionComplete = true
    }

    private fun applyCreateRetry(command: BridgeProductCommand) {
        command.requirePayloadKeys()
        if (
            route != "create" ||
            createGenerationOverride !in setOf("retryable", "failed")
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        createGenerationOverride = "running"
        createError = null
        createRetryTarget = null
    }

    private fun completeCreateBackground() {
        if (route != "create" || createStep != 1 || createTransitionComplete) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        createTransitionComplete = true
    }

    private fun applyNavigate(command: BridgeProductCommand) {
        command.requirePayloadKeys("route")
        val requestedRoute = command.requiredString("route", maxLength = 16)
        if (route != "dashboard" || dashboardMode != "idle") {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        dashboardReply = null
        openedStory = null
        when (requestedRoute) {
            "events" -> route = "events"
            "travel" -> {
                route = "story"
                openedStory = onboardingQuestionStory()
            }
            else -> throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private fun applyStoryOpen(command: BridgeProductCommand) {
        command.requirePayloadKeys("storyId")
        val storyId = command.requiredString("storyId", maxLength = 200)
        if (
            route != "events" ||
            openedStory != null ||
            eventsSnapshot.stories.none { event -> event.story.storyId == storyId }
        ) {
            throw WebAppRuntimeException("WRONG_STAGE")
        }
        route = "story"
        openedStory = scheduledQuestionStory(storyId)
    }

    private fun applyStoryChoose(command: BridgeProductCommand) {
        command.requirePayloadKeys("storyId", "choice")
        val storyId = command.requiredString("storyId", maxLength = 200)
        val choice = command.requiredString("choice", maxLength = 200)
        val current = openedStory?.takeIf {
            route == "story" &&
                it.story.storyId == storyId &&
                it.phase == WebDurableStoryPhase.Question
        } ?: throw WebAppRuntimeException("WRONG_STAGE")
        if (
            choice !in current.story.choices ||
            (current.story.enabledChoice != null && choice != current.story.enabledChoice)
        ) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
        openedStory = if (
            current.kind == WebDurableStoryKind.Scheduled && choice == FixtureRetryChoice
        ) {
            current.copy(
                phase = WebDurableStoryPhase.Retryable,
                durableRequestKey = command.requestKey,
                pendingChoice = choice,
                result = null,
                error = TravelStoryChoiceFailureMessage,
            )
        } else {
            current.asResult(command.requestKey, choice).also(::markScheduledStoryAnswered)
        }
    }

    private fun applyStoryRetry(command: BridgeProductCommand) {
        command.requirePayloadKeys("storyId")
        val storyId = command.requiredString("storyId", maxLength = 200)
        val current = openedStory?.takeIf {
            route == "story" &&
                it.story.storyId == storyId &&
                it.phase == WebDurableStoryPhase.Retryable
        } ?: throw WebAppRuntimeException("WRONG_STAGE")
        val requestKey = current.durableRequestKey
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        val choice = current.pendingChoice
            ?: throw WebAppRuntimeException("WRONG_STAGE")
        openedStory = current.asResult(requestKey, choice).also(::markScheduledStoryAnswered)
    }

    private fun applyStoryFinish(command: BridgeProductCommand) {
        command.requirePayloadKeys("storyId")
        val storyId = command.requiredString("storyId", maxLength = 200)
        val current = openedStory?.takeIf {
            route == "story" &&
                it.story.storyId == storyId &&
                it.phase == WebDurableStoryPhase.Result
        } ?: throw WebAppRuntimeException("WRONG_STAGE")
        route = when (current.origin) {
            WebDurableStoryOrigin.Events -> "events"
            WebDurableStoryOrigin.Dashboard -> "dashboard"
        }
        openedStory = null
    }

    private fun applyEventsMarkViewed(command: BridgeProductCommand) {
        command.requirePayloadKeys("viewedAt")
        val viewedAt = command.requiredLong("viewedAt")
        if (route != "events") throw WebAppRuntimeException("WRONG_STAGE")
        if (eventsSnapshot.latestEventAtEpochMillis != viewedAt) {
            throw WebAppRuntimeException("STATE_CONFLICT", retryable = true)
        }
        eventsSnapshot = eventsSnapshot.copy(
            badgeCount = 0,
            lastViewedAtEpochMillis = viewedAt,
        )
    }

    private fun applyBack(command: BridgeProductCommand) {
        command.requirePayloadKeys()
        route = when (route) {
            "events" -> "dashboard"
            "story" -> when (openedStory?.origin) {
                WebDurableStoryOrigin.Events -> "events"
                WebDurableStoryOrigin.Dashboard -> "dashboard"
                null -> throw WebAppRuntimeException("WRONG_STAGE")
            }
            else -> throw WebAppRuntimeException("WRONG_STAGE")
        }
        openedStory = null
    }

    private fun BridgeProductCommand.requirePayloadKeys(vararg expected: String) {
        if (payload.keys != expected.toSet()) {
            throw WebAppRuntimeException("INVALID_PAYLOAD")
        }
    }

    private fun BridgeProductCommand.requiredString(
        key: String,
        maxLength: Int,
    ): String {
        val primitive = payload[key] as? JsonPrimitive
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (!primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
        return primitive.content.takeIf { value ->
            value.isNotBlank() &&
                value == value.trim() &&
                value.length <= maxLength &&
                value.none(Char::isISOControl)
        } ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    }

    private fun BridgeProductCommand.requiredLong(key: String): Long {
        val primitive = payload[key] as? JsonPrimitive
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
        if (primitive.isString) throw WebAppRuntimeException("INVALID_PAYLOAD")
        return primitive.content.toLongOrNull()
            ?: throw WebAppRuntimeException("INVALID_PAYLOAD")
    }

    private fun WebOpenedStorySnapshot.asResult(
        requestKey: String,
        answer: String,
    ): WebOpenedStorySnapshot {
        val onboarding = kind == WebDurableStoryKind.OnboardingBat
        return copy(
            phase = WebDurableStoryPhase.Result,
            durableRequestKey = requestKey,
            pendingChoice = null,
            result = WebOpenedStoryResultSnapshot(
                requestKey = requestKey,
                answer = answer,
                text = if (onboarding) {
                    "Летучая мышь относится к млекопитающим."
                } else {
                    "Тото помог малышу выбраться из зарослей."
                },
                reaction = if (onboarding) {
                    "Мама-летучая мышь согрела малыша и накормила его молоком."
                } else {
                    "Спасибо, теперь всё хорошо."
                },
                consequence = if (onboarding) {
                    "Малыш снова рядом с семьёй."
                } else {
                    "Тропа у старого дерева снова стала безопасной."
                },
                experienceGained = if (onboarding) 200 else 50,
                paragraphs = if (onboarding) {
                    listOf(
                        "Летучая мышь относится к млекопитающим.",
                        "Мама добралась до малыша, согрела его и накормила молоком.",
                    )
                } else {
                    listOf(
                        "Тото помог малышу выбраться из зарослей.",
                        "Тропа у старого дерева снова стала безопасной.",
                    )
                },
                imageRef = if (onboarding) OnboardingResultImageRef else ScheduledResultImageRef,
                videoRef = if (onboarding) OnboardingResultVideoRef else ScheduledResultVideoRef,
            ),
            error = null,
        )
    }

    private fun markScheduledStoryAnswered(opened: WebOpenedStorySnapshot) {
        if (opened.kind != WebDurableStoryKind.Scheduled) return
        val result = opened.result ?: return
        eventsSnapshot = eventsSnapshot.copy(
            stories = eventsSnapshot.stories.map { event ->
                if (event.story.storyId != opened.story.storyId) return@map event
                event.copy(
                    story = event.story.copy(
                        selectedChoice = result.answer,
                        result = WebScheduledStoryResultSnapshot(
                            text = result.text,
                            reaction = result.reaction,
                            consequence = result.consequence,
                            experienceGained = result.experienceGained,
                        ),
                        resultImageRef = result.imageRef,
                        resultVideoRef = result.videoRef,
                    ),
                )
            },
            badgeCount = (eventsSnapshot.badgeCount - 1).coerceAtLeast(0),
        )
    }

    private fun currentSnapshot(
        petTapFeedback: WebPetTapFeedbackSnapshot? = null,
    ): WebAppSnapshot {
        val hasDashboardState = route != "create"
        return WebAppSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            webBundleVersion = "0.1.0",
            revision = revision(),
            route = route,
            dashboardMode = dashboardMode,
            reducedMotion = reducedMotion,
            safeArea = safeArea,
            notificationPermission = notificationPermission,
            create = if (route == "create") createSnapshot() else null,
            pet = pet.takeIf { hasDashboardState },
            dashboard = if (hasDashboardState) dashboardSnapshot() else null,
            events = eventsSnapshot.takeIf { hasDashboardState },
            story = openedStory.takeIf { route == "story" },
            pending = WebPendingOperationsSnapshot(
                chat = chatActiveRequestKey?.let {
                    WebPendingOperationSnapshot(
                        requestKey = it,
                        status = if (
                            dashboardReply?.source == "chat" &&
                            !chatThinking &&
                            chatError == null
                        ) {
                            "completed"
                        } else {
                            "pending"
                        },
                        prompt = chatDraft.takeIf(String::isNotBlank),
                    )
                },
                outfit = outfitPending?.let {
                    WebPendingOperationSnapshot(it.requestKey, it.status, it.prompt)
                },
                travel = travelPending?.let {
                    WebPendingOperationSnapshot(it.requestKey, it.status, it.prompt)
                },
            ),
            petTapFeedback = petTapFeedback,
        )
    }

    private fun dashboardSnapshot() = WebDashboardSnapshot(
        reply = dashboardReply,
        chat = WebDashboardChatSnapshot(
            draft = chatDraft,
            error = chatError,
            activeRequestKey = chatActiveRequestKey,
            queuedRequestKey = chatQueuedRequestKey,
            thinking = chatThinking,
        ),
        feed = WebDashboardFeedSnapshot(
            error = feedError,
            activeRequestKey = feedActiveRequestKey,
            activeFood = activeFeedFood,
            audioIndex = if (feedPulseId == 0) null else (feedAudioIndex + 2) % 3,
            pulseId = feedPulseId,
            thinking = feedThinking,
        ),
        outfit = WebDashboardOutfitSnapshot(
            draft = outfitDraft,
            error = outfitError,
            activeRequestKey = outfitActiveRequestKey,
            thinking = outfitThinking,
            experienceCost = OutfitExperienceCost,
            pending = outfitPending,
        ),
        travel = WebDashboardTravelSnapshot(
            draft = travelDraft,
            error = travelError,
            activeRequestKey = travelActiveRequestKey,
            thinking = travelThinking,
            pending = travelPending,
        ),
    )

    private fun createSnapshot(): WebCreateSnapshot {
        val question = CreationQuestions.getOrNull(createStep)
        return WebCreateSnapshot(
            step = createStep,
            title = question?.first ?: "Твой новый друг уже рядом",
            options = question?.second ?: emptyList(),
            nextQuestion = CreationQuestions.getOrNull(createStep + 1)?.let {
                WebCreateQuestionSnapshot(it.first, it.second)
            },
            phase = when (createStep) {
                0 -> "initial"
                1 -> if (createTransitionComplete) "formed" else "transition"
                else -> "formed"
            },
            generation = createGenerationOverride ?: if (createStep >= CreationQuestions.size) {
                "ready"
            } else if (createStep == 0) {
                "idle"
            } else {
                "running"
            },
            error = createError,
            retryTarget = createRetryTarget,
        )
    }

    private fun revision(): String = "debug-$revisionNumber"

    private fun fixtureReply(
        source: String,
        requestKey: String,
        text: String,
    ) = WebDashboardReplySnapshot(
        source = source,
        requestKey = requestKey,
        portions = listOf(text),
        portionIndex = 0,
        hasNextPortion = false,
        autoAdvanceDelayMillis = 6_000,
    )

    private companion object {
        const val OutfitExperienceCost = 200
        const val FixtureChatRequestKey = "323e4567-e89b-42d3-a456-426614174002"
        const val FixtureQueuedChatRequestKey = "323e4567-e89b-42d3-a456-426614174012"
        const val FixtureFeedRequestKey = "323e4567-e89b-42d3-a456-426614174022"
        const val FixtureOutfitRequestKey = "423e4567-e89b-42d3-a456-426614174003"
        const val FixtureTravelPendingRequestKey = "523e4567-e89b-42d3-a456-426614174004"
        const val FixtureOutfitPrompt = "красный шарф"
        const val FixtureTravelPrompt = "ночной рынок духов"
        const val FixtureScheduledStoryId = "android-story-fixture-00000000000000000000"
        const val FixtureOnboardingStoryId = "onboarding-bat-help-v1-fixture-pet"
        const val FixtureTravelRequestKey = "123e4567-e89b-42d3-a456-426614174000"
        const val FixtureStoryResultRequestKey = "223e4567-e89b-42d3-a456-426614174001"
        const val FixtureRetryChoice = "Спрятаться"
        const val FixtureSuccessfulChoice = "Подойти"
        const val FixtureOnboardingChoice = "Млекопитающие"
        const val FixtureStoryCreatedAt = "2026-07-19T10:00:00Z"
        const val FixtureTravelCompletedAt = 1_784_536_200_000L
        const val FixtureLatestEventAt = FixtureTravelCompletedAt
        const val ScheduledQuestionImageRef = "/res/onboarding_bat_situation.png"
        const val ScheduledQuestionVideoRef = "/assets/media/onboarding-bat-situation.mp4"
        const val ScheduledResultImageRef = "/res/onboarding_bat_success.png"
        const val ScheduledResultVideoRef = "/assets/media/onboarding-bat-success.mp4"
        const val OnboardingQuestionImageRef = "/res/onboarding_bat_situation.png"
        const val OnboardingQuestionVideoRef = "/assets/media/onboarding-bat-situation.mp4"
        const val OnboardingResultImageRef = "/res/onboarding_bat_success.png"
        const val OnboardingResultVideoRef = "/assets/media/onboarding-bat-success.mp4"
        const val TravelImageRef = "/res/travel_entry_bg.png"
        const val TravelVideoRef = "/assets/media/travel-entry-bg.mp4"

        fun fixtureEventsSnapshot() = WebEventsSnapshot(
            stories = listOf(
                WebScheduledStoryEventSnapshot(
                    story = WebScheduledStorySnapshot(
                        storyId = FixtureScheduledStoryId,
                        title = "Шорох у старого дерева",
                        text = "Тото услышал шорох и заметил, что кому-то нужна помощь.",
                        question = "Что ему сделать?",
                        choices = listOf(
                            FixtureSuccessfulChoice,
                            "Позвать",
                            FixtureRetryChoice,
                            "Подождать",
                        ),
                        createdAt = FixtureStoryCreatedAt,
                        imageRef = ScheduledQuestionImageRef,
                        videoRef = ScheduledQuestionVideoRef,
                    ),
                ),
            ),
            travelVideos = listOf(
                WebTravelVideoEventSnapshot(
                    requestKey = FixtureTravelRequestKey,
                    prompt = "Хочу увидеть море",
                    title = "Путешествие к маяку",
                    scenario = "Тото увидел маяк у моря и встретил рассвет.",
                    imageRef = TravelImageRef,
                    videoRef = TravelVideoRef,
                    completedAtEpochMillis = FixtureTravelCompletedAt,
                ),
            ),
            badgeCount = 2,
            latestEventAtEpochMillis = FixtureLatestEventAt,
            lastViewedAtEpochMillis = null,
            initialFocusTravelRequestKey = null,
        )

        fun scheduledQuestionStory(
            storyId: String = FixtureScheduledStoryId,
        ) = WebOpenedStorySnapshot(
            phase = WebDurableStoryPhase.Question,
            kind = WebDurableStoryKind.Scheduled,
            origin = WebDurableStoryOrigin.Events,
            story = WebOpenedStoryContentSnapshot(
                storyId = storyId,
                title = "Шорох у старого дерева",
                text = "Тото услышал шорох и заметил, что кому-то нужна помощь.",
                question = "Что ему сделать?",
                choices = listOf(
                    FixtureSuccessfulChoice,
                    "Позвать",
                    FixtureRetryChoice,
                    "Подождать",
                ),
                enabledChoice = null,
                questionParagraphs = listOf(
                    "Тото услышал шорох и заметил, что кому-то нужна помощь.",
                    "Что ему сделать?",
                ),
                imageRef = ScheduledQuestionImageRef,
                videoRef = ScheduledQuestionVideoRef,
            ),
        )

        fun onboardingQuestionStory() = WebOpenedStorySnapshot(
            phase = WebDurableStoryPhase.Question,
            kind = WebDurableStoryKind.OnboardingBat,
            origin = WebDurableStoryOrigin.Dashboard,
            story = WebOpenedStoryContentSnapshot(
                storyId = FixtureOnboardingStoryId,
                title = "Малыш летучей мыши",
                text = "Под крышей пищит детёныш летучей мыши, а его мама ждёт рядом.",
                question = "К какой группе относится летучая мышь?",
                choices = listOf(
                    "Птицы",
                    FixtureOnboardingChoice,
                    "Насекомые",
                    "Пресмыкающиеся",
                ),
                enabledChoice = FixtureOnboardingChoice,
                questionParagraphs = listOf(
                    "Ой, что это?",
                    "Под крышей пищит детёныш летучей мыши.",
                    "Его мама рядом и хочет его накормить.",
                    "Чтобы понять, чем она кормит малыша, нужно узнать, к какой группе относится летучая мышь.",
                    "К какой группе относится летучая мышь?",
                ),
                imageRef = OnboardingQuestionImageRef,
                videoRef = OnboardingQuestionVideoRef,
            ),
        )

        val CreationQuestions = listOf(
            "Кого хочешь создать?" to listOf("Ледяного дракона", "Человек-яблоко", "Водяной дух"),
            "Как его будут звать?" to listOf("Тото", "Бачок", "Денис"),
            "Какой у него характер?" to listOf("Добрый", "Злой", "Ленивый"),
            "Чего он боится?" to listOf("Пауков", "Бизнесменов", "Людоедов"),
            "Какой у него любимый предмет?" to listOf("Вантуз", "Рулон бумаги", "Кока кола"),
        )
        val DashboardModes = setOf("chat", "feed", "outfit", "travel")
    }
}
