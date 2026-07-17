package com.gigagochi.app.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardImageBoundsTest {
    @Test
    fun rejectsDecompressionBombDimensionsAndSamplesSafeLargeImage() {
        assertNull(boundedImageSampleSize(20_000, 100))
        assertNull(boundedImageSampleSize(10_000, 10_000))
        assertNull(boundedImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1, boundedImageSampleSize(402, 874))
        assertEquals(2, boundedImageSampleSize(3_000, 5_000))
    }
}
