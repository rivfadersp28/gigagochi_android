package com.gigagochi.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.gigagochi.app.core.background.markNotificationPermissionAsked
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.security.NotificationDeepLinkExtras
import com.gigagochi.app.core.security.NotificationStoryIdExtra
import com.gigagochi.app.core.security.NotificationTravelRequestKeyExtra
import com.gigagochi.app.core.security.notificationDeepLinkExtrasFromUntypedPresence
import com.gigagochi.app.core.webview.AndroidWebAppDataGateway
import com.gigagochi.app.core.webview.AndroidWebFeedbackHandler
import com.gigagochi.app.core.webview.BridgeDispatcher
import com.gigagochi.app.core.webview.BridgeDocumentFence
import com.gigagochi.app.core.webview.BridgeWebBundleVersion
import com.gigagochi.app.core.webview.GigagochiWebViewHost
import com.gigagochi.app.core.webview.ProductionWebAppRuntime
import com.gigagochi.app.core.webview.WebMediaReferenceRegistry
import com.gigagochi.app.core.webview.WebNotificationPermissionCoordinator
import com.gigagochi.app.core.webview.WebNotificationPermissionDecision
import com.gigagochi.app.core.webview.WebNotificationPermissionRequestHandler
import com.gigagochi.app.core.webview.WebPermissionPublication
import com.gigagochi.app.core.webview.WebTravelShareCompletionPublisher
import com.gigagochi.app.core.webview.WebTravelShareCoordinator
import com.gigagochi.app.core.webview.VerifiedWebMediaCache
import com.gigagochi.app.core.webview.projectDashboardWebMedia
import com.gigagochi.app.core.webview.webNotificationPermissionStatus
import com.gigagochi.app.feature.dashboard.DashboardFeedAudio
import com.gigagochi.app.feature.dashboard.DashboardPetTapAudio
import com.gigagochi.app.feature.events.AndroidTravelVideoSharer

/**
 * Production Android shell. Product state and rendering live in the embedded Web bundle; this
 * Activity owns only Android lifecycle, secure media, system UI and native feedback.
 */
