package com.gigagochi.app.core.webview

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewImeOptionsTest {
    @Test
    fun `web view input preserves existing options and disables extract fullscreen UI`() {
        val existing = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

        val resolved = webViewImeOptionsWithoutExtractUi(existing)

        assertEquals(existing, resolved and existing)
        assertTrue(resolved and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
        assertTrue(resolved and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
    }
}
