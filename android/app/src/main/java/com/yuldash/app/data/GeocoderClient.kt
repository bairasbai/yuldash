package com.yuldash.app.data

import com.yuldash.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Найденный адрес: подпись + координаты. */
data class GeoHit(val title: String, val lat: Double, val lon: Double)

/**
 * Поиск адресов через HTTP API Яндекс.Геокодера (поля «Откуда/Куда»).
 * Ключ — из BuildConfig (local.properties, НЕ в git). Без внешних зависимостей.
 */
object GeocoderClient {
    suspend fun suggest(query: String): List<GeoHit> = withContext(Dispatchers.IO) {
        val key = BuildConfig.YANDEX_GEOCODER_KEY
        val q = query.trim()
        if (key.isBlank() || q.length < 2) return@withContext emptyList()
        var conn: HttpURLConnection? = null
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val url = URL("https://geocode-maps.yandex.ru/1.x/?apikey=$key&geocode=$enc&format=json&results=5&lang=ru_RU")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val members = JSONObject(body)
                .getJSONObject("response")
                .getJSONObject("GeoObjectCollection")
                .getJSONArray("featureMember")
            (0 until members.length()).mapNotNull { i ->
                val go = members.getJSONObject(i).getJSONObject("GeoObject")
                val name = go.optString("name")
                val desc = go.optString("description")
                val pos = go.getJSONObject("Point").getString("pos").split(" ") // "lon lat"
                val lon = pos.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = pos.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val title = if (desc.isNotBlank()) "$name, $desc" else name
                if (title.isBlank()) null else GeoHit(title, lat, lon)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }
}
