@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.gigagochi.app.feature.dashboard

import android.content.Context
import android.opengl.GLES20
import androidx.compose.ui.geometry.Offset
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

internal class PetTapVideoEffect : GlEffect {
    @Volatile
    private var uniforms = Uniforms()

    fun update(centerX: Float, centerY: Float, width: Float, height: Float, strength: Float) {
        val center = normalizedPetTapShaderCenter(centerX, centerY, width, height) ?: return
        uniforms = Uniforms(
            centerX = center.x,
            centerY = center.y,
            strength = strength.coerceIn(0f, PetTapBulgeStrength),
        )
    }

    fun clear() {
        uniforms = uniforms.copy(strength = 0f)
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = ShaderProgram(uniforms = { uniforms }, useHdr = useHdr)

    private data class Uniforms(
        val centerX: Float = .5f,
        val centerY: Float = .5f,
        val strength: Float = 0f,
    )

    private class ShaderProgram(
        private val uniforms: () -> Uniforms,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {
        private val program = try {
            GlProgram(VertexShader, FragmentShader).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
        } catch (error: Exception) {
            throw VideoFrameProcessingException.from(error)
        }
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val state = uniforms()
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uCenter", floatArrayOf(state.centerX, state.centerY))
                program.setFloatUniform("uAspect", width.toFloat() / height.toFloat())
                program.setFloatUniform("uRadius", PetTapBulgeRadius)
                program.setFloatUniform("uStrength", state.strength)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (error: Exception) {
                throw VideoFrameProcessingException.from(error)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (error: Exception) {
                throw VideoFrameProcessingException.from(error)
            }
        }
    }
}

internal fun normalizedPetTapShaderCenter(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
): Offset? {
    if (width <= 0f || height <= 0f) return null
    return Offset(
        x = (centerX / width).coerceIn(0f, 1f),
        y = (1f - centerY / height).coerceIn(0f, 1f),
    )
}

private const val VertexShader = """
    #version 100
    attribute vec4 aFramePosition;
    varying vec2 vUv;

    void main() {
        gl_Position = aFramePosition;
        vUv = aFramePosition.xy * 0.5 + 0.5;
    }
"""

private const val FragmentShader = """
    #version 100
    precision highp float;
    uniform sampler2D uTexSampler;
    uniform vec2 uCenter;
    uniform float uAspect;
    uniform float uRadius;
    uniform float uStrength;
    varying vec2 vUv;

    void main() {
        vec2 offset = vUv - uCenter;
        vec2 circularOffset = vec2(offset.x * uAspect, offset.y);
        float distanceFromCenter = length(circularOffset);
        float normalizedDistance = clamp(distanceFromCenter / uRadius, 0.0, 1.0);
        float falloff = 1.0 - smoothstep(0.0, 1.0, normalizedDistance);
        float sampleScale = 1.0 - uStrength * falloff * falloff;
        vec2 distortedUv = clamp(uCenter + offset * sampleScale, 0.0, 1.0);
        float softCircle = 1.0 - smoothstep(uRadius * 0.78, uRadius, distanceFromCenter);
        vec4 source = texture2D(uTexSampler, vUv);
        vec4 distorted = texture2D(uTexSampler, distortedUv);
        gl_FragColor = mix(source, distorted, softCircle);
    }
"""
