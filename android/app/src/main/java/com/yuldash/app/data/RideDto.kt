package com.yuldash.app.data

import org.json.JSONObject

data class RideDto(
    val id: Int,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val seatsTotal: Int,
    val seatsLeft: Int,
    val price: Int,
    val category: String,
    val driverId: Int = 0,            // id водителя → публичный профиль
    val driverName: String,
    val driverRating: Double,
    val driverVerified: Boolean,
    val driverCar: String,
    val driverAvatar: String = "",
    val driverOnline: Boolean = false,
    val driverTrips: Int = 0,         // F8: завершённых поездок водителя (бейдж «N поездок»)
    val driverSince: String = "",     // F8: месяц регистрации "YYYY-MM" (бейдж «С нами с …»)
    val driverIsWoman: Boolean = false,   // F9: водитель — женщина (opt-in сигнал для бейджа)
    val petsAllowed: Boolean = false,
    val childSeat: Boolean = false,
    val womenOnly: Boolean = false,
    val smoking: Boolean = false,
    val baggage: Boolean = false,
    val airConditioner: Boolean = false,
    val quiet: Boolean = false,
    val noMinors: Boolean = false,   // водитель не берёт младше 18 без взрослого
    val backMaxTwo: Boolean = false, val frontSeatAvailable: Boolean = true, val frontSeatTaken: Boolean = false,   // опции мест
    val waypoints: List<String> = emptyList(),
    val matchedStop: String? = null, val matchedFromStart: Boolean = false,
    val matchedDropoffStop: String? = null, val matchedToEnd: Boolean = false,
    val pickup: String = "",          // где водитель забирает (точка сбора)
    val pickupLat: Double? = null,    // координаты точки сбора (пин на карте)
    val pickupLng: Double? = null,
    val distanceKm: Double? = null,   // дистанция клиент→точка выезда (только из /rides/near с координатами)
    val boosted: Boolean = false,     // активный Boost (подсветка/бейдж)
    val receiverName: String = "",    // посылка: кому отдать
    val parcelSize: String = "",      // посылка: габарит/вес
    val status: String = "active",    // active / done / cancelled (для раздела «Архив»)
    val partnerId: Int? = null,       // F22: клиника-назначение (для category=hospital)
    // RS-02 А/Б: версия условий, места по участкам и на участке запроса, цены остановок,
    // цена участка в рублях; seatsBooked — только свои рейсы водителя.
    val termsVersion: Long = 0,
    val seatsByLeg: List<Int> = emptyList(),
    val seatsOnSegment: Int? = null,
    val seatsBooked: Int? = null,
    val stopPrices: List<StopPriceDto> = emptyList(),
    val segmentPrice: Int? = null,
    val driverPayMethods: List<String> = emptyList(),
    val cardPayment: Boolean = false,
)

/** JSON поездки с сервера → RideDto. Один шов вместо копипасты в getRides/getNearbyRides.
 *  distance_km нет в /rides → isNull(...) = null; есть в /rides/near → читаем.
 *  RS-02 А/Б: сегментные места и цены разбирает data/RideSegmentDtos.kt. */
internal fun JSONObject.toRideDto(): RideDto {
    val seg = toSegmentExtras()
    return RideDto(
    termsVersion = seg.termsVersion, seatsByLeg = seg.seatsByLeg, seatsOnSegment = seg.seatsOnSegment,
    seatsBooked = seg.seatsBooked, stopPrices = seg.stopPrices, segmentPrice = seg.segmentPrice,
    id = optInt("id"),
    fromCity = optString("from_city"),
    toCity = optString("to_city"),
    departAt = optString("depart_at"),
    seatsTotal = optInt("seats_total"),
    seatsLeft = optInt("seats_left"),
    price = optInt("price"),
    category = optString("category"),
    driverId = optInt("driver_id"),
    driverName = optString("driver_name"),
    driverRating = if (has("driver_rating_count") && optInt("driver_rating_count") <= 0) 0.0
        else optDouble("driver_rating", 0.0),
    driverVerified = optBoolean("driver_verified"),
    driverCar = optString("driver_car"),
    driverAvatar = optString("driver_avatar"),
    driverOnline = optBoolean("driver_online"),
    driverTrips = optInt("driver_trips"),
    driverSince = optString("driver_since"),
    driverIsWoman = optBoolean("driver_is_woman"),
    driverPayMethods = driverPayMethodList(optJSONArray("driver_pay_methods")),
    cardPayment = optBoolean("card_payment", false),
    petsAllowed = optBoolean("pets_allowed"),
    childSeat = optBoolean("child_seat"),
    womenOnly = optBoolean("women_only"),
    smoking = optBoolean("smoking"),
    baggage = optBoolean("baggage"),
    airConditioner = optBoolean("air_conditioner"),
    quiet = optBoolean("quiet"),
    noMinors = optBoolean("no_minors"),
    // Старый сервер полей не знает — переднее тогда свободно, как было всегда.
    backMaxTwo = optBoolean("back_max_two"), frontSeatAvailable = optBoolean("front_seat_available", true), frontSeatTaken = optBoolean("front_seat_taken"),
    waypoints = optString("waypoints").split(" | ").map { it.trim() }.filter { it.isNotBlank() },
    matchedStop = if (isNull("matched_stop")) null else optString("matched_stop").ifBlank { null }, matchedFromStart = optBoolean("matched_from_start"),
    matchedDropoffStop = if (isNull("matched_dropoff_stop")) null else optString("matched_dropoff_stop").ifBlank { null }, matchedToEnd = optBoolean("matched_to_end"),
    pickup = optString("pickup"),
    pickupLat = if (isNull("pickup_lat")) null else optDouble("pickup_lat"),
    pickupLng = if (isNull("pickup_lng")) null else optDouble("pickup_lng"),
    distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
    boosted = optBoolean("boosted"),
    receiverName = optString("receiver_name"),
    parcelSize = optString("parcel_size"),
    status = optString("status", "active"),
    partnerId = if (isNull("partner_id")) null else optInt("partner_id"),
)
}
