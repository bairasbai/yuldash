package com.yuldash.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Дизайн-токены: адаптивная палитра Canon* (светлый/тёмный) + формы карточек.
// Вынесено из MainActivity.kt (Фаза 0 разрезки). Тот же пакет com.yuldash.app → импортов не нужно.
// Один и тот же CanonX отдаёт цвет по теме — меняется только определение, использования не трогаем.

/** Тема приложения: null = как в системе, true = тёмная, false = светлая (тумблер день/ночь в шапке). */
internal object ThemePrefs {
    var darkOverride by mutableStateOf<Boolean?>(null)
}

@Composable
internal fun appIsDark(): Boolean = ThemePrefs.darkOverride ?: isSystemInDarkTheme()

internal val CanonGreen: Color @Composable get() = if (appIsDark()) Color(0xFF7FE3AB) else Color(0xFF073F25)
internal val CanonGreen2: Color @Composable get() = if (appIsDark()) Color(0xFF27A463) else Color(0xFF0B6B3A)  // тёмный затемнён под WCAG (белый текст на кнопке ≥3:1)
// Фиксированные тёмно-зелёные (НЕ адаптивные) — для шапок-градиентов с белым текстом.
// CanonGreen/CanonGreen2 в тёмной теме инвертируются в светлую мяту → белый текст на них нечитаем.
internal val CanonGreenInk: Color = Color(0xFF0B6B3A)      // верх градиента шапки
internal val CanonGreenInkDark: Color = Color(0xFF073F25)  // низ градиента шапки
internal val CanonMint: Color @Composable get() = if (appIsDark()) Color(0xFF0F2419) else Color(0xFFE7F5EC)  // тёмная мята темнее под контраст зелёного текста
internal val CanonYellow: Color @Composable get() = if (appIsDark()) Color(0xFF4A3A14) else Color(0xFFFFE3A1)
// Брендовое золото (как дорога на карте/лого) — заливка акцентной кнопки. Золотое в обеих темах → текст фиксированно тёмный.
internal val CanonGold: Color @Composable get() = if (appIsDark()) Color(0xFFE8C36B) else Color(0xFFF5B301)
internal val CanonGoldInk: Color = Color(0xFF0B3D20)
internal val CanonBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F1613) else Color(0xFFFAFAF6)
internal val CanonText: Color @Composable get() = if (appIsDark()) Color(0xFFEAF2EC) else Color(0xFF0B1F14)
internal val CanonMuted: Color @Composable get() = if (appIsDark()) Color(0xFF9BA49D) else Color(0xFF686F66)
internal val CanonBorder: Color @Composable get() = if (appIsDark()) Color(0x24FFFFFF) else Color(0x1F000000)
internal val CanonRed: Color @Composable get() = if (appIsDark()) Color(0xFFF25A4D) else Color(0xFFCC2A20)  // WCAG: белый на красной кнопке ≥3:1, красный текст на фоне/danger-bg ≥4.5:1
// Поверхность карточек: была хардкод Color.White — теперь адаптивная.
internal val CanonSurface: Color @Composable get() = if (appIsDark()) Color(0xFF192420) else Color(0xFFFFFFFF)
// Подложка опасности/ошибки (SOS, ошибки) — адаптивная (светло-розовая / тёмно-красная).
internal val CanonDangerBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A1B18) else Color(0xFFFDECEA)
// Предупреждение/в процессе (pending, черновик): подложка + текст — адаптивные.
internal val CanonWarnBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A2E12) else Color(0xFFFFF2D6)
internal val CanonWarn: Color @Composable get() = if (appIsDark()) Color(0xFFE8B86A) else Color(0xFF9A6200)  // светлый затемнён под WCAG (текст на жёлтом фоне ≥4.5:1)
// F9 «Женщинам — водитель-женщина»: деликатный сигнал «женщина за рулём» (opt-in).
// Мягкий сливово-розовый, отличается от зелёного «проверен»/«на линии», но остаётся спокойным.
internal val CanonWomanBg: Color @Composable get() = if (appIsDark()) Color(0xFF2E1A27) else Color(0xFFF7E9F1)
internal val CanonWoman: Color @Composable get() = if (appIsDark()) Color(0xFFE39BC4) else Color(0xFF8E3B6B)  // текст/иконка на CanonWomanBg ≥4.5:1
// Золото рейтинга (звёзды). Plain val — читается и из @Composable, и из не-composable (Canvas). Золото видно в обеих темах.
internal val CanonStar: Color = Color(0xFFE7A921)
// Тонкая зелёная разделительная линия (border карточек) — была хардкод 0x1A0B6B3A в нескольких экранах.
internal val CanonHairlineGreen: Color = Color(0x1A0B6B3A)
// Тонкая красная рамка danger-карточек (SOS) — была хардкод 0x33D93025 в SafetyScreen.
internal val CanonDangerBorder: Color = Color(0x33D93025)
internal val CanonCardShape = RoundedCornerShape(28.dp)
internal val CanonItemShape = RoundedCornerShape(22.dp)
