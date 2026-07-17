package com.gigagochi.app.feature.travel

import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

private val DemoTravelPet = TravelEntryPet(
    petId = "debug-test-pet",
    name = "Без имени",
)

enum class TravelDebugState(val routeValue: String) {
    Loading("loading"),
    Picker("picker"),
    CustomEmpty("custom-empty"),
    CustomFilled("custom-filled"),
    CustomIme("custom-ime"),
    StartPending("start-pending"),
    Error("error"),
    SuggestionsError("suggestions-error"),
    StoryQuestionPoster("story-question-poster"),
    StoryQuestionVideo("story-question-video"),
    StoryChoicePending("story-choice-pending"),
    StoryResultPoster("story-result-poster"),
    StoryResultVideo("story-result-video"),
    StoryQuestionScrolled("story-question-scrolled"),
    StoryResultScrolled("story-result-scrolled"),
    StoryReducedMotion("story-reduced-motion");

    val freezesAsync: Boolean
        get() = this !in setOf(
            StoryQuestionPoster,
            StoryQuestionVideo,
            StoryQuestionScrolled,
            StoryReducedMotion,
        )

    companion object {
        fun fromRouteValue(value: String?): TravelDebugState? = entries.firstOrNull {
            it.routeValue == value
        }
    }
}

internal object TravelEntryMediaTestProbe {
    var createdPlayerCount: Int = 0
        private set

    fun reset() {
        createdPlayerCount = 0
    }

    fun recordPlayerCreation() {
        createdPlayerCount += 1
    }
}

