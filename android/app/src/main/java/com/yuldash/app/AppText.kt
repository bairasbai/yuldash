package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

// Двуязычие RU/BA. Вынесено из MainActivity.kt (Фаза 0 разрезки) — от этого зависят все экраны.
// Тот же пакет com.yuldash.app → импортов не нужно; видимость internal (виден всему модулю).

/** Язык интерфейса. Переключается кнопкой, раздаётся через LocalAppLanguage. */
internal enum class AppLanguage { Ru, Ba }

/** Текущий язык в дереве Compose. */
internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.Ru }

/** Двуязычная надпись по текущему языку (в Compose-контексте). */
@Composable
internal fun appText(ru: String, ba: String): String =
    if (LocalAppLanguage.current == AppLanguage.Ba) ba else ru

/** Двуязычная надпись по явно переданному языку (вне LocalAppLanguage). */
internal fun appTextFor(language: AppLanguage, ru: String, ba: String): String =
    if (language == AppLanguage.Ba) ba else ru
