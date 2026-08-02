package com.yuldash.app

import android.content.Context
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

/**
 * Крупный шрифт — глобальный тумблер размера ВСЕГО текста (для пожилых и слабовидящих).
 * [multiplier] умножается на системный fontScale в корне композиции (см. MainActivity.setContent),
 * поэтому масштабируется весь sp-текст разом, без правки экранов, и системная настройка тоже уважается.
 */
internal enum class FontScaleOption(val multiplier: Float) {
    Normal(1.0f),
    Large(1.15f),
    ExtraLarge(1.30f),
}

/** Единая точка правды выбранного размера текста. Читается в корне (Density) и в настройках/простом режиме. */
internal object FontScalePrefs {
    private const val PREFS = "yuldash_prefs"
    private const val KEY = "font_scale"

    var option by mutableStateOf(FontScaleOption.Normal)
        private set

    /** Восстановить сохранённый выбор при старте (зовётся из MainActivity.onCreate). */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        option = runCatching { FontScaleOption.valueOf(saved) }.getOrDefault(FontScaleOption.Normal)
    }

    /** Сменить размер: применяется сразу (state вверху) и сохраняется на диск. */
    fun set(context: Context, value: FontScaleOption) {
        option = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}

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
// Более контрастный вариант приглушённого текста (неактивные подписи нав-меню и т.п.): темнее/светлее CanonMuted, но не активный цвет. Контраст с фоном ≥4.5:1.
internal val CanonMutedStrong: Color @Composable get() = if (appIsDark()) Color(0xFFC2CBC3) else Color(0xFF4C534B)
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
// Золото рейтинга (звёзды). Адаптивное: яркое золото на белой карточке давало контраст 2.08 —
// ниже порога 3:1 для значащей графики (в выборе оценки звёзды И ЕСТЬ информация, а не украшение).
// В светлой теме глубокая охра (3.43), в тёмной прежнее яркое золото (7.68). Проверяется
// расчётом: tools/contrast.py.
internal val CanonStar: Color @Composable get() = if (appIsDark()) Color(0xFFE7A921) else Color(0xFFBE7D00)
// Режимы поездки (переключатель пассажира): Такси = ЖЁЛТЫЙ (привычный цвет такси), Попутка = ЗЕЛЁНЫЙ (бренд «свои»).
// Акцент окрашивает активный сегмент, кнопку действия и индикатор-полоску. Оба варианта — светлый/тёмный.
internal val CanonTaxi: Color @Composable get() = if (appIsDark()) Color(0xFFF2C14E) else Color(0xFFE8A200)       // акцент такси (кнопка/полоска/иконка)
internal val CanonTaxiBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A2E12) else Color(0xFFFFEFC2)      // мягкая подложка активного сегмента «Такси»
internal val CanonTaxiInk: Color = Color(0xFF3A2A00)                                                               // тёмный текст НА ЖЁЛТОМ (CanonTaxi жёлтый в обеих темах — ink подходит всегда)
// Текст на ПОДЛОЖКЕ такси (CanonTaxiBg), а это разные вещи: подложка в тёмной теме
// тёмно-коричневая, и тёмный CanonTaxiInk на ней давал контраст 1.05 — то есть госномер
// машины в бейдже был не виден вообще. А смысл бейджа именно в том, чтобы пассажир сверил
// номер и не сел в чужую машину. Светлая тема 12.16, тёмная 10.61 (tools/contrast.py).
internal val CanonTaxiText: Color @Composable get() = if (appIsDark()) Color(0xFFFFE3A1) else Color(0xFF3A2A00)
internal val CanonPooling: Color @Composable get() = if (appIsDark()) Color(0xFF27A463) else Color(0xFF0B6B3A)     // акцент попутки (бренд-зелёный)
internal val CanonPoolingBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F2419) else Color(0xFFE7F5EC)   // мягкая подложка активного сегмента «Попутка»
// Тонкая зелёная разделительная линия (border карточек) — была хардкод 0x1A0B6B3A в нескольких экранах.
// Теперь адаптивна: тёмный зелёный почти невидим на тёмном фоне → в тёмной теме светлая мятная линия.
internal val CanonHairlineGreen: Color @Composable get() = if (appIsDark()) Color(0x2E7FE3AB) else Color(0x1A0B6B3A)
// Тонкая красная рамка danger-карточек (SOS) — была хардкод 0x33D93025 в SafetyScreen.
// Адаптивна: в тёмной теме чуть светлее и заметнее на тёмном danger-bg.
internal val CanonDangerBorder: Color @Composable get() = if (appIsDark()) Color(0x55F25A4D) else Color(0x33D93025)
internal val CanonCardShape = RoundedCornerShape(28.dp)
internal val CanonItemShape = RoundedCornerShape(22.dp)
