package com.gigagochi.app.feature.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatePetStateMachineTest {
    @Test
    fun allSixStepsPreserveAnswersAndOnlyFirstStartsGeneration() {
        var state = CreatePetState()

        state = state.answer("Ледяного дракона", requestKeyFactory = { "request-1" })
        assertEquals(1, state.step)
        assertEquals(GenerationStatus.Running, state.generation)
        assertEquals(1, state.generationAttempt)
        assertEquals(CreationBackgroundPhase.Transition, state.backgroundPhase)

        state = state.answer("Тото")
        state = state.answer("Добрый")
        state = state.answer("Пауков")
        state = state.answer("Вантуз")

        assertEquals(FinalCreationStep, state.step)
        assertEquals(
            listOf("Ледяного дракона", "Тото", "Добрый", "Пауков", "Вантуз"),
            state.answers,
        )
        assertEquals(1, state.generationAttempt)
        assertTrue(state.isFinal)
        assertFalse(state.canNavigate)

        state = state.markGenerationReady(GeneratedPetFixture(state.description))
        assertTrue(state.canNavigate)
    }

    @Test
    fun readyResultWaitsUntilFinalQuestion() {
        var state = CreatePetState().answer("Водяной дух", requestKeyFactory = { "request" })
        state = state.markGenerationReady(GeneratedPetFixture(state.description))

        assertFalse(state.canNavigate)
        repeat(4) { index ->
            state = state.answer(CreationQuestions[index + 1].options.first())
        }
        assertTrue(state.canNavigate)
    }

    @Test
    fun customInputTrimsForSubmissionAndCapsAtThreeHundredCharacters() {
        var state = CreatePetState().openCustomInput()
        assertFalse(state.updateCustomValue("   ").canSubmitCustom)

        state = state.updateCustomValue("  Мой дракон  " + "x".repeat(400))
        assertEquals(MaxCustomPromptLength, state.customValue.length)
        assertTrue(state.canSubmitCustom)

        state = state.submitCustom(requestKeyFactory = { "custom" })
        assertEquals(MaxCustomPromptLength - 2, state.description.length)
        assertEquals(state.description, state.pending?.description)
        assertEquals(1, state.step)
    }

    @Test
    fun reducedMotionSkipsTransitionAndRecoveryStartsFormed() {
        val reduced = CreatePetState().answer(
            "Человек-яблоко",
            reducedMotion = true,
            requestKeyFactory = { "reduced" },
        )
        assertEquals(CreationBackgroundPhase.Formed, reduced.backgroundPhase)

        val recovered = recoveredCreatePetState("Ледяного дракона")
        assertTrue(recovered.isFinal)
        assertEquals(CreationBackgroundPhase.Formed, recovered.backgroundPhase)
        assertEquals(GenerationStatus.Running, recovered.generation)
        assertEquals(1, recovered.generationAttempt)
    }

    @Test
    fun terminalFailedGenerationRetriesWithNewRequestWithoutRestartingQuestions() {
        val failed = CreatePetState()
            .answer("Ледяного дракона", requestKeyFactory = { "stable-key" })
            .markGenerationFailed(newRequestRequired = true)
        val retried = failed.retryGeneration(requestKeyFactory = { "retry-key" })

        assertEquals("retry-key", retried.pending?.requestKey)
        assertEquals(failed.pending?.petId, retried.pending?.petId)
        assertEquals(1, retried.step)
        assertEquals(2, retried.generationAttempt)
        assertEquals(GenerationStatus.Running, retried.generation)
    }

    @Test
    fun transientFailureRetriesAttachedRequestWithoutChangingIdentity() {
        val failed = CreatePetState()
            .answer("Ледяного дракона", requestKeyFactory = { "stable-key" })
            .markGenerationFailed()

        assertEquals("stable-key", failed.retryGeneration().pending?.requestKey)
    }
}
