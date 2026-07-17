package com.gigagochi.app.feature.create

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import com.gigagochi.app.core.model.PetDashboardState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.roundToInt

enum class CreateDebugState(val routeValue: String) {
    Initial("initial"),
    Name("name"),
    Custom("custom"),
    CustomIme("custom-ime"),
    Final("final"),
    Error("error"),
    Loader("loader"),
    Recovery("recovery");

    companion object {
        fun fromRouteValue(value: String?): CreateDebugState = entries.firstOrNull {
            it.routeValue == value
        } ?: Initial
    }
}

@Composable
fun CreatePetRoute(
    debugState: CreateDebugState = CreateDebugState.Initial,
    initialStateOverride: CreatePetState? = null,
    generationAdapter: PetGenerationAdapter = remember { FakePetGenerationAdapter() },
    readyTransitionDelayMillis: Long = 900L,
    reducedMotionOverride: Boolean? = null,
    finalizationCoordinator: CreateFinalizationCoordinator? = null,
    pendingCoordinator: CreatePendingCoordinator? = null,
    onPetPersisted: (PetDashboardState) -> Unit = {},
    onNavigateDashboard: () -> Unit,
) {
    val reducedMotion = reducedMotionOverride ?: rememberReducedMotionPreference()
    var state by remember(debugState, initialStateOverride) {
        mutableStateOf(initialStateOverride ?: debugFixtureState(debugState))
    }
    var showLoader by remember(debugState) { mutableStateOf(debugState == CreateDebugState.Loader) }
    var customShouldRequestIme by remember(debugState) {
        mutableStateOf(debugState == CreateDebugState.CustomIme)
    }
    var finalizationError by remember(debugState) { mutableStateOf<String?>(null) }
    var finalizationAttempt by remember(debugState) { mutableIntStateOf(0) }
    var pendingPersistenceAttempt by remember(debugState) { mutableIntStateOf(0) }
    var pendingPersistenceError by remember(debugState) { mutableStateOf<String?>(null) }
    var persistedRevision by remember(debugState) { mutableStateOf<CreatePendingRevision?>(null) }
    val persistenceGate = remember(debugState) { CreatePendingPersistenceGate() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val audioFeedback = remember(context) { CreationAudioFeedback(context.applicationContext) }

    DisposableEffect(audioFeedback, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> audioFeedback.resumeMusicIfTrusted()
                Lifecycle.Event.ON_STOP -> audioFeedback.pauseMusic()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            audioFeedback.release()
        }
    }

    val currentPendingRevision = state.pendingRevision()
    LaunchedEffect(currentPendingRevision, pendingPersistenceAttempt) {
        val coordinator = pendingCoordinator ?: return@LaunchedEffect
        val revision = currentPendingRevision ?: return@LaunchedEffect
        val attempt = persistenceGate.begin(revision)
        val stateToPersist = state
        when (coordinator.persist(stateToPersist)) {
            com.gigagochi.app.core.database.LocalOperationResult.Failure -> {
                if (persistenceGate.isLatest(attempt)) {
                    pendingPersistenceError = "Не удалось сохранить создание. Попробуйте ещё раз."
                }
            }
            is com.gigagochi.app.core.database.LocalOperationResult.Success -> {
                persistenceGate.completeIfLatest(attempt)?.let { completedRevision ->
                    pendingPersistenceError = null
                    persistedRevision = completedRevision
                }
            }
        }
    }

    LaunchedEffect(state.generationAttempt) {
        if (state.generationAttempt == 0 || state.pending == null) return@LaunchedEffect
        val readyState = if (pendingCoordinator == null) {
            state
        } else {
            snapshotFlow { state to persistedRevision }
                .first { (currentState, persisted) ->
                    currentState.generation is GenerationStatus.Running &&
                        currentState.pendingRevision() == persisted
                }
                .first
        }
        when (
            val result = if (pendingCoordinator == null) {
                executePetGeneration(generationAdapter, readyState.pending!!)
            } else {
                executePetGenerationIfCurrentRevisionPersisted(
                    generationAdapter,
                    readyState,
                    persistedRevision,
                ) ?: return@LaunchedEffect
            }
        ) {
            PetGenerationExecutionResult.Failure -> state = state.markGenerationFailed()
            is PetGenerationExecutionResult.Success -> state = state.markGenerationReady(result.pet)
        }
    }

    LaunchedEffect(state.canNavigate, finalizationAttempt) {
        if (!state.canNavigate || debugState == CreateDebugState.Loader) return@LaunchedEffect
        showLoader = true
        if (!reducedMotion) delay(readyTransitionDelayMillis)
        if (finalizationCoordinator != null) {
            when (val result = finalizationCoordinator.finalize(state)) {
                CreateFinalizationResult.Failure -> {
                    showLoader = false
                    finalizationError = "Не удалось сохранить питомца. Попробуйте ещё раз."
                    return@LaunchedEffect
                }
                is CreateFinalizationResult.Success -> onPetPersisted(result.pet)
            }
        }
        onNavigateDashboard()
    }

    if (showLoader) {
        PetCreatingStage(reducedMotion = reducedMotion)
        return
    }

    fun trustedAction() {
        audioFeedback.onTrustedInteraction()
    }

    CreatePetScreen(
        state = (pendingPersistenceError ?: finalizationError)?.let { message ->
            state.copy(generation = GenerationStatus.Error(message))
        } ?: state,
        reducedMotion = reducedMotion,
        requestCustomIme = customShouldRequestIme,
        onBackgroundPhaseComplete = { state = state.markTransitionComplete() },
        onAnswer = { answer ->
            trustedAction()
            audioFeedback.playButtonSound()
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            state = state.answer(answer, reducedMotion)
            customShouldRequestIme = false
        },
        onOpenCustom = {
            trustedAction()
            audioFeedback.playButtonSound()
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            state = state.openCustomInput()
            customShouldRequestIme = true
        },
        onCustomValueChange = { state = state.updateCustomValue(it) },
        onSubmitCustom = {
            trustedAction()
            audioFeedback.playButtonSound()
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            state = state.submitCustom(reducedMotion)
            customShouldRequestIme = false
        },
        onRetry = {
            trustedAction()
            audioFeedback.playButtonSound()
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (pendingPersistenceError != null) {
                pendingPersistenceError = null
                pendingPersistenceAttempt += 1
            } else if (finalizationError != null) {
                finalizationError = null
                finalizationAttempt += 1
            } else {
                state = state.retryGeneration()
            }
        },
    )
}

