package com.yuldash.app.data

import org.json.JSONObject

/**
 * Ответ на «Сел» и «Отменить»: итоговый статус брони и сколько секунд ещё можно отменить
 * свою отметку (0 — уже нельзя). Секунды считает сервер: часы телефона врут на минуты.
 */
data class BoardingUndoTerms(
    val pending: Boolean = false, val requestId: String? = null, val waitSec: Int = 0,
    val onboardAt: String? = null, val confirmedId: String? = null, val sourceOnboardAt: String? = null,
)

/** Подтверждение старой посадки не откатывает новую посадку или терминальный статус. */
internal fun boardingUndoMatches(onboardAt: String?, terms: BoardingUndoTerms): Boolean {
    return !terms.confirmedId.isNullOrBlank() && sameBoardingTimestamp(onboardAt, terms.sourceOnboardAt)
}

internal fun sameBoardingTimestamp(first: String?, second: String?): Boolean {
    if (first.isNullOrBlank() || second.isNullOrBlank()) return false
    fun instant(value: String) = java.time.Instant.parse(if (value.endsWith("Z") || value.endsWith("+00:00")) value else value + "Z")
    return runCatching { instant(first) == instant(second) }.getOrDefault(false)
}

internal fun parseBoardingUndo(o: JSONObject): BoardingUndoTerms = BoardingUndoTerms(
    o.optBoolean("boarding_undo_pending"), o.optString("boarding_undo_request_id").takeIf { !o.isNull("boarding_undo_request_id") && it.isNotBlank() },
    o.optInt("boarding_undo_wait_sec").coerceAtLeast(0),
    o.optString("onboard_at").takeIf { !o.isNull("onboard_at") && it.isNotBlank() },
    o.optString("boarding_undo_confirmed_id").takeIf { !o.isNull("boarding_undo_confirmed_id") && it.isNotBlank() },
    o.optString("boarding_undo_onboard_at").takeIf { !o.isNull("boarding_undo_onboard_at") && it.isNotBlank() },
)

data class BoardingResult(val status: String, val undoSec: Int, val undo: BoardingUndoTerms = BoardingUndoTerms())

internal fun parseBoardingResult(o: JSONObject): BoardingResult =
    BoardingResult(status = o.optString("status"), undoSec = o.optInt("boarding_undo_sec").coerceAtLeast(0), undo = parseBoardingUndo(o))

/**
 * Сроки у карточки водителя (решения 18.10.2026). Считает ТОЛЬКО сервер, клиент берёт:
 *  • [noShowAt] — с какого момента доступна «Не пришёл» (ISO UTC, `no_show_at`); null — срока нет;
 *  • [needsArriving] — поездка с остановками, а «Подъезжаю» по этой брони ещё не было
 *    (`no_show_needs_arriving`): вместо «Не пришёл» карточка предлагает «Подъезжаю»;
 *  • [waitUntilMs] — срок «Не пришёл» по часам ТЕЛЕФОНА: «сейчас + `no_show_wait_sec`» в момент ответа.
 *    Часы телефона спешат на минуты — по одному `no_show_at` карточка решила бы, что срок вышел, и
 *    снова показала бы кнопку, на которую сервер только что ответил «рано»;
 *  • [arrivingOpensAt] / [arrivingWaitUntilMs] — то же для самой «Подъезжаю»: не раньше чем за
 *    30 минут до выезда (`arriving_opens_at` / `arriving_wait_sec`, приёмка PR184).
 * Приходит в строке `/driver/bookings`, в ответе «Подъезжаю» и в отказах.
 */
data class NoShowTerms(
    val noShowAt: String?,
    val needsArriving: Boolean = false,
    val waitUntilMs: Long? = null,
    val arrivingOpensAt: String? = null,
    val arrivingWaitUntilMs: Long? = null,
)

private fun JSONObject?.isoOrNull(key: String): String? =
    if (this == null || isNull(key)) null else optString(key).ifBlank { null }

/** «Сейчас + секунды сервера» по часам телефона; null — сервер секунд не прислал. */
private fun JSONObject?.untilMs(key: String, nowMs: Long): Long? =
    this?.takeIf { it.has(key) && !it.isNull(key) }?.let { nowMs + it.optLong(key).coerceAtLeast(0) * 1000L }

