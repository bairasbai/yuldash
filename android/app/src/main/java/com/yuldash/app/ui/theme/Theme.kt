package com.yuldash.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val YuldashLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F5EC),
    onPrimaryContainer = Color(0xFF073F25),
    secondary = Color(0xFFD89B12),
    onSecondary = Color(0xFF241800),
    secondaryContainer = Color(0xFFFFE3A1),
    onSecondaryContainer = Color(0xFF241800),
    background = Color(0xFFFAFAF6),
    onBackground = Color(0xFF0B1F14),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1F14),
    surfaceVariant = Color(0xFFEDEFEA),
    onSurfaceVariant = Color(0xFF686F66),
)

// Тёмная палитра: глубокий зелёно-чёрный фон, мятные акценты, мягкие карточки.
private val YuldashDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF2FB36E),
    onPrimary = Color(0xFF052612),
    primaryContainer = Color(0xFF143024),
    onPrimaryContainer = Color(0xFF7FE3AB),
    secondary = Color(0xFFE8C36B),
    onSecondary = Color(0xFF241800),
    secondaryContainer = Color(0xFF4A3A14),
    onSecondaryContainer = Color(0xFFFFE3A1),
    background = Color(0xFF0F1613),
    onBackground = Color(0xFFEAF2EC),
    surface = Color(0xFF192420),
    onSurface = Color(0xFFEAF2EC),
    surfaceVariant = Color(0xFF202B25),
    onSurfaceVariant = Color(0xFF9BA49D),
)

@Composable
fun YuldashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) YuldashDarkColors else YuldashLightColors,
        content = content
    )
}
