package com.yuldash.app

import android.content.Context
import android.widget.Toast
import com.yuldash.app.data.ApiException

/** Демо-поездка не бронируется; человек видит причину вместо молчащей кнопки. */
internal fun bookingRideIdOrNotify(ride: Ride?, context: Context, language: AppLanguage): Int? {
    val id = ride?.id?.toIntOrNull()
    if (id == null) {
        Toast.makeText(
            context,
            if (language == AppLanguage.Ba)
                "Был — өлгө сәфәр. Ысын сәфәрҙәр бәйләнеш ҡайтҡас күренер."
            else
                "Это пример поездки. Настоящие появятся, когда вернётся связь.",
            Toast.LENGTH_LONG,
        ).show()
    }
    return id
}

/** Отказ «у тебя уже есть бронь» (409 с `booking_id`: другое число мест, остановка, подросток) — номер своей
 *  брони, чтобы открыть её, а не оставить человека с отказом (приёмка #189). */
internal fun existingBookingIdOf(e: Throwable): Int? =
    (e as? ApiException)?.takeIf { it.status == 409 }?.detail?.optInt("booking_id", 0)?.takeIf { it > 0 }
