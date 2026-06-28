package com.yuldash.app.data

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Аналитика Юлдаша (Firebase Analytics). Метрики смотрятся в консоли Firebase (проект yuldash-9586e):
 * DAU/MAU, удержание (день 1/7/30), сессии, страна/устройство — авто; ключевые события — ниже.
 * Активна только при наличии google-services.json (как и FCM). Без него — тихо ничего (не падает).
 */
object Analytics {
    private var fa: FirebaseAnalytics? = null

    fun init(context: Context) {
        runCatching { fa = FirebaseAnalytics.getInstance(context.applicationContext) }
    }

    /** Событие воронки. Имя — snake_case (login, create_request, booking…), параметры опц. */
    fun log(event: String, params: Map<String, String> = emptyMap()) {
        val a = fa ?: return
        runCatching {
            a.logEvent(event, Bundle().apply { params.forEach { (k, v) -> putString(k, v) } })
        }
    }
}
