package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Найденный адрес: подпись + координаты. */
data class GeoHit(val title: String, val lat: Double, val lon: Double)

/**
 * Поиск адресов для полей «Откуда/Куда».
 * Ходим на НАШ бэкенд `/geocode` — ключ Яндекс.Геокодера живёт на сервере, не в APK
 * (раньше клиент слал ключ прямо в URL Яндекса → извлекался из трафика/декомпиляции).
 */
object GeocoderClient {
    // LRU-кеш по запросу: при наборе «Уфа» (У→Уф→Уфа) и повторных полях не дёргаем сеть заново.
    private const val CACHE_MAX = 64
    private val cache = object : LinkedHashMap<String, List<GeoHit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<GeoHit>>) = size > CACHE_MAX
    }

    /**
     * Поиск, который честно различает ДВА разных «пусто»:
     *  • success(emptyList()) — спросили и правда ничего не нашли;
     *  • failure(...)         — спросить не смогли (нет сети, сервер молчит, лифт/подвал).
     * Раньше оба случая возвращали пустой список, и человеку с ПРАВИЛЬНЫМ адресом уверенно
     * писали «Такого адреса не нашли» — экран врал вместо «нет связи, повторить?».
     */
    suspend fun suggestResult(query: String): Result<List<GeoHit>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext Result.success(emptyList())
        synchronized(cache) { cache[q] }?.let { return@withContext Result.success(it) }
        // Запрос идёт через ApiClient.geocode — то есть С ТОКЕНОМ и с авто-обновлением сессии.
        // Раньше соединение открывалось здесь напрямую, без заголовка Authorization, а сервер
        // авторизацию требует: подсказки адреса не работали НИГДЕ — ни в такси, ни в посылках,
        // ни в «моих адресах». Человек вводил правильный адрес и получал «не нашли»
        // (аудит 2026-08-07).
        ApiClient.geocode(q).map { obj ->
            val items = obj.optJSONArray("items") ?: return@map emptyList<GeoHit>()
            val hits = (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val title = o.optString("title")
                val lat = o.optDouble("lat").takeIf { !it.isNaN() } ?: return@mapNotNull null
                val lon = o.optDouble("lon").takeIf { !it.isNaN() } ?: return@mapNotNull null
                if (title.isBlank()) null else GeoHit(title, lat, lon)
            }
            synchronized(cache) { cache[q] = hits }
            hits
        }
    }

    /**
     * Короткий вход для мест, где сбой и «не нашли» равноценны (одна подсказка в фоне,
     * разбор города при отправке формы). Экранам с полем адреса нужен suggestResult.
     */
    suspend fun suggest(query: String): List<GeoHit> = suggestResult(query).getOrDefault(emptyList())
}