@Composable
fun TravelEntryRoute(
    debugState: TravelDebugState? = null,
    initialPet: TravelEntryPet = DemoTravelPet,
    initialAppliedTravelIds: Set<String> = emptySet(),
    receiptCoordinator: StoryReceiptCoordinator? = null,
    initialSuggestionsRequestKey: String? = null,
    resultConsumptionAdapter: TravelResultConsumptionAdapter = ImmediateTravelResultConsumptionAdapter,
    mediaUrlPolicy: com.gigagochi.app.core.network.StaticMediaUrlPolicy? = null,
    requestKeyFactory: ((String) -> String)? = null,
    suggestionsAdapter: TravelSuggestionsAdapter = remember { FakeTravelSuggestionsAdapter() },
    startAdapter: TravelStartAdapter = remember { FakeTravelStartAdapter() },
    storyChoiceAdapter: TravelStoryChoiceAdapter = remember {
        FakeOnboardingTravelStoryAdapter()
    },
    reducedMotionOverride: Boolean? = null,
    onNavigateDashboard: (TravelExitReason) -> Unit,
) {
    val systemReducedMotion = rememberTravelReducedMotionPreference()
    val reducedMotion = when {
        debugState == TravelDebugState.StoryReducedMotion -> true
        reducedMotionOverride != null -> reducedMotionOverride
        else -> systemReducedMotion
    }
    var state by remember(debugState, initialPet, initialAppliedTravelIds) {
        val initial = when {
            debugState != null -> travelDebugFixture(debugState, initialPet)
            else -> initialTravelEntryState(
                initialPet,
                initialSuggestionsRequestKey
                    ?: requestKeyFactory?.invoke("suggestions")
                    ?: "suggestions-initial",
            )
        }
        mutableStateOf(initial.copy(appliedStoryTravelIds = initial.appliedStoryTravelIds + initialAppliedTravelIds))
    }
    var requestSequence by remember { mutableIntStateOf(0) }
    var requestIme by remember(debugState) {
        mutableStateOf(debugState == TravelDebugState.CustomIme)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val freezesAsync = debugState?.freezesAsync == true
    val lifecycleOwner = LocalLifecycleOwner.current
    val routeScope = rememberCoroutineScope()
    var consumingResult by remember { mutableStateOf(false) }

    LaunchedEffect(state.activeSuggestionsRequestKey, freezesAsync) {
        val requestKey = state.activeSuggestionsRequestKey ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (val result = executeTravelAdapter { suggestionsAdapter.load(state.pet, requestKey) }) {
            is TravelAdapterResult.Success -> {
                state = reduceTravelEntry(
                    state,
                    TravelEntryEvent.SuggestionsLoaded(requestKey, result.value),
                )
            }
            TravelAdapterResult.Failure -> {
                state = reduceTravelEntry(state, TravelEntryEvent.SuggestionsFailed(requestKey))
            }
            }
        }
    }

    LaunchedEffect(state.activeStart?.requestKey, freezesAsync) {
        val request = state.activeStart ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (val result = executeTravelAdapter { startAdapter.start(request) }) {
            is TravelAdapterResult.Success -> {
                val outcome = result.value
                when (outcome) {
                    is TravelStartOutcome.Accepted -> state = reduceTravelEntry(
                        state,
                        TravelEntryEvent.StartAccepted(request.requestKey, outcome.story),
                    )
                }
            }
            TravelAdapterResult.Failure -> {
                state = reduceTravelEntry(state, TravelEntryEvent.StartFailed(request.requestKey))
            }
            }
        }
    }

    LaunchedEffect(state.activeChoice?.requestKey, freezesAsync) {
        val request = state.activeChoice ?: return@LaunchedEffect
        val story = state.story ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (val adapterResult = executeTravelAdapter { storyChoiceAdapter.choose(request, story) }) {
            is TravelAdapterResult.Success -> {
                val outcome = adapterResult.value
                val result = outcome.result
                val coordinator = receiptCoordinator
                if (outcome.committedExperience != null) {
                    state = reduceTravelEntry(
                        state,
                        TravelEntryEvent.StoryChoiceResolved(
                            request.requestKey,
                            result,
                            outcome.committedExperience,
                            outcome.committedTravelIds,
                        ),
                    )
                } else if (coordinator == null) {
                    state = reduceTravelEntry(
                        state,
                        TravelEntryEvent.StoryChoiceResolved(request.requestKey, result),
                    )
                } else {
                    when (val commit = coordinator.commit(request, result)) {
                        is StoryReceiptCommitResult.Committed -> state = reduceTravelEntry(
                            state,
                            TravelEntryEvent.StoryChoiceResolved(
                                request.requestKey,
                                result,
                                commit.experience,
                                commit.appliedTravelIds,
                            ),
                        )
                        StoryReceiptCommitResult.Failure -> state = reduceTravelEntry(
                            state,
                            TravelEntryEvent.StoryChoiceFailed(request.requestKey),
                        )
                    }
                }
            }
            TravelAdapterResult.Failure -> {
                state = reduceTravelEntry(
                    state,
                    TravelEntryEvent.StoryChoiceFailed(request.requestKey),
                )
            }
            }
        }
    }

    LaunchedEffect(state.exitRequested) {
        if (state.exitRequested) {
            val reason = state.exitReason ?: TravelExitReason.UserDismissed
            onNavigateDashboard(reason)
        }
    }

    fun dispatch(event: TravelEntryEvent) {
        state = reduceTravelEntry(state, event)
    }

    BackHandler {
        keyboard?.hide()
        requestIme = false
        dispatch(TravelEntryEvent.Back)
    }

    if (state.phase in StoryTravelPhases) {
        InteractiveTravelStoryScreen(
            state = state,
            reducedMotion = reducedMotion,
            forcePoster = debugState in setOf(
                TravelDebugState.StoryQuestionPoster,
                TravelDebugState.StoryResultPoster,
                TravelDebugState.StoryReducedMotion,
            ),
            scrollTarget = when (debugState) {
                TravelDebugState.StoryQuestionScrolled -> StoryScrollTarget.Answers
                TravelDebugState.StoryResultScrolled -> StoryScrollTarget.FinishAction
                else -> StoryScrollTarget.Top
            },
            mediaUrlPolicy = mediaUrlPolicy,
            onChoice = { choice ->
                requestSequence += 1
                dispatch(
                    TravelEntryEvent.SubmitStoryChoice(
                        choice = choice,
                        requestKey = requestKeyFactory?.invoke("story-choice")
                            ?: "story-choice-$requestSequence",
                    ),
                )
            },
            onFinish = {
                val result = state.storyResult
                if (result != null && !consumingResult) {
                    consumingResult = true
                    routeScope.launch {
                        val consumed = executeTravelAdapter { resultConsumptionAdapter.consume(result) }
                        consumingResult = false
                        if (consumed is TravelAdapterResult.Success && consumed.value) {
                            dispatch(TravelEntryEvent.FinishStory)
                        } else {
                            dispatch(TravelEntryEvent.ResultConsumptionFailed)
                        }
                    }
                }
            },
        )
    } else {
        TravelEntryScreen(
            state = state,
            reducedMotion = reducedMotion,
            requestIme = requestIme,
            onOpenCustomDestination = {
                requestIme = true
                dispatch(TravelEntryEvent.OpenCustomDestination)
            },
            onDraftChange = { dispatch(TravelEntryEvent.UpdateCustomDraft(it)) },
            onSelectSuggestion = {
                requestSequence += 1
                dispatch(
                    TravelEntryEvent.SelectSuggestion(
                        it,
                        requestKeyFactory?.invoke("travel-start")
                            ?: "travel-start-$requestSequence",
                    ),
                )
            },
            onSubmitCustomDestination = {
                requestSequence += 1
                dispatch(
                    TravelEntryEvent.SubmitCustomDestination(
                        requestKeyFactory?.invoke("travel-start")
                            ?: "travel-start-$requestSequence",
                    ),
                )
            },
        )
    }
}

