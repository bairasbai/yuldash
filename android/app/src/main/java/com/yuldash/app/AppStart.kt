package com.yuldash.app

import android.content.SharedPreferences

// Первый экран после сплэша. Вынесено из YuldashApp.kt, чтобы правило проверялось тестом без
// всего корня навигации (приёмка 18.10.2026, находка 3).

/** Отметка «вводные экраны уже показывали на этом телефоне» (SessionKeys.kt). */
internal const val ONBOARDING_COMPLETED = "onboarding_completed"

/**
 * Куда вести после сплэша. Старт всегда на главной — карте, кабинет водитель открывает сам.
 * Вход проверяем раньше вводных: человек мог войти по ссылке на поездку, вводных не видя, — интро
 * ему уже ни к чему, а «Пропустить» вела бы на вход, хотя он вошёл (приёмка 18.10.2026).
 */
internal fun appStartScreen(onboardingCompleted: Boolean, loggedIn: Boolean, debug: Boolean): Screen = when {
    loggedIn -> Screen.Home               // уже вошёл → карта
    !onboardingCompleted -> Screen.Intro  // ПЕРВЫЙ запуск → брендовое интро (даже в debug)
    debug -> Screen.Home                  // DEV-обход входа (повторные запуски) → карта
    else -> Screen.Login
}

/** Человек вошёл — вводные ему больше не нужны, даже если он их не видел (вход по ссылке). */
internal fun SharedPreferences.markOnboardingCompleted() = edit().putBoolean(ONBOARDING_COMPLETED, true).apply()
