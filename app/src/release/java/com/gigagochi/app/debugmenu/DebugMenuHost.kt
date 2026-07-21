package com.gigagochi.app.debugmenu

import android.content.Context
import androidx.compose.runtime.Composable
import com.gigagochi.app.core.database.ScheduledStoryStore
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.feature.create.PetGenerationAdapter
import com.gigagochi.app.feature.dashboard.DashboardChatAdapter
import com.gigagochi.app.feature.dashboard.DashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.DashboardTravelAdapter

@Composable
fun DebugMenuHost(bindings: DebugMenuBindings) = Unit

fun debugFixturePet(): PetDashboardState? = null
fun debugPreferredPetId(context: Context): String? = null
fun debugSavedPetId(context: Context): String? = null
fun setDebugFixtureSelection(context: Context, fixturePetId: String, savedPetId: String?) = Unit
fun clearDebugFixtureSelection(context: Context) = Unit
fun debugDeadPetId(context: Context): String? = null
fun setDebugDeadPetId(context: Context, petId: String?) = Unit
fun scheduleDebugPush(context: Context) = Unit
fun recordDebugEvent(kind: String, title: String, text: String) = Unit
fun debugGenerationAdapter(delegate: PetGenerationAdapter): PetGenerationAdapter = delegate
fun debugChatAdapter(delegate: DashboardChatAdapter): DashboardChatAdapter = delegate
fun debugOutfitAdapter(delegate: DashboardOutfitAdapter): DashboardOutfitAdapter = delegate
fun debugTravelAdapter(delegate: DashboardTravelAdapter): DashboardTravelAdapter = delegate
fun debugScheduledStoryService(delegate: AndroidFeatureService): AndroidFeatureService = delegate
suspend fun ensureDebugFixtureStories(
    ownerId: String,
    pet: PetDashboardState,
    store: ScheduledStoryStore,
): Boolean = true
