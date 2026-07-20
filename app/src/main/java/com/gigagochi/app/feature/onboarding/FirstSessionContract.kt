package com.gigagochi.app.feature.onboarding

import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.model.PetDashboardState

const val FirstSessionAfterNameFallback = "Приятно познакомиться!"
const val FirstSessionAfterName = "А чем ты любишь заниматься?"
const val FirstSessionAfterChatFallback = "Звучит здорово!"
const val FirstSessionAfterChat =
    "Слушай, что-то я проголодался. Может, у тебя что-нибудь завалялось?"
const val FirstSessionAfterFirstFood =
    "Хм, вкусно! Но что-то я себя неважно чувствую. Может, у тебя есть какое-нибудь снадобье?"
const val FirstSessionAfterRemedyCare =
    "Уф, мне лучше. Иногда я могу проголодаться или попасть в беду. " +
        "Будет круто, если будешь заходить и помогать мне."
const val FirstSessionAfterRemedyBat =
    "Ой, что это? Я увидел летучую мышь, и ей нужна помощь."
const val FirstSessionAfterRemedy = "$FirstSessionAfterRemedyCare $FirstSessionAfterRemedyBat"
val FirstSessionAfterRemedyPortions = listOf(
    FirstSessionAfterRemedyCare,
    FirstSessionAfterRemedyBat,
).map(String::withoutTerminalPeriod)
const val FirstSessionAfterChallenge =
    "За правильные ответы я получаю монетки. Их можно потратить на новый гардероб или " +
        "внешность. Попробуй меня во что-то нарядить."

enum class FirstSessionMainAction { Chat, Feed, Travel, Outfit }

sealed interface FirstSessionEvent {
    data object ChatSucceeded : FirstSessionEvent
    data class FoodAccepted(val routeValue: String) : FirstSessionEvent
    data class DestinationSelected(val destination: String) : FirstSessionEvent
    data object DestinationChanged : FirstSessionEvent
    data object BatFinished : FirstSessionEvent
    data object OutfitAccepted : FirstSessionEvent
}

fun reduceFirstSession(stage: FirstSessionStage, event: FirstSessionEvent): FirstSessionStage = when {
    stage == FirstSessionStage.AwaitingChat && event == FirstSessionEvent.ChatSucceeded ->
        FirstSessionStage.AwaitingChatFollowup
    stage == FirstSessionStage.AwaitingChatFollowup && event == FirstSessionEvent.ChatSucceeded ->
        FirstSessionStage.AwaitingFirstFood
    stage == FirstSessionStage.AwaitingFirstFood &&
        event == FirstSessionEvent.FoodAccepted("berry-bowl") -> FirstSessionStage.AwaitingRemedy
    stage == FirstSessionStage.AwaitingRemedy &&
        event == FirstSessionEvent.FoodAccepted("leaf-crunch") -> FirstSessionStage.AwaitingTravel
    stage == FirstSessionStage.AwaitingTravel && event is FirstSessionEvent.DestinationSelected &&
        event.destination.trim().isNotEmpty() -> FirstSessionStage.ConfirmingTravel
    stage == FirstSessionStage.ConfirmingTravel && event == FirstSessionEvent.DestinationChanged ->
        FirstSessionStage.AwaitingTravel
    stage == FirstSessionStage.AwaitingTravel && event == FirstSessionEvent.BatFinished ->
        FirstSessionStage.AwaitingCompletionMessage
    stage == FirstSessionStage.AwaitingCompletionMessage && event == FirstSessionEvent.OutfitAccepted ->
        FirstSessionStage.Completed
    else -> stage
}

fun firstSessionMainAction(session: LocalFirstSession?): FirstSessionMainAction? = when (session?.stage) {
    FirstSessionStage.AwaitingChat,
    FirstSessionStage.AwaitingChatFollowup,
    -> FirstSessionMainAction.Chat
    FirstSessionStage.AwaitingFirstFood,
    FirstSessionStage.AwaitingRemedy,
    -> FirstSessionMainAction.Feed
    FirstSessionStage.AwaitingTravel,
    FirstSessionStage.ConfirmingTravel,
    -> FirstSessionMainAction.Travel
    FirstSessionStage.AwaitingCompletionMessage -> FirstSessionMainAction.Outfit
    FirstSessionStage.Completed, null -> null
}

fun firstSessionDashboardMessage(pet: PetDashboardState, session: LocalFirstSession): String? =
    when (session.stage) {
        FirstSessionStage.AwaitingChat ->
            "Привет, меня зовут ${pet.name.trim().ifEmpty { "Без имени" }}. " +
                "Давай познакомимся. Как тебя зовут?"
        FirstSessionStage.AwaitingChatFollowup -> FirstSessionAfterName
        FirstSessionStage.AwaitingFirstFood -> FirstSessionAfterChat
        FirstSessionStage.AwaitingRemedy -> FirstSessionAfterFirstFood
        FirstSessionStage.AwaitingTravel,
        FirstSessionStage.ConfirmingTravel,
        -> FirstSessionAfterRemedy
        FirstSessionStage.AwaitingCompletionMessage -> FirstSessionAfterChallenge
        FirstSessionStage.Completed -> null
    }

fun firstSessionDashboardMessagePortions(
    pet: PetDashboardState,
    session: LocalFirstSession,
): List<String> = when (session.stage) {
    FirstSessionStage.AwaitingTravel,
    FirstSessionStage.ConfirmingTravel,
    -> FirstSessionAfterRemedyPortions
    FirstSessionStage.AwaitingCompletionMessage -> listOf(
        "За правильные ответы я получаю монетки",
        "Их можно потратить на новый гардероб или внешность",
        "Попробуй меня во что-то нарядить",
    )
    else -> listOfNotNull(firstSessionDashboardMessage(pet, session)?.withoutTerminalPeriod())
}

private fun String.withoutTerminalPeriod(): String = removeSuffix(".")

fun firstSessionReactionReply(
    reply: String,
    fallback: String,
    petName: String? = null,
): String {
    val normalizedPetName = petName?.trim().orEmpty()
    val petNameMention = normalizedPetName.takeIf(String::isNotEmpty)?.let { name ->
        Regex("(?iu)(?<![\\p{L}\\p{N}_])${Regex.escape(name)}(?![\\p{L}\\p{N}_])")
    }
    val declarative = Regex("(?<=[.!?…])\\s+").split(reply)
        .map(String::trim)
        .filter {
            it.isNotEmpty() &&
                '?' !in it &&
                '？' !in it &&
                petNameMention?.containsMatchIn(it) != true
        }
        .joinToString(" ")
        .trim()
    return declarative.ifEmpty { fallback }
}
