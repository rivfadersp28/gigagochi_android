package com.gigagochi.app.core.designsystem

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.staticCompositionLocalOf
import com.gigagochi.app.R

val LocalButtonPressFeedback = staticCompositionLocalOf<() -> Unit> { {} }

internal class ButtonPressAudio(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    @Volatile
    private var loadedSoundId = 0
    @Volatile
    private var pendingPlay = false
    private val soundId: Int

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSoundId = sampleId
                if (pendingPlay) {
                    pendingPlay = false
                    playLoaded(sampleId)
                }
            }
        }
        soundId = soundPool.load(context, R.raw.button_press, 1)
    }

    fun play() {
        val ready = loadedSoundId
        if (ready == soundId) {
            playLoaded(ready)
        } else {
            pendingPlay = true
        }
    }

    private fun playLoaded(sampleId: Int) {
        soundPool.play(sampleId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
