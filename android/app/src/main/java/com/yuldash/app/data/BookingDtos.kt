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
    val declined: Boolean = false,
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
    // RS-03 А: точное место встречи/высадки. Пассажир видит своё; водитель — при confirmed/onboard;
    // иначе null/"" — «место указано» без координат. Старый сервер полей не шлёт — дефолты.
    val meetLat: Double? = null, val meetLng: Double? = null, val meetNote: String = "",
    val meetLandmarkRu: String = "", val meetLandmarkBa: String = "",
    val alightLat: Double? = null, val alightLng: Double? = null, val alightNote: String = "",
    val alightLandmarkRu: String = "", val alightLandmarkBa: String = "",
    val meetingVersion: Int = 0, val meetingProposal: MeetingProposalDto? = null,
    val meetLandmarkId: Int? = null, val alightLandmarkId: Int? = null,
    val boardingUndo: BoardingUndoTerms = BoardingUndoTerms(),
    val canRate: Boolean = false,
    val myStars: Int = 0,
    val traveler: PrivateBookingTraveler? = null,
)

/** RS-03 Б: активное предложение смены места (публичные поля — без автора). */
data class MeetingProposalDto(
    val proposalId: String, val kind: String, val createdAt: String,
    val lat: Double?, val lng: Double?, val note: String, val landmarkId: Int?, val clear: Boolean,
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
        declined = o.optBoolean("declined"),
        cancelledInTransit = o.optBoolean("cancelled_in_transit", false),
    )
}


/** Полная карточка брони. */
internal fun parseBookingDetailsDto(o: JSONObject, startedElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): BookingDetailsDto {
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
        traveler = parsePrivateBookingTraveler(o, startedElapsedMs),
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
        meetLat = if (o.isNull("meet_lat")) null else o.optDouble("meet_lat"),
        meetLng = if (o.isNull("meet_lng")) null else o.optDouble("meet_lng"),
        meetNote = o.optString("meet_note"),
        meetLandmarkRu = o.optString("meet_landmark_ru"), meetLandmarkBa = o.optString("meet_landmark_ba"),
        meetLandmarkId = if (o.isNull("meet_landmark_id")) null else o.optInt("meet_landmark_id"),
        alightLat = if (o.isNull("alight_lat")) null else o.optDouble("alight_lat"),
        alightLng = if (o.isNull("alight_lng")) null else o.optDouble("alight_lng"),
        alightNote = o.optString("alight_note"),
        alightLandmarkRu = o.optString("alight_landmark_ru"), alightLandmarkBa = o.optString("alight_landmark_ba"),
        alightLandmarkId = if (o.isNull("alight_landmark_id")) null else o.optInt("alight_landmark_id"),
        meetingVersion = o.optInt("meeting_version", 0),
        meetingProposal = parseMeetingProposal(o.optJSONObject("meeting_proposal")),
        boardingUndo = parseBoardingUndo(o),
        canRate = o.optBoolean("can_rate", false),
        myStars = o.optInt("my_stars", 0),
    )
}

fun parseMeetingProposal(o: JSONObject?): MeetingProposalDto? {
    if (o == null) return null
    val point = o.optJSONObject("point")
    val id = o.optString("proposal_id")
    if (id.isBlank()) return null
    return MeetingProposalDto(
        proposalId = id, kind = o.optString("kind"), createdAt = o.optString("created_at"),
        lat = if (point == null || point.isNull("lat")) null else point.optDouble("lat"),
        lng = if (point == null || point.isNull("lng")) null else point.optDouble("lng"),
        note = point?.optString("note") ?: "",
        landmarkId = if (point == null || point.isNull("landmark_id")) null else point.optInt("landmark_id"),
        clear = point?.optBoolean("clear") ?: false,
    )
}