@Composable
fun TravelEntryScreen(
    state: TravelEntryState,
    reducedMotion: Boolean,
    requestIme: Boolean,
    onOpenCustomDestination: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSelectSuggestion: (String) -> Unit,
    onSubmitCustomDestination: () -> Unit,
) {
    TravelReferenceFrame {
        TravelEntryBackground(
            reducedMotion = reducedMotion,
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

        if (state.error != null && state.phase !in listOf(
                TravelEntryPhase.LoadingSuggestions,
                TravelEntryPhase.StartPending,
            )
        ) {
            TravelErrorNotice(
                message = state.error,
                modifier = Modifier.offset(x = 21.dp, y = 58.dp),
            )
        }

        when (state.phase) {
            TravelEntryPhase.LoadingSuggestions,
            TravelEntryPhase.StartPending,
            -> TravelThinkingIndicator(
                freezeFrame = reducedMotion,
                modifier = Modifier.offset(x = 161.dp, y = 567.1.dp),
            )

            TravelEntryPhase.Picker -> TravelDestinationPicker(
                petName = state.pet.name,
                suggestions = state.suggestions,
                reducedMotion = reducedMotion,
                onSelectSuggestion = onSelectSuggestion,
                onOpenCustomDestination = onOpenCustomDestination,
            )

            TravelEntryPhase.CustomDestination -> TravelCustomDestinationForm(
                petName = state.pet.name,
                draft = state.customDraft,
                requestIme = requestIme,
                reducedMotion = reducedMotion,
                onDraftChange = onDraftChange,
                onSubmit = onSubmitCustomDestination,
            )

            TravelEntryPhase.StoryQuestion,
            TravelEntryPhase.ChoicePending,
            TravelEntryPhase.StoryResult,
            TravelEntryPhase.Finished,
            -> Unit
        }
    }
}

@Composable
private fun BoxScope.TravelDestinationPicker(
    petName: String,
    suggestions: List<String>,
    reducedMotion: Boolean,
    onSelectSuggestion: (String) -> Unit,
    onOpenCustomDestination: () -> Unit,
) {
    Text(
        text = "Куда отправим $petName?",
        color = Color.White,
        fontFamily = OpenRundeFontFamily,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 31.5.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier
            .requiredSize(261.dp, 63.dp)
            .offset(x = 70.5.dp, y = 405.047.dp),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .requiredWidth(360.dp)
            .offset(x = 21.dp, y = 499.047.dp),
    ) {
        suggestions.forEach { suggestion ->
            TravelEntryButton(
                label = suggestion,
                secondary = false,
                enabled = true,
                reducedMotion = reducedMotion,
                onClick = { onSelectSuggestion(suggestion) },
            )
        }
        TravelEntryButton(
            label = "Свой вариант",
            secondary = true,
            enabled = true,
            reducedMotion = reducedMotion,
            onClick = onOpenCustomDestination,
        )
    }
}

@Composable
private fun BoxScope.TravelCustomDestinationForm(
    petName: String,
    draft: String,
    requestIme: Boolean,
    reducedMotion: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestIme) {
        if (requestIme) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Text(
        text = "Куда отправим $petName?",
        color = Color.White,
        fontFamily = OpenRundeFontFamily,
        fontSize = 26.sp,
        fontWeight = FontWeight.Black,
        lineHeight = 30.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier
            .requiredSize(348.dp, 60.dp)
            .offset(x = 27.dp, y = 221.992.dp),
    )

    TravelDestinationField(
        value = draft,
        onValueChange = onDraftChange,
        onSubmit = onSubmit,
        focusRequester = focusRequester,
        modifier = Modifier.offset(x = 94.5.dp, y = 275.305.dp),
    )

    TravelEntryButton(
        label = "Путешествие",
        secondary = false,
        enabled = draft.trim().isNotEmpty(),
        reducedMotion = reducedMotion,
        fixedWidth = 220.dp,
        onClick = onSubmit,
        modifier = Modifier.offset(x = 91.dp, y = 451.852.dp),
    )
}

@Composable
private fun TravelDestinationField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Color.White,
            fontFamily = OpenRundeFontFamily,
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 28.sp,
            letterSpacing = (-.45).sp,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = { if (value.trim().isNotEmpty()) onSubmit() },
        ),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.Center) {
                if (value.isEmpty()) {
                    Text(
                        text = "Свой вариант",
                        color = Color.White.copy(alpha = .72f),
                        fontFamily = OpenRundeFontFamily,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-.45).sp,
                        textAlign = TextAlign.Center,
                    )
                }
                innerTextField()
            }
        },
        modifier = modifier
            .requiredSize(213.dp, 62.dp)
            .clip(shape)
            .background(Color(0xF0161616))
            .innerShadow(
                shape,
                Shadow(
                    radius = 8.dp,
                    color = Color(0x66010101),
                    offset = DpOffset(0.dp, (-3.768).dp),
                ),
            )
            .focusRequester(focusRequester)
            .semantics {
                contentDescription = "Свой вариант путешествия"
            }
            .padding(horizontal = 24.dp),
    )
}

