package com.gigagochi.app.feature.dashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.gigagochi.app.R

internal class DashboardFeedAudio(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundIds = intArrayOf(
        soundPool.load(context, R.raw.feed_bite_1, 1),
        soundPool.load(context, R.raw.feed_bite_2, 1),
        soundPool.load(context, R.raw.feed_bite_3, 1),
    )

    fun play(index: Int) {
        val soundId = soundIds[index.mod(soundIds.size)]
        soundPool.play(soundId, .78f, .78f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}

internal class DashboardPetTapAudio(context: Context) {
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
        soundId = soundPool.load(context, R.raw.pet_tap, 1)
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
        soundPool.play(sampleId, .9f, .9f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
