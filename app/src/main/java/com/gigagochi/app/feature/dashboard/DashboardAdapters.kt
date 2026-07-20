package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.delay

interface DashboardChatAdapter {
    suspend fun reply(request: PendingChatRequest, pet: PetDashboardState): DashboardChatResult
}

data class DashboardChatResult(
    val reply: String,
    val pet: PetDashboardState,
)

interface DashboardFeedAdapter {
    suspend fun reply(request: PendingFeedRequest, pet: PetDashboardState): String
}

interface DashboardOutfitAdapter {
    val isAvailable: Boolean get() = true
    suspend fun queue(request: PendingOutfitRequest, pet: PetDashboardState): PendingOutfitGeneration
}

interface DashboardTravelAdapter {
    val isAvailable: Boolean get() = true
    suspend fun queue(request: PendingTravelRequest, pet: PetDashboardState): PendingTravelGeneration
}

class UnavailableDashboardChatAdapter : DashboardChatAdapter {
    override suspend fun reply(request: PendingChatRequest, pet: PetDashboardState): DashboardChatResult =
        error("Real chat API is not connected")
}

class UnavailableDashboardFeedAdapter : DashboardFeedAdapter {
    override suspend fun reply(request: PendingFeedRequest, pet: PetDashboardState): String =
        error("Real feed API is not connected")
}

class UnavailableDashboardOutfitAdapter : DashboardOutfitAdapter {
    override val isAvailable: Boolean = false
    override suspend fun queue(
        request: PendingOutfitRequest,
        pet: PetDashboardState,
    ): PendingOutfitGeneration = error("Real outfit API is not connected")
}

class UnavailableDashboardTravelAdapter : DashboardTravelAdapter {
    override val isAvailable: Boolean = false
    override suspend fun queue(
        request: PendingTravelRequest,
        pet: PetDashboardState,
    ): PendingTravelGeneration = error("Real travel API is not connected")
}

class FakeDashboardChatAdapter(
    private val adapterDelayMillis: Long = 180L,
) : DashboardChatAdapter {
    override suspend fun reply(request: PendingChatRequest, pet: PetDashboardState): DashboardChatResult {
        delay(adapterDelayMillis)
        return DashboardChatResult(DeterministicChatReply, pet)
    }
}

class FakeDashboardFeedAdapter(
    private val adapterDelayMillis: Long = 180L,
) : DashboardFeedAdapter {
    override suspend fun reply(request: PendingFeedRequest, pet: PetDashboardState): String {
        delay(adapterDelayMillis)
        return when (request.food) {
            DashboardFood.BerryBowl -> BerryReply
            DashboardFood.LeafCrunch -> LeafReply
        }
    }
}

class FakeDashboardOutfitAdapter(
    private val adapterDelayMillis: Long = 240L,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : DashboardOutfitAdapter {
    override suspend fun queue(
        request: PendingOutfitRequest,
        pet: PetDashboardState,
    ): PendingOutfitGeneration {
        delay(adapterDelayMillis)
        return PendingOutfitGeneration(
            petId = pet.petId,
            requestKey = request.requestKey,
            prompt = request.prompt,
            displayItem = canonicalOutfitDisplayItem(request.prompt),
            localJobId = "local-fake-${request.requestKey}",
            createdAtEpochMillis = nowEpochMillis(),
        )
    }
}

class FakeDashboardTravelAdapter(
    private val adapterDelayMillis: Long = 240L,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : DashboardTravelAdapter {
    private val pendingByRequest = mutableMapOf<String, PendingTravelGeneration>()

    override suspend fun queue(
        request: PendingTravelRequest,
        pet: PetDashboardState,
    ): PendingTravelGeneration {
        val cacheKey = "${pet.petId}:${request.requestKey}"
        synchronized(pendingByRequest) {
            pendingByRequest[cacheKey]?.let { return it }
        }
        delay(adapterDelayMillis)
        return synchronized(pendingByRequest) {
            pendingByRequest.getOrPut(cacheKey) {
                PendingTravelGeneration(
                    petId = pet.petId,
                    requestKey = request.requestKey,
                    prompt = request.prompt,
                    localJobId = "local-fake-travel-${request.requestKey}",
                    createdAtEpochMillis = nowEpochMillis(),
                )
            }
        }
    }
}

fun canonicalOutfitDisplayItem(prompt: String): String {
    val normalized = prompt.trim()
    if (normalized.equals("В футболку Metallica", ignoreCase = true)) {
        return "футболка Metallica"
    }
    val withoutPrefix = normalized.removePrefix("В ").removePrefix("в ")
    return withoutPrefix.replaceFirstChar { it.lowercase() }
}

fun outfitQueuedReply(displayItem: String): String {
    val normalized = displayItem.trim()
    val titled = normalized.replaceFirstChar { it.titlecase() }.ifBlank { "Наряд" }
    return "$titled? Интересно. Я получу заказ примерно через 10 минут."
}

fun travelQueuedReply(destination: String): String {
    val normalized = destination.trim().trimEnd('.', '!', '?', '…')
    return "$normalized? Надеюсь, со мной всё будет в порядке. Пришлю видео, когда вернусь."
}
