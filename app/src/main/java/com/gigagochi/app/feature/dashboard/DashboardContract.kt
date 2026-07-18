package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.model.PetDashboardState
import kotlin.math.max

const val DashboardPromptMaxLength = 1_000
const val DashboardMinimumThinkingMillis = 1_000L
const val DashboardReplyAutoAdvanceMillis = 3_000L
const val PetTapThanksVisibleMillis = 5_000L
const val OutfitExperienceCost = 200
const val ChatFailureMessage = "Не получилось отправить сообщение. Попробуйте ещё раз."
const val FeedFailureMessage = "Питомец поел, но не смог ответить. Попробуйте ещё раз."
const val OutfitInsufficientMessage = "Не хватает опыта для нового наряда."
const val OutfitFailureMessage = "Не получилось нарядить персонажа. Попробуйте ещё раз."
const val TravelFailureMessage = "Не получилось отправиться в путешествие. Попробуйте ещё раз."
const val OutfitPrompt = "Во что мне нарядиться?"
const val TravelPrompt = "Куда мне отправиться?"
const val DeterministicTravelPrompt = "На ночной рынок духов"
const val DeterministicChatReply = "Слышал, почему лёд плавает?"
const val BerryReply = "Ням-ням!"
const val LeafReply = "Мне легче, но это ужасная гадость!!"
const val PetTapsPerHappinessReward = 5
const val PetTapHappinessReward = 15
val PetTapThanksReplies = listOf("Приятно!", "Щекотно!", "Мне нравится!")
const val DeterministicOutfitReply =
    "Футболка Metallica? Интересно. Я получу заказ примерно через 10 минут."
const val DeterministicTravelReply =
    "На ночной рынок духов? Надеюсь, со мной всё будет в порядке. Пришлю видео, когда вернусь."

enum class DashboardMode { Idle, Chat, Feed, Outfit, Travel }

internal fun contextualNavigationForDashboardMode(
    mode: DashboardMode,
): ContextualNavigationAction? = when (mode) {
    DashboardMode.Idle -> null
    DashboardMode.Chat,
    DashboardMode.Feed,
    DashboardMode.Outfit,
    DashboardMode.Travel,
    -> ContextualNavigationAction.Close
}

enum class DashboardFood(val routeValue: String) {
    BerryBowl("berry-bowl"),
    LeafCrunch("leaf-crunch"),
}

enum class FoodTokenPhase { Idle, Dragging, Consuming, Reappearing }

