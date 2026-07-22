package com.gigagochi.app.feature.dashboard

import android.net.Uri
import android.graphics.BitmapFactory
import android.provider.Settings
import android.os.SystemClock
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.effect.Presentation
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gigagochi.app.R
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.ContextualGlassNavigation
import com.gigagochi.app.core.designsystem.LocalButtonPressFeedback
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import com.gigagochi.app.core.designsystem.SbSansDisplayFontFamily
import com.gigagochi.app.core.model.PetDashboardState
import com.gigagochi.app.core.database.FirstSessionStage
import com.gigagochi.app.core.database.FirstSessionStore
import com.gigagochi.app.core.database.FirstSessionMutationResult
import com.gigagochi.app.core.database.LocalFirstSession
import com.gigagochi.app.core.network.StaticMediaCache
import com.gigagochi.app.core.network.StaticImageMaxBytes
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChat
import com.gigagochi.app.feature.onboarding.FirstSessionAfterChatFallback
import com.gigagochi.app.feature.onboarding.FirstSessionAfterFirstFood
import com.gigagochi.app.feature.onboarding.FirstSessionAfterName
import com.gigagochi.app.feature.onboarding.FirstSessionAfterNameFallback
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedy
import com.gigagochi.app.feature.onboarding.FirstSessionAfterRemedyPortions
import com.gigagochi.app.feature.onboarding.FirstSessionMainAction
import com.gigagochi.app.feature.onboarding.firstSessionMainAction
import com.gigagochi.app.feature.onboarding.firstSessionReactionReply
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
)

