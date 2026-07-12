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
    @Volatile private var userRole: String? = null

    // Анти-фрод (B8-1): стабильный идентификатор устройства (ANDROID_ID из Settings.Secure).
    // Уходит заголовком X-Device-Id со ВСЕМИ запросами: сервер ловит обход бана новым номером
    // и сигналит о входе с нового устройства. Никогда не логируем и не показываем в UI.
    @Volatile private var deviceId: String? = null
    private val refreshMutex = Mutex()   // не даём нескольким 401 рефрешить одновременно
    // Язык интерфейса для сообщений об ошибке. ApiClient — не Composable (appText недоступен),
    // поэтому язык синхронизируем из UI при переключении (YuldashApp). Иначе башкир видел бы
    // серверные/клиентские ошибки по-русски.
    @Volatile private var langBa = false
    /** Синхронизировать язык ошибок с UI (зовётся при смене языка). */
    fun setUiLanguageBashkir(ba: Boolean) { langBa = ba }

    /** Понятная общая ошибка по HTTP-коду (двуязычно) — когда сервер не дал перевод. */
    private fun genericByStatus(status: Int, ba: Boolean): String = when (status) {
        400, 422 -> if (ba) "Мәғлүмәттә хата. Тикшереп ҡабатла." else "Проверь введённые данные и повтори."
        401 -> if (ba) "Сессия бөттө. Яңынан ин." else "Сессия истекла. Войди заново."
        403 -> if (ba) "Был эшкә рөхсәт юҡ." else "Нет доступа к этому действию."
        404 -> if (ba) "Табылманы." else "Не найдено."
        409 -> if (ba) "Хәл үҙгәргән — экранды яңырт." else "Уже изменилось — обнови экран."
        429 -> if (ba) "Артыҡ йыш. Бер аҙ көт." else "Слишком часто — подожди немного."
        in 500..599 -> if (ba) "Сервер хатаһы. Аҙаҡ ҡабатла." else "Ошибка сервера. Попробуй позже."
        else -> if (ba) "Булманы. Ҡабатла." else "Не получилось. Повтори."
    }

    /** Разобрать тело ошибки в сообщение по текущему языку.
     *  detail={ru,ba} → берём по языку; строка → русскому как есть, башкиру — общий по коду;
     *  список (валидация FastAPI) / пусто → общий по коду. Русский флоу не меняется. */
    private fun errorMessage(status: Int, text: String): String {
        val detail = runCatching { JSONObject(text).opt("detail") }.getOrNull()
        when (detail) {
            is JSONObject -> {
                val ru = detail.optString("ru"); val ba = detail.optString("ba")
                if (ru.isNotBlank() || ba.isNotBlank())
                    return if (langBa && ba.isNotBlank()) ba else if (ru.isNotBlank()) ru else genericByStatus(status, langBa)
            }
            is String -> if (detail.isNotBlank())
                return if (langBa) genericByStatus(status, true) else detail
        }
        return genericByStatus(status, langBa)
    }

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
        pickupLat: Double? = null, pickupLng: Double? = null, onlyTrusted: Boolean = false,
    ) {
        bg.launch { publishRide(fromCity, toCity, departAt, seats, price, comment, petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner, recurrence, category, pickup, pickupLat, pickupLng, onlyTrusted = onlyTrusted) }
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
        // Анти-фрод (B8-1): ANDROID_ID стабилен на устройстве (сбрасывается только factory reset).
        deviceId = runCatching {
            android.provider.Settings.Secure.getString(
                app.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }
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
        userRole = p.getString("user_role", null)
        // Прогрев кеша статики из prefs → цены пакетов/буста видны мгновенно на холодном старте (сеть освежит по TTL).
        seedStatic("ad-packages", ::parseAdPackages)
        seedStatic("boost-plans", ::parseBoostPlans)
        // F11: локальные хранилища офлайн-паспорта поездки и очереди исходящих действий.
        TripPassStore.init(app)
        Outbox.init(app)
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

    /** Роль вошедшего клиента (passenger/driver/admin) — для честной подписи в профиле. null → неизвестна. */
    fun cachedRole(): String? = userRole?.takeIf { it.isNotBlank() }

    /** Кеш роли из /me — чтобы статичные баннеры показывали настоящую роль без своего запроса. */
    fun saveRole(r: String) {
        if (r.isBlank()) return
        userRole = r
        prefs?.edit()?.putString("user_role", r)?.apply()
    }

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
                        deviceId?.let { setRequestProperty("X-Device-Id", it) }   // анти-фрод (B8-1)
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
        userRole = null
        cachedUserId = null
        cachedUserIdForToken = null
        respCache.clear()   // сброс кеша ответов (иначе следующий юзер увидит чужой /me/referral/contacts)
        prefs?.edit()?.remove("token")?.remove("refresh_token")?.remove("user_name")?.remove("user_role")?.apply()
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

    /** Минимальная поддерживаемая версия приложения (force-update, B9b-1). Без авторизации.
     *  min_version_code=0 → проверка выключена. Ошибка/офлайн → вызывающий НЕ блокирует. */
    suspend fun minAppVersion(): Result<JSONObject> = call("GET", "/version/min", null, auth = false)

    /** Текущий пользователь по токену (проверка валидности сессии). Освежает имя клиента. */
    suspend fun me(): Result<JSONObject> = cachedGet("me", TTL_PERSONAL) {
        call("GET", "/me", null, auth = true)
            .onSuccess { o ->
                o.optString("name").takeIf { it.isNotBlank() }?.let(::saveName)
                o.optString("role").takeIf { it.isNotBlank() }?.let(::saveRole)
            }
    }

    // ---------- Поездки ----------

    /** Активные поездки с витриной водителя (для «Ближайших поездок»). */
    suspend fun getRides(): Result<List<RideDto>> =
        // auth=true: шлём токен (если есть) → сервер прячет заблокированных водителей. Без токена — аноним, как раньше.
        call("GET", "/rides", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }

    /** Одна поездка по id — публичная витрина (без ПДн). Для deep-link yulbash.ru/r/{id}. */
    suspend fun getRide(id: Int): Result<RideDto> =
        call("GET", "/rides/$id", null, auth = true).map { it.toRideDto() }

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
        date: String? = null,   // F4: «когда едем» — YYYY-MM-DD, только поездки этого дня
    ): Result<NearbyPage> {
        val params = buildList {
            fromCity?.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity?.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
            lat?.let { add("lat=$it") }
            lng?.let { add("lng=$it") }
            radiusKm?.let { add("radius_km=$it") }
            date?.takeIf { it.isNotBlank() }?.let { add("date=$it") }
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

    /**
     * F14: подсказки точек сбора по ориентирам города/села («у мечети», «автовокзал»).
     * Публичный справочник — auth не нужен. Пусто → показываем ручной выбор на карте.
     */
    suspend fun getPickupPoints(city: String): Result<List<PickupPointDto>> {
        if (city.isBlank()) return Result.success(emptyList())
        val path = "/pickup-points?city=" + enc(city.trim())
        return call("GET", path, null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toPickupPointDto() }
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
        onlyTrusted: Boolean = false,   // «только для своих» — поездку видят/берут лишь L3
        receiverName: String = "",   // посылка: кому отдать
        parcelSize: String = "",     // посылка: габарит/вес
        pickupPointId: Int? = null,  // F14: выбрана известная точка сбора из справочника → привязать
        partnerId: Int? = null,      // F22: клиника-назначение (category=hospital)
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
            .put("only_trusted", onlyTrusted)
            .put("pickup_point_id", pickupPointId ?: JSONObject.NULL)
            .put("receiver_name", receiverName)
            .put("parcel_size", parcelSize)
            .put("partner_id", partnerId ?: JSONObject.NULL),
        auth = true,
    ).map { }.onSuccess { Analytics.log("publish_ride") }

    // ---------- F22: клиники-партнёры (медцентры) ----------

    /** Справочник клиник-партнёров (только активные). Опц. фильтр по городу. Публичные данные. */
    suspend fun getMedicalPartners(city: String? = null): Result<List<MedicalPartnerDto>> {
        val path = "/medical-partners" + (city?.takeIf { it.isNotBlank() }?.let { "?city=" + enc(it) } ?: "")
        return call("GET", path, null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toMedicalPartnerDto() }
        }
    }

    /** Поездки «к этой клинике» — активные попутки с клиникой-назначением. Витрина публичная (без телефона). */
    suspend fun getRidesToPartner(partnerId: Int): Result<List<RideDto>> =
        call("GET", "/medical-partners/$partnerId/rides", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }

    /** Забронировать поездку. Возвращает id брони.
     * payMethod/payAmount — договорённость об оплате (ЗАПИСЬ, не платёж): как решили платить.
     * Способ по умолчанию — "negotiate" (договоримся); сумма опц. (null = сервер возьмёт цену поездки). */
    suspend fun book(rideId: Int, seats: Int, payMethod: String = "negotiate", payAmount: Int? = null): Result<Int> = call(
        "POST", "/bookings",
        JSONObject().put("ride_id", rideId).put("seats", seats)
            .put("pay_method", payMethod)
            .put("pay_amount", payAmount ?: JSONObject.NULL),
        auth = true,
    ).map { it.optInt("id") }.onSuccess { Analytics.log("booking") }

    /** Поправить договорённость об оплате брони (может любая сторона — пассажир/водитель).
     * Это ЗАПИСЬ «как договорились платить», а не платёж. */
    suspend fun setPayAgreement(bookingId: Int, payMethod: String? = null, payAmount: Int? = null): Result<Unit> = call(
        "POST", "/bookings/$bookingId/pay-agreement",
        JSONObject()
            .put("pay_method", payMethod ?: JSONObject.NULL)
            .put("pay_amount", payAmount ?: JSONObject.NULL),
        auth = true,
    ).map { }

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
                payMethod = o.optString("pay_method", "negotiate"),
                payAmount = if (o.isNull("pay_amount")) null else o.optInt("pay_amount"),
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
        onlyTrusted: Boolean = false,   // «только для своих» — заявку видят/берут лишь L3
        pickupPointId: Int? = null,   // F14: выбрана точка сбора из подсказок (пополняет справочник)
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
            .put("only_trusted", onlyTrusted)
            .put("comment", comment)
            .put("assisted", assisted)
            .put("pickup_point_id", pickupPointId ?: JSONObject.NULL)
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

    // ---------- Доверие «между своими» (уровни L0–L3, инвайты, согласия) ----------

    private fun JSONObject.toBilingual(): Bilingual = Bilingual(optString("ru"), optString("ba"))

    private fun JSONArray?.toBenefits(): List<Bilingual> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).toBilingual() }
    }

    /** Мой уровень доверия + что даёт следующий (только про себя, 152-ФЗ). */
    suspend fun getMyTrust(): Result<TrustSummaryDto> =
        call("GET", "/me/trust", null, auth = true).map { o ->
            val nextObj = o.optJSONObject("next")
            TrustSummaryDto(
                level = o.optInt("level"),
                title = (o.optJSONObject("title") ?: JSONObject()).toBilingual(),
                benefits = o.optJSONArray("benefits").toBenefits(),
                isInsider = o.optBoolean("is_insider"),
                invitedBy = if (o.isNull("invited_by")) null else o.optInt("invited_by"),
                canInvite = o.optBoolean("can_invite"),
                next = nextObj?.let {
                    TrustNextDto(
                        level = it.optInt("level"),
                        title = (it.optJSONObject("title") ?: JSONObject()).toBilingual(),
                        how = (it.optJSONObject("how") ?: JSONObject()).toBilingual(),
                        benefits = it.optJSONArray("benefits").toBenefits(),
                    )
                },
            )
        }

    /** Создать пригласительный код в круг «своих» (может только L2+). */
    suspend fun createInvite(): Result<InviteDto> =
        call("POST", "/invites", JSONObject(), auth = true).map { it.toInviteDto() }

    /** Мои пригласительные коды (только свои). */
    suspend fun getMyInvites(): Result<List<InviteDto>> =
        call("GET", "/invites/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toInviteDto() }
        }

    private fun JSONObject.toInviteDto() =
        InviteDto(code = optString("code"), usesLeft = optInt("uses_left"), createdAt = optString("created_at"))

    /** Активировать код → стать «своим» (L3). Возвращает новый уровень. */
    suspend fun redeemInvite(code: String): Result<Int> =
        call("POST", "/invites/redeem", JSONObject().put("code", code.trim().uppercase()), auth = true)
            .map { it.optInt("level") }
            .onSuccess { Analytics.log("trust_redeem_invite") }

    /** Мои зафиксированные согласия (оферта/политика/гео). */
    suspend fun getConsents(): Result<List<ConsentDto>> =
        call("GET", "/me/consents", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ConsentDto(kind = o.optString("kind"), grantedAt = o.optString("granted_at"))
            }
        }

    /** Зафиксировать согласие (идемпотентно, время первого не перезаписывается). */
    suspend fun setConsent(kind: String): Result<ConsentDto> =
        call("POST", "/me/consents", JSONObject().put("kind", kind), auth = true).map { o ->
            ConsentDto(kind = o.optString("kind"), grantedAt = o.optString("granted_at"))
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

    /** SOS. orderId — контекст такси-заказа (B7b-2): админ увидит маршрут и вторую сторону. */
    suspend fun sos(category: String, note: String, orderId: Int? = null): Result<Unit> {
        val body = JSONObject().put("category", category).put("note", note)
        if (orderId != null) body.put("order_id", orderId)
        return call("POST", "/sos", body, auth = true).map { }.onSuccess { Analytics.log("sos") }
    }

    /** «Поделиться поездкой» из такси-заказа (B7b-2): близкий получит SMS о маршруте и статусах.
     *  B7c: сервер возвращает token live-ссылки — отдаём готовый URL (null на старом сервере). */
    suspend fun shareInstantTrip(orderId: Int, contactId: Int): Result<String?> =
        call("POST", "/instant/orders/$orderId/share", JSONObject().put("contact_id", contactId), auth = true)
            .map { liveLinkOrNull(it) }
            .onSuccess { Analytics.log("instant_share_trip") }

    /** Live-ссылка близкого (B7c) из ответа share: {token} → "$BASE/t/{token}".
     *  База — тот же хост, что API (прод: https://yulbash.ru). Токена нет (старый сервер) → null. */
    private fun liveLinkOrNull(j: JSONObject): String? =
        j.optString("token", "").takeIf { it.isNotBlank() }?.let { "$BASE/t/$it" }

    /** F12 «Застрял на трассе»: координаты уходят доверенным контактам + запись в SOS-ленту админа.
     *  Уровень мягче паники SOS. Координаты необязательны (шлём хотя бы сигнал о помощи). */
    suspend fun roadsideHelp(bookingId: Int, lat: Double?, lng: Double?, note: String): Result<Unit> {
        val body = JSONObject().put("note", note)
        if (lat != null && lng != null) body.put("lat", lat).put("lng", lng)
        return call("POST", "/bookings/$bookingId/stuck", body, auth = true).map { }.onSuccess { Analytics.log("roadside_help") }
    }

    /** Запрос «перезвоните мне» → уведомление админу в Telegram (помощь пожилым/без интернета). */
    suspend fun requestCallback(note: String): Result<Unit> =
        call("POST", "/callback", JSONObject().put("note", note), auth = true).map { }.onSuccess { Analytics.log("callback_request") }

    // ---------- Жалобы и чёрный список ----------
    /** Пожаловаться (§9 Качество). category — из закрытого перечня (см. ReportCategoryUi);
     *  привязка к заказу/брони (orderId/bookingId) — сервер сам проверит участие и вычислит цель.
     *  Жалоба анонимна: цель НИКОГДА не видит автора. */
    suspend fun reportUser(
        targetUserId: Int? = null, reason: String = "", category: String = "other",
        orderId: Int? = null, bookingId: Int? = null,
    ): Result<Unit> {
        val body = JSONObject().put("reason", reason).put("category", category)
        if (targetUserId != null) body.put("target_user_id", targetUserId)
        if (orderId != null) body.put("order_id", orderId)
        if (bookingId != null) body.put("booking_id", bookingId)
        return call("POST", "/reports", body, auth = true).map { }
            .onSuccess { Analytics.log("report_create") }
    }

    /** Мои активные ограничения (§9, право объяснения): пауза такси/заказов — что, до когда,
     *  «попутка работает». Автор жалобы НЕ раскрывается. Пусто → items=[]. */
    suspend fun getMyRestrictions(): Result<RestrictionsDto> =
        call("GET", "/me/restrictions", null, auth = true).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            RestrictionsDto(
                items = (0 until arr.length()).map { i ->
                    val it = arr.getJSONObject(i)
                    RestrictionDto(
                        kind = it.optString("kind"),
                        reason = it.optString("reason"),
                        category = it.optString("category"),
                        categoryRu = it.optString("category_ru"),
                        categoryBa = it.optString("category_ba"),
                        until = if (it.isNull("until")) null else it.optString("until"),
                        titleRu = it.optString("title_ru"), titleBa = it.optString("title_ba"),
                        noteRu = it.optString("note_ru"), noteBa = it.optString("note_ba"),
                    )
                },
                supportRu = o.optString("support_ru"),
                supportBa = o.optString("support_ba"),
            )
        }

    /** Оценить вторую сторону завершённого быстрого заказа (1..5). Оценка анонимна —
     *  в рейтинг идёт только агрегат, «кто поставил» не раскрывается. */
    suspend fun rateInstantOrder(orderId: Int, stars: Int): Result<Unit> =
        call("POST", "/instant/orders/$orderId/rate", JSONObject().put("stars", stars), auth = true).map { }
            .onSuccess { Analytics.log("instant_order_rate") }

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
                RequestFeedDto(o.optInt("id"), o.optString("passenger_name"), o.optString("from_city"), o.optString("to_city"), o.optInt("seats"), o.optString("comment"), o.optBoolean("responded"), o.optString("passenger_avatar"), prefs, if (o.isNull("my_response_id")) null else o.optInt("my_response_id"))
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

    /** Водитель отзывает свой отклик — пока пассажир его не принял (после accept сервер вернёт 409). */
    suspend fun deleteResponse(responseId: Int): Result<Unit> =
        call("DELETE", "/responses/$responseId", null, auth = true).map { }.onSuccess { Analytics.log("withdraw_response") }

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
                AdminReportDto(
                    o.optInt("id"), o.optString("reporter_name"), o.optString("target_name"),
                    o.optString("target_phone"), o.optString("reason"), o.optString("created_at"),
                    category = o.optString("category", "other"),
                    status = o.optString("status", "new"),
                    resolution = if (o.isNull("resolution")) "" else o.optString("resolution"),
                    targetUserId = o.optInt("target_user_id"),
                )
            }
        }

    /** Админ: жалоба подтверждена (resolved). keepPause — для тяжёлой категории:
     *  оставить паузу такси (таймерную) или снять. Лестница §9 дальше считается сервером. */
    suspend fun adminResolveReport(id: Int, resolution: String, keepPause: Boolean = false): Result<Unit> =
        call("POST", "/admin/reports/$id/resolve",
            JSONObject().put("resolution", resolution).put("keep_pause", keepPause), auth = true).map { }

    /** Админ: жалоба отклонена (не подтвердилась) — пауза разбора снимается. */
    suspend fun adminRejectReport(id: Int): Result<Unit> =
        call("POST", "/admin/reports/$id/reject", JSONObject(), auth = true).map { }

    /** Админ: пауза такси водителю на N часов (продлевает). Попутка работает. */
    suspend fun adminQualityPause(userId: Int, hours: Int): Result<Unit> =
        call("POST", "/admin/quality/$userId/pause", JSONObject().put("hours", hours), auth = true).map { }

    /** Админ: снять паузу такси (разбор закончен / поставлено ошибочно). */
    suspend fun adminQualityUnpause(userId: Int): Result<Unit> =
        call("POST", "/admin/quality/$userId/unpause", JSONObject(), auth = true).map { }

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

    // ---------- Чат такси-заказа (B7b-1): та же механика, привязка к order_id ----------
    /** История чата заказа. После done/отмены сервер отдаёт read-only историю. */
    suspend fun getOrderMessages(orderId: Int): Result<List<MessageDto>> =
        call("GET", "/instant/orders/$orderId/messages", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> parseMessageDto(arr.getJSONObject(i)) }
        }

    /** Отправить текст в чат заказа (REST-фолбэк, когда WS лежит). */
    suspend fun sendOrderMessage(orderId: Int, text: String): Result<Unit> =
        call("POST", "/instant/orders/$orderId/messages", JSONObject().put("text", text), auth = true).map { }

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
                gender = o.optString("gender"),
                autocheckResult = o.optString("autocheck_result"),
                autocheckData = o.optString("autocheck_data"),
            )
        }

    /** Водитель: я на линии (доступен сейчас) / не на линии. */
    suspend fun setOnline(online: Boolean): Result<Unit> =
        call("POST", "/driver/online", JSONObject().put("online", online), auth = true).map { }

    /** F9: водитель по желанию (opt-in) указывает пол ("" снять / "female" / "male").
     *  Наружу раскрывается только сигнал «женщина за рулём» (driverIsWoman). */
    suspend fun setDriverGender(gender: String): Result<Unit> =
        call("POST", "/driver/gender", JSONObject().put("gender", gender), auth = true).map { }

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

    // --- F17: постоянные (регулярные) маршруты водителя ---
    private fun JSONObject.toDriverScheduleDto() = DriverScheduleDto(
        id = optInt("id"),
        driverId = optInt("driver_id"),
        fromCity = optString("from_city"),
        toCity = optString("to_city"),
        weekdays = optString("weekdays"),
        time = optString("time"),
        comment = optString("comment"),
        active = optBoolean("active", true),
    )

    /** Создать своё расписание (маршрут + дни недели CSV ISO 1..7 + время ЧЧ:ММ). */
    suspend fun createDriverSchedule(fromCity: String, toCity: String, weekdays: String, time: String, comment: String = ""): Result<DriverScheduleDto> =
        call(
            "POST", "/driver/schedule",
            JSONObject().put("from_city", fromCity).put("to_city", toCity)
                .put("weekdays", weekdays).put("time", time).put("comment", comment),
            auth = true,
        ).map { it.toDriverScheduleDto() }

    /** Мои регулярные маршруты (все, включая скрытые). */
    suspend fun getMyDriverSchedules(): Result<List<DriverScheduleDto>> =
        call("GET", "/driver/schedule", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toDriverScheduleDto() }
        }

    /** Публичные регулярные маршруты водителя (для профиля/поиска, без auth). */
    suspend fun getPublicDriverSchedules(driverId: Int): Result<List<DriverScheduleDto>> =
        call("GET", "/drivers/$driverId/schedule", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toDriverScheduleDto() }
        }

    /** Удалить своё расписание. */
    suspend fun deleteDriverSchedule(scheduleId: Int): Result<Unit> =
        call("DELETE", "/driver/schedule/$scheduleId", null, auth = true).map { }

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

    // Центр уведомлений: типизированная лента (непрочитанные сверху) + счётчик для бейджа.
    suspend fun getNotifications(): Result<NotifFeed> =
        call("GET", "/notifications", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NotifDto(
                    id = o.optInt("id"),
                    type = o.optString("type"),
                    titleRu = o.optString("title_ru"), titleBa = o.optString("title_ba"),
                    bodyRu = o.optString("body_ru"), bodyBa = o.optString("body_ba"),
                    refKind = o.optString("ref_kind"),
                    refId = if (o.isNull("ref_id")) null else o.optInt("ref_id"),
                    read = o.optBoolean("read"),
                    createdAt = o.optString("created_at"),
                )
            }
            NotifFeed(unread = obj.optInt("unread"), items = items)
        }

    /** Пометить уведомление(я) прочитанным: id=конкретное, null=все. Возврат — актуальный unread. */
    suspend fun markNotificationsRead(id: Int? = null): Result<Int> {
        val body = JSONObject()
        if (id != null) body.put("id", id) else body.put("all", true)
        return call("POST", "/notifications/read", body, auth = true).map { it.optInt("unread") }
    }

    // ---------- Подписка на маршрут «карауль поездку» (F13) ----------
    /** Подписаться на маршрут: как только появится подходящая поездка — придёт уведомление. */
    suspend fun createRouteWatch(
        fromCity: String,
        toCity: String,
        direction: String = "forward",     // forward | both (туда-обратно)
        watchDate: String? = null,         // ISO "yyyy-MM-dd'T'HH:mm:ss" — опц. конкретный день
    ): Result<Int> = call(
        "POST", "/route-watch",
        JSONObject()
            .put("from_city", fromCity)
            .put("to_city", toCity)
            .put("direction", direction)
            .apply { watchDate?.takeIf { it.isNotBlank() }?.let { put("watch_date", it) } },
        auth = true,
    ).map { it.optInt("id") }.onSuccess { Analytics.log("route_watch_create") }

    /** Мои активные подписки на маршрут (непротухшие). */
    suspend fun getRouteWatches(): Result<List<RouteWatchDto>> =
        call("GET", "/route-watch", null, auth = true).map { obj ->
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
    suspend fun deleteRouteWatch(id: Int): Result<Unit> =
        call("DELETE", "/route-watch/$id", null, auth = true).map { }

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

    /** Статистика по моим объявлениям (показы/клики/CTR/остаток срока). Приватность: только владелец (IDOR закрыт на сервере). */
    suspend fun getMyAdsStats(): Result<Map<String, MyAdStatsDto>> =
        call("GET", "/ads/mine/stats", null, auth = true).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            val out = mutableMapOf<String, MyAdStatsDto>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val id = s.optString("ad_id")
                out[id] = MyAdStatsDto(
                    adId = id,
                    impressions = s.optInt("impressions"),
                    clicks = s.optInt("clicks"),
                    ctr = s.optDouble("ctr", 0.0),
                    daysLeft = if (s.isNull("days_left")) null else s.optInt("days_left"),
                    endsAt = s.optString("ends_at").ifBlank { null },
                )
            }
            out
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

    /** Поделиться бронью попутки. B7c: возвращает live-ссылку близкого (null на старом сервере). */
    suspend fun shareTrip(bookingId: Int, contactId: Int): Result<String?> =
        call("POST", "/bookings/$bookingId/share", JSONObject().put("contact_id", contactId), auth = true)
            .map { liveLinkOrNull(it) }

    suspend fun setTripStatus(bookingId: Int, status: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/trip-status", JSONObject().put("status", status), auth = true).map { }

    /** Оценить вторую сторону поездки (1..5 звёзд) + опц. текстовый отзыв (≤500, идёт на модерацию). */
    suspend fun rateBooking(bookingId: Int, stars: Int, text: String = ""): Result<Unit> =
        call("POST", "/bookings/$bookingId/rate",
            JSONObject().put("stars", stars).apply { text.trim().take(500).let { if (it.isNotBlank()) put("text", it) } },
            auth = true).map { }

    /** Публичный профиль водителя: стаж, поездки, средний рейтинг, отзывы (после модерации). Без ПДн. */
    suspend fun getDriverPublic(driverId: Int): Result<DriverPublicDto> =
        call("GET", "/drivers/$driverId/public", null, auth = false).map { o ->
            val revArr = o.optJSONArray("reviews") ?: JSONArray()
            DriverPublicDto(
                id = o.optInt("id"),
                name = o.optString("name"),
                avatarUrl = o.optString("avatar_url"),
                verified = o.optBoolean("verified"),
                daysInService = o.optInt("days_in_service"),
                tripsCount = o.optInt("trips_count"),
                car = o.optString("car"),
                rating = if (o.isNull("rating")) null else o.optDouble("rating"),
                ratingCount = o.optInt("rating_count"),
                reviews = (0 until revArr.length()).map { i ->
                    val r = revArr.getJSONObject(i)
                    PublicReviewDto(
                        author = r.optString("author").ifBlank { "Аноним" },
                        stars = r.optInt("stars"),
                        text = r.optString("text"),
                        createdAt = r.optString("created_at"),
                    )
                },
            )
        }

    /** Отменить поездку (пассажир или водитель). Места возвращаются в поездку.
     *  Возврат: contact_then_cancel (B8-8) — отмена после открытия телефона/чата →
     *  UI показывает мягкий баннер «заверши поездку в приложении». */
    suspend fun cancelBooking(bookingId: Int): Result<Boolean> =
        call("POST", "/bookings/$bookingId/cancel", null, auth = true)
            .map { it.optBoolean("contact_then_cancel") }
            .onSuccess { Analytics.log("booking_cancel") }

    /** F2: водитель подтверждает бронь → пассажиру открываются телефон/точка сбора, приходит push. */
    suspend fun confirmBooking(bookingId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/confirm", JSONObject(), auth = true).map { }

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
    /** Поездки водителя. status=null/"active" — активные (как раньше, для Boost);
     *  "done"/"cancelled"/"all" — для раздела «Архив» в кабинете. */
    suspend fun getDriverRides(status: String? = null): Result<List<RideDto>> {
        val q = if (status.isNullOrBlank()) "" else "?status=$status"
        return call("GET", "/driver/rides$q", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }
    }

    /** F1: снять поездку (сломался/передумал). Сервер каскадно отменяет брони и шлёт пуши пассажирам. */
    suspend fun cancelRide(rideId: Int): Result<Unit> =
        call("POST", "/rides/$rideId/cancel", JSONObject(), auth = true).map { }

    /** F1: завершить рейс целиком (поездка → done, подтверждённые брони → done). */
    suspend fun completeRide(rideId: Int): Result<Unit> =
        call("POST", "/rides/$rideId/complete", JSONObject(), auth = true).map { }

    /** F3: правка своей поездки (null = поле не менять). POST-алиас /edit: HttpURLConnection не умеет PATCH.
     *  С активными бронями сервер разрешит только комментарий и цену ВНИЗ (иначе 409 с понятным текстом). */
    suspend fun editRide(rideId: Int, price: Int? = null, comment: String? = null): Result<Unit> {
        val body = JSONObject()
        price?.let { body.put("price", it) }
        comment?.let { body.put("comment", it) }
        return call("POST", "/rides/$rideId/edit", body, auth = true).map { }
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

    /**
     * «Поддержать Юлдаш» — добровольная поддержка платформы (доход платформы, НЕ водителю).
     * Деньги в копейках (int). Карта/СБП через ту же ЮKassa-инфру; без ключей — СБП-фолбэк
     * (реквизиты в ответе, как у boost/доната). confirmationUrl != null → открыть оплату картой.
     */
    suspend fun supportDonate(amountKop: Int): Result<BoostResultDto> =
        call("POST", "/support/donate", JSONObject().put("amount_kop", amountKop), auth = true).map { o ->
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

    /** Статус СВОЕГО платежа — клиент поллит после возврата из браузера ЮKassa (ON_RESUME экрана).
     *  Сервер при pending+yookassa сам перепроверяет оплату у ЮKassa и активирует boost (go-live). */
    suspend fun getPaymentStatus(paymentId: Int): Result<PaymentStatusDto> =
        call("GET", "/payments/$paymentId/status", null, auth = true).map { o ->
            PaymentStatusDto(
                paymentId = o.optInt("payment_id"),
                status = o.optString("status"),
                purpose = o.optString("purpose"),
                boostedUntil = o.optString("boosted_until").ifBlank { null },
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

    // ---------- Долг по комиссии за такси (Модель А «на доверии») ----------
    /** Долг водителя: сколько должен, до какой даты, реквизиты СБП Александра, блок такси. По своему токену. */
    suspend fun getDriverDebt(): Result<DriverDebtDto> =
        call("GET", "/driver/debt", null, auth = true).map { o ->
            val sbp = o.optJSONObject("sbp") ?: JSONObject()
            val wk = o.optJSONArray("weeks") ?: JSONArray()
            DriverDebtDto(
                unpaidKop = o.optInt("unpaid_kop"), pendingKop = o.optInt("pending_kop"),
                dueAt = o.optString("due_at").ifBlank { null },
                overdue = o.optBoolean("overdue"), blocked = o.optBoolean("blocked"),
                blockReason = o.optString("block_reason").ifBlank { null },
                thresholdKop = o.optInt("threshold_kop"),
                sbpPhone = sbp.optString("phone"), sbpName = sbp.optString("name"),
                weeks = (0 until wk.length()).map { i ->
                    val w = wk.getJSONObject(i)
                    DebtWeekDto(w.optString("week"), w.optInt("amount_kop"), w.optString("status"))
                },
            )
        }

    /** Водитель нажал «Я оплатил» → долг в pending (на подтверждение админом). */
    suspend fun declareDebtPaid(): Result<Int> =
        call("POST", "/driver/debt/paid", JSONObject(), auth = true).map { it.optInt("pending_kop") }

    /** Админ: долги на подтверждении (сгруппированы по водителю). */
    suspend fun getAdminDebts(): Result<List<AdminDebtDto>> =
        call("GET", "/admin/debts", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val wk = o.optJSONArray("weeks") ?: JSONArray()
                AdminDebtDto(
                    debtId = o.optInt("debt_id"), driverId = o.optInt("driver_id"),
                    driverName = o.optString("driver_name"), driverPhone = o.optString("driver_phone"),
                    amount = o.optInt("amount"),
                    weeks = (0 until wk.length()).map { j -> wk.optString(j) },
                )
            }
        }

    suspend fun confirmDebt(debtId: Int): Result<Unit> =
        call("POST", "/admin/debts/$debtId/confirm", JSONObject(), auth = true).map { }

    suspend fun rejectDebt(debtId: Int): Result<Unit> =
        call("POST", "/admin/debts/$debtId/reject", JSONObject(), auth = true).map { }

    // ---------- Быстрый заказ (такси-режим, Фаза 2) ----------
    // Отдельный поток от плановых поездок (Ride/Booking) — те не трогаем. Приватность: телефоны/имя
    // стороны сервер отдаёт пустыми до accept. Координаты heartbeat НЕ логируем.

    private fun instantBody(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String, toText: String, category: String,
    ): JSONObject = JSONObject()
        .put("from_lat", fromLat).put("from_lng", fromLng)
        .put("to_lat", toLat).put("to_lng", toLng)
        .put("from_text", fromText).put("to_text", toText)
        .put("category", category)

    /** Водитель «на линии» шлёт координаты (heartbeat ~раз в 12с) → Redis GEO. Координаты не логируем.
     *  ok=false, если Redis на сервере недоступен (заказ тогда «рядом никого», но запрос не падает). */
    /** «Пульс такси» (B7b-3, только админ): на линии, активные заказы, счётчики дня, по городам. */
    suspend fun getTaxiPulse(): Result<TaxiPulseDto> =
        call("GET", "/admin/taxi/pulse", null, auth = true).map { o ->
            val arr = o.optJSONArray("by_city") ?: JSONArray()
            TaxiPulseDto(
                driversOnline = o.optInt("drivers_online"),
                ordersActive = o.optInt("orders_active"),
                ordersToday = o.optInt("orders_today"),
                doneToday = o.optInt("done_today"),
                cancelledToday = o.optInt("cancelled_today"),
                noShowToday = o.optInt("no_show_today"),
                avgSearchSec = if (o.isNull("avg_search_sec_today")) null else o.optDouble("avg_search_sec_today"),
                byCity = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    TaxiPulseCityDto(c.optString("city"), c.optInt("online"), c.optInt("active"))
                },
            )
        }

    suspend fun instantPresence(lat: Double, lng: Double): Result<Boolean> =
        call("POST", "/instant/presence", JSONObject().put("lat", lat).put("lng", lng), auth = true).map { it.optBoolean("ok") }

    /** Fire-and-forget heartbeat (для таймера presence — не ждём ответа, не роняем экран при сбое сети). */
    fun fireInstantPresence(lat: Double, lng: Double) {
        bg.launch { call("POST", "/instant/presence", JSONObject().put("lat", lat).put("lng", lng), auth = true) }
    }

    /** Оценка цены ДО заказа. Сервер считает сам (клиенту не верит) — поля цены в запросе нет. */
    suspend fun instantEstimate(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String = "", toText: String = "", category: String = "standard",
    ): Result<InstantEstimateDto> =
        call("POST", "/instant/estimate", instantBody(fromLat, fromLng, toLat, toLng, fromText, toText, category), auth = true).map { o ->
            val note = o.optJSONObject("surge_note")
            val optArr = o.optJSONArray("options") ?: JSONArray()
            InstantEstimateDto(
                price = o.optInt("price"),
                distanceKm = o.optDouble("distance_km", 0.0),
                etaMin = o.optDouble("eta_min", 0.0),
                zone = o.optString("zone"),
                category = o.optString("category", category),
                tariffId = o.optInt("tariff_id"),
                surgeK = o.optDouble("surge_k", 1.0),
                surgeNoteRu = note?.optString("ru") ?: "",
                surgeNoteBa = note?.optString("ba") ?: "",
                options = (0 until optArr.length()).map { i ->
                    val c = optArr.getJSONObject(i)
                    InstantClassOption(category = c.optString("category"), price = c.optInt("price"))
                },
            )
        }

    /** Создать быстрый заказ → сервер считает цену и ищет водителя (сразу offered | expired). */
    suspend fun createInstantOrder(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String = "", toText: String = "", category: String = "standard",
    ): Result<InstantOrderDto> =
        call("POST", "/instant/orders", instantBody(fromLat, fromLng, toLat, toLng, fromText, toText, category), auth = true)
            .map { it.toInstantOrderDto() }.onSuccess { Analytics.log("instant_order_create") }

    /** Мои быстрые заказы (свежие сверху) — восстановить активный заказ при возврате на экран. */
    suspend fun getMyInstantOrders(limit: Int = 5): Result<List<InstantOrderDto>> =
        call("GET", "/instant/orders/mine?limit=$limit", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toInstantOrderDto() }
        }

    /** Детали заказа: пассажир поллит статус (searching→offered→accepted→arriving→onboard→done). */
    suspend fun getInstantOrder(id: Int): Result<InstantOrderDto> =
        call("GET", "/instant/orders/$id", null, auth = true).map { it.toInstantOrderDto() }

    /** Активный оффер для водителя (поллинг-фолбэк к пушу). null = нет входящего заказа. */
    suspend fun getDriverOffer(): Result<InstantOrderDto?> =
        call("GET", "/instant/driver/offer", null, auth = true).map { o ->
            if (o.isNull("offer")) null else o.optJSONObject("offer")?.toInstantOrderDto()
        }

    /** Водитель принимает оффер. Гонка/протух → 409 (ApiException) — экран покажет «оффер ушёл». */
    suspend fun instantAccept(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/accept", JSONObject(), auth = true).map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_order_accept") }

    /** Водитель пропускает оффер → matcher предлагает следующему. */
    suspend fun instantDecline(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/decline", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Водитель поехал к пассажиру: accepted → arriving. */
    suspend fun instantArrived(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/arrived", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Пассажир сел: arriving → onboard. */
    suspend fun instantOnboard(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/onboard", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Поездка завершена: onboard → done. */
    suspend fun instantDone(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/done", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Отмена заказа (пассажир до посадки / водитель после accept). Причина опциональна. */
    suspend fun instantCancel(id: Int, reason: String = ""): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/cancel", JSONObject().put("reason", reason), auth = true).map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_order_cancel") }

    // ---------- Такси-гейт + онбординг таксиста (580-ФЗ) ----------
    // Пассажирский гейт: доступно ли такси в его точке. Водительский гейт: заявка «Стать таксистом»
    // (самозанятость/разрешение/ОСАГО, возраст 20+, стаж 2+) → модерация админом → выход на линию.

    /** Доступно ли такси в точке (глобальный флаг + города). message — тёплый текст заглушки RU/BA. */
    suspend fun getTaxiAvailability(lat: Double, lng: Double): Result<TaxiAvailabilityDto> =
        call("GET", "/instant/availability?lat=$lat&lng=$lng", null, auth = true).map { o ->
            val msg = o.optJSONObject("message") ?: JSONObject()
            TaxiAvailabilityDto(
                enabled = o.optBoolean("enabled"),
                reason = o.optString("reason"),
                messageRu = msg.optString("ru"),
                messageBa = msg.optString("ba"),
                city = o.optNullableString("city") ?: "",
            )
        }

    /** Подать заявку «Стать таксистом» (580-ФЗ). Повторная подача после reject — тот же метод (заявка снова pending).
     *  Сервер валидирует возраст 20+/стаж 2+/ИНН 10–12 цифр → 400 с русским detail (покажем как есть). */
    suspend fun applyTaxi(
        inn: String, permitNumber: String, birthDate: String, licenseSinceYear: Int,
        permitPhotoUrl: String, osagoUrl: String,
        selfieUrl: String, criminalRecordUrl: String, carClass: String = "economy",
    ): Result<TaxiApplicationDto> =
        call(
            "POST", "/taxi/apply",
            JSONObject()
                .put("inn", inn).put("permit_number", permitNumber)
                .put("birth_date", birthDate).put("license_since_year", licenseSinceYear)
                .put("permit_photo_url", permitPhotoUrl).put("osago_url", osagoUrl)
                .put("selfie_url", selfieUrl).put("criminal_record_url", criminalRecordUrl)
                .put("car_class", carClass),   // §6: заявленный класс, админ подтверждает при approve
            auth = true,
        ).map { it.toTaxiApplicationDto() }.onSuccess { Analytics.log("taxi_apply") }

    /** Моя заявка таксиста. Не подавал → failure с ApiException(404) — экран трактует как «нет заявки». */
    suspend fun getMyTaxiApplication(): Result<TaxiApplicationDto> =
        call("GET", "/taxi/application", null, auth = true).map { it.toTaxiApplicationDto() }

    /** Админ: заявки таксистов. status: pending | approved | rejected | all. */
    suspend fun adminTaxiApplications(status: String = "pending"): Result<List<TaxiApplicationDto>> =
        call("GET", "/admin/taxi-applications?status=$status", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toTaxiApplicationDto() }
        }

    suspend fun adminApproveTaxiApplication(id: Int): Result<Unit> =
        call("POST", "/admin/taxi-applications/$id/approve", JSONObject(), auth = true).map { }

    suspend fun adminRejectTaxiApplication(id: Int, comment: String): Result<Unit> =
        call("POST", "/admin/taxi-applications/$id/reject", JSONObject().put("comment", comment), auth = true).map { }

    /** Админ: города, где включено такси. */
    suspend fun adminTaxiCities(): Result<List<TaxiCityDto>> =
        call("GET", "/admin/taxi-cities", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TaxiCityDto(o.optInt("id"), o.optString("city"), o.optBoolean("enabled"))
            }
        }

    /** Админ: добавить город такси (или обновить enabled существующего — сервер делает upsert по названию). */
    suspend fun adminAddTaxiCity(city: String, enabled: Boolean): Result<TaxiCityDto> =
        call("POST", "/admin/taxi-cities", JSONObject().put("city", city).put("enabled", enabled), auth = true).map { o ->
            TaxiCityDto(o.optInt("id"), o.optString("city"), o.optBoolean("enabled"))
        }

    suspend fun adminDeleteTaxiCity(id: Int): Result<Unit> =
        call("DELETE", "/admin/taxi-cities/$id", null, auth = true).map { }

    // ---------- Ранний доступ / лист ожидания (волна 2, §11 «Запуск») ----------

    /** Оставить номер в листе ожидания («сообщим, когда включим»). ПУБЛИЧНЫЙ — работает и без входа.
     *  Повторная подача того же номера обновляет город/роль на сервере (дублей не будет).
     *  role: passenger | driver. Город пустой → не отправляем (не затираем известный на сервере). */
    suspend fun joinWaitlist(phone: String, city: String, role: String): Result<Unit> =
        call(
            "POST", "/waitlist",
            JSONObject().put("phone", phone).put("role", role)
                .apply { if (city.isNotBlank()) put("city", city) },
            auth = false,
        ).map { }.onSuccess { Analytics.log("waitlist_join") }

    /** Админ: лист ожидания — счётчики (по всей базе) + записи (по фильтрам).
     *  city/role пустые = без фильтра; invited: null = все, true/false = позваны/ждут. */
    suspend fun getAdminWaitlist(city: String = "", role: String = "", invited: Boolean? = null): Result<AdminWaitlistDto> {
        val q = buildList {
            if (city.isNotBlank()) add("city=${enc(city)}")
            if (role.isNotBlank()) add("role=$role")
            if (invited != null) add("invited=$invited")
        }.joinToString("&")
        return call("GET", "/admin/waitlist" + (if (q.isBlank()) "" else "?$q"), null, auth = true).map { o ->
            val cityArr = o.optJSONArray("by_city") ?: JSONArray()
            val roles = o.optJSONObject("by_role") ?: JSONObject()
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            AdminWaitlistDto(
                total = o.optInt("total"),
                invited = o.optInt("invited"),
                byCity = (0 until cityArr.length()).map { i ->
                    val c = cityArr.getJSONObject(i)
                    c.optString("city") to c.optInt("count")
                },
                passengers = roles.optInt("passenger"),
                drivers = roles.optInt("driver"),
                items = (0 until itemsArr.length()).map { i ->
                    val e = itemsArr.getJSONObject(i)
                    WaitlistEntryDto(
                        id = e.optInt("id"),
                        phone = e.optString("phone"),
                        city = e.optString("city"),
                        role = e.optString("role"),
                        createdAt = e.optString("created_at"),
                        invitedAt = e.optNullableString("invited_at"),
                    )
                },
            )
        }
    }

    /** Админ: пометить волну — проставить invited_at выбранным (рассылку админ делает сам).
     *  Возвращает, сколько записей реально помечено (уже позванные не перетираются). */
    suspend fun adminWaitlistInvite(ids: List<Int>): Result<Int> =
        call("POST", "/admin/waitlist/invite", JSONObject().put("ids", JSONArray(ids)), auth = true)
            .map { it.optInt("invited") }

    // ---------- География: справочник НП + зона работы таксиста (волна 2) ----------
    // Справочник публичный (общеизвестные города, не перс.данные) — auth не нужен.

    /** Автоподсказки городов/райцентров: префиксный поиск по русскому И башкирскому имени. */
    suspend fun searchSettlements(q: String, limit: Int = 10): Result<List<SettlementDto>> =
        call("GET", "/settlements?q=${enc(q)}&limit=$limit", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toSettlementDto() }
        }

    /** Пресеты популярных межгород-маршрутов (Сибай–Магнитогорск, Баймак–Уфа…) — чипы в UI.
     *  Не путать с getPopularRoutes() (/popular-routes — живая статистика реальных поездок). */
    suspend fun getSettlementPopularRoutes(): Result<List<SettlementRouteDto>> = cachedGet("settlement-popular-routes", TTL_SLOW) {
        call("GET", "/settlements/popular-routes", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("routes") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SettlementRouteDto(
                    from = o.getJSONObject("from").toSettlementDto(),
                    to = o.getJSONObject("to").toSettlementDto(),
                )
            }
        }
    }

    /** Текущая зона работы таксиста (город / межгород / соседний регион). */
    suspend fun getInstantZone(): Result<InstantZoneDto> =
        call("GET", "/instant/zone", null, auth = true).map { it.toInstantZoneDto() }

    /** Выбор зоны работы: city (+work_city) | intercity (+опц. направление) | region.
     *  Только водитель с одобренной заявкой таксиста (сервер вернёт 403/409 понятной строкой). */
    suspend fun setInstantZone(workZone: String, workCity: String? = null, workDirectionId: Int? = null): Result<InstantZoneDto> =
        call(
            "POST", "/instant/zone",
            JSONObject()
                .put("work_zone", workZone)
                .put("work_city", workCity ?: JSONObject.NULL)
                .put("work_direction_id", workDirectionId ?: JSONObject.NULL),
            auth = true,
        ).map { it.toInstantZoneDto() }.onSuccess { Analytics.log("instant_zone_set") }

    /** Сводка смены таксиста (волна 2, §8 Отдых): сколько на линии, осталось, блок отдыха,
     *  когда разблокировка, использован ли «один попутчик домой». */
    suspend fun getTaxiWorkday(): Result<TaxiWorkdayDto> =
        call("GET", "/instant/workday", null, auth = true).map { o ->
            TaxiWorkdayDto(
                day = o.optString("day"),
                secondsOnline = o.optInt("seconds_online"),
                limitSec = o.optInt("limit_sec"),
                remainingSec = o.optInt("remaining_sec"),
                limitHours = o.optInt("limit_hours", 8),
                blocked = o.optBoolean("blocked"),
                unlockAt = o.optString("unlock_at").ifBlank { null },
                returnRideUsed = o.optBoolean("return_ride_used"),
            )
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
                deviceId?.let { setRequestProperty("X-Device-Id", it) }   // анти-фрод (B8-1)
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
                    Result.failure(ApiException(401, genericByStatus(401, langBa)))
                }
            } else {
                Result.failure(ApiException(code, errorMessage(code, text)))
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
                deviceId?.let { setRequestProperty("X-Device-Id", it) }   // анти-фрод (B8-1)
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
                else Result.failure(ApiException(401, genericByStatus(401, langBa)))
            } else {
                Result.failure(ApiException(code, errorMessage(code, text)))
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

/** Цена одного класса машины (Эконом/Комфорт) в options оценки — обе цены одним запросом. */
data class InstantClassOption(val category: String, val price: Int)

/** Оценка цены быстрого заказа (сервер считает сам по своей формуле).
 *  surgeK > 1.0 → час пик: плашка surgeNote (RU/BA) показывается ДО заказа, цена уже с k. */
data class InstantEstimateDto(
    val price: Int,
    val distanceKm: Double,
    val etaMin: Double,
    val zone: String,
    val category: String,
    val tariffId: Int,
    val surgeK: Double = 1.0,
    val surgeNoteRu: String = "",
    val surgeNoteBa: String = "",
    val options: List<InstantClassOption> = emptyList(),
)

/** Быстрый заказ (такси-режим) с сервера. Имя/телефон стороны приходят пустыми до accept (приватность). */
// «Пульс такси» (B7b-3): живая сводка для админа.
data class TaxiPulseCityDto(val city: String, val online: Int, val active: Int)

data class TaxiPulseDto(
    val driversOnline: Int,       // живой presence (heartbeat в Redis)
    val ordersActive: Int,        // searching/offered/accepted/arriving/onboard
    val ordersToday: Int,
    val doneToday: Int,
    val cancelledToday: Int,
    val noShowToday: Int,
    val avgSearchSec: Double?,    // средний подбор (created→accepted) сегодня; null = не было
    val byCity: List<TaxiPulseCityDto>,
)

data class InstantOrderDto(
    val id: Int,
    val status: String,           // created/searching/offered/accepted/arriving/onboard/done/cancelled/expired
    val role: String,             // "driver" | "passenger" — чья это витрина
    val fromLat: Double, val fromLng: Double,
    val toLat: Double, val toLng: Double,
    val fromText: String, val toText: String,
    val category: String,
    val priceEstimate: Int,
    val priceFinal: Int?,
    val distanceKm: Double,
    val etaMin: Double,
    val driverId: Int?,
    val offerExpiresAt: String?,  // ISO — когда протухнет текущий оффер (таймер водителя ведём локально)
    val cancelBy: String,         // "" | passenger | driver
    val cancelReason: String,
    val contactThenCancel: Boolean = false,  // B8-8: отмена после открытия телефона/чата → мягкий баннер
    // Деньги-правила (волна 2 §5): сурж/ожидание/отмены. Всё считает сервер, UI только показывает.
    val surgeK: Double,           // применённый сурж (зафиксирован при создании)
    val waitingStartedAt: String?, // ISO UTC — водитель нажал «Я на месте» (пошло ожидание)
    val waitingFeeKop: Int,       // платное ожидание, копейки (фиксируется на посадке)
    val cancelFeeKop: Int,        // штраф за позднюю отмену / no-show (Модель А: фиксация, не списание)
    val noShow: Boolean,          // «пассажир не вышел»
    val waitFreeMin: Int,         // бесплатное ожидание, мин (конфиг сервера)
    val waitFeeRubPerMin: Int,    // платное ожидание, ₽/мин (конфиг сервера)
    val noShowAt: String?,        // ISO UTC — с этого момента водителю доступна «Пассажир не вышел»
    val cancelFeeNowKop: Int,     // сколько стоила бы отмена ПРЯМО СЕЙЧАС (0 = бесплатно)
    // Пассажир глазами водителя (B7a-4): анонимный агрегат, доступен уже в оффере.
    val passengerRating: Double?, // null = новичок без оценок
    val passengerTrips: Int,      // завершённые такси-заказы + брони попутки
    // Раскрыто только после accept:
    val driverName: String,
    val driverCar: String,
    val driverVerified: Boolean,
    val driverRating: Double,
    val driverPhone: String,      // виден пассажиру после accept
    val passengerName: String,    // виден водителю после accept
    val passengerPhone: String,   // виден водителю после accept
) {
    /** Терминальный статус — заказ окончен (успех/отмена/протух). */
    val isTerminal: Boolean get() = status == "done" || status == "cancelled" || status == "expired"
    /** Идёт подбор водителя (машину ещё ищем). */
    val isSearching: Boolean get() = status == "created" || status == "searching" || status == "offered"
    /** Водитель назначен и заказ активен (телефон раскрыт). */
    val isActive: Boolean get() = status == "accepted" || status == "arriving" || status == "onboard"
}

private fun JSONObject.toInstantOrderDto() = InstantOrderDto(
    id = optInt("id"),
    status = optString("status"),
    role = optString("role"),
    fromLat = optDouble("from_lat", 0.0),
    fromLng = optDouble("from_lng", 0.0),
    toLat = optDouble("to_lat", 0.0),
    toLng = optDouble("to_lng", 0.0),
    fromText = optString("from_text"),
    toText = optString("to_text"),
    category = optString("category"),
    priceEstimate = optInt("price_estimate"),
    priceFinal = if (isNull("price_final")) null else optInt("price_final"),
    distanceKm = optDouble("distance_km", 0.0),
    etaMin = optDouble("eta_min", 0.0),
    driverId = if (isNull("driver_id")) null else optInt("driver_id"),
    offerExpiresAt = if (isNull("offer_expires_at")) null else optString("offer_expires_at").ifBlank { null },
    cancelBy = optString("cancel_by"),
    cancelReason = optString("cancel_reason"),
    contactThenCancel = optBoolean("contact_then_cancel"),
    surgeK = optDouble("surge_k", 1.0),
    waitingStartedAt = if (isNull("waiting_started_at")) null else optString("waiting_started_at").ifBlank { null },
    waitingFeeKop = optInt("waiting_fee_kop"),
    cancelFeeKop = optInt("cancel_fee_kop"),
    noShow = optBoolean("no_show"),
    waitFreeMin = optInt("wait_free_min", 5),
    waitFeeRubPerMin = optInt("wait_fee_rub_per_min", 5),
    noShowAt = if (isNull("no_show_at")) null else optString("no_show_at").ifBlank { null },
    cancelFeeNowKop = optInt("cancel_fee_now_kop"),
    passengerRating = if (isNull("passenger_rating")) null else optDouble("passenger_rating"),
    passengerTrips = optInt("passenger_trips"),
    driverName = optString("driver_name"),
    driverCar = optString("driver_car"),
    driverVerified = optBoolean("driver_verified"),
    driverRating = optDouble("driver_rating", 0.0),
    driverPhone = optString("driver_phone"),
    passengerName = optString("passenger_name"),
    passengerPhone = optString("passenger_phone"),
)

/** Доступность такси в точке (гейт пассажира). reason: ok | global_off | city_off.
 *  city — ближайший известный город (для предзаполнения листа ожидания); пусто = не определён. */
data class TaxiAvailabilityDto(
    val enabled: Boolean,
    val reason: String,
    val messageRu: String,
    val messageBa: String,
    val city: String = "",
)

/** Запись листа ожидания (админ). role: passenger | driver; invitedAt = null → ещё ждёт. */
data class WaitlistEntryDto(
    val id: Int,
    val phone: String,
    val city: String,
    val role: String,
    val createdAt: String,
    val invitedAt: String?,
)

/** Лист ожидания для админа: счётчики по всей базе + записи по текущим фильтрам. */
data class AdminWaitlistDto(
    val total: Int,
    val invited: Int,
    val byCity: List<Pair<String, Int>>,   // отсортировано по убыванию на сервере
    val passengers: Int,
    val drivers: Int,
    val items: List<WaitlistEntryDto>,
)

/** Заявка «Стать таксистом» (580-ФЗ). В админ-списке дополнительно приходят user_id/name/phone. */
data class TaxiApplicationDto(
    val id: Int,
    val status: String,          // pending | approved | rejected
    val inn: String,
    val permitNumber: String,
    val permitPhotoUrl: String,
    val osagoUrl: String,
    val selfieUrl: String,           // селфи с правами в руках (сверка лица) — Уровень 1
    val criminalRecordUrl: String,   // справка о несудимости (опц.)
    val birthDate: String,       // YYYY-MM-DD
    val licenseSinceYear: Int,
    val comment: String,         // комментарий админа при отклонении
    val createdAt: String,
    val reviewedAt: String?,
    // Только в списке админа (в личной заявке пустые):
    val userId: Int = 0,
    val name: String = "",
    val phone: String = "",
    val invitedBy: String? = null,   // «кто пригласил» (доверие между своими) — только в админ-списке
)

private fun JSONObject.toTaxiApplicationDto() = TaxiApplicationDto(
    id = optInt("id"),
    status = optString("status"),
    inn = optString("inn"),
    permitNumber = optString("permit_number"),
    permitPhotoUrl = optString("permit_photo_url"),
    osagoUrl = optString("osago_url"),
    selfieUrl = optString("selfie_url"),
    criminalRecordUrl = optString("criminal_record_url"),
    birthDate = optString("birth_date"),
    licenseSinceYear = optInt("license_since_year"),
    comment = optString("comment"),
    createdAt = optString("created_at"),
    reviewedAt = if (isNull("reviewed_at")) null else optString("reviewed_at").ifBlank { null },
    userId = optInt("user_id"),
    name = optString("name"),
    phone = optString("phone"),
    invitedBy = if (isNull("invited_by")) null else optString("invited_by").ifBlank { null },
)

/** Город, где включено такси (управляет админ). */
data class TaxiCityDto(val id: Int, val city: String, val enabled: Boolean)

/** Населённый пункт из справочника географии (волна 2).
 *  kind: city (город РБ) | district_center (райцентр) | neighbor (соседний регион). */
data class SettlementDto(
    val id: Int,
    val nameRu: String,
    val nameBa: String?,      // черновой башкирский; null = показываем русское
    val region: String,
    val kind: String,
    val lat: Double,
    val lng: Double,
)

/** Зона работы таксиста: city | intercity | region; null = не выбрана (беру всё рядом). */
data class InstantZoneDto(
    val workZone: String?,
    val workCity: String?,
    val workDirectionId: Int?,
    val workDirection: SettlementDto?,
)

/** Смена такси за местный день (волна 2, §8 Отдых): прогресс к 8-часовому лимиту и блок отдыха. */
data class TaxiWorkdayDto(
    val day: String,                 // местный день учёта, ISO ("2026-07-10")
    val secondsOnline: Int,          // такси-время на линии за день, секунд
    val limitSec: Int,               // лимит смены, секунд (8ч)
    val remainingSec: Int,           // сколько осталось до лимита, секунд
    val limitHours: Int,             // лимит смены, часов (для текстов «из 8»)
    val blocked: Boolean,            // отдых: такси закрыто до unlockAt
    val unlockAt: String?,           // когда снова на линию (ISO, UTC-наивное), null если не заблокирован
    val returnRideUsed: Boolean,     // «один попутчик домой» уже опубликован
)

/** Пресет популярного маршрута (Сибай–Магнитогорск…) — чип, заполняющий «откуда/куда». */
data class SettlementRouteDto(val from: SettlementDto, val to: SettlementDto)

private fun JSONObject.toSettlementDto() = SettlementDto(
    id = optInt("id"),
    nameRu = optString("name_ru"),
    nameBa = optNullableString("name_ba"),
    region = optString("region"),
    kind = optString("kind"),
    lat = optDouble("lat"),
    lng = optDouble("lng"),
)

private fun JSONObject.toInstantZoneDto() = InstantZoneDto(
    workZone = optNullableString("work_zone"),
    workCity = optNullableString("work_city"),
    workDirectionId = if (isNull("work_direction_id")) null else optInt("work_direction_id"),
    workDirection = optJSONObject("work_direction")?.toSettlementDto(),
)

data class ReviewItem(val id: Int, val name: String, val city: String, val stars: Int, val text: String)

/** Двуязычная пара RU/BA, как её отдаёт бэкенд доверия (title/benefit/how). */
data class Bilingual(val ru: String, val ba: String)

/** Следующий уровень доверия: что он даёт и как его получить. */
data class TrustNextDto(val level: Int, val title: Bilingual, val how: Bilingual, val benefits: List<Bilingual>)

/** Мой уровень доверия L0–L3 (GET /me/trust). */
data class TrustSummaryDto(
    val level: Int,
    val title: Bilingual,
    val benefits: List<Bilingual>,
    val isInsider: Boolean,
    val invitedBy: Int?,
    val canInvite: Boolean,
    val next: TrustNextDto?,
)

/** Пригласительный код в круг «своих». */
data class InviteDto(val code: String, val usesLeft: Int, val createdAt: String)

/** Зафиксированное согласие (152-ФЗ): вид + время. */
data class ConsentDto(val kind: String, val grantedAt: String)

/** Поездка с витрины сервера (бэкенд RideOut: поездка + данные водителя). */
data class PriceHintDto(val avg: Int, val count: Int)

/** Страница «Ближайших»: показанные + всего на маршруте (для кнопки «Показать ещё»). */
data class NearbyPage(val items: List<RideDto>, val total: Int)

/** F14: точка сбора по ориентиру (публичный справочник). titleRu/titleBa — двуязычное название. */
data class PickupPointDto(
    val id: Int,
    val city: String,
    val titleRu: String,
    val titleBa: String,
    val lat: Double?,
    val lng: Double?,
    val usageCount: Int,
)

/** JSON точки сбора с сервера → PickupPointDto. */
private fun JSONObject.toPickupPointDto() = PickupPointDto(
    id = optInt("id"),
    city = optString("city"),
    titleRu = optString("title_ru"),
    titleBa = optString("title_ba"),
    lat = if (isNull("lat")) null else optDouble("lat"),
    lng = if (isNull("lng")) null else optDouble("lng"),
    usageCount = optInt("usage_count"),
)

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
    driverId = optInt("driver_id"),
    driverName = optString("driver_name"),
    driverRating = optDouble("driver_rating", 5.0),
    driverVerified = optBoolean("driver_verified"),
    driverCar = optString("driver_car"),
    driverAvatar = optString("driver_avatar"),
    driverOnline = optBoolean("driver_online"),
    driverTrips = optInt("driver_trips"),
    driverSince = optString("driver_since"),
    driverIsWoman = optBoolean("driver_is_woman"),
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
    status = optString("status", "active"),
    partnerId = if (isNull("partner_id")) null else optInt("partner_id"),
)

