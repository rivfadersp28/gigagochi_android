package com.gigagochi.app.feature.events

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gigagochi.app.R
import com.gigagochi.app.core.database.LocalScheduledStory
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import com.gigagochi.app.core.designsystem.SbSansDisplayFontFamily
import com.gigagochi.app.core.designsystem.ContextualAppBarContentGap
import com.gigagochi.app.core.designsystem.ContextualAppBarEdgePadding
import com.gigagochi.app.core.designsystem.ContextualGlassNavigation
import com.gigagochi.app.core.designsystem.ContextualNavigationMinimumTouchTarget
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.travel.LoopingStoryMedia
import com.gigagochi.app.feature.travel.rememberTravelReducedMotionPreference
import kotlinx.coroutines.launch
import kotlin.math.abs

private val EventCardShape = RoundedCornerShape(20.dp)
private val HelpButtonShape = RoundedCornerShape(24.dp)
internal val EventScreenHorizontalPadding = 20.dp
internal val EventCardSpacing = 86.dp

@Composable
fun EventHistoryScreen(
    stories: List<LocalScheduledStory>,
    travelVideos: List<LocalTravelVideoAsset> = emptyList(),
    mediaUrlPolicy: StaticMediaUrlPolicy,
    travelVideoSharer: TravelVideoSharer = TravelVideoSharer {
        TravelVideoShareResult.Failed
    },
    initialFocusTravelRequestKey: String? = null,
    onHelp: (LocalScheduledStory) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val history = remember(stories, travelVideos) {
        eventHistoryUiState(stories, travelVideos)
    }
    val listState = rememberLazyListState()
    val reducedMotion = rememberTravelReducedMotionPreference()
    val safeTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val eventKeys = remember(history.items) { history.items.mapTo(mutableSetOf()) { it.key } }
    val activeEventKey by remember(listState, eventKeys) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
            layout.visibleItemsInfo
                .filter { it.key is String && it.key in eventKeys }
                .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
                ?.key as? String
        }
    }

    LaunchedEffect(initialFocusTravelRequestKey, history.items) {
        val requestKey = initialFocusTravelRequestKey ?: return@LaunchedEffect
        val index = history.items.indexOfFirst { it.key == travelEventKey(requestKey) }
        if (index >= 0) listState.scrollToItem(index + 1)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = EventScreenHorizontalPadding,
                top = safeTop + ContextualAppBarEdgePadding +
                    ContextualNavigationMinimumTouchTarget + ContextualAppBarContentGap,
                end = EventScreenHorizontalPadding,
                bottom = 34.dp,
            ),
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "История событий" },
        ) {
            item(key = "events-title") {
                Text(
                    text = "События",
                    color = Color.White,
                    fontFamily = SbSansDisplayFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 40.sp,
                )
                Spacer(Modifier.height(19.dp))
            }

            if (history.isEmpty) {
                item(key = "events-empty") {
                    Text(
                        text = "Пока событий нет",
                        color = Color.White.copy(alpha = .48f),
                        fontFamily = OpenRundeFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
            }

            items(history.items, key = { it.key }) { historyItem ->
                when (historyItem) {
                    is EventHistoryItem.TravelVideo -> TravelVideoEventCard(
                        asset = historyItem.asset,
                        playVideo = historyItem.key == activeEventKey,
                        reducedMotion = reducedMotion,
                        mediaUrlPolicy = mediaUrlPolicy,
                        sharer = travelVideoSharer,
                    )
                    is EventHistoryItem.ScheduledStory -> if (historyItem.answered) {
                        AnsweredEventCard(
                            item = historyItem.item,
                            playVideo = historyItem.key == activeEventKey,
                            reducedMotion = reducedMotion,
                            mediaUrlPolicy = mediaUrlPolicy,
                        )
                    } else {
                        UnansweredEventCard(
                            item = historyItem.item,
                            playVideo = historyItem.key == activeEventKey,
                            reducedMotion = reducedMotion,
                            mediaUrlPolicy = mediaUrlPolicy,
                            onHelp = { onHelp(historyItem.item) },
                        )
                    }
                }
                Spacer(Modifier.height(EventCardSpacing))
            }
        }

        ContextualGlassNavigation(
            action = ContextualNavigationAction.Back,
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(
                    start = ContextualAppBarEdgePadding,
                    top = ContextualAppBarEdgePadding,
                ),
        )
    }
}

