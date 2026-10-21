package com.yuldash.app

import android.content.Context
import android.widget.Toast

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
