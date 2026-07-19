package com.gigagochi.app.feature.travel

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gigagochi.app.R
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.dashboard.RemoteOrFixturePoster

@OptIn(UnstableApi::class)
@Composable
internal fun LoopingStoryMedia(
    @DrawableRes fallbackPoster: Int,
    remotePosterUrl: String?,
    remoteVideoUrl: String?,
    mediaUrlPolicy: StaticMediaUrlPolicy?,
    reducedMotion: Boolean,
    forcePoster: Boolean,
    playVideo: Boolean,
    modifier: Modifier = Modifier,
    localVideoAsset: String? = null,
) {
    Box(modifier = modifier.background(Color(0xFF242424))) {
        val canPlay = !LocalInspectionMode.current && !reducedMotion && playVideo &&
            (remoteVideoUrl != null || localVideoAsset != null)
        if (!canPlay) {
            RemoteOrFixturePoster(
                posterUrl = remotePosterUrl,
                urlPolicy = mediaUrlPolicy,
                retryToken = 0,
                onFailure = {},
                fallbackDrawable = fallbackPoster,
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }

        val context = LocalContext.current.applicationContext
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val mediaKey = remoteVideoUrl ?: localVideoAsset
        var showPoster by remember(mediaKey) { mutableStateOf(true) }
        val currentForcePoster by rememberUpdatedState(forcePoster)
        val currentPlayVideo by rememberUpdatedState(playVideo)
        var lifecycleStarted by remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        val usesRemoteDataSource = remoteVideoUrl != null && mediaUrlPolicy != null
        val player = remember(context, usesRemoteDataSource, mediaKey) {
            TravelStoryMediaTestProbe.recordPlayerCreation()
            val builder = if (usesRemoteDataSource) {
                ExoPlayer.Builder(context).setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        StaticMediaCache.dataSourceFactory(
                            context,
                            requireNotNull(mediaUrlPolicy),
                        ),
                    ),
                )
            } else ExoPlayer.Builder(context)
            builder.build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }

        LaunchedEffect(player, mediaKey) {
            showPoster = true
            val uri = remoteVideoUrl ?: "asset:///${requireNotNull(localVideoAsset)}"
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            player.prepare()
            player.playWhenReady = lifecycleStarted && playVideo
        }

        LaunchedEffect(player, lifecycleStarted, playVideo) {
            player.playWhenReady = lifecycleStarted && playVideo
        }

        DisposableEffect(player, lifecycle) {
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!currentForcePoster) showPoster = false
                }

                override fun onPlayerError(error: PlaybackException) {
                    showPoster = true
                }
            }
            val observer = LifecycleEventObserver { _, _ ->
                lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                player.playWhenReady = lifecycleStarted && currentPlayVideo
            }
            player.addListener(listener)
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                player.removeListener(listener)
                player.release()
            }
        }

        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext)
                    .inflate(R.layout.view_travel_player, null, false) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        val posterAlpha by animateFloatAsState(
            targetValue = if (showPoster || forcePoster) 1f else 0f,
            animationSpec = tween(180),
            label = "story-poster-crossfade",
        )
        RemoteOrFixturePoster(
            posterUrl = remotePosterUrl,
            urlPolicy = mediaUrlPolicy,
            retryToken = 0,
            onFailure = { showPoster = true },
            fallbackDrawable = fallbackPoster,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = posterAlpha },
        )
    }
}
