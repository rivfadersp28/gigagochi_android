package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.database.PetLocalRepository
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.network.AndroidFeatureService
import com.gigagochi.app.core.network.FeatureApiResult
import com.gigagochi.app.core.network.ProactiveRequestDto
import com.gigagochi.app.core.network.toFeaturePetDto
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DailyProactiveCoordinator(
    private val ownerId: String,
    private val repository: PetLocalRepository,
    private val api: AndroidFeatureService,
) {
    suspend fun generateIfDue(
        pet: PetDashboardState,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val memory = repository.memoryState(ownerId, pet.petId)
        val history = repository.recentChatMessages(ownerId, pet.petId, 200)
        val characterExperiences = repository.recentCharacterExperiences(ownerId, pet.petId)
        val context = buildDailyProactiveContext(
            memory,
            history,
            nowEpochMillis,
            characterExperiences = characterExperiences,
        ) ?: return false
        return when (
            val response = api.proactive(
                ProactiveRequestDto(
                    requestKey = UUID.randomUUID().toString(),
                    pet = pet.toFeaturePetDto(),
                    memoryContext = context,
                    nowIso = Instant.ofEpochMilli(nowEpochMillis).toString(),
                    timezone = ZoneId.systemDefault().id,
                ),
            )
        ) {
            is FeatureApiResult.Failure -> false
            is FeatureApiResult.Success -> {
                val reply = sanitizeProactiveReply(response.value.reply, memory, nowEpochMillis)
                if (reply.isBlank()) return false
                repository.saveProactiveNotification(
                    ownerId,
                    pet.petId,
                    reply,
                    context.proactiveCandidate?.memoryIds.orEmpty().toSet(),
                    nowEpochMillis,
                )
            }
        }
    }
}
