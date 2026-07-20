package com.gigagochi.app.feature.create

import org.junit.Assert.assertEquals
import org.junit.Test

class CreationMediaContractTest {
    @Test
    fun segmentBoundariesMatchCanonicalTwentyFourFpsTimeline() {
        assertEquals(7_083_333L, CreationMediaContract.InitialEndUs)
        assertEquals(CreationMediaContract.InitialEndUs, CreationMediaContract.TransitionStartUs)
        assertEquals(11_125_000L, CreationMediaContract.TransitionEndUs)
        assertEquals(CreationMediaContract.TransitionEndUs, CreationMediaContract.FormedStartUs)
        assertEquals(18_625_000L, CreationMediaContract.FormedEndUs)
    }

    @Test
    fun transitionCompletesIntoFormedLoop() {
        val transitioning = CreatePetState().answer(
            "Ледяного дракона",
            requestKeyFactory = { "media" },
        )
        assertEquals(CreationBackgroundPhase.Transition, transitioning.backgroundPhase)
        assertEquals(CreationBackgroundPhase.Formed, transitioning.markTransitionComplete().backgroundPhase)
    }
}
