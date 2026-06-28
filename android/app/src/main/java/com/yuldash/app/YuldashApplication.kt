package com.yuldash.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import com.yuldash.app.data.Analytics
import com.yuldash.app.data.ApiClient

/**
 * Application-класс Юлдаша. Единственная задача сейчас — отдать ключ Яндекс MapKit
 * один раз при старте процесса (setApiKey должен идти ДО MapKitFactory.initialize,
 * который вызывается лениво при открытии вкладки «Карта»).
 *
 * Ключ берётся из BuildConfig (он из local.properties, в git не попадает).
 * Если ключа нет — карту не инициализируем, экран «Карта» показывает фолбэк-превью.
 */
class YuldashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)              // загрузить сохранённый токен сессии
        Analytics.init(this)              // Firebase Analytics: DAU/удержание/воронка событий
        ApiClient.registerCurrentPushToken()   // если уже вошли — зарегистрировать устройство для push
        if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
            MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_KEY)
        }
    }
}