@Composable
fun CreatePetScreen(
    state: CreatePetState,
    reducedMotion: Boolean,
    requestCustomIme: Boolean,
    onBackgroundPhaseComplete: () -> Unit,
    onAnswer: (String) -> Unit,
    onOpenCustom: () -> Unit,
    onCustomValueChange: (String) -> Unit,
    onSubmitCustom: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hazeState = rememberHazeState()
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
        CreationReferenceFrame {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
            ) {
                CreationBackground(
                    phase = state.backgroundPhase,
                    reducedMotion = reducedMotion,
                    dimmed = state.isCustomInputOpen,
                    onTransitionComplete = onBackgroundPhaseComplete,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.video_filter_normal),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = .7f },
                )
            }

            when {
                state.isCustomInputOpen -> CustomInputContent(
                    state = state,
                    hazeState = hazeState,
                    reducedMotion = reducedMotion,
                    requestIme = requestCustomIme,
                    onValueChange = onCustomValueChange,
                    onSubmit = onSubmitCustom,
                )

                state.isFinal -> FinalCreationContent(
                    generation = state.generation,
                    hazeState = hazeState,
                    reducedMotion = reducedMotion,
                    onRetry = onRetry,
                )

                else -> QuestionContent(
                    question = requireNotNull(state.question),
                    hazeState = hazeState,
                    reducedMotion = reducedMotion,
                    onAnswer = onAnswer,
                    onOpenCustom = onOpenCustom,
                )
            }
        }
    }
}

@Composable
private fun CreationReferenceFrame(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val scale = max(maxWidth.value / 402f, maxHeight.value / 874f)
        Box(
            modifier = Modifier
                .requiredSize(402.dp, 874.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .clip(RoundedCornerShape(0.dp)),
            content = content,
        )
    }
}

