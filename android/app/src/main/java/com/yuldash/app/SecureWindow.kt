package com.yuldash.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Экран с ЧУЖИМИ документами не попадает ни в скриншот, ни в список недавних приложений.
 *
 * Зачем (аудит 2026-08-12, волна 30). В очереди модерации админ видит права водителя, селфи
 * курьера, фото машины — паспортные данные посторонних людей. Android по умолчанию делает
 * снимок последнего экрана для переключателя приложений и разрешает скриншот кому угодно,
 * включая приложения с правом записи экрана. Пока телефон админа в руках хозяина, это неважно;
 * но именно на этом экране цена одного случайного взгляда — чужой документ.
 *
 * Почему НЕ вешаем флаг на всё приложение: скриншот поездки — полезная и любимая вещь
 * («скинул мужу номер машины»). Запрещать его целиком значило бы чинить не там. Флаг живёт
 * ровно столько, сколько открыт экран с документами.
 *
 * Счётчик, а не голый add/clear: если два таких экрана окажутся в стеке одновременно, выход
 * из верхнего не должен снимать защиту с нижнего.
 */
private var secureDepth = 0

@Composable
internal fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity != null) {
            secureDepth++
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        onDispose {
            if (activity != null) {
                secureDepth--
                if (secureDepth <= 0) {
                    secureDepth = 0
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

/** Compose отдаёт обёрнутый контекст — до Activity добираемся через цепочку обёрток. */
internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
