package com.gigagochi.app.debugmenu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.debug.debugTestPetFixture
import com.gigagochi.app.feature.create.GeneratedPetFixture
import com.gigagochi.app.feature.create.PendingPetGeneration
import com.gigagochi.app.feature.create.PetGenerationAdapter
import com.gigagochi.app.feature.dashboard.DashboardChatAdapter
import com.gigagochi.app.feature.dashboard.DashboardChatResult
import com.gigagochi.app.feature.dashboard.DashboardOutfitAdapter
import com.gigagochi.app.feature.dashboard.DashboardTravelAdapter
import com.gigagochi.app.feature.dashboard.PendingChatRequest
import com.gigagochi.app.feature.dashboard.PendingOutfitGeneration
import com.gigagochi.app.feature.dashboard.PendingOutfitRequest
import com.gigagochi.app.feature.dashboard.PendingTravelGeneration
import com.gigagochi.app.feature.dashboard.PendingTravelRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val PreferencesName = "gigagochi_debug_menu"
private const val PreferredPetKey = "preferred_pet_id"
private const val SavedPetKey = "saved_pet_id"
private const val DeadPetKey = "dead_pet_id"

private data class DebugEvent(
    val kind: String,
    val title: String,
    val text: String,
)

private val DebugEvents = MutableStateFlow<List<DebugEvent>>(emptyList())

fun recordDebugEvent(kind: String, title: String, text: String) {
    DebugEvents.update { current ->
        (current + DebugEvent(kind.take(40), title.take(120), text.take(8_000)))
            .takeLast(100)
    }
}

fun debugGenerationAdapter(delegate: PetGenerationAdapter): PetGenerationAdapter =
    object : PetGenerationAdapter {
        override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
            recordDebugEvent("prompt", "Create request", "${request.requestKey}\n${request.description}")
            return runCatching { delegate.generate(request) }
                .onSuccess { recordDebugEvent("response", "Create succeeded", it.assetSetId) }
                .onFailure { recordDebugEvent("error", "Create failed", it.toString()) }
                .getOrThrow()
        }
    }

fun debugChatAdapter(delegate: DashboardChatAdapter): DashboardChatAdapter =
    object : DashboardChatAdapter {
        override suspend fun reply(request: PendingChatRequest, pet: PetDashboardState): DashboardChatResult {
            recordDebugEvent("prompt", "Chat request", "${request.requestKey}\n${request.message}")
            return runCatching { delegate.reply(request, pet) }
                .onSuccess { recordDebugEvent("response", "Chat reply", it.reply) }
                .onFailure { recordDebugEvent("error", "Chat failed", it.toString()) }
                .getOrThrow()
        }
    }

fun debugOutfitAdapter(delegate: DashboardOutfitAdapter): DashboardOutfitAdapter =
    object : DashboardOutfitAdapter {
        override suspend fun queue(
            request: PendingOutfitRequest,
            pet: PetDashboardState,
        ): PendingOutfitGeneration {
            recordDebugEvent("prompt", "Outfit request", "${request.requestKey}\n${request.prompt}")
            return runCatching { delegate.queue(request, pet) }
                .onSuccess { recordDebugEvent("response", "Outfit queued", it.toString()) }
                .onFailure { recordDebugEvent("error", "Outfit failed", it.toString()) }
                .getOrThrow()
        }
    }

fun debugTravelAdapter(delegate: DashboardTravelAdapter): DashboardTravelAdapter =
    object : DashboardTravelAdapter {
        override suspend fun queue(
            request: PendingTravelRequest,
            pet: PetDashboardState,
        ): PendingTravelGeneration {
            recordDebugEvent("prompt", "Travel request", "${request.requestKey}\n${request.prompt}")
            return runCatching { delegate.queue(request, pet) }
                .onSuccess { recordDebugEvent("response", "Travel queued", it.toString()) }
                .onFailure { recordDebugEvent("error", "Travel failed", it.toString()) }
                .getOrThrow()
        }
    }

fun debugFixturePet(): PetDashboardState? = debugTestPetFixture()

fun debugPreferredPetId(context: Context): String? = context
    .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    .getString(PreferredPetKey, null)

fun debugSavedPetId(context: Context): String? = context
    .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    .getString(SavedPetKey, null)

fun setDebugFixtureSelection(context: Context, fixturePetId: String, savedPetId: String?) {
    context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit()
        .putString(PreferredPetKey, fixturePetId)
        .apply {
            if (savedPetId == null) remove(SavedPetKey) else putString(SavedPetKey, savedPetId)
        }
        .apply()
}

fun clearDebugFixtureSelection(context: Context) {
    context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit()
        .remove(PreferredPetKey)
        .remove(SavedPetKey)
        .apply()
}

fun debugDeadPetId(context: Context): String? = context
    .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    .getString(DeadPetKey, null)

