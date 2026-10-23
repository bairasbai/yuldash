package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ответ «позвать помощь»: старый сервер второго числа не присылает — тогда доверенных столько же, скольким ушло. */
internal fun parseRoadsideResult(j: JSONObject): ApiClient.RoadsideResult {
    val notified = j.optInt("contacts_notified")
    return ApiClient.RoadsideResult(notified, j.optInt("contacts_total", notified), j.optBoolean("delivery_unconfirmed", false))
}

/**
 * HTTP домена «безопасность»: SOS и «застрял на трассе» попутки с явным повтором по тому же ключу.
 *
 * Ответ мог потеряться, а сигнал — уже сохраниться. Повторное нажатие с тем же текстом и местом
 * уходит с тем же ключом намерения ([SosRetryIntent]), и сервер не заводит второй сигнал. Ключ
 * живёт во входе [ApiClient]: другой аккаунт его не получит. Готовим и снимаем ключ в фоне — запись
 * на диск не должна тормозить главный поток экрана ЧП. Транспорт и сессия — общие, в [ApiClient].
 */
internal object SafetyApi {

    suspend fun sos(
        category: String, note: String, orderId: Int?, lat: Double?, lng: Double?, bookingId: Int?, owner: Long,
    ): Result<Unit> {
        val body = JSONObject().put("category", category).put("note", note)
        if (orderId != null) body.put("order_id", orderId)
        if (bookingId != null) body.put("booking_id", bookingId)
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return send("/sos", body, owner, scope = "sos", oneShot = true).map { }.onSuccess { Analytics.log("sos") }
    }

    suspend fun roadsideHelp(bookingId: Int, lat: Double?, lng: Double?, note: String, owner: Long): Result<ApiClient.RoadsideResult> {
        val body = JSONObject().put("note", note)
        if (lat != null && lng != null) body.put("lat", lat).put("lng", lng)
        return send("/bookings/$bookingId/stuck", body, owner, scope = "roadside-booking-$bookingId", oneShot = false)
            .map { parseRoadsideResult(it) }.onSuccess { Analytics.log("roadside_help") }
    }

    private suspend fun send(path: String, body: JSONObject, owner: Long, scope: String, oneShot: Boolean): Result<JSONObject> {
        val key = withContext(Dispatchers.IO) { ApiClient.prepareSafetyIntent(owner, body, scope) }
            ?: return ApiClient.refusedSession(owner)
        val result = ApiClient.authorized("POST", path, body, owner, oneShot, idempotencyKey = key)
        // Подтверждённое намерение больше не переиспользуется: следующий сигнал — новый ключ.
        if (result.isSuccess) withContext(Dispatchers.IO) { ApiClient.acknowledgeSafetyIntent(owner, key, scope) }
        return result
    }
}
