package com.yuldash.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

// Двуязычие RU/BA — портировано 1:1 из android/app/src/main/java/com/yuldash/app/AppText.kt.
// Тот же API appText(ru, ba) работает без изменений в общем коде → и на Android, и на iPhone.

internal enum class AppLanguage { Ru, Ba }

/** Текущий язык интерфейса в дереве Compose. */
internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.Ru }

/** Двуязычная надпись по текущему языку (в Compose-контексте). */
@Composable
internal fun appText(ru: String, ba: String): String =
    if (LocalAppLanguage.current == AppLanguage.Ba) ba else ru

/** Двуязычная надпись по явно переданному языку (вне LocalAppLanguage). */
internal fun appTextFor(language: AppLanguage, ru: String, ba: String): String =
    if (language == AppLanguage.Ba) ba else ru
