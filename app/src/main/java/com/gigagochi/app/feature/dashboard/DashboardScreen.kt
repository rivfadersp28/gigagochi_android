package com.gigagochi.app.feature.dashboard

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.os.SystemClock
import android.view.TextureView
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSpec
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.ContextualGlassNavigation
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.database.LocalTravelVideoAsset
import com.gigagochi.app.core.network.SecureStaticMediaDataSource
import com.gigagochi.app.core.network.StaticImageMaxBytes
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

private val DemoPet = PetDashboardState(
    petId = "debug-test-pet",
    assetSetId = "debug-test-pet-seedance-forest-mouse-v1",
    description = "Ледяной дракон",
    name = "Без имени",
    stage = "baby",
    stageLabel = "Малыш",
    mood = "idle",
    experience = 0,
    hunger = 100,
    happiness = 100,
    energy = 100,
    message = "Как тебя зовут?",
    firstSessionActive = false,
)

internal object DashboardVideoTestProbe {
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
fun DashboardRoute(
    debugState: DashboardDebugState = DashboardDebugState.Idle,
    recoveryRevision: Int = 0,
    initialPet: PetDashboardState = DemoPet,
    initialPendingOutfit: PendingOutfitGeneration? = null,
    initialPendingTravel: PendingTravelGeneration? = null,
    onPetChanged: suspend (PetDashboardState) -> Boolean = { true },
    chatAdapter: DashboardChatAdapter = remember { FakeDashboardChatAdapter() },
    feedAdapter: DashboardFeedAdapter = remember { FakeDashboardFeedAdapter() },
    outfitAdapter: DashboardOutfitAdapter = remember { FakeDashboardOutfitAdapter() },
    travelAdapter: DashboardTravelAdapter = remember { FakeDashboardTravelAdapter() },
    durableOperations: DashboardDurableOperations? = null,
    requestKeyFactory: ((String) -> String)? = null,
    requestImeOverride: Boolean? = null,
    travelPresentation: LocalTravelVideoAsset? = null,
    mediaUrlPolicy: StaticMediaUrlPolicy? = null,
    reducedMotionOverride: Boolean? = null,
) {
    var state by remember(debugState, initialPet, recoveryRevision) {
        val base = dashboardDebugFixture(debugState, initialPet)
        mutableStateOf(
            if (debugState == DashboardDebugState.Idle) {
                base.copy(
                    pendingOutfit = initialPendingOutfit,
                    chargedOutfitRequestKeys = initialPendingOutfit?.let {
                        setOf(it.requestKey)
                    }.orEmpty(),
                    pendingTravel = initialPendingTravel,
                    queuedTravelRequestKeys = initialPendingTravel?.let {
                        setOf(it.requestKey)
                    }.orEmpty(),
                    transientReply = initialPendingOutfit?.let {
                        DashboardReply(it.requestKey, outfitQueuedReply(it.displayItem))
                    } ?: initialPendingTravel?.let {
                        DashboardReply(it.requestKey, travelQueuedReply(it.prompt))
                    },
                )
            } else base,
        )
    }
    var lastPersistedPet by remember(initialPet) { mutableStateOf(initialPet) }
    val persistenceCoordinator = remember(onPetChanged) {
        DashboardPetPersistenceCoordinator(onPetChanged)
    }
    var requestSequence by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val feedAudio = remember(context) { DashboardFeedAudio(context.applicationContext) }
    val petTapAudio = remember(context) { DashboardPetTapAudio(context.applicationContext) }

    fun dispatch(event: DashboardEvent) {
        state = reduceDashboard(state, event)
    }

    fun nextRequestKey(prefix: String): String {
        requestKeyFactory?.let { return it(prefix) }
        requestSequence += 1
        return "$prefix-$requestSequence"
    }

    fun closeMode() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        dispatch(DashboardEvent.CloseMode)
    }

    DisposableEffect(feedAudio, petTapAudio) {
        onDispose {
            feedAudio.release()
            petTapAudio.release()
        }
    }

    LaunchedEffect(state.pet) {
        if (state.pet != lastPersistedPet) {
            if (persistenceCoordinator.persist(state.pet)) lastPersistedPet = state.pet
        }
    }

    BackHandler(enabled = state.mode != DashboardMode.Idle) { closeMode() }

    LaunchedEffect(state.activeChat?.requestKey) {
        val request = state.activeChat ?: return@LaunchedEffect
        if (debugState.freezesAsync) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        when (val result = executeDashboardAdapter { chatAdapter.reply(request, state.pet) }) {
            is DashboardAdapterResult.Success -> {
                delay(remainingThinkingDelayMillis(startedAt, SystemClock.elapsedRealtime()))
                dispatch(DashboardEvent.ChatSucceeded(request.requestKey, result.value))
            }
            DashboardAdapterResult.Failure -> {
                delay(remainingThinkingDelayMillis(startedAt, SystemClock.elapsedRealtime()))
                dispatch(DashboardEvent.ChatFailed(request.requestKey))
            }
        }
    }

    LaunchedEffect(state.activeFeed?.requestKey) {
        val request = state.activeFeed ?: return@LaunchedEffect
        if (debugState.freezesAsync) return@LaunchedEffect
        feedAudio.play(request.audioIndex)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val startedAt = SystemClock.elapsedRealtime()
        when (val result = executeDashboardAdapter { feedAdapter.reply(request, state.pet) }) {
            is DashboardAdapterResult.Success -> {
                delay(remainingThinkingDelayMillis(startedAt, SystemClock.elapsedRealtime()))
                dispatch(DashboardEvent.FeedSucceeded(request.requestKey, result.value))
            }
            DashboardAdapterResult.Failure -> {
                delay(remainingThinkingDelayMillis(startedAt, SystemClock.elapsedRealtime()))
                dispatch(DashboardEvent.FeedFailed(request.requestKey))
            }
        }
    }

    LaunchedEffect(state.feedToken.food, state.feedToken.phase) {
        if (debugState.freezesMotion) return@LaunchedEffect
        val food = state.feedToken.food ?: return@LaunchedEffect
        when (state.feedToken.phase) {
            FoodTokenPhase.Consuming -> {
                delay(180)
                dispatch(DashboardEvent.FoodConsumeFinished(food))
            }
            FoodTokenPhase.Reappearing -> {
                delay(220)
                dispatch(DashboardEvent.FoodReappearFinished(food))
            }
            else -> Unit
        }
    }

