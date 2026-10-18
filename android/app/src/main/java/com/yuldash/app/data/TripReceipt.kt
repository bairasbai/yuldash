package com.yuldash.app.data

import org.json.JSONObject

/** Квитанция завершённой поездки (GET /trips/{booking_id}/receipt).
 *  amount — ₽; pay_method: cash|sbp|negotiate; role — «passenger»|«driver» (чья витрина). */
data class TripReceiptDto(
    val bookingId: Int, val rideId: Int, val role: String,
    val fromCity: String, val toCity: String, val departAt: String,
    val seats: Int, val amount: Int, val payMethod: String, val paid: Boolean,
    val driverName: String, val driverVerified: Boolean,
    val counterpartyName: String = "",
    val myStars: Int = 0,
    val myRatingTags: String = "", val counterpartyId: Int = 0, val problemReportSec: Int = 0,  // «Сообщить о проблеме» пассажиру после поездки (PR174)
    val pickupStop: String? = null, val dropoffStop: String? = null,
)

/** Keep raw null / empty / published stop values distinct; legacy is never guessed. */
internal fun parseTripReceiptDto(o: JSONObject): TripReceiptDto = TripReceiptDto(
    bookingId = o.optInt("booking_id"),
    rideId = o.optInt("ride_id"),
    role = o.optString("role"),
    fromCity = o.optString("from_city"),
    toCity = o.optString("to_city"),
    departAt = o.optString("depart_at"),
    seats = o.optInt("seats"),
    amount = o.optInt("amount"),
    payMethod = o.optString("pay_method"),
    paid = o.optBoolean("paid"),
    driverName = o.optString("driver_name"),
    driverVerified = o.optBoolean("driver_verified"),
    counterpartyName = o.optString("counterparty_name"),
    myStars = o.optInt("my_stars"),
    myRatingTags = o.optString("my_rating_tags"), counterpartyId = o.optInt("counterparty_id"), problemReportSec = o.optInt("problem_report_sec"),
    pickupStop = if (o.isNull("pickup_stop")) null else o.optString("pickup_stop"),
    dropoffStop = if (o.isNull("dropoff_stop")) null else o.optString("dropoff_stop"),
)