data class FoodTokenMotion(
    val food: DashboardFood? = null,
    val phase: FoodTokenPhase = FoodTokenPhase.Idle,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

data class PendingChatRequest(
    val requestKey: String,
    val message: String,
)

data class PendingFeedRequest(
    val requestKey: String,
    val food: DashboardFood,
    val audioIndex: Int,
)

data class PendingOutfitRequest(
    val requestKey: String,
    val prompt: String,
)

data class PendingOutfitGeneration(
    val version: Int = 1,
    val petId: String,
    val requestKey: String,
    val prompt: String,
    val displayItem: String,
    val experienceCost: Int = OutfitExperienceCost,
    val localJobId: String,
    val backendJobId: String? = null,
    val createdAtEpochMillis: Long,
)

data class PendingTravelRequest(
    val requestKey: String,
    val prompt: String,
)

data class PendingTravelGeneration(
    val version: Int = 1,
    val petId: String,
    val requestKey: String,
    val prompt: String,
    val localJobId: String,
    val backendJobId: String? = null,
    val createdAtEpochMillis: Long,
)

data class DashboardReply(
    val requestKey: String,
    val text: String,
    val portionIndex: Int = 0,
) {
    val portions: List<String>
        get() = splitDashboardReplySentences(text)
    val visibleText: String
        get() = portions.getOrNull(portionIndex) ?: text
    val hasNextPortion: Boolean
        get() = portionIndex < portions.lastIndex
}

data class DashboardUiState(
    val pet: PetDashboardState,
    val mode: DashboardMode = DashboardMode.Idle,
    val chatDraft: String = "",
    val chatError: String? = null,
    val activeChat: PendingChatRequest? = null,
    val chatReply: DashboardReply? = null,
    val feedError: String? = null,
    val activeFeed: PendingFeedRequest? = null,
    val feedReply: DashboardReply? = null,
    val feedToken: FoodTokenMotion = FoodTokenMotion(),
    val feedPulseId: Int = 0,
    val nextFeedAudioIndex: Int = 0,
    val outfitDraft: String = "",
    val outfitError: String? = null,
    val activeOutfit: PendingOutfitRequest? = null,
    val pendingOutfit: PendingOutfitGeneration? = null,
    val chargedOutfitRequestKeys: Set<String> = emptySet(),
    val travelDraft: String = "",
    val travelError: String? = null,
    val activeTravel: PendingTravelRequest? = null,
    val pendingTravel: PendingTravelGeneration? = null,
    val queuedTravelRequestKeys: Set<String> = emptySet(),
    val transientReply: DashboardReply? = null,
)

sealed interface DashboardEvent {
    data object OpenChat : DashboardEvent
    data object OpenFeed : DashboardEvent
    data object OpenOutfit : DashboardEvent
    data object OpenTravel : DashboardEvent
    data object CloseMode : DashboardEvent
    data class UpdateChatDraft(val value: String) : DashboardEvent
    data class SubmitChat(val requestKey: String) : DashboardEvent
    data class ChatSucceeded(val requestKey: String, val reply: String) : DashboardEvent
    data class ChatFailed(val requestKey: String) : DashboardEvent
    data class StartFoodDrag(val food: DashboardFood) : DashboardEvent
    data class MoveFoodDrag(val food: DashboardFood, val offsetX: Float, val offsetY: Float) : DashboardEvent
    data class CancelFoodDrag(val food: DashboardFood) : DashboardEvent
    data class DropFood(
        val food: DashboardFood,
        val requestKey: String,
        val isInsideSceneWithTolerance: Boolean,
    ) : DashboardEvent
    data class TapFood(val food: DashboardFood, val requestKey: String) : DashboardEvent
    data class FoodConsumeFinished(val food: DashboardFood) : DashboardEvent
    data class FoodReappearFinished(val food: DashboardFood) : DashboardEvent
    data class FeedSucceeded(val requestKey: String, val reply: String) : DashboardEvent
    data class FeedFailed(val requestKey: String) : DashboardEvent
    data class UpdateOutfitDraft(val value: String) : DashboardEvent
    data class SubmitOutfit(val requestKey: String) : DashboardEvent
    data class OutfitQueued(
        val requestKey: String,
        val pending: PendingOutfitGeneration,
        val acceptedPet: PetDashboardState,
        val reply: String,
    ) : DashboardEvent
    data class OutfitFailed(val requestKey: String) : DashboardEvent
    data class OutfitPersistedButQueueFailed(
        val requestKey: String,
        val pending: PendingOutfitGeneration,
        val acceptedPet: PetDashboardState,
    ) : DashboardEvent
    data class UpdateTravelDraft(val value: String) : DashboardEvent
    data class SubmitTravel(val requestKey: String) : DashboardEvent
    data class TravelQueued(
        val requestKey: String,
        val pending: PendingTravelGeneration,
        val reply: String,
    ) : DashboardEvent
    data class TravelFailed(val requestKey: String) : DashboardEvent
    data class TravelPersistedButQueueFailed(
        val requestKey: String,
        val pending: PendingTravelGeneration,
    ) : DashboardEvent
    data class AdvanceReply(val requestKey: String) : DashboardEvent
    data class ClearReply(val requestKey: String) : DashboardEvent
    data class PetTapped(
        val thanksMessage: String? = null,
        val replyRequestKey: String? = null,
    ) : DashboardEvent
}

fun reduceDashboard(state: DashboardUiState, event: DashboardEvent): DashboardUiState = when (event) {
    DashboardEvent.OpenChat -> state.copy(
        mode = DashboardMode.Chat,
        chatError = null,
        chatReply = null,
        activeChat = null,
        transientReply = null,
    )

    DashboardEvent.OpenFeed -> state.copy(
        mode = DashboardMode.Feed,
        feedError = null,
        feedReply = null,
        activeFeed = null,
        feedToken = FoodTokenMotion(),
        transientReply = null,
    )

    DashboardEvent.OpenOutfit -> state.copy(
        mode = DashboardMode.Outfit,
        outfitError = null,
        activeOutfit = null,
        transientReply = null,
    )

    DashboardEvent.OpenTravel -> state.copy(
        mode = DashboardMode.Travel,
        travelError = null,
        activeTravel = null,
        transientReply = null,
    )

    DashboardEvent.CloseMode -> state.copy(
        mode = DashboardMode.Idle,
        chatError = null,
        chatReply = null,
        activeChat = null,
        feedError = null,
        feedReply = null,
        activeFeed = null,
        feedToken = FoodTokenMotion(),
        outfitError = null,
        activeOutfit = null,
        travelError = null,
        activeTravel = null,
        transientReply = null,
    )

    is DashboardEvent.UpdateChatDraft -> if (state.mode == DashboardMode.Chat) {
        state.copy(
            chatDraft = event.value.take(DashboardPromptMaxLength),
            chatError = null,
        )
    } else {
        state
    }

    is DashboardEvent.SubmitChat -> {
        val message = state.chatDraft.trim().take(DashboardPromptMaxLength)
        if (state.mode != DashboardMode.Chat || message.isEmpty() || state.activeChat != null) {
            state
        } else {
            state.copy(
                chatDraft = "",
                chatError = null,
                chatReply = null,
                activeChat = PendingChatRequest(event.requestKey, message),
            )
        }
    }

    is DashboardEvent.ChatSucceeded -> if (
        state.mode == DashboardMode.Chat && state.activeChat?.requestKey == event.requestKey
    ) {
        state.copy(
            activeChat = null,
            chatError = null,
            chatReply = DashboardReply(event.requestKey, event.reply),
        )
    } else {
        state
    }

    is DashboardEvent.ChatFailed -> if (
        state.mode == DashboardMode.Chat && state.activeChat?.requestKey == event.requestKey
    ) {
        val failedDraft = state.activeChat.message
        state.copy(
            activeChat = null,
            chatDraft = state.chatDraft.ifBlank { failedDraft },
            chatError = ChatFailureMessage,
        )
    } else {
        state
    }

    is DashboardEvent.StartFoodDrag -> if (
        state.mode == DashboardMode.Feed && state.activeFeed == null
    ) {
        state.copy(feedToken = FoodTokenMotion(event.food, FoodTokenPhase.Dragging))
    } else {
        state
    }

    is DashboardEvent.MoveFoodDrag -> if (
        state.feedToken.food == event.food && state.feedToken.phase == FoodTokenPhase.Dragging
    ) {
        state.copy(feedToken = state.feedToken.copy(offsetX = event.offsetX, offsetY = event.offsetY))
    } else {
        state
    }

    is DashboardEvent.CancelFoodDrag -> if (state.feedToken.food == event.food) {
        state.copy(feedToken = FoodTokenMotion())
    } else {
        state
    }

    is DashboardEvent.DropFood -> if (!event.isInsideSceneWithTolerance) {
        if (state.feedToken.food == event.food) state.copy(feedToken = FoodTokenMotion()) else state
    } else {
        activateFood(state, event.food, event.requestKey)
    }

    is DashboardEvent.TapFood -> activateFood(state, event.food, event.requestKey)

    is DashboardEvent.FoodConsumeFinished -> if (
        state.feedToken.food == event.food && state.feedToken.phase == FoodTokenPhase.Consuming
    ) {
        state.copy(
            feedToken = FoodTokenMotion(event.food, FoodTokenPhase.Reappearing),
        )
    } else {
        state
    }

    is DashboardEvent.FoodReappearFinished -> if (
        state.feedToken.food == event.food && state.feedToken.phase == FoodTokenPhase.Reappearing
    ) {
        state.copy(feedToken = FoodTokenMotion())
    } else {
        state
    }

    is DashboardEvent.FeedSucceeded -> if (
        state.mode == DashboardMode.Feed && state.activeFeed?.requestKey == event.requestKey
    ) {
        state.copy(
            activeFeed = null,
            feedError = null,
            feedReply = DashboardReply(event.requestKey, event.reply),
        )
    } else {
        state
    }

    is DashboardEvent.FeedFailed -> if (
        state.mode == DashboardMode.Feed && state.activeFeed?.requestKey == event.requestKey
    ) {
        state.copy(activeFeed = null, feedError = FeedFailureMessage)
    } else {
        state
    }

    is DashboardEvent.UpdateOutfitDraft -> if (state.mode == DashboardMode.Outfit) {
        state.copy(
            outfitDraft = event.value.take(DashboardPromptMaxLength),
            outfitError = null,
        )
    } else {
        state
    }

    is DashboardEvent.SubmitOutfit -> {
        val prompt = state.outfitDraft.trim().take(DashboardPromptMaxLength)
        when {
            state.mode != DashboardMode.Outfit || prompt.isEmpty() ||
                state.activeOutfit != null || state.pendingOutfit != null -> state
            event.requestKey in state.chargedOutfitRequestKeys -> state
            state.pet.experience < OutfitExperienceCost -> state.copy(outfitError = OutfitInsufficientMessage)
            else -> state.copy(
                outfitError = null,
                activeOutfit = PendingOutfitRequest(event.requestKey, prompt),
            )
        }
    }

    is DashboardEvent.OutfitQueued -> if (
        state.mode == DashboardMode.Outfit &&
        state.activeOutfit?.requestKey == event.requestKey &&
        event.requestKey !in state.chargedOutfitRequestKeys
    ) {
        state.copy(
            pet = event.acceptedPet,
            mode = DashboardMode.Idle,
            outfitDraft = "",
            outfitError = null,
            activeOutfit = null,
            pendingOutfit = event.pending,
            chargedOutfitRequestKeys = state.chargedOutfitRequestKeys + event.requestKey,
            transientReply = DashboardReply(event.requestKey, event.reply),
        )
    } else {
        state
    }

    is DashboardEvent.OutfitFailed -> if (
        state.mode == DashboardMode.Outfit && state.activeOutfit?.requestKey == event.requestKey
    ) {
        state.copy(activeOutfit = null, outfitError = OutfitFailureMessage)
    } else {
        state
    }

    is DashboardEvent.OutfitPersistedButQueueFailed -> if (
        state.activeOutfit?.requestKey == event.requestKey
    ) {
        state.copy(
            pet = event.acceptedPet,
            mode = DashboardMode.Idle,
            activeOutfit = null,
            pendingOutfit = event.pending,
            chargedOutfitRequestKeys = state.chargedOutfitRequestKeys + event.requestKey,
            outfitError = OutfitFailureMessage,
        )
    } else state

    is DashboardEvent.UpdateTravelDraft -> if (state.mode == DashboardMode.Travel) {
        state.copy(
            travelDraft = event.value.take(DashboardPromptMaxLength),
            travelError = null,
        )
    } else {
        state
    }

    is DashboardEvent.SubmitTravel -> {
        val prompt = state.travelDraft.trim().take(DashboardPromptMaxLength)
        if (
            state.mode != DashboardMode.Travel ||
            prompt.isEmpty() ||
            state.activeTravel != null ||
            state.pendingTravel != null ||
            event.requestKey in state.queuedTravelRequestKeys
        ) {
            state
        } else {
            state.copy(
                travelError = null,
                activeTravel = PendingTravelRequest(event.requestKey, prompt),
            )
        }
    }

    is DashboardEvent.TravelQueued -> if (
        state.mode == DashboardMode.Travel &&
        state.activeTravel?.requestKey == event.requestKey &&
        event.pending.requestKey == event.requestKey &&
        event.pending.petId == state.pet.petId &&
        event.requestKey !in state.queuedTravelRequestKeys
    ) {
        state.copy(
            mode = DashboardMode.Idle,
            travelDraft = "",
            travelError = null,
            activeTravel = null,
            pendingTravel = event.pending,
            queuedTravelRequestKeys = state.queuedTravelRequestKeys + event.requestKey,
            transientReply = DashboardReply(event.requestKey, event.reply),
        )
    } else {
        state
    }

    is DashboardEvent.TravelFailed -> if (
        state.mode == DashboardMode.Travel && state.activeTravel?.requestKey == event.requestKey
    ) {
        state.copy(activeTravel = null, travelError = TravelFailureMessage)
    } else {
        state
    }

    is DashboardEvent.TravelPersistedButQueueFailed -> if (
        state.activeTravel?.requestKey == event.requestKey
    ) {
        state.copy(
            mode = DashboardMode.Idle,
            activeTravel = null,
            pendingTravel = event.pending,
            queuedTravelRequestKeys = state.queuedTravelRequestKeys + event.requestKey,
            travelError = TravelFailureMessage,
        )
    } else state

    is DashboardEvent.AdvanceReply -> state.copy(
        chatReply = state.chatReply.advanceIfMatching(event.requestKey),
        feedReply = state.feedReply.advanceIfMatching(event.requestKey),
        transientReply = state.transientReply.advanceIfMatching(event.requestKey),
    )

    is DashboardEvent.ClearReply -> state.copy(
        chatReply = state.chatReply?.takeUnless { it.requestKey == event.requestKey },
        feedReply = state.feedReply?.takeUnless { it.requestKey == event.requestKey },
        transientReply = state.transientReply?.takeUnless { it.requestKey == event.requestKey },
    )

    is DashboardEvent.PetTapped -> {
        val currentProgress = state.pet.petTapProgress.coerceIn(0, PetTapsPerHappinessReward - 1)
        val nextProgress = (currentProgress + 1) % PetTapsPerHappinessReward
        val rewarded = nextProgress == 0
        state.copy(
            pet = state.pet.copy(
                petTapProgress = nextProgress,
                happiness = if (rewarded) {
                    (state.pet.happiness + PetTapHappinessReward).coerceAtMost(100)
                } else {
                    state.pet.happiness
                },
            ),
            transientReply = if (
                rewarded && event.thanksMessage != null && event.replyRequestKey != null
            ) {
                DashboardReply(event.replyRequestKey, event.thanksMessage)
            } else {
                state.transientReply
            },
        )
    }

}

private fun activateFood(
    state: DashboardUiState,
    food: DashboardFood,
    requestKey: String,
): DashboardUiState {
    if (state.mode != DashboardMode.Feed || state.activeFeed != null) return state
    val nextPet = when (food) {
        DashboardFood.BerryBowl -> state.pet.copy(hunger = (state.pet.hunger + 25).coerceAtMost(100))
        DashboardFood.LeafCrunch -> state.pet.copy(energy = (state.pet.energy + 25).coerceAtMost(100))
    }
    val soundIndex = state.nextFeedAudioIndex
    return state.copy(
        pet = nextPet,
        feedError = null,
        feedReply = null,
        activeFeed = PendingFeedRequest(requestKey, food, soundIndex),
        feedToken = state.feedToken.copy(food = food, phase = FoodTokenPhase.Consuming),
        feedPulseId = state.feedPulseId + 1,
        nextFeedAudioIndex = (soundIndex + 1) % 3,
    )
}

fun remainingThinkingDelayMillis(startedAtMillis: Long, completedAtMillis: Long): Long =
    (DashboardMinimumThinkingMillis - (completedAtMillis - startedAtMillis)).coerceAtLeast(0L)

private fun DashboardReply?.advanceIfMatching(requestKey: String): DashboardReply? {
    if (this == null || this.requestKey != requestKey || !hasNextPortion) return this
    return copy(portionIndex = portionIndex + 1)
}

fun splitDashboardReplySentences(text: String): List<String> {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isEmpty()) return emptyList()
    val portions = mutableListOf<String>()
    var start = 0
    var index = 0
    val terminal = setOf('.', '!', '?', '…')
    val closing = setOf('"', '\'', '»', '”', ')')
    while (index < clean.length) {
        if (clean[index] !in terminal) {
            index += 1
            continue
        }
        while (index + 1 < clean.length && clean[index + 1] in terminal) index += 1
        while (index + 1 < clean.length && clean[index + 1] in closing) index += 1
        if (index + 1 == clean.length || clean[index + 1].isWhitespace()) {
            var portion = clean.substring(start, index + 1).trim()
            if (portion.endsWith('.') && !portion.endsWith("..")) portion = portion.dropLast(1)
            if (portion.isNotEmpty()) portions += portion
            start = index + 1
            while (start < clean.length && clean[start].isWhitespace()) start += 1
            index = start
        } else {
            index += 1
        }
    }
    if (start < clean.length) portions += clean.substring(start).trim()
    return portions.ifEmpty { listOf(clean) }
}

