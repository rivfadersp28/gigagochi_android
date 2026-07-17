package com.gigagochi.app.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import com.gigagochi.app.core.auth.AuthFailure
import com.gigagochi.app.core.auth.AuthFailureKind
import com.gigagochi.app.core.auth.CredentialManagerGoogleCredentialProvider
import com.gigagochi.app.core.auth.GoogleAuthConfiguration
import com.gigagochi.app.core.auth.GoogleAuthCredential
import com.gigagochi.app.core.auth.GoogleAuthRuntimeConfiguration
import com.gigagochi.app.core.auth.GoogleCredentialProvider
import com.gigagochi.app.core.auth.GoogleCredentialResult
import com.gigagochi.app.core.auth.HttpGoogleSessionExchange
import com.gigagochi.app.core.auth.MissingGoogleAuthConfiguration
import com.gigagochi.app.core.auth.SessionExchange
import com.gigagochi.app.core.auth.SessionRepository
import com.gigagochi.app.core.auth.androidSessionRepository
import com.gigagochi.app.core.auth.googleAuthConfiguration
import com.gigagochi.app.core.designsystem.GigagochiTheme
import com.gigagochi.app.core.designsystem.OpenRundeFontFamily
import com.gigagochi.app.core.model.SensitiveToken
import com.gigagochi.app.core.model.Session
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.max

enum class AuthDebugState(val routeValue: String) {
    MissingConfiguration("missing-config"),
    Ready("ready"),
    CredentialPending("credential-pending"),
    ExchangePending("exchange-pending"),
    Error("error");

    companion object {
        fun fromRouteValue(value: String?): AuthDebugState? = entries.firstOrNull {
            it.routeValue == value
        }
    }
}

internal object AuthMediaTestProbe {
    var createdPlayerCount: Int = 0
        private set

    fun reset() {
        createdPlayerCount = 0
    }

    fun recordPlayerCreation() {
        createdPlayerCount += 1
    }
}

private object AuthGlassContract {
    val Shape = RoundedCornerShape(32.dp)
    val Tint = Color(0x52071219)
    val Style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(Tint)),
        blurRadius = 18.5.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Tint),
    )
}

private val GoogleSignInFontFamily = FontFamily(
    Font(
        resId = R.font.google_sans_medium,
        weight = FontWeight.Medium,
    ),
)

@Composable
fun GoogleAuthRoute(
    debugState: AuthDebugState? = null,
    configurationOverride: GoogleAuthConfiguration? = null,
    credentialProviderOverride: GoogleCredentialProvider? = null,
    sessionExchangeOverride: SessionExchange? = null,
    sessionRepositoryOverride: SessionRepository? = null,
    reducedMotionOverride: Boolean? = null,
    onAuthenticated: (Session) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = remember(configurationOverride) {
        configurationOverride ?: googleAuthConfiguration()
    }
    val credentialProvider = credentialProviderOverride ?: remember(activity) {
        activity?.let(::CredentialManagerGoogleCredentialProvider)
    }
    val sessionRepository = sessionRepositoryOverride ?: remember(context) {
        androidSessionRepository(context.applicationContext)
    }
    var state by remember(debugState, configuration) {
        mutableStateOf(
            debugState?.let(::authDebugFixture) ?: initialGoogleAuthState(configuration),
        )
    }
    var requestSequence by remember { mutableIntStateOf(0) }
    val reducedMotion = reducedMotionOverride ?: rememberAuthReducedMotionPreference()
    val freezesAsync = debugState != null

    LaunchedEffect(state) {
        val pending = state as? GoogleAuthState.CredentialPending ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        val result = credentialProvider?.requestCredential(
            pending.configuration.webClientId,
        ) ?: GoogleCredentialResult.Failure(AuthFailure(AuthFailureKind.Configuration))
        state = reduceGoogleAuth(
            state,
            GoogleAuthEvent.CredentialFinished(pending.requestKey, result),
        )
    }

    LaunchedEffect(state) {
        val pending = state as? GoogleAuthState.ExchangePending ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        val exchange = sessionExchangeOverride ?: HttpGoogleSessionExchange(
            baseUrl = pending.configuration.backendBaseUrl,
            allowDebugLoopbackHttp = pending.configuration.allowDebugLoopbackHttp,
        )
        val result = exchange.exchangeGoogleCredential(pending.credential)
        state = reduceGoogleAuth(
            state,
            GoogleAuthEvent.ExchangeFinished(pending.requestKey, result),
        )
    }

    LaunchedEffect(state) {
        val pending = state as? GoogleAuthState.PersistencePending ?: return@LaunchedEffect
        if (freezesAsync) return@LaunchedEffect
        val result = sessionRepository.save(pending.session)
        state = reduceGoogleAuth(
            state,
            GoogleAuthEvent.PersistenceFinished(pending.requestKey, result),
        )
    }

    LaunchedEffect(state) {
        val authenticated = state as? GoogleAuthState.Authenticated ?: return@LaunchedEffect
        onAuthenticated(authenticated.session)
    }

    GoogleAuthScreen(
        state = state,
        reducedMotion = reducedMotion,
        onPrimaryAction = {
            requestSequence += 1
            state = reduceGoogleAuth(
                state,
                when (state) {
                    is GoogleAuthState.Ready -> GoogleAuthEvent.BeginSignIn(
                        "google-auth-$requestSequence",
                    )
                    is GoogleAuthState.Error -> GoogleAuthEvent.Retry(
                        "google-auth-$requestSequence",
                    )
                    else -> GoogleAuthEvent.BeginSignIn("ignored-$requestSequence")
                },
            )
        },
    )
}