internal fun parseNoShowTerms(o: JSONObject?, nowMs: Long = System.currentTimeMillis()): NoShowTerms = NoShowTerms(
    noShowAt = o.isoOrNull("no_show_at"),
    needsArriving = o?.optBoolean("no_show_needs_arriving") == true,
    waitUntilMs = o.untilMs("no_show_wait_sec", nowMs),
    arrivingOpensAt = o.isoOrNull("arriving_opens_at"),
    arrivingWaitUntilMs = o.untilMs("arriving_wait_sec", nowMs),
)

/**
 * Сроки после отказа по его коду: `no_show_too_early` — момент и секунды «Не пришёл» из отказа
 * (кнопка снова неактивна), `no_show_needs_arriving` — сначала «Подъезжаю», `arriving_too_early` —
 * момент и секунды «Подъезжаю» (кнопка «Подъезжаю» неактивна). Другой отказ — null, сроки те же.
 */
internal fun NoShowTerms.afterRefusal(code: String, detail: JSONObject?, nowMs: Long = System.currentTimeMillis()): NoShowTerms? =
    when (code) {
        "no_show_too_early" -> copy(
            noShowAt = detail.isoOrNull("no_show_at"), waitUntilMs = detail.untilMs("no_show_wait_sec", nowMs), needsArriving = false,
        )
        "no_show_needs_arriving" -> copy(noShowAt = null, waitUntilMs = null, needsArriving = true)
        "arriving_too_early" -> copy(
            arrivingOpensAt = detail.isoOrNull("arriving_opens_at"), arrivingWaitUntilMs = detail.untilMs("arriving_wait_sec", nowMs),
        )
        else -> null
    }

/**
 * HTTP домена «посадка». Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026): пассажирский
 * «Я сел» можно было нажать из дома, чтобы водитель уже не отметил «Не пришёл», а нажатое по
 * ошибке было не вернуть. Сервер — `bookings.py`: `driver-status` «boarded» и `/boarded/undo`.
 * Транспорт, сессия и обновление токена — общие, в [ApiClient] (как у [ContactsApi]).
 */
internal object BoardingApi {

    /** «Сел»: подтверждённая бронь → «в пути». Не раньше чем за 30 минут до выезда; повтор — тот же ответ. */
    suspend fun markBoarded(bookingId: Int, owner: Long = ApiClient.queueSessionGeneration()): Result<BoardingResult> =
        OfflineTripSnapshotHooks.boarding(bookingId, "driver", owner) {
            ApiClient.authorized("POST", "/bookings/$bookingId/driver-status", JSONObject().put("status", "boarded"), owner) }

    /** «Отменить» свою отметку — промахнулся карточкой. Только в первые 5 минут, позже сервер ответит 409. */
    suspend fun undoBoarded(bookingId: Int, owner: Long = ApiClient.queueSessionGeneration()): Result<BoardingResult> =
        OfflineTripSnapshotHooks.boarding(bookingId, "driver", owner) {
            ApiClient.authorized("POST", "/bookings/$bookingId/boarded/undo", JSONObject(), owner) }

    /** Ответ пассажира на конкретный запрос водителя, привязанный к его сессии. */
    suspend fun respondUndo(bookingId: Int, requestId: String, confirm: Boolean, owner: Long): Result<BoardingResult> =
        OfflineTripSnapshotHooks.boarding(bookingId, "passenger", owner) {
            ApiClient.authorized("POST", "/bookings/$bookingId/boarded/undo/respond",
                JSONObject().put("request_id", requestId).put("confirm", confirm), owner) }

    /**
     * «Подъезжаю» с карточки «Едут с тобой» (поездка с остановками, 18.10.2026): пассажиру уходит
     * «Водитель подъезжает», а ответ несёт новый срок «Не пришёл» — от этого нажатия + 15 минут.
     * То же `driver-status` «arriving», что на экране поездки; повтор срок не сдвигает (сервер).
     */
    suspend fun markArriving(bookingId: Int, owner: Long = ApiClient.queueSessionGeneration()): Result<NoShowTerms> =
        OfflineTripSnapshotHooks.arriving(bookingId, owner) {
            ApiClient.authorized("POST", "/bookings/$bookingId/driver-status", JSONObject().put("status", "arriving"), owner) }
}