private val ClosedDialogueTop = 663.dp
private val OpenDialogueTop = 371.dp
private val DialogueInputGap = 24.dp
private val PreferredDashboardActionTop = 762.dp
private val DashboardActionHeight = 58.203.dp
private val DashboardActionBottomMargin = 16.dp
private val DashboardFeedRowHeight = 148.dp
private val OnboardingActionMaxWidth = 346.dp
internal val DashboardExperienceTop = 92.dp
internal val DashboardInputHorizontalPadding = 20.dp
internal val DashboardInputMaxWidth = 362.dp
internal val DashboardActionStartPadding = 12.dp
internal val DashboardActionEndPadding = 16.dp
private val LocalDashboardActionTop = staticCompositionLocalOf { PreferredDashboardActionTop }
private const val ImeMotionStartInsetDp = 40f
private const val ImeMotionTravelDp = 292f

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
    initialFirstSession: LocalFirstSession? = null,
    firstSessionOwnerId: String? = null,
    firstSessionStore: FirstSessionStore? = null,
    onFirstSessionTravel: () -> Unit = {},
    onFirstSessionChanged: (LocalFirstSession) -> Unit = {},
    onPetChanged: suspend (PetDashboardState) -> Boolean = { true },
    chatAdapter: DashboardChatAdapter = remember { FakeDashboardChatAdapter() },
    feedAdapter: DashboardFeedAdapter = remember { FakeDashboardFeedAdapter() },
    outfitAdapter: DashboardOutfitAdapter = remember { FakeDashboardOutfitAdapter() },
    travelAdapter: DashboardTravelAdapter = remember { FakeDashboardTravelAdapter() },
    durableOperations: DashboardDurableOperations? = null,
    requestKeyFactory: ((String) -> String)? = null,
    requestImeOverride: Boolean? = null,
    mediaUrlPolicy: StaticMediaUrlPolicy? = null,
    reducedMotionOverride: Boolean? = null,
    unansweredEventCount: Int = 0,
    onEvents: () -> Unit = {},
    mediaActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var state by remember(debugState, initialPet.petId, recoveryRevision) {
        val base = dashboardDebugFixture(debugState, initialPet)
        mutableStateOf(
            if (debugState == DashboardDebugState.Idle) {
                base.copy(
                    firstSession = initialFirstSession,
                    firstSessionIdleReply = initialFirstSession?.let {
                        firstSessionIdleReply(base.pet, it)
                    },
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
        if (event is DashboardEvent.FirstSessionSynced) onFirstSessionChanged(event.session)
    }

    LaunchedEffect(initialFirstSession) {
        val externalSession = initialFirstSession ?: return@LaunchedEffect
        state = hydrateExternalFirstSession(state, externalSession)
    }

    LaunchedEffect(initialPet) {
        if (state.pet != initialPet) {
            state = state.copy(pet = initialPet)
        }
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
                val session = state.firstSession
                if (session != null && session.stage in setOf(
                        FirstSessionStage.AwaitingChat,
                        FirstSessionStage.AwaitingChatFollowup,
                    )
                ) {
                    val next = if (session.stage == FirstSessionStage.AwaitingChat) {
                        FirstSessionStage.AwaitingChatFollowup
                    } else FirstSessionStage.AwaitingFirstFood
                    val mutation = firstSessionStore?.advanceFirstSession(
                        requireNotNull(firstSessionOwnerId),
                        state.pet.petId,
                        session.stage,
                        next,
                        request.requestKey,
                        nowEpochMillis = System.currentTimeMillis(),
                    )
                    if (mutation is FirstSessionMutationResult.Applied ||
                        mutation is FirstSessionMutationResult.AlreadyApplied
                    ) {
                        val durableSession = when (mutation) {
                            is FirstSessionMutationResult.Applied -> mutation.session
                            is FirstSessionMutationResult.AlreadyApplied -> mutation.session
                            else -> error("unreachable")
                        }
                        val durablePet = when (mutation) {
                            is FirstSessionMutationResult.Applied -> mutation.pet
                            is FirstSessionMutationResult.AlreadyApplied -> mutation.pet
                            else -> error("unreachable")
                        }
                        dispatch(DashboardEvent.FirstSessionSynced(durableSession, durablePet))
                        val reaction = firstSessionReactionReply(
                            result.value.reply,
                            if (session.stage == FirstSessionStage.AwaitingChat) {
                                FirstSessionAfterNameFallback
                            } else FirstSessionAfterChatFallback,
                            durablePet.name,
                        )
                        val prompt = if (session.stage == FirstSessionStage.AwaitingChat) {
                            FirstSessionAfterName
                        } else FirstSessionAfterChat
                        dispatch(DashboardEvent.ChatSucceeded(
                            request.requestKey,
                            DashboardChatResult(
                                reply = "$reaction $prompt",
                                pet = durablePet,
                                explicitPortions = listOf(reaction, prompt),
                            ),
                        ))
                    } else dispatch(DashboardEvent.ChatFailed(request.requestKey))
                } else {
                    dispatch(DashboardEvent.ChatSucceeded(request.requestKey, result.value))
                }
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
        val session = state.firstSession
        if (session != null && session.stage in setOf(
                FirstSessionStage.AwaitingFirstFood,
                FirstSessionStage.AwaitingRemedy,
            )
        ) {
            val mutation = firstSessionStore?.applyFirstSessionFood(
                requireNotNull(firstSessionOwnerId),
                state.pet.petId,
                request.food.routeValue,
                request.requestKey,
                System.currentTimeMillis(),
            )
            delay(remainingThinkingDelayMillis(startedAt, SystemClock.elapsedRealtime()))
            if (mutation is FirstSessionMutationResult.Applied ||
                mutation is FirstSessionMutationResult.AlreadyApplied
            ) {
                val durableSession = if (mutation is FirstSessionMutationResult.Applied) mutation.session
                    else (mutation as FirstSessionMutationResult.AlreadyApplied).session
                val durablePet = if (mutation is FirstSessionMutationResult.Applied) mutation.pet
                    else (mutation as FirstSessionMutationResult.AlreadyApplied).pet
                dispatch(DashboardEvent.FirstSessionSynced(durableSession, durablePet))
                val reply = when {
                    session.stage == FirstSessionStage.AwaitingFirstFood -> FirstSessionAfterFirstFood
                    request.food == DashboardFood.LeafCrunch -> FirstSessionAfterRemedy
                    else -> BerryReply
                }
                dispatch(
                    DashboardEvent.FeedSucceeded(
                        requestKey = request.requestKey,
                        reply = reply,
                        explicitPortions = if (request.food == DashboardFood.LeafCrunch) {
                            FirstSessionAfterRemedyPortions
                        } else null,
                        autoAdvanceDelayMillis = if (request.food == DashboardFood.LeafCrunch) {
                            OnboardingBlockAutoAdvanceMillis
                        } else DashboardReplyAutoAdvanceMillis,
                    ),
                )
            } else dispatch(DashboardEvent.FeedFailed(request.requestKey))
            return@LaunchedEffect
        }
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
            firstSessionStore?.getFirstSession(
                requireNotNull(firstSessionOwnerId),
                state.pet.petId,
            )?.let { session -> dispatch(DashboardEvent.FirstSessionSynced(session, state.pet)) }
        } else {
            when (val result = executeDashboardAdapter { outfitAdapter.queue(request, state.pet) }) {
                is DashboardAdapterResult.Success -> {
                    val pending = result.value
                    dispatch(
                        DashboardEvent.OutfitQueued(
                            requestKey = request.requestKey,
                            pending = pending,
                            acceptedPet = state.pet.copy(
                                experience = (state.pet.experience - OutfitExperienceCharge)
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
        ?: state.firstSessionIdleReply.takeIf { state.mode == DashboardMode.Idle }
    LaunchedEffect(advancingReply?.requestKey, advancingReply?.portionIndex) {
        val reply = advancingReply ?: return@LaunchedEffect
        if (debugState.freezesReplyAdvance || !reply.hasNextPortion) return@LaunchedEffect
        delay(reply.autoAdvanceDelayMillis)
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
            resolveUrl = { mediaUrlPolicy?.resolve(it) },
            fixtureOnly = mediaUrlPolicy == null,
        ),
        mediaUrlPolicy = mediaUrlPolicy,
        reducedMotionOverride = reducedMotionOverride,
        unansweredEventCount = unansweredEventCount,
        onEvents = onEvents,
        onFirstSessionTravel = onFirstSessionTravel,
        mediaActive = mediaActive,
        modifier = modifier,
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
    onEvents: () -> Unit = {},
    unansweredEventCount: Int = 0,
    modifier: Modifier = Modifier,
    mediaUrlPolicy: StaticMediaUrlPolicy? = null,
    reducedMotionOverride: Boolean? = null,
) {
    val hazeState = rememberHazeState()
    val actionScrollState = rememberDashboardActionScrollState()
    Box(modifier = modifier.fillMaxSize().background(Color(0xFFBDBBB3)), contentAlignment = Alignment.TopCenter) {
        BoxWithReferenceFrame {
            val actionTop = LocalDashboardActionTop.current
            Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                DashboardVideo(
                    projection = projectDashboardMedia(
                        state,
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
            ExperiencePill(
                experience = state.experience,
                hazeState = hazeState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = DashboardExperienceTop),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(21.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 77.dp, end = 20.dp),
            ) {
                StatusRing(state.hunger, StatusKind.Hunger)
                StatusRing(state.happiness, StatusKind.Mood)
                StatusRing(state.energy, StatusKind.Energy)
            }

            CharacterDialogueText(
                message = state.message,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(19.dp),
                modifier = Modifier
                    .offset(y = actionTop)
                    .horizontalScroll(actionScrollState)
                    .padding(start = 28.dp, end = 29.dp),
            ) {
                GlassAction("Поболтать", ActionKind.Chat, hazeState, onChat)
                GlassAction(
                    "События",
                    ActionKind.Events,
                    hazeState,
                    onEvents,
                    badgeCount = unansweredEventCount,
                )
                GlassAction("Покормить", ActionKind.Feed, hazeState, onFeed)
                GlassAction("В путешествие", ActionKind.Travel, hazeState, onTravel)
                GlassAction("Нарядить", ActionKind.Outfit, hazeState, onOutfit)
            }
        }
        DashboardLevelAppBar(state.stageLabel)
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
    unansweredEventCount: Int,
    onEvents: () -> Unit,
    onFirstSessionTravel: () -> Unit,
    mediaActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val hazeState = rememberHazeState()
    val density = LocalDensity.current
    val reducedMotion = reducedMotionOverride ?: systemReducedMotionEnabled()
    var nextPetTapReactionId by remember { mutableIntStateOf(0) }
    var petTapReaction by remember { mutableStateOf<PetTapReaction?>(null) }
    var petTapHeartBursts by remember { mutableStateOf<List<PetTapHeartBurst>>(emptyList()) }
    var lastPetTapParticleAt by remember { mutableStateOf(Long.MIN_VALUE) }
    var composerLineCount by remember(state.mode) { mutableIntStateOf(1) }
    var referenceFrameTopPx by remember { mutableFloatStateOf(0f) }
    var referenceFrameScale by remember { mutableFloatStateOf(Float.NaN) }
    var inputSurfaceTopPx by remember(state.mode) { mutableFloatStateOf(Float.NaN) }
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp().value }
    val imeMotionProgress = (
        (imeBottomDp - ImeMotionStartInsetDp) / ImeMotionTravelDp
        ).coerceIn(0f, 1f)
    val composerDialogueLift = if (composerLineCount > 2) {
        ((composerLineCount - 2) * 24).dp
    } else {
        0.dp
    }
    val fallbackDialogueTop = ClosedDialogueTop -
        (ClosedDialogueTop - OpenDialogueTop) * imeMotionProgress - composerDialogueLift
    val inputTopInReference = if (inputSurfaceTopPx.isFinite() && referenceFrameScale > 0f) {
        with(density) {
            ((inputSurfaceTopPx - referenceFrameTopPx) / referenceFrameScale).toDp()
        }
    } else null
    val dialogueTop = inputTopInReference?.let {
        dialogueAnchorAboveInput(it) - composerDialogueLift
    } ?: fallbackDialogueTop
    val chatThinkingTop = inputTopInReference?.let(::thinkingIndicatorTopAboveInput)
        ?: characterThinkingIndicatorTop(dialogueTop)
    val mediaOffsetY = if (
        imeMotionProgress > 0f && (
            state.mode == DashboardMode.Chat ||
                state.mode == DashboardMode.Outfit ||
                state.mode == DashboardMode.Travel
            )
    ) (-240).dp * imeMotionProgress else 0.dp
    val promptMode = state.mode == DashboardMode.Outfit || state.mode == DashboardMode.Travel
    val showDefaultStatus = !promptMode && state.firstSession?.stage in setOf(
        null,
        FirstSessionStage.AwaitingCompletionMessage,
        FirstSessionStage.Completed,
    )
    val tapAdvanceReply = state.chatReply ?: state.feedReply ?: state.transientReply
        ?: state.firstSessionIdleReply.takeIf { state.mode == DashboardMode.Idle }
    val tapAdvanceModifier = if (
        tapAdvanceReply?.hasNextPortion == true && !debugState.freezesReplyAdvance
    ) {
        Modifier.pointerInput(tapAdvanceReply.requestKey, tapAdvanceReply.portionIndex) {
            awaitPointerEventScope {
                var downPosition: Offset? = null
                var movedBeyondTap = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                        ?: continue
                    if (!change.previousPressed && change.pressed) {
                        downPosition = change.position
                        movedBeyondTap = false
                    } else if (downPosition != null && change.pressed) {
                        val distance = (change.position - downPosition).getDistance()
                        if (distance > viewConfiguration.touchSlop) {
                            movedBeyondTap = true
                        }
                    } else if (downPosition != null && change.previousPressed && !change.pressed) {
                        if (!movedBeyondTap) {
                            onEvent(DashboardEvent.AdvanceReply(tapAdvanceReply.requestKey))
                        }
                        downPosition = null
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(tapAdvanceModifier)
            .background(Color(0xFFBDBBB3)),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithReferenceFrame(
            onReferenceFramePositioned = { coordinates ->
                val top = coordinates.localToRoot(Offset.Zero).y
                val bottom = coordinates.localToRoot(
                    Offset(0f, coordinates.size.height.toFloat()),
                ).y
                referenceFrameTopPx = top
                referenceFrameScale = (bottom - top) / coordinates.size.height
            },
        ) {
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
                    active = mediaActive,
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
                        petTapHeartBursts = appendPetTapHeartBurst(
                            current = petTapHeartBursts,
                            next = PetTapHeartBurst(nextPetTapReactionId, center),
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
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = DashboardExperienceTop),
                )
            }

            when (state.mode) {
                DashboardMode.Chat -> {
                    if (state.activeChat != null) {
                        CharacterThinkingIndicator(
                            freezeFrame = debugState.freezesMotion,
                            top = chatThinkingTop,
                        )
                    } else {
                        (state.chatReply ?: state.settledFirstSessionReply)?.let { reply ->
                            CharacterDialogueText(
                                message = reply.visibleText,
                                top = dialogueTop,
                                animateEntrance = state.chatReply != null &&
                                    !debugState.freezesMotion &&
                                    !reducedMotion,
                                onRevealComplete = if (
                                    state.chatReply != null &&
                                    !debugState.freezesReplyAdvance &&
                                    !reply.hasNextPortion &&
                                    state.firstSession != null
                                ) {
                                    { onEvent(DashboardEvent.CompleteReply(reply.requestKey)) }
                                } else null,
                            )
                        }
                    }
                }

                DashboardMode.Feed -> {
                    if (state.activeFeed != null) {
                        CharacterThinkingIndicator(
                            freezeFrame = debugState.freezesMotion,
                            top = 638.dp,
                        )
                    } else {
                        (state.feedReply ?: state.settledFirstSessionReply)?.let { reply ->
                            CharacterDialogueText(
                                message = reply.visibleText,
                                top = 630.dp,
                                animateEntrance = state.feedReply != null &&
                                    !debugState.freezesMotion &&
                                    !reducedMotion,
                                onRevealComplete = if (
                                    state.feedReply != null &&
                                    !debugState.freezesReplyAdvance &&
                                    !reply.hasNextPortion &&
                                    state.firstSession != null
                                ) {
                                    { onEvent(DashboardEvent.CompleteReply(reply.requestKey)) }
                                } else null,
                            )
                        }
                    }
                }

                DashboardMode.Outfit -> CharacterDialogueText(
                    message = OutfitPrompt,
                    top = dialogueTop,
                    animateEntrance = !debugState.freezesMotion && !reducedMotion,
                )

                DashboardMode.Travel -> CharacterDialogueText(
                    message = TravelPrompt,
                    top = dialogueTop,
                    animateEntrance = !debugState.freezesMotion && !reducedMotion,
                )

                DashboardMode.Idle -> {
                    val firstSessionReply = state.firstSessionIdleReply
                    val displaysSettledFirstSessionReply = state.transientReply == null &&
                        firstSessionReply == null &&
                        state.settledFirstSessionReply != null
                    dashboardIdleMessage(state)?.let { message ->
                        CharacterDialogueText(
                            message = message,
                            animateEntrance = !displaysSettledFirstSessionReply &&
                                !debugState.freezesMotion &&
                                !reducedMotion,
                            onRevealComplete = if (
                                !debugState.freezesReplyAdvance &&
                                firstSessionReply != null &&
                                !firstSessionReply.hasNextPortion
                            ) {
                                {
                                    onEvent(
                                        DashboardEvent.CompleteReply(firstSessionReply.requestKey),
                                    )
                                }
                            } else null,
                        )
                    }
                }
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

            if (state.mode == DashboardMode.Idle && !isFirstSessionReplyPending(state)) {
                DashboardActions(
                    hazeState = hazeState,
                    firstSessionAction = firstSessionMainAction(state.firstSession),
                    reducedMotion = reducedMotion,
                    onChat = { onEvent(DashboardEvent.OpenChat) },
                    onEvents = onEvents,
                    unansweredEventCount = unansweredEventCount,
                    onFeed = { onEvent(DashboardEvent.OpenFeed) },
                    onTravel = {
                        if (firstSessionMainAction(state.firstSession) == FirstSessionMainAction.Travel) {
                            onFirstSessionTravel()
                        } else onEvent(DashboardEvent.OpenTravel)
                    },
                    onOutfit = { onEvent(DashboardEvent.OpenOutfit) },
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
                    else -> false
                },
                hazeState = hazeState,
                requestIme = requestImeOverride
                    ?: (debugState.requestsIme || debugState == DashboardDebugState.Idle),
                reducedMotion = reducedMotion,
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
                onLineCountChange = { composerLineCount = it },
                onInputSurfaceTopChange = { inputSurfaceTopPx = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = DashboardInputHorizontalPadding)
                    .padding(bottom = 16.dp),
            )
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
        DashboardLevelAppBar(state.pet.stageLabel)
    }
}

@Composable
private fun BoxScope.DashboardStatusChrome(pet: PetDashboardState, hazeState: HazeState) {
    ExperiencePill(
        experience = pet.experience,
        hazeState = hazeState,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = DashboardExperienceTop),
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
private fun BoxScope.DashboardLevelAppBar(stageLabel: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(top = 16.dp)
            .requiredSize(220.dp, 48.dp),
    ) {
        Text(
            text = "Уровень: $stageLabel",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OpenRundeFontFamily,
            letterSpacing = (-0.15).sp,
            modifier = Modifier.graphicsLayer { scaleX = 1.044f },
        )
    }
}

@Composable
private fun BoxScope.DashboardActions(
    hazeState: HazeState,
    firstSessionAction: FirstSessionMainAction?,
    reducedMotion: Boolean,
    onChat: () -> Unit,
    onEvents: () -> Unit,
    unansweredEventCount: Int,
    onFeed: () -> Unit,
    onTravel: () -> Unit,
    onOutfit: () -> Unit,
) {
    val actionScrollState = rememberDashboardActionScrollState()
    val actionTop = LocalDashboardActionTop.current
    val onboardingEntrance = remember(firstSessionAction, reducedMotion) {
        Animatable(if (firstSessionAction != null && !reducedMotion) .6f else 1f)
    }
    LaunchedEffect(firstSessionAction, reducedMotion) {
        if (firstSessionAction != null && !reducedMotion) {
            onboardingEntrance.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 300,
                    easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f),
                ),
            )
        } else {
            onboardingEntrance.snapTo(1f)
        }
    }
    val actionModifier = if (firstSessionAction == null) {
        Modifier
            .offset(y = actionTop)
            .horizontalScroll(actionScrollState)
            .padding(start = 28.dp, end = 29.dp)
    } else {
        Modifier
            .requiredWidth(402.dp)
            .offset(y = actionTop)
    }
    Row(
        horizontalArrangement = if (firstSessionAction == null) {
            Arrangement.spacedBy(19.dp)
        } else Arrangement.Center,
        modifier = actionModifier.graphicsLayer {
            if (firstSessionAction != null) {
                scaleX = onboardingEntrance.value
                scaleY = onboardingEntrance.value
                alpha = ((onboardingEntrance.value - .6f) / .4f).coerceIn(0f, 1f)
            }
        },
    ) {
        if (firstSessionAction == null || firstSessionAction == FirstSessionMainAction.Chat) {
            GlassAction("Поболтать", ActionKind.Chat, hazeState, onChat)
        }
        if (firstSessionAction == null || firstSessionAction == FirstSessionMainAction.Feed) {
            GlassAction("Покормить", ActionKind.Feed, hazeState, onFeed)
        }
        if (firstSessionAction == null) {
            GlassAction(
                "События",
                ActionKind.Events,
                hazeState,
                onEvents,
                badgeCount = unansweredEventCount,
            )
        }
        if (firstSessionAction == null || firstSessionAction == FirstSessionMainAction.Outfit) {
            GlassAction("Нарядить", ActionKind.Outfit, hazeState, onOutfit)
        }
        if (firstSessionAction == null || firstSessionAction == FirstSessionMainAction.Travel) {
            GlassAction(
                if (firstSessionAction == FirstSessionMainAction.Travel) "Помочь летучей мыши" else "В путешествие",
                ActionKind.Travel,
                hazeState,
                onTravel,
                showGlyph = firstSessionAction != FirstSessionMainAction.Travel,
            )
        }
    }
}

@Composable
private fun rememberDashboardActionScrollState(): ScrollState {
    val density = LocalDensity.current
    return rememberScrollState(initial = with(density) { 68.dp.roundToPx() })
}

@Composable
private fun ConversationInputPanel(
    mode: DashboardMode,
    value: String,
    error: String?,
    busy: Boolean,
    hazeState: HazeState,
    requestIme: Boolean,
    reducedMotion: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLineCountChange: (Int) -> Unit,
    onInputSurfaceTopChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonPressFeedback = LocalButtonPressFeedback.current
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
    val entranceAlpha = remember(mode, requestIme, reducedMotion) {
        Animatable(if (requestIme && !reducedMotion) 0f else 1f)
    }

    LaunchedEffect(requestIme, mode) {
        if (requestIme) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(mode, requestIme, reducedMotion) {
        if (requestIme && !reducedMotion) {
            entranceAlpha.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = 220,
                    easing = CubicBezierEasing(.16f, 1f, .3f, 1f),
                ),
            )
        }
    }

    val collapsedPanelHeight = 62.dp
    val expandedPanelHeight = 134.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = DashboardInputMaxWidth)
            .height(expandedPanelHeight)
            .graphicsLayer { alpha = entranceAlpha.value },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(min = collapsedPanelHeight, max = expandedPanelHeight)
                .onGloballyPositioned { coordinates ->
                    onInputSurfaceTopChange(coordinates.positionInRoot().y)
                },
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
                    )
                    .drawBehind {
                        drawRoundRect(
                            color = DashboardGlassContract.ConversationOutline,
                            cornerRadius = CornerRadius(
                                56.dp.toPx().coerceAtMost(size.height / 2f),
                            ),
                            style = Stroke(1.dp.toPx()),
                        )
                    },
            )
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(DashboardPromptMaxLength)) },
                enabled = !busy,
                singleLine = false,
                minLines = 1,
                maxLines = 4,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = OpenRundeFontFamily,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                onTextLayout = { result ->
                    onLineCountChange(result.lineCount.coerceIn(1, 4))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 24.dp,
                        end = if (isOutfit) 126.dp else 82.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    )
                    .heightIn(min = 46.dp, max = 118.dp)
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
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 24.dp)
                        .offset(y = (-24).dp)
                        .semantics { contentDescription = error },
                )
            }
            if (isOutfit) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 8.dp)
                        .requiredSize(94.dp, 46.dp)
                        .clip(RoundedCornerShape(36.429.dp))
                        .background(Color.White)
                        .pointerInput(value, busy) {
                            detectTapGestures(onTap = {
                                if (!busy && value.trim().isNotEmpty()) {
                                    buttonPressFeedback()
                                    submit()
                                }
                            })
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Создать наряд за 200 монет"
                            onClick("Создать наряд за 200 монет") {
                                if (!busy && value.trim().isNotEmpty()) {
                                    buttonPressFeedback()
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
                    Spacer(Modifier.width(7.dp))
                    Coin(19.5.dp, monochrome = true)
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 8.dp)
                        .requiredSize(46.dp)
                        .clip(RoundedCornerShape(50))
                        .pointerInput(value, busy) {
                            detectTapGestures(onTap = {
                                if (!busy && value.trim().isNotEmpty()) {
                                    buttonPressFeedback()
                                    submit()
                                }
                            })
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Отправить"
                            onClick("Отправить") {
                                if (!busy && value.trim().isNotEmpty()) {
                                    buttonPressFeedback()
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
}

@Composable
private fun FeedModeLayer(
    state: DashboardUiState,
    hazeState: HazeState,
    freezeMotion: Boolean,
    nextRequestKey: (String) -> String,
    onEvent: (DashboardEvent) -> Unit,
) {
    val feedRowTop = dashboardFeedRowTop(LocalDashboardActionTop.current)
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
                .offset(x = 28.dp, y = feedRowTop - 42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2B1116))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .requiredSize(374.dp, DashboardFeedRowHeight)
            .offset(x = 14.dp, y = feedRowTop)
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
    val buttonPressFeedback = LocalButtonPressFeedback.current
    val density = LocalDensity.current
    val isActiveToken = state.feedToken.food == food
    val motion = if (isActiveToken) state.feedToken else FoodTokenMotion()
    val latestMotion by rememberUpdatedState(motion)
    val enabled = state.activeFeed == null && motion.phase == FoodTokenPhase.Idle &&
        !isFirstSessionReplyPending(state) &&
        !(state.firstSession?.stage == FirstSessionStage.AwaitingFirstFood &&
            food != DashboardFood.BerryBowl)
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
            buttonPressFeedback()
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
private fun BoxScope.CharacterThinkingIndicator(freezeFrame: Boolean, top: Dp) {
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
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = top)
            .requiredSize(80.dp, CharacterThinkingIndicatorHeight),
    )
}

@Composable
private fun BoxWithReferenceFrame(
    onReferenceFramePositioned: ((LayoutCoordinates) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val scale = max(maxWidth.value / 402f, maxHeight.value / 874f)
        val actionTop = dashboardActionTop(maxHeight, scale)
        CompositionLocalProvider(LocalDashboardActionTop provides actionTop) {
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.TopCenter, unbounded = true)
                    .requiredSize(402.dp, 874.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(.5f, 0f)
                    }
                    .onGloballyPositioned { coordinates ->
                        onReferenceFramePositioned?.invoke(coordinates)
                    }
                    .clip(RoundedCornerShape(0.dp)),
                content = content,
            )
        }
    }
}

