package com.yuldash.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Подмножество дизайн-токенов Юлдаша (Canon*), портированное 1:1 из
// android/app/src/main/java/com/yuldash/app/CanonTokens.kt — но теперь это ОБЩИЙ код (commonMain),
// поэтому те же цвета и формы работают и на Android, и на iPhone без изменений.
// Приём тот же: один токен сам отдаёт цвет по теме (свет/тьма).

internal object ThemePrefs {
    var darkOverride by mutableStateOf<Boolean?>(null)
}

@Composable
internal fun appIsDark(): Boolean = ThemePrefs.darkOverride ?: isSystemInDarkTheme()

internal val CanonGreen: Color @Composable get() = if (appIsDark()) Color(0xFF7FE3AB) else Color(0xFF073F25)
internal val CanonGreen2: Color @Composable get() = if (appIsDark()) Color(0xFF27A463) else Color(0xFF0B6B3A)
internal val CanonMint: Color @Composable get() = if (appIsDark()) Color(0xFF0F2419) else Color(0xFFE7F5EC)
internal val CanonGold: Color @Composable get() = if (appIsDark()) Color(0xFFE8C36B) else Color(0xFFF5B301)
internal val CanonGoldInk: Color = Color(0xFF0B3D20)
internal val CanonBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F1613) else Color(0xFFFAFAF6)
internal val CanonText: Color @Composable get() = if (appIsDark()) Color(0xFFEAF2EC) else Color(0xFF0B1F14)
internal val CanonMuted: Color @Composable get() = if (appIsDark()) Color(0xFF9BA49D) else Color(0xFF686F66)
internal val CanonBorder: Color @Composable get() = if (appIsDark()) Color(0x24FFFFFF) else Color(0x1F000000)
internal val CanonSurface: Color @Composable get() = if (appIsDark()) Color(0xFF192420) else Color(0xFFFFFFFF)
internal val CanonCardShape = RoundedCornerShape(28.dp)
internal val CanonItemShape = RoundedCornerShape(22.dp)
