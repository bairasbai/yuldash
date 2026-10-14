package com.yuldash.app

// Шаги поездки попутки для закреплённой кнопки экрана активной поездки. Вынесено из
// BookingActiveTripScreen.kt (храповик размера файлов, 14.10.2026): чистая логика без Compose.

/**
 * Следующий РЕАЛЬНЫЙ серверный переход для одной закреплённой CTA варианта B.
 *
 * [passengerStatus] — что пассажир нажал в ЭТОМ запуске (живёт в памяти экрана), [bookingStatus] —
 * статус брони с сервера. Посадку решает сервер: «Я сел» переводит бронь в onboard. Локальная
 * отметка теряется при перезапуске приложения — раньше заголовок по серверу уже говорил
 * «Поездка идёт», а кнопка снова звала «Я сел» (проверка волны 1, 14.10.2026).
 */
internal fun activeTripNextStatus(role: String, driverPhase: String, passengerStatus: String?, bookingStatus: String): String =
    if (role == "driver") {
        when (driverPhase) {
            "departed" -> "arriving"
            "arriving", "done" -> "done"
            else -> "departed"
        }
    } else {
        val step = passengerStatus ?: when (bookingStatus) {
            "onboard" -> "sat"
            "done" -> "done"
            else -> null
        }
        when (step) {
            "sat" -> "arrived"
            "arrived", "done" -> "done"
            else -> "sat"
        }
    }