internal fun dashboardActionTop(viewportHeight: Dp, scale: Float): Dp {
    require(scale > 0f)
    val visibleReferenceBottom = viewportHeight / scale
    val preferredBottom = PreferredDashboardActionTop + DashboardActionHeight
    val overlap = (preferredBottom + DashboardActionBottomMargin - visibleReferenceBottom)
        .coerceAtLeast(0.dp)
    return PreferredDashboardActionTop - overlap
}

internal fun dashboardFeedRowTop(actionTop: Dp): Dp =
    actionTop + DashboardActionHeight - DashboardFeedRowHeight

@OptIn(UnstableApi::class)
@Composable
private fun DashboardVideo(
    projection: DashboardMediaProjection,
    urlPolicy: StaticMediaUrlPolicy?,
    reducedMotion: Boolean,
    petTapReaction: PetTapReaction? = null,
    active: Boolean = true,
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
    var showPoster by remember(projection, reducedMotion, retryToken) {
        mutableStateOf(true)
    }
    val petTapVideoEffect = remember(projection, retryToken) { PetTapVideoEffect() }
    val player = remember(context, videoUrl, retryToken, petTapVideoEffect) {
        if (videoUrl == null) return@remember null
        DashboardVideoTestProbe.recordPlayerCreation()
        val builder = if (videoUrl.startsWith("asset:///")) {
            ExoPlayer.Builder(context)
        } else {
            ExoPlayer.Builder(context).setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    StaticMediaCache.dataSourceFactory(context, requireNotNull(urlPolicy)),
                ),
            )
        }
        builder.build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            val displayMetrics = context.resources.displayMetrics
            setVideoEffects(
                listOf(
                    Presentation.createForAspectRatio(
                        displayMetrics.widthPixels.toFloat() / displayMetrics.heightPixels,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                    ),
                    petTapVideoEffect,
                ),
            )
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            playWhenReady = lifecycleStarted && active
            prepare()
        }
    }
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(player, lifecycleStarted, active) {
        player?.playWhenReady = lifecycleStarted && active
    }
    LaunchedEffect(petTapReaction?.id, petTapVideoEffect, reducedMotion) {
        val reaction = petTapReaction ?: return@LaunchedEffect
        val durationMillis = if (reducedMotion) {
            PetTapReducedBulgeDurationMillis.toLong()
        } else {
            PetTapBulgeDurationMillis.toLong()
        }
        val startedAtNanos = withFrameNanos { it }
        try {
            do {
                val nowNanos = withFrameNanos { it }
                val elapsedMillis = (nowNanos - startedAtNanos) / 1_000_000f
                petTapVideoEffect.update(
                    centerX = reaction.center.x,
                    centerY = reaction.center.y,
                    width = context.resources.displayMetrics.widthPixels.toFloat(),
                    height = context.resources.displayMetrics.heightPixels.toFloat(),
                    strength = petTapBulgeStrength(elapsedMillis, reducedMotion),
                )
            } while (
                elapsedMillis < durationMillis &&
                reaction.id == petTapReaction?.id
            )
        } finally {
            petTapVideoEffect.clear()
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
                Lifecycle.Event.ON_START -> if (currentActive) player.play()
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
                    }
                },
                update = { it.player = player },
                onRelease = {
                    it.player = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        val posterAlpha by animateFloatAsState(
            targetValue = if (showPoster) 1f else 0f,
            animationSpec = tween(durationMillis = 120),
            label = "dashboard-poster-crossfade",
        )
        if (posterAlpha > 0f) {
            RemoteOrFixturePoster(
                posterUrl = posterUrl,
                urlPolicy = urlPolicy,
                retryToken = retryToken,
                onFailure = { mediaFailed = true },
                fallbackDrawable = if (projection == DashboardMediaProjection.Fixture) {
                    R.drawable.test_pet_poster
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha },
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
    fallbackDrawable: Int? = R.drawable.test_pet_poster,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val cachedBitmap = remember(posterUrl) {
        posterUrl?.let(StaticMediaCache::decodedImage)
    }
    val bitmap by produceState(cachedBitmap, posterUrl, retryToken) {
        if (posterUrl == null || urlPolicy == null) return@produceState
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = StaticMediaCache.readBytes(
                    context,
                    posterUrl,
                    urlPolicy,
                    StaticImageMaxBytes,
                )
                requireNotNull(decodeBoundedStaticImage(bytes)).also {
                    StaticMediaCache.putDecodedImage(posterUrl, it)
                }
            }.getOrNull()
        }
        if (value == null) onFailure()
    }
    val painter = bitmap?.asImageBitmap()?.let(::BitmapPainter)
        ?: fallbackDrawable?.let { painterResource(it) }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color(0xFF242424)))
    }
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
        Coin(21.7.dp)
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