private fun JSONObject.toMedicalPartnerDto() = MedicalPartnerDto(
    id = optInt("id"),
    name = optString("name"),
    city = optString("city"),
    address = optString("address"),
    lat = if (isNull("lat")) null else optDouble("lat"),
    lng = if (isNull("lng")) null else optDouble("lng"),
    description = optString("description"),
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
    val driverId: Int = 0,            // id водителя → публичный профиль
    val driverName: String,
    val driverRating: Double,
    val driverVerified: Boolean,
    val driverCar: String,
    val driverAvatar: String = "",
    val driverOnline: Boolean = false,
    val driverTrips: Int = 0,         // F8: завершённых поездок водителя (бейдж «N поездок»)
    val driverSince: String = "",     // F8: месяц регистрации "YYYY-MM" (бейдж «С нами с …»)
    val driverIsWoman: Boolean = false,   // F9: водитель — женщина (opt-in сигнал для бейджа)
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
    val status: String = "active",    // active / done / cancelled (для раздела «Архив»)
    val partnerId: Int? = null,       // F22: клиника-назначение (для category=hospital)
)

/** F22: клиника-партнёр (медцентр) — точка назначения поездки «в больницу». Только логистика,
 *  публичные данные организации. Никаких мед.данных пациента. */
data class MedicalPartnerDto(
    val id: Int,
    val name: String,
    val city: String,
    val address: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val description: String = "",
)

