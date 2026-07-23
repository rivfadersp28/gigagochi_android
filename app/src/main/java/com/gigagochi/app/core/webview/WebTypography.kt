package com.gigagochi.app.core.webview

import android.content.res.Resources
import android.util.TypedValue
import java.math.BigDecimal
import java.math.RoundingMode

internal const val NeutralWebViewTextZoomPercent = 100

internal data class WebTypographyToken(
    val cssProperty: String,
    val sp: Double,
)

internal data class ResolvedWebTypographyToken(
    val cssProperty: String,
    val cssPixels: Double,
)

/** Closed set of every SP-valued font, line-height, and letter-spacing used by the Web UI. */
internal val WebTypographyTokens = listOf(
    WebTypographyToken("--platform-sp-neg-0-45", -0.45),
    WebTypographyToken("--platform-sp-neg-0-15", -0.15),
    WebTypographyToken("--platform-sp-11", 11.0),
    WebTypographyToken("--platform-sp-12", 12.0),
    WebTypographyToken("--platform-sp-13", 13.0),
    WebTypographyToken("--platform-sp-14", 14.0),
    WebTypographyToken("--platform-sp-15", 15.0),
    WebTypographyToken("--platform-sp-16", 16.0),
    WebTypographyToken("--platform-sp-16-25", 16.25),
    WebTypographyToken("--platform-sp-17", 17.0),
    WebTypographyToken("--platform-sp-18", 18.0),
    WebTypographyToken("--platform-sp-18-2", 18.2),
    WebTypographyToken("--platform-sp-20", 20.0),
    WebTypographyToken("--platform-sp-22", 22.0),
    WebTypographyToken("--platform-sp-22-6", 22.6),
    WebTypographyToken("--platform-sp-23", 23.0),
    WebTypographyToken("--platform-sp-23-445", 23.445),
    WebTypographyToken("--platform-sp-23-9", 23.9),
    WebTypographyToken("--platform-sp-24", 24.0),
    WebTypographyToken("--platform-sp-26", 26.0),
    WebTypographyToken("--platform-sp-28", 28.0),
    WebTypographyToken("--platform-sp-30", 30.0),
    WebTypographyToken("--platform-sp-31-5", 31.5),
    WebTypographyToken("--platform-sp-32", 32.0),
    WebTypographyToken("--platform-sp-38", 38.0),
    WebTypographyToken("--platform-sp-39-45", 39.45),
    WebTypographyToken("--platform-sp-40", 40.0),
)

internal fun webTypographyCssDeclarations(
    spToDp: (Double) -> Double,
): String = webTypographyResolvedTokens(spToDp)
    .joinToString(separator = ";", postfix = ";") { token ->
        "${token.cssProperty}:${token.cssPixels.toStableCssNumber()}px"
    }

internal fun webTypographyResolvedTokens(
    spToDp: (Double) -> Double,
): List<ResolvedWebTypographyToken> = WebTypographyTokens.map { token ->
    val value = spToDp(token.sp)
    require(value.isFinite()) { "Non-finite typography value for ${token.cssProperty}" }
    ResolvedWebTypographyToken(token.cssProperty, value)
}

internal fun webTypographyDocumentStartScript(
    tokens: List<ResolvedWebTypographyToken>,
): String {
    val expectedProperties = WebTypographyTokens.map(WebTypographyToken::cssProperty)
    require(tokens.map(ResolvedWebTypographyToken::cssProperty) == expectedProperties) {
        "Typography bootstrap must contain the complete ordered platform token set"
    }
    require(tokens.all { it.cssPixels.isFinite() }) { "Typography values must be finite" }
    val assignments = tokens.joinToString(separator = "\n") { token ->
        "root.style.setProperty('${token.cssProperty}', " +
            "'${token.cssPixels.toStableCssNumber()}px');"
    }
    return """
        (() => {
          const apply = () => {
            const root = document.documentElement;
            if (!root) return false;
            $assignments
            root.dataset.platformTypography = 'ready';
            return true;
          };
          if (!apply()) {
            const observer = new MutationObserver(() => {
              if (apply()) observer.disconnect();
            });
            observer.observe(document, { childList: true });
          }
        })();
    """.trimIndent()
}

internal fun webTypographyResolvedTokens(resources: Resources): List<ResolvedWebTypographyToken> {
    val metrics = resources.displayMetrics
    val density = validatedWebTypographyDensity(metrics.density)
    val fontScale = resources.configuration.fontScale
    return webTypographyResolvedTokens { sp ->
        if (fontScale == 1f) {
            sp
        } else {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sp.toFloat(),
                metrics,
            ).toDouble() / density
        }
    }
}

internal fun validatedWebTypographyDensity(density: Float): Float {
    require(density.isFinite() && density > 0f) {
        "Typography display density must be finite and greater than zero"
    }
    return density
}

internal fun webTypographyDocumentStartScript(resources: Resources): String =
    webTypographyDocumentStartScript(webTypographyResolvedTokens(resources))

private fun Double.toStableCssNumber(): String {
    val normalized = if (this == -0.0) 0.0 else this
    return BigDecimal.valueOf(normalized)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}
