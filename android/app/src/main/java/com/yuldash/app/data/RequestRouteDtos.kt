package com.yuldash.app.data

import org.json.JSONObject

data class RequestFeedDto(val id: Int, val passengerName: String, val from: String, val to: String, val seats: Int, val comment: String, val responded: Boolean, val passengerAvatar: String = "", val prefs: List<String> = emptyList(), val myResponseId: Int? = null,
    // На сколько км заявка уводит с собственного маршрута. null = у водителя нет активных
    // поездок или где-то нет координат — тогда числа не показываем, а не выдумываем.
    val detourKm: Int? = null,
    val desiredAt: String = "",
    val maxPrice: Int? = null,
    val distanceKm: Double? = null,
    val category: String = "regular",
    val passengerRating: Double? = null,
    val passengerRatingCount: Int = 0,
    val passengerVerified: Boolean = false,
    val matchedRideId: Int? = null, val matchedRideFromCity: String = "", val matchedRideToCity: String = "",
    val matchedRideDepartAt: String = "", val matchedPickupStop: String? = null, val matchedDropoffStop: String? = null,
)
data class ResponseDto(
    val id: Int, val driverId: Int, val driverName: String, val driverRating: Double?,
    val price: Int,                    // первая цена водителя (историческая)
    val comment: String, val status: String, val driverAvatar: String = "",
    // --- Торг о цене (второй круг). Раньше отклик был «бери или уходи». ---
    val currentPrice: Int = 0,         // цена, которая сейчас НА СТОЛЕ
    val lastOfferBy: String = "driver",// чей ход был последним: driver | passenger
    val bargainRounds: Int = 0,        // сколько встречных сделано
    val canCounter: Boolean = false,   // я могу предложить свою цену
    val canAccept: Boolean = false,    // я могу принять то, что на столе
    val bargainHistory: String = "",   // «d:500,p:400,d:450»
    // Публичное доверие + контекст заявки для премиального экрана откликов.
    // Дефолты сохраняют совместимость со старым сервером и существующими тестами.
    val driverVerified: Boolean = false,
    val driverTripsCount: Int = 0,
    val driverCar: String = "",
    val requestFromCity: String = "",
    val requestToCity: String = "",
    val requestSeats: Int = 0,
    val requestMaxPrice: Int = 0,
    val requestDesiredAt: String? = null,
    val rideId: Int? = null, val rideFromCity: String = "", val rideToCity: String = "", val rideDepartAt: String = "",
    val pickupStop: String? = null, val dropoffStop: String? = null,
) {
    /** Что показывать как цену: пока торга не было — первое предложение водителя. */
    val onTable: Int get() = if (currentPrice > 0) currentPrice else price
    val haggled: Boolean get() = bargainRounds > 0
}

/** Заявка в ленте водителя. */
internal fun parseRequestFeedDto(o: JSONObject): RequestFeedDto {
    val pa = o.optJSONArray("prefs")
    val prefs = if (pa != null) (0 until pa.length()).map { pa.optString(it) } else emptyList()
    return RequestFeedDto(
        id = o.optInt("id"), passengerName = o.optString("passenger_name"),
        from = o.optString("from_city"), to = o.optString("to_city"),
        seats = o.optInt("seats"), comment = o.optString("comment"),
        responded = o.optBoolean("responded"), passengerAvatar = o.optString("passenger_avatar"),
        prefs = prefs, myResponseId = if (o.isNull("my_response_id")) null else o.optInt("my_response_id"),
        detourKm = if (o.isNull("detour_km")) null else o.optInt("detour_km"),
        desiredAt = o.optString("desired_at"),
        maxPrice = if (o.isNull("max_price")) null else o.optInt("max_price"),
        distanceKm = if (o.isNull("distance_km")) null else o.optDouble("distance_km"),
        category = o.optString("category", "regular"),
        passengerRating = if (o.isNull("passenger_rating")) null else o.optDouble("passenger_rating"),
        passengerRatingCount = o.optInt("passenger_rating_count"),
        passengerVerified = o.optBoolean("passenger_verified"),
        matchedRideId = if (o.isNull("matched_ride_id")) null else o.optInt("matched_ride_id").takeIf { it > 0 },
        matchedRideFromCity = o.optString("matched_ride_from_city"), matchedRideToCity = o.optString("matched_ride_to_city"),
        matchedRideDepartAt = o.optString("matched_ride_depart_at"),
        matchedPickupStop = if (o.isNull("matched_pickup_stop")) null else o.optString("matched_pickup_stop"),
        matchedDropoffStop = if (o.isNull("matched_dropoff_stop")) null else o.optString("matched_dropoff_stop"),
    )
}

internal fun JSONObject.toResponseDto() = ResponseDto(
    id = optInt("id"), driverId = optInt("driver_id"), driverName = optString("driver_name"),
    driverRating = if (isNull("driver_rating")) null else optDouble("driver_rating"),
    price = optInt("price"), comment = optString("comment"), status = optString("status"),
    driverAvatar = optString("driver_avatar"),
    currentPrice = optInt("current_price"),
    lastOfferBy = optString("last_offer_by").ifBlank { "driver" },
    bargainRounds = optInt("bargain_rounds"),
    canCounter = optBoolean("can_counter"), canAccept = optBoolean("can_accept"),
    bargainHistory = optString("bargain_history"),
    driverVerified = optBoolean("driver_verified"),
    driverTripsCount = optInt("driver_trips_count"),
    driverCar = optString("driver_car"),
    requestFromCity = optString("request_from_city"),
    requestToCity = optString("request_to_city"),
    requestSeats = optInt("request_seats"),
    requestMaxPrice = optInt("request_max_price"),
    requestDesiredAt = optString("request_desired_at").ifBlank { null },
    rideId = if (isNull("ride_id")) null else optInt("ride_id").takeIf { it > 0 },
    rideFromCity = optString("ride_from_city"), rideToCity = optString("ride_to_city"), rideDepartAt = optString("ride_depart_at"),
    pickupStop = if (isNull("pickup_stop")) null else optString("pickup_stop"),
    dropoffStop = if (isNull("dropoff_stop")) null else optString("dropoff_stop"),
)

internal fun parseResponseDto(o: JSONObject): ResponseDto = o.toResponseDto()
