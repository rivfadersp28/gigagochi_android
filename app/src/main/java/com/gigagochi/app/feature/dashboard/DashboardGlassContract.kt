package com.gigagochi.app.feature.dashboard

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

internal object DashboardGlassContract {
    const val MinimumSupportedSdk = 23
    const val NativeBlurMinimumSdk = 31

    val ActionShape = RoundedCornerShape(24.dp)
    val ActionTint = Color.White.copy(alpha = .15f)
    val ActionStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(ActionTint)),
        blurRadius = 12.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(ActionTint),
    )
    val ActionHighlightInset = Shadow(
        radius = 3.dp,
        color = Color.White.copy(alpha = .2f),
        offset = DpOffset(1.dp, 2.dp),
    )
    val ActionShadeInset = Shadow(
        radius = 2.dp,
        color = Color.Black.copy(alpha = .2f),
        offset = DpOffset((-4).dp, (-4).dp),
    )

    val ConversationShape = RoundedCornerShape(56.dp)
    val FoodShape = RoundedCornerShape(28.dp)
    val InlineTint = Color.White.copy(alpha = .15f)
    val InlineStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(InlineTint)),
        blurRadius = 18.5.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(InlineTint),
    )
    val ConversationHighlightInset = Shadow(
        radius = 12.dp,
        color = Color.White.copy(alpha = .3f),
        offset = DpOffset(0.dp, 3.dp),
    )
    val ConversationSoftInset = Shadow(
        radius = 4.dp,
        color = Color.White.copy(alpha = .09f),
        offset = DpOffset(0.dp, 2.dp),
    )
    val FoodInset = Shadow(
        radius = 4.dp,
        color = Color.White.copy(alpha = .09f),
        offset = DpOffset(0.dp, 2.dp),
    )

    val ExperienceShape = RoundedCornerShape(31.927.dp)
    val ExperienceTint = Color(0x29845C05)
    val ExperienceStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(ExperienceTint)),
        blurRadius = 8.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(ExperienceTint),
    )
}
