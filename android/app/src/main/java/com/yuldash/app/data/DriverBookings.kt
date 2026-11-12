package com.yuldash.app.data

import org.json.JSONObject

// Брони на поездки водителя (`GET /driver/bookings`): кабинет «Едут с тобой» и «Пассажиры — оцените».
// Вынесено из ApiClient.kt (храповик размера файлов, 18.10.2026): это домен брони, а не транспорт.
// Сроки «Не пришёл» и «Подъезжаю» по брони — в BoardingApi.kt ([NoShowTerms]).

/** Бронь на поездку водителя — для оценки пассажира. */
data class DriverBookingDto(
    val bookingId: Int,
    val passengerName: String,
    val passengerRating: Double?,
    val route: String,
    val status: String,
    // Сколько звёзд водитель уже поставил по этой брони: 0 = ещё не оценивал.
    // Без этого после перезагрузки экрана звёзды снова были пустые, и человек оценивал повторно.
    val myStars: Int = 0,
    val frontSeat: Boolean = false,   // просит переднее место — водитель видит до решения
    val passengerId: Int = 0,   // вторая сторона для «Сообщить о проблеме» (разбор по брони «в пути»); 0 — старый сервер
    val boardingUndoSec: Int = 0,   // сколько секунд ещё можно отменить свою отметку «Сел»; 0 — нельзя / старый сервер
    val noShowAt: String? = null,   // ISO UTC: с этого момента доступна «Не пришёл» (поздний из выезда и «Подъезжаю» + ожидание, считает сервер); null — срока нет / старый сервер, решает сервер
    val noShowNeedsArriving: Boolean = false,   // поездка с остановками ждёт «Подъезжаю» по этой брони: вместо «Не пришёл» — «Подъезжаю» (18.10.2026)
    val arrivingOpensAt: String? = null,   // ISO UTC: с этого момента можно нажать «Подъезжаю» (за 30 минут до выезда, считает сервер); null — старый сервер
    val pickupStop: String? = null, val dropoffStop: String? = null,   // null — не указано/закрыто; "" — начало; непусто — остановка
    val rideHasStops: Boolean = false, val fromCity: String = "", val toCity: String = "",
    val rideId: Int = 0,   // своя поездка брони: «Снять поездку» считает, кто из её пассажиров в машине; 0 — старый сервер
    // RS-02 А: сколько мест занял пассажир и за сколько (кабинет «кто где садится и за сколько»).
    val seats: Int = 0,
    val price: Int = 0,
    // RS-03 А: точное место встречи/высадки пассажира. Открыто при confirmed/onboard; иначе
    // null/"" + meetingPointSet «указано — откроется после подтверждения». Старый сервер — дефолты.
    val meetLat: Double? = null, val meetLng: Double? = null, val meetNote: String = "",
    val meetLandmarkRu: String = "", val meetLandmarkBa: String = "",
    val alightLat: Double? = null, val alightLng: Double? = null, val alightNote: String = "",
    val alightLandmarkRu: String = "", val alightLandmarkBa: String = "",
    val meetingPointSet: Boolean = false, val meetingProposalPending: Boolean = false,
    // RS-03 Б: активное предложение смены места (водителю при confirmed/onboard) + версия для CAS.
    val meetingProposal: MeetingProposalDto? = null, val meetingVersion: Int = 0,
    val boardingUndo: BoardingUndoTerms = BoardingUndoTerms(),
    val noShowWaitSec: Int = 0,
    val boardingUndoDeadlineElapsedMs: Long? = null,
)

internal fun DriverBookingDto.observeBoardingUndo(nowElapsedMs: Long, previous: DriverBookingDto?): DriverBookingDto {
    if (status != "onboard") return copy(boardingUndoDeadlineElapsedMs = null)
    val deadline = nowElapsedMs + boardingUndoSec * 1_000L
    val previousDeadline = previous?.boardingUndoDeadlineElapsedMs
    val sameBoarding = sameBoardingTimestamp(boardingUndo.onboardAt, previous?.boardingUndo?.onboardAt)
    return copy(boardingUndoDeadlineElapsedMs = if (sameBoarding && previousDeadline != null) minOf(deadline, previousDeadline) else deadline)
}

