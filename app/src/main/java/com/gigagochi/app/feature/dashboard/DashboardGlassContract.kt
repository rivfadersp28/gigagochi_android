package com.gigagochi.app.feature.dashboard

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import com.gigagochi.app.core.designsystem.GlassActionSurfaceContract

internal object DashboardGlassContract {
    const val MinimumSupportedSdk = 23
    const val NativeBlurMinimumSdk = 31

    val ActionShape = GlassActionSurfaceContract.Shape
    val ActionTint = GlassActionSurfaceContract.Tint
    val ActionStyle = GlassActionSurfaceContract.Style
    val ActionBlurStyle = GlassActionSurfaceContract.BlurStyle
    val ActionHighlightInset = GlassActionSurfaceContract.HighlightInset
    val ActionShadeInset = GlassActionSurfaceContract.ShadeInset

    val ConversationShape = RoundedCornerShape(56.dp)
    val FoodShape = RoundedCornerShape(28.dp)
    val InlineTint = Color.White.copy(alpha = .15f)
    val ConversationOutline = Color.White.copy(alpha = .16f)
    val InlineStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tints = listOf(HazeTint(InlineTint)),
        blurRadius = 12.dp,
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
