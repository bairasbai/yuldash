package com.yuldash.app.data

import android.content.Context
import android.util.Base64
import com.yuldash.app.BuildConfig
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
 * Базовый URL приходит из BuildConfig:
 * debug по умолчанию ходит на локальный backend Android Emulator (http://10.0.2.2:8000),
 * release — на публичный домен. Значения можно переопределить в local.properties.
 */
object ApiClient {
    private val BASE = BuildConfig.YULDASH_API_BASE_URL.trimEnd('/')

    @Volatile private var token: String? = null
    @Volatile private var userName: String? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // Долгоживущий scope для POST'ов «отправил и забыл». НЕ привязан к экрану —
    // переживает навигацию (scope экрана отменяется при уходе и обрывает запрос).
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fireCreateRequest(fromCity: String, toCity: String, seats: Int, category: String, withKids: Boolean, comment: String, maxPrice: Int, voiceUrl: String? = null, transcript: String? = null) {
        bg.launch { createRequest(fromCity, toCity, seats, category, withKids, comment, maxPrice, voiceUrl, transcript) }
    }

    fun firePublishRide(
        fromCity: String, toCity: String, departAt: String, seats: Int, price: Int, comment: String,
        petsAllowed: Boolean = false, childSeat: Boolean = false, womenOnly: Boolean = false,
        smoking: Boolean = false, baggage: Boolean = false, airConditioner: Boolean = false,
        recurrence: String = "none", category: String = "regular", pickup: String = "",
    ) {
        bg.launch { publishRide(fromCity, toCity, departAt, seats, price, comment, petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner, recurrence, category, pickup) }
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
        userName = p.getString("user_name", null)
    }

    fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    /** Имя вошедшего клиента (для приветствия и профиля). null → не вошёл (демо). */
    fun cachedName(): String? = userName?.takeIf { it.isNotBlank() }

    fun saveName(n: String) {
        if (n.isBlank()) return
        userName = n
        prefs?.edit()?.putString("user_name", n)?.apply()
    }

    fun saveToken(t: String) {
        token = t
        prefs?.edit()?.putString("token", t)?.apply()
    }

    fun logout() {
        token = null
        userName = null
        prefs?.edit()?.remove("token")?.remove("user_name")?.apply()
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
            saveName(obj.optString("name").ifBlank { name })
        }

    /** Текущий пользователь по токену (проверка валидности сессии). Освежает имя клиента. */
    suspend fun me(): Result<JSONObject> = call("GET", "/me", null, auth = true)
        .onSuccess { o -> o.optString("name").takeIf { it.isNotBlank() }?.let(::saveName) }

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
                    petsAllowed = o.optBoolean("pets_allowed"),
                    childSeat = o.optBoolean("child_seat"),
                    womenOnly = o.optBoolean("women_only"),
                    smoking = o.optBoolean("smoking"),
                    baggage = o.optBoolean("baggage"),
                    airConditioner = o.optBoolean("air_conditioner"),
                    pickup = o.optString("pickup"),
                )
            }
        }

    /**
     * Ближайшие поездки по маршруту клиента, отсортированы по времени выезда (ранняя — первой).
     * Координаты (lat/lng) → дистанция до точки выезда; radiusKm → фильтр по радиусу.
     */
    suspend fun getNearbyRides(
        fromCity: String?,
        toCity: String?,
        lat: Double? = null,
        lng: Double? = null,
        radiusKm: Double? = null,
    ): Result<List<RideDto>> {
        val params = buildList {
            fromCity?.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity?.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
            lat?.let { add("lat=$it") }
            lng?.let { add("lng=$it") }
            radiusKm?.let { add("radius_km=$it") }
        }
        val path = "/rides/near" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return call("GET", path, null, auth = false).map { obj ->
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
                    petsAllowed = o.optBoolean("pets_allowed"),
                    childSeat = o.optBoolean("child_seat"),
                    womenOnly = o.optBoolean("women_only"),
                    smoking = o.optBoolean("smoking"),
                    baggage = o.optBoolean("baggage"),
                    airConditioner = o.optBoolean("air_conditioner"),
                    pickup = o.optString("pickup"),
                    distanceKm = if (o.isNull("distance_km")) null else o.optDouble("distance_km"),
                )
            }
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Опубликовать поездку (текущий пользователь = водитель). depart_at — ISO-строка. */
    suspend fun publishRide(
        fromCity: String,
        toCity: String,
        departAt: String,
        seats: Int,
        price: Int,
        comment: String,
        petsAllowed: Boolean = false,
        childSeat: Boolean = false,
        womenOnly: Boolean = false,
        smoking: Boolean = false,
        baggage: Boolean = false,
        airConditioner: Boolean = false,
        recurrence: String = "none",
        category: String = "regular",
        pickup: String = "",
    ): Result<Unit> = call(
        "POST", "/rides",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("depart_at", departAt)
            .put("seats_total", seats)
            .put("price", price)
            .put("category", category)
            .put("comment", comment)
            .put("pets_allowed", petsAllowed)
            .put("child_seat", childSeat)
            .put("women_only", womenOnly)
            .put("smoking", smoking)
            .put("baggage", baggage)
            .put("air_conditioner", airConditioner)
            .put("recurrence", recurrence)
            .put("pickup", pickup),
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
        voiceUrl: String? = null,
        transcript: String? = null,
    ): Result<Unit> = call(
        "POST", "/requests",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("seats", seats)
            .put("category", category)
            .put("with_kids", withKids)
            .put("max_price", maxPrice)
            .put("comment", comment)
            .apply {
                voiceUrl?.takeIf { it.isNotBlank() }?.let { put("voice_url", it) }
                transcript?.takeIf { it.isNotBlank() }?.let { put("transcript", it) }
            },
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

    // ---------- Проверка водителя ----------
    /** Загрузить фото (документ/авто) base64 → публичный URL. */
    suspend fun uploadPhoto(bytes: ByteArray, ext: String = "jpg"): Result<String> =
        call("POST", "/upload/photo", JSONObject().put("photo_b64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("ext", ext), auth = true)
            .map { it.optString("url") }

    /** Сохранить реальные данные авто водителя. */
    suspend fun setDriverProfile(make: String, model: String, color: String, plate: String, seats: Int): Result<Unit> =
        call(
            "POST", "/driver/profile",
            JSONObject().put("car_make", make).put("car_model", model).put("car_color", color).put("car_plate", plate).put("seats", seats),
            auth = true,
        ).map { }

    /** Отправить документы на проверку (URL фото прав + авто) → статус pending. */
    suspend fun submitDriverVerify(licenseUrl: String, carPhotoUrl: String): Result<Unit> =
        call(
            "POST", "/driver/verify",
            JSONObject().put("license_url", licenseUrl).put("car_photo_url", carPhotoUrl),
            auth = true,
        ).map { }

    /** Текущий статус проверки водителя. */
    suspend fun getDriverStatus(): Result<DriverStatusDto> =
        call("GET", "/driver/status", null, auth = true).map { o ->
            DriverStatusDto(
                docsStatus = o.optString("docs_status", "none"),
                verified = o.optBoolean("verified"),
                carMake = o.optString("car_make"),
                carModel = o.optString("car_model"),
                carColor = o.optString("car_color"),
                carPlate = o.optString("car_plate"),
                seats = o.optInt("seats", 4),
                licenseUrl = o.optString("license_url"),
                carPhotoUrl = o.optString("car_photo_url"),
            )
        }

    /** Брони на поездки водителя — чтобы оценить пассажиров. */
    suspend fun getDriverBookings(): Result<List<DriverBookingDto>> =
        call("GET", "/driver/bookings", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DriverBookingDto(
                    bookingId = o.optInt("booking_id"),
                    passengerName = o.optString("passenger_name"),
                    passengerRating = if (o.isNull("passenger_rating")) null else o.optDouble("passenger_rating"),
                    route = o.optString("route"),
                    status = o.optString("status"),
                )
            }
        }

    // Инбокс: брони с сообщениями (как пассажир и как водитель).
    suspend fun getConversations(): Result<List<ConversationDto>> =
        call("GET", "/conversations", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConversationDto(o.optInt("booking_id"), o.optString("peer_name"), o.optString("route"), o.optString("last_message"))
            }
        }

    // Популярные маршруты — считаются из реальных поездок на сервере.
    suspend fun getPopularRoutes(): Result<List<PopularRouteDto>> =
        call("GET", "/popular-routes", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PopularRouteDto(o.optString("from_city"), o.optString("to_city"), o.optInt("count"))
            }
        }

    // Частые поездки пользователя — из истории его броней.
    suspend fun getMyRoutes(): Result<List<PopularRouteDto>> =
        call("GET", "/my-routes", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PopularRouteDto(o.optString("from_city"), o.optString("to_city"), o.optInt("count"))
            }
        }

    // Живая лента карты: счётчики поездок за период + топ-маршрут недели (из реальных данных).
    suspend fun getFeed(): Result<FeedDto> =
        call("GET", "/feed", null, auth = false).map { o ->
            val tr = o.optJSONObject("top_route")
            FeedDto(
                today = o.optInt("today"),
                week = o.optInt("week"),
                month = o.optInt("month"),
                year = o.optInt("year"),
                drivers = o.optInt("drivers"),
                topFrom = tr?.optString("from_city").orEmpty(),
                topTo = tr?.optString("to_city").orEmpty(),
                topCount = tr?.optInt("count") ?: 0
            )
        }

    // Лента событий (входящие сообщения по броням).
    suspend fun getNotifications(): Result<List<NotifDto>> =
        call("GET", "/notifications", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NotifDto(o.optString("title"), o.optString("text"))
            }
        }

    // Партнёрская реклама — сервер-управляемая.
    suspend fun getAds(): Result<List<AdDto>> =
        call("GET", "/ads", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AdDto(o.optString("id"), o.optString("title"), o.optString("text"), o.optString("button"), o.optString("erid"), o.optString("placement"))
            }
        }

    /** Записать показ/клик по рекламе (реальная статистика). Fire-and-forget. */
    fun fireAdEvent(adId: String, type: String) {
        bg.launch { call("POST", "/ads/$adId/event", JSONObject().put("type", type), auth = false) }
    }

    /** Сводка показов/кликов по каждой рекламе (для кабинета). */
    suspend fun getAdStats(): Result<Map<String, AdStatsDto>> =
        call("GET", "/ads/stats", null, auth = false).map { o ->
            val out = mutableMapOf<String, AdStatsDto>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.optJSONObject(k)?.let { s -> out[k] = AdStatsDto(s.optInt("impressions"), s.optInt("clicks")) }
            }
            out
        }

    // ---------- Активная поездка: поделиться / статус ----------

    suspend fun shareTrip(bookingId: Int, contactId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/share", JSONObject().put("contact_id", contactId), auth = true).map { }

    suspend fun setTripStatus(bookingId: Int, status: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/trip-status", JSONObject().put("status", status), auth = true).map { }

    /** Оценить вторую сторону поездки (1..5 звёзд). Пассажир → водитель, водитель → пассажир. */
    suspend fun rateBooking(bookingId: Int, stars: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/rate", JSONObject().put("stars", stars), auth = true).map { }

    /** Отменить поездку (пассажир или водитель). Места возвращаются в поездку. */
    suspend fun cancelBooking(bookingId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/cancel", null, auth = true).map { }

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
    val petsAllowed: Boolean = false,
    val childSeat: Boolean = false,
    val womenOnly: Boolean = false,
    val smoking: Boolean = false,
    val baggage: Boolean = false,
    val airConditioner: Boolean = false,
    val pickup: String = "",          // где водитель забирает (точка сбора)
    val distanceKm: Double? = null,   // дистанция клиент→точка выезда (только из /rides/near с координатами)
)

/** Статус проверки водителя (с бэкенда /driver/status). */
data class DriverStatusDto(
    val docsStatus: String,
    val verified: Boolean,
    val carMake: String,
    val carModel: String,
    val carColor: String,
    val carPlate: String,
    val seats: Int,
    val licenseUrl: String,
    val carPhotoUrl: String,
)

/** Бронь на поездку водителя — для оценки пассажира. */
data class DriverBookingDto(
    val bookingId: Int,
    val passengerName: String,
    val passengerRating: Double?,
    val route: String,
    val status: String,
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

data class PopularRouteDto(val from: String, val to: String, val count: Int)
/** Живая лента карты: счётчики поездок за период + топ-маршрут недели. */
data class FeedDto(
    val today: Int, val week: Int, val month: Int, val year: Int,
    val drivers: Int, val topFrom: String, val topTo: String, val topCount: Int
)
data class NotifDto(val title: String, val text: String)
data class AdDto(val id: String, val title: String, val text: String, val button: String, val erid: String, val placement: String)
/** Серверная статистика рекламы (показы/клики). */
data class AdStatsDto(val impressions: Int, val clicks: Int)
