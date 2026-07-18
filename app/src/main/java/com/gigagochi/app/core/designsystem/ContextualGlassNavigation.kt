package com.gigagochi.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

enum class ContextualNavigationAction(val contentDescription: String) {
    Back("Назад"),
    Close("Закрыть"),
}

private val AutoMirroredBackIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "AutoMirroredBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f)
            horizontalLineTo(7.83f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13f)
            horizontalLineTo(20f)
            close()
        }
    }.build()
}

private val CloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Close",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(18.3f, 5.71f)
            lineTo(12f, 12f)
            lineTo(5.7f, 5.71f)
            lineTo(4.29f, 7.12f)
            lineTo(10.59f, 13.41f)
            lineTo(4.29f, 19.71f)
            lineTo(5.7f, 21.12f)
            lineTo(12f, 14.83f)
            lineTo(18.3f, 21.12f)
            lineTo(19.71f, 19.71f)
            lineTo(13.41f, 13.41f)
            lineTo(19.71f, 7.12f)
            close()
        }
    }.build()
}

val ContextualNavigationMinimumTouchTarget = 48.dp

@Composable
fun ContextualGlassNavigation(
    action: ContextualNavigationAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val glass = GlassActionSurfaceContract
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .requiredSize(ContextualNavigationMinimumTouchTarget)
            .clip(glass.Shape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(hazeState, glass.Style)
                } else {
                    Modifier.background(glass.Tint)
                },
            )
            .innerShadow(glass.Shape, glass.HighlightInset)
            .innerShadow(glass.Shape, glass.ShadeInset)
            .clickable(
                role = Role.Button,
                onClickLabel = action.contentDescription,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = action.contentDescription
            },
    ) {
        Icon(
            imageVector = when (action) {
                ContextualNavigationAction.Back -> AutoMirroredBackIcon
                ContextualNavigationAction.Close -> CloseIcon
            },
            contentDescription = null,
            tint = Color.White,
        )
    }
}
