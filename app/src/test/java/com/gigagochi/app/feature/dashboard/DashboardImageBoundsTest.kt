package com.gigagochi.app.feature.dashboard

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun dashboardActionsUseAlreadyInsetViewportAfterCoverScale() {
        assertEquals(92.dp, DashboardExperienceTop)
        assertEquals(20.dp, DashboardInputHorizontalPadding)
        assertEquals(362.dp, DashboardInputMaxWidth)
        assertEquals(762.dp, dashboardActionTop(874.dp, 1f))
        assertEquals(672.203.dp, dashboardFeedRowTop(762.dp))

        val scale = 411f / 402f
        val viewportHeight = 823.dp
        val actionTop = dashboardActionTop(viewportHeight, scale)
        val visibleReferenceBottom = viewportHeight / scale

        assertTrue(actionTop < 762.dp)
        assertTrue(actionTop + 58.203.dp + 16.dp <= visibleReferenceBottom)
        assertTrue(dashboardFeedRowTop(actionTop) + 148.dp + 16.dp <= visibleReferenceBottom)
    }

    @Test
    fun characterMessageUsesAFixedBottomBoundary() {
        val anchor = 663.dp
        val containerTop = characterMessageContainerTop(anchor)

        assertEquals(586.dp, containerTop)
        assertEquals(anchor + CharacterMessageFixedBottomOffset, containerTop + 132.dp)
    }

    @Test
    fun characterMessageStaysAboveMeasuredInputSurface() {
        val inputTop = 464.dp
        val anchor = dialogueAnchorAboveInput(inputTop)
        val messageBottom = characterMessageContainerTop(anchor) + 132.dp

        assertEquals(452.dp, messageBottom)
        assertTrue(messageBottom < inputTop)
    }
}