@Composable
private fun TravelVideoEventCard(
    asset: LocalTravelVideoAsset,
    playVideo: Boolean,
    reducedMotion: Boolean,
    mediaUrlPolicy: StaticMediaUrlPolicy,
    sharer: TravelVideoSharer,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember(asset.requestKey) { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        EventMedia(
            posterUrl = asset.imageUrl,
            videoUrl = asset.videoUrl,
            playVideo = playVideo,
            reducedMotion = reducedMotion,
            mediaUrlPolicy = mediaUrlPolicy,
            description = "Видео путешествия: ${travelEventCaption(asset)}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = travelEventCaption(asset),
            color = Color.White,
            fontFamily = SbSansDisplayFontFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 23.9.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        TiltedEventButton(
            text = if (sharing) "Подготавливаю…" else "Показать друзьям",
            contentDescription = if (sharing) "Подготовка видео" else "Поделиться видео",
            width = 271.328.dp,
            enabled = !sharing,
            onClick = {
                sharing = true
                scope.launch {
                    val result = sharer.share(asset)
                    sharing = false
                    if (result is TravelVideoShareResult.Failed) {
                        Toast.makeText(
                            context,
                            "Не удалось открыть отправку видео",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun UnansweredEventCard(
    item: LocalScheduledStory,
    playVideo: Boolean,
    reducedMotion: Boolean,
    mediaUrlPolicy: StaticMediaUrlPolicy,
    onHelp: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        EventMedia(
            posterUrl = item.story.imageUrl,
            videoUrl = item.story.videoUrl,
            playVideo = playVideo,
            reducedMotion = reducedMotion,
            mediaUrlPolicy = mediaUrlPolicy,
            description = "Видео события: ${item.story.title}",
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = item.story.text.take(180),
            color = Color.White,
            fontFamily = SbSansDisplayFontFamily,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 23.9.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        HelpButton(onClick = onHelp)
    }
}

@Composable
private fun AnsweredEventCard(
    item: LocalScheduledStory,
    playVideo: Boolean,
    reducedMotion: Boolean,
    mediaUrlPolicy: StaticMediaUrlPolicy,
) {
    val result = item.story.result ?: return
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        EventMedia(
            posterUrl = item.story.resultImageUrl ?: item.story.imageUrl,
            videoUrl = item.story.resultVideoUrl ?: item.story.videoUrl,
            playVideo = playVideo,
            reducedMotion = reducedMotion,
            mediaUrlPolicy = mediaUrlPolicy,
            description = "Итог события: ${item.story.title}",
        )
        Spacer(Modifier.height(20.dp))
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EventResultText(result.text)
            EventResultText(result.consequence)
            EventResultText(result.reaction, muted = true)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.Start)
                    .semantics {
                        contentDescription = "Получено ${result.experienceGained} монет"
                    },
            ) {
                Image(
                    painter = painterResource(R.drawable.xp_coin),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(24.dp, 24.174.dp),
                )
                Text(
                    text = "+${result.experienceGained}",
                    color = Color(0xFFFFB107),
                    fontFamily = OpenRundeFontFamily,
                    fontSize = 31.5.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 38.sp,
                )
            }
        }
    }
}

@Composable
private fun EventMedia(
    posterUrl: String?,
    videoUrl: String?,
    playVideo: Boolean,
    reducedMotion: Boolean,
    mediaUrlPolicy: StaticMediaUrlPolicy,
    description: String,
    modifier: Modifier = Modifier.fillMaxWidth().height(355.dp),
) {
    LoopingStoryMedia(
        fallbackPoster = R.drawable.event_media_placeholder,
        remotePosterUrl = posterUrl,
        remoteVideoUrl = videoUrl,
        mediaUrlPolicy = mediaUrlPolicy,
        reducedMotion = reducedMotion,
        forcePoster = false,
        playVideo = playVideo,
        modifier = modifier
            .clip(EventCardShape)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun EventResultText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        color = if (muted) Color.White.copy(alpha = .3f) else Color.White,
        fontFamily = OpenRundeFontFamily,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 24.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HelpButton(onClick: () -> Unit) {
    TiltedEventButton(
        text = "Помочь",
        contentDescription = "Помочь",
        width = 150.dp,
        onClick = onClick,
    )
}

@Composable
private fun TiltedEventButton(
    text: String,
    contentDescription: String,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .requiredWidth(width)
            .requiredHeight(58.203.dp)
            .graphicsLayer {
                rotationZ = 2f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .scaleForEventButton(scale.value)
            .shadow(24.dp, HelpButtonShape, ambientColor = Color.Black.copy(alpha = .4f))
            .clip(HelpButtonShape)
            .background(Color.White)
            .graphicsLayer { alpha = if (enabled) 1f else .62f }
            .pointerInput(enabled, onClick) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        scope.launch { scale.animateTo(.94f, spring(stiffness = Spring.StiffnessHigh)) }
                        val released = tryAwaitRelease()
                        scope.launch {
                            scale.animateTo(
                                1f,
                                spring(
                                    dampingRatio = .6f,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                        if (released) onClick()
                    },
                )
            }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (!enabled) disabled()
                if (enabled) {
                    onClick(contentDescription) {
                        onClick()
                        true
                    }
                }
            },
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontFamily = OpenRundeFontFamily,
            fontSize = 23.445.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 30.sp,
        )
    }
}

private fun Modifier.scaleForEventButton(value: Float): Modifier = graphicsLayer {
    scaleX = value
    scaleY = value
}
