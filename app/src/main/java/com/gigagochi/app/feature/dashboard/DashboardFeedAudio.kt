package com.gigagochi.app.feature.dashboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.gigagochi.app.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

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

internal class DashboardSpeechAudio(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val soundIds: IntArray

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loadedSoundIds) { loadedSoundIds += sampleId }
        }
        soundIds = intArrayOf(
            soundPool.load(context, R.raw.speech_1, 1),
            soundPool.load(context, R.raw.speech_2, 1),
            soundPool.load(context, R.raw.speech_3, 1),
            soundPool.load(context, R.raw.speech_4, 1),
        )
    }

    suspend fun playSequence(durationMillis: Long) {
        if (durationMillis <= 0) return
        val activeStreams = mutableListOf<Int>()
        var previousIndex = -1
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (SystemClock.elapsedRealtime() - startedAt < durationMillis) {
                currentCoroutineContext().ensureActive()
                val ready = synchronized(loadedSoundIds) { loadedSoundIds.toSet() }
                val candidates = soundIds.indices.filter { soundIds[it] in ready && it != previousIndex }
                if (candidates.isNotEmpty()) {
                    val index = candidates.random()
                    val streamId = soundPool.play(soundIds[index], .32f, .32f, 1, 0, 1f)
                    if (streamId != 0) activeStreams += streamId
                    previousIndex = index
                }
                delay((48L + Random.nextLong(-6L, 7L)).coerceAtLeast(1L))
            }
        } finally {
            activeStreams.forEach(soundPool::stop)
        }
    }

    fun release() {
        soundPool.release()
    }
}
