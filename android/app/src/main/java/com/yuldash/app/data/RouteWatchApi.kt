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
)

/** Подписаться на маршрут: как только появится подходящая поездка — придёт уведомление. */
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
            RouteWatchDto(
                id = o.optInt("id"),
                fromCity = o.optString("from_city"),
                toCity = o.optString("to_city"),
                direction = o.optString("direction").ifBlank { "forward" },
                watchDate = o.optString("watch_date").ifBlank { null },
            )
        }
    }

/** Отписаться от маршрута. */
suspend fun ApiClient.deleteRouteWatch(id: Int, expectedGeneration: Long? = null): Result<Unit> =
    authorized("DELETE", "/route-watch/$id", null, owner = expectedGeneration ?: queueSessionGeneration(), oneShot = true).map { }
