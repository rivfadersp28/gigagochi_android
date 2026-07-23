package com.gigagochi.app.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import com.gigagochi.app.R

internal class AndroidWebFeedbackHandler(
    context: Context,
    private val hapticView: View,
) : WebFeedbackHandler, AutoCloseable {
    private val lock = Any()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val pendingPlayCounts = mutableMapOf<Int, Int>()
    private val creationSoundId: Int
    private val buttonSoundId: Int
    private var released = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            val pendingPlayCount = synchronized(lock) {
                if (released) {
                    0
                } else {
                    loadedSoundIds += sampleId
                    pendingPlayCounts.remove(sampleId) ?: 0
                }
            }
            repeat(pendingPlayCount) { playLoaded(sampleId) }
        }
        creationSoundId = soundPool.load(context.applicationContext, R.raw.creation_button_plop, 1)
        buttonSoundId = soundPool.load(context.applicationContext, R.raw.button_press, 1)
    }

    @SuppressLint("InlinedApi")
    override fun handle(kind: WebFeedbackKind) {
        val isCreateFeedback = when (kind) {
            WebFeedbackKind.CreateAnswer,
            WebFeedbackKind.CreateCustom,
            WebFeedbackKind.CreateRetry,
            -> true
            WebFeedbackKind.DashboardAction,
            WebFeedbackKind.ChatSubmit,
            WebFeedbackKind.ButtonPress,
            -> false
        }
        play(if (isCreateFeedback) creationSoundId else buttonSoundId)
        if (isCreateFeedback) {
            runCatching {
                hapticView.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            }
        }
    }

    private fun play(soundId: Int) {
        val playNow = synchronized(lock) {
            if (released) return
            if (soundId in loadedSoundIds) true else {
                pendingPlayCounts[soundId] = (pendingPlayCounts[soundId] ?: 0) + 1
                false
            }
        }
        if (playNow) playLoaded(soundId)
    }

    private fun playLoaded(soundId: Int) {
        runCatching { soundPool.play(soundId, 1f, 1f, 1, 0, 1f) }
    }

    override fun close() {
        val shouldRelease = synchronized(lock) {
            if (released) {
                false
            } else {
                released = true
                loadedSoundIds.clear()
                pendingPlayCounts.clear()
                true
            }
        }
        if (shouldRelease) {
            runCatching {
                soundPool.setOnLoadCompleteListener(null)
                soundPool.release()
            }
        }
    }
}