/** Публичный профиль водителя (без ПДн: без телефона). Тапом с карточки поездки. */
data class DriverPublicDto(
    val id: Int,
    val name: String,
    val avatarUrl: String,
    val verified: Boolean,
    val daysInService: Int,       // стаж в Юлдаше в днях (клиент форматирует в «X лет/мес»)
    val tripsCount: Int,          // завершённых поездок как водитель
    val car: String,              // марка+модель (без госномера)
    val rating: Double?,          // средний рейтинг (null — оценок ещё нет)
    val ratingCount: Int,
    val reviews: List<PublicReviewDto>,
)

/** Один текстовый отзыв в публичном профиле (прошёл модерацию). */
data class PublicReviewDto(
    val author: String,
    val stars: Int,
    val text: String,
    val createdAt: String,
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
    val gender: String = "",             // "" не указан / female / male — виден только самому водителю (opt-in)
    val autocheckResult: String = "",   // "" / pass / needs_human / reject / error
    val autocheckData: String = "",      // JSON: распознанные поля + коды причин
)

/** F17 — постоянный (регулярный) маршрут водителя: «Баймаҡ→Уфа по пятницам в 8:00».
 *  weekdays — дни недели ISO 1=Пн..7=Вс через запятую (напр. "1,3,5"). */
