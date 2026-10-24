package com.yuldash.app

// Темп отправки точки поездки (вынесено из TripLocationService.kt, Д2 24.10.2026: служба выросла режимом рейса).

/**
 * Когда отправлять свою точку. Чистая логика: часы и координаты приходят снаружи — её гоняют
 * обычные JVM-тесты, без эмулятора (`TripLocationPacerTest`).
 *
 *  • Едем — как и раньше: не чаще раза в [minIntervalMs] и только если сдвинулись на [minDistanceM]
 *    от ПОСЛЕДНЕЙ ОТПРАВЛЕННОЙ точки (дрожание GPS на стоянке за езду не считаем).
 *  • Стоим — всё равно раз в [heartbeatMs] (только водитель; пассажиру — [TripLocationService.NO_HEARTBEAT_MS]).
 *    Сервер держит машину для ссылки близким и для SOS 120 с; без пульса стоящая машина пропадала
 *    через 2 минуты — ровно когда «встала в поле» и близким она нужнее всего (проверка безопасности PR170).
 *  • Отдельного таймера нет: пульс уходит вместе с точкой, которую отдал Android. Сами мы старую
 *    точку не повторяем. Но, по нашему пониманию (на телефоне не проверено), Android 12+ в глубоком
 *    сне (Doze) может сам отдавать последнюю известную точку с новым временем — при пороге 0 м
 *    именно она и держит пульс, и сервер примет её за свежую. Таймер `Handler` в глубоком сне
 *    процессора опаздывает, а доставка точки будит процесс сама.
 *  • Нет точки совсем (крытая парковка, навес АЗС, GPS не видит неба; гео выключили) — пульса нет,
 *    и через 120 с машина у близких пропадает. Это осознанно: выдумывать место мы не будем.
 *  • Ушедшей считается только точка, которую принял сокет ([sent]): не принял — следующая точка
 *    (через ~3 с) повторит попытку, а не ждёт ещё 45 с. Канал переподключился — сервис зовёт
 *    [reset], и следующая точка уходит сразу.
 */
internal class TripLocationPacer(
    private val minIntervalMs: Long = TripLocationService.MIN_INTERVAL_MS,
    private val minDistanceM: Double = TripLocationService.MIN_DISTANCE_M,
    private val heartbeatMs: Long = TripLocationService.HEARTBEAT_MS,
) {
    private var hasSent = false
    private var sentAtMs = 0L
    private var sentLat = 0.0
    private var sentLng = 0.0

    /** Пора ли отправить эту точку. Ничего не запоминает — запоминает [sent]. */
    fun due(nowMs: Long, lat: Double, lng: Double): Boolean {
        if (!hasSent) return true
        val sinceMs = nowMs - sentAtMs
        if (sinceMs < minIntervalMs) return false
        return sinceMs >= heartbeatMs || metersBetween(sentLat, sentLng, lat, lng) >= minDistanceM
    }

    /** Точка ушла в канал — от неё считаем и паузу, и расстояние. */
    fun sent(nowMs: Long, lat: Double, lng: Double) {
        hasSent = true
        sentAtMs = nowMs
        sentLat = lat
        sentLng = lng
    }

    /** Стоп или (пере)подключение канала: следующая точка уходит сразу. */
    fun reset() {
        hasSent = false
    }

    private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * 6_371_000.0 * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }
}
