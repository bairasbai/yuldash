package com.yuldash.app.data

import org.json.JSONObject

/** Снимок брони, сохранённый локально для показа без сети. */
data class TripPass(
    val bookingId: Int,
    val fromCity: String,
    val toCity: String,
    val departAt: String,      // ISO с сервера, форматируется на экране
    val driverName: String,
    val driverCar: String,
    // Госномер — по нему у машины и сверяют. Хранить локально ОБЯЗАТЕЛЬНО: сверяют ровно там,
    // где связи может не быть (перевал, ночная трасса), а без сети деталей брони не поднять.
    // Дефолт держит паспорта, сохранённые прошлой версией приложения.
    val driverPlate: String = "",
    val driverPhone: String,   // ПДн — только в secure-хранилище, не логировать
    val boardingCode: String,
    val pickup: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val price: Int,
    val seats: Int,
    val paymentNote: String,   // договорённость об оплате (если есть), иначе пусто
    val savedAt: Long,
    // Nullable defaults keep old snapshots honest: their payment method was never saved.
    val payMethod: String? = null,
    val payAmount: Int? = null,
    val pickupStop: String? = null, val dropoffStop: String? = null, val rideHasStops: Boolean = false,
    // RS-03 А: своя точная точка встречи — в паспорт пассажира (показать и офлайн); уезжает
    // вместе с паспортом при done/cancelled. Старые снимки — дефолты.
    val meetLat: Double? = null, val meetLng: Double? = null, val meetNote: String = "",
    val meetLandmarkRu: String = "", val meetLandmarkBa: String = "",
) {
    val effectivePayAmount: Int get() = payAmount ?: price

    fun toJson(): JSONObject = JSONObject().apply {
        put("booking_id", bookingId)
        put("from_city", fromCity)
        put("to_city", toCity)
        put("depart_at", departAt)
        put("driver_name", driverName)
        put("driver_car", driverCar)
        put("driver_plate", driverPlate)
        put("driver_phone", driverPhone)
        put("boarding_code", boardingCode)
        put("pickup", pickup)
        if (pickupLat != null) put("pickup_lat", pickupLat)
        if (pickupLng != null) put("pickup_lng", pickupLng)
        put("price", price)
        put("seats", seats)
        put("payment_note", paymentNote)
        put("saved_at", savedAt)
        put("pay_method", payMethod ?: JSONObject.NULL)
        put("pay_amount", payAmount ?: JSONObject.NULL)
        put("pickup_stop", pickupStop ?: JSONObject.NULL)
        put("dropoff_stop", dropoffStop ?: JSONObject.NULL)
        put("ride_has_stops", rideHasStops)
        if (meetLat != null) put("meet_lat", meetLat)
        if (meetLng != null) put("meet_lng", meetLng)
        put("meet_note", meetNote)
        put("meet_landmark_ru", meetLandmarkRu)
        put("meet_landmark_ba", meetLandmarkBa)
    }

    companion object {
        fun fromJson(o: JSONObject): TripPass = TripPass(
            bookingId = o.optInt("booking_id"),
            fromCity = o.optString("from_city"),
            toCity = o.optString("to_city"),
            departAt = o.optString("depart_at"),
            driverName = o.optString("driver_name"),
            driverCar = o.optString("driver_car"),
            driverPlate = o.optString("driver_plate"),
            driverPhone = o.optString("driver_phone"),
            boardingCode = o.optString("boarding_code"),
            pickup = o.optString("pickup"),
            pickupLat = if (o.isNull("pickup_lat")) null else o.optDouble("pickup_lat"),
            pickupLng = if (o.isNull("pickup_lng")) null else o.optDouble("pickup_lng"),
            price = o.optInt("price"),
            seats = o.optInt("seats", 1),
            paymentNote = o.optString("payment_note"),
            savedAt = o.optLong("saved_at"),
            payMethod = if (o.isNull("pay_method")) null else o.optString("pay_method"),
            payAmount = if (o.isNull("pay_amount")) null else o.optInt("pay_amount"),
            pickupStop = if (o.isNull("pickup_stop")) null else o.optString("pickup_stop"),
            dropoffStop = if (o.isNull("dropoff_stop")) null else o.optString("dropoff_stop"),
            rideHasStops = o.optBoolean("ride_has_stops"),
            meetLat = if (o.isNull("meet_lat")) null else o.optDouble("meet_lat"),
            meetLng = if (o.isNull("meet_lng")) null else o.optDouble("meet_lng"),
            meetNote = o.optString("meet_note"),
            meetLandmarkRu = o.optString("meet_landmark_ru"),
            meetLandmarkBa = o.optString("meet_landmark_ba"),
        )
    }
}
