package com.gigagochi.app.feature.create

import kotlinx.coroutines.CancellationException

sealed interface PetGenerationExecutionResult {
    data class Success(val pet: GeneratedPetFixture) : PetGenerationExecutionResult
    data class Failure(val newRequestRequired: Boolean) : PetGenerationExecutionResult
}

suspend fun executePetGeneration(
    adapter: PetGenerationAdapter,
    request: PendingPetGeneration,
): PetGenerationExecutionResult = try {
    PetGenerationExecutionResult.Success(adapter.generate(request))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: FeatureAdapterException) {
    PetGenerationExecutionResult.Failure(
        newRequestRequired = failure.failure.code == "GENERATION_FAILED",
    )
} catch (_: Exception) {
    PetGenerationExecutionResult.Failure(newRequestRequired = false)
}
