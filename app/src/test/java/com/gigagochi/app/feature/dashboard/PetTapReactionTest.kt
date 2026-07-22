package com.gigagochi.app.feature.dashboard

import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTapReactionTest {
    @Test
    fun tapTargetMatchesWebMediaRegionInReferenceFrame() {
        val target = petTapTargetRect()

        assertEquals(37.125f, target.left, .0001f)
        assertEquals(122.90625f, target.top, .0001f)
        assertEquals(364.875f, target.right, .0001f)
        assertEquals(573.5625f, target.bottom, .0001f)
        assertEquals(327.75f, target.width, .0001f)
        assertEquals(450.65625f, target.height, .0001f)
    }

    @Test
    fun shaderCenterUsesVideoViewportInsteadOfPhysicalDisplay() {
        val center = normalizedPetTapShaderCenter(
            centerX = DashboardReferenceSize.width * 3f * .25f,
            centerY = DashboardReferenceSize.height * 3f * .75f,
            width = DashboardReferenceSize.width * 3f,
            height = DashboardReferenceSize.height * 3f,
        )

        requireNotNull(center)
        assertEquals(.25f, center.x, .0001f)
        assertEquals(.25f, center.y, .0001f)
        assertEquals(
            DashboardReferenceSize.width / DashboardReferenceSize.height,
            DashboardReferenceAspectRatio,
            .0001f,
        )
    }

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
    fun rapidTapsKeepTwoBurstsAliveAndFadeTheOldest() {
        val first = PetTapHeartBurst(1, Offset(10f, 20f))
        val second = PetTapHeartBurst(2, Offset(20f, 30f))
        val third = PetTapHeartBurst(3, Offset(30f, 40f))

        val twoActive = appendPetTapHeartBurst(
            appendPetTapHeartBurst(emptyList(), first),
            second,
        )
        assertEquals(listOf(first, second), twoActive)

        val overflow = appendPetTapHeartBurst(twoActive, third)
        assertEquals(listOf(1, 2, 3), overflow.map(PetTapHeartBurst::id))
        assertTrue(overflow.first().isExiting)
        assertEquals(2, overflow.count { !it.isExiting })
        assertEquals(120, PetTapParticleFadeMillis)
    }

    @Test
    fun anotherRapidTapDropsOnlyTheAlreadyFadedOverflowBurst() {
        val current = listOf(
            PetTapHeartBurst(1, Offset.Zero, isExiting = true),
            PetTapHeartBurst(2, Offset.Zero),
            PetTapHeartBurst(3, Offset.Zero),
        )

        val next = appendPetTapHeartBurst(current, PetTapHeartBurst(4, Offset.Zero))

        assertEquals(listOf(2, 3, 4), next.map(PetTapHeartBurst::id))
        assertTrue(next.first().isExiting)
        assertEquals(2, next.count { !it.isExiting })
    }

    @Test
    fun bulgeTimingUsesSmoothedAttackHoldAndRelease() {
        assertEquals(0f, petTapBulgeStrength(0f, reducedMotion = false))
        assertEquals(.09f, petTapBulgeStrength(60f, reducedMotion = false), .0001f)
        assertEquals(.18f, petTapBulgeStrength(120f, reducedMotion = false))
        assertEquals(.18f, petTapBulgeStrength(170f, reducedMotion = false))
        assertEquals(.09f, petTapBulgeStrength(210f, reducedMotion = false), .0001f)
        assertEquals(0f, petTapBulgeStrength(250f, reducedMotion = false))
        assertEquals(.04f, petTapBulgeStrength(17.5f, reducedMotion = true), .0001f)
        assertEquals(.08f, petTapBulgeStrength(35f, reducedMotion = true))
        assertEquals(.08f, petTapBulgeStrength(60f, reducedMotion = true))
        assertEquals(.04f, petTapBulgeStrength(80f, reducedMotion = true), .0001f)
        assertEquals(0f, petTapBulgeStrength(100f, reducedMotion = true))
    }
}
