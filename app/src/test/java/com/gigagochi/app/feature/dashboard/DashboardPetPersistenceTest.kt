package com.gigagochi.app.feature.dashboard

import com.gigagochi.app.core.model.PetDashboardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPetPersistenceTest {
    @Test
    fun firstSaveFailureRetriesSameSnapshotAndSecondSucceeds() = runBlocking {
        val savedPets = mutableListOf<PetDashboardState>()
        val delays = mutableListOf<Long>()
        val coordinator = DashboardPetPersistenceCoordinator(
            save = { pet ->
                savedPets += pet
                savedPets.size == 2
            },
            retryDelay = { delays += it },
        )

        assertTrue(coordinator.persist(pet()))
        assertEquals(2, savedPets.size)
        assertEquals(savedPets[0], savedPets[1])
        assertEquals(listOf(DashboardSaveRetryDelayMillis), delays)
    }

    @Test
    fun repeatedFailureStopsAfterBoundedAttempts() = runBlocking {
        var calls = 0
        val coordinator = DashboardPetPersistenceCoordinator(
            save = {
                calls += 1
                false
            },
            retryDelay = {},
        )

        assertFalse(coordinator.persist(pet()))
        assertEquals(DashboardSaveMaxAttempts, calls)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedToSaveFailure() {
        runBlocking {
            DashboardPetPersistenceCoordinator(
                save = { throw CancellationException("route closed") },
                retryDelay = {},
            ).persist(pet())
        }
    }

    private fun pet() = PetDashboardState(
        petId = "pet-1",
        assetSetId = "assets-1",
        description = "Ледяной дракон",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "idle",
        experience = 0,
        hunger = 75,
        happiness = 100,
        energy = 100,
        message = "Привет",
    )
}
