package com.gigagochi.app.core.webview

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSafeAreaCalculatorTest {
    @Test
    fun `safe area is projected from pixels to dp with continuous ime progress`() {
        val snapshot = WebSafeAreaCalculator.calculate(
            pixels = WebInsetPixels(
                viewportHeight = 1_748,
                systemTop = 48,
                systemRight = 6,
                systemBottom = 64,
                systemLeft = 4,
                imeBottom = 380,
                imeVisible = true,
            ),
            density = 2.0,
            animationBounds = WebImeAnimationBounds(lowerBottom = 80, upperBottom = 680),
        )

        assertEquals(24.0, snapshot.top, 0.0)
        assertEquals(3.0, snapshot.right, 0.0)
        assertEquals(32.0, snapshot.bottom, 0.0)
        assertEquals(2.0, snapshot.left, 0.0)
        assertEquals(684.0, snapshot.imeTop, 0.0)
        assertEquals(190.0, snapshot.imeHeight, 0.0)
        assertEquals(0.5, snapshot.imeProgress, 0.0)
    }

    @Test
    fun `ime progress uses ordered animation bounds and clamps overshoot`() {
        assertEquals(
            0.5,
            WebSafeAreaCalculator.imeProgress(
                currentBottom = 400,
                lowerBottom = 700,
                upperBottom = 100,
                imeVisible = true,
            ),
            0.0,
        )
        assertEquals(
            0.0,
            WebSafeAreaCalculator.imeProgress(
                currentBottom = 0,
                lowerBottom = 80,
                upperBottom = 680,
                imeVisible = false,
            ),
            0.0,
        )
        assertEquals(
            1.0,
            WebSafeAreaCalculator.imeProgress(
                currentBottom = 900,
                lowerBottom = 80,
                upperBottom = 680,
                imeVisible = true,
            ),
            0.0,
        )
    }

    @Test
    fun `degenerate ime bounds fall back to visibility`() {
        assertEquals(
            1.0,
            WebSafeAreaCalculator.imeProgress(
                currentBottom = 300,
                lowerBottom = 300,
                upperBottom = 300,
                imeVisible = true,
            ),
            0.0,
        )
        assertEquals(
            0.0,
            WebSafeAreaCalculator.imeProgress(
                currentBottom = 0,
                lowerBottom = 0,
                upperBottom = 0,
                imeVisible = false,
            ),
            0.0,
        )
    }
}
