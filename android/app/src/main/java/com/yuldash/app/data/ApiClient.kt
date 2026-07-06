package com.yuldash.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yuldash.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val buildBase = BuildConfig.YULDASH_API_BASE_URL.trimEnd('/')

    /** Тест-хук: в unit-тестах подменяем базовый URL на локальный MockWebServer.
     *  В проде остаётся null → идём на buildBase из BuildConfig. Поведение приложения НЕ меняется. */
    internal var testBaseUrl: String? = null

    private val BASE: String get() = testBaseUrl ?: buildBase

    @Volatile private var token: String? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var userName: String? = null
    private val refreshMutex = Mutex()   // не даём нескольким 401 рефрешить одновременно
    @Volatile private var prefs: android.content.SharedPreferences? = null

    // Долгоживущий scope для POST'ов «отправил и забыл». НЕ привязан к экрану —
    // переживает навигацию (scope экрана отменяется при уходе и обрывает запрос).
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fireCreateRequest(fromCity: String, toCity: String, seats: Int, category: String, withKids: Boolean, comment: String, maxPrice: Int, voiceUrl: String? = null, transcript: String? = null, assisted: Boolean = false, relativeName: String? = null) {
        bg.launch { createRequest(fromCity, toCity, seats, category, withKids, comment, maxPrice, voiceUrl, transcript, assisted, relativeName) }
    }

    fun firePublishRide(
        fromCity: String, toCity: String, departAt: String, seats: Int, price: Int, comment: String,
        petsAllowed: Boolean = false, childSeat: Boolean = false, womenOnly: Boolean = false,
        smoking: Boolean = false, baggage: Boolean = false, airConditioner: Boolean = false,
        recurrence: String = "none", category: String = "regular", pickup: String = "",
        pickupLat: Double? = null, pickupLng: Double? = null,
    ) {
        bg.launch { publishRide(fromCity, toCity, departAt, seats, price, comment, petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner, recurrence, category, pickup, pickupLat, pickupLng) }
    }

    fun fireAddContact(name: String, relation: String, phone: String, notifyByDefault: Boolean) {
        bg.launch { addContact(name, relation, phone, notifyByDefault) }
    }

    fun fireRequestCallback(note: String) {
        bg.launch { requestCallback(note) }
    }

    fun fireUpdateName(name: String) {
        bg.launch { updateName(name) }
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
        val app = context.applicationContext
        // Шифрованное хранилище токена (через Android Keystore). Если на устройстве недоступно —
        // не ломаем вход, мягко падаем на обычные prefs.
        val secure = runCatching {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                "yuldash_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
        val plain = app.getSharedPreferences("yuldash", Context.MODE_PRIVATE)
        // Миграция со старого незашифрованного хранилища (один раз): переносим токен в secure.
        if (secure != null && plain.contains("token")) {
            secure.edit()
                .putString("token", plain.getString("token", null))
                .putString("refresh_token", plain.getString("refresh_token", null))
                .putString("user_name", plain.getString("user_name", null))
                .apply()
            plain.edit().remove("token").remove("refresh_token").remove("user_name").apply()
        }
        val p = secure ?: plain
        prefs = p
        token = p.getString("token", null)
        refreshToken = p.getString("refresh_token", null)
        userName = p.getString("user_name", null)
        // Прогрев кеша статики из prefs → цены пакетов/буста видны мгновенно на холодном старте (сеть освежит по TTL).
        seedStatic("ad-packages", ::parseAdPackages)
        seedStatic("boost-plans", ::parseBoostPlans)
    }

    fun isLoggedIn(): Boolean = !token.isNullOrBlank()

    /** Токен (тот же JWT) для WebSocket-чата. */
    internal fun currentToken(): String? = token

    /** База для WebSocket: https→wss, http→ws. */
    internal fun wsBase(): String = BASE.replace("https://", "wss://").replace("http://", "ws://")

    /** База REST API (для geocoder-прокси и т.п.). */
    internal fun apiBase(): String = BASE

    // Кеш разобранного JWT: myUserId() зовётся на КАЖДОЕ сообщение в чате (senderId == myUserId()).
    // Без кеша это Base64-декод + JSON-парс на каждый рендер строки. Сбрасывается сменой токена.
    // @Volatile: myUserId() читается из UI-потока и фонового приёма WS — без него возможна гонка видимости.
    @Volatile private var cachedUserId: Int? = null
    @Volatile private var cachedUserIdForToken: String? = null

    /** Мой user_id из JWT (поле sub) — чтобы отличать свои сообщения. */
    internal fun myUserId(): Int? {
        val t = token ?: return null
        if (t == cachedUserIdForToken) return cachedUserId
        val id = runCatching {
            val payload = t.split(".")[1]
            val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            JSONObject(json).optString("sub").toIntOrNull()
        }.getOrNull()
        cachedUserId = id
        cachedUserIdForToken = t
        return id
    }

    /** Имя вошедшего клиента (для приветствия и профиля). null → не вошёл (демо). */
    fun cachedName(): String? = userName?.takeIf { it.isNotBlank() }

    // ---------- Кеш GET-ответов (TTL) ----------
    // Статику/редкие данные не дёргаем на каждом открытии экрана и в поллинге. Живое (поездки/near/
    // брони/статус/чат) НЕ кешируем. Инвалидация: точечно на мутациях + всё на logout.
    private const val TTL_STATIC = 30 * 60_000L   // ad-packages, boost-plans (меняются лишь при редеплое бэка)
    private const val TTL_SLOW = 5 * 60_000L      // popular-routes, my-routes (обновляются медленно)
    private const val TTL_FEED = 3 * 60_000L      // feed (счётчики дня)
    private const val TTL_PERSONAL = 90_000L      // me, referral, contacts (+ инвалидация на мутациях)
    private class CacheEntry(val ts: Long, val value: Any?)
    private val respCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()
    private fun invalidate(vararg keys: String) { keys.forEach { respCache.remove(it) } }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cachedGet(key: String, ttlMs: Long, fetch: suspend () -> Result<T>): Result<T> {
        respCache[key]?.let { c ->
            if (System.currentTimeMillis() - c.ts < ttlMs && c.value != null) return Result.success(c.value as T)
        }
        return fetch().onSuccess { respCache[key] = CacheEntry(System.currentTimeMillis(), it) }
    }

    // Персист публичной статики (цены пакетов/буста) в prefs → на холодном старте цены видны
    // мгновенно и работают оффлайн; сеть освежит по TTL. Приватности нет (данные публичные).
    private fun parseAdPackages(arr: JSONArray): List<AdPackageDto> =
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            AdPackageDto(a.optString("code"), a.optString("title"), a.optString("title_ba"), a.optInt("amount_kop"), a.optInt("period_days"))
        }
    private fun parseBoostPlans(arr: JSONArray): List<BoostPlanDto> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BoostPlanDto(o.optString("tier"), o.optString("title"), o.optInt("price"), o.optInt("hours"))
        }
    private fun persistStatic(key: String, arr: JSONArray) { prefs?.edit()?.putString("static_$key", arr.toString())?.apply() }
    private fun seedStatic(key: String, parse: (JSONArray) -> Any) {
        prefs?.getString("static_$key", null)?.let { s ->
            runCatching { JSONArray(s) }.getOrNull()?.let { respCache[key] = CacheEntry(System.currentTimeMillis(), parse(it)) }
        }
    }

    fun saveName(n: String) {
        if (n.isBlank()) return
        userName = n
        prefs?.edit()?.putString("user_name", n)?.apply()
    }

    fun saveToken(t: String) {
        token = t
        prefs?.edit()?.putString("token", t)?.apply()
    }

    private fun saveRefresh(t: String) {
        refreshToken = t
        prefs?.edit()?.putString("refresh_token", t)?.apply()
    }

    fun logout() {
        // Серверный выход: помечаем токен недействительным на сервере (logout со всех устройств,
        // ревокация при потере телефона). Токен захватываем в local val — иначе гонка с очисткой ниже.
        val t = token
        if (!t.isNullOrBlank()) {
            bg.launch {
                runCatching {
                    val conn = (URL("$BASE/auth/logout").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 15000
                        setRequestProperty("Authorization", "Bearer $t")
                    }
                    conn.responseCode
                    conn.disconnect()
                }
            }
        }
        // Локальная очистка — синхронно, чтобы UI сразу видел «вышел».
        clearLocalSession()
    }

    /** Локальная очистка сессии (токены, имя, кеши). Реюз: logout + deleteAccount. */
    private fun clearLocalSession() {
        token = null
        refreshToken = null
        userName = null
        cachedUserId = null
        cachedUserIdForToken = null
        respCache.clear()   // сброс кеша ответов (иначе следующий юзер увидит чужой /me/referral/contacts)
        prefs?.edit()?.remove("token")?.remove("refresh_token")?.remove("user_name")?.apply()
    }

    /** Необратимое удаление аккаунта и всех данных на сервере (POST /me/delete).
     *  При успехе локально очищаем сессию — как при выходе. Ошибку прокидываем наверх. */
    suspend fun deleteAccount(): Result<Unit> =
        call("POST", "/me/delete", JSONObject(), auth = true).onSuccess { clearLocalSession() }.map { }

    // ---------- Push (FCM) ----------
    /** Зарегистрировать FCM-токен устройства на сервере (если вошли). Сохраняем, чтобы дослать после логина. */
    fun fireRegisterPushToken(token: String) {
        if (token.isBlank()) return
        prefs?.edit()?.putString("push_token", token)?.apply()
        if (!isLoggedIn()) return
        bg.launch { call("POST", "/push/register", JSONObject().put("token", token), auth = true) }
    }

    /** После логина/старта: взять текущий FCM-токен и зарегистрировать. Без Firebase (нет файла) — тихо ничего. */
    fun registerCurrentPushToken() {
        if (!isLoggedIn()) return
        prefs?.getString("push_token", null)?.let { fireRegisterPushToken(it) }   // дослать сохранённый
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { t -> fireRegisterPushToken(t) }
        }
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
            obj.optString("refresh_token").takeIf { it.isNotBlank() }?.let { saveRefresh(it) }
            // Имя сервер кладёт в user.name (не в корень) — читаем оттуда, иначе фолбэк на введённое.
            val serverName = obj.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }
            saveName(serverName ?: name)
            registerCurrentPushToken()   // SMS-вход тоже регистрирует устройство для push (иначе пуши не идут до перезапуска)
            Analytics.log("login", mapOf("method" to "sms"))
        }

    /** Редактирование профиля: имя для показа. При успехе обновляем кеш имени. */
    suspend fun updateName(name: String): Result<Unit> =
        call("POST", "/me/update", JSONObject().put("name", name), auth = true).onSuccess { saveName(name); invalidate("me") }.map { }

    /** Сохранить аватар (публичный URL из uploadChatPhoto). */
    suspend fun updateAvatar(url: String): Result<Unit> =
        call("POST", "/me/update", JSONObject().put("avatar_url", url), auth = true).onSuccess { invalidate("me") }.map { }

    // ---------- OAuth: Telegram / VK / WhatsApp ----------
    // Возврат из соцсети DeepLink'ом → сюда. При успехе сохраняем токен+имя (как SMS-вход).

    private fun JSONObject.applyAuth(): JSONObject = apply {
        optString("access_token").takeIf { it.isNotBlank() }?.let { saveToken(it) }
        optString("refresh_token").takeIf { it.isNotBlank() }?.let { saveRefresh(it) }
        optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() }?.let(::saveName)
        registerCurrentPushToken()   // после входа — зарегистрировать устройство для push
    }

    /** Старт входа через Telegram. Возвращает request_id — app по нему строит ссылку t.me/<bot>?start=request_id. */
    suspend fun tgStart(): Result<String> =
        call("POST", "/auth/tg/start", JSONObject(), auth = false)
            .map { it.optString("request_id") }
            .onSuccess { Analytics.log("login_start", mapOf("method" to "telegram")) }   // старт входа → видно отвал «начал, но не дошёл до кода»

    /** Проверка 6-значного кода, который бот прислал в Telegram. При успехе — токен+имя. */
    suspend fun tgVerify(requestId: String, code: String): Result<JSONObject> =
        call(
            "POST", "/auth/tg/verify",
            JSONObject().put("request_id", requestId).put("code", code),
            auth = false,
        ).onSuccess { it.applyAuth(); Analytics.log("login", mapOf("method" to "telegram")) }

    /** Текущий пользователь по токену (проверка валидности сессии). Освежает имя клиента. */
    suspend fun me(): Result<JSONObject> = cachedGet("me", TTL_PERSONAL) {
        call("GET", "/me", null, auth = true)
            .onSuccess { o -> o.optString("name").takeIf { it.isNotBlank() }?.let(::saveName) }
    }

    // ---------- Поездки ----------

    /** Активные поездки с витриной водителя (для «Ближайших поездок»). */
    suspend fun getRides(): Result<List<RideDto>> =
        // auth=true: шлём токен (если есть) → сервер прячет заблокированных водителей. Без токена — аноним, как раньше.
        call("GET", "/rides", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
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
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }
    }

    /** Ближайшие поездки с пагинацией («показать ещё»). total — сколько всего на маршруте. */
    suspend fun getNearbyRidesPaged(
        fromCity: String?,
        toCity: String?,
        lat: Double? = null,
        lng: Double? = null,
        radiusKm: Double? = null,
        limit: Int,
    ): Result<NearbyPage> {
        val params = buildList {
            fromCity?.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity?.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
            lat?.let { add("lat=$it") }
            lng?.let { add("lng=$it") }
            radiusKm?.let { add("radius_km=$it") }
            add("limit=$limit")
        }
        val path = "/rides/near?" + params.joinToString("&")
        // auth=true: токен (если есть) → сервер прячет заблокированных. Аноним по-прежнему видит всё.
        return call("GET", path, null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            NearbyPage((0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }, obj.optInt("count"))
        }
    }

    /** Заявки пассажиров рядом (для карты водителя — кто ищет попутку). Приватность: без телефона. */
    suspend fun getNearbyRequests(
        lat: Double? = null,
        lng: Double? = null,
        radiusKm: Double? = null,
    ): Result<List<RequestNearDto>> {
        val params = buildList {
            lat?.let { add("lat=$it") }
            lng?.let { add("lng=$it") }
            radiusKm?.let { add("radius_km=$it") }
        }
        val path = "/requests/near" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return call("GET", path, null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRequestNearDto() }
        }
    }

    /** Ориентир цены по маршруту (среднее прошлых поездок). count=0 → данных нет. */
    suspend fun getPriceHint(fromCity: String, toCity: String): Result<PriceHintDto> {
        val params = buildList {
            fromCity.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
        }
        val path = "/rides/price_hint" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return call("GET", path, null, auth = false).map { PriceHintDto(it.optInt("avg"), it.optInt("count")) }
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
        pickupLat: Double? = null,
        pickupLng: Double? = null,
        receiverName: String = "",   // посылка: кому отдать
        parcelSize: String = "",     // посылка: габарит/вес
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
            .put("pickup", pickup)
            .put("pickup_lat", pickupLat ?: JSONObject.NULL)
            .put("pickup_lng", pickupLng ?: JSONObject.NULL)
            .put("receiver_name", receiverName)
            .put("parcel_size", parcelSize),
        auth = true,
    ).map { }.onSuccess { Analytics.log("publish_ride") }

    /** Забронировать поездку. Возвращает id брони. */
    suspend fun book(rideId: Int, seats: Int): Result<Int> = call(
        "POST", "/bookings",
        JSONObject().put("ride_id", rideId).put("seats", seats),
        auth = true,
    ).map { it.optInt("id") }.onSuccess { Analytics.log("booking") }

    /** Приватные детали брони: телефон и точная встреча открываются только после подтверждения. */
    suspend fun getBookingDetails(bookingId: Int): Result<BookingDetailsDto> =
        call("GET", "/bookings/$bookingId/details", null, auth = true).map { o ->
            BookingDetailsDto(
                bookingId = o.optInt("booking_id"),
                rideId = o.optInt("ride_id"),
                role = o.optString("role"),
                status = o.optString("status"),
                contactUnlocked = o.optBoolean("contact_unlocked"),
                fromCity = o.optString("from_city"),
                toCity = o.optString("to_city"),
                departAt = o.optString("depart_at"),
                seats = o.optInt("seats", 1),
                price = o.optInt("price"),
                driverName = o.optString("driver_name"),
                driverVerified = o.optBoolean("driver_verified"),
                driverPhone = o.optString("driver_phone"),
                driverCar = o.optString("driver_car"),
                pickup = o.optString("pickup"),
                pickupLat = if (o.isNull("pickup_lat")) null else o.optDouble("pickup_lat"),
                pickupLng = if (o.isNull("pickup_lng")) null else o.optDouble("pickup_lng"),
                fromLat = if (o.isNull("from_lat")) null else o.optDouble("from_lat"),
                fromLng = if (o.isNull("from_lng")) null else o.optDouble("from_lng"),
                toLat = if (o.isNull("to_lat")) null else o.optDouble("to_lat"),
                toLng = if (o.isNull("to_lng")) null else o.optDouble("to_lng"),
            )
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
        maxPrice: Int,
        voiceUrl: String? = null,
        transcript: String? = null,
        assisted: Boolean = false,
        relativeName: String? = null,
        desiredAt: String? = null,        // ISO "yyyy-MM-dd'T'HH:mm:ss" — желаемое время выезда (форма его требует)
        womenOnly: Boolean = false,
        childSeat: Boolean = false,
        pets: Boolean = false,
        wheelchair: Boolean = false,
        nonSmoking: Boolean = false,
        airConditioner: Boolean = false,
        baggage: Boolean = false,
    ): Result<Int> = call(
        "POST", "/requests",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("seats", seats)
            .put("category", category)
            .put("with_kids", withKids)
            .put("baggage", baggage)
            .put("women_only", womenOnly)
            .put("child_seat", childSeat)
            .put("pets", pets)
            .put("wheelchair", wheelchair)
            .put("non_smoking", nonSmoking)
            .put("air_conditioner", airConditioner)
            .put("max_price", maxPrice)
            .put("comment", comment)
            .put("assisted", assisted)
            .apply {
                voiceUrl?.takeIf { it.isNotBlank() }?.let { put("voice_url", it) }
                transcript?.takeIf { it.isNotBlank() }?.let { put("transcript", it) }
                relativeName?.takeIf { it.isNotBlank() }?.let { put("for_relative_name", it) }
                desiredAt?.takeIf { it.isNotBlank() }?.let { put("desired_at", it) }
            },
        auth = true,
    ).map { it.optInt("id") }.onSuccess { Analytics.log("create_request") }   // id → оптимистичный LocalRequest.serverId (кнопка отмены сразу)

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
                    desiredAt = o.optString("desired_at").ifBlank { null },
                )
            }
        }

    // ---------- Доверенные контакты / SOS ----------

    suspend fun addContact(name: String, relation: String, phone: String, notifyByDefault: Boolean): Result<Unit> =
        call(
            "POST", "/trusted-contacts",
            JSONObject().put("name", name).put("relation", relation).put("phone", phone).put("notify_by_default", notifyByDefault),
            auth = true,
        ).onSuccess { invalidate("contacts") }.map { }   // добавили контакт → следующий getContacts тянет свежий список

    suspend fun getContacts(): Result<List<ContactDto>> = cachedGet("contacts", TTL_PERSONAL) {
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
    }

    suspend fun sos(category: String, note: String): Result<Unit> =
        call("POST", "/sos", JSONObject().put("category", category).put("note", note), auth = true).map { }.onSuccess { Analytics.log("sos") }

    /** Запрос «перезвоните мне» → уведомление админу в Telegram (помощь пожилым/без интернета). */
    suspend fun requestCallback(note: String): Result<Unit> =
        call("POST", "/callback", JSONObject().put("note", note), auth = true).map { }.onSuccess { Analytics.log("callback_request") }

    // ---------- Жалобы и чёрный список ----------
    suspend fun reportUser(targetUserId: Int, reason: String): Result<Unit> =
        call("POST", "/reports", JSONObject().put("target_user_id", targetUserId).put("reason", reason), auth = true).map { }

    suspend fun blockUser(userId: Int): Result<Unit> =
        call("POST", "/blocks", JSONObject().put("blocked_user_id", userId), auth = true).map { }

    suspend fun unblockUser(userId: Int): Result<Unit> =
        call("DELETE", "/blocks/$userId", null, auth = true).map { }

    suspend fun getBlocks(): Result<List<BlockDto>> =
        call("GET", "/blocks", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); BlockDto(o.optInt("blocked_user_id"), o.optString("name")) }
        }

    // ---------- Заявки ↔ водители: лента, отклики, принятие ----------
    suspend fun getRequestsFeed(): Result<List<RequestFeedDto>> =
        call("GET", "/requests/feed", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pa = o.optJSONArray("prefs")
                val prefs = if (pa != null) (0 until pa.length()).map { pa.optString(it) } else emptyList()
                RequestFeedDto(o.optInt("id"), o.optString("passenger_name"), o.optString("from_city"), o.optString("to_city"), o.optInt("seats"), o.optString("comment"), o.optBoolean("responded"), o.optString("passenger_avatar"), prefs)
            }
        }

    suspend fun respondToRequest(requestId: Int, price: Int, comment: String): Result<Unit> =
        call("POST", "/requests/$requestId/respond", JSONObject().put("price", price).put("comment", comment), auth = true).map { }.onSuccess { Analytics.log("respond_request") }

    /** Пассажир отменяет свою заявку → сервер ставит status=cancelled (идемпотентно; matched → 400). */
    suspend fun cancelRequest(requestId: Int): Result<Unit> =
        call("POST", "/requests/$requestId/cancel", null, auth = true).map { }.onSuccess { Analytics.log("cancel_request") }

    suspend fun getRequestResponses(requestId: Int): Result<List<ResponseDto>> =
        call("GET", "/requests/$requestId/responses", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ResponseDto(o.optInt("id"), o.optInt("driver_id"), o.optString("driver_name"), if (o.isNull("driver_rating")) null else o.optDouble("driver_rating"), o.optInt("price"), o.optString("comment"), o.optString("status"), o.optString("driver_avatar"))
            }
        }

    /** Пассажир принимает отклик → возвращает booking_id (переход в активную поездку). */
    suspend fun acceptResponse(responseId: Int): Result<Int> =
        call("POST", "/responses/$responseId/accept", JSONObject(), auth = true).map { it.optInt("booking_id") }.onSuccess { Analytics.log("accept_response") }

    // ---------- Админ: модерация водителей + жалобы ----------
    suspend fun getPendingDrivers(): Result<List<PendingDriverDto>> =
        call("GET", "/admin/drivers/pending", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingDriverDto(o.optInt("user_id"), o.optString("name"), o.optString("phone"), o.optString("car"), o.optString("license_url"), o.optString("car_photo_url"),
                    o.optString("autocheck_result"), o.optDouble("autocheck_score", 0.0), o.optString("autocheck_data"))
            }
        }

    suspend fun moderateDriver(userId: Int, approve: Boolean): Result<Unit> =
        call("POST", "/admin/drivers/$userId/moderate", JSONObject().put("approve", approve), auth = true).map { }

    suspend fun getAdminReports(): Result<List<AdminReportDto>> =
        call("GET", "/admin/reports", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AdminReportDto(o.optInt("id"), o.optString("reporter_name"), o.optString("target_name"), o.optString("target_phone"), o.optString("reason"), o.optString("created_at"))
            }
        }

    /** Админ создаёт заявку ЗА пользователя по телефону (после звонка «перезвоните мне»). */
    suspend fun adminRequestForPhone(phone: String, name: String, fromCity: String, toCity: String, seats: Int, comment: String): Result<Unit> =
        call("POST", "/admin/request-for-phone", JSONObject()
            .put("phone", phone).put("name", name).put("from_city", fromCity).put("to_city", toCity)
            .put("seats", seats).put("comment", comment), auth = true).map { }

    suspend fun getReportableUsers(): Result<List<ReportableUserDto>> =
        call("GET", "/reportable-users", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); ReportableUserDto(o.optInt("id"), o.optString("name")) }
        }

    /** Отзыв о приложении (идёт на лендинг после модерации published). */
    suspend fun submitAppReview(stars: Int, text: String, city: String): Result<Unit> =
        call(
            "POST", "/reviews",
            JSONObject().put("stars", stars).put("text", text).put("city", city),
            auth = true,
        ).map { }

    /** Отзывы, ожидающие модерации (только админ). */
    suspend fun getPendingReviews(): Result<List<ReviewItem>> =
        call("GET", "/admin/reviews/pending", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ReviewItem(o.optInt("id"), o.optString("name"), o.optString("city"), o.optInt("stars", 5), o.optString("text"))
            }
        }

    /** Одобрить отзыв (или снять с публикации). */
    suspend fun publishReview(id: Int, published: Boolean): Result<Unit> =
        call("POST", "/admin/reviews/$id/publish", JSONObject().put("published", published), auth = true).map { }

    // ---------- Чат (сообщения по брони) ----------

    /** Id моих броней (чату нужен booking_id). */
    suspend fun getMyBookings(): Result<List<Int>> =
        call("GET", "/bookings/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> arr.getJSONObject(i).optInt("id") }
        }

    /** Мои брони со сводкой поездки (маршрут/водитель/статус) — для экрана «Мои поездки».
     *  Старый сервер вернёт только id/seats/status → поля сводки пустые, экран это переживает. */
    suspend fun getMyBookingsDetailed(): Result<List<BookingMineDto>> =
        call("GET", "/bookings/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookingMineDto(
                    id = o.optInt("id"),
                    rideId = o.optInt("ride_id"),
                    seats = o.optInt("seats", 1),
                    price = o.optInt("price"),
                    status = o.optString("status"),
                    boardingCode = o.optString("boarding_code"),
                    fromCity = o.optString("from_city"),
                    toCity = o.optString("to_city"),
                    departAt = o.optString("depart_at"),
                    driverName = o.optString("driver_name"),
                    driverVerified = o.optBoolean("driver_verified"),
                )
            }
        }

    suspend fun sendMessage(bookingId: Int, text: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/messages", JSONObject().put("text", text), auth = true).map { }

    suspend fun editMessage(bookingId: Int, messageId: Int, text: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/messages/$messageId/edit", JSONObject().put("text", text), auth = true).map { }

    /** scope: "all" — удалить у всех (только своё), "me" — скрыть у себя. */
    suspend fun deleteMessage(bookingId: Int, messageId: Int, scope: String): Result<Unit> =
        call("DELETE", "/bookings/$bookingId/messages/$messageId?scope=$scope", null, auth = true).map { }

    /** Код посадки брони (виден только участникам) — пассажир называет, водитель сверяет. */
    suspend fun getBoardingCode(bookingId: Int): Result<String> =
        call("GET", "/bookings/$bookingId/boarding-code", null, auth = true).map { it.optString("code") }

    /** Роль в брони: "driver" | "passenger" — экран активной поездки показывает нужные кнопки. */
    suspend fun getBookingRole(bookingId: Int): Result<String> =
        call("GET", "/bookings/$bookingId/role", null, auth = true).map { it.optString("role") }

    /** Состояние активной поездки (для live-баннера): роль + статус брони + подфаза водителя
     *  (""/departed/arriving). Экран активной поездки опрашивает это раз в ~12с. */
    suspend fun getTripState(bookingId: Int): Result<TripStateDto> =
        call("GET", "/bookings/$bookingId/role", null, auth = true).map {
            TripStateDto(it.optString("role"), it.optString("status"), it.optString("driver_phase"))
        }

    /** Водитель отмечает «выехал»/«подъезжаю» → push пассажиру. status: "departed"|"arriving". */
    suspend fun driverStatus(bookingId: Int, status: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/driver-status", JSONObject().put("status", status), auth = true).map { }

    /** Мой реферал: код, сколько привёл, бонусы, вводил ли чей-то код. */
    suspend fun getReferral(): Result<ReferralDto> = cachedGet("referral", TTL_PERSONAL) {
        call("GET", "/referral/me", null, auth = true).map { o ->
            ReferralDto(o.optString("code"), o.optInt("invited"), o.optInt("credits"), o.optBoolean("redeemed"))
        }
    }

    /** Ввести код друга → оба получают бонус. Возвращает новый баланс бонусов. */
    suspend fun redeemReferral(code: String): Result<Int> =
        call("POST", "/referral/redeem", JSONObject().put("code", code), auth = true)
            .onSuccess { invalidate("referral") }.map { it.optInt("credits") }   // бонусы изменились → сбросить кеш

    /** Поднять свою поездку бесплатно за бонус. Возвращает остаток бонусов. */
    suspend fun boostFree(rideId: Int): Result<Int> =
        call("POST", "/boost/free", JSONObject().put("ride_id", rideId), auth = true)
            .onSuccess { invalidate("referral") }.map { it.optInt("credits") }   // потратили бонус → сбросить кеш

    suspend fun getMessages(bookingId: Int): Result<List<MessageDto>> =
        call("GET", "/bookings/$bookingId/messages", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                parseMessageDto(arr.getJSONObject(i))
            }
        }

    // Голосовое: загрузить аудио (multipart) → URL, затем отправить сообщение со ссылкой.
    suspend fun uploadVoice(bytes: ByteArray): Result<String> =
        callMultipart("/voice", bytes, "m4a", "voice.m4a").map { it.optString("url") }

    suspend fun sendVoiceMessage(bookingId: Int, voiceUrl: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/messages", JSONObject().put("voice_url", voiceUrl), auth = true).map { }

    // Фото в чате: загрузить (multipart) → публичный URL, затем отправить как сообщение с меткой [img].
    suspend fun uploadChatPhoto(bytes: ByteArray, ext: String = "jpg"): Result<String> =
        callMultipart("/upload/chat-photo", bytes, ext, "photo.$ext").map { it.optString("url") }

    const val IMG_PREFIX = "[img]"

    suspend fun sendPhotoMessage(bookingId: Int, photoUrl: String): Result<Unit> =
        sendMessage(bookingId, "$IMG_PREFIX$photoUrl")

    // ---------- Проверка водителя ----------
    /** Загрузить фото (документ/авто) через multipart → публичный URL. */
    suspend fun uploadPhoto(bytes: ByteArray, ext: String = "jpg"): Result<String> =
        callMultipart("/upload/photo", bytes, ext, "photo.$ext").map { it.optString("url") }

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
                online = o.optBoolean("online"),
                autocheckResult = o.optString("autocheck_result"),
                autocheckData = o.optString("autocheck_data"),
            )
        }

    /** Водитель: я на линии (доступен сейчас) / не на линии. */
    suspend fun setOnline(online: Boolean): Result<Unit> =
        call("POST", "/driver/online", JSONObject().put("online", online), auth = true).map { }

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
                ConversationDto(o.optInt("booking_id"), o.optString("peer_name"), o.optString("route"), o.optString("last_message"), o.optString("peer_avatar"), o.optString("depart_at").ifBlank { null }, o.optBoolean("peer_verified"))
            }
        }

    // Популярные маршруты — считаются из реальных поездок на сервере.
    suspend fun getPopularRoutes(): Result<List<PopularRouteDto>> = cachedGet("popular-routes", TTL_SLOW) {
        call("GET", "/popular-routes", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PopularRouteDto(o.optString("from_city"), o.optString("to_city"), o.optInt("count"))
            }
        }
    }

    // Частые поездки пользователя — из истории его броней.
    suspend fun getMyRoutes(): Result<List<PopularRouteDto>> = cachedGet("my-routes", TTL_SLOW) {
        call("GET", "/my-routes", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PopularRouteDto(o.optString("from_city"), o.optString("to_city"), o.optInt("count"))
            }
        }
    }

    // Живая лента карты: счётчики поездок за период + топ-маршрут недели (из реальных данных).
    suspend fun getFeed(): Result<FeedDto> = cachedGet("feed", TTL_FEED) {
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
                topCount = tr?.optInt("count") ?: 0,
                donationsTotal = o.optInt("donations_total")
            )
        }
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
                AdDto(
                    o.optString("id"), o.optString("title"), o.optString("text"), o.optString("button"), o.optString("erid"), o.optString("placement"),
                    partner = o.optString("partner"), contact = o.optString("contact"), target = o.optString("target"),
                    image = o.optString("image"), city = o.optString("city"),
                )
            }
        }

    /** Записать показ/клик по рекламе (реальная статистика). Fire-and-forget. Требует входа
     *  (сервер закрыл endpoint от накрутки) — в приложении реклама показывается уже после логина. */
    fun fireAdEvent(adId: String, type: String) {
        bg.launch { call("POST", "/ads/$adId/event", JSONObject().put("type", type), auth = true) }
    }

    /** Сводка показов/кликов по каждой рекламе (для кабинета, admin-only на сервере → шлём токен). */
    suspend fun getAdStats(): Result<Map<String, AdStatsDto>> =
        call("GET", "/ads/stats", null, auth = true).map { o ->
            val out = mutableMapOf<String, AdStatsDto>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                o.optJSONObject(k)?.let { s -> out[k] = AdStatsDto(s.optInt("impressions"), s.optInt("clicks")) }
            }
            out
        }

    // ---------- Реклама: админ-управление ----------
    /** Все объявления для админ-кабинета + сколько founder-слотов занято. */
    suspend fun getAdminAds(): Result<AdminAdsDto> =
        call("GET", "/admin/ads", null, auth = true).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                val pls = a.optJSONArray("placements")
                val placements = if (pls != null) (0 until pls.length()).joinToString(",") { pls.optString(it) } else ""
                val cts = a.optJSONArray("cities")
                val cities = if (cts != null) (0 until cts.length()).joinToString(",") { cts.optString(it) } else ""
                AdminAdDto(
                    id = a.optString("id"),
                    partner = a.optString("partner"),
                    title = a.optString("title"),
                    text = a.optString("text"),
                    plan = a.optString("plan"),
                    status = a.optString("status"),
                    placements = placements,
                    erid = a.optString("erid"),
                    endsAt = a.optString("ends_at").ifBlank { null },
                    live = a.optBoolean("live"),
                    expired = a.optBoolean("expired"),
                    button = a.optString("button"),
                    target = a.optString("target"),
                    cities = cities,
                    rejectReason = a.optString("reject_reason"),
                    ownerId = if (a.isNull("owner_id")) null else a.optInt("owner_id"),
                )
            }
            AdminAdsDto(o.optInt("founder_used"), o.optInt("founder_limit", 10), items)
        }

    /** Создать объявление (admin). plan: founder/standard/premium. */
    suspend fun createAd(
        partnerName: String, title: String, text: String, button: String,
        plan: String, placements: String, erid: String, target: String, city: String, price: Int = 0,
    ): Result<Unit> =
        call(
            "POST", "/admin/ads",
            JSONObject()
                .put("partner_name", partnerName).put("title", title).put("text", text)
                .put("button", button).put("plan", plan).put("placements", placements)
                .put("erid", erid).put("target", target).put("cities", city).put("price", price),
            auth = true,
        ).map { }

    /** Редактировать объявление (admin). Цену не трогаем — правка контента не пере-выставляет оплату. */
    suspend fun updateAd(
        id: String, partnerName: String, title: String, text: String, button: String,
        plan: String, placements: String, erid: String, target: String, city: String,
    ): Result<Unit> =
        call(
            "POST", "/admin/ads/$id",
            JSONObject()
                .put("partner_name", partnerName).put("title", title).put("text", text)
                .put("button", button).put("plan", plan).put("placements", placements)
                .put("erid", erid).put("target", target).put("cities", city),
            auth = true,
        ).map { }

    /** Сменить статус: active / paused / draft / archived. */
    suspend fun setAdStatus(id: String, status: String): Result<Unit> =
        call("POST", "/admin/ads/$id/status", JSONObject().put("status", status), auth = true).map { }

    /** Модерация партнёрского объявления: одобрить (→active, вставить erid из ОРД). */
    suspend fun approveAd(id: String, erid: String): Result<Unit> =
        call("POST", "/admin/ads/$id/approve", JSONObject().put("erid", erid), auth = true).map { }

    /** Модерация: отклонить с причиной (партнёр увидит и исправит). */
    suspend fun rejectAd(id: String, reason: String): Result<Unit> =
        call("POST", "/admin/ads/$id/reject", JSONObject().put("reason", reason), auth = true).map { }

    /** Удалить (мягко в архив). */
    suspend fun deleteAd(id: String): Result<Unit> =
        call("DELETE", "/admin/ads/$id", null, auth = true).map { }

    // ---------- Реклама: кабинет ПАРТНЁРА (self-serve) ----------
    private fun parseMyAd(a: JSONObject): MyAdDto {
        fun arrCsv(key: String): String {
            val arr = a.optJSONArray(key) ?: return ""
            return (0 until arr.length()).joinToString(",") { arr.optString(it) }
        }
        return MyAdDto(
            id = a.optString("id"), title = a.optString("title"), text = a.optString("text"),
            button = a.optString("button"), target = a.optString("target"), erid = a.optString("erid"),
            status = a.optString("status"), rejectReason = a.optString("reject_reason"),
            pkg = a.optString("package"), pkgTitle = a.optString("package_title"),
            budgetKop = a.optInt("budget_kop"), periodDays = a.optInt("period_days"),
            placements = arrCsv("placements"), cities = arrCsv("cities"),
            paid = a.optBoolean("paid"), submittedAt = a.optString("submitted_at").ifBlank { null },
        )
    }

    /** Тарифы размещения (из конфига сервера) — для кабинета/витрины. Без входа. */
    suspend fun getAdPackages(): Result<List<AdPackageDto>> = cachedGet("ad-packages", TTL_STATIC) {
        call("GET", "/ad-packages", null, auth = false).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            persistStatic("ad-packages", arr)   // сохранить → переживёт перезапуск, работает оффлайн
            parseAdPackages(arr)
        }
    }

    /** Мои объявления (владелец = я), все статусы + причина отказа + оплата. */
    suspend fun getMyAds(): Result<List<MyAdDto>> =
        call("GET", "/ads/mine", null, auth = true).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> parseMyAd(arr.getJSONObject(i)) }
        }

    /** Создать своё объявление (черновик). */
    suspend fun createMyAd(title: String, text: String, button: String, target: String, pkg: String, cities: String): Result<MyAdDto> =
        call("POST", "/ads", JSONObject().put("title", title).put("text", text).put("button", button)
            .put("target", target).put("package", pkg).put("cities", cities), auth = true).map { parseMyAd(it) }

    /** Правка своего черновика/отклонённого. */
    suspend fun updateMyAd(id: String, title: String, text: String, button: String, target: String, pkg: String, cities: String): Result<MyAdDto> =
        call("POST", "/ads/$id", JSONObject().put("title", title).put("text", text).put("button", button)
            .put("target", target).put("package", pkg).put("cities", cities), auth = true).map { parseMyAd(it) }

    /** Отправить своё объявление на модерацию. */
    suspend fun submitMyAd(id: String): Result<MyAdDto> =
        call("POST", "/ads/$id/submit", null, auth = true).map { parseMyAd(it) }

    /** Создать заявку на оплату своего размещения (после одобрения). Возвращает сумму в копейках. */
    suspend fun payAd(id: String): Result<Int> =
        call("POST", "/ads/$id/pay", null, auth = true).map { it.optInt("amount_kop") }

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
            .onSuccess { Analytics.log("booking_cancel") }

    // ---------- Boost (поднятие объявления, оплата) ----------

    /** Тарифы поднятия (цены с бэкенда). */
    suspend fun getBoostPlans(): Result<List<BoostPlanDto>> = cachedGet("boost-plans", TTL_STATIC) {
        call("GET", "/boost/plans", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            persistStatic("boost-plans", arr)   // сохранить → мгновенно на холодном старте
            parseBoostPlans(arr)
        }
    }

    /** Мои активные поездки (для выбора, какую поднять). */
    suspend fun getDriverRides(): Result<List<RideDto>> =
        call("GET", "/driver/rides", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }

    /** Создать платёж за поднятие поездки. Возврат: статус + реквизиты СБП / ссылка ЮKassa. */
    suspend fun createBoost(rideId: Int, tier: String): Result<BoostResultDto> =
        call("POST", "/boost/create", JSONObject().put("ride_id", rideId).put("tier", tier), auth = true).map { o ->
            val payee = o.optJSONObject("payee")
            BoostResultDto(
                status = o.optString("status"),
                method = o.optString("method"),
                paymentId = o.optInt("payment_id"),
                amount = o.optInt("amount"),
                confirmationUrl = o.optString("confirmation_url").ifBlank { null },
                payeePhone = payee?.optString("phone")?.ifBlank { null },
                payeeBank = payee?.optString("bank")?.ifBlank { null },
                payeeName = payee?.optString("name")?.ifBlank { null },
            )
        }

    /** Донат на платформу (интерим СБП): создаёт заявку на подтверждение, возвращает реквизиты (как boost). */
    suspend fun createDonation(amount: Int): Result<BoostResultDto> =
        call("POST", "/donate", JSONObject().put("amount", amount), auth = true).map { o ->
            val payee = o.optJSONObject("payee")
            BoostResultDto(
                status = o.optString("status"), method = o.optString("method"),
                paymentId = o.optInt("payment_id"), amount = o.optInt("amount"),
                confirmationUrl = o.optString("confirmation_url").ifBlank { null },
                payeePhone = payee?.optString("phone")?.ifBlank { null },
                payeeBank = payee?.optString("bank")?.ifBlank { null },
                payeeName = payee?.optString("name")?.ifBlank { null },
            )
        }

    // ---------- Админ: заявки на оплату (буст/донат на подтверждение) ----------
    suspend fun getPendingPayments(): Result<List<PendingPaymentDto>> =
        call("GET", "/admin/payments/pending", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingPaymentDto(
                    o.optInt("payment_id"), o.optString("purpose"), o.optString("tier"),
                    o.optInt("amount"), if (o.isNull("ride_id")) null else o.optInt("ride_id"),
                    o.optString("payer_name"), o.optString("payer_phone"), o.optString("created_at"), o.optString("note"),
                )
            }
        }

    suspend fun confirmPayment(paymentId: Int): Result<Unit> =
        call("POST", "/admin/payments/$paymentId/confirm", JSONObject(), auth = true).map { }

    suspend fun rejectPayment(paymentId: Int): Result<Unit> =
        call("POST", "/admin/payments/$paymentId/reject", JSONObject(), auth = true).map { }

    suspend fun getPaymentsSummary(): Result<PaymentsSummaryDto> =
        call("GET", "/admin/payments/summary", null, auth = true).map { o ->
            val d = o.optJSONObject("donate") ?: JSONObject()
            val b = o.optJSONObject("boost") ?: JSONObject()
            PaymentsSummaryDto(d.optInt("count"), d.optInt("sum_rub"), b.optInt("count"), b.optInt("sum_rub"))
        }

    /** F18: личная статистика попутчика (км/поездки/₽/CO₂/звание). */
    suspend fun getMyStats(): Result<MyStatsDto> =
        call("GET", "/me/stats", null, auth = true).map { o ->
            val rank = o.optJSONObject("rank") ?: JSONObject()
            MyStatsDto(
                trips = o.optInt("trips"),
                km = o.optDouble("km", 0.0),
                savedRub = o.optInt("saved_rub"),
                co2SavedKg = o.optDouble("co2_saved_kg", 0.0),
                rankLevel = rank.optInt("level"),
                rankTitleRu = rank.optString("title_ru"),
                rankTitleBa = rank.optString("title_ba"),
                nextTitleRu = if (rank.isNull("next_title_ru")) null else rank.optString("next_title_ru"),
                nextTitleBa = if (rank.isNull("next_title_ba")) null else rank.optString("next_title_ba"),
                nextAt = if (rank.isNull("next_at")) null else rank.optInt("next_at"),
                toNext = rank.optInt("to_next"),
            )
        }

    // ---------- Базовый вызов ----------

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject?,
        auth: Boolean,
        isRetry: Boolean = false,        // повтор после обновления access-токена (чтобы не зациклиться)
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val usedToken = if (auth) token else null
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
                if (auth) usedToken?.let { setRequestProperty("Authorization", "Bearer $it") }
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
            } else if (code == 401 && auth && !isRetry && !refreshToken.isNullOrBlank()) {
                // Access протух → пробуем обновить по refresh-токену и повторить ОДИН раз.
                conn.disconnect(); conn = null
                if (tryRefresh(usedToken)) call(method, path, body, auth, isRetry = true)
                else {
                    logout()   // refresh мёртв → чистим локальную сессию, иначе isLoggedIn() врёт true и юзер «залипает» с 401 на каждом запросе
                    Result.failure(ApiException(401, "Сессия истекла. Войди заново."))
                }
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

    /** Загрузка файла через multipart/form-data (поле `file` + `ext`). В отличие от base64-JSON
     *  не держит весь файл удвоенным в памяти как строку. Сервер принимает и multipart, и base64
     *  (обратная совместимость). Те же auth + однократный refresh на 401, что и в call(). */
    private suspend fun callMultipart(
        path: String,
        fileBytes: ByteArray,
        ext: String,
        filename: String,
        isRetry: Boolean = false,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val usedToken = token
        var conn: HttpURLConnection? = null
        try {
            val boundary = "----yuldash${System.nanoTime().toString(16)}"
            val crlf = "\r\n"
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                usedToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { out ->
                val head = "--$boundary$crlf" +
                    "Content-Disposition: form-data; name=\"ext\"$crlf$crlf$ext$crlf" +
                    "--$boundary$crlf" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"$crlf" +
                    "Content-Type: application/octet-stream$crlf$crlf"
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(fileBytes)
                out.write("$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Result.success(if (text.isBlank()) JSONObject() else JSONObject(text))
            } else if (code == 401 && !isRetry && !refreshToken.isNullOrBlank()) {
                conn.disconnect(); conn = null
                if (tryRefresh(usedToken)) callMultipart(path, fileBytes, ext, filename, isRetry = true)
                else Result.failure(ApiException(401, "Сессия истекла. Войди заново."))
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

    /** Обновить пару токенов по refresh. Mutex: при пачке 401 рефреш идёт один раз.
     *  `staleToken` — access, с которым словили 401; если он уже сменился — другой поток обновил. */
    private suspend fun tryRefresh(staleToken: String?): Boolean = refreshMutex.withLock {
        if (token != null && token != staleToken) return@withLock true   // уже обновил другой запрос
        val rt = refreshToken ?: return@withLock false
        call("POST", "/auth/refresh", JSONObject().put("refresh_token", rt), auth = false, isRetry = true)
            .map { obj ->
                obj.optString("access_token").takeIf { it.isNotBlank() }?.let { saveToken(it) }
                obj.optString("refresh_token").takeIf { it.isNotBlank() }?.let { saveRefresh(it) }
            }
            .isSuccess
    }
}

/** Ошибка API с кодом и понятным текстом для пользователя. */
class ApiException(val status: Int, message: String) : Exception(message)

data class ReviewItem(val id: Int, val name: String, val city: String, val stars: Int, val text: String)

/** Поездка с витрины сервера (бэкенд RideOut: поездка + данные водителя). */
data class PriceHintDto(val avg: Int, val count: Int)

/** Страница «Ближайших»: показанные + всего на маршруте (для кнопки «Показать ещё»). */
data class NearbyPage(val items: List<RideDto>, val total: Int)

/** JSON поездки с сервера → RideDto. Один шов вместо копипасты в getRides/getNearbyRides.
 *  distance_km нет в /rides → isNull(...) = null; есть в /rides/near → читаем. */
private fun JSONObject.toRideDto() = RideDto(
    id = optInt("id"),
    fromCity = optString("from_city"),
    toCity = optString("to_city"),
    departAt = optString("depart_at"),
    seatsTotal = optInt("seats_total"),
    seatsLeft = optInt("seats_left"),
    price = optInt("price"),
    category = optString("category"),
    driverName = optString("driver_name"),
    driverRating = optDouble("driver_rating", 5.0),
    driverVerified = optBoolean("driver_verified"),
    driverCar = optString("driver_car"),
    driverAvatar = optString("driver_avatar"),
    driverOnline = optBoolean("driver_online"),
    petsAllowed = optBoolean("pets_allowed"),
    childSeat = optBoolean("child_seat"),
    womenOnly = optBoolean("women_only"),
    smoking = optBoolean("smoking"),
    baggage = optBoolean("baggage"),
    airConditioner = optBoolean("air_conditioner"),
    pickup = optString("pickup"),
    pickupLat = if (isNull("pickup_lat")) null else optDouble("pickup_lat"),
    pickupLng = if (isNull("pickup_lng")) null else optDouble("pickup_lng"),
    distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
    boosted = optBoolean("boosted"),
    receiverName = optString("receiver_name"),
    parcelSize = optString("parcel_size"),
)

private fun JSONObject.toRequestNearDto() = RequestNearDto(
    id = optInt("id"),
    passengerName = optString("passenger_name").ifBlank { "Пассажир" },
    fromCity = optString("from_city"),
    toCity = optString("to_city"),
    fromLat = if (isNull("from_lat")) null else optDouble("from_lat"),
    fromLng = if (isNull("from_lng")) null else optDouble("from_lng"),
    seats = optInt("seats", 1),
    comment = optString("comment"),
    distanceKm = if (isNull("distance_km")) null else optDouble("distance_km"),
)

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
    val driverAvatar: String = "",
    val driverOnline: Boolean = false,
    val petsAllowed: Boolean = false,
    val childSeat: Boolean = false,
    val womenOnly: Boolean = false,
    val smoking: Boolean = false,
    val baggage: Boolean = false,
    val airConditioner: Boolean = false,
    val pickup: String = "",          // где водитель забирает (точка сбора)
    val pickupLat: Double? = null,    // координаты точки сбора (пин на карте)
    val pickupLng: Double? = null,
    val distanceKm: Double? = null,   // дистанция клиент→точка выезда (только из /rides/near с координатами)
    val boosted: Boolean = false,     // активный Boost (подсветка/бейдж)
    val receiverName: String = "",    // посылка: кому отдать
    val parcelSize: String = "",      // посылка: габарит/вес
)

/** Заявка пассажира рядом (/requests/near) — для маркера «ищет попутку» на карте. Без телефона. */
/** Состояние активной поездки: роль + статус брони + подфаза водителя (""/departed/arriving). */
data class TripStateDto(val role: String, val status: String, val driverPhase: String)

data class RequestNearDto(
    val id: Int,
    val passengerName: String,
    val fromCity: String,
    val toCity: String,
    val fromLat: Double?,
    val fromLng: Double?,
    val seats: Int,
    val comment: String,
    val distanceKm: Double?,
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
    val online: Boolean = false,
    val autocheckResult: String = "",   // "" / pass / needs_human / reject / error
    val autocheckData: String = "",      // JSON: распознанные поля + коды причин
)

/** Бронь на поездку водителя — для оценки пассажира. */
data class DriverBookingDto(
    val bookingId: Int,
    val passengerName: String,
    val passengerRating: Double?,
    val route: String,
    val status: String,
)

/** Реферал «позови своего»: код, сколько привёл, бонусы, вводил ли чей-то код. */
data class ReferralDto(val code: String, val invited: Int, val credits: Int, val redeemed: Boolean)

/** Моя бронь со сводкой поездки — для экрана «Мои поездки». */
data class BookingMineDto(
    val id: Int,
    val rideId: Int,
    val seats: Int,
    val price: Int,
    val status: String,
    val boardingCode: String,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val driverName: String,
    val driverVerified: Boolean,
)

/** Приватные детали брони для вкладки «Детали поездки». */
data class BookingDetailsDto(
    val bookingId: Int,
    val rideId: Int,
    val role: String,
    val status: String,
    val contactUnlocked: Boolean,
    val fromCity: String,
    val toCity: String,
    val departAt: String,
    val seats: Int,
    val price: Int,
    val driverName: String,
    val driverVerified: Boolean,
    val driverPhone: String,
    val driverCar: String,
    val pickup: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val fromLat: Double?,
    val fromLng: Double?,
    val toLat: Double?,
    val toLng: Double?,
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
    val desiredAt: String? = null,   // ISO желаемого выезда (может быть null у старых заявок)
)

/** Доверенный контакт с сервера. */
data class BlockDto(val blockedUserId: Int, val name: String)
data class ReportableUserDto(val id: Int, val name: String)
data class PendingDriverDto(val userId: Int, val name: String, val phone: String, val car: String, val licenseUrl: String, val carPhotoUrl: String,
    val autocheckResult: String = "", val autocheckScore: Double = 0.0, val autocheckData: String = "")
data class AdminReportDto(val id: Int, val reporterName: String, val targetName: String, val targetPhone: String, val reason: String, val createdAt: String)
data class RequestFeedDto(val id: Int, val passengerName: String, val from: String, val to: String, val seats: Int, val comment: String, val responded: Boolean, val passengerAvatar: String = "", val prefs: List<String> = emptyList())
data class ResponseDto(val id: Int, val driverId: Int, val driverName: String, val driverRating: Double?, val price: Int, val comment: String, val status: String, val driverAvatar: String = "")

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
    val deleted: Boolean = false,
    val edited: Boolean = false,
)

internal fun parseMessageDto(o: JSONObject): MessageDto =
    MessageDto(
        id = o.optInt("id"),
        text = o.optString("text"),
        senderId = o.optInt("sender_id"),
        voiceUrl = o.optNullableString("voice_url"),
        deleted = o.optBoolean("deleted"),
        edited = o.optBoolean("edited"),
    )

private fun JSONObject.optNullableString(key: String): String? {
    return normalizeOptionalJsonString(
        hasValue = has(key),
        isJsonNull = isNull(key),
        raw = optString(key),
    )
}

internal fun normalizeOptionalJsonString(hasValue: Boolean, isJsonNull: Boolean, raw: String): String? {
    if (!hasValue || isJsonNull) return null
    return raw.takeIf { it.isNotBlank() && it != "null" }
}

data class ConversationDto(
    val bookingId: Int,
    val peerName: String,
    val route: String,
    val lastMessage: String,
    val peerAvatar: String = "",
    val departAt: String? = null,   // ISO времени выезда — различать треды одного маршрута
    val peerVerified: Boolean = false,   // реальный статус проверки собеседника (с сервера)
)

data class PopularRouteDto(val from: String, val to: String, val count: Int)
/** Живая лента карты: счётчики поездок за период + топ-маршрут недели. */
data class FeedDto(
    val today: Int, val week: Int, val month: Int, val year: Int,
    val drivers: Int, val topFrom: String, val topTo: String, val topCount: Int,
    val donationsTotal: Int = 0   // ₽ донатов от пользователей за всё время
)
data class NotifDto(val title: String, val text: String)

/** Тариф поднятия (с бэкенда /boost/plans). */
data class BoostPlanDto(val tier: String, val title: String, val price: Int, val hours: Int)

/** Результат /boost/create: статус + способ оплаты (реквизиты СБП или ссылка ЮKassa). */
data class BoostResultDto(
    val status: String,            // succeeded | pending
    val method: String,            // sbp_manual | yookassa
    val paymentId: Int,
    val amount: Int,               // ₽
    val confirmationUrl: String?,  // ЮKassa redirect
    val payeePhone: String?,       // СБП: номер получателя
    val payeeBank: String?,
    val payeeName: String?,
)

/** Заявка на оплату (буст/донат) в админ-очереди подтверждения. */
data class PendingPaymentDto(
    val paymentId: Int, val purpose: String, val tier: String, val amount: Int,
    val rideId: Int?, val payerName: String, val payerPhone: String, val createdAt: String, val note: String = "",
)
/** Счётчик подтверждённых оплат (донаты/буст) для админ-кабинета. */
data class PaymentsSummaryDto(val donateCount: Int, val donateSum: Int, val boostCount: Int, val boostSum: Int)

/** F18 «Мой Юлдаш» — личная статистика попутчика (GET /me/stats). */
data class MyStatsDto(
    val trips: Int,            // число поездок (пассажир + водитель)
    val km: Double,            // км, проеханные вместе
    val savedRub: Int,         // сэкономлено ₽ (vs такси-ориентир)
    val co2SavedKg: Double,    // сэкономлено CO₂, кг
    val rankLevel: Int,        // уровень звания (0 = новичок)
    val rankTitleRu: String,
    val rankTitleBa: String,
    val nextTitleRu: String?,  // следующее звание (null = максимум)
    val nextTitleBa: String?,
    val nextAt: Int?,          // при скольки поездках следующее звание
    val toNext: Int,           // сколько поездок осталось до следующего звания
)
data class AdDto(
    val id: String, val title: String, val text: String, val button: String, val erid: String, val placement: String,
    val partner: String = "", val contact: String = "", val target: String = "", val image: String = "", val city: String = "",
)
/** Серверная статистика рекламы (показы/клики). */
data class AdStatsDto(val impressions: Int, val clicks: Int)

data class AdminAdDto(
    val id: String, val partner: String, val title: String, val text: String,
    val plan: String, val status: String, val placements: String, val erid: String,
    val endsAt: String?, val live: Boolean, val expired: Boolean,
    val button: String = "", val target: String = "", val cities: String = "",  // для предзаполнения формы при редактировании
    val rejectReason: String = "", val ownerId: Int? = null,  // модерация партнёрских: причина отказа, владелец
)
data class AdminAdsDto(val founderUsed: Int, val founderLimit: Int, val items: List<AdminAdDto>)

/** Объявление в кабинете ПАРТНЁРА (своё): статус модерации, причина отказа, тариф, оплата. */
data class MyAdDto(
    val id: String, val title: String, val text: String, val button: String, val target: String,
    val erid: String, val status: String, val rejectReason: String,
    val pkg: String, val pkgTitle: String, val budgetKop: Int, val periodDays: Int,
    val placements: String, val cities: String, val paid: Boolean, val submittedAt: String?,
)
/** Тариф размещения (из конфига сервера). */
data class AdPackageDto(val code: String, val title: String, val titleBa: String, val amountKop: Int, val periodDays: Int)
