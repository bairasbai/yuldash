package com.yuldash.app.data

import org.json.JSONObject

/** Город для фильтров «Откуда»/«Куда» ленты: одно место — два написания («Баймак» / «Баймаҡ»). */
data class FilterCity(val ru: String, val ba: String)

/** Города, откуда и куда сейчас есть поездки (`/rides/cities`). Пусто — старый сервер или поездок нет. */
data class RideCitiesDto(val from: List<FilterCity>, val to: List<FilterCity>)

/** Ответ `/rides/cities` → города. Без русского имени город пропускаем; без башкирского — берём русское. */
internal fun parseRideCities(o: JSONObject): RideCitiesDto {
    fun list(key: String): List<FilterCity> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val ru = c.optString("ru").trim()
            if (ru.isEmpty()) null else FilterCity(ru, c.optString("ba").trim().ifEmpty { ru })
        }
    }
    return RideCitiesDto(list("from"), list("to"))
}