@Composable
private fun BoxScope.CharacterDialogueText(
    message: String,
    top: Dp = 663.dp,
    animateEntrance: Boolean = true,
    onRevealComplete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    val speechAudio = remember(context, inspectionMode) {
        if (inspectionMode) null else DashboardSpeechAudio(context.applicationContext)
    }
    DisposableEffect(speechAudio) {
        onDispose { speechAudio?.release() }
    }
    var elapsedMillis by remember(message, animateEntrance) {
        mutableStateOf(if (animateEntrance) 0f else Float.POSITIVE_INFINITY)
    }
    val animatedUnitCount = remember(message) {
        message.count { !it.isWhitespace() }.coerceAtMost(CharacterMessageMaxAnimatedUnits)
    }
    val revealDurationMillis = CharacterMessageUnitDurationMillis +
        (animatedUnitCount - 1).coerceAtLeast(0) * CharacterMessageUnitStaggerMillis
    val latestOnRevealComplete by rememberUpdatedState(onRevealComplete)
    LaunchedEffect(message, animateEntrance) {
        if (!animateEntrance || animatedUnitCount == 0) {
            elapsedMillis = Float.POSITIVE_INFINITY
            latestOnRevealComplete?.invoke()
            return@LaunchedEffect
        }
        val startedAtNanos = withFrameNanos { it }
        launch { speechAudio?.playSequence(revealDurationMillis.toLong()) }
        do {
            val nowNanos = withFrameNanos { it }
            elapsedMillis = (nowNanos - startedAtNanos) / 1_000_000f
        } while (elapsedMillis < revealDurationMillis)
        elapsedMillis = Float.POSITIVE_INFINITY
        latestOnRevealComplete?.invoke()
    }
    val entranceFraction = if (elapsedMillis.isFinite()) {
        CharacterMessageEnterEasing.transform(
            (elapsedMillis / CharacterMessageEnterDurationMillis).coerceIn(0f, 1f),
        )
    } else {
        1f
    }
    val animatedMessage = buildAnnotatedString {
        var unitIndex = 0
        message.forEach { character ->
            if (character.isWhitespace()) {
                append(character)
            } else if (unitIndex < CharacterMessageMaxAnimatedUnits) {
                val unitFraction = if (elapsedMillis.isFinite()) {
                    CharacterMessageUnitEasing.transform(
                        (
                            (elapsedMillis - unitIndex * CharacterMessageUnitStaggerMillis) /
                                CharacterMessageUnitDurationMillis
                            ).coerceIn(0f, 1f),
                    )
                } else {
                    1f
                }
                withStyle(SpanStyle(color = Color.White.copy(alpha = unitFraction))) {
                    append(character)
                }
                unitIndex += 1
            } else {
                val tailVisible = !elapsedMillis.isFinite() || elapsedMillis >= revealDurationMillis
                withStyle(SpanStyle(color = Color.White.copy(alpha = if (tailVisible) 1f else 0f))) {
                    append(character)
                }
            }
        }
    }
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = characterMessageContainerTop(top))
            .requiredSize(356.dp, CharacterMessageMaxHeight)
            .graphicsLayer {
                alpha = entranceFraction
                scaleX = 1.035f - .035f * entranceFraction
                scaleY = 1.035f - .035f * entranceFraction
            },
    ) {
        Text(
            text = animatedMessage,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = SbSansDisplayFontFamily,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            maxLines = 6,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.requiredWidth(356.dp),
        )
    }
}

