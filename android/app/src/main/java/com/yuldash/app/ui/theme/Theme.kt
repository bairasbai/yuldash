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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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

/** One owner per Activity window; platform splash removal may overwrite both appearance flags. */
class AppSystemBars(private val window: android.view.Window) {
    private var darkTheme = false
    private val lightIconOwners = mutableSetOf<Any>()

    fun updateTheme(dark: Boolean) {
        darkTheme = dark
        reapply()
    }

    /** A temporary dark scene restores the latest app theme, never a hard-coded light theme. */
    fun withLightIcons(): () -> Unit {
        val owner = Any()
        lightIconOwners += owner
        reapply()
        return { if (lightIconOwners.remove(owner)) reapply() }
    }

    fun reapply() {
        val dark = darkTheme || lightIconOwners.isNotEmpty()
        val barColor = (if (dark) YuldashDarkColors.background else Color(0xFFF7F7F2)).toArgb()
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

internal val LocalAppSystemBars = staticCompositionLocalOf<AppSystemBars?> { null }

@Composable
fun YuldashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    systemBars: AppSystemBars? = null,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val bars = if (view.isInEditMode) null else systemBars ?: remember(view) {
        view.context.findActivity()?.window?.let(::AppSystemBars)
    }
    SideEffect { bars?.updateTheme(darkTheme) }
    CompositionLocalProvider(LocalAppSystemBars provides bars) {
        MaterialTheme(
            colorScheme = if (darkTheme) YuldashDarkColors else YuldashLightColors,
            content = content
        )
    }
}
