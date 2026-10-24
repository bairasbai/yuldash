package com.yuldash.app

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Клавиатура должна СЖИМАТЬ окно, а не сдвигать его целиком: при сдвиге шапка с «Назад» уезжает
 * за верх, а на Android 15+ к сдвигу добавляется наш отступ под клавиатуру — двойной скачок.
 * В манифесте режим не задан, и менять его для всего приложения — трогать каждый экран с полем
 * ввода. Поэтому экран (чат поездки, вход — приёмка 18.10, п.15) включает adjustResize, пока он
 * на экране, и возвращает прежний режим. Счётчик — на случай, когда при переходе на экране на миг
 * оказываются два таких экрана.
 *
 * Окно берём через `LocalView`, а не `LocalContext`: контекст приложения обёрнут под выбранный
 * язык и до Activity по нему не дойти.
 *
 * Повтор после кадра (эмулятор, 23.10): на холодном старте, когда экран открыт сразу (вход),
 * первая установка остаётся только в настройках окна и до системы не доходит — система сама
 * выбирает «сдвигать». Повтор после первого кадра и при каждом возвращении на экран (например,
 * из Telegram) доводит режим до системы; смена «через сдвиг» нужна, чтобы окно заметило изменение.
 */
@Composable
internal fun ResizeWindowForKeyboard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null && KeyboardResizeMode.holders++ == 0) {
            KeyboardResizeMode.previous = window.attributes.softInputMode
            window.adjust(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        onDispose {
            if (window != null && --KeyboardResizeMode.holders == 0) window.setSoftInputMode(restoredMode(KeyboardResizeMode.previous ?: 0))
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(view, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withFrameNanos { }
            val window = view.context.findActivity()?.window ?: return@repeatOnLifecycle
            if (KeyboardResizeMode.holders > 0) {
                window.adjust(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
                window.adjust(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }
}

/**
 * Какой режим вернуть, когда экран ушёл. «Не задан» (так у приложения: в манифесте режима нет)
 * Android назад не принимает — окно осталось бы «поджимать», и на Android 15+ поля без `imePadding`
 * уходили бы под клавиатуру (ревью Opus 23.10, P1-1). Поэтому вместо «не задан» — то, что система сама
 * выбирает для такого окна: «сдвигать». Явно заданный прежний режим возвращается как был.
 */
internal fun restoredMode(previous: Int): Int =
    if ((previous and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
        (previous and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    else previous

@Suppress("DEPRECATION")   // на API 30+ режим по-прежнему действует, иначе окно сдвигается целиком
private fun Window.adjust(mode: Int) =
    setSoftInputMode((attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or mode)

private object KeyboardResizeMode {
    var holders = 0
    var previous: Int? = null
}
