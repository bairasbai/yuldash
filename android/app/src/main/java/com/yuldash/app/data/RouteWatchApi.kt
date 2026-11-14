package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject

// HTTP домена «карауль поездку» (F13): подписка на маршрут. Вынесено из ApiClient (decisions.md,
// «Слои Android-экрана»): транспорт, сессия и обновление токена — общие, в [ApiClient.authorized].
// Функции-расширения сохраняют прежние вызовы `ApiClient.createRouteWatch(...)`.
//
// Сетевая защита: каждый запрос привязан ко входу владельца экрана (поздний ответ после смены
// аккаунта не применяется), а создание и удаление — одноразовые: после потерянного ответа
// транспорт не повторяет их молча, решает человек кнопкой «Повторить».

/** Подписка на маршрут «карауль поездку» (F13). */
data class RouteWatchDto(
    val id: Int,
    val fromCity: String,
    val toCity: String,
    val direction: String,          // forward | both
    val watchDate: String? = null,  // ISO, если задан конкретный день
    val watchKind: String = "rides",
    val lastNotifiedAt: String? = null,
    val registeredPushApp: Boolean? = null,
    val registeredPushWeb: Boolean? = null,
    val quietFrom: Int? = null,
    val quietTo: Int? = null,
)

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optionalHour(key: String): Int? = optInt(key, -1).takeIf { it in 0..23 }

/** Подписаться на маршрут. */
suspend fun ApiClient.createRouteWatch(
    fromCity: String,
    toCity: String,
    direction: String = "forward",     // forward | both (туда-обратно)
    watchDate: String? = null,         // ISO "yyyy-MM-dd'T'HH:mm:ss" — опц. конкретный день
    expectedGeneration: Long? = null,
): Result<Int> = authorized(
    "POST", "/route-watch",
    JSONObject()
        .put("from_city", fromCity)
        .put("to_city", toCity)
        .put("direction", direction)
        .put("watch_kind", "rides")
        .apply { watchDate?.takeIf { it.isNotBlank() }?.let { put("watch_date", it) } },
    owner = expectedGeneration ?: queueSessionGeneration(),
    oneShot = true,
).map { it.optInt("id") }.onSuccess { Analytics.log("route_watch_create") }

/** Мои активные подписки на маршрут (непротухшие). */
suspend fun ApiClient.getRouteWatches(expectedGeneration: Long? = null): Result<List<RouteWatchDto>> =
    authorized("GET", "/route-watch", null, owner = expectedGeneration ?: queueSessionGeneration()).map { obj ->
        val arr = obj.optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val channels = o.optJSONObject("registered_push_channels")
            RouteWatchDto(
                id = o.optInt("id"),
                fromCity = o.optString("from_city"),
                toCity = o.optString("to_city"),
                direction = o.optString("direction").ifBlank { "forward" },
                watchDate = o.optionalString("watch_date"),
                watchKind = o.optString("watch_kind").ifBlank { "rides" },
                lastNotifiedAt = o.optionalString("last_notified_at"),
                registeredPushApp = channels?.takeUnless { it.isNull("app") }?.optBoolean("app"),
                registeredPushWeb = channels?.takeUnless { it.isNull("web") }?.optBoolean("web"),
                quietFrom = o.optionalHour("quiet_from"),
                quietTo = o.optionalHour("quiet_to"),
            )
        }
    }

/** Отписаться от маршрута. */
suspend fun ApiClient.deleteRouteWatch(id: Int, expectedGeneration: Long? = null): Result<Unit> =
    authorized("DELETE", "/route-watch/$id", null, owner = expectedGeneration ?: queueSessionGeneration(), oneShot = true).map { }