@Composable
private fun TravelEntryButton(
    label: String,
    secondary: Boolean,
    enabled: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fixedWidth: androidx.compose.ui.unit.Dp? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) .97f else 1f,
        animationSpec = tween(if (reducedMotion) 0 else 140),
        label = "travel-entry-button-press",
    )
    val shape = RoundedCornerShape(24.dp)
    val sizeModifier = if (fixedWidth == null) {
        Modifier.widthIn(max = 360.dp)
    } else {
        Modifier.requiredWidth(fixedWidth)
    }
    val color = if (secondary) Color.White else Color(0xFF080808)
    val background = if (secondary) Color(0xF0161616) else Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(sizeModifier)
            .height(62.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (enabled) 1f else .65f
            }
            .shadow(
                elevation = if (secondary) 0.dp else 12.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = .16f),
                spotColor = Color.Black.copy(alpha = .16f),
            )
            .clip(shape)
            .background(background)
            .then(
                if (secondary) {
                    Modifier.innerShadow(
                        shape,
                        Shadow(
                            radius = 8.dp,
                            color = Color(0x66010101),
                            offset = DpOffset(0.dp, (-3.768).dp),
                        ),
                    )
                } else {
                    Modifier
                        .innerShadow(
                            shape,
                            Shadow(
                                radius = .1.dp,
                                color = Color.White.copy(alpha = .7f),
                                offset = DpOffset(0.dp, 2.dp),
                            ),
                        )
                        .innerShadow(
                            shape,
                            Shadow(
                                radius = 9.dp,
                                color = Color.Black.copy(alpha = .25f),
                                offset = DpOffset(0.dp, (-4).dp),
                            ),
                        )
                },
            )
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
            .padding(PaddingValues(start = 25.dp, top = 15.dp, end = 25.dp, bottom = 17.dp)),
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = OpenRundeFontFamily,
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 28.sp,
            letterSpacing = (-.45).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun TravelErrorNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message,
        color = Color.White,
        fontFamily = OpenRundeFontFamily,
        fontSize = 13.sp,
        lineHeight = 16.25.sp,
        modifier = modifier
            .requiredWidth(360.dp)
            .background(Color(0xC2160A0A), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(14.dp))
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = message
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun TravelThinkingIndicator(
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
private fun TravelReferenceFrame(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.TopCenter,
    ) {
        val scale = max(maxWidth.value / 402f, maxHeight.value / 874f)
        Box(
            modifier = Modifier
                .requiredSize(402.dp, 874.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(.5f, 0f)
                },
            content = content,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TravelEntryBackground(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current || reducedMotion) {
        Image(
            painter = painterResource(R.drawable.travel_entry_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showPoster by remember { mutableStateOf(true) }
    val player = remember(context) {
        TravelEntryMediaTestProbe.recordPlayerCreation()
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse("asset:///media/travel-entry-bg.mp4")))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showPoster = false
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
            player.release()
        }
    }

    Box(modifier) {
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
        if (showPoster) {
            Image(
                painter = painterResource(R.drawable.travel_entry_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun rememberTravelReducedMotionPreference(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

private fun travelDebugFixture(
    debugState: TravelDebugState?,
    pet: TravelEntryPet,
): TravelEntryState = when (debugState) {
    null -> initialTravelEntryState(pet)
    TravelDebugState.Loading -> initialTravelEntryState(pet, "debug-loading")
    TravelDebugState.Picker -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.Picker,
        suggestions = DeterministicTravelDestinations,
    )
    TravelDebugState.CustomEmpty -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.CustomDestination,
        suggestions = DeterministicTravelDestinations,
    )
    TravelDebugState.CustomFilled,
    TravelDebugState.CustomIme,
    -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.CustomDestination,
        suggestions = DeterministicTravelDestinations,
        customDraft = TravelCustomDestination,
    )
    TravelDebugState.StartPending -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.StartPending,
        suggestions = DeterministicTravelDestinations,
        customDraft = TravelCustomDestination,
        activeStart = PendingTravelStart(
            petId = pet.petId,
            requestKey = "debug-start-pending",
            destination = TravelCustomDestination,
            origin = TravelStartOrigin.CustomDestination,
        ),
    )
    TravelDebugState.Error -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.CustomDestination,
        suggestions = DeterministicTravelDestinations,
        customDraft = TravelCustomDestination,
        error = TravelStartFailureMessage,
    )
    TravelDebugState.SuggestionsError -> TravelEntryState(
        pet = pet,
        phase = TravelEntryPhase.Picker,
        suggestions = DeterministicTravelFallbackDestinations,
        error = TravelSuggestionsFailureMessage,
    )
    TravelDebugState.StoryQuestionPoster,
    TravelDebugState.StoryQuestionVideo,
    TravelDebugState.StoryQuestionScrolled,
    TravelDebugState.StoryReducedMotion,
    -> onboardingStoryState(pet, TravelEntryPhase.StoryQuestion)
    TravelDebugState.StoryChoicePending -> onboardingStoryState(
        pet = pet,
        phase = TravelEntryPhase.ChoicePending,
    ).let { fixture ->
        fixture.copy(
            activeChoice = PendingTravelStoryChoice(
                travelId = fixture.story!!.travelId,
                requestKey = "debug-story-choice-pending",
                choice = OnboardingBatCorrectChoice,
            ),
        )
    }
    TravelDebugState.StoryResultPoster,
    TravelDebugState.StoryResultVideo,
    TravelDebugState.StoryResultScrolled,
    -> onboardingStoryState(pet, TravelEntryPhase.StoryResult)
}

private fun onboardingStoryState(
    pet: TravelEntryPet,
    phase: TravelEntryPhase,
): TravelEntryState {
    val story = onboardingBatStory(pet.petId)
    val result = onboardingBatStoryResult(story, "debug-story-result")
    return TravelEntryState(
        pet = if (phase == TravelEntryPhase.StoryResult) {
            pet.copy(experience = pet.experience + result.experienceGained)
        } else {
            pet
        },
        phase = phase,
        story = story,
        storyResult = result.takeIf { phase == TravelEntryPhase.StoryResult },
        appliedStoryTravelIds = if (phase == TravelEntryPhase.StoryResult) {
            setOf(story.travelId)
        } else {
            emptySet()
        },
    )
}

internal val StoryTravelPhases = setOf(
    TravelEntryPhase.StoryQuestion,
    TravelEntryPhase.ChoicePending,
    TravelEntryPhase.StoryResult,
    TravelEntryPhase.Finished,
)

@Preview(widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun TravelEntryPickerPreview() {
    GigagochiTheme {
        TravelEntryScreen(
            state = travelDebugFixture(TravelDebugState.Picker, DemoTravelPet),
            reducedMotion = true,
            requestIme = false,
            onOpenCustomDestination = {},
            onDraftChange = {},
            onSelectSuggestion = {},
            onSubmitCustomDestination = {},
        )
    }
}
