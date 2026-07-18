package com.gigagochi.app.feature.travel

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.model.ScheduledStory
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ScheduledStoryRoute(
    pet: PetDashboardState,
    initialStory: LocalScheduledStory,
    coordinator: ScheduledStoryCoordinator,
    mediaUrlPolicy: StaticMediaUrlPolicy,
    navigationAction: ContextualNavigationAction = ContextualNavigationAction.Close,
    onNavigateDashboard: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberTravelReducedMotionPreference()
    var state by remember(initialStory) {
        mutableStateOf(initialStory.story.toTravelState(pet, initialStory.choiceRequestKey))
    }

    LaunchedEffect(initialStory.story.storyId, initialStory.story.selectedChoice) {
        if (initialStory.story.selectedChoice != null) {
            when (val recovered = coordinator.reconcileSelected(initialStory.story.storyId)) {
                is ScheduledStoryChoiceResult.Saved -> {
                    state = recovered.story.toTravelState(pet, recovered.requestKey).copy(
                        pet = state.pet.copy(experience = recovered.committedExperience),
                        appliedStoryTravelIds = recovered.committedTravelIds,
                    )
                }
                is ScheduledStoryChoiceResult.Failure -> Unit
            }
        }
    }

    BackHandler(onBack = onNavigateDashboard)
    InteractiveTravelStoryScreen(
        state = state,
        reducedMotion = reducedMotion,
        forcePoster = false,
        scrollTarget = StoryScrollTarget.Top,
        mediaUrlPolicy = mediaUrlPolicy,
        navigationAction = navigationAction,
        onNavigateBack = onNavigateDashboard,
        onChoice = { choice ->
            if (state.phase != TravelEntryPhase.StoryQuestion) return@InteractiveTravelStoryScreen
            val requestKey = UUID.randomUUID().toString()
            state = reduceTravelEntry(
                state,
                TravelEntryEvent.SubmitStoryChoice(choice, requestKey),
            )
            scope.launch {
                state = when (
                    val result = coordinator.choose(initialStory.story.storyId, choice, requestKey)
                ) {
                    is ScheduledStoryChoiceResult.Saved -> {
                        val aligned = state.copy(
                            activeChoice = state.activeChoice?.copy(requestKey = result.requestKey),
                        )
                        reduceTravelEntry(
                            aligned,
                            TravelEntryEvent.StoryChoiceResolved(
                                requestKey = result.requestKey,
                                result = result.story.toInteractiveResult(result.requestKey),
                                committedExperience = result.committedExperience,
                                committedTravelIds = result.committedTravelIds,
                            ),
                        )
                    }
                    is ScheduledStoryChoiceResult.Failure -> reduceTravelEntry(
                        state,
                        TravelEntryEvent.StoryChoiceFailed(requestKey),
                    )
                }
            }
        },
        onFinish = onNavigateDashboard,
    )
}

internal fun ScheduledStory.toTravelState(
    pet: PetDashboardState,
    resultRequestKey: String? = null,
): TravelEntryState {
    val travelStory = InteractiveTravelStory(
        travelId = storyId,
        title = title,
        storyText = text,
        challenge = question,
        choices = choices,
        enabledChoice = "",
        imageUrl = imageUrl,
        videoUrl = videoUrl,
    )
    val resolved = result?.let { toInteractiveResult(requireNotNull(resultRequestKey)) }
    return TravelEntryState(
        pet = TravelEntryPet(pet.petId, pet.name, pet.experience),
        phase = if (resolved == null) TravelEntryPhase.StoryQuestion else TravelEntryPhase.StoryResult,
        story = travelStory,
        storyResult = resolved,
    )
}

private fun ScheduledStory.toInteractiveResult(requestKey: String): InteractiveTravelStoryResult {
    val outcome = requireNotNull(result)
    return InteractiveTravelStoryResult(
        travelId = storyId,
        requestKey = requestKey,
        answer = requireNotNull(selectedChoice),
        text = outcome.text,
        reaction = outcome.reaction,
        consequence = outcome.consequence,
        experienceGained = outcome.experienceGained,
        imageUrl = resultImageUrl,
        videoUrl = resultVideoUrl,
    )
}
