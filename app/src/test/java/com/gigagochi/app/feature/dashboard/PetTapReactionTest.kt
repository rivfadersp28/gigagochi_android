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
}
