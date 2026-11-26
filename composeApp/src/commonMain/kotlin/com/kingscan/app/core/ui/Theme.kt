package com.kingscan.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF1B3A4B)
val Seal = Color(0xFFD86B32)
val Paper = Color(0xFFF3EEE4)
val PaperCard = Color(0xFFFFFCF7)

// Material types are an internal shared vocabulary. Each platform supplies its own appearance.
object ScanTheme {
    val colorScheme: ColorScheme @Composable get() = MaterialTheme.colorScheme
    val typography: Typography @Composable get() = MaterialTheme.typography
    val shapes: Shapes @Composable get() = MaterialTheme.shapes
}

internal data class ScanAppearance(val colors: ColorScheme, val typography: Typography, val shapes: Shapes = Shapes())
internal val LocalScanControlBackground = staticCompositionLocalOf { Color.Unspecified }
@Composable internal expect fun platformAppearance(dark: Boolean): ScanAppearance

internal fun brandColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color(0xFF9CCBE0), onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF184C60), onPrimaryContainer = Color(0xFFC1E8FC),
    secondary = Color(0xFFE7BA9C), onSecondary = Color(0xFF432B1A),
    secondaryContainer = Color(0xFF5C412D), onSecondaryContainer = Color(0xFFFFDBC3),
    tertiary = Color(0xFFB9CEAB), onTertiary = Color(0xFF25351C),
    tertiaryContainer = Color(0xFF3B4C31), onTertiaryContainer = Color(0xFFD5EAC6),
    background = Color(0xFF11181C), onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF11181C), onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF40484D), onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9297), outlineVariant = Color(0xFF40484D),
    surfaceContainerLowest = Color(0xFF0C1317), surfaceContainerLow = Color(0xFF192024),
    surfaceContainer = Color(0xFF1D2428), surfaceContainerHigh = Color(0xFF272E32),
    surfaceContainerHighest = Color(0xFF32393D), surfaceDim = Color(0xFF11181C), surfaceBright = Color(0xFF373E42),
) else lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    primaryContainer = Color(0xFFC1E8FC), onPrimaryContainer = Color(0xFF003547),
    secondary = Color(0xFF765740), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC3), onSecondaryContainer = Color(0xFF5C412D),
    tertiary = Color(0xFF526449), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5EAC6), onTertiaryContainer = Color(0xFF3B4C31),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE4E9), onSurfaceVariant = Color(0xFF40484D),
    outline = Color(0xFF70787D), outlineVariant = Color(0xFFC0C8CD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEEF1), surfaceContainerHigh = Color(0xFFE5E8EC),
    surfaceContainerHighest = Color(0xFFDFE3E7), surfaceDim = Color(0xFFD7DADE), surfaceBright = Color(0xFFF7F9FC),
)

@Composable
fun KingScanTheme(content: @Composable () -> Unit) {
    val appearance = platformAppearance(isSystemInDarkTheme())
    MaterialTheme(colorScheme = appearance.colors, typography = appearance.typography, shapes = appearance.shapes) {
        CompositionLocalProvider(LocalScanControlBackground provides appearance.colors.background, content = content)
    }
}