enum class DashboardDebugState(val routeValue: String) {
    Idle("idle"),
    ChatEmpty("chat-empty"),
    ChatIme("chat-ime"),
    ChatThinking("chat-thinking"),
    ChatReply("chat-reply"),
    FeedShelf("feed-shelf"),
    FeedDragging("feed-dragging"),
    FeedConsuming("feed-consuming"),
    FeedReply("feed-reply"),
    OutfitInsufficient("outfit-insufficient"),
    OutfitPromptIme("outfit-prompt-ime"),
    OutfitQueued("outfit-queued"),
    OutfitApplied("outfit-applied"),
    TravelEmpty("travel-empty"),
    TravelIme("travel-ime"),
    TravelStarting("travel-starting"),
    TravelFailure("travel-failure"),
    TravelQueuedFirst("travel-queued-first"),
    TravelQueuedFinal("travel-queued-final"),
    TravelReady("travel-ready");

    val requestsIme: Boolean
        get() = this == ChatIme || this == OutfitPromptIme || this == TravelIme
    val freezesAsync: Boolean
        get() = this != Idle && this != ChatIme && this != OutfitPromptIme && this != TravelIme
    val freezesMotion: Boolean
        get() = this == ChatThinking ||
            this == FeedDragging ||
            this == FeedConsuming ||
            this == OutfitQueued ||
            this == TravelQueuedFirst
    val freezesReplyAdvance: Boolean
        get() = this != Idle

