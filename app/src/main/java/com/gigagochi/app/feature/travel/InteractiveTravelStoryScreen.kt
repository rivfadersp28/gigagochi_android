package com.gigagochi.app.feature.travel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.ContextualAppBarContentGap
import com.gigagochi.app.core.designsystem.ContextualAppBarEdgePadding
import com.gigagochi.app.core.designsystem.ContextualGlassNavigation
import com.gigagochi.app.core.designsystem.ContextualNavigationMinimumTouchTarget
import com.gigagochi.app.core.designsystem.ContextualNavigationAction
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlin.math.max

enum class StoryScrollTarget { Top, Answers, FinishAction }

internal object TravelStoryMediaTestProbe {
    var createdPlayerCount: Int = 0
        private set

    fun reset() {
        createdPlayerCount = 0
    }

    fun recordPlayerCreation() {
        createdPlayerCount += 1
    }
}

private object TravelStoryGlassContract {
    val Shape = RoundedCornerShape(24.dp)
    val Tint = Color.White.copy(alpha = .60f)
    val Style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(Tint)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Tint),
    )
    val BottomInset = Shadow(
        radius = 6.dp,
        color = Color(0x6600182E),
        offset = DpOffset(0.dp, (-5).dp),
    )
}

@Composable
fun InteractiveTravelStoryScreen(
    state: TravelEntryState,
    reducedMotion: Boolean,
    forcePoster: Boolean,
    scrollTarget: StoryScrollTarget,
    mediaUrlPolicy: com.gigagochi.app.core.network.StaticMediaUrlPolicy? = null,
    navigationAction: ContextualNavigationAction? = null,
    onNavigateBack: () -> Unit = {},
    onChoice: (String) -> Unit,
    onFinish: () -> Unit,
) {
    val story = state.story ?: return
    val result = state.storyResult
    val isResult = state.phase == TravelEntryPhase.StoryResult || result != null
    val hazeState = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        StoryReferenceFrame {
            StoryBackdrop(
            drawable = if (isResult) {
                R.drawable.onboarding_bat_success
            } else {
                R.drawable.onboarding_bat_situation
            },
            hazeState = hazeState,
            )

            if (state.phase != TravelEntryPhase.Finished) {
                val contentIsInteractive = state.phase != TravelEntryPhase.ChoicePending
                StoryScrollableContent(
                    story = story,
                    result = result,
                    reducedMotion = reducedMotion,
                    forcePoster = forcePoster,
                    scrollTarget = scrollTarget,
                    hazeState = hazeState,
                    contentIsInteractive = contentIsInteractive,
                    mediaUrlPolicy = mediaUrlPolicy,
                    onChoice = if (contentIsInteractive) onChoice else ({ _: String -> }),
                    onFinish = if (contentIsInteractive) onFinish else ({ Unit }),
                )
            }
            if (state.phase == TravelEntryPhase.ChoicePending) {
                StoryThinkingIndicator(
                    freezeFrame = reducedMotion,
                    modifier = Modifier.offset(x = 161.dp, y = 567.1.dp),
                )
            }
            if (state.error != null && state.phase == TravelEntryPhase.StoryQuestion) {
                Text(
                    text = state.error,
                    color = Color.White,
                    fontFamily = OpenRundeFontFamily,
                    fontSize = 13.sp,
                    lineHeight = 16.25.sp,
                    modifier = Modifier
                        .offset(x = 21.dp, y = 58.dp)
                        .requiredWidth(360.dp)
                        .background(Color(0xC2160A0A), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(14.dp))
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = state.error
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        navigationAction?.let { action ->
            ContextualGlassNavigation(
                action = action,
                onClick = onNavigateBack,
                hazeState = hazeState,
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
}

@Composable
private fun BoxScope.StoryBackdrop(
    drawable: Int,
    hazeState: HazeState,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .hazeSource(hazeState),
    ) {
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .requiredSize(580.dp, 1209.dp)
                .offset(x = (-89).dp, y = (-325).dp)
                .blur(62.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .graphicsLayer { alpha = .5f },
        )
    }
}

@Composable
private fun StoryScrollableContent(
    story: InteractiveTravelStory,
    result: InteractiveTravelStoryResult?,
    reducedMotion: Boolean,
    forcePoster: Boolean,
    scrollTarget: StoryScrollTarget,
    hazeState: HazeState,
    contentIsInteractive: Boolean,
    mediaUrlPolicy: com.gigagochi.app.core.network.StaticMediaUrlPolicy?,
    onChoice: (String) -> Unit,
    onFinish: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollTarget, scrollState.maxValue) {
        if (scrollState.maxValue <= 0) return@LaunchedEffect
        when (scrollTarget) {
            StoryScrollTarget.Top -> scrollState.scrollTo(0)
            StoryScrollTarget.Answers,
            StoryScrollTarget.FinishAction,
            -> scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (contentIsInteractive) 1f else 0f }
            .verticalScroll(scrollState, enabled = contentIsInteractive)
            .then(
                if (contentIsInteractive) {
                    Modifier.semantics { contentDescription = "Интерактивное путешествие" }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
            )
            .padding(
                top = ContextualAppBarEdgePadding +
                    ContextualNavigationMinimumTouchTarget + ContextualAppBarContentGap,
            ),
    ) {
        StoryMediaFrame(
            poster = if (result == null) {
                R.drawable.onboarding_bat_situation
            } else {
                R.drawable.onboarding_bat_success
            },
            videoAsset = if (result == null) {
                "media/onboarding-bat-situation.mp4"
            } else {
                "media/onboarding-bat-success.mp4"
            },
            remotePosterUrl = result?.imageUrl ?: story.imageUrl,
            remoteVideoUrl = result?.videoUrl ?: story.videoUrl,
            mediaUrlPolicy = mediaUrlPolicy,
            reducedMotion = reducedMotion,
            forcePoster = forcePoster,
        )

        StoryTextFrame(
            story = story,
            result = result,
            reducedMotion = reducedMotion,
            modifier = Modifier.padding(top = 34.dp),
        )

        if (result == null) {
            StoryAnswers(
                story = story,
                reducedMotion = reducedMotion,
                hazeState = hazeState,
                onChoice = onChoice,
                modifier = Modifier.padding(top = 40.dp, bottom = 59.dp),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .requiredWidth(402.dp)
                    .padding(start = 20.dp, top = 46.dp, end = 20.dp, bottom = 59.dp),
            ) {
                StoryTiltedGlassButton(
                    label = "Завершить",
                    index = 0,
                    animateEntrance = false,
                    enabled = true,
                    reducedMotion = reducedMotion,
                    hazeState = hazeState,
                    minWidth = 130.dp,
                    onClick = onFinish,
                )
            }
        }
    }
}

@Composable
private fun StoryTextFrame(
    story: InteractiveTravelStory,
    result: InteractiveTravelStoryResult?,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val paragraphs = if (result == null) {
        if (isOnboardingBatStory(story)) onboardingBatQuestionParagraphs(story)
        else listOf(story.storyText, story.challenge)
    } else {
        if (isOnboardingBatStory(story)) onboardingBatResultParagraphs(result)
        else listOf(result.text, result.consequence)
    }
    val paragraphStartIndices = paragraphs.indices.map { paragraphIndex ->
        paragraphs.take(paragraphIndex).sumOf(String::length)
    }
    val storyCharacterCount = paragraphs.sumOf(String::length)
    val storyRevealDuration = if (storyCharacterCount == 0) {
        0
    } else {
        300 + (storyCharacterCount - 1) * 12
    }
    val reactionCharacterCount = result?.reaction?.length ?: 0
    val reactionRevealDuration = if (reactionCharacterCount == 0) {
        0
    } else {
        300 + (reactionCharacterCount - 1) * 12
    }

    Column(
        modifier = modifier
            .requiredWidth(362.dp)
            .heightIn(min = 146.dp)
            .semantics {
                contentDescription = if (result == null) {
                    "История и вопрос"
                } else {
                    "Результат выбора"
                }
            },
    ) {
        paragraphs.forEachIndexed { index, paragraph ->
            if (index > 0) Spacer(Modifier.height(13.dp))
            StaggeredStoryText(
                text = paragraph,
                startIndex = paragraphStartIndices[index],
                baseDelayMillis = 0,
                color = Color.White,
                reducedMotion = reducedMotion,
            )
        }
        if (result != null) {
            Spacer(Modifier.height(16.dp))
            StaggeredStoryText(
                text = result.reaction,
                startIndex = 0,
                baseDelayMillis = storyRevealDuration,
                color = Color.White.copy(alpha = .3f),
                reducedMotion = reducedMotion,
            )
            Spacer(Modifier.height(8.dp))
            StoryExperienceOutcome(
                amount = result.experienceGained,
                delayMillis = storyRevealDuration + reactionRevealDuration,
                reducedMotion = reducedMotion,
            )
        }
    }
}

@Composable
private fun StaggeredStoryText(
    text: String,
    startIndex: Int,
    baseDelayMillis: Int,
    color: Color,
    reducedMotion: Boolean,
) {
    val riseEasing = remember { CubicBezierEasing(.2f, .8f, .2f, 1f) }
    var elapsedMillis by remember(text, reducedMotion) {
        mutableLongStateOf(if (reducedMotion) Long.MAX_VALUE else 0L)
    }
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val totalDuration = baseDelayMillis + (startIndex + text.length) * 12 + 300
    LaunchedEffect(text, reducedMotion, baseDelayMillis, startIndex) {
        if (reducedMotion) return@LaunchedEffect
        val startedAt = withFrameNanos { it }
        while (elapsedMillis < totalDuration) {
            val now = withFrameNanos { it }
            elapsedMillis = (now - startedAt) / 1_000_000L
        }
    }
    // Read on the composition frame so TextureView/poster layer changes cannot leave the custom
    // glyph draw cache stale after the reveal coroutine advances.
    val revealElapsedMillis = elapsedMillis
    Text(
        text = text,
        color = Color.Transparent,
        fontFamily = OpenRundeFontFamily,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 24.sp,
        textAlign = TextAlign.Left,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
            .drawWithContent {
                val measured = layoutResult ?: return@drawWithContent
                val riseDistance = 12.dp.toPx()
                text.forEachIndexed { index, character ->
                    if (character.isWhitespace()) return@forEachIndexed
                    val localElapsed =
                        revealElapsedMillis - baseDelayMillis - (startIndex + index) * 12L
                    val rawProgress = if (reducedMotion) {
                        1f
                    } else {
                        (localElapsed / 300f).coerceIn(0f, 1f)
                    }
                    if (rawProgress <= 0f) return@forEachIndexed
                    val progress = riseEasing.transform(rawProgress)
                    val bounds = measured.getBoundingBox(index)
                    translate(top = riseDistance * (1f - progress)) {
                        clipRect(
                            left = bounds.left - 1f,
                            top = bounds.top - 1f,
                            right = bounds.right + 1f,
                            bottom = bounds.bottom + 1f,
                        ) {
                            drawText(
                                textLayoutResult = measured,
                                color = color,
                                alpha = progress,
                            )
                        }
                    }
                }
            }
            .clearAndSetSemantics {
                contentDescription = text
            },
    )
}

@Composable
private fun StoryExperienceOutcome(
    amount: Int,
    delayMillis: Int,
    reducedMotion: Boolean,
) {
    val entrance = remember(amount, reducedMotion) {
        Animatable(if (reducedMotion) 1f else 0f)
    }
    LaunchedEffect(amount, reducedMotion, delayMillis) {
        if (reducedMotion) return@LaunchedEffect
        delay(delayMillis.toLong())
        entrance.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = 300,
                easing = CubicBezierEasing(.2f, .8f, .2f, 1f),
            ),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .graphicsLayer {
                alpha = entrance.value
                translationY = (12.dp.toPx() * (1f - entrance.value))
            }
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Получено $amount единиц опыта"
            },
    ) {
        Text(
            text = "+$amount",
            color = Color(0xFFFFB107),
            fontFamily = OpenRundeFontFamily,
            fontSize = 31.5.sp,
            fontWeight = FontWeight.Black,
        )
        Image(
            painter = painterResource(R.drawable.xp_coin),
            contentDescription = null,
            modifier = Modifier.requiredSize(24.dp, 24.174.dp),
        )
    }
}

@Composable
private fun StoryAnswers(
    story: InteractiveTravelStory,
    reducedMotion: Boolean,
    hazeState: HazeState,
    onChoice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(19.dp),
        horizontalAlignment = Alignment.Start,
        modifier = modifier
            .requiredWidth(362.dp)
            .semantics { contentDescription = "Варианты ответа" },
    ) {
        story.choices.take(4).forEachIndexed { index, choice ->
            StoryTiltedGlassButton(
                label = choice,
                index = index,
                animateEntrance = true,
                enabled = story.enabledChoice.isEmpty() || choice == story.enabledChoice,
                reducedMotion = reducedMotion,
                hazeState = hazeState,
                onClick = { onChoice(choice) },
            )
        }
    }
}

@Composable
private fun StoryTiltedGlassButton(
    label: String,
    index: Int,
    animateEntrance: Boolean,
    enabled: Boolean,
    reducedMotion: Boolean,
    hazeState: HazeState,
    onClick: () -> Unit,
    minWidth: androidx.compose.ui.unit.Dp? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val entrance = remember(label, reducedMotion) {
        Animatable(if (animateEntrance && !reducedMotion) .6f else 1f)
    }
    LaunchedEffect(label, animateEntrance, reducedMotion) {
        if (animateEntrance && !reducedMotion) {
            delay(index * 200L)
            entrance.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f),
                ),
            )
        }
    }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .9f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 140),
        label = "travel-story-button-press",
    )
    val entranceAlpha = ((entrance.value - .6f) / .4f).coerceIn(0f, 1f)
    val alpha = entranceAlpha * if (enabled) 1f else .65f
    val rotation = if (index % 2 == 0) -2f else 2f
    val widthModifier = if (minWidth == null) Modifier else Modifier.widthIn(min = minWidth)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(widthModifier)
            .height(62.dp)
            .graphicsLayer {
                rotationZ = rotation
                scaleX = entrance.value * pressScale
                scaleY = entrance.value * pressScale
                this.alpha = alpha
            }
            .clip(TravelStoryGlassContract.Shape)
            .hazeEffect(hazeState, TravelStoryGlassContract.Style)
            .innerShadow(TravelStoryGlassContract.Shape, TravelStoryGlassContract.BottomInset)
            .pointerInput(label, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val released = tryAwaitRelease()
                        pressed = false
                        if (released) onClick()
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (enabled) {
                    onClick {
                        onClick()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .padding(start = 17.dp, top = 14.dp, end = 17.dp, bottom = 16.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF05152C),
            fontFamily = OpenRundeFontFamily,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp,
            letterSpacing = (-.45).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun StoryMediaFrame(
    poster: Int,
    videoAsset: String,
    remotePosterUrl: String? = null,
    remoteVideoUrl: String? = null,
    mediaUrlPolicy: com.gigagochi.app.core.network.StaticMediaUrlPolicy? = null,
    reducedMotion: Boolean,
    forcePoster: Boolean,
) {
    LoopingStoryMedia(
        fallbackPoster = poster,
        remotePosterUrl = remotePosterUrl,
        remoteVideoUrl = remoteVideoUrl,
        mediaUrlPolicy = mediaUrlPolicy,
        reducedMotion = reducedMotion,
        forcePoster = forcePoster,
        playVideo = true,
        localVideoAsset = videoAsset,
        modifier = Modifier
            .requiredSize(382.dp, 461.dp)
            .clip(RoundedCornerShape(20.dp)),
    )
}

@Composable
private fun StoryThinkingIndicator(
    freezeFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(freezeFrame) {
        if (freezeFrame) return@LaunchedEffect
        while (true) {
            delay(200)
            frame = (frame + 1) % 3
        }
    }
    val frames = listOf(
        R.drawable.thinking_frame_1,
        R.drawable.thinking_frame_2,
        R.drawable.thinking_frame_3,
    )
    Image(
        painter = painterResource(frames[if (freezeFrame) 0 else frame]),
        contentDescription = "Персонаж думает",
        contentScale = ContentScale.FillBounds,
        modifier = modifier.requiredSize(80.dp, 55.5.dp),
    )
}

@Composable
private fun StoryReferenceFrame(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter,
    ) {
        val scale = maxWidth.value / 402f
        val referenceHeight = max(874f, maxHeight.value / scale)
        Box(
            modifier = Modifier
                .requiredSize(402.dp, referenceHeight.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(.5f, 0f)
                },
            content = content,
        )
    }
}

@Preview(widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun InteractiveTravelStoryPreview() {
    val pet = TravelEntryPet("debug-test-pet", "Без имени")
    GigagochiTheme {
        InteractiveTravelStoryScreen(
            state = TravelEntryState(
                pet = pet,
                phase = TravelEntryPhase.StoryQuestion,
                story = onboardingBatStory(pet.petId),
            ),
            reducedMotion = true,
            forcePoster = true,
            scrollTarget = StoryScrollTarget.Top,
            onChoice = {},
            onFinish = {},
        )
    }
}
