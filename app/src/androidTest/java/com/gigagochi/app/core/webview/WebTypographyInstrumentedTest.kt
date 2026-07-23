package com.gigagochi.app.core.webview

import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebTypographyInstrumentedTest {
    @Test
    fun platformDocumentStartValuesMatchAndroidNonlinearSpCurve() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selectedSp = listOf(-0.45, -0.15, 16.0, 20.0, 23.0, 26.0, 40.0)
        val knownApi37Sp23Dp = mapOf(1f to 23.0, 1.3f to 25.7, 2f to 35.5)

        listOf(1f, 1.3f, 2f).forEach { fontScale ->
            val configuration = Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
            }
            val scaledResources = context.createConfigurationContext(configuration).resources
            val density = scaledResources.displayMetrics.density
            val resolved = webTypographyResolvedTokens(scaledResources)
            val script = webTypographyDocumentStartScript(resolved)

            selectedSp.forEach { sp ->
                val property = WebTypographyTokens.single { it.sp == sp }.cssProperty
                val expectedDp = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    sp.toFloat(),
                    scaledResources.displayMetrics,
                ).toDouble() / density
                val generatedDp = resolved.single { it.cssProperty == property }.cssPixels
                assertEquals("$fontScale x $sp sp", expectedDp, generatedDp, 0.000_01)

                val assignment = Regex(
                    "root\\.style\\.setProperty\\('" + Regex.escape(property) +
                        "', '([^']+)px'\\)",
                ).find(script)
                assertNotNull("missing injected $property at $fontScale", assignment)
                assertEquals(expectedDp, assignment!!.groupValues[1].toDouble(), 0.000_01)
                if (sp < 0.0) {
                    assertTrue("$sp sp letter spacing must preserve its sign", generatedDp < 0.0)
                } else if (fontScale > 1f && Build.VERSION.SDK_INT >= 34) {
                    assertTrue(
                        "$fontScale x $sp sp must use Android's sublinear curve",
                        generatedDp < sp * fontScale,
                    )
                } else if (fontScale > 1f) {
                    assertEquals(sp * fontScale, generatedDp, 0.000_01)
                }
            }

            val sp23 = resolved.single { it.cssProperty == "--platform-sp-23" }.cssPixels
            if (Build.VERSION.SDK_INT == 37) {
                assertEquals(knownApi37Sp23Dp.getValue(fontScale), sp23, 0.000_1)
            }
            if (fontScale > 1f && Build.VERSION.SDK_INT >= 34) {
                assertNotEquals(23.0, sp23, 0.000_01)
            }
        }
    }
}
