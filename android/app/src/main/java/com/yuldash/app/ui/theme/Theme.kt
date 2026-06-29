package com.yuldash.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun YuldashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // Красим системные статус/нав-бары под тему. Завязано на darkTheme (= appIsDark()),
        // поэтому работает и для системной тёмной, и для ручного переключателя темы в приложении
        // (values-night срабатывает только по системному режиму — здесь покрываем оба случая).
        val barColor = (if (darkTheme) Color(0xFF0F1613) else Color(0xFFF7F7F2)).toArgb()
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) YuldashDarkColors else YuldashLightColors,
        content = content
    )
}