data class DriverScheduleDto(
    val id: Int,
    val driverId: Int,
    val fromCity: String,
    val toCity: String,
    val weekdays: String,
    val time: String,
    val comment: String = "",
    val active: Boolean = true,
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
    val payMethod: String = "negotiate",   // договорённость об оплате (запись, не платёж): cash/sbp/negotiate
    val payAmount: Int? = null,            // сумма договорённости, ₽ (опц.)
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
data class AdminReportDto(
    val id: Int, val reporterName: String, val targetName: String, val targetPhone: String,
    val reason: String, val createdAt: String,
    // §9 Качество (дефолты — совместимость со старыми вызовами/тестами).
    val category: String = "other",      // rude|kicked_out|dangerous_driving|price_fraud|dirty_car|late|safety_threat|no_show|damage|unpaid|other
    val status: String = "new",          // new|reviewing|resolved|rejected
    val resolution: String = "",
    val targetUserId: Int = 0,
)

/** Активное ограничение пользователя (§9, /me/restrictions). Тексты приходят с сервера
 *  на двух языках — экран выбирает по LocalAppLanguage. Автор жалобы НЕ раскрывается. */
data class RestrictionDto(
    val kind: String,          // taxi_pause | orders_pause
    val reason: String,        // reports | review | admin | strikes
    val category: String = "",
    val categoryRu: String = "", val categoryBa: String = "",
    val until: String? = null, // ISO; null = «до разбора» (решает человек)
    val titleRu: String = "", val titleBa: String = "",
    val noteRu: String = "", val noteBa: String = "",
)

data class RestrictionsDto(
    val items: List<RestrictionDto> = emptyList(),
    val supportRu: String = "", val supportBa: String = "",
)
data class RequestFeedDto(val id: Int, val passengerName: String, val from: String, val to: String, val seats: Int, val comment: String, val responded: Boolean, val passengerAvatar: String = "", val prefs: List<String> = emptyList(), val myResponseId: Int? = null)
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
    val flag: String = "",           // анти-фишинг (B8-6): "warn" → плашка-предупреждение получателю
    val fromAdmin: Boolean = false,  // официальность (B8-9): бейдж «Юлдаш ✓» у сообщений админа/системы
)