@Composable
private fun QuestionContent(
    question: CreationQuestion,
    hazeState: HazeState,
    reducedMotion: Boolean,
    onAnswer: (String) -> Unit,
    onOpenCustom: () -> Unit,
) {
    Box(
        Modifier
            .requiredSize(402.dp, 519.dp)
            .offset(y = 355.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color(0x0029485E),
                    1f to Color(0xFF173A58),
                ),
            ),
    )
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .requiredSize(261.dp, 52.dp)
            .offset(x = 76.dp, y = 426.dp),
    ) {
        Text(
            text = question.title,
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center,
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(19.dp),
        modifier = Modifier
            .requiredWidth(402.dp)
            .offset(y = 515.dp),
    ) {
        (question.options + "Свой вариант").forEachIndexed { index, option ->
            TiltedGlassButton(
                label = option,
                index = index,
                delayMillis = index * 80,
                hazeState = hazeState,
                reducedMotion = reducedMotion,
                onClick = if (index == question.options.size) onOpenCustom else ({ onAnswer(option) }),
            )
        }
    }
}

@Composable
private fun CustomInputContent(
    state: CreatePetState,
    hazeState: HazeState,
    reducedMotion: Boolean,
    requestIme: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestIme) {
        if (requestIme) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .requiredSize(261.dp, 52.dp)
            .offset(x = 73.dp, y = 185.dp),
    ) {
        Text(
            text = state.question?.title.orEmpty(),
            color = Color.White.copy(alpha = .4f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center,
        )
    }

    BasicTextField(
        value = state.customValue,
        onValueChange = onValueChange,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = 39.45.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            if (state.canSubmitCustom) onSubmit()
        }),
        minLines = 3,
        maxLines = 4,
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (state.customValue.isEmpty()) {
                    Text(
                        text = "Свой вариант",
                        color = Color.White.copy(alpha = .4f),
                        fontSize = 39.45.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = OpenRundeFontFamily,
                        lineHeight = 40.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                innerTextField()
            }
        },
        modifier = Modifier
            .requiredWidth(341.dp)
            .heightIn(min = 120.dp, max = 160.dp)
            .offset(x = 31.dp, y = 261.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Enter &&
                    !event.isShiftPressed &&
                    state.canSubmitCustom
                ) {
                    onSubmit()
                    true
                } else {
                    false
                }
            }
            .semantics {
                contentDescription = if (state.step == 0) {
                    "Свой вариант персонажа"
                } else {
                    "Свой вариант: ${state.question?.title.orEmpty()}"
                }
            },
    )

    if (state.canSubmitCustom) {
        Box(
            modifier = Modifier
                .requiredWidth(402.dp)
                .offset(y = 444.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            TiltedGlassButton(
                label = "Далее",
                index = 1,
                delayMillis = 0,
                animateEntrance = false,
                hazeState = hazeState,
                reducedMotion = reducedMotion,
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun FinalCreationContent(
    generation: GenerationStatus,
    hazeState: HazeState,
    reducedMotion: Boolean,
    onRetry: () -> Unit,
) {
    val entrance = remember { Animatable(if (reducedMotion) 1f else .8f) }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) entrance.animateTo(1f, tween(300))
    }
    Box(
        Modifier
            .requiredSize(402.dp, 262.dp)
            .offset(y = 612.dp)
            .background(
                Brush.verticalGradient(
                    0f to Color(0x0029485E),
                    1f to Color(0xFF173A58),
                ),
            ),
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = entrance.value
                scaleY = entrance.value
                alpha = entrance.value.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(.5f, .75f)
            },
    ) {
        if (generation is GenerationStatus.Running) {
            ThinkingIndicator(
                reducedMotion = reducedMotion,
                modifier = Modifier.offset(x = 161.dp, y = 582.dp),
            )
        }
        Text(
            text = "Персонаж формируется...",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 26.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .requiredWidth(280.dp)
                .offset(x = 61.dp, y = 668.dp),
        )
        if (generation is GenerationStatus.Error) {
            Text(
                text = generation.message,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = OpenRundeFontFamily,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .requiredWidth(354.dp)
                    .offset(x = 24.dp, y = 724.dp),
            )
            Box(
                modifier = Modifier
                    .requiredWidth(402.dp)
                    .offset(y = 809.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                TiltedGlassButton(
                    label = "Попробовать снова",
                    index = 0,
                    delayMillis = 0,
                    animateEntrance = false,
                    hazeState = hazeState,
                    reducedMotion = reducedMotion,
                    onClick = onRetry,
                )
            }
        } else {
            Text(
                text = "Это может занять несколько минут. Можешь пока пойти по своим делам, мы тебя позовем",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OpenRundeFontFamily,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .requiredWidth(303.dp)
                    .offset(x = 49.5.dp, y = 741.dp),
            )
        }
    }
}

@Composable
private fun ThinkingIndicator(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            delay(200)
            frame = (frame + 1) % 3
        }
    }
    val resources = listOf(
        R.drawable.thinking_frame_1,
        R.drawable.thinking_frame_2,
        R.drawable.thinking_frame_3,
    )
    Image(
        painter = painterResource(resources[if (reducedMotion) 0 else frame]),
        contentDescription = "Персонаж думает",
        contentScale = ContentScale.FillBounds,
        modifier = modifier.requiredSize(80.dp, 55.5.dp),
    )
}

