package com.gigagochi.app.feature.create

import kotlinx.coroutines.CancellationException

sealed interface PetGenerationExecutionResult {
    data class Success(val pet: GeneratedPetFixture) : PetGenerationExecutionResult
    data object Failure : PetGenerationExecutionResult
}

suspend fun executePetGeneration(
    adapter: PetGenerationAdapter,
    request: PendingPetGeneration,
): PetGenerationExecutionResult = try {
    PetGenerationExecutionResult.Success(adapter.generate(request))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    PetGenerationExecutionResult.Failure
}