class GigagochiWebViewActivity : ComponentActivity() {
    private var host: GigagochiWebViewHost? = null
    private var runtime: ProductionWebAppRuntime? = null
    private var feedAudio: DashboardFeedAudio? = null
    private var petTapAudio: DashboardPetTapAudio? = null
    private var webFeedback: AndroidWebFeedbackHandler? = null
    private var mediaRegistry: WebMediaReferenceRegistry? = null
    private var travelShareCoordinator: WebTravelShareCoordinator? = null
    private val notificationPermissionCoordinator = WebNotificationPermissionCoordinator()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionCoordinator.result(granted)?.let(::publishPermission)
    }

    private val webViewBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (host?.handleSystemBack() == true) return
            isEnabled = false
            try {
                onBackPressedDispatcher.onBackPressed()
            } finally {
                isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureSystemBars()
        onBackPressedDispatcher.addCallback(this, webViewBackCallback)
        GigagochiWebViewHost.configureDebugging()

        val mediaUrlPolicy = StaticMediaUrlPolicy(
            BuildConfig.BACKEND_BASE_URL,
            BuildConfig.DEBUG,
        )
        val gateway = AndroidWebAppDataGateway(
            context = applicationContext,
            preferredPetId = { intent.getStringExtra(PreferredPetIdExtra) },
        )
        val verifiedMedia = VerifiedWebMediaCache.store(applicationContext, mediaUrlPolicy)
        val registry = WebMediaReferenceRegistry(
            urlPolicy = mediaUrlPolicy,
            materializer = verifiedMedia,
            scopeProvider = gateway::currentMediaScope,
            materializationScope = lifecycleScope,
        )
        val dashboardFeedAudio = DashboardFeedAudio(applicationContext)
        val dashboardPetTapAudio = DashboardPetTapAudio(applicationContext)
        val feedback = AndroidWebFeedbackHandler(applicationContext, window.decorView)
        val productionRuntime = ProductionWebAppRuntime(
            gateway = gateway,
            appVersion = BuildConfig.VERSION_NAME,
            webBundleVersion = BridgeWebBundleVersion,
            reducedMotion = ::isReducedMotionEnabled,
            notificationPermission = {
                webNotificationPermissionStatus(applicationContext).wireValue
            },
            mediaProjection = { pet -> projectDashboardWebMedia(pet, registry) },
            mediaRegistry = registry,
            onPetTapFeedback = {
                window.decorView.post {
                    dashboardPetTapAudio.play()
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                }
            },
            onFeedFeedback = { audioIndex ->
                window.decorView.post {
                    dashboardFeedAudio.play(audioIndex)
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            },
            createForegroundHandled = {
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
            initialNotificationDeepLink = notificationDeepLinkExtras(intent),
        )
        lateinit var webHost: GigagochiWebViewHost
        val shareCoordinator = WebTravelShareCoordinator(
            scope = lifecycleScope,
            resolver = gateway::resolveTravelVideoShare,
            sharer = AndroidTravelVideoSharer(
                context = this@GigagochiWebViewActivity,
                verifiedMedia = verifiedMedia,
            )::share,
            presentFailure = {
                Toast.makeText(
                    this@GigagochiWebViewActivity,
                    ShareFailureMessage,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            publishCompletion = WebTravelShareCompletionPublisher { documentFence, payload ->
                webHost.publishTravelShareCompletion(payload, documentFence)
            },
        )
        webHost = GigagochiWebViewHost(
            activity = this,
            scope = lifecycleScope,
            dispatcher = BridgeDispatcher(
                runtime = productionRuntime,
                feedbackHandler = feedback,
                notificationPermissionHandler = WebNotificationPermissionRequestHandler(
                    ::scheduleNotificationPermissionRequest,
                ),
                travelShareHandler = shareCoordinator,
            ),
            runtime = productionRuntime,
            mediaRegistry = registry,
        )

        mediaRegistry = registry
        runtime = productionRuntime
        feedAudio = dashboardFeedAudio
        petTapAudio = dashboardPetTapAudio
        webFeedback = feedback
        travelShareCoordinator = shareCoordinator
        host = webHost
        setContentView(webHost.rootView)
        webHost.start()
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationDeepLinkExtras(intent)?.let { extras ->
            runtime?.offerNotificationDeepLink(extras)
        }
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        travelShareCoordinator?.close()
        travelShareCoordinator = null
        runtime?.close()
        runtime = null
        feedAudio?.release()
        feedAudio = null
        petTapAudio?.release()
        petTapAudio = null
        webFeedback?.close()
        webFeedback = null
        mediaRegistry?.close()
        mediaRegistry = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureSystemBars()
    }

    private fun isReducedMotionEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    private fun scheduleNotificationPermissionRequest(documentFence: BridgeDocumentFence) {
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            when (
                val decision = notificationPermissionCoordinator.request(
                    documentFence = documentFence,
                    currentStatus = webNotificationPermissionStatus(applicationContext),
                )
            ) {
                WebNotificationPermissionDecision.AwaitResult -> Unit
                is WebNotificationPermissionDecision.Publish -> {
                    publishPermission(decision.publication)
                }
                is WebNotificationPermissionDecision.LaunchPrompt -> {
                    markNotificationPermissionAsked(applicationContext)
                    if (
                        runCatching {
                            notificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }.isFailure
                    ) {
                        notificationPermissionCoordinator.result(granted = false)
                            ?.let(::publishPermission)
                    }
                }
            }
        }
    }

    private fun publishPermission(publication: WebPermissionPublication) {
        host?.publishPermissionChanged(publication.status, publication.documentFence)
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
            isAppearanceLightNavigationBars = false
        }
    }

    private companion object {
        const val PreferredPetIdExtra = "gigagochi.petId"
        const val ShareFailureMessage = "Не удалось открыть отправку видео"
    }
}

internal fun notificationDeepLinkExtras(intent: Intent): NotificationDeepLinkExtras? = try {
    notificationDeepLinkExtras(intent.extras)
} catch (_: RuntimeException) {
    notificationDeepLinkExtrasFromUntypedPresence(
        storyIdPresent = true,
        storyIdReadable = false,
    )
}

internal fun notificationDeepLinkExtras(bundle: Bundle?): NotificationDeepLinkExtras? {
    val story = bundle.readNotificationExtra(NotificationStoryIdExtra)
    val travel = bundle.readNotificationExtra(NotificationTravelRequestKeyExtra)
    return notificationDeepLinkExtrasFromUntypedPresence(
        storyIdPresent = story.present,
        storyIdValue = story.value,
        storyIdReadable = story.readable,
        travelRequestKeyPresent = travel.present,
        travelRequestKeyValue = travel.value,
        travelRequestKeyReadable = travel.readable,
    )
}

private data class NotificationBundleExtra(
    val present: Boolean,
    val value: Any? = null,
    val readable: Boolean = true,
)

@Suppress("DEPRECATION")
private fun Bundle?.readNotificationExtra(key: String): NotificationBundleExtra {
    if (this == null) return NotificationBundleExtra(present = false)
    return try {
        if (!containsKey(key)) {
            NotificationBundleExtra(present = false)
        } else {
            NotificationBundleExtra(present = true, value = get(key))
        }
    } catch (_: RuntimeException) {
        // BadParcelableException and type-unparcel failures are an invalid notification target.
        NotificationBundleExtra(present = true, readable = false)
    }
}
