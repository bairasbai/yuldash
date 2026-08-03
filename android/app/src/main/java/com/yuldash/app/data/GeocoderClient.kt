package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
        var conn: HttpURLConnection? = null
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val url = URL("${ApiClient.apiBase()}/geocode?q=$enc")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext Result.failure(IOException("geocode HTTP $code"))
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return@withContext Result.success(emptyList())
            val hits = (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val title = o.optString("title")
                val lat = o.optDouble("lat").takeIf { !it.isNaN() } ?: return@mapNotNull null
                val lon = o.optDouble("lon").takeIf { !it.isNaN() } ?: return@mapNotNull null
                if (title.isBlank()) null else GeoHit(title, lat, lon)
            }
            synchronized(cache) { cache[q] = hits }
            Result.success(hits)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Короткий вход для мест, где сбой и «не нашли» равноценны (одна подсказка в фоне,
     * разбор города при отправке формы). Экранам с полем адреса нужен suggestResult.
     */
    suspend fun suggest(query: String): List<GeoHit> = suggestResult(query).getOrDefault(emptyList())
}
