package com.gigagochi.app.feature.create

import kotlinx.coroutines.delay

interface PetGenerationAdapter {
    suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture
}

class FakePetGenerationAdapter(
    private val delayMillis: Long = 6_000L,
) : PetGenerationAdapter {
    override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
        delay(delayMillis)
        return GeneratedPetFixture(description = request.description)
    }
}

class ProductionPetGenerationUnavailableAdapter : PetGenerationAdapter {
    override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
        throw IllegalStateException("Real pet generation API is not connected")
    }
}
