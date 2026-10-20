package com.kingscan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF1B3A4B)
val Seal = Color(0xFFD86B32)
val Paper = Color(0xFFF3EEE4)
val PaperCard = Color(0xFFFFFCF7)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color(0xFFF6F1E7),
    secondary = Seal,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF1C1915),
    surface = PaperCard,
    onSurface = Color(0xFF1C1915),
    surfaceVariant = Color(0xFFE7DFD2),
    onSurfaceVariant = Color(0xFF5C564C),
    outline = Color(0xFFD5CBBC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE7C7A2),
    onPrimary = Color(0xFF2A2118),
    secondary = Color(0xFFE08A55),
    onSecondary = Color(0xFF2A160C),
    background = Color(0xFF12171A),
    onBackground = Color(0xFFF3EEE6),
    surface = Color(0xFF1C2429),
    onSurface = Color(0xFFF3EEE6),
    surfaceVariant = Color(0xFF2A343A),
    onSurfaceVariant = Color(0xFFC9C0B4),
    outline = Color(0xFF3C474D),
)

private val ScanTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun KingScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ScanTypography,
        content = content,
    )
}
