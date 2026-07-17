package com.gigagochi.app.feature.create

data class CreatePendingRevision(
    val petId: String,
    val requestKey: String,
    val currentStep: Int,
    val description: String,
    val name: String?,
    val personality: String?,
    val fear: String?,
    val favoriteItem: String?,
    val requestedStage: Boolean,
)

fun CreatePetState.pendingRevision(): CreatePendingRevision? {
    val request = pending ?: return null
    return CreatePendingRevision(
        petId = request.petId,
        requestKey = request.requestKey,
        currentStep = step,
        description = description,
        name = answers.getOrNull(1),
        personality = answers.getOrNull(2),
        fear = answers.getOrNull(3),
        favoriteItem = answers.getOrNull(4),
        requestedStage = generation is GenerationStatus.Idle,
    )
}

class CreatePendingPersistenceGate {
    class Attempt internal constructor(
        internal val sequence: Long,
        val revision: CreatePendingRevision,
    )

    private var sequence = 0L
    private var latestSequence = 0L

    fun begin(revision: CreatePendingRevision): Attempt = Attempt(
        sequence = ++sequence,
        revision = revision,
    ).also { latestSequence = it.sequence }

    fun completeIfLatest(attempt: Attempt): CreatePendingRevision? =
        attempt.revision.takeIf { attempt.sequence == latestSequence }

    fun isLatest(attempt: Attempt): Boolean = attempt.sequence == latestSequence
}

suspend fun executePetGenerationIfCurrentRevisionPersisted(
    adapter: PetGenerationAdapter,
    state: CreatePetState,
    persistedRevision: CreatePendingRevision?,
): PetGenerationExecutionResult? {
    val request = state.pending ?: return null
    if (
        state.generationAttempt == 0 ||
        state.generation !is GenerationStatus.Running ||
        state.pendingRevision() != persistedRevision
    ) {
        return null
    }
    return executePetGeneration(adapter, request)
}