@Composable
fun GoogleAuthScreen(
    state: GoogleAuthState,
    reducedMotion: Boolean,
    onPrimaryAction: () -> Unit,
) {
    val hazeState = rememberHazeState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071219)),
        contentAlignment = Alignment.TopCenter,
    ) {
        AuthReferenceFrame { safeInsets ->
            val foreground = authForegroundGeometry(
                safeTop = safeInsets.top,
                safeBottom = safeInsets.bottom,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
            ) {
                AuthSceneVideo(
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
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                .48f to Color(0x24040C11),
                                1f to Color(0xE8071219),
                            ),
                        ),
                )
            }

            Text(
                text = "GIGAGOCHI",
                color = Color.White.copy(alpha = .82f),
                fontFamily = OpenRundeFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = foreground.wordmarkY.dp),
            )

            AuthPanel(
                state = state,
                hazeState = hazeState,
                reducedMotion = reducedMotion,
                onPrimaryAction = onPrimaryAction,
                modifier = Modifier.offset(x = 20.dp, y = foreground.panelY.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.AuthPanel(
    state: GoogleAuthState,
    hazeState: HazeState,
    reducedMotion: Boolean,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = authContent(state)
    val buttonEnabled = state is GoogleAuthState.Ready || state is GoogleAuthState.Error
    val isPending =
        state is GoogleAuthState.CredentialPending ||
            state is GoogleAuthState.ExchangePending ||
            state is GoogleAuthState.PersistencePending

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .requiredSize(362.dp, 322.dp)
            .clip(AuthGlassContract.Shape)
            .hazeEffect(hazeState, AuthGlassContract.Style)
            .border(1.dp, Color.White.copy(alpha = .16f), AuthGlassContract.Shape)
            .padding(horizontal = 24.dp, vertical = 25.dp)
            .semantics { contentDescription = "Экран входа Gigagochi" },
    ) {
            Text(
                text = content.title,
                color = Color.White,
                fontFamily = OpenRundeFontFamily,
                fontSize = 29.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 33.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = content.body,
                color = if (state is GoogleAuthState.Error) {
                    Color(0xFFFFD7D2)
                } else {
                    Color.White.copy(alpha = .76f)
                },
                fontFamily = OpenRundeFontFamily,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (state is GoogleAuthState.Error) {
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = content.body
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
            Spacer(Modifier.weight(1f))
            if (isPending) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = content.pendingDescription
                    },
                ) {
                    if (!reducedMotion) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = content.pendingDescription,
                        color = Color.White.copy(alpha = .78f),
                        fontFamily = OpenRundeFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(13.dp))
            }
            GoogleBrandButton(
                enabled = buttonEnabled,
                onClick = onPrimaryAction,
            )
    }
}

private data class AuthContent(
    val title: String,
    val body: String,
    val pendingDescription: String = "",
)

private fun authContent(state: GoogleAuthState): AuthContent = when (state) {
    is GoogleAuthState.MissingConfiguration -> AuthContent(
        title = "Вход ещё не настроен",
        body = buildString {
            append("Добавьте ")
            val labels = state.reasons.map { reason ->
                when (reason) {
                    MissingGoogleAuthConfiguration.WebClientId -> "Google Web client ID"
                    MissingGoogleAuthConfiguration.BackendBaseUrl -> "HTTPS адрес backend"
                }
            }
            append(labels.joinToString(" и "))
            append(" в локальную конфигурацию.")
        },
    )
    is GoogleAuthState.Ready -> AuthContent(
        title = "Создай своего питомца",
        body = "Войди через Google, чтобы сохранить его историю и продолжить на другом устройстве.",
    )
    is GoogleAuthState.CredentialPending -> AuthContent(
        title = "Выбери аккаунт Google",
        body = "Используем только безопасное системное окно Google.",
        pendingDescription = "Ожидаем выбор аккаунта",
    )
    is GoogleAuthState.ExchangePending -> AuthContent(
        title = "Проверяем вход",
        body = "Подтверждаем сессию на сервере. Это займёт несколько секунд.",
        pendingDescription = "Связываемся с сервером",
    )
    is GoogleAuthState.PersistencePending -> AuthContent(
        title = "Проверяем вход",
        body = "Безопасно сохраняем сессию на этом устройстве.",
        pendingDescription = "Сохраняем вход",
    )
    is GoogleAuthState.Error -> AuthContent(
        title = "Не получилось войти",
        body = state.failure.message,
    )
    is GoogleAuthState.Authenticated -> AuthContent(
        title = "Вход выполнен",
        body = "Переходим к созданию питомца.",
    )
}

