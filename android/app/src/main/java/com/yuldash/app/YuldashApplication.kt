package com.yuldash.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import com.yuldash.app.data.Analytics
import com.yuldash.app.data.ApiClient
import io.sentry.android.core.SentryAndroid

/**
 * Application-класс Юлдаша. Задачи при старте процесса: отдать ключ Яндекс MapKit
 * (setApiKey должен идти ДО MapKitFactory.initialize, который вызывается лениво при
 * открытии вкладки «Карта») и, при наличии DSN, поднять Sentry (сбор ошибок/крашей).
 *
 * Ключи берутся из BuildConfig (они из local.properties, в git не попадают).
 * Если ключа MapKit нет — карту не инициализируем (фолбэк-превью). Если нет
 * SENTRY_DSN — Sentry не инициализируется (no-op), приложение работает как раньше.
 */
class YuldashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSentry()                      // сбор ошибок — до остального, чтобы поймать ранние сбои
        ApiClient.init(this)              // загрузить сохранённый токен сессии
        Analytics.init(this)              // Firebase Analytics: DAU/удержание/воронка событий
        ApiClient.registerCurrentPushToken()   // если уже вошли — зарегистрировать устройство для push
        if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
            MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_KEY)
        }
    }

    /** Поднять Sentry только при заданном DSN. Пусто → no-op (ничего не шлётся). */
    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "yuldash@" + BuildConfig.VERSION_NAME
            // Приватность (152-ФЗ): не отправлять PII — телефоны/координаты/токены.
            options.isSendDefaultPii = false
        }
    }
}
