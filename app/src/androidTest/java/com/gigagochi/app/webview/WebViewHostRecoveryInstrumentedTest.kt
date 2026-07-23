package com.gigagochi.app.webview

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebSettings
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.gigagochi.app.core.webview.WebDocumentFontReadiness
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class WebViewHostRecoveryInstrumentedTest {
    @Test
    fun realHost_waitsForDocumentFonts_thenProviderTerminationReplacesWebView_andDeliversFreshBootstrap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, WebViewPreviewActivity::class.java)
            .putExtra(DebugWebAppFixtureRouting.IntentExtra, "dashboard")
            .putExtra(WebViewHostQaFixture.IntentExtra, true)
        val firstView = AtomicReference<WebView>()

        ActivityScenario.launch<WebViewPreviewActivity>(intent).use { scenario ->
            val firstEvidence = awaitEvidence {
                val firstBootstrap = it.bootstrapEvents.firstOrNull() ?: return@awaitEvidence null
                val fontsReady = it.fontEvents.any { event ->
                    event.documentGeneration == firstBootstrap.documentGeneration &&
                        event.readiness == WebDocumentFontReadiness.Ready
                }
                firstBootstrap.takeIf { fontsReady }
            }
            scenario.onActivity { activity ->
                firstView.set(requireNotNull(findWebView(activity.window.decorView)))
                assertTrue(activity.exerciseRendererTerminationForQa())
            }

            val recovered = awaitEvidence { snapshot ->
                val replacement = snapshot.replacementEvents.singleOrNull()
                    ?: return@awaitEvidence null
                val secondBootstrap = snapshot.bootstrapEvents.firstOrNull {
                    it.documentGeneration == replacement.replacementDocumentGeneration
                } ?: return@awaitEvidence null
                val replacementFontsReady = snapshot.fontEvents.any {
                    it.documentGeneration == replacement.replacementDocumentGeneration &&
                        it.readiness == WebDocumentFontReadiness.Ready
                }
                (replacement to secondBootstrap).takeIf { replacementFontsReady }
            }
            val replacement = recovered.first
            val secondEvidence = recovered.second

            assertEquals(
                firstEvidence.documentGeneration,
                replacement.previousDocumentGeneration,
            )
            assertTrue(
                replacement.replacementDocumentGeneration >
                    replacement.previousDocumentGeneration,
            )
            assertEquals(firstEvidence.documentFence, replacement.previousDocumentFence)
            assertTrue(
                replacement.replacementDocumentFence.epoch >
                    replacement.previousDocumentFence.epoch,
            )
            assertTrue(
                secondEvidence.documentFence.epoch >
                    replacement.replacementDocumentFence.epoch,
            )
            assertNotEquals(firstEvidence.documentId, secondEvidence.documentId)
            assertNotEquals(firstEvidence.bridgeSessionId, secondEvidence.bridgeSessionId)

            scenario.onActivity { activity ->
                val replacementView = requireNotNull(findWebView(activity.window.decorView))
                assertNotSame(firstView.get(), replacementView)
                assertTrue(replacementView.settings.javaScriptEnabled)
                assertFalse(replacementView.settings.allowFileAccess)
                assertFalse(replacementView.settings.allowContentAccess)
                assertEquals(
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW,
                    replacementView.settings.mixedContentMode,
                )
            }
        }
    }

    private fun <T> awaitEvidence(
        timeoutMillis: Long = 20_000L,
        select: (WebViewHostQaSnapshot) -> T?,
    ): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            select(WebViewHostQaFixture.snapshot())?.let { return it }
            SystemClock.sleep(32L)
        }
        error("Timed out waiting for WebView host QA evidence: ${WebViewHostQaFixture.snapshot()}")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        repeat(view.childCount) { index ->
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