private const val CharacterMessageEnterDurationMillis = 300f
private val CharacterMessageMaxHeight = 132.dp
internal val CharacterMessageFixedBottomOffset = 55.dp
internal val CharacterThinkingIndicatorHeight = 55.5.dp
internal fun dialogueAnchorAboveInput(inputTop: Dp): Dp =
    inputTop - CharacterMessageFixedBottomOffset - DialogueInputGap
internal fun characterMessageContainerTop(anchor: Dp): Dp =
    anchor + CharacterMessageFixedBottomOffset - CharacterMessageMaxHeight
internal fun characterThinkingIndicatorTop(anchor: Dp): Dp =
    anchor + CharacterMessageFixedBottomOffset - CharacterThinkingIndicatorHeight
internal fun thinkingIndicatorTopAboveInput(inputTop: Dp): Dp =
    inputTop - DialogueInputGap - CharacterThinkingIndicatorHeight
private const val CharacterMessageUnitDurationMillis = 700f
private const val CharacterMessageUnitStaggerMillis = 24f
private const val CharacterMessageMaxAnimatedUnits = 80
private val CharacterMessageEnterEasing = CubicBezierEasing(.16f, 1f, .3f, 1f)
private val CharacterMessageUnitEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)

private enum class ActionKind { Chat, Events, Feed, Travel, Outfit }

