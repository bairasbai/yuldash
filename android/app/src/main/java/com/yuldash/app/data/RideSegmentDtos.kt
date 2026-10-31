package com.yuldash.app.data

import org.json.JSONObject

/** Цена до одной остановки в целых рублях (RS-02 Б, D7). */
data class StopPriceDto(val stop: String, val price: Int)

/** Подсказка по одной остановке маршрута (RS-02 Б): расстояние от предыдущей точки и цены прошлых поездок до неё. */
data class StopHintDto(
    val stop: String,
    val distanceKm: Double? = null,
    val avg: Int = 0,
    val count: Int = 0,
)

/** RS-02 А/Б: разобранные сегментные поля карточки поездки (места по участкам, цены, версия условий). */
internal class SegmentExtras(
    val termsVersion: Long,
    val seatsByLeg: List<Int>,
    val seatsOnSegment: Int?,
    val seatsBooked: Int?,
    val stopPrices: List<StopPriceDto>,
    val segmentPrice: Int?,
)

/** RS-02 А/Б: разбор полей сегментных мест и цен из JSON карточки поездки. Вызывается из toRideDto. */
internal fun JSONObject.toSegmentExtras() = SegmentExtras(
    termsVersion = optLong("terms_version"),
    seatsByLeg = optJSONArray("seats_by_leg")?.let { arr -> (0 until arr.length()).map { arr.optInt(it) } } ?: emptyList(),
    seatsOnSegment = if (isNull("seats_on_segment")) null else optInt("seats_on_segment"),
    seatsBooked = if (isNull("seats_booked")) null else optInt("seats_booked"),
    stopPrices = optJSONArray("stop_prices")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { StopPriceDto(stop = it.optString("stop"), price = it.optInt("price")) }
        }
    } ?: emptyList(),
    segmentPrice = if (isNull("segment_price")) null else optInt("segment_price"),
)

/** RS-02 Б: разбор подсказок по остановкам из ответа price_hint. */
internal fun JSONObject.toStopHints(): List<StopHintDto> =
    optJSONArray("stops")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { s ->
                StopHintDto(stop = s.optString("stop"),
                            distanceKm = if (s.isNull("distance_km")) null else s.optDouble("distance_km"),
                            avg = s.optInt("avg"), count = s.optInt("count"))
            }
        }
    } ?: emptyList()
