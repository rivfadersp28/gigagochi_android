package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewHostSecurityTest {
    @Test
    fun `host requires both message listener and document start support`() {
        val both = setOf(
            androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER,
            androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT,
        )
        assertTrue(supportsRequiredWebViewFeatures(both::contains))
        assertFalse(supportsRequiredWebViewFeatures { it == androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER })
        assertFalse(supportsRequiredWebViewFeatures { it == androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT })
        assertFalse(supportsRequiredWebViewFeatures { error("provider failure") })
    }

    @Test
    fun `required registration failure disposes partial resource exactly once`() {
        var disposeCalls = 0
        val failed = configureRequiredWebResourceOrDispose<String>(
            configure = { error("registration failed") },
            dispose = { disposeCalls += 1 },
        )
        assertEquals(null, failed)
        assertEquals(1, disposeCalls)

        val configured = configureRequiredWebResourceOrDispose(
            configure = { "ready" },
            dispose = { disposeCalls += 1 },
        )
        assertEquals("ready", configured)
        assertEquals(1, disposeCalls)
    }

    @Test
    fun `accepts only canonical appassets https origin`() {
        assertTrue(isTrustedAppAssetsRequest("https://appassets.androidplatform.net/assets/web/index.html"))
        assertTrue(isTrustedAppAssetsRequest("https://APPASSETS.ANDROIDPLATFORM.NET/res/pet.png?v=1"))
        assertTrue(isTrustedAppAssetsRequest("https://appassets.androidplatform.net/media/v1/doc/resource"))

        listOf(
            "http://appassets.androidplatform.net/assets/web/index.html",
            "https://appassets.androidplatform.net:443/assets/web/index.html",
            "https://user@appassets.androidplatform.net/assets/web/index.html",
            "https://appassets.androidplatform.net.evil.example/assets/web/index.html",
            "https://evil.example/assets/web/index.html",
            "file:///android_asset/web/index.html",
            "content://appassets.androidplatform.net/assets/web/index.html",
            "javascript:alert(1)",
            "not a url",
            "",
        ).forEach { url ->
            assertFalse(url, isTrustedAppAssetsRequest(url))
        }
    }

    @Test
    fun `safe browsing setting is applied only when provider supports it`() {
        var enableCalls = 0

        assertFalse(
            enableSafeBrowsingWhenSupported(
                isFeatureSupported = { false },
                enable = { enableCalls += 1 },
            ),
        )
        assertTrue(
            enableSafeBrowsingWhenSupported(
                isFeatureSupported = { true },
                enable = { enableCalls += 1 },
            ),
        )

        assertEquals(1, enableCalls)
    }

    @Test
    fun `safe browsing provider failures do not block local-only startup`() {
        assertFalse(
            enableSafeBrowsingWhenSupported(
                isFeatureSupported = { error("provider unavailable") },
                enable = { error("must not run") },
            ),
        )
        assertFalse(
            enableSafeBrowsingWhenSupported(
                isFeatureSupported = { true },
                enable = { error("setting unavailable") },
            ),
        )
    }
}
