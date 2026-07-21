package com.gigagochi.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

/** Shared by Dashboard actions and small contextual navigation stones. */
internal object GlassActionSurfaceContract {
    val Shape = RoundedCornerShape(24.dp)
    val Tint = Color.White.copy(alpha = .15f)
    val Style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(Tint)),
        blurRadius = 12.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Tint),
    )
    val BlurStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = emptyList(),
        blurRadius = 12.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Color.Transparent),
    )
    val HighlightInset = Shadow(
        radius = 3.dp,
        color = Color.White.copy(alpha = .2f),
        offset = DpOffset(1.dp, 2.dp),
    )
    val ShadeInset = Shadow(
        radius = 2.dp,
        color = Color.Black.copy(alpha = .2f),
        offset = DpOffset((-4).dp, (-4).dp),
    )
}