@Composable
private fun GlassAction(
    label: String,
    kind: ActionKind,
    hazeState: HazeState,
    onClick: () -> Unit,
    badgeCount: Int = 0,
    showGlyph: Boolean = true,
) {
    val scale = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val buttonPressFeedback = LocalButtonPressFeedback.current
    val latestOnClick by rememberUpdatedState(onClick)
    val latestButtonPressFeedback by rememberUpdatedState(buttonPressFeedback)
    LaunchedEffect(pressed) {
        scale.animateTo(
            if (pressed) .92f else 1f,
            spring(
                dampingRatio = if (pressed) Spring.DampingRatioNoBouncy else .55f,
                stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMedium,
            ),
        )
    }
    Box(
        modifier = Modifier
            .widthIn(max = OnboardingActionMaxWidth)
            .height(58.203.dp)
            .scale(scale.value)
            .clip(DashboardGlassContract.ActionShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                latestButtonPressFeedback()
                latestOnClick()
            }
            .semantics {
                contentDescription = if (badgeCount > 0) {
                    "$label, требуют внимания: $badgeCount"
                } else label
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .hazeEffect(state = hazeState, style = DashboardGlassContract.ActionBlurStyle),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(DashboardGlassContract.ActionTint),
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
            modifier = Modifier
                .height(DashboardActionHeight)
                .widthIn(max = OnboardingActionMaxWidth)
                .padding(
                    start = DashboardActionStartPadding,
                    end = DashboardActionEndPadding,
                ),
        ) {
            if (showGlyph) {
                ActionGlyph(kind)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                color = Color.White,
                fontSize = 23.445.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = OpenRundeFontFamily,
                maxLines = 1,
            )
            if (badgeCount > 0) {
                Spacer(Modifier.width(8.dp))
                EventBadge(badgeCount)
            }
        }
    }
}

@Composable
private fun EventBadge(count: Int) {
    val label = if (count > 99) "99+" else count.toString()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(24.dp)
            .widthIn(min = 24.dp)
            .clip(CircleShape)
            .background(Color.Red)
            .padding(horizontal = if (label.length > 1) 5.dp else 0.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontFamily = OpenRundeFontFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 22.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ActionGlyph(kind: ActionKind) {
    val drawable = when (kind) {
        ActionKind.Chat -> R.drawable.action_chat
        ActionKind.Events -> R.drawable.action_events
        ActionKind.Feed -> R.drawable.action_feed
        ActionKind.Travel -> R.drawable.action_travel
        ActionKind.Outfit -> R.drawable.action_outfit
    }
    val modifier = if (kind == ActionKind.Outfit) {
        Modifier.requiredWidth(36.dp).requiredHeight(30.dp)
    } else {
        Modifier.size(28.dp)
    }
    Image(painterResource(drawable), contentDescription = null, modifier = modifier)
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