    LaunchedEffect(state.activeOutfit?.requestKey) {
        val request = state.activeOutfit ?: return@LaunchedEffect
        if (debugState.freezesAsync) return@LaunchedEffect
        val durable = durableOperations
        if (durable != null) {
            when (val result = durable.acceptOutfit(request, state.pet)) {
                is DurableOutfitResult.Queued -> dispatch(
                    DashboardEvent.OutfitQueued(
                        request.requestKey,
                        result.pending,
                        result.acceptedPet,
                        outfitQueuedReply(result.pending.displayItem),
                    ),
                )
                is DurableOutfitResult.PersistedButQueueFailed -> dispatch(
                    DashboardEvent.OutfitPersistedButQueueFailed(
                        request.requestKey,
                        result.pending,
                        result.acceptedPet,
                    ),
                )
                DurableOutfitResult.Failure,
                DurableOutfitResult.Unavailable,
                -> dispatch(DashboardEvent.OutfitFailed(request.requestKey))
            }
        } else {
            when (val result = executeDashboardAdapter { outfitAdapter.queue(request, state.pet) }) {
                is DashboardAdapterResult.Success -> {
                    val pending = result.value
                    dispatch(
                        DashboardEvent.OutfitQueued(
                            requestKey = request.requestKey,
                            pending = pending,
                            acceptedPet = state.pet.copy(
                                experience = (state.pet.experience - OutfitExperienceCost)
                                    .coerceAtLeast(0),
                            ),
                            reply = outfitQueuedReply(pending.displayItem),
                        ),
                    )
                }
                DashboardAdapterResult.Failure -> dispatch(
                    DashboardEvent.OutfitFailed(request.requestKey),
                )
            }
        }
    }

    LaunchedEffect(state.activeTravel?.requestKey) {
        val request = state.activeTravel ?: return@LaunchedEffect
        if (debugState.freezesAsync) return@LaunchedEffect
        val durable = durableOperations
        if (durable != null) {
            when (val result = durable.acceptTravel(request, state.pet)) {
                is DurableTravelResult.Queued -> dispatch(
                    DashboardEvent.TravelQueued(
                        request.requestKey,
                        result.pending,
                        travelQueuedReply(result.pending.prompt),
                    ),
                )
                is DurableTravelResult.PersistedButQueueFailed -> dispatch(
                    DashboardEvent.TravelPersistedButQueueFailed(
                        request.requestKey,
                        result.pending,
                    ),
                )
                DurableTravelResult.Failure,
                DurableTravelResult.Unavailable,
                -> dispatch(DashboardEvent.TravelFailed(request.requestKey))
            }
        } else {
            when (val result = executeDashboardAdapter { travelAdapter.queue(request, state.pet) }) {
                is DashboardAdapterResult.Success -> {
                    val pending = result.value
                    dispatch(
                        DashboardEvent.TravelQueued(
                            requestKey = request.requestKey,
                            pending = pending,
                            reply = travelQueuedReply(pending.prompt),
                        ),
                    )
                }
                DashboardAdapterResult.Failure -> dispatch(
                    DashboardEvent.TravelFailed(request.requestKey),
                )
            }
        }
    }

    val advancingReply = state.chatReply ?: state.feedReply ?: state.transientReply
    LaunchedEffect(advancingReply?.requestKey, advancingReply?.portionIndex) {
        val reply = advancingReply ?: return@LaunchedEffect
        if (debugState.freezesReplyAdvance || !reply.hasNextPortion) return@LaunchedEffect
        delay(DashboardReplyAutoAdvanceMillis)
        dispatch(DashboardEvent.AdvanceReply(reply.requestKey))
    }

    val petTapThanks = state.transientReply?.takeIf {
        it.requestKey.startsWith("pet-tap-")
    }
    LaunchedEffect(petTapThanks?.requestKey) {
        val reply = petTapThanks ?: return@LaunchedEffect
        delay(PetTapThanksVisibleMillis)
        dispatch(DashboardEvent.ClearReply(reply.requestKey))
    }

    DashboardInlineScreen(
        state = state,
        debugState = debugState,
        onEvent = ::dispatch,
        onPetTapFeedback = {
            petTapAudio.play()
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val willReward = (state.pet.petTapProgress + 1) % PetTapsPerHappinessReward == 0
            val thanksMessage = if (willReward && PetTapThanksSession.claim(state.pet.petId)) {
                PetTapThanksReplies.random()
            } else {
                null
            }
            dispatch(
                DashboardEvent.PetTapped(
                    thanksMessage = thanksMessage,
                    replyRequestKey = thanksMessage?.let { nextRequestKey("pet-tap") },
                ),
            )
        },
        onClose = ::closeMode,
        nextRequestKey = ::nextRequestKey,
        requestImeOverride = requestImeOverride,
        mediaProjection = projectDashboardMedia(
            state.pet,
            travelPresentation,
            resolveUrl = { mediaUrlPolicy?.resolve(it) },
            fixtureOnly = mediaUrlPolicy == null,
        ),
        mediaUrlPolicy = mediaUrlPolicy,
        reducedMotionOverride = reducedMotionOverride,
    )
}

@Composable
fun DashboardScreen(
    state: PetDashboardState,
    onChat: () -> Unit,
    onFeed: () -> Unit,
    onTravel: () -> Unit,
    onPetTap: () -> Unit,
    onOutfit: () -> Unit = {},
    modifier: Modifier = Modifier,
    travelPresentation: LocalTravelVideoAsset? = null,
    mediaUrlPolicy: StaticMediaUrlPolicy? = null,
    reducedMotionOverride: Boolean? = null,
) {
    val hazeState = rememberHazeState()
    Box(modifier = modifier.fillMaxSize().background(Color(0xFFBDBBB3)), contentAlignment = Alignment.TopCenter) {
        BoxWithReferenceFrame {
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                DashboardVideo(
                    projection = projectDashboardMedia(
                        state,
                        travelPresentation,
                        resolveUrl = { mediaUrlPolicy?.resolve(it) },
                        fixtureOnly = mediaUrlPolicy == null,
                    ),
                    urlPolicy = mediaUrlPolicy,
                    reducedMotion = reducedMotionOverride ?: systemReducedMotionEnabled(),
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.video_filter_normal),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .7f },
                )
            }
            Box(
                modifier = Modifier
                    .requiredWidth(268.dp)
                    .requiredHeight(491.dp)
                    .offset(x = 67.dp, y = 219.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onPetTap() }) },
            )
            Text(
                text = "Уровень: ${state.stageLabel}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OpenRundeFontFamily,
                letterSpacing = (-0.15).sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .graphicsLayer { scaleX = 1.044f },
            )
            ExperiencePill(
                experience = state.experience,
                hazeState = hazeState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(21.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 77.dp, end = 20.dp),
            ) {
                StatusRing(state.hunger, StatusKind.Hunger)
                StatusRing(state.happiness, StatusKind.Mood)
                StatusRing(state.energy, StatusKind.Energy)
            }

            SpeechBubble(
                message = state.message,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 237.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(19.dp),
                modifier = Modifier
                    .offset(y = 762.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 29.dp),
            ) {
                GlassAction("Поболтать", ActionKind.Chat, 192.dp, hazeState, onChat)
                GlassAction("Покормить", ActionKind.Feed, 198.dp, hazeState, onFeed)
                GlassAction("В путешествие", ActionKind.Travel, 241.dp, hazeState, onTravel)
                GlassAction("Нарядить", ActionKind.Outfit, 180.dp, hazeState, onOutfit)
            }
        }
    }
}

