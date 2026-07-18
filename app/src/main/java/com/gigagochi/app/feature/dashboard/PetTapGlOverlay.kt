package com.gigagochi.app.feature.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Transparent GLES2 layer that repeats the Mini App WebGL pet-tap shader over a live video frame.
 */
internal class PetTapGlOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {
    private val renderThread = HandlerThread("pet-tap-gl").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val frameLock = Any()

    private var glState: GlState? = null
    private var stagingBitmap: Bitmap? = null
    private var framePending = false
    private var released = false

    init {
        isOpaque = false
        isClickable = false
        isFocusable = false
        surfaceTextureListener = this
    }

    fun captureAndRender(
        source: TextureView,
        centerX: Float,
        centerY: Float,
        strength: Float,
    ): Boolean = synchronized(frameLock) {
        if (
            released || framePending || !source.isAvailable ||
            source.width <= 0 || source.height <= 0
        ) {
            return@synchronized false
        }
        val bitmap = stagingBitmap
            ?.takeIf { it.width == source.width && it.height == source.height && !it.isRecycled }
            ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                stagingBitmap?.recycle()
                stagingBitmap = it
            }
        source.getBitmap(bitmap)
        framePending = true
        val normalizedX = (centerX / source.width).coerceIn(0f, 1f)
        val normalizedY = (centerY / source.height).coerceIn(0f, 1f)
        renderHandler.post {
            synchronized(frameLock) {
                try {
                    glState?.render(bitmap, normalizedX, normalizedY, strength)
                } catch (error: RuntimeException) {
                    Log.e(Tag, "Unable to render pet-tap shader", error)
                } finally {
                    framePending = false
                }
            }
        }
        true
    }

    fun clearEffect() {
        if (released) return
        renderHandler.post { glState?.clear() }
    }

    fun releaseRenderer() {
        synchronized(frameLock) {
            if (released) return
            released = true
        }
        surfaceTextureListener = null
        renderHandler.post {
            glState?.release()
            glState = null
            synchronized(frameLock) {
                stagingBitmap?.recycle()
                stagingBitmap = null
                framePending = false
            }
            renderThread.quitSafely()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderHandler.post {
            if (released) return@post
            runCatching {
                glState?.release()
                glState = GlState(surface, width, height).also { it.clear() }
            }.onFailure { error ->
                Log.e(Tag, "Unable to initialize pet-tap shader", error)
                glState?.release()
                glState = null
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderHandler.post { glState?.resize(width, height) }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderHandler.post {
            glState?.release()
            glState = null
            surface.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private class GlState(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface
        private val program: Int
        private val texture: Int
        private val positionLocation: Int
        private val centerLocation: Int
        private val aspectLocation: Int
        private val radiusLocation: Int
        private val strengthLocation: Int
        private val vertices: FloatBuffer = ByteBuffer
            .allocateDirect(Vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(Vertices)
                position(0)
            }
        private var viewportWidth = width
        private var viewportHeight = height

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            require(display != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            check(
                EGL14.eglChooseConfig(
                    display,
                    EglConfigAttributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    configCount,
                    0,
                ) && configCount[0] > 0,
            ) { "EGL config unavailable" }
            val config = requireNotNull(configs[0])
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            require(context != EGL14.EGL_NO_CONTEXT) { "EGL context unavailable" }
            surface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surfaceTexture,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            require(surface != EGL14.EGL_NO_SURFACE) { "EGL surface unavailable" }
            makeCurrent()

            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VertexShader)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FragmentShader)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}"
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            positionLocation = GLES20.glGetAttribLocation(program, "a_position")
            centerLocation = requiredUniform("u_center")
            aspectLocation = requiredUniform("u_aspect")
            radiusLocation = requiredUniform("u_radius")
            strengthLocation = requiredUniform("u_strength")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(requiredUniform("u_texture"), 0)
            GLES20.glUniform1f(radiusLocation, PetTapBulgeRadius)
            resize(width, height)
        }

        fun render(bitmap: Bitmap, centerX: Float, centerY: Float, strength: Float) {
            makeCurrent()
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glUniform2f(centerLocation, centerX, centerY)
            GLES20.glUniform1f(aspectLocation, viewportWidth.toFloat() / viewportHeight)
            GLES20.glUniform1f(strengthLocation, strength)
            vertices.position(0)
            GLES20.glEnableVertexAttribArray(positionLocation)
            GLES20.glVertexAttribPointer(
                positionLocation,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertices,
            )
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            check(EGL14.eglSwapBuffers(display, surface)) { "EGL buffer swap failed" }
        }

        fun clear() {
            makeCurrent()
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun resize(width: Int, height: Int) {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
        }

        fun release() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "Unable to make EGL context current"
            }
        }

        private fun requiredUniform(name: String): Int = GLES20.glGetUniformLocation(program, name)
            .also { check(it >= 0) { "Missing GL uniform: $name" } }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            check(compileStatus[0] == GLES20.GL_TRUE) {
                "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        }
    }

    private companion object {
        const val Tag = "PetTapGlOverlay"

        val Vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        val EglConfigAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE,
            EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,
            EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE,
            8,
            EGL14.EGL_GREEN_SIZE,
            8,
            EGL14.EGL_BLUE_SIZE,
            8,
            EGL14.EGL_ALPHA_SIZE,
            8,
            EGL14.EGL_NONE,
        )

        const val VertexShader = """
            attribute vec2 a_position;
            varying vec2 v_uv;

            void main() {
                v_uv = vec2(a_position.x * 0.5 + 0.5, 0.5 - a_position.y * 0.5);
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """

        const val FragmentShader = """
            precision highp float;

            varying vec2 v_uv;
            uniform sampler2D u_texture;
            uniform vec2 u_center;
            uniform float u_aspect;
            uniform float u_radius;
            uniform float u_strength;

            void main() {
                vec2 offset = v_uv - u_center;
                vec2 circularOffset = vec2(offset.x * u_aspect, offset.y);
                float distanceFromCenter = length(circularOffset);
                float normalizedDistance = clamp(distanceFromCenter / u_radius, 0.0, 1.0);
                float falloff = 1.0 - smoothstep(0.0, 1.0, normalizedDistance);
                float sampleScale = 1.0 - u_strength * falloff * falloff;
                vec2 distortedUv = clamp(u_center + offset * sampleScale, 0.0, 1.0);
                float softCircle = 1.0 - smoothstep(u_radius * 0.78, u_radius, distanceFromCenter);
                vec4 sourceColor = texture2D(u_texture, v_uv);
                vec4 distortedColor = texture2D(u_texture, distortedUv);
                gl_FragColor = mix(sourceColor, distortedColor, softCircle);
            }
        """
    }
}
