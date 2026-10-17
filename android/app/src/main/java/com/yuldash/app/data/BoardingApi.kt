package com.yuldash.app.data

import org.json.JSONObject

/**
 * Ответ на «Сел» и «Отменить»: итоговый статус брони и сколько секунд ещё можно отменить
 * свою отметку (0 — уже нельзя). Секунды считает сервер: часы телефона врут на минуты.
 */
data class BoardingResult(val status: String, val undoSec: Int)

internal fun parseBoardingResult(o: JSONObject): BoardingResult =
    BoardingResult(status = o.optString("status"), undoSec = o.optInt("boarding_undo_sec").coerceAtLeast(0))

/**
 * HTTP домена «посадка». Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026): пассажирский
 * «Я сел» можно было нажать из дома, чтобы водитель уже не отметил «Не пришёл», а нажатое по
 * ошибке было не вернуть. Сервер — `bookings.py`: `driver-status` «boarded» и `/boarded/undo`.
 * Транспорт, сессия и обновление токена — общие, в [ApiClient] (как у [ContactsApi]).
 */
internal object BoardingApi {

    /** «Сел»: подтверждённая бронь → «в пути». Не раньше чем за 30 минут до выезда; повтор — тот же ответ. */
    suspend fun markBoarded(bookingId: Int, owner: Long = ApiClient.queueSessionGeneration()): Result<BoardingResult> =
        ApiClient.authorized("POST", "/bookings/$bookingId/driver-status", JSONObject().put("status", "boarded"), owner)
            .map(::parseBoardingResult)

    /** «Отменить» свою отметку — промахнулся карточкой. Только в первые 5 минут, позже сервер ответит 409. */
    suspend fun undoBoarded(bookingId: Int, owner: Long = ApiClient.queueSessionGeneration()): Result<BoardingResult> =
        ApiClient.authorized("POST", "/bookings/$bookingId/boarded/undo", JSONObject(), owner)
            .map(::parseBoardingResult)
}
