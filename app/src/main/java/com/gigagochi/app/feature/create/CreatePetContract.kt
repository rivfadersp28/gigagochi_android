package com.gigagochi.app.feature.create

import com.gigagochi.app.core.model.PetGeneratedMedia
import java.util.UUID

const val MaxCustomPromptLength = 300
const val FinalCreationStep = 5

data class CreationQuestion(
    val title: String,
    val options: List<String>,
)

val CreationQuestions = listOf(
    CreationQuestion(
        title = "Кого хочешь создать?",
        options = listOf("Ледяного дракона", "Человек-яблоко", "Водяной дух"),
    ),
    CreationQuestion(
        title = "Как его будут звать?",
        options = listOf("Тото", "Бачок", "Денис"),
    ),
    CreationQuestion(
        title = "Какой у него характер?",
        options = listOf("Добрый", "Злой", "Ленивый"),
    ),
    CreationQuestion(
        title = "Чего он боится?",
        options = listOf("Пауков", "Бизнесменов", "Людоедов"),
    ),
    CreationQuestion(
        title = "Какой у него любимый предмет?",
        options = listOf("Вантуз", "Рулон бумаги", "Кока кола"),
    ),
)

enum class CreationBackgroundPhase { Initial, Transition, Formed }

object CreationMediaContract {
    const val FramesPerSecond = 24L
    const val InitialStartFrame = 0L
    const val InitialEndFrame = 170L
    const val TransitionStartFrame = InitialEndFrame
    const val TransitionEndFrame = 267L
    const val FormedStartFrame = TransitionEndFrame
    const val FormedEndFrame = 447L

    const val InitialStartUs = 0L
    const val InitialEndUs = InitialEndFrame * 1_000_000L / FramesPerSecond
    const val TransitionStartUs = InitialEndUs
    const val TransitionEndUs = TransitionEndFrame * 1_000_000L / FramesPerSecond
    const val FormedStartUs = TransitionEndUs
    const val FormedEndUs = FormedEndFrame * 1_000_000L / FramesPerSecond
}

data class PendingPetGeneration(
    val petId: String,
    val description: String,
    val requestKey: String,
)

data class GeneratedPetFixture(
    val description: String,
    val petId: String = "local-fake-pet",
    val assetSetId: String = "local-fake-pending",
    val generatedMedia: PetGeneratedMedia = PetGeneratedMedia(),
)

sealed interface GenerationStatus {
    data object Idle : GenerationStatus
    data object Running : GenerationStatus
    data class Ready(val pet: GeneratedPetFixture) : GenerationStatus
    data class Error(
        val message: String,
        val newRequestRequired: Boolean = false,
    ) : GenerationStatus
}

data class CreatePetState(
    val step: Int = 0,
    val answers: List<String> = emptyList(),
    val description: String = "",
    val customInputStep: Int? = null,
    val customValue: String = "",
    val backgroundPhase: CreationBackgroundPhase = CreationBackgroundPhase.Initial,
    val generation: GenerationStatus = GenerationStatus.Idle,
    val generationAttempt: Int = 0,
    val pending: PendingPetGeneration? = null,
) {
    val question: CreationQuestion?
        get() = CreationQuestions.getOrNull(step)
    val isCustomInputOpen: Boolean
        get() = customInputStep == step
    val isFinal: Boolean
        get() = step == FinalCreationStep
    val canSubmitCustom: Boolean
        get() = customValue.trim().isNotEmpty()
    val canNavigate: Boolean
        get() = isFinal && generation is GenerationStatus.Ready
}

fun CreatePetState.openCustomInput(): CreatePetState = copy(
    customInputStep = step,
    customValue = "",
)

fun CreatePetState.closeCustomInput(): CreatePetState = copy(
    customInputStep = null,
    customValue = "",
)

fun CreatePetState.updateCustomValue(value: String): CreatePetState = copy(
    customValue = value.take(MaxCustomPromptLength),
)

fun CreatePetState.answer(
    rawAnswer: String,
    reducedMotion: Boolean = false,
    requestKeyFactory: () -> String = { UUID.randomUUID().toString() },
    petIdFactory: () -> String = { UUID.randomUUID().toString() },
): CreatePetState {
    if (isFinal) return this
    val answer = rawAnswer.trim()
    if (answer.isEmpty()) return this

    if (step == 0) {
        val pendingRequest = PendingPetGeneration(
            petId = petIdFactory(),
            description = answer,
            requestKey = requestKeyFactory(),
        )
        return copy(
            step = 1,
            answers = answers + answer,
            description = answer,
            customInputStep = null,
            customValue = "",
            backgroundPhase = if (reducedMotion) {
                CreationBackgroundPhase.Formed
            } else {
                CreationBackgroundPhase.Transition
            },
            generation = GenerationStatus.Running,
            generationAttempt = generationAttempt + 1,
            pending = pendingRequest,
        )
    }

    return copy(
        step = (step + 1).coerceAtMost(FinalCreationStep),
        answers = answers + answer,
        customInputStep = null,
        customValue = "",
        backgroundPhase = CreationBackgroundPhase.Formed,
    )
}

fun CreatePetState.submitCustom(
    reducedMotion: Boolean = false,
    requestKeyFactory: () -> String = { UUID.randomUUID().toString() },
    petIdFactory: () -> String = { UUID.randomUUID().toString() },
): CreatePetState {
    if (!canSubmitCustom) return this
    return answer(customValue, reducedMotion, requestKeyFactory, petIdFactory)
}

fun CreatePetState.markTransitionComplete(): CreatePetState = copy(
    backgroundPhase = CreationBackgroundPhase.Formed,
)

fun CreatePetState.markGenerationReady(result: GeneratedPetFixture): CreatePetState = copy(
    generation = GenerationStatus.Ready(result),
)

fun CreatePetState.markGenerationFailed(
    message: String = "Не получилось создать питомца. Попробуйте ещё раз.",
    newRequestRequired: Boolean = false,
): CreatePetState = copy(
    generation = GenerationStatus.Error(message, newRequestRequired),
)

fun CreatePetState.retryGeneration(
    requestKeyFactory: () -> String = { UUID.randomUUID().toString() },
): CreatePetState {
    if (pending == null || generation is GenerationStatus.Running) return this
    val retryPending = if ((generation as? GenerationStatus.Error)?.newRequestRequired == true) {
        pending.copy(requestKey = requestKeyFactory())
    } else {
        pending
    }
    return copy(
        generation = GenerationStatus.Running,
        generationAttempt = generationAttempt + 1,
        pending = retryPending,
    )
}

fun recoveredCreatePetState(description: String): CreatePetState {
    val validDescription = description.trim().take(MaxCustomPromptLength)
    val pending = PendingPetGeneration(
        petId = "recovered-local-pet",
        description = validDescription,
        requestKey = "recovered-local-fixture",
    )
    return CreatePetState(
        step = FinalCreationStep,
        answers = listOf(validDescription),
        description = validDescription,
        backgroundPhase = CreationBackgroundPhase.Formed,
        generation = GenerationStatus.Running,
        generationAttempt = 1,
        pending = pending,
    )
}
