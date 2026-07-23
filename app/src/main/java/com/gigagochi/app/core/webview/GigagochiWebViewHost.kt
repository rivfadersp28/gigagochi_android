package com.gigagochi.app.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.gigagochi.app.BuildConfig
import com.gigagochi.app.R
import java.io.ByteArrayInputStream
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.encodeToJsonElement

internal const val GigagochiWebOrigin = "https://appassets.androidplatform.net"
internal const val GigagochiWebRootUrl = "$GigagochiWebOrigin/assets/web/index.html"
internal const val GigagochiBridgeObject = "gigagochiNative"

internal class GigagochiWebViewHost(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val dispatcher: BridgeDispatcher,
    private val runtime: WebAppRuntime,
    mediaRegistry: WebMediaReferenceRegistry? = null,
    private val qaObserver: WebViewHostQaObserver? = null,
    private val qaController: WebViewHostQaController? = null,
) {
    val rootView: FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
    }

    private val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
        .addPathHandler("/res/", NativeResourcePathHandler(activity))
        .build()
    private val mediaProxy = mediaRegistry?.let {
        WebMediaProxy(activity.applicationContext, it)
    }
    private val mediaRegistry = mediaRegistry
    private var webView: WebView? = null
    private var typographyScriptHandler: ScriptHandler? = null
    private var fontReadinessCoordinator: WebDocumentFontReadinessCoordinator? = null
    private var fontReadinessGeneration: Long? = null
    private var activeReply: JavaScriptReplyProxy? = null
    private var documentGeneration = 0L
    private val bridgeRequestIngress: WebBridgeRequestIngress<JavaScriptReplyProxy> =
        WebBridgeRequestIngress(
            scope = scope,
            currentGeneration = { documentGeneration },
            handleRequest = { pending ->
                activeReply = pending.reply
                dispatcher.handle(pending.raw)
            },
            postResponse = { pending, response ->
                if (runCatching { pending.reply.postMessage(response) }.isFailure) {
                    if (activeReply === pending.reply) {
                        activeReply = null
                        lifecycleEventController.resetForDocument()
                    }
                } else if (pending.hostGeneration == documentGeneration) {
                    val responseEnvelope = runCatching {
                        BridgeCodec.json.decodeFromString<BridgeResponseEnvelope>(response)
                    }.getOrNull()
                    val establishesObserver =
                        responseEnvelope?.ok == true &&
                            responseEnvelope.bridgeSessionId != null
                    if (establishesObserver) {
                        // The response is posted before this fence opens. Web message ordering then
                        // lets JS install bridgeSessionId before any session-scoped native event.
                        lifecycleEventController.markObserverReady()
                        publishLifecycle(currentLifecycleState)
                        if (qaObserver != null) {
                            val request = runCatching {
                                BridgeCodec.decodeRequest(pending.raw)
                            }.getOrNull()
                            if (request?.method == "bootstrap") {
                                publishQaBootstrapDelivered(
                                    pending = pending,
                                    request = request,
                                    bridgeSessionId = requireNotNull(
                                        responseEnvelope?.bridgeSessionId,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            monotonicClock = BridgeMonotonicClock { SystemClock.elapsedRealtimeNanos() },
        )
    private val rendererExitTimes = ArrayDeque<Long>()
    private val nativeEvents = Channel<PendingNativeEvent>(Channel.UNLIMITED)
    private val lifecycleEventController = WebLifecycleEventController()
    private var currentLifecycleState = WebLifecycleState.Background
    private val systemBackCoordinator = WebSystemBackHostCoordinator(
        requestSystemBack = dispatcher::requestSystemBack,
        enqueue = ::enqueueSystemBack,
        releaseSystemBack = dispatcher::releaseSystemBack,
    )
    private var preparingImeAnimation: WindowInsetsAnimationCompat? = null
    private var activeImeAnimation: WindowInsetsAnimationCompat? = null
    private var activeImeBounds: WebImeAnimationBounds? = null
    private var lastSafeAreaSnapshot: WebSafeAreaSnapshot? = null

    init {
        installInsetsListener()
        qaController?.attachRendererControls(
            recovery = ::exerciseRendererRecoverySeam,
            termination = ::exerciseRendererTerminationForQa,
        )
        scope.launch {
            for (pending in nativeEvents) postNativeEvent(pending)
        }
        scope.launch {
            runtime.events.collect { event ->
                enqueueNativeEvent(event)
            }
        }
    }

    fun start() {
        if (supportsRequiredWebViewFeatures(WebViewFeature::isFeatureSupported)) {
            replaceWebView()
        } else {
            showCompatibilityError()
        }
    }

    fun onResume() {
        currentLifecycleState = WebLifecycleState.Foreground
        webView?.onResume()
        runtime.onForeground()
        publishLifecycle(currentLifecycleState)
    }

    fun onPause() {
        currentLifecycleState = WebLifecycleState.Background
        runtime.onBackground()
        publishLifecycle(currentLifecycleState)
        webView?.onPause()
    }

    fun handleSystemBack(): Boolean = systemBackCoordinator.handleSystemBack()

    fun publishPermissionChanged(
        status: WebNotificationPermissionStatus,
        documentFence: BridgeDocumentFence,
    ) {
        enqueueNativeEvent(permissionChangedEvent(status), documentFence)
    }

    fun publishTravelShareCompletion(
        payload: WebTravelShareCompletionPayload,
        documentFence: BridgeDocumentFence,
    ) {
        enqueueNativeEvent(webTravelShareCompletedEvent(payload), documentFence)
    }

    fun destroy() {
        bridgeRequestIngress.close()
        qaController?.detachRendererControls()
        cancelFontReadinessProbe()
        webView?.let(::disposeWebView)
        rootView.removeAllViews()
        invalidateWebDocument()
        nativeEvents.close()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(hostGeneration: Long): WebView? {
        val view = GigagochiLockedDownWebView(activity)
        return configureRequiredWebResourceOrDispose(
            configure = {
                view.apply {
                    val messageGeneration = WebMessageDocumentGeneration(hostGeneration)
                    setBackgroundColor(Color.BLACK)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    isFocusable = true
                    isFocusableInTouchMode = true
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        databaseEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mediaPlaybackRequiresUserGesture = false
                        builtInZoomControls = false
                        displayZoomControls = false
                        // CSS receives Android's exact non-linear SP values at document start.
                        // Keeping WebView at 100 prevents a second, linear fontScale application.
                        textZoom = NeutralWebViewTextZoomPercent
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    enableSafeBrowsingWhenSupported(settings)
                    val configuredWebView = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(false)
                        setAcceptThirdPartyCookies(configuredWebView, false)
                    }
                    setDownloadListener { _, _, _, _, _ -> Unit }
                    webChromeClient = LockedDownChromeClient()
                    webViewClient = LocalContentClient(messageGeneration)
                    // Both required registrations precede loadUrl; the document-start handler is
                    // retained until this exact WebView is disposed.
                    installBridgeListener(this, messageGeneration)
                    typographyScriptHandler = installTypographyBootstrap(this)
                }
            },
            dispose = { disposeUnattachedWebView(view) },
        )
    }

    private fun installTypographyBootstrap(view: WebView): ScriptHandler {
        val script = webTypographyDocumentStartScript(activity.resources)
        return WebViewCompat.addDocumentStartJavaScript(
            view,
            script,
            setOf(GigagochiWebOrigin),
        )
    }

    private fun installBridgeListener(
        view: WebView,
        messageGeneration: WebMessageDocumentGeneration,
    ) {
        WebViewCompat.addWebMessageListener(
            view,
            GigagochiBridgeObject,
            setOf(GigagochiWebOrigin),
            WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, reply ->
                if (!isMainFrame || sourceOrigin.toString() != GigagochiWebOrigin) {
                    return@WebMessageListener
                }
                val raw = message.data ?: return@WebMessageListener
                // Each callback is stamped with the page generation tracked by onPageStarted, so
                // work already queued before the next document is fenced before native handling.
                val generation = messageGeneration.current()
                when (bridgeRequestIngress.offer(raw, reply, generation)) {
                    WebBridgeIngressAdmission.Accepted,
                    WebBridgeIngressAdmission.Closed,
                    -> Unit

                    WebBridgeIngressAdmission.PayloadTooLarge -> {
                        if (generation == documentGeneration) {
                            postIngressFailure(reply, raw, "PAYLOAD_TOO_LARGE", retryable = false)
                        }
                    }

                    WebBridgeIngressAdmission.RateLimited -> {
                        if (generation == documentGeneration) {
                            postIngressFailure(reply, raw, "RATE_LIMITED", retryable = true)
                        }
                    }

                    WebBridgeIngressAdmission.QueueFull -> {
                        if (generation == documentGeneration) {
                            postIngressFailure(reply, raw, "BRIDGE_QUEUE_FULL", retryable = true)
                        }
                    }
                }
            },
        )
    }

    private fun postIngressFailure(
        reply: JavaScriptReplyProxy,
        raw: String,
        code: String,
        retryable: Boolean,
    ) {
        runCatching {
            reply.postMessage(dispatcher.ingressFailure(raw, code, retryable))
        }
    }

    private fun replaceWebView() {
        webView?.let(::disposeWebView)
        rootView.removeAllViews()
        invalidateWebDocument()
        val next = createWebView(documentGeneration) ?: run {
            showCompatibilityError()
            return
        }
        webView = next
        rootView.addView(
            next,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        if (currentLifecycleState == WebLifecycleState.Background) next.onPause()
        next.loadUrl(GigagochiWebRootUrl)
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun publishQaBootstrapDelivered(
        pending: PendingWebBridgeRequest<JavaScriptReplyProxy>,
        request: BridgeRequestEnvelope,
        bridgeSessionId: String,
    ) {
        if (pending.hostGeneration != documentGeneration) return
        runCatching {
            qaObserver?.onBootstrapDelivered(
                WebBootstrapDeliveredEvent(
                    documentGeneration = pending.hostGeneration,
                    documentFence = dispatcher.currentDocumentFence(),
                    documentId = request.documentId,
                    bridgeSessionId = bridgeSessionId,
                ),
            )
        }
    }

    private fun disposeWebView(view: WebView) {
        if (webView === view) webView = null
        runCatching { typographyScriptHandler?.remove() }
        typographyScriptHandler = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    private fun disposeUnattachedWebView(view: WebView) {
        runCatching { typographyScriptHandler?.remove() }
        typographyScriptHandler = null
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    private fun disposeRendererGoneWebView(view: WebView) {
        if (webView === view) webView = null
        runCatching { typographyScriptHandler?.remove() }
        typographyScriptHandler = null
        (view.parent as? ViewGroup)?.removeView(view)
        // Android's renderer-gone contract requires removing and destroying this instance. Avoid
        // stopLoading or client mutations here: they would send work to a renderer that is gone.
        runCatching { view.destroy() }
    }

    private fun invalidateWebDocument() {
        cancelFontReadinessProbe()
        documentGeneration += 1
        activeReply = null
        lastSafeAreaSnapshot = null
        lifecycleEventController.resetForDocument()
        mediaRegistry?.invalidateDocument()
        dispatcher.invalidateDocument()
    }

    private fun publishLifecycle(state: WebLifecycleState) {
        if (activeReply == null || !lifecycleEventController.isObserverReady()) return
        lifecycleEventController.transition(state)?.let { event ->
            enqueueNativeEvent(event, lifecycleState = state)
        }
    }

    private fun enqueueNativeEvent(
        event: WebAppRuntimeEvent,
        dispatcherFence: BridgeDocumentFence = dispatcher.currentDocumentFence(),
        lifecycleState: WebLifecycleState? = null,
    ) {
        nativeEvents.trySend(
            PendingNativeEvent(
                event = event,
                hostGeneration = documentGeneration,
                dispatcherFence = dispatcherFence,
                systemBackEventId = null,
                lifecycleState = lifecycleState,
            ),
        )
    }

    private fun enqueueSystemBack(pending: PendingWebSystemBack): Boolean =
        nativeEvents.trySend(
            PendingNativeEvent(
                event = WebAppRuntimeEvent(
                    type = "systemBack",
                    payload = BridgeCodec.json.encodeToJsonElement(
                        WebSystemBackPayload.serializer(),
                        WebSystemBackPayload(pending.navigationSequence),
                    ),
                ),
                hostGeneration = documentGeneration,
                dispatcherFence = pending.documentFence,
                systemBackEventId = pending.eventId,
                lifecycleState = null,
            ),
        ).isSuccess

    private suspend fun postNativeEvent(pending: PendingNativeEvent) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            abandonNativeEvent(pending)
            return
        }
        if (pending.hostGeneration != documentGeneration) {
            abandonNativeEvent(pending)
            return
        }
        if (!lifecycleEventController.isObserverReady()) {
            abandonNativeEvent(pending)
            return
        }
        val encoded = dispatcher.event(pending.event, pending.dispatcherFence)
        if (encoded == null) {
            abandonNativeEvent(pending)
            return
        }
        if (pending.hostGeneration != documentGeneration) {
            abandonNativeEvent(pending)
            return
        }
        val reply = activeReply
        if (reply == null) {
            abandonNativeEvent(pending)
            return
        }
        if (runCatching { reply.postMessage(encoded) }.isFailure) {
            if (activeReply === reply) {
                activeReply = null
                lifecycleEventController.resetForDocument()
            }
            abandonNativeEvent(pending)
            return
        }
        pending.systemBackEventId?.let { eventId ->
            scope.launch {
                delay(SystemBackAckTimeoutMillis)
                dispatcher.releaseSystemBack(eventId)
            }
        }
    }

    private fun releasePendingSystemBack(pending: PendingNativeEvent) {
        pending.systemBackEventId?.let(dispatcher::releaseSystemBack)
    }

    private fun abandonNativeEvent(pending: PendingNativeEvent) {
        // An event from a disposed renderer must never close the observer gate that a newer
        // document has already established.
        if (pending.lifecycleState != null) {
            lifecycleEventController.abandonTransition(
                eventGeneration = pending.hostGeneration,
                currentGeneration = documentGeneration,
            )
        }
        releasePendingSystemBack(pending)
    }

    private fun showCompatibilityError() {
        webView?.let(::disposeWebView)
        rootView.removeAllViews()
        invalidateWebDocument()
        rootView.addView(
            TextView(activity).apply {
                text = "Не удалось запустить Android System WebView. Обновите компонент и повторите."
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 32)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            if (preparingImeAnimation == null && activeImeAnimation == null) {
                publishSafeArea(view, insets)
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            rootView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        preparingImeAnimation = animation
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        preparingImeAnimation = null
                        activeImeAnimation = animation
                        activeImeBounds = WebImeAnimationBounds(
                            lowerBottom = bounds.lowerBound.bottom,
                            upperBottom = bounds.upperBound.bottom,
                        )
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    publishSafeArea(rootView, insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    val wasPreparing = preparingImeAnimation === animation
                    val wasActive = activeImeAnimation === animation
                    if (wasPreparing) preparingImeAnimation = null
                    if (wasActive) {
                        activeImeAnimation = null
                        activeImeBounds = null
                    }
                    if (wasPreparing || wasActive) {
                        ViewCompat.getRootWindowInsets(rootView)?.let {
                            publishSafeArea(rootView, it)
                        }
                    }
                }
            },
        )
    }

    private fun publishSafeArea(view: View, insets: WindowInsetsCompat) {
        val density = view.resources.displayMetrics.density.toDouble()
        val safeDrawingTypes =
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val system = insets.getInsets(safeDrawingTypes)
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val height = view.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val snapshot = WebSafeAreaCalculator.calculate(
            pixels = WebInsetPixels(
                viewportHeight = height,
                systemTop = system.top,
                systemRight = system.right,
                systemBottom = system.bottom,
                systemLeft = system.left,
                imeBottom = ime.bottom,
                imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()),
            ),
            density = density,
            animationBounds = activeImeBounds,
        )
        if (snapshot == lastSafeAreaSnapshot) return
        lastSafeAreaSnapshot = snapshot
        runtime.updateSafeArea(snapshot)
        enqueueNativeEvent(
            WebAppRuntimeEvent(
                type = "insetsChanged",
                payload = BridgeCodec.json.encodeToJsonElement(
                    WebSafeAreaSnapshot.serializer(),
                    snapshot,
                ),
            ),
        )
    }

    private inner class LocalContentClient(
        private val messageGeneration: WebMessageDocumentGeneration,
    ) : WebViewClient() {
        private var hasStartedDocument = false

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse = interceptLocalRequest(
            requestUrl = request.url.toString(),
            method = request.method,
            requestHeaders = request.requestHeaders,
        )

        @Deprecated("Legacy WebView callback")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse =
            interceptLocalRequest(
                requestUrl = url,
                method = "GET",
                requestHeaders = emptyMap(),
            )

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !isTrustedTopLevelUrl(request.url)

        @Deprecated("Legacy WebView callback")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            !isTrustedTopLevelUrl(Uri.parse(url))

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (!hasStartedDocument) {
                hasStartedDocument = true
                return
            }
            invalidateWebDocument()
            messageGeneration.advanceTo(documentGeneration)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val parsedUrl = url?.let(Uri::parse) ?: return
            if (
                view !== webView ||
                messageGeneration.current() != documentGeneration ||
                !isTrustedTopLevelUrl(parsedUrl)
            ) {
                return
            }
            startFontReadinessProbe(
                view = view,
                generation = messageGeneration.current(),
            )
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            return recoverFromRendererExit(view, SystemClock.elapsedRealtime())
        }
    }

    private fun exerciseRendererRecoverySeam(): Boolean {
        val active = webView ?: return false
        return recoverFromRendererExit(active, SystemClock.elapsedRealtime())
    }

    private fun exerciseRendererTerminationForQa(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val active = webView ?: return false
        return runCatching {
            active.webViewRenderProcess?.terminate() == true
        }.getOrDefault(false)
    }

    private fun recoverFromRendererExit(
        exitedView: WebView,
        nowMillis: Long,
    ): Boolean {
        // A late callback from a renderer that was already replaced must never dispose the current
        // document or consume another recovery-budget slot.
        if (exitedView !== webView) return true
        val previousGeneration = documentGeneration
        val previousFence = dispatcher.currentDocumentFence()
        rendererExitTimes.addLast(nowMillis)
        while (rendererExitTimes.firstOrNull()?.let { nowMillis - it > 60_000L } == true) {
            rendererExitTimes.removeFirst()
        }
        disposeRendererGoneWebView(exitedView)
        if (rendererExitTimes.size <= 3) {
            replaceWebView()
            runCatching {
                qaObserver?.onRendererReplaced(
                    WebRendererReplacementEvent(
                        previousDocumentGeneration = previousGeneration,
                        replacementDocumentGeneration = documentGeneration,
                        previousDocumentFence = previousFence,
                        replacementDocumentFence = dispatcher.currentDocumentFence(),
                    ),
                )
            }
        } else {
            showCompatibilityError()
        }
        return true
    }

    private fun startFontReadinessProbe(
        view: WebView,
        generation: Long,
    ) {
        val observer = qaObserver ?: return
        if (fontReadinessGeneration == generation) return
        cancelFontReadinessProbe()
        fontReadinessGeneration = generation
        val coordinator = WebDocumentFontReadinessCoordinator(
            timeoutMillis = observer.fontReadinessTimeoutMillis,
            monotonicClockMillis = SystemClock::elapsedRealtime,
            schedule = { delayMillis, task ->
                rootView.postDelayed(task, delayMillis)
            },
            readState = { callback ->
                view.evaluateJavascript(WebDocumentFontReadinessProbeScript) { result ->
                    callback(parseWebDocumentFontProbeState(result))
                }
            },
            isDocumentCurrent = {
                view === webView && generation == documentGeneration
            },
            publish = { readiness, failureCode ->
                runCatching {
                    observer.onFontReadiness(
                        WebDocumentFontReadinessEvent(
                            documentGeneration = generation,
                            readiness = readiness,
                            failureCode = failureCode,
                        ),
                    )
                }
            },
        )
        fontReadinessCoordinator = coordinator
        coordinator.start()
    }

    private fun cancelFontReadinessProbe() {
        fontReadinessCoordinator?.cancel()
        fontReadinessCoordinator = null
        fontReadinessGeneration = null
    }

    private fun interceptLocalRequest(
        requestUrl: String,
        method: String,
        requestHeaders: Map<String, String>,
    ): WebResourceResponse {
        if (!isTrustedAppAssetsRequest(requestUrl)) {
            return emptyWebResourceError(403, "Forbidden")
        }
        return mediaProxy?.intercept(
            requestUrl = requestUrl,
            method = method,
            requestHeaders = requestHeaders,
        ) ?: assetLoader.shouldInterceptRequest(Uri.parse(requestUrl))
            ?: emptyWebResourceError(404, "Not Found")
    }

    private class LockedDownChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            filePathCallback.onReceiveValue(null)
            return false
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message,
        ): Boolean = false
    }

    private class GigagochiLockedDownWebView(context: Context) : WebView(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
            val connection = super.onCreateInputConnection(outAttrs)
            outAttrs.imeOptions = webViewImeOptionsWithoutExtractUi(outAttrs.imeOptions)
            return connection
        }
    }

    private class NativeResourcePathHandler(context: Context) : WebViewAssetLoader.PathHandler {
        private val resources = context.resources

        override fun handle(path: String): WebResourceResponse? {
            val resource = ResourceAllowlist[path] ?: return null
            return runCatching {
                WebResourceResponse(
                    resource.mimeType,
                    resource.encoding,
                    resources.openRawResource(resource.id),
                )
            }.getOrNull()
        }
    }

    private data class LocalResource(
        val id: Int,
        val mimeType: String,
        val encoding: String? = null,
    )

    private data class PendingNativeEvent(
        val event: WebAppRuntimeEvent,
        val hostGeneration: Long,
        val dispatcherFence: BridgeDocumentFence,
        val systemBackEventId: String?,
        val lifecycleState: WebLifecycleState?,
    )

    private class WebMessageDocumentGeneration(initial: Long) {
        @Volatile
        private var value = initial

        fun current(): Long = value

        fun advanceTo(next: Long) {
            value = next
        }
    }

    private fun isTrustedTopLevelUrl(uri: Uri): Boolean =
        isTrustedAppAssetsRequest(uri.toString()) &&
            uri.path == "/assets/web/index.html"

    companion object {
        private const val SystemBackAckTimeoutMillis = 1_500L
        private const val WebDocumentFontReadinessProbeScript = """
            (() => {
              try {
                const key = "__gigagochiQaDocumentFontsV1";
                let marker = window[key];
                if (!marker) {
                  const fonts = document.fonts;
                  if (!fonts || !fonts.ready) return "failed";
                  marker = { state: "pending" };
                  window[key] = marker;
                  Promise.resolve(fonts.ready).then(
                    () => { marker.state = "ready"; },
                    () => { marker.state = "failed"; }
                  );
                }
                return marker.state;
              } catch (_) {
                return "failed";
              }
            })()
        """

        private val ResourceAllowlist = mapOf(
            "clouds_empty.png" to LocalResource(R.drawable.clouds_empty, "image/png"),
            "clouds_formed.png" to LocalResource(R.drawable.clouds_formed, "image/png"),
            "main_pet.png" to LocalResource(R.drawable.main_pet, "image/png"),
            "main_screen_bg.png" to LocalResource(R.drawable.main_screen_bg, "image/png"),
            "onboarding_bat_situation.png" to LocalResource(
                R.drawable.onboarding_bat_situation,
                "image/png",
            ),
            "onboarding_bat_success.png" to LocalResource(
                R.drawable.onboarding_bat_success,
                "image/png",
            ),
            "pet.png" to LocalResource(R.drawable.pet, "image/png"),
            "speech_bubble_new.png" to LocalResource(R.drawable.speech_bubble_new, "image/png"),
            "test_pet_poster.png" to LocalResource(R.drawable.test_pet_poster, "image/png"),
            "thinking_frame_1.png" to LocalResource(R.drawable.thinking_frame_1, "image/png"),
            "thinking_frame_2.png" to LocalResource(R.drawable.thinking_frame_2, "image/png"),
            "thinking_frame_3.png" to LocalResource(R.drawable.thinking_frame_3, "image/png"),
            "travel_entry_bg.png" to LocalResource(R.drawable.travel_entry_bg, "image/png"),
            "video_filter_normal.webp" to LocalResource(
                R.drawable.video_filter_normal,
                "image/webp",
            ),
            "action_chat_icon_new.svg" to LocalResource(
                R.raw.action_chat_icon_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "action_feed_icon_new.svg" to LocalResource(
                R.raw.action_feed_icon_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "action_outfit_icon.svg" to LocalResource(
                R.raw.action_outfit_icon,
                "image/svg+xml",
                "UTF-8",
            ),
            "action_travel_icon_new.svg" to LocalResource(
                R.raw.action_travel_icon_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "conversation_send_icon.svg" to LocalResource(
                R.raw.conversation_send_icon,
                "image/svg+xml",
                "UTF-8",
            ),
            "feed_food_berry_bowl.svg" to LocalResource(
                R.raw.feed_food_berry_bowl,
                "image/svg+xml",
                "UTF-8",
            ),
            "feed_food_leaf_crunch.svg" to LocalResource(
                R.raw.feed_food_leaf_crunch,
                "image/svg+xml",
                "UTF-8",
            ),
            "speech_bubble_new.svg" to LocalResource(
                R.raw.speech_bubble_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "status_energy_new.svg" to LocalResource(
                R.raw.status_energy_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "status_hunger_new.svg" to LocalResource(
                R.raw.status_hunger_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "status_mood_new.svg" to LocalResource(
                R.raw.status_mood_new,
                "image/svg+xml",
                "UTF-8",
            ),
            "thinking_frame_1.svg" to LocalResource(
                R.raw.thinking_frame_1,
                "image/svg+xml",
                "UTF-8",
            ),
            "thinking_frame_2.svg" to LocalResource(
                R.raw.thinking_frame_2,
                "image/svg+xml",
                "UTF-8",
            ),
            "thinking_frame_3.svg" to LocalResource(
                R.raw.thinking_frame_3,
                "image/svg+xml",
                "UTF-8",
            ),
            "xp_coin.svg" to LocalResource(R.raw.xp_coin, "image/svg+xml", "UTF-8"),
            "open_runde_medium.ttf" to LocalResource(R.font.open_runde_medium, "font/ttf"),
            "open_runde_semibold.ttf" to LocalResource(R.font.open_runde_semibold, "font/ttf"),
            "sb_sans_display_bold.otf" to LocalResource(
                R.font.sb_sans_display_bold,
                "font/otf",
            ),
            "button_press.wav" to LocalResource(R.raw.button_press, "audio/wav"),
            "creation_button_plop.wav" to LocalResource(
                R.raw.creation_button_plop,
                "audio/wav",
            ),
            "feed_bite_1.wav" to LocalResource(R.raw.feed_bite_1, "audio/wav"),
            "feed_bite_2.wav" to LocalResource(R.raw.feed_bite_2, "audio/wav"),
            "feed_bite_3.wav" to LocalResource(R.raw.feed_bite_3, "audio/wav"),
            "pet_tap.wav" to LocalResource(R.raw.pet_tap, "audio/wav"),
            "speech_1.wav" to LocalResource(R.raw.speech_1, "audio/wav"),
            "speech_2.wav" to LocalResource(R.raw.speech_2, "audio/wav"),
            "speech_3.wav" to LocalResource(R.raw.speech_3, "audio/wav"),
            "speech_4.wav" to LocalResource(R.raw.speech_4, "audio/wav"),
        )

        fun configureDebugging() {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
    }
}

internal fun webViewImeOptionsWithoutExtractUi(current: Int): Int =
    current or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

internal fun isTrustedAppAssetsRequest(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("appassets.androidplatform.net", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.port == -1
}

internal fun supportsRequiredWebViewFeatures(
    isFeatureSupported: (String) -> Boolean,
): Boolean = runCatching {
    isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
}.getOrDefault(false)

internal fun <T> configureRequiredWebResourceOrDispose(
    configure: () -> T,
    dispose: () -> Unit,
): T? = try {
    configure()
} catch (_: RuntimeException) {
    runCatching(dispose)
    null
}

@Suppress("DEPRECATION")
private fun enableSafeBrowsingWhenSupported(settings: WebSettings) {
    // WebView 122+ starts Safe Browsing automatically. Older providers may expose this per-WebView
    // setting; feature detection keeps API 23 and providers without the feature fully operational.
    enableSafeBrowsingWhenSupported(
        isFeatureSupported = {
            WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)
        },
        enable = { WebSettingsCompat.setSafeBrowsingEnabled(settings, true) },
    )
}

internal fun enableSafeBrowsingWhenSupported(
    isFeatureSupported: () -> Boolean,
    enable: () -> Unit,
): Boolean {
    if (!runCatching(isFeatureSupported).getOrDefault(false)) return false
    return runCatching {
        enable()
        true
    }.getOrDefault(false)
}

private fun emptyWebResourceError(
    statusCode: Int,
    reasonPhrase: String,
): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "UTF-8",
    statusCode,
    reasonPhrase,
    mapOf(
        "Cache-Control" to "no-store",
        "Content-Length" to "0",
        "X-Content-Type-Options" to "nosniff",
    ),
    ByteArrayInputStream(ByteArray(0)),
)