fun setDebugDeadPetId(context: Context, petId: String?) {
    context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit().apply {
        if (petId == null) remove(DeadPetKey) else putString(DeadPetKey, petId)
    }.apply()
}

@Composable
fun DebugMenuHost(bindings: DebugMenuBindings) {
    var open by remember { mutableStateOf(false) }
    var confirmNewPet by remember { mutableStateOf(false) }
    val events by DebugEvents.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        if (bindings.isPetDead) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF171310))
                    .padding(horizontal = 30.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Хозяин, я умер. Жаль, что я тебе не понравился", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))
                Text("Открой debug-меню, чтобы воскресить персонажа", color = Color(0xFFBDB7B1), fontSize = 13.sp)
            }
        }
        Surface(
            modifier = Modifier.padding(top = 18.dp, end = 14.dp),
            shape = CircleShape,
            color = Color(0xD91A1A1A),
            shadowElevation = 8.dp,
        ) {
            IconButton(
                onClick = { open = true },
                modifier = Modifier
                    .size(46.dp)
                    .semantics { contentDescription = "Открыть debug-меню" },
            ) {
                Text("⚙", color = Color.White, fontSize = 22.sp)
            }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = {
                Column {
                    Text("Debug", fontWeight = FontWeight.Bold)
                    Text(bindings.routeName, color = Color(0xFF777777), fontSize = 12.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DebugAction(
                        if (bindings.onboardingActive) {
                            "Выключить onboarding"
                        } else {
                            "Включить onboarding"
                        },
                        bindings.pet != null,
                    ) {
                        open = false
                        bindings.onToggleOnboarding()
                    }
                    DebugAction("Открыть тестового персонажа", !bindings.fixtureActive) {
                        open = false
                        bindings.onOpenFixture()
                    }
                    DebugAction("Переключиться на сохранённого", bindings.savedPetAvailable) {
                        open = false
                        bindings.onRestoreSavedPet()
                    }
                    DebugAction("Запустить демо-историю", bindings.pet != null) {
                        open = false
                        bindings.onOpenTravelDemo()
                    }
                    DebugAction("Сбросить параметры персонажа", bindings.pet != null) {
                        open = false
                        bindings.onResetStats()
                    }
                    if (bindings.isPetDead) {
                        DebugAction("Воскресить персонажа", bindings.pet != null) {
                            open = false
                            bindings.onRevivePet()
                        }
                    } else {
                        DebugAction("Убить персонажа", bindings.pet != null) {
                            open = false
                            bindings.onKillPet()
                        }
                    }
                    Text("Вид персонажа", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("sad" to "Грустный", "idle" to "Обычный", "happy" to "Счастливый")
                            .forEach { (mood, label) ->
                                TextButton(
                                    onClick = {
                                        bindings.onVisualMoodOverride(
                                            mood.takeUnless { bindings.visualMoodOverride == mood },
                                        )
                                        open = false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(label, fontSize = 10.sp) }
                            }
                    }
                    DebugAction("Создать нового персонажа", true) { confirmNewPet = true }
                    Spacer(Modifier.height(4.dp))
                    DebugSnapshot(bindings)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Лента / Prompts", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = { DebugEvents.value = emptyList() }) { Text("Очистить") }
                    }
                    if (events.isEmpty()) {
                        Text("Пока нет событий.", color = Color(0xFF777777), fontSize = 12.sp)
                    } else {
                        events.asReversed().take(20).forEach { event ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF2F2F2), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                            ) {
                                Text("${event.kind} · ${event.title}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(event.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Закрыть") }
            },
        )
    }

    if (confirmNewPet) {
        AlertDialog(
            onDismissRequest = { confirmNewPet = false },
            title = { Text("Создать нового персонажа?") },
            text = { Text("Текущий персонаж останется сохранённым, пока явно не удалён.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewPet = false
                    open = false
                    bindings.onCreateNewPet()
                }) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNewPet = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun DebugAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF222222),
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(10.dp),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun DebugSnapshot(bindings: DebugMenuBindings) {
    val pet = bindings.pet
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF2F2F2), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text("Персонаж", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            if (pet == null) "Нет активного персонажа" else buildString {
                appendLine("${pet.name} · ${pet.description}")
                appendLine("petId: ${pet.petId}")
                appendLine("XP ${pet.experience} · еда ${pet.hunger} · радость ${pet.happiness} · энергия ${pet.energy}")
                appendLine("onboarding: ${bindings.firstSession?.stage?.storageValue ?: "нет"}")
                appendLine("генератор: OpenAI · Kandinsky недоступен")
                appendLine("assetSet: ${pet.assetSetId}")
                appendLine("generatedAt: ${pet.generatedMedia.generatedAt ?: "—"}")
                appendLine("video: ${pet.generatedMedia.videoUrl ?: "—"}")
                append("character bible: ${pet.generatedMedia.characterBibleJson?.length ?: 0} символов")
            },
            modifier = Modifier.padding(top = 6.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}
