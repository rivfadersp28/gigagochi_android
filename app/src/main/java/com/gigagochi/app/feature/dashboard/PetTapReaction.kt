package com.gigagochi.app.feature.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

internal const val PetTapParticleIntervalMillis = 80L
internal const val MaxActivePetTapHeartBursts = 2
internal const val PetTapParticleFadeMillis = 120
internal const val PetTapParticleCount = 9
internal const val PetTapBulgeDurationMillis = 250
internal const val PetTapBulgeAttackMillis = 120
internal const val PetTapBulgeHoldMillis = 170
internal const val PetTapReducedBulgeDurationMillis = 100
internal const val PetTapReducedBulgeAttackMillis = 35
internal const val PetTapReducedBulgeHoldMillis = 60
internal const val PetTapParticleLifetimeMillis = 1_889
internal const val PetTapBulgeStrength = .18f
internal const val PetTapReducedBulgeStrength = .08f
internal const val PetTapBulgeRadius = .27f
internal const val PetTapParticleLifetimeFrames = 136f
internal const val PetTapParticleLifeDecayPerFrame = 1.2f
internal const val PetTapParticleFriction = .99f
internal const val PetTapParticleGravity = -.04f

internal data class PetTapHeartParticleSpec(
    val startOffsetX: Float,
    val velocityX: Float,
    val velocityY: Float,
    val size: Float,
    val rotation: Float,
    val colorIndex: Int,
)

internal fun petTapHeartParticles(burstId: Int): List<PetTapHeartParticleSpec> {
    val random = Random(burstId.toLong() * 7_919L + 17L)
    return List(PetTapParticleCount) {
        val angle = (-45f - random.nextFloat() * 90f) / 180f * PI.toFloat()
        val speed = 11.2f + random.nextFloat() * 9.6f
        PetTapHeartParticleSpec(
            startOffsetX = -10f + random.nextFloat() * 20f,
            velocityX = cos(angle) * speed,
            velocityY = sin(angle) * speed,
            size = 19.8f + random.nextFloat() * 19.8f,
            rotation = -20f + random.nextFloat() * 40f,
            colorIndex = random.nextInt(4),
        )
    }
}

internal data class PetTapReaction(
    val id: Int,
    val center: Offset,
)

internal data class PetTapHeartBurst(
    val id: Int,
    val center: Offset,
    val isExiting: Boolean = false,
)

internal fun appendPetTapHeartBurst(
    current: List<PetTapHeartBurst>,
    next: PetTapHeartBurst,
): List<PetTapHeartBurst> {
    val activeBursts = current.filterNot(PetTapHeartBurst::isExiting)
    if (activeBursts.size < MaxActivePetTapHeartBursts) return current + next

    val oldestActiveId = activeBursts.first().id
    val retainedBursts = current
        .filterNot(PetTapHeartBurst::isExiting)
        .map { burst ->
            if (burst.id == oldestActiveId) burst.copy(isExiting = true) else burst
        }
    return retainedBursts + next
}

internal object PetTapThanksSession {
    private val claimedPetIds = mutableSetOf<String>()

    fun claim(petId: String): Boolean = synchronized(claimedPetIds) {
        claimedPetIds.add(petId)
    }
}

internal fun petTapBulgeStrength(elapsedMillis: Float, reducedMotion: Boolean): Float {
    val duration = if (reducedMotion) {
        PetTapReducedBulgeDurationMillis.toFloat()
    } else {
        PetTapBulgeDurationMillis.toFloat()
    }
    if (elapsedMillis < 0f || elapsedMillis >= duration) return 0f
    val peak = if (reducedMotion) PetTapReducedBulgeStrength else PetTapBulgeStrength
    val attackMillis = if (reducedMotion) {
        PetTapReducedBulgeAttackMillis
    } else {
        PetTapBulgeAttackMillis
    }
    val holdMillis = if (reducedMotion) {
        PetTapReducedBulgeHoldMillis
    } else {
        PetTapBulgeHoldMillis
    }
    val attack = smoothStep(elapsedMillis / attackMillis)
    val release = if (elapsedMillis <= holdMillis) {
        1f
    } else {
        smoothStep(1f - (elapsedMillis - holdMillis) / (duration - holdMillis))
    }
    return peak * minOf(attack, release)
}

