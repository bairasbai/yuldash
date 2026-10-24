package com.yuldash.app.data

import org.json.JSONObject

// Приватные ответы брони: место посадки не берём из публичной поездки.

/** Моя бронь со сводкой поездки — для экрана «Мои поездки». */
data class BookingMineDto(
    val id: Int,
    val rideId: Int,
    val seats: Int,
    val price: Int,
    val status: String,
    val boardingCode: String,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val driverName: String,
    val driverVerified: Boolean,
    val pickupStop: String? = null, val dropoffStop: String? = null, val rideHasStops: Boolean = false,
    val cancelledInTransit: Boolean = false,   // отменили, когда пассажир был в машине (23.10.2026); старый сервер — false
)

/** Приватные детали брони для вкладки «Детали поездки». */
data class BookingDetailsDto(
    val bookingId: Int,
    val rideId: Int,
    val role: String,
    val status: String,
    val contactUnlocked: Boolean,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val seats: Int,
    val price: Int,
    val payMethod: String = "negotiate",   // договорённость об оплате (запись, не платёж): cash/sbp/negotiate
    val payAmount: Int? = null,            // сумма договорённости, ₽ (опц.)
    val driverName: String,
    val passengerName: String = "",   // кого везёт водитель — шапка его экрана поездки (приёмка 18.10, п.14)
    val declined: Boolean = false,    // водитель отклонил неподтверждённую бронь — «Водитель отклонил бронь» (п.6)
    val passengerPhone: String = "",  // водителю после подтверждения (решение 23.10.2026); иначе пусто
    val driverVerified: Boolean,
    val driverPhone: String,
    val driverCar: String,
    // Госномер и цвет — чтобы сверить машину у подъезда. Пусто до подтверждения брони.
    // Дефолты держат старый сервер: клиент новее бэкенда не падает, просто не показывает.
    val driverPlate: String = "",
    val driverCarColor: String = "",
    // Несовершеннолетний пассажир: пометку видят оба, контакты взрослого — только водитель.
    val minorPassenger: Boolean = false,
    val minorGuardianName: String = "",
    val minorGuardianPhone: String = "",
    val frontSeat: Boolean = false,   // пассажир просил переднее место
    val pickup: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val fromLat: Double?,
    val fromLng: Double?,
    val toLat: Double?,
    val toLng: Double?,
    val pickupStop: String? = null, val dropoffStop: String? = null, val rideHasStops: Boolean = false,
    // Отменили, когда пассажир был в машине (23.10.2026): машина и госномер остаются, телефона нет.
    val cancelledInTransit: Boolean = false,
)


/** Моя бронь в списке поездок. */
internal fun parseBookingMineDto(o: JSONObject): BookingMineDto {
    return BookingMineDto(
        id = o.optInt("id"),
        rideId = o.optInt("ride_id"),
        seats = o.optInt("seats", 1),
        price = o.optInt("price"),
        status = o.optString("status"),
        boardingCode = o.optString("boarding_code"),
        fromCity = o.optString("from_city"),
        toCity = o.optString("to_city"),
        departAt = o.optString("depart_at"),
        driverName = o.optString("driver_name"),
        driverVerified = o.optBoolean("driver_verified"),
        dropoffStop = if (o.isNull("dropoff_stop")) null else o.optString("dropoff_stop"),
        pickupStop = if (o.isNull("pickup_stop")) null else o.optString("pickup_stop"), rideHasStops = o.optBoolean("ride_has_stops"),
        cancelledInTransit = o.optBoolean("cancelled_in_transit", false),
    )
}


/** Полная карточка брони. */
internal fun parseBookingDetailsDto(o: JSONObject): BookingDetailsDto {
    return BookingDetailsDto(
        bookingId = o.optInt("booking_id"),
        rideId = o.optInt("ride_id"),
        role = o.optString("role"),
        status = o.optString("status"),
        contactUnlocked = o.optBoolean("contact_unlocked"),
        fromCity = o.optString("from_city"),
        toCity = o.optString("to_city"),
        departAt = o.optString("depart_at"),
        seats = o.optInt("seats", 1),
        price = o.optInt("price"),
        payMethod = o.optString("pay_method", "negotiate"),
        payAmount = if (o.isNull("pay_amount")) null else o.optInt("pay_amount"),
        driverName = o.optString("driver_name"),
        passengerName = o.optString("passenger_name"), declined = o.optBoolean("declined"),
        passengerPhone = o.optString("passenger_phone"),
        driverVerified = o.optBoolean("driver_verified"),
        driverPhone = o.optString("driver_phone"),
        driverCar = o.optString("driver_car"),
        driverPlate = o.optString("driver_plate"),
        driverCarColor = o.optString("driver_car_color"),
        minorPassenger = o.optBoolean("minor_passenger"),
        minorGuardianName = o.optString("minor_guardian_name"),
        minorGuardianPhone = o.optString("minor_guardian_phone"),
        frontSeat = o.optBoolean("front_seat"),
        pickup = o.optString("pickup"),
        pickupLat = if (o.isNull("pickup_lat")) null else o.optDouble("pickup_lat"),
        pickupLng = if (o.isNull("pickup_lng")) null else o.optDouble("pickup_lng"),
        fromLat = if (o.isNull("from_lat")) null else o.optDouble("from_lat"),
        fromLng = if (o.isNull("from_lng")) null else o.optDouble("from_lng"),
        toLat = if (o.isNull("to_lat")) null else o.optDouble("to_lat"),
        toLng = if (o.isNull("to_lng")) null else o.optDouble("to_lng"),
        dropoffStop = if (o.isNull("dropoff_stop")) null else o.optString("dropoff_stop"),
        pickupStop = if (o.isNull("pickup_stop")) null else o.optString("pickup_stop"), rideHasStops = o.optBoolean("ride_has_stops"),
        cancelledInTransit = o.optBoolean("cancelled_in_transit", false),
    )
}
