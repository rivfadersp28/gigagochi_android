package com.gigagochi.app.core.webview

import android.os.Looper

/**
 * Optional, process-local observability for debug screenshot and host-recovery tests.
 *
 * Production activities never install these hooks. They are deliberately not part of the bridge
 * or product snapshot, and they cannot navigate a WebView or evaluate caller-provided JavaScript.
 */
internal interface WebViewHostQaObserver {
    val fontReadinessTimeoutMillis: Long
        get() = DefaultWebDocumentFontReadinessTimeoutMillis

    fun onFontReadiness(event: WebDocumentFontReadinessEvent) = Unit

    fun onBootstrapDelivered(event: WebBootstrapDeliveredEvent) = Unit

    fun onRendererReplaced(event: WebRendererReplacementEvent) = Unit
}

internal enum class WebDocumentFontReadiness {
    Ready,
    TimedOut,
    Failed,
    Superseded,
}

internal data class WebDocumentFontReadinessEvent(
    val documentGeneration: Long,
    val readiness: WebDocumentFontReadiness,
    val failureCode: String? = null,
)

internal data class WebBootstrapDeliveredEvent(
    val documentGeneration: Long,
    val documentFence: BridgeDocumentFence,
    val documentId: String,
    val bridgeSessionId: String,
)

internal data class WebRendererReplacementEvent(
    val previousDocumentGeneration: Long,
    val replacementDocumentGeneration: Long,
    val previousDocumentFence: BridgeDocumentFence,
    val replacementDocumentFence: BridgeDocumentFence,
)

/**
 * Non-production controls for both the exact host recovery branch and a real provider renderer
 * termination. The controller has no effect unless a host explicitly attaches it, which only the
 * debug preview activity does.
 */
internal class WebViewHostQaController {
    private var rendererRecovery: (() -> Boolean)? = null
    private var rendererTermination: (() -> Boolean)? = null

    fun requestRendererRecovery(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return rendererRecovery?.invoke() == true
    }

    fun requestRendererTermination(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return rendererTermination?.invoke() == true
    }

    fun attachRendererControls(
        recovery: () -> Boolean,
        termination: () -> Boolean,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        rendererRecovery = recovery
        rendererTermination = termination
    }

    fun detachRendererControls() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            rendererRecovery = null
            rendererTermination = null
        }
    }
}

internal enum class WebDocumentFontProbeState {
    Pending,
    Ready,
    Failed,
}

/**
 * Native watchdog around an asynchronous `document.fonts.ready` marker.
 *
 * The deadline is scheduled independently from JavaScript callbacks, so a wedged renderer or a
 * callback that never arrives still terminates as [WebDocumentFontReadiness.TimedOut].
 */
internal class WebDocumentFontReadinessCoordinator(
    timeoutMillis: Long,
    private val pollIntervalMillis: Long = DefaultWebDocumentFontPollIntervalMillis,
    private val monotonicClockMillis: () -> Long,
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Unit,
    private val readState: (callback: (WebDocumentFontProbeState) -> Unit) -> Unit,
    private val isDocumentCurrent: () -> Boolean,
    private val publish: (WebDocumentFontReadiness, String?) -> Unit,
) {
    private val boundedTimeoutMillis = timeoutMillis.coerceIn(
        MinimumWebDocumentFontReadinessTimeoutMillis,
        MaximumWebDocumentFontReadinessTimeoutMillis,
    )
    private var started = false
    private var finished = false
    private var deadlineMillis = 0L

    fun start() {
        check(!started)
        started = true
        deadlineMillis = monotonicClockMillis() + boundedTimeoutMillis
        schedule(boundedTimeoutMillis) {
            if (!finished) finish(WebDocumentFontReadiness.TimedOut, "timeout")
        }
        poll()
    }

    fun cancel() {
        if (started && !finished) {
            finish(WebDocumentFontReadiness.Superseded, "document-superseded")
        }
    }

    private fun poll() {
        if (finished) return
        if (!isDocumentCurrent()) {
            finish(WebDocumentFontReadiness.Superseded, "document-superseded")
            return
        }
        if (monotonicClockMillis() >= deadlineMillis) {
            finish(WebDocumentFontReadiness.TimedOut, "timeout")
            return
        }
        try {
            readState { state ->
                if (finished) return@readState
                if (!isDocumentCurrent()) {
                    finish(WebDocumentFontReadiness.Superseded, "document-superseded")
                    return@readState
                }
                when (state) {
                    WebDocumentFontProbeState.Ready ->
                        finish(WebDocumentFontReadiness.Ready, null)

                    WebDocumentFontProbeState.Failed ->
                        finish(WebDocumentFontReadiness.Failed, "document-fonts-ready-failed")

                    WebDocumentFontProbeState.Pending -> {
                        val remaining = deadlineMillis - monotonicClockMillis()
                        if (remaining <= 0L) {
                            finish(WebDocumentFontReadiness.TimedOut, "timeout")
                        } else {
                            schedule(minOf(pollIntervalMillis, remaining), ::poll)
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            finish(WebDocumentFontReadiness.Failed, "javascript-evaluation-failed")
        }
    }

    private fun finish(result: WebDocumentFontReadiness, failureCode: String?) {
        if (finished) return
        finished = true
        publish(result, failureCode)
    }
}

internal fun parseWebDocumentFontProbeState(rawResult: String?): WebDocumentFontProbeState =
    when (rawResult?.trim()?.removeSurrounding("\"")) {
        "pending" -> WebDocumentFontProbeState.Pending
        "ready" -> WebDocumentFontProbeState.Ready
        else -> WebDocumentFontProbeState.Failed
    }

internal const val DefaultWebDocumentFontReadinessTimeoutMillis = 8_000L
private const val MinimumWebDocumentFontReadinessTimeoutMillis = 250L
private const val MaximumWebDocumentFontReadinessTimeoutMillis = 30_000L
private const val DefaultWebDocumentFontPollIntervalMillis = 32L
