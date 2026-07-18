package com.gigagochi.app.feature.dashboard

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

internal const val PetTapParticleIntervalMillis = 80L
internal const val PetTapParticleCount = 9
internal const val PetTapBulgeDurationMillis = 180
internal const val PetTapBulgeAttackMillis = 45
internal const val PetTapBulgeHoldMillis = 80
internal const val PetTapParticleLifetimeMillis = 1_889
internal const val PetTapBulgeStrength = .18f
internal const val PetTapReducedBulgeStrength = .08f
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
)

internal object PetTapThanksSession {
    private val claimedPetIds = mutableSetOf<String>()

    fun claim(petId: String): Boolean = synchronized(claimedPetIds) {
        claimedPetIds.add(petId)
    }
}

private const val PetTapBulgeShader = """
    uniform shader content;
    uniform float2 resolution;
    uniform float2 center;
    uniform float radius;
    uniform float strength;

    half4 main(float2 fragCoord) {
        float2 offset = fragCoord - center;
        float normalizedDistance = clamp(length(offset) / resolution.y / radius, 0.0, 1.0);
        float falloff = 1.0 - smoothstep(0.0, 1.0, normalizedDistance);
        float sampleScale = 1.0 - strength * falloff * falloff;
        float2 distortedCoord = clamp(
            center + offset * sampleScale,
            float2(0.0, 0.0),
            resolution
        );
        float softCircle = 1.0 - smoothstep(0.78, 1.0, normalizedDistance);
        return mix(content.eval(fragCoord), content.eval(distortedCoord), softCircle);
    }
"""

@Composable
internal fun Modifier.petTapBulge(
    reaction: PetTapReaction?,
    reducedMotion: Boolean,
): Modifier {
    val strength = remember { Animatable(0f) }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val runtimeShader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { RuntimeShader(PetTapBulgeShader) }
    } else {
        null
    }
    val renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader != null) {
        remember(runtimeShader) {
            android.graphics.RenderEffect
                .createRuntimeShaderEffect(runtimeShader, "content")
                .asComposeRenderEffect()
        }
    } else {
        null
    }

    LaunchedEffect(reaction?.id, reducedMotion) {
        if (reaction == null) {
            strength.snapTo(0f)
            return@LaunchedEffect
        }
        val peak = if (reducedMotion) PetTapReducedBulgeStrength else PetTapBulgeStrength
        val duration = if (reducedMotion) 100 else PetTapBulgeDurationMillis
        val hold = if (reducedMotion) PetTapBulgeAttackMillis else PetTapBulgeHoldMillis
        strength.snapTo(0f)
        strength.animateTo(
            peak,
            tween(PetTapBulgeAttackMillis, easing = LinearEasing),
        )
        strength.animateTo(
            peak,
            tween((hold - PetTapBulgeAttackMillis).coerceAtLeast(0), easing = LinearEasing),
        )
        strength.animateTo(
            0f,
            tween((duration - hold).coerceAtLeast(1), easing = LinearEasing),
        )
    }

    val measuredModifier = onSizeChanged { measuredSize = it }
    val activeStrength = strength.value
    if (activeStrength <= .0001f) return measuredModifier

    return measuredModifier.graphicsLayer {
            val center = reaction?.center ?: Offset(
                measuredSize.width / 2f,
                measuredSize.height / 2f,
            )
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runtimeShader != null &&
                renderEffect != null &&
                measuredSize.width > 0 &&
                measuredSize.height > 0
            ) {
                runtimeShader.setFloatUniform(
                    "resolution",
                    measuredSize.width.toFloat(),
                    measuredSize.height.toFloat(),
                )
                runtimeShader.setFloatUniform("center", center.x, center.y)
                runtimeShader.setFloatUniform("radius", .27f)
                runtimeShader.setFloatUniform("strength", activeStrength)
                this.renderEffect = renderEffect
            } else if (measuredSize.width > 0 && measuredSize.height > 0) {
                transformOrigin = TransformOrigin(
                    (center.x / measuredSize.width).coerceIn(0f, 1f),
                    (center.y / measuredSize.height).coerceIn(0f, 1f),
                )
                val fallbackScale = 1f + activeStrength * .09f
                scaleX = fallbackScale
                scaleY = fallbackScale
            }
        }
}

@Composable
internal fun PetTapHeartBurst(
    burst: PetTapHeartBurst,
    onFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember(burst.id) { Animatable(0f) }
    LaunchedEffect(burst.id) {
        progress.animateTo(
            1f,
            tween(PetTapParticleLifetimeMillis, easing = LinearEasing),
        )
        onFinished(burst.id)
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
                alpha = colors[particle.colorIndex].alpha * opacity,
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
