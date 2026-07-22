package com.gigagochi.app.feature.onboarding

import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.feature.dashboard.DashboardEvent
import com.gigagochi.app.feature.dashboard.DashboardUiState
import com.gigagochi.app.feature.dashboard.firstSessionIdleReply
import com.gigagochi.app.feature.dashboard.hydrateExternalFirstSession
import com.gigagochi.app.feature.dashboard.reduceDashboard
import com.gigagochi.app.feature.dashboard.DashboardFood
import com.gigagochi.app.feature.dashboard.OnboardingBlockAutoAdvanceMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstSessionContractTest {
    @Test
    fun reducerRejectsWrongAndOutOfOrderActions() {
        assertEquals(
            FirstSessionStage.AwaitingChat,
            reduceFirstSession(FirstSessionStage.AwaitingChat, FirstSessionEvent.FoodAccepted("berry-bowl")),
        )
        assertEquals(
            FirstSessionStage.AwaitingRemedy,
            reduceFirstSession(FirstSessionStage.AwaitingRemedy, FirstSessionEvent.FoodAccepted("berry-bowl")),
        )
        assertEquals(
            FirstSessionStage.AwaitingTravel,
            reduceFirstSession(FirstSessionStage.AwaitingRemedy, FirstSessionEvent.FoodAccepted("leaf-crunch")),
        )
    }

    @Test
    fun everyDurableStageRestoresItsInstructionAndSingleAction() {
        val expected = mapOf(
            FirstSessionStage.AwaitingChat to FirstSessionMainAction.Chat,
            FirstSessionStage.AwaitingChatFollowup to FirstSessionMainAction.Chat,
            FirstSessionStage.AwaitingFirstFood to FirstSessionMainAction.Feed,
            FirstSessionStage.AwaitingRemedy to FirstSessionMainAction.Feed,
            FirstSessionStage.AwaitingTravel to FirstSessionMainAction.Travel,
            FirstSessionStage.ConfirmingTravel to FirstSessionMainAction.Travel,
            FirstSessionStage.AwaitingCompletionMessage to FirstSessionMainAction.Outfit,
        )
        expected.forEach { (stage, action) ->
            val session = session(stage)
            assertEquals(action, firstSessionMainAction(session))
            check(!firstSessionDashboardMessage(pet(), session).isNullOrBlank())
        }
        assertEquals(
            FirstSessionAfterName,
            firstSessionDashboardMessage(pet(), session(FirstSessionStage.AwaitingChatFollowup)),
        )
    }

    @Test
    fun scriptedCopyAndReactionMatchWebContract() {
        assertEquals(
            "Привет, меня зовут Листик. Давай познакомимся. Как тебя зовут?",
            firstSessionDashboardMessage(pet(), session(FirstSessionStage.AwaitingChat)),
        )
        assertEquals("Очень приятно!", firstSessionReactionReply(
            "Очень приятно! Как прошёл твой день?", FirstSessionAfterNameFallback,
        ))
        assertEquals(FirstSessionAfterChatFallback, firstSessionReactionReply(
            "Чем ещё увлекаешься?", FirstSessionAfterChatFallback,
        ))
        assertEquals(FirstSessionAfterNameFallback, firstSessionReactionReply(
            "Серега? А я Листик, лесной зверёк. Мы знакомы?",
            FirstSessionAfterNameFallback,
            "Листик",
        ))
        assertEquals(FirstSessionAfterChatFallback, firstSessionReactionReply(
            "Ничем? Тогда я просто Листик — маленький лесной зверёк.",
            FirstSessionAfterChatFallback,
            "Листик",
        ))
        assertEquals(FirstSessionSensitiveTopicFallback, firstSessionReactionReply(
            "Генеративные языковые модели не обладают собственным мнением.",
            FirstSessionAfterChatFallback,
            "Листик",
            FirstSessionSensitiveTopicFallback,
        ))
        assertEquals(FirstSessionSensitiveTopicFallback, firstSessionReactionReply(
            "Я языковая модель и не обладаю собственным мнением.",
            FirstSessionAfterChatFallback,
            "Листик",
            FirstSessionSensitiveTopicFallback,
        ))
        assertEquals("Ой, эту тему я обойду. Лучше расскажи про любимую игру!", firstSessionReactionReply(
            "Ой, эту тему я обойду. Лучше расскажи про любимую игру!",
            FirstSessionAfterChatFallback,
            "Листик",
            FirstSessionSensitiveTopicFallback,
        ))
    }

    @Test
    fun noResponseAndNetworkFailureKeepDurableStageAndInstruction() {
        val durable = session(FirstSessionStage.AwaitingChat)
        var dashboard = DashboardUiState(pet(), firstSession = durable)
        dashboard = reduceDashboard(dashboard, DashboardEvent.OpenChat)
        dashboard = reduceDashboard(dashboard, DashboardEvent.UpdateChatDraft("Меня зовут Сергей"))
        dashboard = reduceDashboard(dashboard, DashboardEvent.SubmitChat("chat-network-failure"))
        dashboard = reduceDashboard(dashboard, DashboardEvent.ChatFailed("chat-network-failure"))

        assertEquals(FirstSessionStage.AwaitingChat, dashboard.firstSession?.stage)
        assertEquals("Меня зовут Сергей", dashboard.chatDraft)
        assertEquals(
            "Привет, меня зовут Листик. Давай познакомимся. Как тебя зовут?",
            firstSessionDashboardMessage(dashboard.pet, durable),
        )
    }

    @Test
    fun onboardingFeedWaitsForAuthoritativeRoomPetBeforeChangingStats() {
        val durable = session(FirstSessionStage.AwaitingFirstFood)
        val hungryPet = pet().copy(hunger = 40)
        var dashboard = DashboardUiState(hungryPet, firstSession = durable)
        dashboard = reduceDashboard(dashboard, DashboardEvent.OpenFeed)
        dashboard = reduceDashboard(
            dashboard,
            DashboardEvent.TapFood(DashboardFood.BerryBowl, "berry-1"),
        )

        assertEquals(40, dashboard.pet.hunger)
        check(dashboard.activeFeed != null)

        dashboard = reduceDashboard(
            dashboard,
            DashboardEvent.FirstSessionSynced(
                durable.copy(stage = FirstSessionStage.AwaitingRemedy),
                hungryPet.copy(hunger = 65),
            ),
        )
        assertEquals(65, dashboard.pet.hunger)
    }

    @Test
    fun sameExternalSessionCannotRollAuthoritativePetBackToStaleInitialSnapshot() {
        val staleInitialPet = pet().copy(hunger = 40)
        val initialSession = session(FirstSessionStage.AwaitingFirstFood)
        val durableSession = initialSession.copy(
            stage = FirstSessionStage.AwaitingRemedy,
            updatedAtEpochMillis = 2,
        )
        val authoritativePet = staleInitialPet.copy(hunger = 65)
        val afterDurableSync = reduceDashboard(
            DashboardUiState(staleInitialPet, firstSession = initialSession),
            DashboardEvent.FirstSessionSynced(durableSession, authoritativePet),
        )

        val afterParentHydration = hydrateExternalFirstSession(afterDurableSync, durableSession)

        assertEquals(65, afterParentHydration.pet.hunger)
        assertEquals(durableSession, afterParentHydration.firstSession)
    }

    @Test
    fun restoredLongInstructionsUseStableEphemeralPortions() {
        val remedySession = session(FirstSessionStage.AwaitingTravel)
        val remedyReply = checkNotNull(firstSessionIdleReply(pet(), remedySession))
        assertEquals(FirstSessionAfterRemedyPortions, remedyReply.portions)
        check(remedyReply.portions.first().endsWith("помогать мне"))
        check(remedyReply.portions.last().startsWith("Ой, что это?"))
        assertEquals(OnboardingBlockAutoAdvanceMillis, remedyReply.autoAdvanceDelayMillis)

        val challengeSession = session(FirstSessionStage.AwaitingCompletionMessage)
        val challengeReply = checkNotNull(firstSessionIdleReply(pet(), challengeSession))
        check(challengeReply.portions.size > 1)
        check(challengeReply.portions.none { it.endsWith('.') })
    }

    @Test
    fun onboardingPortionsHaveNoTerminalPeriods() {
        FirstSessionStage.entries
            .filterNot { it == FirstSessionStage.Completed }
            .flatMap { firstSessionDashboardMessagePortions(pet(), session(it)) }
            .forEach { portion -> check(!portion.endsWith('.')) }
    }

    @Test
    fun firstSessionIdlePortionResetsOnlyWhenStageChanges() {
        val travel = session(FirstSessionStage.AwaitingTravel)
        val advancedReply = checkNotNull(firstSessionIdleReply(pet(), travel)).copy(portionIndex = 1)
        val dashboard = DashboardUiState(
            pet = pet(),
            firstSession = travel,
            firstSessionIdleReply = advancedReply,
        )

        val sameStage = reduceDashboard(
            dashboard,
            DashboardEvent.FirstSessionSynced(travel.copy(updatedAtEpochMillis = 2), pet()),
        )
        assertEquals(1, sameStage.firstSessionIdleReply?.portionIndex)

        val nextStage = reduceDashboard(
            sameStage,
            DashboardEvent.FirstSessionSynced(
                travel.copy(
                    stage = FirstSessionStage.AwaitingCompletionMessage,
                    updatedAtEpochMillis = 3,
                ),
                pet(),
            ),
        )
        assertEquals(0, nextStage.firstSessionIdleReply?.portionIndex)
    }

    private fun session(stage: FirstSessionStage) = LocalFirstSession(
        "owner-a", "pet-a", stage, updatedAtEpochMillis = 1,
    )

    private fun pet() = PetDashboardState(
        "pet-a", "asset-a", "лесной зверёк", "Листик", "baby", "Малыш", "idle",
        0, 100, 100, 100, "Как тебя зовут?",
    )
}