@Composable
private fun GoogleBrandButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .requiredSize(240.dp, 48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Войти через Google"
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .requiredSize(240.dp, 40.dp)
                .graphicsLayer { alpha = if (enabled) 1f else .65f }
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFF747775), RoundedCornerShape(20.dp))
                .padding(start = 12.dp, end = 12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.google_g_logo),
                contentDescription = null,
                modifier = Modifier.requiredSize(20.dp, 20.4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Войти через Google",
                color = Color(0xFF1F1F1F),
                fontFamily = GoogleSignInFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                maxLines = 1,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun AuthSceneVideo(
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current || reducedMotion) {
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
    var showPoster by remember { mutableStateOf(true) }
    val player = remember(context) {
        AuthMediaTestProbe.recordPlayerCreation()
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse("asset:///media/openai-normal.mp4")))
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
                    .inflate(R.layout.view_dashboard_player, null, false) as PlayerView).apply {
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
                painter = painterResource(R.drawable.test_pet_poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private data class AuthSafeInsets(
    val top: Float,
    val bottom: Float,
)

internal data class AuthForegroundGeometry(
    val wordmarkY: Float,
    val panelY: Float,
)

internal fun authForegroundGeometry(
    safeTop: Float,
    safeBottom: Float,
): AuthForegroundGeometry {
    val wordmarkY = max(72f, safeTop + 16f)
    val panelY = (528f - max(0f, safeBottom - 24f))
        .coerceAtLeast(safeTop + 24f)
    return AuthForegroundGeometry(wordmarkY = wordmarkY, panelY = panelY)
}

@Composable
private fun AuthReferenceFrame(
    content: @Composable BoxScope.(AuthSafeInsets) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val density = LocalDensity.current
        val scale = max(maxWidth.value / 402f, maxHeight.value / 874f)
        val safeInsets = AuthSafeInsets(
            top = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value } / scale,
            bottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp().value } / scale,
        )
        Box(
            modifier = Modifier
                .requiredSize(402.dp, 874.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(.5f, .5f)
                },
            content = { content(safeInsets) },
        )
    }
}

@Composable
private fun rememberAuthReducedMotionPreference(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun authDebugFixture(debugState: AuthDebugState): GoogleAuthState {
    val configuration = GoogleAuthRuntimeConfiguration(
        webClientId = "debug-review.apps.googleusercontent.com",
        backendBaseUrl = "https://api.review.invalid/",
        allowDebugLoopbackHttp = false,
    )
    val credential = GoogleAuthCredential(
        idToken = SensitiveToken.of("debug-redacted-id-token"),
        nonce = "debugreviewnonce_abcdefghijklmnopqrstuvwxyz",
    )
    return when (debugState) {
        AuthDebugState.MissingConfiguration -> GoogleAuthState.MissingConfiguration(
            setOf(
                MissingGoogleAuthConfiguration.WebClientId,
                MissingGoogleAuthConfiguration.BackendBaseUrl,
            ),
        )
        AuthDebugState.Ready -> GoogleAuthState.Ready(configuration)
        AuthDebugState.CredentialPending -> GoogleAuthState.CredentialPending(
            configuration = configuration,
            requestKey = "debug-credential-pending",
        )
        AuthDebugState.ExchangePending -> GoogleAuthState.ExchangePending(
            configuration = configuration,
            requestKey = "debug-exchange-pending",
            credential = credential,
        )
        AuthDebugState.Error -> GoogleAuthState.Error(
            configuration = configuration,
            failure = AuthFailure(AuthFailureKind.Network),
        )
    }
}

@Preview(widthDp = 402, heightDp = 874, showBackground = true)
@Composable
private fun GoogleAuthReadyPreview() {
    GigagochiTheme {
        GoogleAuthScreen(
            state = authDebugFixture(AuthDebugState.Ready),
            reducedMotion = true,
            onPrimaryAction = {},
        )
    }
}
