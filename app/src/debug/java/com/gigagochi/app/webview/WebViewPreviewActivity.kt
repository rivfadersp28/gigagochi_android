package com.gigagochi.app.webview

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.gigagochi.app.BuildConfig
import com.gigagochi.app.core.background.CompletionSyncUniqueWorkName
import com.gigagochi.app.core.background.MvpSyncUniqueWorkName
import com.gigagochi.app.core.network.StaticMediaUrlPolicy
import com.gigagochi.app.core.webview.AndroidWebAppDataGateway
import com.gigagochi.app.core.webview.AndroidWebFeedbackHandler
import com.gigagochi.app.core.webview.BridgeDispatcher
import com.gigagochi.app.core.webview.BridgeDocumentFence
import com.gigagochi.app.core.webview.BridgeWebBundleVersion
import com.gigagochi.app.core.webview.GigagochiWebViewHost
import com.gigagochi.app.core.webview.ProductionWebAppRuntime
import com.gigagochi.app.core.webview.WebAppRuntime
import com.gigagochi.app.core.webview.WebAppGenerationPolicy
import com.gigagochi.app.core.webview.WebMediaReferenceRegistry
import com.gigagochi.app.core.webview.WebNotificationPermissionRequestHandler
import com.gigagochi.app.core.webview.WebNotificationPermissionStatus
import com.gigagochi.app.core.webview.WebTravelShareAcceptResult
import com.gigagochi.app.core.webview.WebTravelShareCompletionPayload
import com.gigagochi.app.core.webview.WebTravelShareCompletionStatus
import com.gigagochi.app.core.webview.WebTravelShareRequestHandler
import com.gigagochi.app.core.webview.WebViewHostQaController
import com.gigagochi.app.core.webview.VerifiedWebMediaCache
import com.gigagochi.app.core.webview.projectDashboardWebMedia
import com.gigagochi.app.feature.dashboard.DashboardFeedAudio
import com.gigagochi.app.feature.dashboard.DashboardPetTapAudio

