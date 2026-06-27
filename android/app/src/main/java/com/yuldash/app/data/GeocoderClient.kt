package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    suspend fun suggest(query: String): List<GeoHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        synchronized(cache) { cache[q] }?.let { return@withContext it }
        var conn: HttpURLConnection? = null
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val url = URL("${ApiClient.apiBase()}/geocode?q=$enc")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return@withContext emptyList()
            val hits = (0 until items.length()).mapNotNull { i ->
                val o = items.getJSONObject(i)
                val title = o.optString("title")
                val lat = o.optDouble("lat").takeIf { !it.isNaN() } ?: return@mapNotNull null
                val lon = o.optDouble("lon").takeIf { !it.isNaN() } ?: return@mapNotNull null
                if (title.isBlank()) null else GeoHit(title, lat, lon)
            }
            synchronized(cache) { cache[q] = hits }
            hits
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }
}
