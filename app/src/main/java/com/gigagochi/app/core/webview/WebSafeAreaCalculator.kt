package com.gigagochi.app.core.webview

internal data class WebInsetPixels(
    val viewportHeight: Int,
    val systemTop: Int,
    val systemRight: Int,
    val systemBottom: Int,
    val systemLeft: Int,
    val imeBottom: Int,
    val imeVisible: Boolean,
)

internal data class WebImeAnimationBounds(
    val lowerBottom: Int,
    val upperBottom: Int,
)

internal object WebSafeAreaCalculator {
    fun calculate(
        pixels: WebInsetPixels,
        density: Double,
        animationBounds: WebImeAnimationBounds? = null,
    ): WebSafeAreaSnapshot {
        require(density > 0.0 && density.isFinite())
        val viewportHeight = pixels.viewportHeight.coerceAtLeast(0)
        val imeBottom = pixels.imeBottom.coerceAtLeast(0)
        return WebSafeAreaSnapshot(
            top = pixels.systemTop.coerceAtLeast(0) / density,
            right = pixels.systemRight.coerceAtLeast(0) / density,
            bottom = pixels.systemBottom.coerceAtLeast(0) / density,
            left = pixels.systemLeft.coerceAtLeast(0) / density,
            imeTop = (viewportHeight - imeBottom).coerceAtLeast(0) / density,
            imeHeight = imeBottom / density,
            imeProgress = animationBounds?.let {
                imeProgress(
                    currentBottom = imeBottom,
                    lowerBottom = it.lowerBottom,
                    upperBottom = it.upperBottom,
                    imeVisible = pixels.imeVisible,
                )
            } ?: if (pixels.imeVisible) 1.0 else 0.0,
        )
    }

    fun imeProgress(
        currentBottom: Int,
        lowerBottom: Int,
        upperBottom: Int,
        imeVisible: Boolean,
    ): Double {
        val lower = minOf(lowerBottom, upperBottom)
        val upper = maxOf(lowerBottom, upperBottom)
        val travel = upper - lower
        if (travel <= 0) return if (imeVisible) 1.0 else 0.0
        return ((currentBottom - lower).toDouble() / travel).coerceIn(0.0, 1.0)
    }
}
