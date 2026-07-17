package com.gigagochi.app.feature.create

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

internal object CreateGlassContract {
    const val MinimumSupportedSdk = 23
    const val NativeBlurMinimumSdk = 31

    val Shape = RoundedCornerShape(24.dp)
    val Tint = Color.White.copy(alpha = .60f)
    val Style = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(Tint)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(Tint),
    )
    val BottomInset = Shadow(
        radius = 6.dp,
        color = Color(0x6600182E),
        offset = DpOffset(0.dp, (-5).dp),
    )
}
