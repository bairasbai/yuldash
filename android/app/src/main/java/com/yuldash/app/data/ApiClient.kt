package com.yuldash.app.data

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * Базовый URL — домен yulbash.ru (HTTPS, Let's Encrypt).
 */
object ApiClient {
    private const val BASE = "https://yulbash.ru"

    @Volatile private var token: String? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // Долгоживущий scope для POST'ов «отправил и забыл». НЕ привязан к экрану —
    // переживает навигацию (scope экрана отменяется при уходе и обрывает запрос).
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fireCreateRequest(fromCity: String, toCity: String, seats: Int, category: String, withKids: Boolean, comment: String, maxPrice: Int) {
        bg.launch { createRequest(fromCity, toCity, seats, category, withKids, comment, maxPrice) }
    }

    fun firePublishRide(fromCity: String, toCity: String, departAt: String, seats: Int, price: Int, comment: String) {
        bg.launch { publishRide(fromCity, toCity, departAt, seats, price, comment) }
    }

    fun fireBook(rideId: Int, seats: Int) {
        bg.launch { book(rideId, seats) }
    }

    fun fireAddContact(name: String, relation: String, phone: String, notifyByDefault: Boolean) {
        bg.launch { addContact(name, relation, phone, notifyByDefault) }
    }

    fun fireSos(category: String, note: String) {
        bg.launch { sos(category, note) }
    }

    fun fireSendMessage(bookingId: Int, text: String) {
        bg.launch { sendMessage(bookingId, text) }
    }

    fun fireShareTrip(bookingId: Int, contactId: Int) {
        bg.launch { shareTrip(bookingId, contactId) }
    }

    fun fireSetTripStatus(bookingId: Int, status: String) {
        bg.launch { setTripStatus(bookingId, status) }
    }

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

    /** Опубликовать поездку (текущий пользователь = водитель). depart_at — ISO-строка. */
    suspend fun publishRide(
        fromCity: String,
        toCity: String,
        departAt: String,
        seats: Int,
        price: Int,
        comment: String,
    ): Result<Unit> = call(
        "POST", "/rides",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("depart_at", departAt)
            .put("seats_total", seats)
            .put("price", price)
            .put("category", "regular")
            .put("comment", comment),
        auth = true,
    ).map { }

    /** Забронировать поездку. Возвращает id брони. */
    suspend fun book(rideId: Int, seats: Int): Result<Int> = call(
        "POST", "/bookings",
        JSONObject().put("ride_id", rideId).put("seats", seats),
        auth = true,
    ).map { it.optInt("id") }

    // ---------- Заявки ----------

    /** Создать заявку пассажира (требует входа). */
    suspend fun createRequest(
        fromCity: String,
        toCity: String,
        seats: Int,
        category: String,
        withKids: Boolean,
        comment: String,
        maxPrice: Int,
    ): Result<Unit> = call(
        "POST", "/requests",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("seats", seats)
            .put("category", category)
            .put("with_kids", withKids)
            .put("max_price", maxPrice)
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
                    maxPrice = o.optInt("max_price"),
                    comment = o.optString("comment"),
                    forRelativeName = o.optString("for_relative_name").ifBlank { null },
                    status = o.optString("status"),
                )
            }
        }

    // ---------- Доверенные контакты / SOS ----------

    suspend fun addContact(name: String, relation: String, phone: String, notifyByDefault: Boolean): Result<Unit> =
        call(
            "POST", "/trusted-contacts",
            JSONObject().put("name", name).put("relation", relation).put("phone", phone).put("notify_by_default", notifyByDefault),
            auth = true,
        ).map { }

    suspend fun getContacts(): Result<List<ContactDto>> =
        call("GET", "/trusted-contacts", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ContactDto(
                    id = o.optInt("id"),
                    name = o.optString("name"),
                    relation = o.optString("relation"),
                    phone = o.optString("phone"),
                    notifyByDefault = o.optBoolean("notify_by_default"),
                )
            }
        }

    suspend fun sos(category: String, note: String): Result<Unit> =
        call("POST", "/sos", JSONObject().put("category", category).put("note", note), auth = true).map { }

    // ---------- Чат (сообщения по брони) ----------

    /** Id моих броней (чату нужен booking_id). */
    suspend fun getMyBookings(): Result<List<Int>> =
        call("GET", "/bookings/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> arr.getJSONObject(i).optInt("id") }
        }

    suspend fun sendMessage(bookingId: Int, text: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/messages", JSONObject().put("text", text), auth = true).map { }

    suspend fun getMessages(bookingId: Int): Result<List<MessageDto>> =
        call("GET", "/bookings/$bookingId/messages", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MessageDto(o.optInt("id"), o.optString("text"), o.optInt("sender_id"), o.optString("voice_url").ifBlank { null })
            }
        }

    // Голосовое: загрузить аудио (base64) → URL, затем отправить сообщение со ссылкой.
    suspend fun uploadVoice(bytes: ByteArray): Result<String> =
        call("POST", "/voice", JSONObject().put("audio_b64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("ext", "m4a"), auth = true)
            .map { it.optString("url") }

    suspend fun sendVoiceMessage(bookingId: Int, voiceUrl: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/messages", JSONObject().put("voice_url", voiceUrl), auth = true).map { }

    // Инбокс: брони с сообщениями (как пассажир и как водитель).
    suspend fun getConversations(): Result<List<ConversationDto>> =
        call("GET", "/conversations", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConversationDto(o.optInt("booking_id"), o.optString("peer_name"), o.optString("route"), o.optString("last_message"))
            }
        }

    // ---------- Активная поездка: поделиться / статус ----------

    suspend fun shareTrip(bookingId: Int, contactId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/share", JSONObject().put("contact_id", contactId), auth = true).map { }

    suspend fun setTripStatus(bookingId: Int, status: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/trip-status", JSONObject().put("status", status), auth = true).map { }

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
    val maxPrice: Int,
    val comment: String,
    val forRelativeName: String?,
    val status: String,
)

/** Доверенный контакт с сервера. */
data class ContactDto(
    val id: Int,
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
)

/** Сообщение чата с сервера. */
data class MessageDto(
    val id: Int,
    val text: String,
    val senderId: Int,
    val voiceUrl: String? = null,
)

data class ConversationDto(
    val bookingId: Int,
    val peerName: String,
    val route: String,
    val lastMessage: String,
)
