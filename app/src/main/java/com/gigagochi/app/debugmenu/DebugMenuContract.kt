package com.gigagochi.app.debugmenu

import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.model.PetDashboardState

data class DebugMenuBindings(
    val routeName: String,
    val pet: PetDashboardState?,
    val firstSession: LocalFirstSession?,
    val onboardingActive: Boolean,
    val savedPetAvailable: Boolean,
    val fixtureActive: Boolean,
    val visualMoodOverride: String?,
    val isPetDead: Boolean,
    val onToggleOnboarding: () -> Unit,
    val onOpenFixture: () -> Unit,
    val onRestoreSavedPet: () -> Unit,
    val onOpenTravelDemo: () -> Unit,
    val onResetStats: () -> Unit,
    val onVisualMoodOverride: (String?) -> Unit,
    val onKillPet: () -> Unit,
    val onRevivePet: () -> Unit,
    val onCreateNewPet: () -> Unit,
    val onSendPush: () -> Unit,
)