    companion object {
        fun fromRouteValue(value: String?): DashboardDebugState = entries.firstOrNull {
            it.routeValue == value
        } ?: Idle
    }
}

fun dashboardDebugFixture(
    debugState: DashboardDebugState,
    pet: PetDashboardState,
): DashboardUiState {
    val base = DashboardUiState(pet)
    return when (debugState) {
        DashboardDebugState.Idle -> base
        DashboardDebugState.ChatEmpty -> base.copy(mode = DashboardMode.Chat)
        DashboardDebugState.ChatIme -> base.copy(
            mode = DashboardMode.Chat,
            chatDraft = "Меня зовут Сергей",
        )
        DashboardDebugState.ChatThinking -> base.copy(
            mode = DashboardMode.Chat,
            activeChat = PendingChatRequest("debug-chat-thinking", "Меня зовут Сергей"),
        )
        DashboardDebugState.ChatReply -> base.copy(
            mode = DashboardMode.Chat,
            chatReply = DashboardReply("debug-chat-reply", DeterministicChatReply),
        )
        DashboardDebugState.FeedShelf -> base.copy(mode = DashboardMode.Feed)
        DashboardDebugState.FeedDragging -> base.copy(
            mode = DashboardMode.Feed,
            feedToken = FoodTokenMotion(
                food = DashboardFood.BerryBowl,
                phase = FoodTokenPhase.Dragging,
                offsetX = 68f,
                offsetY = -252f,
            ),
        )
        DashboardDebugState.FeedConsuming -> base.copy(
            mode = DashboardMode.Feed,
            activeFeed = PendingFeedRequest("debug-feed-consuming", DashboardFood.BerryBowl, 0),
            feedToken = FoodTokenMotion(
                food = DashboardFood.BerryBowl,
                phase = FoodTokenPhase.Consuming,
                offsetX = 68f,
                offsetY = -252f,
            ),
            feedPulseId = 1,
        )
        DashboardDebugState.FeedReply -> base.copy(
            mode = DashboardMode.Feed,
            feedReply = DashboardReply("debug-feed-reply", BerryReply),
        )
        DashboardDebugState.OutfitInsufficient -> base.copy(
            mode = DashboardMode.Outfit,
            outfitDraft = "В футболку Metallica",
            outfitError = OutfitInsufficientMessage,
        )
        DashboardDebugState.OutfitPromptIme -> base.copy(
            pet = pet.copy(experience = 200),
            mode = DashboardMode.Outfit,
            outfitDraft = "В футболку Metallica",
        )
        DashboardDebugState.OutfitQueued -> base.copy(
            pendingOutfit = PendingOutfitGeneration(
                petId = pet.petId,
                requestKey = "debug-outfit-queued",
                prompt = "В футболку Metallica",
                displayItem = "футболка Metallica",
                localJobId = "local-fake-outfit",
                createdAtEpochMillis = 0L,
            ),
            chargedOutfitRequestKeys = setOf("debug-outfit-queued"),
            transientReply = DashboardReply("debug-outfit-queued", DeterministicOutfitReply),
        )
        DashboardDebugState.OutfitApplied -> base.copy(
            pet = pet.copy(assetSetId = "debug-applied-outfit-asset"),
        )
        DashboardDebugState.TravelEmpty -> base.copy(mode = DashboardMode.Travel)
        DashboardDebugState.TravelIme -> base.copy(
            mode = DashboardMode.Travel,
            travelDraft = DeterministicTravelPrompt,
        )
        DashboardDebugState.TravelStarting -> base.copy(
            mode = DashboardMode.Travel,
            travelDraft = DeterministicTravelPrompt,
            activeTravel = PendingTravelRequest(
                requestKey = "debug-travel-starting",
                prompt = DeterministicTravelPrompt,
            ),
        )
        DashboardDebugState.TravelFailure -> base.copy(
            mode = DashboardMode.Travel,
            travelDraft = DeterministicTravelPrompt,
            travelError = TravelFailureMessage,
        )
        DashboardDebugState.TravelQueuedFirst -> base.copy(
            pendingTravel = PendingTravelGeneration(
                petId = pet.petId,
                requestKey = "debug-travel-queued",
                prompt = DeterministicTravelPrompt,
                localJobId = "local-fake-travel-debug",
                createdAtEpochMillis = 0L,
            ),
            queuedTravelRequestKeys = setOf("debug-travel-queued"),
            transientReply = DashboardReply("debug-travel-queued", DeterministicTravelReply),
        )
        DashboardDebugState.TravelQueuedFinal -> base.copy(
            pendingTravel = PendingTravelGeneration(
                petId = pet.petId,
                requestKey = "debug-travel-queued",
                prompt = DeterministicTravelPrompt,
                localJobId = "local-fake-travel-debug",
                createdAtEpochMillis = 0L,
            ),
            queuedTravelRequestKeys = setOf("debug-travel-queued"),
            transientReply = DashboardReply(
                requestKey = "debug-travel-queued",
                text = DeterministicTravelReply,
                portionIndex = 2,
            ),
        )
        DashboardDebugState.TravelReady -> base
    }
}
