package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebTypographyTest {
    @Test
    fun `token set is complete unique and includes negative SP letter spacing`() {
        assertEquals(27, WebTypographyTokens.size)
        assertEquals(WebTypographyTokens.size, WebTypographyTokens.map { it.cssProperty }.toSet().size)
        assertEquals(WebTypographyTokens.size, WebTypographyTokens.map { it.sp }.toSet().size)
        assertTrue(WebTypographyTokens.any { it.sp == -0.45 })
        assertTrue(WebTypographyTokens.any { it.sp == -0.15 })
    }

    @Test
    fun `baseline declarations preserve every canonical CSS pixel value`() {
        val declarations = webTypographyCssDeclarations { it }

        WebTypographyTokens.forEach { token ->
            assertTrue("missing ${token.cssProperty}", declarations.contains(
                "${token.cssProperty}:${token.sp.toCssExpectation()}px;",
            ))
        }
    }

    @Test
    fun `Android 2x nonlinear samples remain per SP size instead of one global approximation`() {
        val androidTwoX = mapOf(
            20.0 to 34.0,
            23.0 to 35.5,
            24.0 to 36.0,
            26.0 to 36.666667,
            30.0 to 38.0,
            40.0 to 46.857143,
        )
        val declarations = webTypographyCssDeclarations { sp -> androidTwoX[sp] ?: sp }

        androidTwoX.forEach { (sp, dp) ->
            val token = WebTypographyTokens.single { it.sp == sp }
            assertTrue(declarations.contains("${token.cssProperty}:${dp.toCssExpectation()}px;"))
        }
        assertFalse(declarations.contains("--platform-global-font-scale"))
    }

    @Test
    fun `document start bootstrap applies inline values before stylesheet cascade`() {
        val tokens = webTypographyResolvedTokens { it }
        val script = webTypographyDocumentStartScript(tokens)

        assertTrue(script.contains("root.style.setProperty('--platform-sp-23', '23px')"))
        assertTrue(script.contains("root.dataset.platformTypography = 'ready'"))
        assertTrue(script.contains("new MutationObserver"))
        assertFalse(script.contains("createElement('style')"))
        assertFalse(script.contains("eval("))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `document start bootstrap rejects incomplete or injected token maps`() {
        webTypographyDocumentStartScript(
            listOf(ResolvedWebTypographyToken("--platform-sp-23');alert(1)//", 23.0)),
        )
    }

    @Test
    fun `display density validation fails closed for invalid values`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { density ->
            val error = runCatching { validatedWebTypographyDensity(density) }.exceptionOrNull()

            assertTrue("density=$density must be rejected", error is IllegalArgumentException)
        }
        assertEquals(2.75f, validatedWebTypographyDensity(2.75f))
    }

    private fun Double.toCssExpectation(): String =
        toBigDecimal().setScale(6, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}
