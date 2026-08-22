package com.yuldash.app

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Темно ли сейчас на улице в этой точке.
 *
 * Нужно ночной поездке: приложение приглушает экран и карту после заката. По часам это делать
 * нельзя — Башкортостан не Москва: в июне светло до одиннадцати вечера, в декабре темнеет
 * в половине пятого. «После 21:00» включало бы ночь на светлой улице и не включало бы
 * в декабрьские сумерки, то есть ровно тогда, когда белый экран бьёт по глазам сильнее всего.
 *
 * Считаем высоту солнца над горизонтом. Порог — гражданские сумерки (−6°): это момент, когда
 * без фонаря уже не почитать, и как раз тогда светящийся телефон начинает слепить.
 *
 * Точность формулы — минуты. Для «пора ли гасить экран» этого с запасом; астрономии тут не надо.
 */
internal object SunTime {

    /** Ниже этой высоты солнца считаем, что стемнело. −6° = конец гражданских сумерек. */
    private const val CIVIL_TWILIGHT_DEG = -6.0

    /**
     * Высота солнца над горизонтом в градусах. Отрицательная — солнце за горизонтом.
     *
     * @param lat широта точки
     * @param lng долгота точки
     * @param nowMillis момент времени (UTC-миллисекунды)
     */
    fun sunAltitudeDeg(lat: Double, lng: Double, nowMillis: Long): Double {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val dayOfYear = utc.get(Calendar.DAY_OF_YEAR)
        val hourUtc = utc.get(Calendar.HOUR_OF_DAY) +
            utc.get(Calendar.MINUTE) / 60.0 +
            utc.get(Calendar.SECOND) / 3600.0

        // Склонение солнца — насколько оно смещено от экватора в этот день года.
        // Отсюда берётся вся разница между июньской белой ночью и декабрьскими сумерками.
        val decl = 23.45 * sin(Math.toRadians(360.0 / 365.0 * (dayOfYear - 81)))

        // Уравнение времени: солнечные сутки не ровно 24 часа, за год набегает до ±16 минут.
        val b = Math.toRadians(360.0 / 365.0 * (dayOfYear - 81))
        val eqTimeMin = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)

        // Истинное солнечное время в этой точке и часовой угол от полудня.
        val solarHour = hourUtc + lng / 15.0 + eqTimeMin / 60.0
        val hourAngle = Math.toRadians((solarHour - 12.0) * 15.0)

        val latRad = Math.toRadians(lat)
        val declRad = Math.toRadians(decl)
        val sinAlt = sin(latRad) * sin(declRad) + cos(latRad) * cos(declRad) * cos(hourAngle)
        return Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))
    }

    /** Стемнело ли настолько, что светлый экран уже бьёт по глазам. */
    fun isDark(lat: Double, lng: Double, nowMillis: Long): Boolean =
        sunAltitudeDeg(lat, lng, nowMillis) < CIVIL_TWILIGHT_DEG

    /**
     * Полярный день/ночь и прочие края формулу не ломают, но проверить это стоит явно:
     * для широт Башкортостана (52–56°) солнце всегда и восходит, и заходит.
     * Возвращает false, если в этой точке в этот день солнце не заходит вовсе.
     */
    fun sunSetsToday(lat: Double, dayOfYear: Int): Boolean {
        val decl = 23.45 * sin(Math.toRadians(360.0 / 365.0 * (dayOfYear - 81)))
        val cosH = -Math.tan(Math.toRadians(lat)) * Math.tan(Math.toRadians(decl))
        if (cosH !in -1.0..1.0) return false
        acos(cosH)   // угол существует — значит закат сегодня есть
        return true
    }
}