@Composable
private fun DashboardInlineScreen(
    state: DashboardUiState,
    debugState: DashboardDebugState,
    onEvent: (DashboardEvent) -> Unit,
    onPetTapFeedback: () -> Unit,
    onClose: () -> Unit,
    nextRequestKey: (String) -> String,
    requestImeOverride: Boolean?,
    mediaProjection: DashboardMediaProjection,
    mediaUrlPolicy: StaticMediaUrlPolicy?,
    reducedMotionOverride: Boolean?,
    modifier: Modifier = Modifier,
) {
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val reducedMotion = reducedMotionOverride ?: systemReducedMotionEnabled()
    var nextPetTapReactionId by remember { mutableIntStateOf(0) }
    var petTapReaction by remember { mutableStateOf<PetTapReaction?>(null) }
    var petTapHeartBursts by remember { mutableStateOf<List<PetTapHeartBurst>>(emptyList()) }
    var lastPetTapParticleAt by remember { mutableStateOf(Long.MIN_VALUE) }
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val mediaOffsetY = if (
        imeVisible && (
            state.mode == DashboardMode.Chat ||
                state.mode == DashboardMode.Outfit ||
                state.mode == DashboardMode.Travel
            )
    ) -240.dp else 0.dp
    val promptMode = state.mode == DashboardMode.Outfit || state.mode == DashboardMode.Travel
    val showDefaultStatus = !promptMode

    Box(
        modifier = modifier.fillMaxSize().background(Color(0xFFBDBBB3)),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithReferenceFrame {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = mediaOffsetY.toPx() }
                    .hazeSource(hazeState),
            ) {
                DashboardVideo(
                    projection = mediaProjection,
                    urlPolicy = mediaUrlPolicy,
                    reducedMotion = reducedMotion,
                    petTapReaction = petTapReaction,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.video_filter_normal),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .7f },
                )
            }

            if (state.mode == DashboardMode.Idle) {
                fun triggerPetTap(localPoint: Offset) {
                    val center = Offset(
                        x = with(density) { 37.dp.toPx() } + localPoint.x,
                        y = with(density) { 219.dp.toPx() } + localPoint.y,
                    )
                    nextPetTapReactionId += 1
                    petTapReaction = PetTapReaction(nextPetTapReactionId, center)
                    val now = SystemClock.elapsedRealtime()
                    if (
                        !reducedMotion &&
                        (
                            lastPetTapParticleAt == Long.MIN_VALUE ||
                                now - lastPetTapParticleAt >= PetTapParticleIntervalMillis
                            )
                    ) {
                        lastPetTapParticleAt = now
                        petTapHeartBursts = (
                            petTapHeartBursts.takeLast(1) +
                                PetTapHeartBurst(nextPetTapReactionId, center)
                            )
                    }
                    onPetTapFeedback()
                }
                Box(
                    modifier = Modifier
                        .requiredWidth(328.dp)
                        .requiredHeight(491.dp)
                        .offset(x = 37.dp, y = 219.dp)
                        .pointerInput(state.pet.petId) {
                            detectTapGestures(onPress = { triggerPetTap(it) })
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Погладить ${state.pet.name}"
                            onClick {
                                triggerPetTap(
                                    Offset(
                                        x = with(density) { 164.dp.toPx() },
                                        y = with(density) { 245.5.dp.toPx() },
                                    ),
                                )
                                true
                            }
                        },
                )
            }

            petTapHeartBursts.forEach { burst ->
                PetTapHeartBurst(
                    burst = burst,
                    onFinished = { finishedId ->
                        petTapHeartBursts = petTapHeartBursts.filterNot { it.id == finishedId }
                    },
                    modifier = Modifier.fillMaxSize().zIndex(9f),
                )
            }

            if (showDefaultStatus) {
                DashboardStatusChrome(state.pet, hazeState)
            } else if (promptMode) {
                ExperiencePill(
                    experience = state.pet.experience,
                    hazeState = hazeState,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp),
                )
            }

            when (state.mode) {
                DashboardMode.Chat -> {
                    if (state.activeChat != null) {
                        DashboardThinkingIndicator(
                            freezeFrame = debugState.freezesMotion,
                            modifier = Modifier.offset(x = 161.dp, y = 235.dp),
                        )
                    } else {
                        state.chatReply?.let { reply ->
                            AnchoredSpeechBubble(
                                message = reply.visibleText,
                                anchorBottom = 291.dp,
                                showContinuation = reply.hasNextPortion,
                                freezeContinuationMotion = debugState.freezesMotion,
                            )
                        }
                    }
                }

                DashboardMode.Feed -> {
                    if (state.activeFeed != null) {
                        DashboardThinkingIndicator(
                            freezeFrame = debugState.freezesMotion,
                            modifier = Modifier.offset(x = 161.dp, y = 235.dp),
                        )
                    } else {
                        state.feedReply?.let { reply ->
                            AnchoredSpeechBubble(
                                message = reply.visibleText,
                                anchorBottom = 336.dp,
                                showContinuation = reply.hasNextPortion,
                                freezeContinuationMotion = debugState.freezesMotion,
                            )
                        }
                    }
                }

                DashboardMode.Outfit -> PromptSpeechBubble(
                    message = OutfitPrompt,
                    modifier = Modifier.offset(x = 65.dp, y = 190.dp),
                )

                DashboardMode.Travel -> PromptSpeechBubble(
                    message = TravelPrompt,
                    modifier = Modifier.offset(x = 65.dp, y = 190.dp),
                )

                DashboardMode.Idle -> {
                    val transientReply = state.transientReply
                    val message = transientReply?.visibleText ?: state.pet.message
                    AnchoredSpeechBubble(
                        message = message,
                        anchorBottom = 336.dp,
                        showContinuation = transientReply?.hasNextPortion == true,
                        freezeContinuationMotion = debugState.freezesMotion,
                    )
                }
            }

            if (
                state.mode == DashboardMode.Chat ||
                state.mode == DashboardMode.Outfit ||
                state.mode == DashboardMode.Travel
            ) {
                val isOutfit = state.mode == DashboardMode.Outfit
                val isTravel = state.mode == DashboardMode.Travel
                ConversationInputPanel(
                    mode = state.mode,
                    value = when {
                        isOutfit -> state.outfitDraft
                        isTravel -> state.travelDraft
                        else -> state.chatDraft
                    },
                    error = when {
                        isOutfit -> state.outfitError
                        isTravel -> state.travelError
                        else -> state.chatError
                    },
                    busy = when {
                        isOutfit -> state.activeOutfit != null
                        isTravel -> state.activeTravel != null
                        else -> state.activeChat != null
                    },
                    hazeState = hazeState,
                    requestIme = requestImeOverride
                        ?: (debugState.requestsIme || debugState == DashboardDebugState.Idle),
                    imeVisible = imeVisible,
                    onValueChange = { value ->
                        onEvent(
                            when {
                                isOutfit -> DashboardEvent.UpdateOutfitDraft(value)
                                isTravel -> DashboardEvent.UpdateTravelDraft(value)
                                else -> DashboardEvent.UpdateChatDraft(value)
                            },
                        )
                    },
                    onSubmit = {
                        onEvent(
                            when {
                                isOutfit -> DashboardEvent.SubmitOutfit(nextRequestKey("outfit"))
                                isTravel -> DashboardEvent.SubmitTravel(nextRequestKey("travel"))
                                else -> DashboardEvent.SubmitChat(nextRequestKey("chat"))
                            },
                        )
                    },
                )
            }

            if (state.mode == DashboardMode.Feed) {
                FeedModeLayer(
                    state = state,
                    hazeState = hazeState,
                    freezeMotion = debugState.freezesMotion,
                    nextRequestKey = nextRequestKey,
                    onEvent = onEvent,
                )
            }

            if (state.mode == DashboardMode.Idle) {
                DashboardActions(
                    hazeState = hazeState,
                    onChat = { onEvent(DashboardEvent.OpenChat) },
                    onFeed = { onEvent(DashboardEvent.OpenFeed) },
                    onTravel = { onEvent(DashboardEvent.OpenTravel) },
                    onOutfit = { onEvent(DashboardEvent.OpenOutfit) },
                )
            }

        }

        contextualNavigationForDashboardMode(state.mode)?.let { action ->
            ContextualGlassNavigation(
                action = action,
                onClick = onClose,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(start = 16.dp, top = 16.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.DashboardStatusChrome(pet: PetDashboardState, hazeState: HazeState) {
    Text(
        text = "Уровень: ${pet.stageLabel}",
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = OpenRundeFontFamily,
        letterSpacing = (-0.15).sp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 80.dp)
            .graphicsLayer { scaleX = 1.044f },
    )
    ExperiencePill(
        experience = pet.experience,
        hazeState = hazeState,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(21.dp),
        modifier = Modifier.align(Alignment.TopEnd).padding(top = 77.dp, end = 20.dp),
    ) {
        StatusRing(pet.hunger, StatusKind.Hunger)
        StatusRing(pet.happiness, StatusKind.Mood)
        StatusRing(pet.energy, StatusKind.Energy)
    }
}

@Composable
private fun BoxScope.DashboardActions(
    hazeState: HazeState,
    onChat: () -> Unit,
    onFeed: () -> Unit,
    onTravel: () -> Unit,
    onOutfit: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(19.dp),
        modifier = Modifier
            .offset(y = 762.dp)
            .horizontalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 29.dp),
    ) {
        GlassAction("Поболтать", ActionKind.Chat, 192.dp, hazeState, onChat)
        GlassAction("Покормить", ActionKind.Feed, 198.dp, hazeState, onFeed)
        GlassAction("В путешествие", ActionKind.Travel, 241.dp, hazeState, onTravel)
        GlassAction("Нарядить", ActionKind.Outfit, 180.dp, hazeState, onOutfit)
    }
}

