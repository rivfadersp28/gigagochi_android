package com.gigagochi.app.feature.create

import com.gigagochi.app.core.network.FeatureFailure
import com.gigagochi.app.core.network.FeatureFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PetGenerationExecutionTest {
    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToGenerationFailure() {
        runBlocking {
            executePetGeneration(
                adapter = object : PetGenerationAdapter {
                    override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
                        throw CancellationException("route closed")
                    }
                },
                request = request(),
            )
        }
    }

    @Test
    fun ordinaryAdapterFailureIsTyped() = runBlocking {
        val result = executePetGeneration(
            adapter = object : PetGenerationAdapter {
                override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
                    error("provider unavailable")
                }
            },
            request = request(),
        )

        assertEquals(PetGenerationExecutionResult.Failure(false), result)
    }

    @Test
    fun terminalProviderFailureRequiresFreshRequestIdentity() = runBlocking {
        val result = executePetGeneration(
            adapter = object : PetGenerationAdapter {
                override suspend fun generate(request: PendingPetGeneration): GeneratedPetFixture {
                    throw FeatureAdapterException(
                        FeatureFailure(FeatureFailureKind.Server, "GENERATION_FAILED"),
                    )
                }
            },
            request = request(),
        )

        assertEquals(PetGenerationExecutionResult.Failure(true), result)
    }

    private fun request() = PendingPetGeneration(
        petId = "pet-1",
        description = "Ледяной дракон",
        requestKey = "request-1",
    )
}
