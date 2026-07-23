package com.gigagochi.app.webview

import android.util.Log
import com.gigagochi.app.core.webview.WebBootstrapDeliveredEvent
import com.gigagochi.app.core.webview.WebDocumentFontReadinessEvent
import com.gigagochi.app.core.webview.WebRendererReplacementEvent
import com.gigagochi.app.core.webview.WebViewHostQaObserver

/**
 * Debug-process evidence sink used by screenshot automation and androidTest.
 *
 * It is absent from release builds and does not participate in the bridge, persistence, or product
 * state. Instrumentation enables it explicitly through [IntentExtra].
 */
internal object WebViewHostQaFixture {
    const val IntentExtra = "gigagochi.web.qa-host-fixture"

    private val lock = Any()
    private val fontEvents = mutableListOf<WebDocumentFontReadinessEvent>()
    private val bootstrapEvents = mutableListOf<WebBootstrapDeliveredEvent>()
    private val replacementEvents = mutableListOf<WebRendererReplacementEvent>()

    val observer = object : WebViewHostQaObserver {
        override fun onFontReadiness(event: WebDocumentFontReadinessEvent) {
            synchronized(lock) {
                fontEvents += event
            }
            Log.i(LogTag, "fonts generation=${event.documentGeneration} result=${event.readiness}")
        }

        override fun onBootstrapDelivered(event: WebBootstrapDeliveredEvent) {
            synchronized(lock) {
                bootstrapEvents += event
            }
            Log.i(
                LogTag,
                "bootstrap generation=${event.documentGeneration} fence=${event.documentFence.epoch}",
            )
        }

        override fun onRendererReplaced(event: WebRendererReplacementEvent) {
            synchronized(lock) {
                replacementEvents += event
            }
            Log.i(
                LogTag,
                "renderer-replaced generation=" +
                    "${event.previousDocumentGeneration}->${event.replacementDocumentGeneration}",
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            fontEvents.clear()
            bootstrapEvents.clear()
            replacementEvents.clear()
        }
    }

    fun snapshot(): WebViewHostQaSnapshot = synchronized(lock) {
        WebViewHostQaSnapshot(
            fontEvents = fontEvents.toList(),
            bootstrapEvents = bootstrapEvents.toList(),
            replacementEvents = replacementEvents.toList(),
        )
    }

    private const val LogTag = "GigagochiWebViewQA"
}

internal data class WebViewHostQaSnapshot(
    val fontEvents: List<WebDocumentFontReadinessEvent>,
    val bootstrapEvents: List<WebBootstrapDeliveredEvent>,
    val replacementEvents: List<WebRendererReplacementEvent>,
)
