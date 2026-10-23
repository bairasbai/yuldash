package com.yuldash.app

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Клавиатура должна СЖИМАТЬ окно, а не сдвигать его целиком: при сдвиге шапка с «Назад» уезжает
 * за верх, а на Android 15+ к сдвигу добавляется наш отступ под клавиатуру — двойной скачок.
 * В манифесте режим не задан, и менять его для всего приложения — трогать каждый экран с полем
 * ввода. Поэтому экран (чат поездки, вход — приёмка 18.10, п.15) включает adjustResize, пока он
 * на экране, и возвращает прежний режим. Счётчик — на случай, когда при переходе на экране на миг
 * оказываются два таких экрана.
 *
 * Окно берём через `LocalView`, а не `LocalContext`: контекст приложения обёрнут под выбранный
 * язык и до Activity по нему не дойти — первая версия для входа молча ничего не делала.
 */
@Composable
internal fun ResizeWindowForKeyboard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null && KeyboardResizeMode.holders++ == 0) {
            KeyboardResizeMode.previous = window.attributes.softInputMode
            @Suppress("DEPRECATION")   // на API 30+ режим по-прежнему действует, иначе окно сдвигается целиком
            window.setSoftInputMode(
                (window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        onDispose {
            if (window != null && --KeyboardResizeMode.holders == 0) KeyboardResizeMode.previous?.let(window::setSoftInputMode)
        }
    }
}

private object KeyboardResizeMode {
    var holders = 0
    var previous: Int? = null
}
