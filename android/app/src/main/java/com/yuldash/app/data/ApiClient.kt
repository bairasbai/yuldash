package com.yuldash.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Тонкий клиент к настоящему бэкенду (FastAPI, см. docs/server.md).
 * Встроенный HttpURLConnection + org.json — БЕЗ внешних зависимостей.
 * Токен храним в SharedPreferences, чтобы вход не слетал между запусками.
 *
 * Базовый URL — пока бесплатный sslip.io+HTTPS; сменится на домен одной строкой.
 */
object ApiClient {
    private const val BASE = "https://85-239-52-55.sslip.io"

    @Volatile private var token: String? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    /** Зовём один раз при старте приложения. */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("yuldash", Context.MODE_PRIVATE)
        prefs = p
        token = p.getString("token", null)
    }

    fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    fun saveToken(t: String) {
        token = t
        prefs?.edit()?.putString("token", t)?.apply()
    }

    fun logout() {
        token = null
        prefs?.edit()?.remove("token")?.apply()
    }

    // ---------- Авторизация по SMS-коду ----------

    /** Запросить код на телефон. Пока SMS — мок: код пишется в лог сервера. */
    suspend fun requestCode(phone: String): Result<Unit> =
        call("POST", "/auth/request-code", JSONObject().put("phone", phone), auth = false).map { }

    /** Проверить код. При успехе сохраняем токен и возвращаем JSON юзера. */
    suspend fun verifyCode(phone: String, code: String, name: String): Result<JSONObject> =
        call(
            "POST", "/auth/verify",
            JSONObject().put("phone", phone).put("code", code).put("name", name),
            auth = false,
        ).onSuccess { obj ->
            obj.optString("access_token").takeIf { it.isNotBlank() }?.let { saveToken(it) }
        }

    /** Текущий пользователь по токену (проверка валидности сессии). */
    suspend fun me(): Result<JSONObject> = call("GET", "/me", null, auth = true)

    // ---------- Поездки ----------

    /** Активные поездки с витриной водителя (для «Ближайших поездок»). */
    suspend fun getRides(): Result<List<RideDto>> =
        call("GET", "/rides", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RideDto(
                    id = o.optInt("id"),
                    fromCity = o.optString("from_city"),
                    toCity = o.optString("to_city"),
                    departAt = o.optString("depart_at"),
                    seatsTotal = o.optInt("seats_total"),
                    seatsLeft = o.optInt("seats_left"),
                    price = o.optInt("price"),
                    category = o.optString("category"),
                    driverName = o.optString("driver_name"),
                    driverRating = o.optDouble("driver_rating", 5.0),
                    driverVerified = o.optBoolean("driver_verified"),
                    driverCar = o.optString("driver_car"),
                )
            }
        }

    // ---------- Заявки ----------

    /** Создать заявку пассажира (требует входа). */
    suspend fun createRequest(
        fromCity: String,
        toCity: String,
        seats: Int,
        category: String,
        withKids: Boolean,
        comment: String,
    ): Result<Unit> = call(
        "POST", "/requests",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("seats", seats)
            .put("category", category)
            .put("with_kids", withKids)
            .put("comment", comment),
        auth = true,
    ).map { }

    /** Мои заявки (для вкладки «Заявка»). */
    suspend fun getMyRequests(): Result<List<RequestDto>> =
        call("GET", "/requests/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RequestDto(
                    id = o.optInt("id"),
                    fromCity = o.optString("from_city"),
                    toCity = o.optString("to_city"),
                    seats = o.optInt("seats"),
                    category = o.optString("category"),
                    withKids = o.optBoolean("with_kids"),
                    comment = o.optString("comment"),
                    forRelativeName = o.optString("for_relative_name").ifBlank { null },
                    status = o.optString("status"),
                )
            }
        }

    // ---------- Базовый вызов ----------

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject?,
        auth: Boolean,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                if (auth) token?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val obj = when {
                    text.isBlank() -> JSONObject()
                    text.trimStart().startsWith("[") -> JSONObject().put("items", JSONArray(text))
                    else -> JSONObject(text)
                }
                Result.success(obj)
            } else {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                Result.failure(ApiException(code, detail?.takeIf { it.isNotBlank() } ?: "Ошибка сервера ($code)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}

/** Ошибка API с кодом и понятным текстом для пользователя. */
class ApiException(val status: Int, message: String) : Exception(message)

/** Поездка с витрины сервера (бэкенд RideOut: поездка + данные водителя). */
data class RideDto(
    val id: Int,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val seatsTotal: Int,
    val seatsLeft: Int,
    val price: Int,
    val category: String,
    val driverName: String,
    val driverRating: Double,
    val driverVerified: Boolean,
    val driverCar: String,
)

/** Заявка пассажира с сервера. */
data class RequestDto(
    val id: Int,
    val fromCity: String,
    val toCity: String,
    val seats: Int,
    val category: String,
    val withKids: Boolean,
    val comment: String,
    val forRelativeName: String?,
    val status: String,
)
