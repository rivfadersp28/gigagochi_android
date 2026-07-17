package com.gigagochi.app.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.gigagochi.app.R

val OpenRundeFontFamily = FontFamily(
    Font(R.font.open_runde_regular, FontWeight.Normal),
    Font(R.font.open_runde_medium, FontWeight.Medium),
    Font(R.font.open_runde_semibold, FontWeight.SemiBold),
    Font(R.font.open_runde_bold, FontWeight.Bold),
)

private val GigagochiColors = lightColorScheme(
    primary = Color(0xFF333333),
    onPrimary = Color.White,
    surface = Color(0xFFF7F3E7),
    onSurface = Color(0xFF333333),
    background = Color(0xFFBDBBB3),
    onBackground = Color(0xFF333333),
)

@Composable
fun GigagochiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GigagochiColors, content = content)
}