@Composable
private fun ConversationInputPanel(
    mode: DashboardMode,
    value: String,
    error: String?,
    busy: Boolean,
    hazeState: HazeState,
    requestIme: Boolean,
    imeVisible: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val isOutfit = mode == DashboardMode.Outfit
    val isTravel = mode == DashboardMode.Travel
    val placeholder = when {
        isOutfit -> "В футболку Metallica"
        isTravel -> DeterministicTravelPrompt
        else -> "Расскажи о себе"
    }
    val inputDescription = when {
        isOutfit -> "Описание наряда"
        isTravel -> "Место путешествия"
        else -> "Сообщение персонажу"
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit by rememberUpdatedState(onSubmit)

    LaunchedEffect(requestIme) {
        if (requestIme) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .requiredSize(362.dp, 62.dp)
            .offset(x = 20.dp, y = if (imeVisible) 463.dp else 755.dp),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(DashboardGlassContract.ConversationShape)
                .hazeEffect(hazeState, DashboardGlassContract.InlineStyle)
                .innerShadow(
                    DashboardGlassContract.ConversationShape,
                    DashboardGlassContract.ConversationHighlightInset,
                )
                .innerShadow(
                    DashboardGlassContract.ConversationShape,
                    DashboardGlassContract.ConversationSoftInset,
                ),
        )
        BasicTextField(
            value = value,
            onValueChange = { onValueChange(it.take(DashboardPromptMaxLength)) },
            enabled = !busy,
            singleLine = true,
            textStyle = TextStyle(
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OpenRundeFontFamily,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .requiredWidth(if (isOutfit) 224.dp else 264.dp)
                .height(46.dp)
                .offset(x = 24.dp, y = 8.dp)
                .focusRequester(focusRequester)
                .semantics {
                    contentDescription = inputDescription
                },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = Color.White.copy(alpha = .3f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = OpenRundeFontFamily,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (error != null) {
            Text(
                text = error,
                color = Color(0xFFFF6675),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OpenRundeFontFamily,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .requiredWidth(314.dp)
                    .offset(x = 24.dp, y = (-24).dp)
                    .semantics { contentDescription = error },
            )
        }
        if (isOutfit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .requiredSize(82.dp, 46.dp)
                    .offset(x = 264.dp, y = 8.dp)
                    .clip(RoundedCornerShape(36.429.dp))
                    .background(Color.White)
                    .pointerInput(value, busy) {
                        detectTapGestures(onTap = { if (!busy && value.trim().isNotEmpty()) submit() })
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Создать наряд за 200 монет"
                        onClick("Создать наряд за 200 монет") {
                            if (!busy && value.trim().isNotEmpty()) {
                                submit()
                                true
                            } else {
                                false
                            }
                        }
                    },
            ) {
                Text(
                    text = "$OutfitExperienceCost",
                    color = Color.Black,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = OpenRundeFontFamily,
                )
                Spacer(Modifier.width(5.dp))
                Coin(17.4.dp, monochrome = true)
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .requiredSize(46.dp)
                    .offset(x = 306.dp, y = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .pointerInput(value, busy) {
                        detectTapGestures(onTap = { if (!busy && value.trim().isNotEmpty()) submit() })
                    }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Отправить"
                        onClick("Отправить") {
                            if (!busy && value.trim().isNotEmpty()) {
                                submit()
                                true
                            } else {
                                false
                            }
                        }
                    },
            ) {
                Image(
                    painter = painterResource(R.drawable.conversation_send),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(35.074.dp),
                )
            }
        }
    }
}

@Composable
private fun FeedModeLayer(
    state: DashboardUiState,
    hazeState: HazeState,
    freezeMotion: Boolean,
    nextRequestKey: (String) -> String,
    onEvent: (DashboardEvent) -> Unit,
) {
    val pulseProgress = remember(state.feedPulseId) {
        Animatable(if (state.feedPulseId > 0 && freezeMotion) .42f else 0f)
    }
    LaunchedEffect(state.feedPulseId, freezeMotion) {
        if (state.feedPulseId <= 0 || freezeMotion) return@LaunchedEffect
        pulseProgress.snapTo(0f)
        pulseProgress.animateTo(1f, tween(200))
    }
    if (state.feedPulseId > 0) {
        Canvas(
            modifier = Modifier
                .requiredSize(126.dp)
                .offset(x = 138.dp, y = 341.dp)
                .graphicsLayer {
                    alpha = if (freezeMotion) .62f else 1f - pulseProgress.value
                    val pulseScale = .72f + (.46f * pulseProgress.value)
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
        ) {
            drawCircle(Color(0x2EEEAA35))
            drawCircle(Color(0x4758A965), style = Stroke(2.dp.toPx()))
        }
    }
    state.feedError?.let { error ->
        Text(
            text = error,
            color = Color(0xFFFF6675),
            fontSize = 14.sp,
            lineHeight = 18.2.sp,
            textAlign = TextAlign.Center,
            fontFamily = OpenRundeFontFamily,
            modifier = Modifier
                .requiredWidth(346.dp)
                .offset(x = 28.dp, y = 666.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2B1116))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .requiredSize(374.dp, 148.dp)
            .offset(x = 14.dp, y = 708.dp)
            .semantics { contentDescription = "Еда" },
    ) {
        FeedFoodToken(
            food = DashboardFood.BerryBowl,
            state = state,
            hazeState = hazeState,
            rotation = (-8f),
            freezeMotion = freezeMotion,
            nextRequestKey = nextRequestKey,
            onEvent = onEvent,
        )
        FeedFoodToken(
            food = DashboardFood.LeafCrunch,
            state = state,
            hazeState = hazeState,
            rotation = 6f,
            freezeMotion = freezeMotion,
            nextRequestKey = nextRequestKey,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun FeedFoodToken(
    food: DashboardFood,
    state: DashboardUiState,
    hazeState: HazeState,
    rotation: Float,
    freezeMotion: Boolean,
    nextRequestKey: (String) -> String,
    onEvent: (DashboardEvent) -> Unit,
) {
    val density = LocalDensity.current
    val isActiveToken = state.feedToken.food == food
    val motion = if (isActiveToken) state.feedToken else FoodTokenMotion()
    val latestMotion by rememberUpdatedState(motion)
    val enabled = state.activeFeed == null && motion.phase == FoodTokenPhase.Idle
    val scale = remember(food) { Animatable(1f) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(motion.phase, freezeMotion) {
        when (motion.phase) {
            FoodTokenPhase.Consuming -> if (freezeMotion) scale.snapTo(.52f) else {
                scale.snapTo(1f)
                scale.animateTo(0f, tween(180))
            }
            FoodTokenPhase.Reappearing -> if (freezeMotion) scale.snapTo(.78f) else {
                scale.snapTo(.7f)
                scale.animateTo(1f, tween(220))
            }
            else -> scale.snapTo(1f)
        }
    }

    fun activateByTap() {
        if (state.activeFeed == null) {
            onEvent(DashboardEvent.TapFood(food, nextRequestKey("feed")))
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .requiredSize(76.dp, 94.dp)
            .zIndex(if (motion.phase == FoodTokenPhase.Dragging || motion.phase == FoodTokenPhase.Consuming) 8f else 1f)
            .graphicsLayer {
                translationX = with(density) { motion.offsetX.dp.toPx() }
                translationY = with(density) { motion.offsetY.dp.toPx() }
                rotationZ = rotation
                scaleX = scale.value
                scaleY = scale.value
                alpha = if (!enabled && !isActiveToken) .58f else 1f
            }
            .onGloballyPositioned { coordinates = it }
            .shadow(
                elevation = 10.dp,
                shape = DashboardGlassContract.FoodShape,
                ambientColor = Color(0x143E2340),
                spotColor = Color(0x143E2340),
            )
            .clip(DashboardGlassContract.FoodShape)
            .hazeEffect(hazeState, DashboardGlassContract.InlineStyle)
            .innerShadow(DashboardGlassContract.FoodShape, DashboardGlassContract.FoodInset)
            .drawBehind {
                drawRoundRect(
                    color = Color(0x0A292929),
                    cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
            }
            .pointerInput(food, enabled) {
                detectTapGestures(onTap = { if (enabled) activateByTap() })
            }
            .pointerInput(food, state.activeFeed != null) {
                if (state.activeFeed != null) return@pointerInput
                detectDragGestures(
                    onDragStart = { onEvent(DashboardEvent.StartFoodDrag(food)) },
                    onDragCancel = { onEvent(DashboardEvent.CancelFoodDrag(food)) },
                    onDragEnd = {
                        val current = latestMotion
                        val origin = coordinates?.positionInRoot() ?: Offset.Zero
                        val centerX = origin.x + with(density) { (38.dp + current.offsetX.dp).toPx() }
                        val centerY = origin.y + with(density) { (47.dp + current.offsetY.dp).toPx() }
                        val inside = centerX >= with(density) { (-18).dp.toPx() } &&
                            centerX <= with(density) { 420.dp.toPx() } &&
                            centerY >= with(density) { (-18).dp.toPx() } &&
                            centerY <= with(density) { 892.dp.toPx() }
                        onEvent(DashboardEvent.DropFood(food, nextRequestKey("feed"), inside))
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val current = latestMotion
                        onEvent(
                            DashboardEvent.MoveFoodDrag(
                                food,
                                current.offsetX + with(density) { dragAmount.x.toDp().value },
                                current.offsetY + with(density) { dragAmount.y.toDp().value },
                            ),
                        )
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = when (food) {
                    DashboardFood.BerryBowl -> "Ягодная миска"
                    DashboardFood.LeafCrunch -> "Хрустящий лист"
                }
            },
    ) {
        Image(
            painter = painterResource(
                when (food) {
                    DashboardFood.BerryBowl -> R.drawable.feed_food_berry_bowl
                    DashboardFood.LeafCrunch -> R.drawable.feed_food_leaf_crunch
                },
            ),
            contentDescription = null,
            modifier = Modifier.requiredSize(78.dp).graphicsLayer {
                if (motion.phase == FoodTokenPhase.Dragging) {
                    scaleX = 1.18f
                    scaleY = 1.18f
                }
            },
        )
    }
}

@Composable
private fun DashboardThinkingIndicator(freezeFrame: Boolean, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(freezeFrame) {
        if (freezeFrame) return@LaunchedEffect
        while (true) {
            delay(200)
            frame = (frame + 1) % 3
        }
    }
    val drawables = listOf(
        R.drawable.thinking_frame_1,
        R.drawable.thinking_frame_2,
        R.drawable.thinking_frame_3,
    )
    Image(
        painter = painterResource(drawables[if (freezeFrame) 0 else frame]),
        contentDescription = "Персонаж думает",
        contentScale = ContentScale.FillBounds,
        modifier = modifier.requiredSize(80.dp, 55.5.dp),
    )
}

@Composable
private fun PromptSpeechBubble(message: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.requiredSize(272.dp, 146.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.speech_bubble_new),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = message,
            color = Color(0xFF333333),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            letterSpacing = (-0.25).sp,
            textAlign = TextAlign.Center,
            lineHeight = 29.9.sp,
            maxLines = 3,
            modifier = Modifier.requiredWidth(232.dp).padding(bottom = 5.dp),
        )
    }
}

@Composable
private fun BoxWithReferenceFrame(content: @Composable BoxScope.() -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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

@OptIn(UnstableApi::class)
@Composable
private fun DashboardVideo(
    projection: DashboardMediaProjection,
    urlPolicy: StaticMediaUrlPolicy?,
    reducedMotion: Boolean,
    petTapReaction: PetTapReaction? = null,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) {
        Image(
            painter = painterResource(R.drawable.test_pet_poster),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lifecycleStarted by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var retryToken by remember(projection) { mutableIntStateOf(0) }
    var mediaFailed by remember(projection, retryToken) { mutableStateOf(false) }
    val videoUrl = when (projection) {
        is DashboardMediaProjection.RemoteVideo -> if (reducedMotion) null else projection.videoUrl
        DashboardMediaProjection.Fixture -> if (reducedMotion) null else "asset:///media/openai-normal.mp4"
        is DashboardMediaProjection.RemotePoster -> null
    }
    val posterUrl = when (projection) {
        is DashboardMediaProjection.RemoteVideo -> projection.posterUrl
        is DashboardMediaProjection.RemotePoster -> projection.posterUrl
        DashboardMediaProjection.Fixture -> null
    }
    var showPoster by remember(projection, reducedMotion, retryToken, lifecycleStarted) {
        mutableStateOf(true)
    }
    val player = remember(context, videoUrl, retryToken, lifecycleStarted) {
        if (videoUrl == null || !lifecycleStarted) return@remember null
        DashboardVideoTestProbe.recordPlayerCreation()
        val builder = if (videoUrl.startsWith("asset:///")) {
            ExoPlayer.Builder(context)
        } else {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    SecureStaticMediaDataSource.Factory(requireNotNull(urlPolicy)),
                ),
            )
        }
        builder.build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            playWhenReady = lifecycleStarted
            prepare()
        }
    }
    var videoTexture by remember(projection, retryToken) { mutableStateOf<TextureView?>(null) }
    var capturedFrame by remember(projection, retryToken) { mutableStateOf<Bitmap?>(null) }
    var capturedFrameVersion by remember(projection, retryToken) { mutableIntStateOf(0) }

    LaunchedEffect(petTapReaction?.id, videoTexture, reducedMotion) {
        val reaction = petTapReaction ?: return@LaunchedEffect
        val texture = videoTexture?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }
            ?: return@LaunchedEffect
        val bitmap = Bitmap.createBitmap(texture.width, texture.height, Bitmap.Config.ARGB_8888)
        capturedFrame = bitmap
        val durationMillis = if (reducedMotion) 100L else PetTapBulgeDurationMillis.toLong()
        val deadlineNanos = System.nanoTime() + durationMillis * 1_000_000L
        try {
            do {
                texture.getBitmap(bitmap)
                capturedFrameVersion += 1
                withFrameNanos { }
            } while (System.nanoTime() < deadlineNanos && reaction.id == petTapReaction?.id)
        } finally {
            capturedFrame = null
            bitmap.recycle()
        }
    }

    DisposableEffect(player, lifecycle) {
        if (player == null) return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                showPoster = false
                mediaFailed = false
            }

            override fun onPlayerError(error: PlaybackException) {
                showPoster = true
                mediaFailed = true
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    (LayoutInflater.from(viewContext).inflate(R.layout.view_dashboard_player, null, false) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                        videoTexture = videoSurfaceView as? TextureView
                    }
                },
                update = {
                    it.player = player
                    videoTexture = it.videoSurfaceView as? TextureView
                },
                onRelease = {
                    videoTexture = null
                    it.player = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showPoster) {
            RemoteOrFixturePoster(
                posterUrl = posterUrl,
                urlPolicy = urlPolicy,
                retryToken = retryToken,
                onFailure = { mediaFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        capturedFrame?.let { bitmap ->
            val frameImage = remember(bitmap, capturedFrameVersion) { bitmap.asImageBitmap() }
            Image(
                bitmap = frameImage,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .petTapBulge(petTapReaction, reducedMotion),
            )
        }
        if (mediaFailed) {
            Text(
                text = "Повторить медиа",
                color = Color.White,
                fontFamily = OpenRundeFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp)
                    .requiredHeight(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = .55f))
                    .clickable(role = Role.Button) {
                        mediaFailed = false
                        showPoster = true
                        retryToken += 1
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun RemoteOrFixturePoster(
    posterUrl: String?,
    urlPolicy: StaticMediaUrlPolicy?,
    retryToken: Int,
    onFailure: () -> Unit,
    fallbackDrawable: Int = R.drawable.test_pet_poster,
    modifier: Modifier = Modifier,
) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, posterUrl, retryToken) {
        if (posterUrl == null || urlPolicy == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = SecureStaticMediaDataSource(urlPolicy, StaticImageMaxBytes)
                try {
                    source.open(DataSpec(Uri.parse(posterUrl)))
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.isNotEmpty() && bytes.size <= StaticImageMaxBytes)
                    requireNotNull(decodeBoundedStaticImage(bytes)).asImageBitmap()
                } finally {
                    source.close()
                }
            }.getOrNull()
        }
        if (value == null) onFailure()
    }
    Image(
        painter = image?.let(::BitmapPainter) ?: painterResource(fallbackDrawable),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

internal fun boundedImageSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384) return null
    if (width.toLong() * height.toLong() > 64_000_000L) return null
    var sample = 1
    while (
        width / sample > 2_048 || height / sample > 4_096 ||
        (width / sample).toLong() * (height / sample).toLong() > 8_000_000L
    ) {
        sample *= 2
    }
    return sample
}

private fun decodeBoundedStaticImage(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = boundedImageSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

@Composable
private fun systemReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

@Composable
private fun ExperiencePill(experience: Int, hazeState: HazeState, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .requiredWidth(87.dp)
            .height(35.119.dp)
            .clip(DashboardGlassContract.ExperienceShape)
            .hazeEffect(state = hazeState, style = DashboardGlassContract.ExperienceStyle)
            .semantics { contentDescription = "Experience: $experience" }
            .padding(horizontal = 11.dp),
    ) {
        Text(
            "$experience",
            color = Color(0xFFFFB107),
            fontSize = 22.6.sp,
            fontWeight = FontWeight.Black,
            fontFamily = OpenRundeFontFamily,
        )
        Spacer(Modifier.width(7.dp))
        Coin(17.4.dp)
    }
}

private enum class StatusKind { Hunger, Mood, Energy }

private val statusGlyphPath = mapOf(
    StatusKind.Hunger to "M22.5576 39.2633C21.5408 39.2633 20.8871 38.6719 20.9078 37.7173L21.1257 26.2C21.1361 25.5878 21.0323 25.4321 20.6277 25.235C19.1232 24.519 18.3969 23.7512 18.511 21.5515L18.8223 15.5957C18.8534 15.0146 19.1958 14.6515 19.725 14.6515C20.2645 14.6515 20.5758 15.025 20.5654 15.6268L20.472 21.3544C20.4617 21.7694 20.6692 21.9977 21.0116 21.9977C21.354 21.9977 21.5615 21.7694 21.5719 21.3855L21.686 15.4089C21.6964 14.8486 22.0284 14.4647 22.5576 14.4647C23.0868 14.4647 23.4188 14.8486 23.4292 15.4089L23.5433 21.3855C23.5537 21.7798 23.7405 21.9977 24.1036 21.9977C24.4357 21.9977 24.6432 21.7694 24.6328 21.3544L24.5498 15.6268C24.5394 15.025 24.8507 14.6515 25.3799 14.6515C25.9194 14.6515 26.2411 15.0043 26.2826 15.5957L26.5835 21.5515C26.708 23.7512 25.9921 24.519 24.4772 25.235C24.0725 25.4321 23.9791 25.5878 23.9895 26.2L24.197 37.7173C24.2074 38.6615 23.5848 39.2633 22.5576 39.2633ZM30.8584 30.0702C30.8792 29.5825 30.7546 29.3646 30.3604 29.0948L30.0698 28.9081C29.3124 28.3893 29.0945 27.9327 29.0945 27.1234V26.4282C29.0945 22.5476 30.4226 17.5153 32.1035 15.191C32.4667 14.693 32.7572 14.4647 33.1722 14.4647C33.6807 14.4647 34.0127 14.8175 34.0127 15.3467V37.7277C34.0127 38.6719 33.4109 39.2633 32.3733 39.2633C31.3564 39.2633 30.7131 38.6719 30.7235 37.7069L30.8584 30.0702Z",
    StatusKind.Mood to "M26.8477 38.6953C20.7569 38.6953 15.8155 33.7539 15.8155 27.6631C15.8155 21.5723 20.7569 16.6309 26.8477 16.6309C32.9385 16.6309 37.8799 21.5723 37.8799 27.6631C37.8799 33.7539 32.9385 38.6953 26.8477 38.6953ZM23.6895 26.5996C24.3448 26.5996 24.9356 25.998 24.9356 25.1816C24.9356 24.3438 24.3448 23.7529 23.6895 23.7529C23.0235 23.7529 22.4756 24.3438 22.4756 25.1816C22.4756 25.998 23.0235 26.5996 23.6895 26.5996ZM29.9844 26.5996C30.6397 26.5996 31.2305 25.998 31.2305 25.1816C31.2305 24.3438 30.6397 23.7529 29.9844 23.7529C29.3077 23.7529 28.7598 24.3438 28.7598 25.1816C28.7598 25.998 29.3077 26.5996 29.9844 26.5996ZM26.8477 32.6045C29.168 32.6045 30.7256 31.0791 30.7256 30.3164C30.7256 30.0264 30.4356 29.8975 30.1456 30.0156C29.3077 30.3809 28.4268 30.875 26.8477 30.875C25.2579 30.875 24.3448 30.4131 23.5391 30.0156C23.2491 29.8867 22.959 30.0264 22.959 30.3164C22.959 31.0791 24.5059 32.6045 26.8477 32.6045Z",
    StatusKind.Energy to "M27 36.85L25.55 35.53C20.4 30.86 17 27.78 17 24C17 20.92 19.42 18.5 22.5 18.5C24.24 18.5 25.91 19.31 27 20.59C28.09 19.31 29.76 18.5 31.5 18.5C34.58 18.5 37 20.92 37 24C37 27.78 33.6 30.86 28.45 35.54L27 36.85Z",
)

@Composable
private fun StatusRing(value: Int, kind: StatusKind) {
    val tone = when {
        value < 30 -> Color(0xFFDF5964)
        value >= 70 -> Color(0xFF3FFF8F)
        else -> Color(0xFFFFB107)
    }
    val glyph = remember(kind) {
        PathParser().parsePathString(statusGlyphPath.getValue(kind)).toPath()
    }
    Canvas(
        modifier = Modifier.size(54.dp).semantics { contentDescription = "${kind.name}: $value из 100" },
    ) {
        val stroke = 5.8.dp.toPx()
        val radius = 23.5.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(tone.copy(alpha = .25f), radius, center, style = Stroke(stroke))
        drawArc(
            color = tone,
            startAngle = -90f,
            sweepAngle = 360f * value.coerceIn(0, 100) / 100f,
            useCenter = false,
            topLeft = center - Offset(radius, radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        withTransform({
            scale(size.width / 54f, size.height / 54f, pivot = Offset.Zero)
        }) {
            drawPath(glyph, tone)
        }
    }
}

private data class SpeechBubbleGeometry(val width: Dp, val height: Dp)

private fun speechBubbleGeometry(message: String): SpeechBubbleGeometry = when (message) {
    "Как тебя зовут?" -> SpeechBubbleGeometry(288.dp, 99.dp)
    BerryReply -> SpeechBubbleGeometry(248.dp, 99.dp)
    DeterministicChatReply, LeafReply -> SpeechBubbleGeometry(360.dp, 150.dp)
    "Футболка Metallica?" -> SpeechBubbleGeometry(341.dp, 99.dp)
    "Интересно" -> SpeechBubbleGeometry(248.dp, 99.dp)
    "Я получу заказ примерно через 10 минут" -> SpeechBubbleGeometry(360.dp, 132.dp)
    else -> if (message.length <= 19) {
        SpeechBubbleGeometry(288.dp, 99.dp)
    } else {
        SpeechBubbleGeometry(360.dp, 150.dp)
    }
}

@Composable
private fun BoxScope.AnchoredSpeechBubble(
    message: String,
    anchorBottom: Dp,
    showContinuation: Boolean = false,
    freezeContinuationMotion: Boolean = false,
) {
    val geometry = speechBubbleGeometry(message)
    SpeechBubble(
        message = message,
        showContinuation = showContinuation,
        freezeContinuationMotion = freezeContinuationMotion,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = anchorBottom - geometry.height),
    )
}

@Composable
private fun SpeechBubble(
    message: String,
    showContinuation: Boolean = false,
    freezeContinuationMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val geometry = speechBubbleGeometry(message)
    val bottomPadding = if (geometry.height > 99.dp) 10.dp else 5.dp
    val maxMessageLines = when {
        geometry.height >= 150.dp -> 4
        geometry.height > 99.dp -> 3
        else -> 2
    }
    val messageWidth = if (
        showContinuation && geometry == SpeechBubbleGeometry(341.dp, 99.dp)
    ) {
        geometry.width - 40.dp
    } else {
        geometry.width - 90.dp
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .requiredSize(geometry.width, geometry.height),
    ) {
        Image(
            painter = painterResource(R.drawable.speech_bubble_new),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().padding(start = 45.dp, end = 45.dp, bottom = bottomPadding),
        ) {
            val displayText = if (showContinuation) {
                buildAnnotatedString {
                    append(message)
                    append(" ")
                    appendInlineContent("continuation", "...")
                }
            } else {
                AnnotatedString(message)
            }
            val inlineContent = if (showContinuation) {
                mapOf(
                    "continuation" to InlineTextContent(
                        placeholder = Placeholder(
                            width = 24.6.sp,
                            height = 8.sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        ),
                    ) {
                        ContinuationDots(freezeMotion = freezeContinuationMotion)
                    },
                )
            } else {
                emptyMap()
            }
            Text(
                text = displayText,
                inlineContent = inlineContent,
                color = Color(0xFF333333),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = OpenRundeFontFamily,
                letterSpacing = (-0.25).sp,
                textAlign = TextAlign.Center,
                lineHeight = 29.9.sp,
                maxLines = maxMessageLines,
                softWrap = true,
                modifier = Modifier.requiredWidth(messageWidth).offset(y = (-1).dp),
            )
        }
    }
}

@Composable
private fun ContinuationDots(freezeMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "reply-continuation")
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.requiredSize(24.6.dp, 8.dp),
    ) {
        repeat(3) { index ->
            val offset = if (freezeMotion) {
                0f
            } else {
                val animated by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = -2.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 360, delayMillis = index * 120),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "reply-continuation-$index",
                )
                animated
            }
            Box(
                modifier = Modifier
                    .requiredSize(5.4.dp)
                    .graphicsLayer { translationY = offset.dp.toPx() }
                    .background(Color(0xFF333333), CircleShape),
            )
        }
    }
}

private enum class ActionKind { Chat, Feed, Travel, Outfit }

@Composable
private fun GlassAction(label: String, kind: ActionKind, width: Dp, hazeState: HazeState, onClick: () -> Unit) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .requiredWidth(width)
            .height(58.203.dp)
            .scale(scale.value)
            .clip(DashboardGlassContract.ActionShape)
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        scope.launch { scale.animateTo(.92f, spring(stiffness = Spring.StiffnessHigh)) }
                        val released = tryAwaitRelease()
                        scope.launch { scale.animateTo(1f, spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium)) }
                        if (released) onClick()
                    },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    onClick()
                    true
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .hazeEffect(state = hazeState, style = DashboardGlassContract.ActionStyle),
        )
        Box(
            Modifier
                .matchParentSize()
                .innerShadow(DashboardGlassContract.ActionShape, DashboardGlassContract.ActionHighlightInset)
                .innerShadow(DashboardGlassContract.ActionShape, DashboardGlassContract.ActionShadeInset)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.linearGradient(listOf(Color.White.copy(.24f), Color.Transparent, Color.Black.copy(.16f))),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(1.2.dp.toPx()),
                    )
                },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.matchParentSize(),
        ) {
            ActionGlyph(kind)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = Color.White,
                fontSize = 23.445.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = OpenRundeFontFamily,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActionGlyph(kind: ActionKind) {
    if (kind == ActionKind.Outfit) return
    val drawable = when (kind) {
        ActionKind.Chat -> R.drawable.action_chat
        ActionKind.Feed -> R.drawable.action_feed
        ActionKind.Travel -> R.drawable.action_travel
        ActionKind.Outfit -> error("handled above")
    }
    Image(painterResource(drawable), contentDescription = null, modifier = Modifier.size(28.dp))
}

@Composable
private fun Coin(size: Dp, monochrome: Boolean = false) {
    Image(
        painter = painterResource(R.drawable.xp_coin),
        contentDescription = null,
        colorFilter = if (monochrome) ColorFilter.tint(Color.Black) else null,
        modifier = Modifier.size(size),
    )
}

@Preview(name = "Dashboard 402×874", widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun DashboardPreview() {
    GigagochiTheme {
        DashboardScreen(DemoPet, {}, {}, {}, {})
    }
}