private fun smoothStep(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

@Composable
internal fun PetTapHeartBurst(
    burst: PetTapHeartBurst,
    onFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(burst.id) { Animatable(0f) }
    val exitAlpha = remember(burst.id) { Animatable(1f) }
    val latestOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(burst.id) {
        progress.animateTo(
            1f,
            tween(PetTapParticleLifetimeMillis, easing = LinearEasing),
        )
        latestOnFinished(burst.id)
    }
    LaunchedEffect(burst.id, burst.isExiting) {
        if (!burst.isExiting) return@LaunchedEffect
        exitAlpha.animateTo(
            0f,
            tween(
                durationMillis = PetTapParticleFadeMillis,
                easing = CubicBezierEasing(.16f, 1f, .3f, 1f),
            ),
        )
        latestOnFinished(burst.id)
    }
    val colors = remember {
        listOf(
            Color(0xE0FF1744),
            Color(0xD1E91E63),
            Color(0xC7FF4569),
            Color(0xBDFF80AB),
        )
    }
    val particles = remember(burst.id) { petTapHeartParticles(burst.id) }

    Canvas(modifier.fillMaxSize()) {
        val fraction = progress.value
        val totalFrames = PetTapParticleLifetimeFrames / PetTapParticleLifeDecayPerFrame
        val elapsedFrames = floor(fraction * totalFrames).toInt()
        particles.forEach { particle ->
            var x = burst.center.x + particle.startOffsetX.dp.toPx()
            var y = burst.center.y
            var velocityX = particle.velocityX.dp.toPx()
            var velocityY = particle.velocityY.dp.toPx()
            val gravity = PetTapParticleGravity.dp.toPx()
            repeat(elapsedFrames) {
                x += velocityX
                y += velocityY
                velocityY += gravity
                velocityX *= PetTapParticleFriction
                velocityY *= PetTapParticleFriction
            }
            val remainingLife = (
                PetTapParticleLifetimeFrames - elapsedFrames * PetTapParticleLifeDecayPerFrame
                ).coerceAtLeast(0f)
            val opacity = (remainingLife / 100f).coerceIn(0f, 1f)
            val pulse = 1f + sin(remainingLife * .2f) * .1f
            val center = Offset(
                x = x,
                y = y,
            )
            val color = colors[particle.colorIndex].copy(
                alpha = colors[particle.colorIndex].alpha * opacity * exitAlpha.value,
            )
            rotate(particle.rotation, center) {
                drawHeart(
                    center = center,
                    size = particle.size.dp.toPx() * pulse * 1.1f,
                    color = color.copy(alpha = color.alpha * .2f),
                )
                drawHeart(
                    center = center,
                    size = particle.size.dp.toPx() * pulse,
                    color = color,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHeart(
    center: Offset,
    size: Float,
    color: Color,
) {
    val half = size / 2f
    val path = Path().apply {
        moveTo(center.x, center.y + half * .78f)
        cubicTo(
            center.x - half * 1.12f,
            center.y + half * .12f,
            center.x - half * .92f,
            center.y - half * .74f,
            center.x - half * .38f,
            center.y - half * .74f,
        )
        cubicTo(
            center.x - half * .08f,
            center.y - half * .74f,
            center.x,
            center.y - half * .48f,
            center.x,
            center.y - half * .31f,
        )
        cubicTo(
            center.x,
            center.y - half * .48f,
            center.x + half * .08f,
            center.y - half * .74f,
            center.x + half * .38f,
            center.y - half * .74f,
        )
        cubicTo(
            center.x + half * .92f,
            center.y - half * .74f,
            center.x + half * 1.12f,
            center.y + half * .12f,
            center.x,
            center.y + half * .78f,
        )
        close()
    }
    drawPath(path, color)
}