internal fun DriverBookingDto.boardingUndoSecondsAt(nowElapsedMs: Long): Int {
    val deadline = boardingUndoDeadlineElapsedMs ?: return boardingUndoSec
    return boardingUndoSecondsUntil(deadline, nowElapsedMs)
}

internal fun boardingUndoSecondsUntil(deadlineElapsedMs: Long, nowElapsedMs: Long): Int =
    ((deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0) + 999L).div(1_000L).toInt()

internal fun DriverBookingDto.applyBoardingResult(result: BoardingResult, nowElapsedMs: Long): DriverBookingDto {
    if (status !in setOf("confirmed", "onboard") || result.status !in setOf("confirmed", "onboard")) return this
    val knownBoarding = boardingUndo.onboardAt
    val resultBoarding = result.undo.onboardAt ?: result.undo.sourceOnboardAt
    if (knownBoarding != null && !sameBoardingTimestamp(knownBoarding, resultBoarding)) return this
    return copy(status = result.status, boardingUndoSec = result.undoSec, boardingUndo = result.undo)
        .observeBoardingUndo(nowElapsedMs, previous = this)
}

/** Бронь глазами водителя. */
internal fun parseDriverBookingDto(o: JSONObject): DriverBookingDto {
    return DriverBookingDto(
        bookingId = o.optInt("booking_id"),
        noShowWaitSec = o.optInt("no_show_wait_sec").coerceAtLeast(0),
        passengerName = o.optString("passenger_name"),
        passengerRating = if (o.isNull("passenger_rating")) null else o.optDouble("passenger_rating"),
        route = o.optString("route"),
        status = o.optString("status"),
        myStars = o.optInt("my_stars"),
        frontSeat = o.optBoolean("front_seat"),
        passengerId = o.optInt("passenger_id"),
        boardingUndoSec = o.optInt("boarding_undo_sec"), boardingUndo = parseBoardingUndo(o),
        noShowAt = if (o.isNull("no_show_at")) null else o.optString("no_show_at").ifBlank { null },
        noShowNeedsArriving = o.optBoolean("no_show_needs_arriving"),
        arrivingOpensAt = if (o.isNull("arriving_opens_at")) null else o.optString("arriving_opens_at").ifBlank { null },
        dropoffStop = if (o.isNull("dropoff_stop")) null else o.optString("dropoff_stop"),
        pickupStop = if (o.isNull("pickup_stop")) null else o.optString("pickup_stop"),
        rideHasStops = o.optBoolean("ride_has_stops"), fromCity = o.optString("from_city"), toCity = o.optString("to_city"),
        rideId = o.optInt("ride_id"),
        seats = o.optInt("seats"),
        price = o.optInt("price"),
        meetLat = if (o.isNull("meet_lat")) null else o.optDouble("meet_lat"),
        meetLng = if (o.isNull("meet_lng")) null else o.optDouble("meet_lng"),
        meetNote = o.optString("meet_note"),
        meetLandmarkRu = o.optString("meet_landmark_ru"), meetLandmarkBa = o.optString("meet_landmark_ba"),
        alightLat = if (o.isNull("alight_lat")) null else o.optDouble("alight_lat"),
        alightLng = if (o.isNull("alight_lng")) null else o.optDouble("alight_lng"),
        alightNote = o.optString("alight_note"),
        alightLandmarkRu = o.optString("alight_landmark_ru"), alightLandmarkBa = o.optString("alight_landmark_ba"),
        meetingPointSet = o.optBoolean("meeting_point_set"),
        meetingProposalPending = o.optBoolean("meeting_proposal_pending"),
        meetingProposal = parseMeetingProposal(o.optJSONObject("meeting_proposal")),
        meetingVersion = o.optInt("meeting_version", 0),
    )
}
