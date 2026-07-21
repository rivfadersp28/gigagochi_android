package com.gigagochi.app.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class PetStatDecayTest {
    @Test
    fun statsFallFromFullToZeroOverTwentyFourHours() {
        val start = 1_000_000L
        val halfway = entity(
            hungerTick = start,
            happinessTick = start,
            energyTick = start,
        ).withDecayedStats(start + PetStatFullDecayMillis / 2)

        assertEquals(50, halfway.hunger)
        assertEquals(50, halfway.happiness)
        assertEquals(50, halfway.energy)

        val finished = halfway.withDecayedStats(start + PetStatFullDecayMillis)
        assertEquals(0, finished.hunger)
        assertEquals(0, finished.happiness)
        assertEquals(0, finished.energy)
        assertEquals("hungry", finished.mood)
    }

    @Test
    fun eachStatKeepsItsOwnClockAndSubPointRemainder() {
        val start = 2_000_000L
        val first = entity(
            hungerTick = start,
            happinessTick = start + 2 * 60 * 60 * 1_000L,
            energyTick = start + 4 * 60 * 60 * 1_000L,
        ).withDecayedStats(start + 60 * 60 * 1_000L)

        assertEquals(96, first.hunger)
        assertEquals(100, first.happiness)
        assertEquals(100, first.energy)

        val beforeNextPoint = first.withDecayedStats(
            first.hungerTickAtEpochMillis + PetStatFullDecayMillis / 100 - 1,
        )
        assertEquals(96, beforeNextPoint.hunger)
        val nextPoint = beforeNextPoint.withDecayedStats(
            first.hungerTickAtEpochMillis + PetStatFullDecayMillis / 100,
        )
        assertEquals(95, nextPoint.hunger)
    }

    private fun entity(
        hungerTick: Long,
        happinessTick: Long,
        energyTick: Long,
    ) = PetSnapshotEntity(
        ownerId = "owner",
        petId = "pet",
        assetSetId = "asset",
        description = "description",
        name = "Тото",
        stage = "baby",
        stageLabel = "Малыш",
        mood = "happy",
        experience = 0,
        hunger = 100,
        happiness = 100,
        energy = 100,
        message = "Привет",
        petTapProgress = 0,
        hungerTickAtEpochMillis = hungerTick,
        happinessTickAtEpochMillis = happinessTick,
        energyTickAtEpochMillis = energyTick,
        updatedAtEpochMillis = hungerTick,
    )
}