class WebViewPreviewActivity : ComponentActivity() {
    private var host: GigagochiWebViewHost? = null
    private var runtimeOwner: AutoCloseable? = null
    private var feedAudio: DashboardFeedAudio? = null
    private var petTapAudio: DashboardPetTapAudio? = null
    private var webFeedback: AndroidWebFeedbackHandler? = null
    private var mediaRegistry: WebMediaReferenceRegistry? = null
    private var qaController: WebViewHostQaController? = null
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
        val useProductionRuntime = intent.getBooleanExtra(
            DebugProductionRuntimeRouting.ProductionRuntimeIntentExtra,
            false,
        )
        val generationPolicy = DebugProductionRuntimeRouting.generationPolicy(
            useProductionRuntime = useProductionRuntime,
            userInvocationsOnlyRequested = intent.getBooleanExtra(
                DebugProductionRuntimeRouting.UserInvocationsOnlyIntentExtra,
                false,
            ),
        )
        if (generationPolicy == WebAppGenerationPolicy.UserInvocationsOnly) {
            cancelPreExistingBackgroundFeatureSync()
        }
        val enableQaHostFixture = intent.getBooleanExtra(WebViewHostQaFixture.IntentExtra, false)
        if (enableQaHostFixture) WebViewHostQaFixture.reset()
        val initialFixture = DebugWebAppFixtureRouting.resolve(
            intent.getStringExtra(DebugWebAppFixtureRouting.IntentExtra),
        )
        val runtime = createRuntime(useProductionRuntime, initialFixture, generationPolicy)
        val feedback = AndroidWebFeedbackHandler(applicationContext, window.decorView)
        webFeedback = feedback
        val hostQaController = WebViewHostQaController().takeIf { enableQaHostFixture }
        qaController = hostQaController
        val nextHost = GigagochiWebViewHost(
            activity = this,
            scope = lifecycleScope,
            dispatcher = BridgeDispatcher(
                runtime = runtime,
                feedbackHandler = feedback,
                notificationPermissionHandler = WebNotificationPermissionRequestHandler(
                    ::scheduleDebugNotificationPermissionResult,
                ),
                travelShareHandler = WebTravelShareRequestHandler { request ->
                    if (useProductionRuntime) {
                        WebTravelShareAcceptResult.Invalid
                    } else {
                        // Fixture QA only: complete the two-phase contract without share I/O.
                        val requestKey = request.requestKey
                            ?: return@WebTravelShareRequestHandler WebTravelShareAcceptResult.Invalid
                        window.decorView.post {
                            host?.publishTravelShareCompletion(
                                WebTravelShareCompletionPayload(
                                    requestKey = requestKey,
                                    status = WebTravelShareCompletionStatus.Opened,
                                ),
                                request.documentFence,
                            )
                        }
                        WebTravelShareAcceptResult.Accepted
                    }
                },
            ),
            runtime = runtime,
            mediaRegistry = mediaRegistry,
            qaObserver = WebViewHostQaFixture.observer.takeIf { enableQaHostFixture },
            qaController = hostQaController,
        )
        host = nextHost
        setContentView(nextHost.rootView)
        nextHost.start()
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        runtimeOwner?.close()
        runtimeOwner = null
        feedAudio?.release()
        feedAudio = null
        petTapAudio?.release()
        petTapAudio = null
        webFeedback?.close()
        webFeedback = null
        mediaRegistry?.close()
        mediaRegistry = null
        qaController = null
        super.onDestroy()
    }

    /**
     * Instrumentation-only entry into the same replacement branch as onRenderProcessGone.
     * No external Intent, Web message, or release activity can invoke this method.
     */
    internal fun exerciseRendererRecoveryForQa(): Boolean =
        qaController?.requestRendererRecovery() == true

    /**
     * Asks the installed WebView provider to terminate the live renderer process. This is exposed
     * only to same-process debug instrumentation and proves the real onRenderProcessGone callback.
     */
    internal fun exerciseRendererTerminationForQa(): Boolean =
        qaController?.requestRendererTermination() == true

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureSystemBars()
    }

    private fun scheduleDebugNotificationPermissionResult(documentFence: BridgeDocumentFence) {
        window.decorView.post {
            host?.publishPermissionChanged(
                WebNotificationPermissionStatus.Denied,
                documentFence,
            )
        }
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

    @SuppressLint("InlinedApi")
    private fun createRuntime(
        useProductionRuntime: Boolean,
        initialFixture: String,
        generationPolicy: WebAppGenerationPolicy,
    ): WebAppRuntime {
        if (!useProductionRuntime) {
            return DebugWebAppRuntime(initialRoute = initialFixture)
        }
        val gateway = AndroidWebAppDataGateway(
            context = applicationContext,
            generationPolicy = generationPolicy,
        )
        val mediaPolicy = StaticMediaUrlPolicy(
            BuildConfig.BACKEND_BASE_URL,
            BuildConfig.DEBUG,
        )
        val verifiedMedia = VerifiedWebMediaCache.store(applicationContext, mediaPolicy)
        val registry = WebMediaReferenceRegistry(
            urlPolicy = mediaPolicy,
            materializer = verifiedMedia,
            scopeProvider = gateway::currentMediaScope,
            materializationScope = lifecycleScope,
        )
        mediaRegistry = registry
        val dashboardFeedAudio = DashboardFeedAudio(applicationContext)
        val audio = DashboardPetTapAudio(applicationContext)
        feedAudio = dashboardFeedAudio
        petTapAudio = audio
        return ProductionWebAppRuntime(
            gateway = gateway,
            appVersion = BuildConfig.VERSION_NAME,
            webBundleVersion = BridgeWebBundleVersion,
            reducedMotion = {
                runCatching {
                    Settings.Global.getFloat(
                        contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE,
                        1f,
                    ) == 0f
                }.getOrDefault(false)
            },
            notificationPermission = { "unknown" },
            mediaProjection = { pet -> projectDashboardWebMedia(pet, registry) },
            mediaRegistry = registry,
            onPetTapFeedback = {
                audio.play()
                window.decorView.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            },
            onFeedFeedback = { audioIndex ->
                dashboardFeedAudio.play(audioIndex)
                window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            },
            createForegroundHandled = {
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        ).also { runtimeOwner = it }
    }

    /**
     * The opt-in gate is normally launched against clean app data. Cancelling both unique workers
     * also protects a repeated debug session from work scheduled before the gate was enabled.
     */
    private fun cancelPreExistingBackgroundFeatureSync() {
        WorkManager.getInstance(applicationContext).apply {
            cancelUniqueWork(MvpSyncUniqueWorkName)
            cancelUniqueWork(CompletionSyncUniqueWorkName)
        }
    }
}
