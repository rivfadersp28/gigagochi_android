package com.gigagochi.app.feature.dashboard

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTapReactionTest {
    @Test
    fun nativeParticlesMatchMobileWebTimingAndBounds() {
        val particles = petTapHeartParticles(burstId = 7)

        assertEquals(9, particles.size)
        assertEquals(
            (PetTapParticleLifetimeFrames / PetTapParticleLifeDecayPerFrame / 60f * 1_000f)
                .roundToInt(),
            PetTapParticleLifetimeMillis,
        )
        assertTrue(particles.all { it.startOffsetX in -10f..10f })
        assertTrue(particles.all { it.size in 19.8f..39.6f })
        assertTrue(particles.all { it.rotation in -20f..20f })
        assertTrue(particles.all { it.colorIndex in 0..3 })
    }

    @Test
    fun burstSeedIsStableButDifferentAcrossTaps() {
        assertEquals(petTapHeartParticles(3), petTapHeartParticles(3))
        assertNotEquals(petTapHeartParticles(3), petTapHeartParticles(4))
    }

    @Test
    fun bulgeTimingUsesSmoothedAttackHoldAndRelease() {
        assertEquals(0f, petTapBulgeStrength(0f, reducedMotion = false))
        assertEquals(.09f, petTapBulgeStrength(60f, reducedMotion = false), .0001f)
        assertEquals(.18f, petTapBulgeStrength(120f, reducedMotion = false))
        assertEquals(.18f, petTapBulgeStrength(170f, reducedMotion = false))
        assertEquals(.09f, petTapBulgeStrength(265f, reducedMotion = false), .0001f)
        assertEquals(0f, petTapBulgeStrength(360f, reducedMotion = false))
        assertEquals(.04f, petTapBulgeStrength(17.5f, reducedMotion = true), .0001f)
        assertEquals(.08f, petTapBulgeStrength(35f, reducedMotion = true))
        assertEquals(.08f, petTapBulgeStrength(60f, reducedMotion = true))
        assertEquals(.04f, petTapBulgeStrength(80f, reducedMotion = true), .0001f)
        assertEquals(0f, petTapBulgeStrength(100f, reducedMotion = true))
    }
}
