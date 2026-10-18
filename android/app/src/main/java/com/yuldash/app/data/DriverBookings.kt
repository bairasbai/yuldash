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
)

/** Бронь глазами водителя. */
internal fun parseDriverBookingDto(o: JSONObject): DriverBookingDto {
    return DriverBookingDto(
        bookingId = o.optInt("booking_id"),
        passengerName = o.optString("passenger_name"),
        passengerRating = if (o.isNull("passenger_rating")) null else o.optDouble("passenger_rating"),
        route = o.optString("route"),
        status = o.optString("status"),
        myStars = o.optInt("my_stars"),
        frontSeat = o.optBoolean("front_seat"),
        passengerId = o.optInt("passenger_id"),
        boardingUndoSec = o.optInt("boarding_undo_sec"),
        noShowAt = if (o.isNull("no_show_at")) null else o.optString("no_show_at").ifBlank { null },
        noShowNeedsArriving = o.optBoolean("no_show_needs_arriving"),
        arrivingOpensAt = if (o.isNull("arriving_opens_at")) null else o.optString("arriving_opens_at").ifBlank { null },
    )
}
