package com.yuldash.app

// Гео-хелперы карты: город→точка, парс координат, азимут/дистанция (haversine), остаток ETA, дистанция городов.
// Вынесено из MapScreen.kt (Спринт 3 — разрезка гиганта). Чистые функции, без Compose и состояния экрана.
// Тот же пакет com.yuldash.app → вызовы из MapScreen (cityPoint(), remainingEtaSec() и т.д.) резолвятся без импортов.

import com.yandex.mapkit.geometry.Point

// Координаты для карты (Башкортостан). Старт — Баймаҡ, финиш — Сибай.
private val BaymakPoint = Point(52.5911, 58.3222)
private val SibayPoint = Point(52.7236, 58.6651)

// Город → точка: частые города Башкортостана мгновенно; прочие догружает геокодер (см. YandexMapCard).
internal fun cityPoint(city: String): Point? = when (city.trim().lowercase()) {
    "баймаҡ", "баймак" -> BaymakPoint
    "сибай" -> SibayPoint
    "темясово" -> Point(52.9686, 58.3206)
    "уфа" -> Point(54.7388, 55.9721)
    "учалы" -> Point(54.3050, 59.4040)
    "магнитогорск" -> Point(53.4072, 58.9794)
    "стерлитамак" -> Point(53.6303, 55.9311)
    "салават" -> Point(53.3617, 55.9244)
    "нефтекамск" -> Point(56.0911, 54.2486)
    "октябрьский" -> Point(54.4817, 53.4708)
    "белорецк" -> Point(53.9694, 58.4097)
    "ишимбай" -> Point(53.4528, 56.0386)
    "туймазы" -> Point(54.6014, 53.6947)
    "кумертау" -> Point(52.7639, 55.7964)
    else -> null
}

// "53.9306, 58.3142" → Point(lat, lon). Кривой формат → null (маршрут просто не построится, без краша).
internal fun parseMapPoint(raw: String): Point? {
    val parts = raw.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    return Point(lat, lon)
}

// Азимут (куда «носом» едет машинка) между двумя гео-точками, 0..360°.
internal fun bearingBetween(a: Point, b: Point): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val y = Math.sin(dLon) * Math.cos(lat2)
    val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}

// Расстояние между двумя гео-точками в метрах (haversine) — для движения с постоянной скоростью по длине маршрута.
internal fun geoMeters(a: Point, b: Point): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val la1 = Math.toRadians(a.latitude); val la2 = Math.toRadians(b.latitude)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
}

// Остаток времени (сек) от позиции pos до конца пути: ближайшая точка маршрута → доля непройденного × полное время.
// Одна функция для демо-симуляции и реальной поездки (живой ETA «осталось»).
internal fun remainingEtaSec(path: List<Point>, pos: Point, totalSec: Double): Int {
    if (path.size < 2 || totalSec <= 0) return 0
    var bestI = 0; var bestD = Double.MAX_VALUE
    for (i in path.indices) {
        val dLat = path[i].latitude - pos.latitude; val dLng = path[i].longitude - pos.longitude
        val d = dLat * dLat + dLng * dLng
        if (d < bestD) { bestD = d; bestI = i }
    }
    return Math.max(0.0, totalSec * (path.size - bestI).toDouble() / path.size).toInt()
}

// Дистанция между городами по координатам (для превью маршрута). null — если город неизвестен.
internal fun cityDistanceText(from: String, to: String): String? {
    val a = cityPoint(from) ?: return null
    val b = cityPoint(to) ?: return null
    val sLat = Math.sin(Math.toRadians(b.latitude - a.latitude) / 2)
    val sLon = Math.sin(Math.toRadians(b.longitude - a.longitude) / 2)
    val h = sLat * sLat + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude)) * sLon * sLon
    return "${Math.round(2 * 6371.0 * Math.asin(Math.sqrt(h)))} км"
}
