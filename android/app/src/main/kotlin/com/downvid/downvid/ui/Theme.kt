package com.downvid.downvid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand colors (logo gradient). */
object Brand {
    val Violet = Color(0xFF7B5CFF)
    val Pink = Color(0xFFFF4F8B)
    val gradient = Brush.linearGradient(listOf(Violet, Pink), start = Offset.Zero, end = Offset.Infinite)
}

// Tonal palette generated from the violet seed (same hues as the Flutter
// "vibrant" scheme), fixed so the app does not depend on wallpaper colors.
private val Light = lightColorScheme(
    primary = Color(0xFF6750E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF7E4E9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DAFF),
    onSecondaryContainer = Color(0xFF320047),
    tertiary = Color(0xFFB3265E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E001C),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE5DFF3),
    onSurfaceVariant = Color(0xFF484555),
    outline = Color(0xFF797586),
    outlineVariant = Color(0xFFC9C4D7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F1FD),
    surfaceContainer = Color(0xFFF0EBF8),
    surfaceContainerHigh = Color(0xFFEAE5F2),
    surfaceContainerHighest = Color(0xFFE5DFEC),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF36169E),
    primaryContainer = Color(0xFF5235CF),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFE4B8FF),
    onSecondary = Color(0xFF4B1D6B),
    secondaryContainer = Color(0xFF643684),
    onSecondaryContainer = Color(0xFFF3DAFF),
    tertiary = Color(0xFFFFB1C8),
    onTertiary = Color(0xFF65002F),
    tertiaryContainer = Color(0xFF8F0A45),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0EA),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0EA),
    surfaceVariant = Color(0xFF484555),
    onSurfaceVariant = Color(0xFFC9C4D7),
    outline = Color(0xFF938FA0),
    outlineVariant = Color(0xFF484555),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B21),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun typography(): Typography {
    val t = Typography()
    return t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppTypography = typography()

@Composable
fun DownVidTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = AppTypography, content = content)
}

/** Semantic colors not covered by ColorScheme. */
val ColorScheme.success get() = if (isLight) Color(0xFF1E8E5A) else Color(0xFF6FD9A0)
val ColorScheme.warning get() = if (isLight) Color(0xFFB26A00) else Color(0xFFFFC266)
private val ColorScheme.isLight get() = background.luminanceApprox() > 0.5f

private fun Color.luminanceApprox() = 0.299f * red + 0.587f * green + 0.114f * blue
