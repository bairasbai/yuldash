package com.yuldash.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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

@Composable
fun YuldashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YuldashLightColors,
        content = content
    )
}