@Composable
private fun TiltedGlassButton(
    label: String,
    index: Int,
    delayMillis: Int,
    hazeState: HazeState,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    animateEntrance: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    val entrance = remember(label) {
        Animatable(if (animateEntrance && !reducedMotion) .6f else 1f)
    }
    LaunchedEffect(label, reducedMotion, animateEntrance) {
        if (animateEntrance && !reducedMotion) {
            delay(delayMillis.toLong())
            entrance.animateTo(
                targetValue = 1f,
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
        label = "tilted-button-press",
    )
    val rotation = if (index % 2 == 0) -2f else 2f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(62.dp)
            .graphicsLayer {
                rotationZ = rotation
                scaleX = entrance.value * pressScale
                scaleY = entrance.value * pressScale
                alpha = ((entrance.value - .6f) / .4f).coerceIn(0f, 1f)
            }
            .clip(CreateGlassContract.Shape)
            .hazeEffect(state = hazeState, style = CreateGlassContract.Style)
            .innerShadow(CreateGlassContract.Shape, CreateGlassContract.BottomInset)
            .pointerInput(label) {
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
                onClick {
                    onClick()
                    true
                }
            }
            .padding(start = 17.dp, top = 14.dp, end = 17.dp, bottom = 16.dp),
    ) {
        Text(
            text = label,
            color = Color(0xFF05152C),
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 30.sp,
            letterSpacing = (-.45).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CreationBackground(
    phase: CreationBackgroundPhase,
    reducedMotion: Boolean,
    dimmed: Boolean,
    onTransitionComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current || reducedMotion) {
        val drawable = if (phase == CreationBackgroundPhase.Initial) {
            R.drawable.clouds_empty
        } else {
            R.drawable.clouds_formed
        }
        Image(
            painter = painterResource(drawable),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.graphicsLayer { alpha = if (dimmed) .8f else 1f },
        )
        return
    }

    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showPoster by remember(phase) { mutableStateOf(true) }
    val player = remember(context) { ExoPlayer.Builder(context).build().apply { volume = 0f } }

    LaunchedEffect(player, phase) {
        val (startUs, endUs) = when (phase) {
            CreationBackgroundPhase.Initial -> CreationMediaContract.InitialStartUs to CreationMediaContract.InitialEndUs
            CreationBackgroundPhase.Transition -> CreationMediaContract.TransitionStartUs to CreationMediaContract.TransitionEndUs
            CreationBackgroundPhase.Formed -> CreationMediaContract.FormedStartUs to CreationMediaContract.FormedEndUs
        }
        val source = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(MediaItem.fromUri(Uri.parse("asset:///media/clouds_creation_timeline.mp4")))
        val clipped = ClippingMediaSource.Builder(source)
            .setStartPositionUs(startUs)
            .setEndPositionUs(endUs)
            .build()
        player.repeatMode = if (phase == CreationBackgroundPhase.Transition) {
            Player.REPEAT_MODE_OFF
        } else {
            Player.REPEAT_MODE_ONE
        }
        showPoster = true
        player.setMediaSource(clipped)
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player, lifecycle, phase) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showPoster = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && phase == CreationBackgroundPhase.Transition) {
                    onTransitionComplete()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showPoster = true
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(modifier.graphicsLayer { alpha = if (dimmed) .8f else 1f }) {
        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(R.layout.view_creation_player, null, false) as PlayerView).apply {
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
        if (showPoster) {
            Image(
                painter = painterResource(
                    if (phase == CreationBackgroundPhase.Initial) R.drawable.clouds_empty else R.drawable.clouds_formed,
                ),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun PetCreatingStage(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    var mosaicFrame by remember { mutableIntStateOf(0) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (true) {
            delay(720)
            mosaicFrame = (mosaicFrame + 1) % 3
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Создаем друга" },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .offset(y = (-98).dp),
        ) {
            Box(
                Modifier
                    .size(280.dp)
                    .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0A0A0A)),
            ) {
                if (reducedMotion) {
                    Image(
                        painter = painterResource(R.drawable.main_pet),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val frames = rememberCreationMosaicFrames()
                    Crossfade(
                        targetState = mosaicFrame,
                        animationSpec = tween(260),
                        label = "creation-pixel-mosaic",
                    ) { frame ->
                        Canvas(Modifier.fillMaxSize()) {
                            val image = frames[frame]
                            drawImage(
                                image = image,
                                srcSize = IntSize(image.width, image.height),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                filterQuality = FilterQuality.None,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = "Создаем друга",
                color = Color.White.copy(alpha = .76f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = OpenRundeFontFamily,
                lineHeight = 17.sp,
            )
        }
        Text(
            text = "Мы пришлем уведомление, когда персонаж будет готов",
            color = Color.White.copy(alpha = .5f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = OpenRundeFontFamily,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun rememberCreationMosaicFrames(): List<ImageBitmap> {
    val context = LocalContext.current
    return remember(context) {
        listOf(
            R.drawable.main_pet to 18,
            R.drawable.pet to 24,
            R.drawable.main_pet to 21,
        ).map { (resourceId, edge) ->
            val source = BitmapFactory.decodeResource(context.resources, resourceId)
            Bitmap.createScaledBitmap(source, edge, edge, false).also {
                if (it !== source) source.recycle()
            }.asImageBitmap()
        }
    }
}

private class CreationAudioFeedback(context: android.content.Context) {
    private val music = MediaPlayer.create(context, R.raw.hopeful_piano_loop)?.apply {
        isLooping = true
        setVolume(CreationMediaContract.MusicVolume, CreationMediaContract.MusicVolume)
    }
    private val sounds = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val buttonSound = sounds.load(context, R.raw.creation_button_plop, 1)
    private var trusted = false

    fun onTrustedInteraction() {
        trusted = true
        if (music?.isPlaying == false) music.start()
    }

    fun playButtonSound() {
        sounds.play(buttonSound, 1f, 1f, 1, 0, 1f)
    }

    fun pauseMusic() {
        if (music?.isPlaying == true) music.pause()
    }

    fun resumeMusicIfTrusted() {
        if (trusted && music?.isPlaying == false) music.start()
    }

    fun release() {
        music?.release()
        sounds.release()
    }
}

@Composable
private fun rememberReducedMotionPreference(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

private fun debugFixtureState(debugState: CreateDebugState): CreatePetState = when (debugState) {
    CreateDebugState.Initial -> CreatePetState()
    CreateDebugState.Name -> recoveredQuestionState(step = 1)
    CreateDebugState.Custom -> CreatePetState().openCustomInput()
    CreateDebugState.CustomIme -> CreatePetState().openCustomInput()
    CreateDebugState.Final -> recoveredCreatePetState("Ледяного дракона").copy(generationAttempt = 0)
    CreateDebugState.Error -> recoveredCreatePetState("Ледяного дракона").markGenerationFailed()
    CreateDebugState.Loader -> CreatePetState()
    CreateDebugState.Recovery -> recoveredCreatePetState("Ледяного дракона")
}

private fun recoveredQuestionState(step: Int): CreatePetState = CreatePetState(
    step = step,
    answers = listOf("Ледяного дракона"),
    description = "Ледяного дракона",
    backgroundPhase = CreationBackgroundPhase.Formed,
    generation = GenerationStatus.Running,
    generationAttempt = 0,
    pending = PendingPetGeneration(
        petId = "debug-preview-pet",
        description = "Ледяного дракона",
        requestKey = "debug-name",
    ),
)

@Preview(widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun CreateInitialPreview() {
    GigagochiTheme {
        CreatePetScreen(
            state = CreatePetState(),
            reducedMotion = true,
            requestCustomIme = false,
            onBackgroundPhaseComplete = {},
            onAnswer = {},
            onOpenCustom = {},
            onCustomValueChange = {},
            onSubmitCustom = {},
            onRetry = {},
        )
    }
}

@Preview(widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun CreateFinalPreview() {
    GigagochiTheme {
        CreatePetScreen(
            state = recoveredCreatePetState("Ледяного дракона"),
            reducedMotion = true,
            requestCustomIme = false,
            onBackgroundPhaseComplete = {},
            onAnswer = {},
            onOpenCustom = {},
            onCustomValueChange = {},
            onSubmitCustom = {},
            onRetry = {},
        )
    }
}