internal fun parseMessageDto(o: JSONObject): MessageDto =
    MessageDto(
        id = o.optInt("id"),
        text = o.optString("text"),
        senderId = o.optInt("sender_id"),
        voiceUrl = o.optNullableString("voice_url"),
        deleted = o.optBoolean("deleted"),
        edited = o.optBoolean("edited"),
        flag = o.optString("flag"),
        fromAdmin = o.optBoolean("from_admin"),
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
/** Уведомление Центра уведомлений (типизированное, двуязычное). ref_kind/ref_id — deep-link. */
data class NotifDto(
    val id: Int,
    val type: String,              // booking / ride / system / message
    val titleRu: String, val titleBa: String,
    val bodyRu: String, val bodyBa: String,
    val refKind: String, val refId: Int?,
    val read: Boolean,
    val createdAt: String,         // ISO-8601 UTC
)

/** Лента уведомлений: непрочитанные сверху + счётчик непрочитанного (бейдж). */
data class NotifFeed(val unread: Int, val items: List<NotifDto>)

/** Подписка на маршрут «карауль поездку» (F13). */
data class RouteWatchDto(
    val id: Int,
    val fromCity: String,
    val toCity: String,
    val direction: String,          // forward | both
    val watchDate: String? = null,  // ISO, если задан конкретный день
)

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

/** Статус платежа для поллинга после возврата из браузера ЮKassa (go-live boost). */
data class PaymentStatusDto(val paymentId: Int, val status: String, val purpose: String, val boostedUntil: String?)

/** Заявка на оплату (буст/донат) в админ-очереди подтверждения. */
data class PendingPaymentDto(
    val paymentId: Int, val purpose: String, val tier: String, val amount: Int,
    val rideId: Int?, val payerName: String, val payerPhone: String, val createdAt: String, val note: String = "",
)
/** Счётчик подтверждённых оплат (донаты/буст) для админ-кабинета. */
data class PaymentsSummaryDto(val donateCount: Int, val donateSum: Int, val boostCount: Int, val boostSum: Int)

/** Разбивка долга по неделе (для наглядности в кабинете водителя). */
data class DebtWeekDto(val week: String, val amountKop: Int, val status: String)
/** Долг водителя по комиссии за такси (Модель А «на доверии»): сколько должен, срок, реквизиты СБП, блок. */
data class DriverDebtDto(
    val unpaidKop: Int, val pendingKop: Int, val dueAt: String?, val overdue: Boolean,
    val blocked: Boolean, val blockReason: String?, val thresholdKop: Int,
    val sbpPhone: String, val sbpName: String, val weeks: List<DebtWeekDto>,
) {
    val unpaidRub: Int get() = unpaidKop / 100
    val pendingRub: Int get() = pendingKop / 100
}
/** Долг водителя в админ-очереди подтверждения (сгруппирован по водителю). */
data class AdminDebtDto(
    val debtId: Int, val driverId: Int, val driverName: String, val driverPhone: String,
    val amount: Int, val weeks: List<String>,
)
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

/** Статистика СВОЕГО объявления для кабинета рекламодателя: показы/клики/CTR/остаток срока. */
data class MyAdStatsDto(
    val adId: String, val impressions: Int, val clicks: Int, val ctr: Double,
    val daysLeft: Int?, val endsAt: String?,
)
