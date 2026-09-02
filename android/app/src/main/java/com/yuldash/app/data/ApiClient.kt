package com.yuldash.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yuldash.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URL
import java.net.UnknownHostException

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

    /**
     * Тест-хук: короткие таймауты и без повторов. В проде null → всё как было.
     *
     * Зачем. В проде запрос обязан быть терпеливым: 15 секунд на соединение, 15 на чтение и
     * до трёх попыток с паузами — человек в дороге на слабой связи должен доехать до ответа,
     * а не увидеть ошибку на первой кочке. В тестах это оборачивается против нас: один вызов
     * живёт до полутора минут, а тест ждёт двадцать секунд. Любая заминка выглядит одинаково —
     * экран навсегда на «Загрузка…», ошибку показать не успевают, и разбирать нечего.
     *
     * Хуже того: все тесты идут в ОДНОЙ виртуальной машине, поэтому брошенный запрос переживает
     * свой тестовый класс и стучится уже на сервер соседнего (адрес перечитывается на каждой
     * попытке). С коротким таймаутом он умирает за секунду и до соседа не доживает.
     */
    internal var testTimeoutMs: Int? = null

    private val connectMs: Int get() = testTimeoutMs ?: 15000
    private val readMs: Int get() = testTimeoutMs ?: 15000

    /**
     * Тест-хук: оборвать всё фоновое и вернуть клиента в исходное состояние.
     *
     * `bg` — область корутин уровня процесса, и её никто никогда не отменял: в приложении это
     * верно (живёт столько же, сколько процесс), а в тестах означало, что «выстрелил и забыл»
     * запрос из раннего класса продолжает повторяться, когда давно идёт другой класс. Зовётся
     * из `@Before`/`@After` тестов, в проде не вызывается.
     */
    internal fun resetForTest() {
        bg.coroutineContext.cancelChildren()
        testBaseUrl = null
    }

    /**
     * Тест-хук: куда писать след сетевого вызова. В проде null → ничего не пишется.
     *
     * Нужен для разбора мигающих тестов админ-экранов. Измерения уже доказали: сервер отдаёт
     * правильный ответ за 6 мс, потоки свободны, а прокрутка очередей текст не проявляет —
     * то есть корутина экрана умирает где-то между отправкой запроса и показом данных.
     * Здесь фиксируется граница ответственности клиента: вошли в вызов, вышли из вызова
     * и с каким итогом. Если вышли успешно, а экран пуст — виноват код экрана.
     */
    internal var testTrace: ((String) -> Unit)? = null

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
        // Файл не влез. Ответ может прийти и не от нашего кода: nginx режет слишком большое
        // тело раньше приложения и отдаёт голый 413 без пояснения. Без своего текста человек
        // видел «Не получилось. Повтори.» — повторял, снова упирался и не понимал, что дело
        // в размере снимка (аудит 2026-08-08, волна 101).
        413 -> if (ba) "Файл артыҡ ҙур. Еңелерәк һүрәт һайла йәки яңынан төшөр."
               else "Файл слишком большой. Выбери фото полегче или сними заново."
        429 -> if (ba) "Артыҡ йыш. Бер аҙ көт." else "Слишком часто — подожди немного."
        in 500..599 -> if (ba) "Сервер хатаһы. Аҙаҡ ҡабатла." else "Ошибка сервера. Попробуй позже."
        else -> if (ba) "Булманы. Ҡабатла." else "Не получилось. Повтори."
    }

    /** Разобрать тело ошибки в сообщение по текущему языку.
     *  detail={ru,ba} → берём по языку; строка → русскому как есть, башкиру — общий по коду;
     *  список (валидация FastAPI) / пусто → общий по коду. Русский флоу не меняется. */
    /** Машинная причина отказа из тела (`detail.code`). Нет — пустая строка. */
    private fun detailCode(text: String): String =
        runCatching { (JSONObject(text).opt("detail") as? JSONObject)?.optString("code") }
            .getOrNull().orEmpty()

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
    // Каталог кеша приложения — нужен, чтобы убрать записанные голосовые при выходе (волна 75).
    @Volatile private var cacheDir: java.io.File? = null

    /** Нужен, чтобы выход добрался до ВСЕХ ящиков настроек, а не только до того, где токен (волна 109). */
    @Volatile private var appCtx: Context? = null

    /** true → Android Keystore недоступен и токены лежат в НЕзашифрованных prefs.
     *  Диагностика: устанавливается в [init], дублируется предупреждением в Sentry. */
    @Volatile internal var secureStorageUnavailable: Boolean = false
        private set

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

    /** Best-effort: наполнить «Недавние» точкой назначения после создания заказа/заявки.
     *  На долгоживущем scope — переживает уход с экрана, заказ не блокирует. */
    fun fireAddRecentPlace(address: String, lat: Double, lng: Double) {
        if (address.isBlank()) return
        bg.launch { addRecentPlace(address, lat, lng) }
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
        cacheDir = app.cacheDir
        appCtx = app
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
        // Keystore недоступен (бывает на «кривых» прошивках) → токены легли бы в ОТКРЫТЫЙ xml,
        // и раньше это происходило совершенно молча. Вход не ломаем (иначе человек не войдёт
        // вообще), но факт делаем видимым: флаг + сигнал в Sentry без единого байта PII.
        secureStorageUnavailable = secure == null
        if (secure == null) {
            runCatching {
                io.sentry.Sentry.captureMessage(
                    "Secure token storage unavailable — falling back to plaintext prefs",
                    io.sentry.SentryLevel.WARNING,
                )
            }
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

    // Сервер недостижим: связь оборвалась ДО получения ответа, все повторы исчерпаны.
    //
    // Зачем глобальный сигнал, а не проверка на каждом экране. Аудит 2026-08-04 нашёл 84 места,
    // где ответ сервера читается через `.onSuccess {}` БЕЗ `.onFailure` — при обрыве связи там
    // молча ничего не происходит, и экран показывает «Заявок пока нет» вместо «нет связи».
    // Это не косметика: человек с активной заявкой видит, что заявок у него нет. Приложение врёт.
    //
    // Чинить 84 места по одному — долго и всё равно не удержится: следующий экран принесёт
    // 85-е. Поэтому сигнал ставится в ЕДИНСТВЕННОЙ точке, через которую проходят все запросы
    // (`call()`), и приложение показывает честную плашку поверх любого экрана. Локальные
    // состояния ошибки это не отменяет — они точнее, просто теперь их отсутствие не врёт.
    val serverUnreachable = kotlinx.coroutines.flow.MutableStateFlow(false)

    // Сессия протухла (refresh-токен мёртв) → UI покажет «войди снова» и уйдёт на Login.
    // Иначе экраны молча деградируют в «пусто». Ставится в 401-ветке ниже, гасится в UI после показа.
    val sessionExpired = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Лёгкая проба «сервер жив?» для плашки «нет связи»: /health без авторизации.
     *  Нужна, потому что флаг гаснет только на успешном ответе, а экран может вообще
     *  не делать запросов (онбординг, вход) — тогда плашка висела бы после возврата сети. */
    suspend fun healthOk(): Boolean =
        call("GET", "/health", null, auth = false, retryOnNetwork = false).isSuccess

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
        val push = prefs?.getString("push_token", null)
        if (!t.isNullOrBlank()) {
            bg.launch {
                // Сперва отвязать устройство от пушей, потом гасить токен: после /auth/logout
                // тот же Bearer уже невалиден и /push/unregister вернёт 401.
                // Иначе на общем телефоне вышедший продолжал получать чужие пуши (брони/чат/SOS).
                if (!push.isNullOrBlank()) runCatching { postWithToken("/push/unregister", JSONObject().put("token", push), t) }
                runCatching { postWithToken("/auth/logout", null, t) }
            }
        }
        // Локальная очистка — синхронно, чтобы UI сразу видел «вышел».
        clearLocalSession()
    }

    /** POST конкретным токеном, минуя общий [call] (нужен для выхода: сессия уже стирается локально). */
    private fun postWithToken(path: String, body: JSONObject?, bearer: String) {
        val conn = (URL("$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectMs
            readTimeout = readMs
            deviceId?.let { setRequestProperty("X-Device-Id", it) }   // анти-фрод (B8-1)
            setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        conn.responseCode
        conn.disconnect()
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
        // Диск тоже чистим, иначе следующий пользователь телефона получает чужое наследство:
        //  • push_token — чтобы не зарегистрировать устройство прошлого владельца заново;
        //  • паспорта поездок — там ИМЯ И ТЕЛЕФОН пассажиров (чужие ПДн на диске, 152-ФЗ);
        //  • очередь исходящих — иначе сообщения прошлого юзера уйдут ОТ НОВОГО аккаунта.
        // Список ключей — в одном месте (`SessionKeys`), потому что забытый ключ здесь означает
        // кусок чужой жизни, оставшийся следующему владельцу телефона. Сторож
        // `SessionKeysGuardTest` требует решения для каждого нового ключа (волна 31).
        prefs?.edit()?.apply {
            SessionKeys.CLEARED_ON_LOGOUT.forEach { remove(it) }
        }?.apply()
        // Ящиков настроек на диске несколько, и раньше выход ходил только в тот, где лежит токен
        // (волна 109). В остальных оставались «я вожу», номер брони и номера посылок — а по двум
        // последним фоновый сервис умеет ВОСКРЕСНУТЬ и снова начать слать GPS уже под новым
        // владельцем телефона. Адрес каждого ключа теперь записан в `SessionKeys.CLEARED_BY_FILE`.
        appCtx?.let { ctx ->
            SessionKeys.CLEARED_BY_FILE.forEach { (file, keys) ->
                if (file == SessionKeys.MAIN_PREFS) return@forEach   // основной ящик закрыт выше
                ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
                    .apply { keys.forEach { remove(it) } }
                    .apply()
            }
        }
        TripPassStore.clearAll()
        Outbox.clearAll()
        clearVoiceCache()
    }

    /**
     * Стереть записанные голосовые из кеша приложения.
     *
     * Зачем (аудит 2026-08-08, волна 75). Голос человека — такие же личные данные, как его
     * телефон. Каждое голосовое сообщение записывается файлом в кеш приложения, уходит
     * на сервер — и остаётся лежать на устройстве: удалять его никто не пытался. За полгода
     * переписки в кеше копится весь архив сказанного вслух, и выход из аккаунта его не трогал.
     * Телефон переходит мужу, сыну, покупателю — записи прежнего владельца едут с ним.
     *
     * Файл нужен ровно до конца отправки, поэтому чистим и сразу после неё (`dropVoiceFile`),
     * и на выходе — второй проход на случай, когда отправка не удалась и файл остался.
     */
    fun clearVoiceCache() {
        runCatching {
            cacheDir?.listFiles { f -> f.isFile && f.name.startsWith("voice_") }?.forEach { it.delete() }
        }
    }

    /** Убрать одну запись сразу после отправки — она уже на сервере, на телефоне не нужна. */
    fun dropVoiceFile(path: String) {
        runCatching { java.io.File(path).takeIf { it.name.startsWith("voice_") }?.delete() }
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
            // V12: чистим сессионные кеши на ЛОГИНЕ (не только на логауте) — иначе me/contacts/referral
            // могут до TTL отдать данные прошлого аккаунта, если logout не отработал (edge: 401 при пустом refresh).
            respCache.clear(); cachedUserId = null; cachedUserIdForToken = null
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

    /** Сохранить родной город (name_ru из справочника; пустая строка сбрасывает). */
    suspend fun updateCity(city: String): Result<Unit> =
        call("POST", "/me/update", JSONObject().put("city", city.trim()), auth = true).onSuccess { invalidate("me") }.map { }

    /**
     * Пол: "" (не указан) | "female" | "male". Нужен ровно для одного — чтобы отметка
     * «Только женщины» была настоящей: сервер пускает в такую поездку только женщин
     * (и за руль, и в салон). Без этого поля обещание проверить нечем (аудит 2026-08-08).
     * Наружу пол не отдаётся никому: другие видят лишь бейдж «женщина за рулём».
     */
    suspend fun updateGender(gender: String): Result<Unit> {
        val g = gender.trim().lowercase()
        if (g != "" && g != "female" && g != "male") return Result.success(Unit)
        return call("POST", "/me/update", JSONObject().put("gender", g), auth = true)
            .onSuccess { invalidate("me") }.map { }
    }

    /**
     * Язык интерфейса на сервер («ru» | «ba») — чтобы ПУШИ приходили на языке человека.
     * Сервер это поле давно принимает и умеет выбирать RU/BA, но клиент его никогда не слал:
     * башкироязычный пользователь получал русские уведомления (аудит 2026-07-26).
     * Fire-and-forget: переключение языка — мгновенное действие в UI, ждать сеть незачем.
     */
    fun fireUpdateLanguage(lang: String) {
        val code = lang.trim().lowercase().take(2)
        if (code != "ru" && code != "ba") return
        if (!isLoggedIn()) return
        bg.launch { call("POST", "/me/update", JSONObject().put("language", code), auth = true).onSuccess { invalidate("me") } }
    }

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
        return call("GET", path, null, auth = false).map {
            val dist = if (it.has("distance_km") && !it.isNull("distance_km")) it.optDouble("distance_km").toFloat() else null
            val fuel = if (it.has("fuel_estimate_kop") && !it.isNull("fuel_estimate_kop")) it.optInt("fuel_estimate_kop") else null
            PriceHintDto(it.optInt("avg"), it.optInt("count"), dist, fuel)
        }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * Подсказки адресов «Откуда/Куда» — прокси Яндекс.Геокодера на нашем сервере
     * (ключ живёт на сервере, не в APK).
     *
     * Ходит через общий [call]: с токеном и с авто-обновлением сессии на 401. Раньше
     * [GeocoderClient] открывал соединение сам и заголовок Authorization не слал, а сервер
     * авторизацию ТРЕБУЕТ (без неё аноним уникальными запросами выжигает бесплатную квоту
     * Яндекса, и подсказки лягут у всех). Итог: поиск адреса не работал нигде — ни в такси,
     * ни в посылках, ни в «моих адресах»; человек с правильным адресом видел «не нашли»
     * (аудит 2026-08-07). Разбор адресов и кеш остались в [GeocoderClient].
     */
    internal suspend fun geocode(query: String): Result<JSONObject> =
        call("GET", "/geocode?q=" + enc(query), null, auth = true)

    /**
     * Адрес по координатам (обратный геокодер).
     *
     * Нужен там, где точку ставят пином: без него в заказ уходило безымянное «Точка
     * на карте» — ни водитель в списке заказов, ни пассажир в истории не понимали, где это.
     */
    internal suspend fun geocodeReverse(lat: Double, lng: Double): Result<JSONObject> =
        call("GET", "/geocode/reverse?lat=$lat&lng=$lng", null, auth = true)

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
        quiet: Boolean = false,      // тихая поездка (в конце — чтобы не сдвигать позиционные вызовы)
        waypoints: String = "",     // остановки по пути (названия через " | ")
        noMinors: Boolean = false,  // не беру пассажиров младше 18 без сопровождения взрослого
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
            .put("quiet", quiet)
            .put("no_minors", noMinors)
            .put("waypoints", waypoints)
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

    // ---------- F15: сезонные события (баннер «на праздник») ----------

    /** Актуальные сезонные события для баннера на карте (публично). days — окно вперёд (по умолч. 21). */
    suspend fun getSeasonalEvents(days: Int? = null): Result<List<SeasonalEventDto>> {
        val path = "/seasonal-events" + (days?.let { "?days=$it" } ?: "")
        return call("GET", path, null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toSeasonalEventDto() }
        }
    }

    /** Забронировать поездку. Возвращает id брони.
     * payMethod/payAmount — договорённость об оплате (ЗАПИСЬ, не платёж): как решили платить.
     * Способ по умолчанию — "negotiate" (договоримся); сумма опц. (null = сервер возьмёт цену поездки). */
    suspend fun book(
        rideId: Int, seats: Int, payMethod: String = "negotiate", payAmount: Int? = null,
        // Едет несовершеннолетний: взрослый обязателен (имя + телефон) — это и запись согласия,
        // и водителю есть кому позвонить. Сервер без них бронь не создаст.
        minorPassenger: Boolean = false, guardianName: String = "", guardianPhone: String = "",
    ): Result<Int> = call(
        "POST", "/bookings",
        JSONObject().put("ride_id", rideId).put("seats", seats)
            .put("pay_method", payMethod)
            .put("pay_amount", payAmount ?: JSONObject.NULL)
            .apply {
                if (minorPassenger) {
                    put("minor_passenger", true)
                    put("minor_guardian_name", guardianName)
                    put("minor_guardian_phone", guardianPhone)
                }
            },
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
                driverPlate = o.optString("driver_plate"),
                driverCarColor = o.optString("driver_car_color"),
                minorPassenger = o.optBoolean("minor_passenger"),
                minorGuardianName = o.optString("minor_guardian_name"),
                minorGuardianPhone = o.optString("minor_guardian_phone"),
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

    /**
     * Убрать доверенный контакт. Сервер умел это с самого начала, а приложение не звало:
     * контакт удалялся ТОЛЬКО на экране и возвращался после перезапуска. То есть человек,
     * которому уходит твоя геопозиция в поездке и SOS-сигнал ночью, оставался в списке
     * навсегда — хотя отношения меняются: бывший, поссорились, ошиблись номером.
     * Сервер сначала гасит шаринги этого контакта, потом сам контакт.
     */
    suspend fun deleteContact(contactId: Int): Result<Unit> =
        call("DELETE", "/trusted-contacts/$contactId", null, auth = true)
            .onSuccess { invalidate("contacts") }.map { }

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

    /** SOS. orderId — контекст такси-заказа (B7b-2), bookingId — контекст попутки: дежурный
     *  увидит маршрут и вторую сторону. Раньше поле брони было только на сервере и всегда
     *  приходило пустым: из такси знали, с кем человек, из попутки — нет (аудит 2026-08-06).
     *  Координаты обязательны по смыслу, но не по форме: без них близкие получат «нужна срочная
     *  помощь» и не будут знать, куда ехать. GPS мог не схватиться — тогда шлём хотя бы сигнал. */
    suspend fun sos(
        category: String, note: String, orderId: Int? = null,
        lat: Double? = null, lng: Double? = null, bookingId: Int? = null,
    ): Result<Unit> {
        val body = JSONObject().put("category", category).put("note", note)
        if (orderId != null) body.put("order_id", orderId)
        if (bookingId != null) body.put("booking_id", bookingId)
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return call("POST", "/sos", body, auth = true).map { }.onSuccess { Analytics.log("sos") }
    }

    /** «Поделиться поездкой» из такси-заказа (B7b-2): близкий получит SMS о маршруте и статусах.
     *  B7c: сервер возвращает token live-ссылки — отдаём готовый URL (null на старом сервере). */
    suspend fun shareInstantTrip(orderId: Int, contactId: Int): Result<TripShareDto?> =
        call("POST", "/instant/orders/$orderId/share", JSONObject().put("contact_id", contactId), auth = true)
            .map { parseTripShare(it) }
            .onSuccess { Analytics.log("instant_share_trip") }

    /** Live-ссылка близкого (B7c) из ответа share: {token} → "$BASE/t/{token}".
     *  База — тот же хост, что API (прод: https://yulbash.ru). Токена нет (старый сервер) → null. */
    private fun liveLinkOrNull(j: JSONObject): String? =
        j.optString("token", "").takeIf { it.isNotBlank() }?.let { "$BASE/t/$it" }

    /** Ответ на «позвать помощь»: скольким близким сообщение реально уйдёт и сколько
     *  доверенных заведено всего. Старый сервер второго числа не присылает — тогда считаем,
     *  что доверенных столько же, скольким ушло (текст экрана останется прежним). */
    data class RoadsideResult(val notified: Int, val contactsTotal: Int)

    private fun roadsideResult(j: JSONObject): RoadsideResult {
        val ушло = j.optInt("contacts_notified")
        return RoadsideResult(ушло, j.optInt("contacts_total", ушло))
    }

    /** F12 «Застрял на трассе»: координаты уходят доверенным контактам + запись в SOS-ленту админа.
     *  Уровень мягче паники SOS. Координаты необязательны (шлём хотя бы сигнал о помощи). */
    /** Возвращает, СКОЛЬКИМ близким реально ушло SMS (волна 122): экран обещал помощь даже
     *  тому, кто доверенных контактов не заводил. Волна 184 добавила второе число — сколько
     *  доверенных вообще заведено: без него «ушло нулю» и «звать некого» выглядят одинаково,
     *  а человеку на трассе это разные новости и разные действия. */
    suspend fun roadsideHelp(bookingId: Int, lat: Double?, lng: Double?, note: String): Result<RoadsideResult> {
        val body = JSONObject().put("note", note)
        if (lat != null && lng != null) body.put("lat", lat).put("lng", lng)
        return call("POST", "/bookings/$bookingId/stuck", body, auth = true).map { roadsideResult(it) }.onSuccess { Analytics.log("roadside_help") }
    }

    /** Запрос «перезвоните мне» → уведомление админу в Telegram (помощь пожилым/без интернета). */
    suspend fun requestCallback(note: String): Result<Unit> =
        call("POST", "/callback", JSONObject().put("note", note), auth = true).map { }.onSuccess { Analytics.log("callback_request") }

    /** F12 «Зимний протокол»: арм авто-проверки «доехал?». Идемпотентна — сервер сам решает
     *  (too_early / check_sent / waiting / escalated). Возвращает поле state. Клиент зовёт,
     *  когда его ETA+буфер истёк, а поездка ещё активна. */
    suspend fun winterCheck(bookingId: Int): Result<String> =
        call("POST", "/bookings/$bookingId/winter-check", JSONObject(), auth = true)
            .map { it.optString("state") }.onSuccess { Analytics.log("winter_check") }

    /** F12: участник ответил «всё в порядке» на проверку «доехал?» — гасит эскалацию доверенным. */
    suspend fun winterCheckOk(bookingId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/winter-check/ok", JSONObject(), auth = true).map { }
            .onSuccess { Analytics.log("winter_check_ok") }

    // ❄️ Зимний протокол в такси и доставке. Раньше он был только у попутки, хотя дорога
    // одна: пассажир такси едет те же четыре часа, курьер — тоже и вдобавок один.
    /** Зимний протокол по такси-заказу: арм проверки «доехал?» (сервер решает, не рано ли). */
    suspend fun winterCheckOrder(orderId: Int): Result<String> =
        call("POST", "/instant/orders/$orderId/winter-check", JSONObject(), auth = true)
            .map { it.optString("state") }.onSuccess { Analytics.log("winter_check_order") }

    /** «Доехал» по такси-заказу — гасит эскалацию близким. */
    suspend fun winterCheckOrderOk(orderId: Int): Result<Unit> =
        call("POST", "/instant/orders/$orderId/winter-check/ok", JSONObject(), auth = true).map { }
            .onSuccess { Analytics.log("winter_check_order_ok") }

    /** Зимний протокол по доставке: спрашиваем курьера — он в дороге один. */
    suspend fun winterCheckParcel(parcelId: Int): Result<String> =
        call("POST", "/parcels/$parcelId/winter-check", JSONObject(), auth = true)
            .map { it.optString("state") }.onSuccess { Analytics.log("winter_check_parcel") }

    /** «Доехал» по доставке — отмечает только курьер. */
    suspend fun winterCheckParcelOk(parcelId: Int): Result<Unit> =
        call("POST", "/parcels/$parcelId/winter-check/ok", JSONObject(), auth = true).map { }
            .onSuccess { Analytics.log("winter_check_parcel_ok") }

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
                RequestFeedDto(o.optInt("id"), o.optString("passenger_name"), o.optString("from_city"), o.optString("to_city"), o.optInt("seats"), o.optString("comment"), o.optBoolean("responded"), o.optString("passenger_avatar"), prefs, if (o.isNull("my_response_id")) null else o.optInt("my_response_id"),
                    if (o.isNull("detour_km")) null else o.optInt("detour_km"))
            }
        }

    suspend fun respondToRequest(requestId: Int, price: Int, comment: String): Result<Unit> =
        call("POST", "/requests/$requestId/respond", JSONObject().put("price", price).put("comment", comment), auth = true).map { }.onSuccess { Analytics.log("respond_request") }

    /** Пассажир отменяет свою заявку → сервер ставит status=cancelled (идемпотентно; matched → 400). */
    suspend fun cancelRequest(requestId: Int): Result<Unit> =
        call("POST", "/requests/$requestId/cancel", null, auth = true).map { }.onSuccess { Analytics.log("cancel_request") }

    /** F3: правка своей активной заявки (по образцу editRide). POST-алиас /edit — HttpURLConnection не умеет PATCH.
     *  Шлём только непустые поля (null = не менять). Смена города → сервер перегеокодит концы. */
    suspend fun editRequest(
        requestId: Int,
        fromCity: String? = null,
        toCity: String? = null,
        maxPrice: Int? = null,
        comment: String? = null,
        seats: Int? = null,
        desiredAt: String? = null,   // ISO "yyyy-MM-dd'T'HH:mm:ss"
    ): Result<Unit> {
        val body = JSONObject()
        fromCity?.takeIf { it.isNotBlank() }?.let { body.put("from_city", it) }
        toCity?.takeIf { it.isNotBlank() }?.let { body.put("to_city", it) }
        maxPrice?.let { body.put("max_price", it) }
        comment?.let { body.put("comment", it) }
        seats?.let { body.put("seats", it) }
        desiredAt?.takeIf { it.isNotBlank() }?.let { body.put("desired_at", it) }
        return call("POST", "/requests/$requestId/edit", body, auth = true).map { }.onSuccess { Analytics.log("edit_request") }
    }

    /** F: авто-подбор попуток под заявку пассажира (GET /match/rides?request_id=). Только владелец заявки.
     *  Возврат — публичная витрина поездок (без ПДн до брони), совпадающих по маршруту/местам/категории. */
    suspend fun matchRides(requestId: Int): Result<List<RideDto>> =
        call("GET", "/match/rides?request_id=$requestId", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toRideDto() }
        }

    suspend fun getRequestResponses(requestId: Int): Result<List<ResponseDto>> =
        call("GET", "/requests/$requestId/responses", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toResponseDto() }
        }

    /** Водитель: мои отклики — где я предложил цену и где мне ответили встречной.
     *  Без этого списка второй круг торга не работал бы: встречную цену водитель видел бы
     *  только в пуше и, пропустив его, терял бы сделку. */
    suspend fun getMyResponses(): Result<List<ResponseDto>> =
        call("GET", "/responses/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toResponseDto() }
        }

    /** Встречная цена: «а за 400 поедешь?». Ходят по очереди, обе стороны, лимит — 3 хода на брата.
     *  409 — сейчас ход другой стороны или торг исчерпан (текст ошибки двуязычный, показываем как есть). */
    suspend fun counterOffer(responseId: Int, price: Int, comment: String = ""): Result<Unit> =
        call("POST", "/responses/$responseId/counter",
            JSONObject().put("price", price).put("comment", comment), auth = true)
            .map { }.onSuccess { Analytics.log("bargain_counter") }

    /** Закончить торг без сделки. Заявка остаётся активной — другие водители продолжают откликаться. */
    suspend fun declineResponse(responseId: Int): Result<Unit> =
        call("POST", "/responses/$responseId/decline", JSONObject(), auth = true)
            .map { }.onSuccess { Analytics.log("bargain_decline") }

    /** Принять цену, которая сейчас на столе → booking_id. Принимает тот, чей ход. */
    suspend fun acceptResponse(responseId: Int): Result<Int> =
        call("POST", "/responses/$responseId/accept", JSONObject(), auth = true).map { it.optInt("booking_id") }.onSuccess { Analytics.log("accept_response") }

    private fun JSONObject.toResponseDto() = ResponseDto(
        id = optInt("id"), driverId = optInt("driver_id"), driverName = optString("driver_name"),
        driverRating = if (isNull("driver_rating")) null else optDouble("driver_rating"),
        price = optInt("price"), comment = optString("comment"), status = optString("status"),
        driverAvatar = optString("driver_avatar"),
        currentPrice = optInt("current_price"),
        lastOfferBy = optString("last_offer_by").ifBlank { "driver" },
        bargainRounds = optInt("bargain_rounds"),
        canCounter = optBoolean("can_counter"), canAccept = optBoolean("can_accept"),
        bargainHistory = optString("bargain_history"),
    )

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
                    o.optString("autocheck_result"), o.optDouble("autocheck_score", 0.0), o.optString("autocheck_data"),
                    o.optString("gender_claimed"), o.optBoolean("gender_verified"))
            }
        }

    /** genderVerified=null — не трогать подтверждение пола (обычное одобрение документов). */
    suspend fun moderateDriver(userId: Int, approve: Boolean, genderVerified: Boolean? = null): Result<Unit> =
        call("POST", "/admin/drivers/$userId/moderate",
            JSONObject().put("approve", approve).apply {
                if (genderVerified != null) put("gender_verified", genderVerified)
            }, auth = true).map { }

    /** Помеченные тексты (модерация). kind — фильтр по виду метки, пусто = все. */
    suspend fun getTextFlags(kind: String = ""): Result<List<TextFlagDto>> =
        call("GET", "/admin/text-flags" + (if (kind.isNotBlank()) "?kind=$kind" else ""), null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TextFlagDto(
                    o.optInt("id"), o.optInt("user_id"), o.optString("user_name"), o.optString("user_phone"),
                    o.optString("kind"), o.optString("place_label"),
                    if (o.isNull("ref_id")) null else o.optInt("ref_id"),
                    o.optString("created_at"), o.optInt("user_flags_total"),
                )
            }
        }

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
            TripStateDto(it.optString("role"), it.optString("status"), it.optString("driver_phase"),
                         it.optBoolean("arrival_verified", false),
                         it.optBoolean("alone_with_driver", false))
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

    // ---------- Чат доставки: отправитель ↔ курьер (привязка к parcel_id) ----------
    // До этого по посылке можно было только позвонить. «Оставь у соседей», «я на работе до шести»,
    // «звони, домофон не работает» — вещи на одну фразу, ради которых звонок избыточен, а без
    // переписки ещё и не остаётся следа, если потом спор.

    /** История переписки по посылке. После вручения/отмены сервер отдаёт её только для чтения. */
    suspend fun getParcelMessages(parcelId: Int): Result<List<MessageDto>> =
        call("GET", "/parcels/$parcelId/messages", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i -> parseMessageDto(arr.getJSONObject(i)) }
        }

    /** Отправить текст в чат посылки (REST-фолбэк, когда WS лежит). */
    suspend fun sendParcelMessage(parcelId: Int, text: String): Result<Unit> =
        call("POST", "/parcels/$parcelId/messages", JSONObject().put("text", text), auth = true).map { }

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
                genderVerified = o.optBoolean("gender_verified"),
                tipsSbp = o.optString("tips_sbp"),
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
                    myStars = o.optInt("my_stars"),
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

    /**
     * Публичные регулярные маршруты водителя (для профиля/поиска).
     *
     * Ключ входа шлём ОБЯЗАТЕЛЬНО (аудит 2026-08-08, волна 143). Раньше запрос уходил без него,
     * и сервер видел не человека, а гостя — значит фильтр «заблокированный не видит расписание»
     * (волна 113) не срабатывал НИ РАЗУ через приложение. Женщина закрывалась от навязчивого
     * пассажира, а он продолжал открывать её недельный график: дни, время и ориентир из
     * комментария. Гостю сервер теперь отдаёт только маршрут и дни недели.
     */
    suspend fun getPublicDriverSchedules(driverId: Int): Result<List<DriverScheduleDto>> =
        call("GET", "/drivers/$driverId/schedule", null, auth = true).map { obj ->
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

    // ---------- Поддержка Юлдаш (обращения в поддержку, тикеты) ----------
    private fun JSONObject.toSupportMessageDto() = SupportMessageDto(
        id = optInt("id"),
        sender = optString("sender"),          // user | admin
        body = optString("body"),
        createdAt = optString("created_at"),
    )

    private fun JSONObject.toSupportTicketDto() = SupportTicketDto(
        id = optInt("id"),
        subject = optString("subject"),
        status = optString("status"),          // open | closed
        createdAt = optString("created_at"),
        updatedAt = optString("updated_at"),
        messages = (optJSONArray("messages") ?: JSONArray()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).toSupportMessageDto() }
        },
    )

    /** Список моих обращений + счётчик непрочитанного (бейдж). */
    suspend fun getSupportTickets(): Result<SupportListDto> =
        call("GET", "/support/tickets", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            SupportListDto(
                unread = obj.optInt("unread"),
                items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    SupportTicketRowDto(
                        id = o.optInt("id"),
                        subject = o.optString("subject"),
                        status = o.optString("status"),
                        lastMessage = o.optString("last_message"),
                        lastSender = o.optString("last_sender"),
                        unread = o.optBoolean("unread"),
                        createdAt = o.optString("created_at"),
                        updatedAt = o.optString("updated_at"),
                    )
                },
            )
        }

    /** Один тред обращения (сообщения user/admin). Чужой → 404 (ApiException). */
    suspend fun getSupportTicket(id: Int): Result<SupportTicketDto> =
        call("GET", "/support/tickets/$id", null, auth = true).map { it.toSupportTicketDto() }

    /** Создать обращение: тема (опц.) + текст. Возврат — созданный тред. */
    suspend fun createSupportTicket(subject: String?, body: String): Result<SupportTicketDto> {
        val json = JSONObject().put("body", body)
        if (!subject.isNullOrBlank()) json.put("subject", subject)
        return call("POST", "/support/tickets", json, auth = true).map { it.toSupportTicketDto() }
            .onSuccess { Analytics.log("support_ticket_create") }
    }

    /** Дописать сообщение в тред. На закрытый тикет — сервер переоткрывает. */
    suspend fun postSupportMessage(id: Int, body: String): Result<SupportMessageDto> =
        call("POST", "/support/tickets/$id/messages", JSONObject().put("body", body), auth = true)
            .map { it.toSupportMessageDto() }

    /** Закрыть обращение (пользователь). */
    suspend fun closeSupportTicket(id: Int): Result<Unit> =
        call("POST", "/support/tickets/$id/close", JSONObject(), auth = true).map { }

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
                val pl = o.optJSONArray("placements")
                AdDto(
                    o.optString("id"), o.optString("title"), o.optString("text"), o.optString("button"), o.optString("erid"), o.optString("placement"),
                    partner = o.optString("partner"), contact = o.optString("contact"), target = o.optString("target"),
                    image = o.optString("image"), city = o.optString("city"),
                    // Старый сервер массива не отдаёт — тогда падаем на одиночное `placement`,
                    // чтобы объявление всё же где-то показалось, а не пропало совсем.
                    placements = if (pl != null) (0 until pl.length()).map { j -> pl.getString(j) }
                                 else listOfNotNull(o.optString("placement").takeIf { p -> p.isNotBlank() }),
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

    /**
     * Продлить размещение ещё на период.
     *
     * Отдельная ручка, потому что обычная оплата на оплаченном объявлении отвечает
     * «Уже оплачено». До волны 116 кнопка «Продлить» вообще не ходила на сервер: человек
     * переводил деньги по QR, а на сервере не появлялось ни заявки, ни сигнала Александру.
     */
    suspend fun renewAd(id: String): Result<Int> =
        call("POST", "/ads/$id/renew", null, auth = true).map { it.optInt("amount_kop") }

    // ---------- Активная поездка: поделиться / статус ----------

    /** Поделиться бронью попутки. B7c: возвращает шаринг (id для отзыва + live-ссылка близкого). */
    suspend fun shareTrip(bookingId: Int, contactId: Int): Result<TripShareDto?> =
        call("POST", "/bookings/$bookingId/share", JSONObject().put("contact_id", contactId), auth = true)
            .map { parseTripShare(it) }

    /** Кому открыта эта поездка (пассажиру — «уже поделился с …» + возможность отозвать).
     *  Аудит 2026-08-06: у такси такой список был, у попутки — нет, и экран помнил ссылки
     *  только в своей памяти. Свернул приложение — отзывать стало нечего, хотя ссылка на
     *  живое местоположение продолжала работать. */
    suspend fun getBookingShares(bookingId: Int): Result<List<TripShareDto>> =
        call("GET", "/bookings/$bookingId/shares", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { parseTripShare(arr.getJSONObject(it)) }
        }

    /** Отозвать шаринг брони (B7c): live-токен «сгорает», SMS-статусы контакту прекращаются. Приватность. */
    suspend fun revokeBookingShare(bookingId: Int, shareId: Int): Result<Unit> =
        call("DELETE", "/bookings/$bookingId/share/$shareId", null, auth = true).map { }
            .onSuccess { Analytics.log("revoke_share") }

    /** Активные шаринги такси-заказа (пассажиру — «уже поделился с …» + отозвать). */
    suspend fun getInstantShares(orderId: Int): Result<List<TripShareDto>> =
        call("GET", "/instant/orders/$orderId/shares", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { parseTripShare(arr.getJSONObject(it)) }
        }

    /** Отозвать шаринг такси-заказа (B7c). */
    suspend fun revokeInstantShare(orderId: Int, shareId: Int): Result<Unit> =
        call("DELETE", "/instant/orders/$orderId/share/$shareId", null, auth = true).map { }
            .onSuccess { Analytics.log("revoke_share") }

    /** Разбор TripShare с сервера → id/contact_id/token + готовый live-URL (или null, старый сервер). */
    private fun parseTripShare(j: JSONObject): TripShareDto? {
        val id = j.optInt("id", 0)
        if (id == 0) return null
        return TripShareDto(
            id = id,
            contactId = j.optInt("contact_id", 0),
            link = liveLinkOrNull(j),
        )
    }

    suspend fun setTripStatus(bookingId: Int, status: String): Result<Unit> =
        call("POST", "/bookings/$bookingId/trip-status", JSONObject().put("status", status), auth = true).map { }

    /** Оценить вторую сторону поездки (1..5 звёзд) + опц. текстовый отзыв (≤500, идёт на модерацию)
     *  и быстрые метки («вежливый», «вовремя») — коды из закрытого списка, сервер их фильтрует. */
    suspend fun rateBooking(
        bookingId: Int,
        stars: Int,
        text: String = "",
        tags: List<String> = emptyList(),
    ): Result<Unit> =
        call("POST", "/bookings/$bookingId/rate",
            JSONObject().put("stars", stars).apply {
                text.trim().take(500).let { if (it.isNotBlank()) put("text", it) }
                // Пустой список не шлём: на сервере пустое НЕ затирает уже поставленные метки,
                // и гонять пустое поле по сети незачем.
                if (tags.isNotEmpty()) put("tags", tags.joinToString(","))
            },
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
    /** Итог отмены брони. `cancelled = false` значит сервер отменять отказался. */
    data class CancelResultDto(val cancelled: Boolean, val contactThenCancel: Boolean, val status: String)

    /**
     * Отменить бронь.
     *
     * ВАЖНО про `cancelled`. Сервер на попытку отменить УЖЕ ЗАВЕРШЁННУЮ поездку отвечает 200
     * и возвращает бронь как есть, ничего не меняя (bookings.py: статусы cancelled и done
     * пропускаются). Это верно для данных, но для экрана — ловушка: раньше клиент читал из
     * ответа только `contact_then_cancel`, статус выбрасывал и на любой успех радостно писал
     * «Поездка отменена», стирал офлайн-паспорт поездки и закрывал экран.
     *
     * Попасть туда просто: пассажир держит открытым экран, водитель тем временем завершает
     * поездку, у пассажира на экране всё ещё «подтверждена» — и кнопка «Отменить» на месте.
     * Человек получал сообщение о том, чего не произошло, и терял телефон водителя из паспорта
     * по состоявшейся поездке.
     *
     * Поэтому отдаём наверх настоящий статус: экран сам решает, что показать.
     */
    suspend fun cancelBooking(bookingId: Int, reason: String = ""): Result<CancelResultDto> =
        call("POST", "/bookings/$bookingId/cancel", JSONObject().put("reason", reason), auth = true)
            .map { o ->
                val status = o.optString("status")
                CancelResultDto(
                    cancelled = status == "cancelled",
                    contactThenCancel = o.optBoolean("contact_then_cancel"),
                    status = status,
                )
            }
            .onSuccess { if (it.cancelled) Analytics.log("booking_cancel") }

    // Водитель отмечает неявку пассажира (no-show): бронь снимается, места возвращаются.
    suspend fun markNoShow(bookingId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/no-show", JSONObject(), auth = true).map { }
            .onSuccess { Analytics.log("booking_no_show") }

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
                payNowKop = o.optInt("pay_now_kop"),
                payNowDueAt = o.optString("pay_now_due_at").ifBlank { null },
            )
        }

    /** Оплата долга: yookassa → оплата картой (confirmationUrl), иначе СБП «на доверии» (pending). */
    suspend fun declareDebtPaid(): Result<DebtPayResultDto> =
        call("POST", "/driver/debt/paid", JSONObject(), auth = true).map { o ->
            DebtPayResultDto(
                method = o.optString("method", "sbp_manual"),
                status = o.optString("status", "pending"),
                paymentId = o.optInt("payment_id"),
                pendingKop = o.optInt("pending_kop"),
                confirmationUrl = o.optString("confirmation_url").ifBlank { null },
            )
        }

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
                    // Точная сумма долга: админ сверяет её с переводом от водителя, а тот
                    // переводит копейки. Округлённое `amount` давало расхождение при сверке.
                    amountKop = o.optInt("amount_kop", o.optInt("amount") * 100),
                    weeks = (0 until wk.length()).map { j -> wk.optString(j) },
                )
            }
        }

    /**
     * Подтвердить/отклонить перевод долга. `amountKop` — сумма, которую админ ВИДИТ в строке.
     *
     * Сервер сверяет её с текущей и при расхождении отвечает 409, ничего не меняя. Между
     * «увидел строку» и «нажал кнопку» проходит время, и водитель успевает заявить оплату по
     * новым поездкам: раньше одно нажатие закрывало обе заявки (волна 220).
     */
    suspend fun confirmDebt(debtId: Int, amountKop: Int): Result<Unit> =
        call("POST", "/admin/debts/$debtId/confirm",
             JSONObject().put("amount_kop", amountKop), auth = true).map { }

    suspend fun rejectDebt(debtId: Int, amountKop: Int): Result<Unit> =
        call("POST", "/admin/debts/$debtId/reject",
             JSONObject().put("amount_kop", amountKop), auth = true).map { }

    // ---------- Быстрый заказ (такси-режим, Фаза 2) ----------
    // Отдельный поток от плановых поездок (Ride/Booking) — те не трогаем. Приватность: телефоны/имя
    // стороны сервер отдаёт пустыми до accept. Координаты heartbeat НЕ логируем.

    /** Остановки в тело запроса. Пустой список не шлём — лишний шум. */
    private fun stopsArray(stops: List<TaxiStop>): JSONArray = JSONArray().apply {
        stops.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("text", it.text)) }
    }

    private fun instantBody(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String, toText: String, category: String,
        roundTrip: Boolean = false, returnWaitMin: Int = 0,
        stops: List<TaxiStop> = emptyList(),
        scheduledAtIso: String? = null,
    ): JSONObject = JSONObject()
        .put("from_lat", fromLat).put("from_lng", fromLng)
        .put("to_lat", toLat).put("to_lng", toLng)
        .put("from_text", fromText).put("to_text", toText)
        .put("category", category)
        // Круговой рейс: водитель везёт туда, ждёт и везёт обратно. Решает сервер — он же
        // проверяет, что это межгород; в городе флаг просто ничего не меняет.
        .put("round_trip", roundTrip)
        .also { if (stops.isNotEmpty()) it.put("waypoints", stopsArray(stops)) }
        // Время подачи предзаказа: без него экран показывал цену «на сейчас», а заказ
        // оформлялся по цене на время подачи — заказ на пять утра считался по дневной
        // ставке, а уезжал по ночной.
        .also { if (!scheduledAtIso.isNullOrBlank()) it.put("scheduled_at", scheduledAtIso) }
        .put("return_wait_min", returnWaitMin.coerceIn(0, 24 * 60))

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
                funnel = o.optJSONObject("funnel")?.let { fo ->
                    val days = fo.optJSONArray("by_day") ?: JSONArray()
                    TaxiFunnelDto(
                        windowDays = fo.optInt("window_days", 7),
                        viewsToday = fo.optInt("views_today"),
                        ordersToday = fo.optInt("orders_today"),
                        percentToday = if (fo.isNull("percent_today")) null else fo.optDouble("percent_today"),
                        viewsPeriod = fo.optInt("views_period"),
                        ordersPeriod = fo.optInt("orders_period"),
                        percentPeriod = if (fo.isNull("percent_period")) null else fo.optDouble("percent_period"),
                        byDay = (0 until days.length()).mapNotNull { i ->
                            days.optJSONObject(i)?.let { dd ->
                                TaxiFunnelDayDto(dd.optString("day"), dd.optInt("views"), dd.optInt("orders"))
                            }
                        },
                    )
                },
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

    /**
     * Карта спроса для водителя — «где сейчас ищут». Анонимно: сервер отдаёт только агрегированные
     * зоны (координаты + вес + число заявок), без личности пассажиров. city — необязательный фильтр.
     */
    suspend fun getInstantDemand(city: String? = null): Result<InstantDemandDto> {
        val path = "/instant/demand" + (city?.takeIf { it.isNotBlank() }?.let { "?city=" + enc(it) } ?: "")
        return call("GET", path, null, auth = true).map { o ->
            val arr = o.optJSONArray("zones") ?: JSONArray()
            InstantDemandDto(
                zones = (0 until arr.length()).map { i ->
                    val z = arr.getJSONObject(i)
                    DemandZoneDto(
                        lat = z.optDouble("lat", 0.0),
                        lng = z.optDouble("lng", 0.0),
                        weight = z.optDouble("weight", 0.0),
                        requests = z.optInt("requests", 0),
                        // Далеко ли зона — считает сервер по живой позиции водителя.
                        // Нет поля (только вышел на линию / старый сервер) → null, и подпись
                        // остаётся «Зона N»: выдуманные километры хуже их отсутствия.
                        distKm = if (z.isNull("dist_km")) null else z.optDouble("dist_km"),
                    )
                }.sortedByDescending { it.weight },
                updatedAt = o.optString("updated_at"),
            )
        }
    }

    /** Оценка цены ДО заказа. Сервер считает сам (клиенту не верит) — поля цены в запросе нет. */
    suspend fun instantEstimate(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String = "", toText: String = "", category: String = "standard",
        roundTrip: Boolean = false, returnWaitMin: Int = 0,
        stops: List<TaxiStop> = emptyList(),
        /** Время подачи предзаказа (ISO-UTC). Пусто = цена «на сейчас». */
        scheduledAtIso: String? = null,
    ): Result<InstantEstimateDto> =
        call("POST", "/instant/estimate",
            instantBody(fromLat, fromLng, toLat, toLng, fromText, toText, category,
                roundTrip, returnWaitMin, stops, scheduledAtIso), auth = true).map { o ->
            val note = o.optJSONObject("surge_note")
            val promoNote = o.optJSONObject("promo_note")   // null, когда скидки нет
            val pickupNote = o.optJSONObject("pickup_note") // null, когда подача ничего не стоит
            val waitHint = o.optJSONObject("pickup_wait_hint") // null, когда ждать нечего
            val optionCatalogArr = o.optJSONArray("option_catalog") ?: JSONArray()
            val optArr = o.optJSONArray("options") ?: JSONArray()
            val factorArr = o.optJSONArray("price_factors") ?: JSONArray()
            InstantEstimateDto(
                price = o.optInt("price"),
                distanceKm = o.optDouble("distance_km", 0.0),
                etaMin = o.optDouble("eta_min", 0.0),
                pickupEtaMin = if (o.isNull("pickup_eta_min")) null else o.optInt("pickup_eta_min"),
                zone = o.optString("zone"),
                category = o.optString("category", category),
                tariffId = o.optInt("tariff_id"),
                surgeK = o.optDouble("surge_k", 1.0),
                surgeNoteRu = note?.optString("ru") ?: "",
                surgeNoteBa = note?.optString("ba") ?: "",
                options = (0 until optArr.length()).map { i ->
                    val c = optArr.getJSONObject(i)
                    InstantClassOption(
                        category = c.optString("category"),
                        price = c.optInt("price"),
                        // Класс, в котором в этом городе ещё не набралось водителей. Приходит
                        // с open=false, чтобы показать «скоро» вместо кнопки, за которой пусто.
                        // Старый сервер поля не шлёт → true, прежнее поведение.
                        open = c.optBoolean("open", true),
                        pickupEtaMin = if (c.isNull("pickup_eta_min")) null
                        else c.optInt("pickup_eta_min").takeIf { it > 0 },
                    )
                },
                basePrice = o.optInt("base_price"),
                dynamicK = o.optDouble("dynamic_k", 1.0),
                pricingCapK = o.optDouble("pricing_cap_k", 1.5),
                pricingVersion = o.optString("pricing_version", "v1"),
                routeSource = o.optString("route_source", "fallback"),
                trafficType = o.optString("traffic_type", "unknown"),
                trafficK = o.optDouble("traffic_k", 1.0),
                pickupK = o.optDouble("pickup_k", 1.0),
                weatherK = o.optDouble("weather_k", 1.0),
                weatherCode = o.optString("weather_code"),
                hasTolls = o.optBoolean("has_tolls"),
                // Дальняя подача строкой. `ride_price` подстрахован полной ценой: у старого
                // сервера поля нет, и тогда «поездка» — это и есть весь чек.
                ridePrice = o.optInt("ride_price", o.optInt("price")),
                pickupFee = o.optInt("pickup_fee", 0),
                pickupKm = o.optDouble("pickup_km", 0.0),
                pickupPending = o.optBoolean("pickup_pending", false),
                pickupMaxRub = o.optInt("pickup_max_rub", 0),
                pickupNoteRu = pickupNote?.optString("ru") ?: "",
                pickupNoteBa = pickupNote?.optString("ba") ?: "",
                pickupEnroute = o.optBoolean("pickup_enroute", false),
                pickupFullFee = o.optInt("pickup_full_fee", 0),
                pickupWaitMinutes = waitHint?.optInt("minutes", 0) ?: 0,
                pickupWaitSaveRub = waitHint?.optInt("save_rub", 0) ?: 0,
                pickupWaitRu = waitHint?.optString("ru") ?: "",
                pickupWaitBa = waitHint?.optString("ba") ?: "",
                optionsFee = o.optInt("options_fee", 0),
                priceLockedSec = o.optInt("price_locked_sec", 0),
                weatherFee = o.optInt("weather_fee", 0),
                weatherKind = o.optString("weather_kind"),
                optionCatalog = (0 until optionCatalogArr.length()).mapNotNull { i ->
                    optionCatalogArr.optJSONObject(i)?.let { row ->
                        val code = row.optString("code")
                        if (code.isBlank()) null else code to row.optInt("price", 0)
                    }
                }.toMap(),
                priceFactors = (0 until factorArr.length()).mapNotNull { i ->
                    factorArr.optJSONObject(i)?.let { p ->
                        InstantPriceFactorDto(
                            code = p.optString("code"),
                            kind = p.optString("kind"),
                            k = p.optDouble("k", 1.0),
                            active = p.optBoolean("active"),
                            titleRu = p.optString("title_ru"),
                            titleBa = p.optString("title_ba"),
                            descriptionRu = p.optString("description_ru"),
                            descriptionBa = p.optString("description_ba"),
                            amountRub = p.optInt("amount_rub", 0),
                        )
                    }
                },
                promoCode = o.optString("promo_code"),
                promoDiscountKop = o.optInt("promo_discount_kop"),
                // Старый сервер поля не шлёт → 0. Ниже подстраховываемся полной ценой, чтобы
                // «со скидкой» никогда не оказалось нулём на пустом месте.
                priceWithDiscount = o.optInt("price_with_discount", o.optInt("price")),
                promoNoteRu = promoNote?.optString("ru") ?: "",
                promoNoteBa = promoNote?.optString("ba") ?: "",
                roundTripAvailable = o.optBoolean("round_trip_available", false),
                roundTripPrice = o.optInt("round_trip_price", 0),
                roundTripDiscountPercent = o.optDouble("round_trip_discount_percent", 0.0).toInt(),
                roundTripMaxWaitHours = o.optInt("round_trip_max_wait_hours", 4),
                roundTrip = o.optBoolean("round_trip", false),
            )
        }

    /** Создать быстрый заказ → сервер считает цену и ищет водителя (сразу offered | expired).
     *
     *  comment/entrance — «как меня найти» (в селе «Ленина 12» это пять домов без табличек,
     *  а чат открывается только ПОСЛЕ принятия заказа). forName/forPhone — заказ ДЛЯ ДРУГОГО
     *  человека: сын из Уфы вызывает такси маме в Баймаке, водитель должен звонить маме. */
    /**
     * Сменить способ расчёта — можно до самого конца поездки.
     *
     * Про наличные человек вспоминает тогда, когда лезет в карман, то есть уже сидя в машине.
     * Водителю сервер шлёт уведомление сам: тихая смена договорённости — это тот же спор
     * на высадке, только с обиженным водителем.
     */
    /**
     * Включена ли онлайн-оплата на сервере.
     *
     * Спрашивается ДО показа кнопки «Оплатить онлайн»: при выключенном эквайринге кнопка
     * была живой, и человек узнавал правду, только нажав её. Ответ не требует входа —
     * это факт про сервис, а не про человека.
     */
    suspend fun paymentsOnlineEnabled(): Result<Boolean> =
        call("GET", "/health", null, auth = false)
            .map { it.optString("payments", "off") != "off" }

    suspend fun setInstantPaymentMethod(orderId: Int, method: String): Result<String> =
        call("POST", "/instant/orders/$orderId/payment",
             JSONObject().put("method", method), auth = true)
            .map { it.optString("payment_method").ifBlank { "negotiate" } }
            .onSuccess { Analytics.log("instant_payment_$method") }

    suspend fun createInstantOrder(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        fromText: String = "", toText: String = "", category: String = "standard",
        comment: String = "", entrance: String = "", forName: String = "", forPhone: String = "",
        womenOnly: Boolean = false,
        options: List<String> = emptyList(),
        roundTrip: Boolean = false, returnWaitMin: Int = 0,
        stops: List<TaxiStop> = emptyList(),
        paymentMethod: String = "",
    ): Result<InstantOrderDto> {
        val body = instantBody(fromLat, fromLng, toLat, toLng, fromText, toText, category,
            roundTrip, returnWaitMin, stops)
        // Опции салона (детское кресло по возрасту, коляска, собака-проводник, животное,
        // большой багаж). Фильтр на сервере жёсткий: машину без кресла к такому заказу
        // не подберут вообще — это и есть смысл галочки.
        if (options.isNotEmpty()) body.put("options", JSONArray(options))
        // Пустые поля не шлём: сервер их и так примет, но лишний шум в теле запроса ни к чему.
        if (comment.isNotBlank()) body.put("comment", comment.take(300))
        if (entrance.isNotBlank()) body.put("entrance", entrance.take(60))
        if (forName.isNotBlank()) body.put("for_name", forName.take(120))
        if (forPhone.isNotBlank()) body.put("for_phone", forPhone.take(32))
        // «Только женщина за рулём» — в попутках выбор был всегда, в такси появился
        // аудитом 2026-08-06. Фильтр жёсткий: подмены не будет.
        if (womenOnly) body.put("women_only", true)
        // Чем рассчитаются. Пусто — сервер поставит «договоримся на месте», как было раньше.
        if (paymentMethod.isNotBlank()) body.put("payment_method", paymentMethod)
        return call("POST", "/instant/orders", body, auth = true)
            .map { it.toInstantOrderDto() }.onSuccess { Analytics.log("instant_order_create") }
    }

    /** Посчитать смену адреса, ничего не меняя. Человек должен увидеть цену ДО согласия. */
    suspend fun previewDestination(orderId: Int, lat: Double, lng: Double, text: String = ""):
        Result<DestinationQuoteDto> = changeDestination(orderId, lat, lng, text, preview = true)

    /** Сменить адрес назначения. Цену считает сервер — из клиента она не принимается.
     *
     *  Сеть отвалилась → ошибка наружу, и экран честно скажет «не получилось». Запоминать
     *  «на потом» нельзя: человек будет уверен, что адрес сменился, а машина поедет по старому,
     *  и смена прилетит водителю через десять минут, когда он уже почти на месте. */
    suspend fun changeDestination(
        orderId: Int, lat: Double, lng: Double, text: String = "", preview: Boolean = false,
    ): Result<DestinationQuoteDto> {
        val body = JSONObject()
            .put("to_lat", lat).put("to_lng", lng)
            .put("to_text", text.take(200))
            .put("preview", preview)
        return call("POST", "/instant/orders/$orderId/destination", body, auth = true).map { o ->
            DestinationQuoteDto(
                price = o.optInt("price"),
                oldPrice = o.optInt("old_price"),
                drivenKm = o.optDouble("driven_km", 0.0),
                restKm = o.optDouble("rest_km", 0.0),
                distanceKm = o.optDouble("distance_km", 0.0),
                needsDriverOk = o.optBoolean("needs_driver_ok", false),
                askReason = o.optString("ask_reason"),
                applied = o.optBoolean("applied", false),
                waitingDriver = o.optBoolean("waiting_driver", false),
            )
        }
    }

    /** Заменить набор остановок уже в поездке.
     *
     *  Проеденные сервер сохранит сам — их не передаём и убрать нельзя: уже проехали.
     *  Порядок задаём тем, в каком идут точки; переставлять на ходу нельзя (водитель уже
     *  едет к первой, и навигатор ведёт туда же). */
    suspend fun setWaypoints(orderId: Int, stops: List<TaxiStop>): Result<DestinationQuoteDto> =
        call("POST", "/instant/orders/$orderId/waypoints",
            JSONObject().put("waypoints", stopsArray(stops)), auth = true).map { o ->
            DestinationQuoteDto(
                price = o.optInt("price"),
                oldPrice = o.optInt("old_price"),
                drivenKm = o.optDouble("driven_km", 0.0),
                restKm = o.optDouble("rest_km", 0.0),
                distanceKm = o.optDouble("distance_km", 0.0),
                needsDriverOk = o.optBoolean("needs_driver_ok", false),
                askReason = o.optString("ask_reason"),
                applied = o.optBoolean("applied", false),
                waitingDriver = false,
            )
        }

    /** Водитель отмечает «Стоим» на остановке и «Поехали», когда тронулся.
     *
     *  Кнопкой, а не автоматом по координатам: машина, застрявшая в пробке у светофора рядом
     *  с остановкой, начала бы «зарабатывать» сама. */
    suspend fun toggleStop(orderId: Int): Result<Boolean> =
        call("POST", "/instant/orders/$orderId/stop", null, auth = true)
            .map { it.optBoolean("standing", false) }

    /** Водитель: «Понял, вижу новый адрес». Снимает с пассажира тревогу «а он вообще знает?». */
    suspend fun ackDestination(orderId: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$orderId/destination/ack", null, auth = true)
            .map { it.toInstantOrderDto() }

    /** Водитель согласился на крупную смену (межгород / тройная цена). */
    suspend fun acceptDestination(orderId: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$orderId/destination/accept", null, auth = true)
            .map { it.toInstantOrderDto() }

    /** Водитель не может ехать дальше.
     *
     *  Ждали согласия на крупную смену → поездка продолжается по старому адресу.
     *  Иначе поездка ЗАВЕРШАЕТСЯ там, где стоит машина: пассажир платит за проеденное,
     *  водитель получает деньги. Это не отмена — работа сделана. */
    suspend fun declineDestination(orderId: Int, reason: String = "other"): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$orderId/destination/decline",
            JSONObject().put("reason", reason), auth = true)
            .map { it.toInstantOrderDto() }

    /** Что предложить, если в выбранном классе никого нет.
     *
     *  Клиент дёргает через `alternativesAfterSec` секунд поиска. Пустой список — предлагать
     *  нечего, честно ждём дальше. Молчаливой подмены класса нет: решает пассажир. */
    suspend fun getInstantAlternatives(orderId: Int): Result<Pair<Int, List<InstantAlternativeDto>>> =
        call("GET", "/instant/orders/$orderId/alternatives", null, auth = true).map { o ->
            val arr = o.optJSONArray("options") ?: JSONArray()
            o.optInt("after_sec", 20) to (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                InstantAlternativeDto(
                    category = c.optString("category"),
                    price = c.optInt("price"),
                    priceDiff = c.optInt("price_diff"),
                )
            }
        }

    /** Пассажир согласился искать и в соседнем классе. Возвращает новую (уже зафиксированную)
     *  цену: он видел её на экране до нажатия и заплатит ровно её. */
    suspend fun addInstantAlternative(orderId: Int, category: String): Result<Int> =
        call("POST", "/instant/orders/$orderId/alternatives",
            JSONObject().put("category", category), auth = true)
            .map { it.optInt("price") }
            .onSuccess { Analytics.log("instant_alternative_add") }

    /** Мои классы и опции (экран водителя): что доступно машине, что включено, чего не хватает
     *  до остальных классов и сколько водителей набралось в районе. */
    suspend fun getMyTaxiClasses(): Result<DriverClassesDto> =
        call("GET", "/taxi/classes", null, auth = true).map { it.toDriverClassesDto() }

    /** Включить/выключить классы и отметить опции салона — без пере-подачи заявки. */
    suspend fun setMyTaxiClasses(
        classesEnabled: List<String>? = null,
        options: List<String>? = null,
    ): Result<DriverClassesDto> {
        val body = JSONObject()
        if (classesEnabled != null) body.put("car_classes_enabled", JSONArray(classesEnabled))
        if (options != null) body.put("car_options", JSONArray(options))
        return call("POST", "/taxi/classes", body, auth = true).map { it.toDriverClassesDto() }
    }

    private fun JSONObject.toDriverClassesDto(): DriverClassesDto {
        val arr = optJSONArray("classes") ?: JSONArray()
        val opts = optJSONArray("options") ?: JSONArray()
        val all = optJSONArray("all_options") ?: JSONArray()
        val car = optJSONObject("car")
        return DriverClassesDto(
            place = optString("place"),
            classes = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val miss = c.optJSONArray("missing") ?: JSONArray()
                DriverClassDto(
                    carClass = c.optString("car_class"),
                    category = c.optString("category"),
                    available = c.optBoolean("available"),
                    enabled = c.optBoolean("enabled"),
                    missing = (0 until miss.length()).map { m -> miss.optString(m) },
                    driversHave = c.optInt("drivers_have"),
                    driversNeed = c.optInt("drivers_need"),
                    open = c.optBoolean("open", true),
                    first = c.optBoolean("first"),
                )
            },
            options = (0 until opts.length()).map { opts.optString(it) },
            allOptions = (0 until all.length()).map { all.optString(it) },
            colorOk = car?.let { if (it.isNull("color_ok")) null else it.optBoolean("color_ok") },
            carYear = car?.let { if (it.isNull("year")) null else it.optInt("year") },
            seats = car?.let { if (it.isNull("seats")) null else it.optInt("seats") },
        )
    }

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

    /**
     * Оффер ВМЕСТЕ с причиной, почему его нет.
     *
     * Раньше экран водителя знал только «оффера нет» и честно писал «ждём заказ» — хотя ждать
     * было бессмысленно: линия закрыта долгом, отдыхом, паузой или документами. Человек сидел
     * и смотрел на надпись, которая обещала то, чего не будет (аудит сценариев 30.08).
     *
     * `blocked`: null = всё в порядке, ждём по-настоящему; иначе код причины
     * (`not_approved` | `debt` | `rest` | `quality_pause` | `review_pause`).
     */
    suspend fun getDriverOfferState(): Result<DriverOfferStateDto> =
        call("GET", "/instant/driver/offer", null, auth = true).map { o ->
            DriverOfferStateDto(
                offer = if (o.isNull("offer")) null else o.optJSONObject("offer")?.toInstantOrderDto(),
                blocked = o.optString("blocked").ifBlank { null },
            )
        }

    /** Водитель принимает оффер. Гонка/протух → 409 (ApiException) — экран покажет «оффер ушёл». */
    suspend fun instantAccept(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/accept", JSONObject(), auth = true).map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_order_accept") }

    /** Водитель пропускает оффер → matcher предлагает следующему. */
    /** Водитель не берёт заказ. `reason` (far|cheap|direction|busy|break|other) — не наказание,
     *  а диагностика: без причины видно только «не берут», и матчинг продолжает слать те же
     *  заказы тем же людям. Пусто = старое поведение, отказ проходит в любом случае. */
    suspend fun instantDecline(id: Int, reason: String = ""): Result<InstantOrderDto> {
        val body = JSONObject()
        if (reason.isNotBlank()) body.put("reason", reason)
        return call("POST", "/instant/orders/$id/decline", body, auth = true).map { it.toInstantOrderDto() }
    }

    /** Водитель поехал к пассажиру: accepted → arriving. */
    suspend fun instantArrived(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/arrived", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Пассажир сел: arriving → onboard. */
    suspend fun instantOnboard(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/onboard", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Поездка завершена: onboard → done. */
    suspend fun instantDone(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/done", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    /** Пассажир отзывает свой вопрос про новый адрес: едем по старому. */
    suspend fun withdrawDestination(id: Int): Result<Unit> =
        call("POST", "/instant/orders/$id/destination/withdraw", JSONObject(), auth = true).map { }

    /**
     * «Поездка закончилась» — пассажир закрывает поездку, которую водитель не закрыл.
     * Сервер пускает только после расчётного времени поездки плюс запас.
     */
    suspend fun instantPassengerDone(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/passenger-done", JSONObject(), auth = true)
            .map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_passenger_done") }

    /** Отмена заказа (пассажир до посадки / водитель после accept). Причина опциональна. */
    suspend fun instantCancel(id: Int, reason: String = ""): Result<InstantOrderDto> =
        call("POST", "/instant/orders/$id/cancel", JSONObject().put("reason", reason), auth = true).map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_order_cancel") }

    // ---------- Предзаказ такси «на время» (scheduled) ----------
    /** Создать предзаказ на будущее время: тело как у обычного заказа + scheduledAt (ISO). Статус scheduled. */
    suspend fun scheduleInstantOrder(
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        scheduledAt: String, fromText: String = "", toText: String = "", category: String = "standard",
        comment: String = "", entrance: String = "", forName: String = "", forPhone: String = "",
        womenOnly: Boolean = false,
        options: List<String> = emptyList(),
        roundTrip: Boolean = false, returnWaitMin: Int = 0,
        stops: List<TaxiStop> = emptyList(),
        paymentMethod: String = "",
    ): Result<InstantOrderDto> {
        // Предзаказ шлёт ТОТ ЖЕ набор полей, что обычный заказ (аудит сценариев 30.08).
        // Раньше уходили только точки, время и класс: человек выбирал детское кресло,
        // «только женщина за рулём», остановки и заказ для другого — переключал на «На время»,
        // и всё это молча пропадало. Сервер эти поля принимал и раньше, терял их клиент.
        val body = instantBody(fromLat, fromLng, toLat, toLng, fromText, toText, category,
            roundTrip, returnWaitMin, stops).put("scheduled_at", scheduledAt)
        if (options.isNotEmpty()) body.put("options", JSONArray(options))
        if (comment.isNotBlank()) body.put("comment", comment.take(300))
        if (entrance.isNotBlank()) body.put("entrance", entrance.take(60))
        if (forName.isNotBlank()) body.put("for_name", forName.take(120))
        if (forPhone.isNotBlank()) body.put("for_phone", forPhone.take(32))
        if (womenOnly) body.put("women_only", true)
        if (paymentMethod.isNotBlank()) body.put("payment_method", paymentMethod)
        return call("POST", "/instant/schedule", body, auth = true)
            .map { it.toInstantOrderDto() }.onSuccess { Analytics.log("instant_order_schedule") }
    }

    /** Мои предзаказы: ещё ждут (scheduled) + только что активированные ко времени (activated). */
    suspend fun getScheduledOrders(): Result<ScheduledOrdersDto> =
        call("GET", "/instant/scheduled", null, auth = true).map { obj ->
            fun arr(key: String) = (obj.optJSONArray(key) ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it).toInstantOrderDto() }
            }
            ScheduledOrdersDto(scheduled = arr("scheduled"), activated = arr("activated"))
        }

    /** Активировать предзаказ вручную → перевод в поиск (цена пересчитывается на сервере). */
    suspend fun activateScheduledOrder(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/scheduled/$id/activate", JSONObject(), auth = true).map { it.toInstantOrderDto() }
            .onSuccess { Analytics.log("instant_schedule_activate") }

    /** Отменить предзаказ. */
    suspend fun cancelScheduledOrder(id: Int): Result<InstantOrderDto> =
        call("POST", "/instant/scheduled/$id/cancel", JSONObject(), auth = true).map { it.toInstantOrderDto() }

    // ---------- Такси-гейт + онбординг таксиста (580-ФЗ) ----------
    // Пассажирский гейт: доступно ли такси в его точке. Водительский гейт: заявка «Стать таксистом»
    // (самозанятость/разрешение/ОСАГО, возраст 20+, стаж 2+) → модерация админом → выход на линию.

    /** Доступно ли такси в точке (глобальный флаг + города). message — тёплый текст заглушки RU/BA. */
    /**
     * Погода на маршруте: гололёд, метель, туман, мороз — перед выездом.
     *
     * Тексты приходят ГОТОВЫМИ на двух языках: что считать опасным, решает сервер, и пороги
     * можно поправить без выпуска приложения. Сбой — не ошибка экрана: возвращаем «данных нет»,
     * и карточка просто не появляется (поездку погода блокировать не вправе).
     */
    suspend fun getRouteWeather(
        fromLat: Double? = null, fromLng: Double? = null,
        toLat: Double? = null, toLng: Double? = null,
        fromCity: String? = null, toCity: String? = null,
        atIso: String? = null,
    ): Result<RouteWeatherDto> {
        val q = StringBuilder("/weather/route?")
        if (fromLat != null && fromLng != null) q.append("from_lat=$fromLat&from_lng=$fromLng&")
        if (toLat != null && toLng != null) q.append("to_lat=$toLat&to_lng=$toLng&")
        // Названия городов вместо координат: в форме публикации человек печатает «Баймак»,
        // и геокодить их на клиенте ради погоды — лишний запрос к платному геокодеру.
        if (!fromCity.isNullOrBlank()) q.append("from_city=").append(enc(fromCity.trim())).append('&')
        if (!toCity.isNullOrBlank()) q.append("to_city=").append(enc(toCity.trim())).append('&')
        if (!atIso.isNullOrBlank()) q.append("at=").append(enc(atIso)).append('&')
        return call("GET", q.toString().trimEnd('&', '?'), null, auth = false)
            .map { it.toRouteWeatherDto() }
    }

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
     *  Сервер валидирует возраст 20+/стаж 3+/ИНН 10–12 цифр и сроки документов → 400 с текстом (покажем как есть).
     *  Даты (YYYY-MM-DD, пустая строка = не указана) нужны, чтобы допуск истекал вместе с документом,
     *  а не жил вечно: одобрили в июле — возит с просроченным ОСАГО в декабре (аудит 2026-07-26). */
    suspend fun applyTaxi(
        inn: String, permitNumber: String, birthDate: String, licenseSinceYear: Int,
        permitPhotoUrl: String, osagoUrl: String,
        selfieUrl: String, criminalRecordUrl: String, carClass: String = "economy",
        osagoUntil: String = "", permitUntil: String = "", inspectionUntil: String = "",
        osgopUrl: String = "", osgopUntil: String = "",
        carYear: Int? = null, seats: Int? = null, carColor: String = "",
        carAc: Boolean = false, carSedan: Boolean = false, carLeather: Boolean = false,
        carLightSalon: Boolean = false,
        carOptions: List<String> = emptyList(), carClassesEnabled: List<String> = emptyList(),
    ): Result<TaxiApplicationDto> {
        val body = JSONObject()
            .put("inn", inn).put("permit_number", permitNumber)
            .put("birth_date", birthDate).put("license_since_year", licenseSinceYear)
            .put("permit_photo_url", permitPhotoUrl).put("osago_url", osagoUrl)
            .put("selfie_url", selfieUrl).put("criminal_record_url", criminalRecordUrl)
            .put("car_class", carClass)   // §6: заявленный класс, админ подтверждает при approve
        if (osagoUntil.isNotBlank()) body.put("osago_until", osagoUntil)
        if (permitUntil.isNotBlank()) body.put("permit_until", permitUntil)
        if (inspectionUntil.isNotBlank()) body.put("inspection_until", inspectionUntil)
        // ОСГОП — страховка ответственности перевозчика, обязательна для всех с 01.09.2024.
        if (osgopUrl.isNotBlank()) body.put("osgop_url", osgopUrl)
        if (osgopUntil.isNotBlank()) body.put("osgop_until", osgopUntil)
        // Характеристики машины: из них сервер САМ считает класс. Раньше класс заявлял водитель —
        // и любой мог поставить себе «Комфорт», а пассажир получал Гранту по цене Комфорта.
        if (carYear != null) body.put("car_year", carYear)
        if (seats != null) body.put("seats", seats)
        if (carColor.isNotBlank()) body.put("car_color", carColor.take(40))
        body.put("car_ac", carAc).put("car_sedan", carSedan).put("car_leather", carLeather)
        // Светлый салон — равноценная коже дорога в Бизнес (решение 30.08): кожа в райцентре
        // редкость, а светлый ухоженный салон читается пассажиром как «дорого» не хуже.
        body.put("car_light_salon", carLightSalon)
        if (carOptions.isNotEmpty()) body.put("car_options", JSONArray(carOptions))
        if (carClassesEnabled.isNotEmpty()) body.put("car_classes_enabled", JSONArray(carClassesEnabled))
        return call("POST", "/taxi/apply", body, auth = true)
            .map { it.toTaxiApplicationDto() }.onSuccess { Analytics.log("taxi_apply") }
    }

    /** Обновить сроки документов БЕЗ пере-подачи заявки (продлил ОСАГО — не теряй допуск).
     *  Пустая строка = поле не трогаем. Все даты снова в будущем → допуск возвращается сразу. */
    suspend fun updateTaxiDocuments(
        osagoUntil: String = "", permitUntil: String = "", inspectionUntil: String = "",
        osgopUntil: String = "",
        osagoUrl: String = "", permitPhotoUrl: String = "",
    ): Result<TaxiApplicationDto> {
        val body = JSONObject()
        if (osagoUntil.isNotBlank()) body.put("osago_until", osagoUntil)
        if (permitUntil.isNotBlank()) body.put("permit_until", permitUntil)
        if (inspectionUntil.isNotBlank()) body.put("inspection_until", inspectionUntil)
        // ОСГОП продлевается как ОСАГО, раз в год. Раньше срок можно было указать только при
        // первой подаче — и больше никогда (аудит сценариев 30.08).
        if (osgopUntil.isNotBlank()) body.put("osgop_until", osgopUntil)
        if (osagoUrl.isNotBlank()) body.put("osago_url", osagoUrl)
        if (permitPhotoUrl.isNotBlank()) body.put("permit_photo_url", permitPhotoUrl)
        return call("POST", "/taxi/documents", body, auth = true).map { it.toTaxiApplicationDto() }
    }

    /** Предрейсовое подтверждение на сегодня (580-ФЗ, честный минимум): подтверждал ли уже. */
    suspend fun getPretrip(): Result<PretripDto> =
        call("GET", "/taxi/pretrip", null, auth = true).map { o ->
            PretripDto(
                required = o.optBoolean("required"),
                confirmed = o.optBoolean("confirmed"),
                day = o.optString("day"),
                confirmedAt = if (o.isNull("confirmed_at")) null else o.optString("confirmed_at").ifBlank { null },
                note = o.optString("note"),
            )
        }

    /** Подтвердить готовность на сегодня. Все три пункта обязательны — сервер иначе даёт 400. */
    suspend fun confirmPretrip(note: String = ""): Result<PretripDto> =
        call(
            "POST", "/taxi/pretrip",
            JSONObject().put("health_ok", true).put("car_ok", true).put("no_alcohol", true)
                .put("note", note.take(300)),
            auth = true,
        ).map { o ->
            PretripDto(
                required = o.optBoolean("required"),
                confirmed = o.optBoolean("confirmed"),
                day = o.optString("day"),
                confirmedAt = if (o.isNull("confirmed_at")) null else o.optString("confirmed_at").ifBlank { null },
                note = o.optString("note"),
            )
        }

    // ---------- Фотоконтроль машины (580-ФЗ) ----------
    /**
     * Что снять и до какого числа. Такси — четыре стороны кузова и салон, курьер — две
     * стороны и багажник. `enabled=false` → контроль выключен на сервере, экран не нужен.
     */
    suspend fun getCarPhoto(mode: String = "taxi"): Result<CarPhotoDto> =
        call("GET", "/carphoto?mode=$mode", null, auth = true).map { it.toCarPhotoDto() }

    /**
     * Прислать ОДИН кадр. Ответ приходит сразу: подошёл или что переснять — человек в этот
     * момент стоит у машины с телефоном, и переснять он может прямо сейчас.
     */
    suspend fun uploadCarPhoto(mode: String, slot: String, bytes: ByteArray, ext: String = "jpg",
                               kind: String = "periodic"): Result<CarPhotoShotDto> =
        callMultipart("/carphoto/photo?mode=$mode&slot=$slot&kind=$kind", bytes, ext, "photo.$ext").map { o ->
            CarPhotoShotDto(
                url = o.optString("url"),
                slot = o.optString("slot"),
                ok = o.optBoolean("ok"),
                reason = o.optString("reason"),
                missing = o.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        }

    /** Отправить набор кадров. Принято сразу или ушло человеку на просмотр. */
    suspend fun submitCarPhoto(mode: String = "taxi", kind: String = "periodic"): Result<CarPhotoDto> =
        call("POST", "/carphoto/submit", JSONObject().put("mode", mode).put("kind", kind), auth = true)
            .map { it.toCarPhotoDto() }

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

    /**
     * Админ: журнал предрейсовых подтверждений за день (580-ФЗ).
     *
     * Водитель отмечается каждый день сам (самочувствие, машина, без алкоголя), запись живёт
     * на сервере — но достать её из приложения было нельзя ни одним способом. При разборе ДТП
     * или проверке это единственное доказательство, что водитель в тот день заявил о готовности.
     *
     * @param day дата в формате `ГГГГ-ММ-ДД`; пусто = сегодня (решает сервер).
     */
    suspend fun adminPretripJournal(day: String? = null): Result<PretripJournalDto> {
        val q = if (day.isNullOrBlank()) "" else "?day=$day"
        return call("GET", "/admin/taxi/pretrip$q", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            PretripJournalDto(
                day = obj.optString("day"),
                items = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PretripEntryDto(
                        driverId = o.optInt("driver_id"),
                        name = o.optString("name"),
                        phone = o.optString("phone"),
                        confirmedAt = o.optString("confirmed_at"),
                        note = o.optString("note"),
                    )
                },
            )
        }
    }

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

    /**
     * НП по названию из нашего справочника. Понимает форму «Берёзовка (Иглинский р-н)» —
     * именно так поле «откуда/куда» записывает выбранную деревню.
     *
     * Нужен там, где по названию нужны координаты (маршрут на карте, расчёт доставки):
     * сёл в справочнике тысячи, и про них он знает точнее геокодера. Не нашли — null,
     * вызывающий идёт к геокодеру как раньше.
     */
    suspend fun settlementByName(raw: String): SettlementDto? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val name = text.substringBefore('(').trim().ifEmpty { text }
        val district = text.substringAfter('(', "").substringBefore(')').trim()
        val hits = searchSettlements(name, 10).getOrNull().orEmpty()
        val sameName = hits.filter { it.nameRu.equals(name, true) || it.nameBa.equals(name, true) }
        return sameName.firstOrNull { district.isNotEmpty() && it.district.equals(district, true) }
            ?: sameName.firstOrNull()
    }

    /** Пресеты популярных межгород-маршрутов (Сибай–Магнитогорск, Баймак–Уфа…) — чипы в UI.
     *  Не путать с getPopularRoutes() (/popular-routes — живая статистика реальных поездок). */
    suspend fun getSettlementPopularRoutes(): Result<List<SettlementRouteDto>> = cachedGet("settlement-popular-routes", TTL_SLOW) {
        call("GET", "/settlements/popular-routes", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("routes") ?: JSONArray()
            // V6: битый элемент (нет объекта from/to) пропускаем, а не роняем весь экран —
            // getJSONObject кидал бы исключение мимо Result (в .map), краша вызывающий экран.
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = o.optJSONObject("from") ?: return@mapNotNull null
                val t = o.optJSONObject("to") ?: return@mapNotNull null
                SettlementRouteDto(from = f.toSettlementDto(), to = t.toSettlementDto())
            }
        }
    }

    /** Текущая зона работы таксиста (город / межгород / соседний регион). */
    suspend fun getInstantZone(): Result<InstantZoneDto> =
        call("GET", "/instant/zone", null, auth = true).map { it.toInstantZoneDto() }

    /**
     * Выбор зоны работы: база `city` (+work_city) или `district` (+work_district),
     * плюс тумблеры «выезд загород» (опц. с направлением) и «соседние регионы».
     * Только водитель с одобренной заявкой таксиста (сервер вернёт 403/409 понятной строкой).
     */
    suspend fun setInstantZone(
        workZone: String,
        workCity: String? = null,
        workDistrict: String? = null,
        workIntercity: Boolean = false,
        workRegions: Boolean = false,
        workDirectionId: Int? = null,
    ): Result<InstantZoneDto> =
        call(
            "POST", "/instant/zone",
            JSONObject()
                .put("work_zone", workZone)
                .put("work_city", workCity ?: JSONObject.NULL)
                .put("work_district", workDistrict ?: JSONObject.NULL)
                .put("work_intercity", workIntercity)
                .put("work_regions", workRegions)
                .put("work_direction_id", workDirectionId ?: JSONObject.NULL),
            auth = true,
        ).map { it.toInstantZoneDto() }.onSuccess { Analytics.log("instant_zone_set") }

    /** Районы для выбора зоны («вожу по Абзелиловскому району»). Публичный справочник. */
    suspend fun searchDistricts(q: String = "", limit: Int = 60): Result<List<DistrictDto>> =
        call("GET", "/settlements/districts?q=${enc(q)}&limit=$limit", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                DistrictDto(o.optString("district"), o.optString("region"), o.optInt("settlements"))
            }
        }

    /** Сводка смены таксиста (волна 2, §8 Отдых): сколько на линии, осталось, блок отдыха,
     *  когда разблокировка, использован ли «один попутчик домой». */
    /** Свободные машины «на линии» рядом (анонимные точки + ≈ETA) — для карты такси. Без личности. */
    suspend fun getNearbyDrivers(lat: Double, lng: Double): Result<List<NearbyDriverDto>> =
        call("GET", "/instant/nearby-drivers?lat=$lat&lng=$lng", null, auth = true).map { o ->
            val arr = o.optJSONArray("drivers") ?: org.json.JSONArray()
            (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                NearbyDriverDto(
                    d.optDouble("lat"), d.optDouble("lng"), d.optInt("eta_min", 1),
                    category = d.optString("category").takeIf { it.isNotBlank() },
                )
            }
        }

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
                weekBlocked = o.optBoolean("week_blocked"),
                weekLimitHours = o.optInt("week_limit_hours", 40),
                weekSeconds = o.optInt("week_seconds"),
                returnRideUsed = o.optBoolean("return_ride_used"),
                earningsToday = o.optInt("earnings_today"),
                grossTodayKop = o.optInt("gross_today_kop", o.optInt("earnings_today") * 100),
                feeTodayKop = o.optInt("fee_today_kop"),
                netTodayKop = o.optInt("net_today_kop", o.optInt("earnings_today") * 100),
                ordersToday = o.optInt("orders_today"),
                feePercent = o.optDouble("fee_percent", 0.0),
                tenureDays = o.optInt("tenure_days"),
                tripsDone = o.optInt("trips_done"),
                feeTiers = o.optJSONArray("fee_tiers")?.let { a -> (0 until a.length()).map { a.optDouble(it) } } ?: emptyList(),
                feeTierTrips = o.optJSONArray("fee_tier_trips")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
                feeNextPercent = if (o.isNull("fee_next_percent")) null else o.optDouble("fee_next_percent"),
                feeTripsToNext = if (o.isNull("fee_trips_to_next")) null else o.optInt("fee_trips_to_next"),
                promoActive = o.optBoolean("promo_active"),
                promoDaysLeft = if (o.isNull("promo_days_left")) null else o.optInt("promo_days_left"),
                feeAfterPromoPercent = if (o.isNull("fee_after_promo_percent")) null
                                       else o.optDouble("fee_after_promo_percent"),
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
        retryOnNetwork: Boolean = true,  // M5: повторять транзитные обрывы связи с backoff (по умолчанию вкл.)
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        testTrace?.invoke("вошли в вызов $method $path")
        val usedToken = if (auth) token else null
        // M5: паузы backoff между попытками ТОЛЬКО при сетевом обрыве ДО получения ответа.
        // Ответ с HTTP-кодом (4xx/5xx) — это ApiException и НЕ повторяется, отмена корутины
        // пробрасывается.
        //
        // ⚠️ Здесь раньше стояло рассуждение «даже POST безопасен: повтор идёт лишь когда ответ
        // не получен вовсе, значит сервер запрос не обработал». Оно НЕВЕРНО (аудит 2026-08-07).
        // Обрыв бывает двух совершенно разных видов, и оба прилетают одним IOException:
        //   • не смогли ДОЗВОНИТЬСЯ (нет сети, хост не резолвится, соединение отвергнуто) —
        //     запрос не ушёл, повтор безопасен;
        //   • ответ не пришёл ВОВРЕМЯ (SocketTimeoutException на чтении) — запрос ушёл, сервер
        //     мог обработать его целиком, просто ответ не успел за 15 секунд. Повтор здесь
        //     создаёт ВТОРУЮ бронь, вторую посылку, второй платёж.
        // Дедуп на сервере есть только у двух ручек (бронь и заказ такси) — остальные ловили
        // дубль молча. Поэтому: GET повторяем всегда (чтение безопасно), а меняющие запросы —
        // только когда обрыв точно случился ДО отправки.
        val idempotent = method.equals("GET", ignoreCase = true)
        // В тестах повторов нет: один вызов и так укладывается в секунду, а повтор только
        // продлевал бы жизнь брошенному запросу за границу своего тестового класса.
        val backoff = if (retryOnNetwork && testTimeoutMs == null) longArrayOf(400L, 900L) else LongArray(0)
        var attempt = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = connectMs
                    readTimeout = readMs
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
                // Сервер ОТВЕТИЛ — связь есть, даже если ответ 4xx/5xx. Гасим флаг «нет связи»:
                // иначе плашка висела бы после восстановления сети до следующего перезапуска.
                serverUnreachable.value = false
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                return@withContext if (code in 200..299) {
                    val obj = when {
                        text.isBlank() -> JSONObject()
                        text.trimStart().startsWith("[") -> JSONObject().put("items", JSONArray(text))
                        else -> JSONObject(text)
                    }
                    testTrace?.invoke("ВЫШЛИ из вызова $method $path успешно (код $code)")
                    Result.success(obj)
                } else if (code == 401 && auth && !isRetry && !refreshToken.isNullOrBlank()) {
                    // Access протух → пробуем обновить по refresh-токену и повторить ОДИН раз.
                    conn.disconnect(); conn = null
                    if (tryRefresh(usedToken)) call(method, path, body, auth, isRetry = true)
                    else {
                        logout()   // refresh мёртв → чистим локальную сессию, иначе isLoggedIn() врёт true и юзер «залипает» с 401 на каждом запросе
                        sessionExpired.value = true   // сигнал UI: показать «войди снова» и уйти на Login (не молчать пустыми экранами)
                        Result.failure(ApiException(401, genericByStatus(401, langBa)))
                    }
                } else if (code == 401 && auth) {
                    // Токен мёртв, а обновить его НЕЧЕМ: refresh пуст (сессия из старой версии,
                    // не сохранился, вычищен) либо повтор после обновления снова дал 401.
                    //
                    // Раньше этот случай уходил в обычную ошибку — и человек «залипал» намертво.
                    // Поймано вживую (2026-08-13, прокси между приложением и сервером): 401 шёл
                    // на КАЖДЫЙ авторизованный запрос — /me, /requests/mine, /trusted-contacts,
                    // /push/register. При этом приложение считало себя залогиненным: профиль
                    // показывал имя из кэша, а экраны без кэша писали «Не удалось загрузить.
                    // Проверь интернет» — при живом интернете. Выйти и войти заново было НЕЛЬЗЯ:
                    // кнопки выхода на таком экране нет, а сессия сама не заканчивалась никогда.
                    // Единственным лечением была переустановка приложения.
                    //
                    // Условие «есть refresh-токен» описывало ЧАСТЫЙ случай, а не ВСЕ. Мёртвая
                    // сессия — это всегда конец сессии, независимо от того, чем её пытались лечить.
                    logout()
                    sessionExpired.value = true
                    Result.failure(ApiException(401, genericByStatus(401, langBa)))
                } else {
                    Result.failure(ApiException(code, errorMessage(code, text), detailCode(text)))
                }
            } catch (ce: CancellationException) {
                testTrace?.invoke("вызов $method $path ОТМЕНЁН (корутину закрыли снаружи)")
                throw ce   // отмена корутины — не глотаем и не повторяем, пробрасываем дальше
            } catch (e: IOException) {
                // Обрыв связи ДО получения ответа. Повторяем, только если это безопасно:
                // чтение (GET) — всегда, меняющий запрос — лишь когда до сервера мы вообще
                // не дозвонились. Таймаут ЧТЕНИЯ у POST не повторяем: запрос уже ушёл, и
                // сервер мог его выполнить — второй такой же создаст дубль (см. выше).
                val neverSent = e is ConnectException || e is UnknownHostException || e is NoRouteToHostException
                if (attempt < backoff.size && (idempotent || neverSent)) {
                    conn?.disconnect(); conn = null
                    delay(backoff[attempt]); attempt++
                    continue
                }
                serverUnreachable.value = true   // повторы исчерпаны — связи действительно нет
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(IllegalStateException("call() loop exited unexpectedly"))
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

    // ═══════════ M1: Купонный маркетплейс «Скидки по пути» + кабинет партнёра ═══════════
    // Витрина скидок от местных заведений вдоль маршрута. Клиент активирует → получает код →
    // показывает в заведении → партнёр гасит код. Оплата подписки партнёра «на доверии» (СБП).

    private fun strList(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
    private fun nStr(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
    private fun nInt(o: JSONObject, key: String): Int? = if (o.isNull(key)) null else o.optInt(key)
    private fun nDbl(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.optDouble(key)

    private fun parseCouponPartner(o: JSONObject) = CouponPartnerDto(
        id = o.optInt("id"), name = o.optString("name"), category = o.optString("category"),
        city = o.optString("city"), address = o.optString("address"),
        lat = nDbl(o, "lat"), lng = nDbl(o, "lng"), phone = o.optString("phone"),
    )
    private fun parseCoupon(o: JSONObject) = CouponDto(
        id = o.optInt("id"),
        partner = o.optJSONObject("partner")?.let { parseCouponPartner(it) }
            ?: CouponPartnerDto(0, "", "", ""),
        title = o.optString("title"), description = o.optString("description"),
        discountText = o.optString("discount_text"), city = o.optString("city"),
        routeHint = strList(o, "route_hint"),
        validFrom = nStr(o, "valid_from"), validUntil = nStr(o, "valid_until"),
        limitTotal = o.optInt("limit_total"), limitPerUser = o.optInt("limit_per_user"),
        redeemedCount = o.optInt("redeemed_count"), remaining = nInt(o, "remaining"),
        premium = o.optBoolean("premium"), status = o.optString("status", "active"),
    )
    private fun parsePartner(o: JSONObject) = PartnerDto(
        id = o.optInt("id"), name = o.optString("name"), category = o.optString("category"),
        city = o.optString("city"), address = o.optString("address"), phone = o.optString("phone"),
        description = o.optString("description"), lat = nDbl(o, "lat"), lng = nDbl(o, "lng"),
        status = o.optString("status", "pending"), rejectReason = o.optString("reject_reason"),
        subscriptionPlan = o.optString("subscription_plan"), subscriptionUntil = nStr(o, "subscription_until"),
        subscriptionActive = o.optBoolean("subscription_active"), hasPremium = o.optBoolean("has_premium"),
        createdAt = o.optString("created_at"),
    )
    private fun parsePartnerCoupon(o: JSONObject) = PartnerCouponDto(
        id = o.optInt("id"), partnerId = o.optInt("partner_id"),
        title = o.optString("title"), description = o.optString("description"),
        discountText = o.optString("discount_text"), city = o.optString("city"),
        routeHint = strList(o, "route_hint"), validFrom = nStr(o, "valid_from"), validUntil = nStr(o, "valid_until"),
        limitTotal = o.optInt("limit_total"), limitPerUser = o.optInt("limit_per_user"),
        redeemedCount = o.optInt("redeemed_count"), activations = o.optInt("activations"),
        premium = o.optBoolean("premium"), status = o.optString("status", "draft"),
        createdAt = o.optString("created_at"),
        review = o.optString("review", "approved"), reviewNote = o.optString("review_note"),
        reportsCount = o.optInt("reports_count"),
    )

    /** Витрина купонов (публичная). Опц. фильтр по городу и по маршруту (from-to или город). */
    suspend fun getCoupons(city: String? = null, route: String? = null): Result<List<CouponDto>> {
        val q = buildList {
            city?.takeIf { it.isNotBlank() }?.let { add("city=" + enc(it)) }
            route?.takeIf { it.isNotBlank() }?.let { add("route=" + enc(it)) }
        }.joinToString("&")
        val path = "/coupons" + if (q.isNotBlank()) "?$q" else ""
        return call("GET", path, null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseCoupon(arr.getJSONObject(it)) }
        }
    }

    /** Один купон (публичный). 404 если недоступен. */
    suspend fun getCoupon(id: Int): Result<CouponDto> =
        call("GET", "/coupons/$id", null, auth = false).map { parseCoupon(it) }

    /** Активировать купон → получить код для показа в заведении. */
    suspend fun activateCoupon(id: Int): Result<ActivatedCouponDto> =
        call("POST", "/coupons/$id/activate", null, auth = true).map { o ->
            ActivatedCouponDto(
                code = o.optString("code"), status = o.optString("status", "reserved"),
                reservedAt = o.optString("reserved_at"),
                coupon = o.optJSONObject("coupon")?.let { parseCoupon(it) } ?: CouponDto.empty(),
            )
        }.onSuccess { Analytics.log("coupon_activate") }

    /** Мои активированные купоны (все статусы). */
    suspend fun getMyCoupons(): Result<List<MyCouponDto>> =
        call("GET", "/my/coupons", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MyCouponDto(
                    code = o.optString("code"), status = o.optString("status", "reserved"),
                    reservedAt = o.optString("reserved_at"), redeemedAt = nStr(o, "redeemed_at"),
                    coupon = o.optJSONObject("coupon")?.let { parseCoupon(it) } ?: CouponDto.empty(),
                )
            }
        }

    /** Тарифы подписки партнёра (публичные). */
    suspend fun getPartnerPlans(): Result<List<PartnerPlanDto>> =
        call("GET", "/partner/plans", null, auth = false).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PartnerPlanDto(
                    code = o.optString("code"), title = o.optString("title"), titleBa = o.optString("title_ba"),
                    amountKop = o.optInt("amount_kop"), periodDays = o.optInt("period_days"),
                    premium = o.optBoolean("premium"),
                )
            }
        }

    /** Зарегистрировать свой бизнес. 409 — уже есть, 422 — данные. */
    suspend fun createPartner(
        name: String, category: String, city: String, address: String,
        phone: String, description: String, lat: Double? = null, lng: Double? = null,
    ): Result<PartnerDto> = call(
        "POST", "/partner",
        JSONObject().put("name", name).put("category", category).put("city", city)
            .put("address", address).put("phone", phone).put("description", description)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL),
        auth = true,
    ).map { parsePartner(it) }.onSuccess { Analytics.log("partner_create") }

    /** Мой бизнес + выписка (сколько погашено, к оплате). partner==null → бизнеса ещё нет. */
    suspend fun getPartnerMe(): Result<PartnerMeDto> =
        call("GET", "/partner/me", null, auth = true).map { o ->
            val p = o.optJSONObject("partner")?.takeIf { !o.isNull("partner") }?.let { parsePartner(it) }
            val s = o.optJSONObject("statement")?.let {
                StatementDto(it.optInt("redeemed_total"), it.optInt("fee_per_redemption_kop"), it.optInt("amount_kop"))
            }
            PartnerMeDto(partner = p, statement = s)
        }

    /** Обновить свой бизнес (после отклонения — правка и повторная отправка). */
    suspend fun updatePartner(
        id: Int, name: String, category: String, city: String, address: String,
        phone: String, description: String, lat: Double? = null, lng: Double? = null,
    ): Result<PartnerDto> = call(
        "POST", "/partner/$id",
        JSONObject().put("name", name).put("category", category).put("city", city)
            .put("address", address).put("phone", phone).put("description", description)
            .put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL),
        auth = true,
    ).map { parsePartner(it) }

    /** Оформить подписку по тарифу → реквизиты «на доверии» (СБП). 409 — на проверке, 422 — тариф. */
    suspend fun subscribePartner(plan: String): Result<PartnerSubscribeDto> =
        call("POST", "/partner/subscribe", JSONObject().put("plan", plan), auth = true).map { o ->
            PartnerSubscribeDto(
                paymentId = o.optInt("payment_id"), amountKop = o.optInt("amount_kop"),
                plan = o.optString("plan"), status = o.optString("status", "pending"),
            )
        }.onSuccess { Analytics.log("partner_subscribe") }

    /** Мои купоны (кабинет партнёра). */
    suspend fun getPartnerCoupons(): Result<List<PartnerCouponDto>> =
        call("GET", "/partner/coupons", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parsePartnerCoupon(arr.getJSONObject(it)) }
        }

    /** Создать купон (кабинет партнёра). */
    suspend fun createPartnerCoupon(
        title: String, description: String, discountText: String, city: String,
        routeHint: List<String>, limitTotal: Int, limitPerUser: Int, premium: Boolean,
        validFrom: String? = null, validUntil: String? = null,
    ): Result<PartnerCouponDto> = call(
        "POST", "/partner/coupons", couponBody(title, description, discountText, city, routeHint, limitTotal, limitPerUser, premium, validFrom, validUntil),
        auth = true,
    ).map { parsePartnerCoupon(it) }.onSuccess { Analytics.log("partner_coupon_create") }

    /** Редактировать купон. */
    suspend fun updatePartnerCoupon(
        id: Int, title: String, description: String, discountText: String, city: String,
        routeHint: List<String>, limitTotal: Int, limitPerUser: Int, premium: Boolean,
        validFrom: String? = null, validUntil: String? = null,
    ): Result<PartnerCouponDto> = call(
        "POST", "/partner/coupons/$id", couponBody(title, description, discountText, city, routeHint, limitTotal, limitPerUser, premium, validFrom, validUntil),
        auth = true,
    ).map { parsePartnerCoupon(it) }

    private fun couponBody(
        title: String, description: String, discountText: String, city: String,
        routeHint: List<String>, limitTotal: Int, limitPerUser: Int, premium: Boolean,
        validFrom: String?, validUntil: String?,
    ) = JSONObject()
        .put("title", title).put("description", description).put("discount_text", discountText)
        .put("city", city).put("route_hint", JSONArray(routeHint))
        .put("limit_total", limitTotal).put("limit_per_user", limitPerUser).put("premium", premium)
        .put("valid_from", validFrom ?: JSONObject.NULL).put("valid_until", validUntil ?: JSONObject.NULL)

    /** Сменить статус купона: draft|active|paused|archived. */
    suspend fun setPartnerCouponStatus(id: Int, status: String): Result<Unit> =
        call("POST", "/partner/coupons/$id/status", JSONObject().put("status", status), auth = true).map { }

    /** Статистика купона: активации, погашения, к оплате. */
    suspend fun getPartnerCouponStats(id: Int): Result<CouponStatDto> =
        call("GET", "/partner/coupons/$id/stats", null, auth = true).map { o ->
            CouponStatDto(
                couponId = o.optInt("coupon_id"), title = o.optString("title"), status = o.optString("status"),
                activations = o.optInt("activations"), redeemed = o.optInt("redeemed"),
                feePerRedemptionKop = o.optInt("fee_per_redemption_kop"), amountKop = o.optInt("amount_kop"),
            )
        }

    /** Погасить код клиента (партнёр). 404 — не найден/чужой, 409 — уже погашён/не действует. */
    suspend fun redeemCoupon(code: String): Result<RedeemResultDto> =
        call("POST", "/coupons/redeem", JSONObject().put("code", code), auth = true).map { o ->
            RedeemResultDto(
                couponTitle = o.optString("coupon_title"), discountText = o.optString("discount_text"),
                customerName = o.optString("customer_name"),
            )
        }.onSuccess { Analytics.log("coupon_redeem") }

    /** Админ: все бизнесы-партнёры для модерации. */
    suspend fun getAdminPartners(): Result<List<AdminPartnerDto>> =
        call("GET", "/admin/partners", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AdminPartnerDto(
                    id = o.optInt("id"), ownerId = o.optInt("owner_id"), name = o.optString("name"),
                    category = o.optString("category"), city = o.optString("city"), address = o.optString("address"),
                    phone = o.optString("phone"), description = o.optString("description"),
                    status = o.optString("status", "pending"), rejectReason = o.optString("reject_reason"),
                    subscriptionPlan = o.optString("subscription_plan"), subscriptionUntil = nStr(o, "subscription_until"),
                    subscriptionActive = o.optBoolean("subscription_active"), createdAt = o.optString("created_at"),
                    reviewedAt = nStr(o, "reviewed_at"),
                )
            }
        }

    /** Админ: одобрить бизнес-партнёра (→active). */
    suspend fun approvePartner(id: Int): Result<Unit> =
        call("POST", "/admin/partners/$id/approve", null, auth = true).map { }

    /** Админ: отклонить бизнес-партнёра с причиной. */
    suspend fun rejectPartner(id: Int, reason: String): Result<Unit> =
        call("POST", "/admin/partners/$id/reject", JSONObject().put("reason", reason), auth = true).map { }

    /** Админ: одна очередь «что я ещё не смотрел» — бизнесы и купоны вместе. */
    suspend fun getModerationQueue(): Result<ModerationQueueDto> =
        call("GET", "/admin/moderation", null, auth = true).map { o ->
            val pArr = o.optJSONArray("partners") ?: JSONArray()
            val cArr = o.optJSONArray("coupons") ?: JSONArray()
            ModerationQueueDto(
                partners = (0 until pArr.length()).map { i ->
                    val p = pArr.getJSONObject(i)
                    AdminPartnerDto(
                        id = p.optInt("id"), ownerId = p.optInt("owner_id"), name = p.optString("name"),
                        category = p.optString("category"), city = p.optString("city"),
                        address = p.optString("address"), phone = p.optString("phone"),
                        description = p.optString("description"), status = p.optString("status", "pending"),
                        rejectReason = p.optString("reject_reason"),
                        subscriptionPlan = p.optString("subscription_plan"),
                        subscriptionUntil = nStr(p, "subscription_until"),
                        subscriptionActive = p.optBoolean("subscription_active"),
                        createdAt = p.optString("created_at"), reviewedAt = nStr(p, "reviewed_at"),
                    )
                },
                coupons = (0 until cArr.length()).map { i ->
                    val c = cArr.getJSONObject(i)
                    AdminCouponDto(
                        id = c.optInt("id"), partnerId = c.optInt("partner_id"),
                        partnerName = c.optString("partner_name"), city = c.optString("city"),
                        title = c.optString("title"), description = c.optString("description"),
                        discountText = c.optString("discount_text"), status = c.optString("status"),
                        review = c.optString("review", "pending"), reviewFlag = c.optString("review_flag"),
                        reviewNote = c.optString("review_note"), reportsCount = c.optInt("reports_count"),
                        visible = c.optBoolean("visible"), createdAt = c.optString("created_at"),
                    )
                },
                total = o.optInt("total"),
            )
        }

    /** Админ: «посмотрел, всё в порядке» — купон уходит из очереди. */
    suspend fun approveCoupon(id: Int): Result<Unit> =
        call("POST", "/admin/coupons/$id/approve", null, auth = true).map { }

    /** Админ: снять купон с витрины с причиной (её увидит партнёр). */
    suspend fun blockCoupon(id: Int, reason: String): Result<Unit> =
        call("POST", "/admin/coupons/$id/block", JSONObject().put("reason", reason), auth = true).map { }

    /** Пожаловаться на купон: ставит его в очередь к админу, но НЕ снимает с витрины. */
    suspend fun reportCoupon(id: Int, reason: String): Result<Boolean> =
        call("POST", "/coupons/$id/report", JSONObject().put("reason", reason), auth = true)
            .map { it.optBoolean("already", false) }

    // ═══════════ M2: Промокоды и кампании ═══════════

    /** Применить промокод. 404 не найден, 422 срок истёк, 409 (уже активировал / свой код / исчерпан). */
    suspend fun applyPromo(code: String): Result<PromoApplyResultDto> =
        call("POST", "/promo/apply", JSONObject().put("code", code.trim().uppercase()), auth = true).map { o ->
            PromoApplyResultDto(
                ok = o.optBoolean("ok", true), kind = o.optString("kind"),
                perkValue = o.optInt("perk_value"),
                messageRu = o.optString("message_ru"), messageBa = o.optString("message_ba"),
                discountKop = o.optInt("discount_kop"),
            )
        }.onSuccess { Analytics.log("promo_apply") }

    /** Мой активированный промокод (один на аккаунт) или null, если ещё не вводил. */
    suspend fun getMyPromo(): Result<MyPromoDto?> =
        call("GET", "/promo/mine", null, auth = true).map { o ->
            val p = o.optJSONObject("promo") ?: return@map null
            MyPromoDto(
                code = p.optString("code"), title = p.optString("title"),
                kind = p.optString("kind"), perkValue = p.optInt("perk_value"),
                redeemedAt = nStr(o, "redeemed_at"),
                // Судьба скидки на такси лежит рядом с promo, а не внутри него.
                discountKop = o.optInt("discount_kop"),
                discountAvailable = o.optBoolean("discount_available"),
                discountUsedOrderId = if (o.isNull("discount_used_order_id")) null else o.optInt("discount_used_order_id"),
            )
        }

    /** Статистика по коду (для владельца/админа): воронка applied → active. */
    suspend fun getPromoStats(code: String): Result<PromoStatDto> =
        call("GET", "/promo/${enc(code.trim().uppercase())}/stats", null, auth = true).map { o ->
            PromoStatDto(
                code = o.optString("code"), title = o.optString("title"),
                campaign = o.optString("campaign"),
                applied = o.optInt("applied"), active = o.optInt("active"),
            )
        }

    private fun parseAdminPromo(o: JSONObject) = AdminPromoDto(
        id = o.optInt("id"), code = o.optString("code"), title = o.optString("title"),
        description = o.optString("description"), ownerId = if (o.isNull("owner_id")) null else o.optInt("owner_id"),
        campaign = o.optString("campaign"), kind = o.optString("kind"), perkValue = o.optInt("perk_value"),
        limitTotal = o.optInt("limit_total"), limitPerUser = o.optInt("limit_per_user"),
        redeemedCount = o.optInt("redeemed_count"), applied = o.optInt("applied"), active = o.optInt("active"),
        validFrom = nStr(o, "valid_from"), validUntil = nStr(o, "valid_until"),
        activeFlag = o.optBoolean("active_flag", true), createdAt = o.optString("created_at"),
    )

    /** Админ: список всех промокодов/кампаний со счётчиками. */
    suspend fun adminListPromo(): Result<List<AdminPromoDto>> =
        call("GET", "/admin/promo", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseAdminPromo(arr.getJSONObject(it)) }
        }

    /** Админ: создать промокод/кампанию. 409 — дубль кода. */
    suspend fun adminCreatePromo(
        code: String, title: String, description: String, campaign: String,
        kind: String, perkValue: Int, limitTotal: Int, limitPerUser: Int,
        ownerPhone: String? = null, validFrom: String? = null, validUntil: String? = null,
    ): Result<AdminPromoDto> {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("title", title.trim())
            .put("description", description.trim())
            .put("campaign", campaign.trim())
            .put("kind", kind)
            .put("perk_value", perkValue)
            .put("limit_total", limitTotal)
            .put("limit_per_user", limitPerUser)
        ownerPhone?.takeIf { it.isNotBlank() }?.let { body.put("owner_phone", it.trim()) }
        validFrom?.takeIf { it.isNotBlank() }?.let { body.put("valid_from", it) }
        validUntil?.takeIf { it.isNotBlank() }?.let { body.put("valid_until", it) }
        return call("POST", "/admin/promo", body, auth = true).map { parseAdminPromo(it) }
            .onSuccess { Analytics.log("promo_create") }
    }

    /** Админ: включить/выключить кампанию. */
    suspend fun adminSetPromoStatus(id: Int, active: Boolean): Result<Unit> =
        call("POST", "/admin/promo/$id/status", JSONObject().put("active", active), auth = true).map { }

    // ═══════════ M3: Доставка посылок (попутчик везёт бандероль) ═══════════
    // Отправитель создаёт посылку → получает код вручения → курьер (попутный водитель) берёт,
    // везёт, при вручении вводит код получателя. Сбор с курьера «на доверии» (fee_kop).

    private fun parseParcel(o: JSONObject) = ParcelDto(
        id = o.optInt("id"),
        senderId = o.optInt("sender_id"),
        courierId = nInt(o, "courier_id"),
        fromCity = o.optString("from_city"), toCity = o.optString("to_city"),
        // «Где забрать / куда привезти». optString даёт "" на старом сервере без этих полей —
        // клиент новее бэкенда не должен падать, он просто не рисует блок.
        fromAddress = o.optString("from_address"), toAddress = o.optString("to_address"),
        fromLat = nDbl(o, "from_lat"), fromLng = nDbl(o, "from_lng"),
        toLat = nDbl(o, "to_lat"), toLng = nDbl(o, "to_lng"),
        size = o.optString("size"),
        description = o.optString("description"),
        receiverName = o.optString("receiver_name"),
        receiverPhone = o.optString("receiver_phone"),
        senderName = o.optString("sender_name"),
        senderPhone = o.optString("sender_phone"),
        feeKop = o.optInt("fee_kop"),
        status = o.optString("status", "created"),
        confirmCode = o.optString("confirm_code"),
        createdAt = o.optString("created_at"),
        acceptedAt = nStr(o, "accepted_at"),
        deliveredAt = nStr(o, "delivered_at"),
        courier = o.optJSONObject("courier")?.takeIf { !o.isNull("courier") }?.let {
            ParcelCourierDto(
                id = it.optInt("id"), name = it.optString("name"),
                rating = if (it.isNull("rating")) null else it.optDouble("rating", 0.0),
                ratingCount = it.optInt("rating_count"),
                phone = it.optString("phone"),
            )
        },
        weightKg = o.optDouble("weight_kg", 0.0),
        cargoType = o.optString("cargo_type"),
        fragile = o.optBoolean("fragile", false),
        deliveryType = o.optString("delivery_type", "poputka"),
        urgency = o.optString("urgency"),
        declaredValueKop = o.optInt("declared_value_kop"),
        codAmountKop = o.optInt("cod_amount_kop"),
        commissionKop = o.optInt("commission_kop"),
        priceKop = o.optInt("price_kop"),
        settlement = parseParcelSettlement(o.optJSONObject("settlement")),
        returnReason = o.optString("return_reason"),
        returnedAt = nStr(o, "returned_at"),
        pickupPhotoUrl = o.optString("pickup_photo_url"),
        deliveryPhotoUrl = o.optString("delivery_photo_url"),
        deliveryAttempts = o.optInt("delivery_attempts"),
        cancelFeeKop = o.optInt("cancel_fee_kop"),
        cancelFeePreviewKop = o.optInt("cancel_fee_preview_kop"),
        cancelFineKop = o.optJSONObject("cancel_fee_parts")?.optInt("base_kop") ?: 0,
        cancelPickupKop = o.optJSONObject("cancel_fee_parts")?.optInt("pickup_kop") ?: 0,
        cancelWaitingKop = o.optJSONObject("cancel_fee_parts")?.optInt("waiting_kop") ?: 0,
        returnFeeKop = o.optInt("return_fee_kop"),
        returnFeePreviewKop = o.optJSONObject("return_fee_parts")?.optInt("total_kop") ?: 0,
        returnRedeliverKop = o.optJSONObject("return_fee_parts")?.optInt("redeliver_kop") ?: 0,
        returnRouteKop = o.optJSONObject("return_fee_parts")?.optInt("route_kop") ?: 0,
        returnCappedKop = o.optJSONObject("return_fee_parts")?.optInt("capped_kop") ?: 0,
        nextRedeliverKop = o.optJSONObject("return_fee_parts")?.optInt("next_redeliver_kop") ?: 0,
        redeliverRequests = o.optInt("redeliver_requests"),
        redeliverMax = o.optInt("redeliver_max"),
        canRequestRedelivery = o.optBoolean("can_request_redelivery"),
        // Срок «к какому дню нужно» и признак просрочки. Просрочку считает СЕРВЕР: у телефона
        // своя дата и свой часовой пояс, и клиентский подсчёт красил бы карточку по-разному
        // у отправителя и курьера. nStr → null, если срока нет или сервер старый.
        deliverBy = nStr(o, "deliver_by"),
        overdue = o.optBoolean("overdue", false),
    )

    /** Разбор блока settlement (buy_bring): null, если сервер не прислал. */
    private fun parseParcelSettlement(s: JSONObject?): ParcelSettlementDto? = s?.let {
        ParcelSettlementDto(
            goodsActualKop = it.optInt("goods_actual_kop"),
            deliveryKop = it.optInt("delivery_kop"),
            totalDueKop = it.optInt("total_due_kop"),
            settled = it.optBoolean("settled"),
        )
    }

    /** Отправитель: создать посылку. rulesAccepted обязателен (422 иначе), size обязателен.
     *
     *  fromAddress/toAddress — «где именно забрать и куда привезти». Города мало: курьер брал
     *  заказ и ехал «в Баймак» — ни дома, ни ориентира. В селе это чаще ориентир, чем улица
     *  с табличкой, поэтому одна свободная строка на сторону. Пустые не шлём — как comment
     *  и entrance в createInstantOrder.
     *
     *  deliverBy — «к какому дню нужно», строка вида 2026-08-05. Это не прогноз, а договорённость:
     *  курьер видит день до того, как взяться. Пусто = «не срочно», ключа в теле нет вовсе. */
    suspend fun createParcel(
        fromCity: String, toCity: String, size: String, description: String,
        receiverName: String, receiverPhone: String, rulesAccepted: Boolean,
        fromLat: Double? = null, fromLng: Double? = null, toLat: Double? = null, toLng: Double? = null,
        priceKop: Int = 0, declaredValueKop: Int = 0,
        fromAddress: String = "", toAddress: String = "",
        deliverBy: String = "",
        // Что везём: вес отвечает на «унесу ли», тип — на «возьмусь ли», хрупкость — на «как положить».
        // Размер (small/medium/large) ни на один из этих вопросов не отвечает.
        weightKg: Double = 0.0, cargoType: String = "", fragile: Boolean = false,
    ): Result<ParcelDto> {
        val body = JSONObject()
            .put("from_city", fromCity).put("to_city", toCity)
            .put("size", size).put("description", description)
            .put("receiver_name", receiverName).put("receiver_phone", receiverPhone)
            .put("rules_accepted", rulesAccepted)
            // Сколько отправитель платит курьеру: раньше цены у «по пути» не было вообще,
            // и курьер брал посылку вслепую. 0 = «по-соседски», это тоже честный вариант.
            .put("price_kop", priceKop)
            .put("declared_value_kop", declaredValueKop)
            .put("from_lat", fromLat ?: JSONObject.NULL).put("from_lng", fromLng ?: JSONObject.NULL)
            .put("to_lat", toLat ?: JSONObject.NULL).put("to_lng", toLng ?: JSONObject.NULL)
        putParcelAddresses(body, fromAddress, toAddress)
        putParcelDeliverBy(body, deliverBy)
        putParcelCargo(body, weightKg, cargoType, fragile)
        return call("POST", "/parcels", body, auth = true)
            .map { parseParcel(it) }.onSuccess { Analytics.log("parcel_create") }
    }

    /** Кладёт «где забрать / куда привезти» в тело заказа. Пустое поле — не поле: в теле его нет. */
    private fun putParcelAddresses(body: JSONObject, fromAddress: String, toAddress: String) {
        if (fromAddress.isNotBlank()) body.put("from_address", fromAddress.take(PARCEL_ADDRESS_MAX_LEN))
        if (toAddress.isNotBlank()) body.put("to_address", toAddress.take(PARCEL_ADDRESS_MAX_LEN))
    }

    /** Кладёт срок «к какому дню нужно» (2026-08-05). Пусто = «не срочно» → ключа в теле нет:
     *  навязанный дедлайн отпугивает курьеров, а пустая строка на сервере значила бы «на сегодня». */
    private fun putParcelDeliverBy(body: JSONObject, deliverBy: String) {
        if (deliverBy.isNotBlank()) body.put("deliver_by", deliverBy.trim())
    }

    /** Что везём — одинаково для «по пути» и для заказа курьера: две копии разъехались бы
     *  при первой правке. Пустые значения не шлём — сервер поймёт их как «не указано». */
    private fun putParcelCargo(body: JSONObject, weightKg: Double, cargoType: String, fragile: Boolean) {
        if (weightKg > 0.0) body.put("weight_kg", weightKg)
        if (cargoType.isNotBlank()) body.put("cargo_type", cargoType.trim())
        if (fragile) body.put("fragile", true)
    }

    /** Отправитель: мои посылки (с кодом вручения и курьером, если принята). */
    suspend fun getMyParcels(): Result<List<ParcelDto>> =
        call("GET", "/parcels/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseParcel(arr.getJSONObject(it)) }
        }

    /** Отправитель: отменить свою посылку. 409 — уже доставлена/отменена, 404 — чужая. */
    suspend fun cancelParcel(id: Int): Result<Unit> =
        call("POST", "/parcels/$id/cancel", null, auth = true).map { }

    /**
     * «Купи и привези»: заказчик поднимает сумму, на которую согласен.
     *
     * В аптеке товар оказался дороже сметы — курьер упирался в отказ «сумма выше
     * согласованной», а поднять её было НЕГДЕ: ручка на сервере жила, кнопки не было ни на
     * одном экране (аудит сценариев 30.08). Только вверх и только до вручения — это деньги
     * заказчика и его решение.
     */
    suspend fun raiseParcelBudget(id: Int, codAmountKop: Int): Result<Unit> =
        call("POST", "/courier/orders/$id/raise-budget",
             JSONObject().put("cod_amount_kop", codAmountKop), auth = true).map { }
            .onSuccess { Analytics.log("parcel_raise_budget") }

    /** Курьер: доступные посылки (без телефона и кода). Опц. фильтр по городам. */
    suspend fun getAvailableParcels(fromCity: String? = null, toCity: String? = null): Result<List<ParcelDto>> {
        val q = buildList {
            fromCity?.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity?.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
        }.joinToString("&")
        val path = "/parcels/available" + if (q.isNotBlank()) "?$q" else ""
        return call("GET", path, null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseParcel(arr.getJSONObject(it)) }
        }
    }

    /** Курьер: взять посылку. 409 — своя/уже взяли. */
    suspend fun acceptParcel(id: Int): Result<ParcelDto> =
        call("POST", "/parcels/$id/accept", null, auth = true).map { parseParcel(it) }
            .onSuccess { Analytics.log("parcel_accept") }

    /** Курьер: сменить статус. status="in_transit" или "delivered"+code. 422 — неверный код. */
    /** deliveryPhotoUrl — фото «отдал целой» на границе ответственности: в споре о повреждении
     *  это единственное, что отличает слово от доказательства (поле сервер принимал, клиент не слал). */
    /** pickupPhotoUrl — фото «взял целой» на переходе в путь. Вторая граница ответственности:
     *  снимок вручения был, снимка забора не было, и спор «было битое / стало битое» упирался
     *  в слово против слова. Момент выбран не при взятии заявки: заказ берут заранее, а у
     *  посылки курьер оказывается позже — сфотографировать там просто нечего. */
    suspend fun setParcelStatus(
        id: Int, status: String, code: String? = null, deliveryPhotoUrl: String? = null,
        pickupPhotoUrl: String? = null,
    ): Result<ParcelDto> {
        val body = JSONObject().put("status", status)
        code?.takeIf { it.isNotBlank() }?.let { body.put("code", it.trim()) }
        deliveryPhotoUrl?.takeIf { it.isNotBlank() }?.let { body.put("delivery_photo_url", it) }
        pickupPhotoUrl?.takeIf { it.isNotBlank() }?.let { body.put("pickup_photo_url", it) }
        return call("POST", "/parcels/$id/status", body, auth = true).map { parseParcel(it) }
            .onSuccess { Analytics.log("parcel_status_$status") }
    }

    /** Курьер приехал, но получателя нет — попытка зафиксирована, посылка ОСТАЁТСЯ у курьера.
     *  Отдельно от возврата: раньше «никого нет дома» имело один исход — везти коробку за 60 км
     *  обратно, хотя человек вернётся через два часа. */
    suspend fun parcelAttemptFailed(id: Int, reason: String = ""): Result<ParcelDto> {
        val body = JSONObject()
        if (reason.isNotBlank()) body.put("reason", reason.trim())
        return call("POST", "/parcels/$id/attempt-failed", body, auth = true).map { parseParcel(it) }
            .onSuccess { Analytics.log("parcel_attempt_failed") }
    }

    /** Курьер: активные + короткая история завершённых доставок.
     *  Финальная карточка не исчезает после вручения/отмены/возврата: остаются квитанция,
     *  компенсация, оценка и спор. Сервер ограничивает историю последними 10 строками. */
    suspend fun getCarryingParcels(): Result<List<ParcelDto>> =
        call("GET", "/parcels/carrying?include_recent=true&recent_limit=10", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseParcel(arr.getJSONObject(it)) }
        }

    /** Админ: все посылки + честная выписка по деньгам (дошло / должны / не выставлено). */
    suspend fun adminListParcels(): Result<ParcelAdminListDto> =
        call("GET", "/admin/parcels", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("parcels") ?: JSONArray()
            val s = obj.optJSONObject("statement")
            ParcelAdminListDto(
                parcels = (0 until arr.length()).map { parseParcel(arr.getJSONObject(it)) },
                statement = ParcelStatementDto(
                    deliveredCount = s?.optInt("delivered_count") ?: 0,
                    collectedFeeKop = s?.optInt("collected_fee_kop") ?: 0,
                    owedCommissionKop = s?.optInt("owed_commission_kop") ?: 0,
                    unbilledFeeKop = s?.optInt("unbilled_fee_kop") ?: 0,
                ),
            )
        }

    // ═══════════ C1: Курьер Юлдаша (профессиональная доставка) ═══════════
    // Пользователь подаёт заявку «Стать курьером» (транспорт + селфи) → админ одобряет →
    // курьер выходит «на линию» (город/межгород/регион), берёт заказы, доставляет по коду.
    // Отправитель заказывает курьера/«купи и привези» — сервер считает цену (комиссия по ступени, прозрачно).

    private fun parseCourierApp(o: JSONObject) = CourierApplicationDto(
        id = o.optInt("id"),
        transport = o.optString("transport"),
        status = o.optString("status", "pending"),
        selfieUrl = o.optString("selfie_url"),
        fullName = o.optString("full_name"),
        carPlate = o.optString("car_plate"),
        rulesAccepted = o.optBoolean("rules_accepted"),
        invitedBy = nStr(o, "invited_by"),
        rejectReason = o.optString("reject_reason"),
        createdAt = o.optString("created_at"),
        reviewedAt = nStr(o, "reviewed_at"),
        userId = o.optInt("user_id"),
        name = o.optString("name"),
        phone = o.optString("phone"),
    )

    /** Подать заявку «Стать курьером». transport: car|cargo. 422 — транспорт/селфи, 409 — заявка уже на рассмотрении. */
    suspend fun applyCourier(
        transport: String, selfieUrl: String,
        fullName: String = "", carPlate: String = "", rulesAccepted: Boolean = false,
    ): Result<CourierApplicationDto> {
        // ФИО, госномер и согласие: мы доверяем курьеру чужую посылку — знать о нём хотя бы
        // столько же, сколько о попутчике, это минимум (аудит 2026-07-26).
        val body = JSONObject().put("transport", transport).put("selfie_url", selfieUrl)
        if (fullName.isNotBlank()) body.put("full_name", fullName.take(120))
        if (carPlate.isNotBlank()) body.put("car_plate", carPlate.take(16))
        if (rulesAccepted) body.put("rules_accepted", true)
        return call("POST", "/courier/apply", body, auth = true)
            .map { parseCourierApp(it) }.onSuccess { Analytics.log("courier_apply") }
    }

    /** Заработок курьера за период (week|month|all): чистыми, комиссия, доставок, по дням.
     *  Раньше курьер видел только «должен Юлдашу столько-то» — работа выглядела сплошным долгом. */
    suspend fun getCourierEarnings(period: String = "week"): Result<CourierEarningsDto> =
        call("GET", "/courier/earnings?period=$period", null, auth = true).map { o ->
            val arr = o.optJSONArray("by_day") ?: JSONArray()
            CourierEarningsDto(
                period = o.optString("period"),
                netKop = o.optInt("net_kop"),
                commissionKop = o.optInt("commission_kop"),
                deliveries = o.optInt("deliveries"),
                byDay = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    CourierEarningsDayDto(
                        date = d.optString("date"),
                        netKop = d.optInt("net_kop"),
                        deliveries = d.optInt("deliveries"),
                    )
                },
            )
        }

    /** Моя заявка курьера. Ответ: {application: {...}|null}. null — ещё не подавал. */
    suspend fun getCourierApplication(): Result<CourierApplicationDto?> =
        call("GET", "/courier/application", null, auth = true).map { o ->
            if (o.isNull("application")) null else o.optJSONObject("application")?.let { parseCourierApp(it) }
        }

    /**
     * Курьер: выйти «на линию». Зона как у таксиста: база `city` (+work_city) или
     * `district` (+work_district) плюс тумблеры «загород» и «соседние регионы».
     */
    suspend fun courierOnline(
        zone: String,
        workCity: String? = null,
        workDirectionId: Int? = null,
        workDistrict: String? = null,
        workIntercity: Boolean = false,
        workRegions: Boolean = false,
    ): Result<Unit> {
        val body = JSONObject().put("zone", zone)
            .put("work_intercity", workIntercity)
            .put("work_regions", workRegions)
        workCity?.takeIf { it.isNotBlank() }?.let { body.put("work_city", it) }
        workDistrict?.takeIf { it.isNotBlank() }?.let { body.put("work_district", it) }
        workDirectionId?.let { body.put("work_direction_id", it) }
        return call("POST", "/courier/online", body, auth = true).map { }.onSuccess { Analytics.log("courier_online") }
    }

    /** Курьер: уйти с линии. */
    suspend fun courierOffline(): Result<Unit> =
        call("POST", "/courier/offline", JSONObject(), auth = true).map { }.onSuccess { Analytics.log("courier_offline") }

    /** Курьер: доступные заказы (без телефона/кода получателя). Опц. фильтр по городам. */
    suspend fun getCourierAvailable(fromCity: String? = null, toCity: String? = null): Result<List<ParcelDto>> {
        val q = buildList {
            fromCity?.takeIf { it.isNotBlank() }?.let { add("from_city=" + enc(it)) }
            toCity?.takeIf { it.isNotBlank() }?.let { add("to_city=" + enc(it)) }
        }.joinToString("&")
        val path = "/courier/available" + if (q.isNotBlank()) "?$q" else ""
        return call("GET", path, null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseParcel(arr.getJSONObject(it)) }
        }
    }

    /** Оценка стоимости доставки курьером. urgency: bypath|now. */
    suspend fun courierEstimate(
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        size: String,
        urgency: String,
        deliveryType: String = "courier",
    ): Result<CourierEstimateDto> {
        val q = "from_lat=$fromLat&from_lng=$fromLng&to_lat=$toLat&to_lng=$toLng" +
            "&size=${enc(size)}&urgency=${enc(urgency)}&delivery_type=${enc(deliveryType)}"
        return call("GET", "/courier/estimate?$q", null, auth = true).map { o ->
            val b = o.optJSONObject("breakdown") ?: JSONObject()
            CourierEstimateDto(
                priceKop = o.optInt("price_kop"),
                commissionKop = o.optInt("commission_kop"),
                distanceKm = o.optDouble("distance_km", 0.0),
                breakdown = CourierEstimateBreakdown(
                    baseKop = b.optInt("base_kop"), distanceKop = b.optInt("distance_kop"),
                    sizeKop = b.optInt("size_kop"), urgencyKop = b.optInt("urgency_kop"),
                    commissionPercent = b.optDouble("commission_percent", 0.0),
                    commissionMinKop = b.optInt("commission_min_kop"),
                    commissionEstimated = b.optBoolean("commission_estimated", false),
                    returnFeeEstimateKop = b.optInt("return_fee_estimate_kop"),
                ),
            )
        }
    }

    /** Отправитель: заказать курьера / «купи и привези». deliveryType: courier|buy_bring, urgency: bypath|now.
     *  422 — «Сумма покупки слишком большая (лимит 5000 ₽)» / rules_accepted / размер / тип. 403 — курьер выключен. */
    suspend fun createCourierOrder(
        fromCity: String, toCity: String,
        fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        size: String, description: String, receiverName: String, receiverPhone: String, rulesAccepted: Boolean,
        deliveryType: String, urgency: String,
        declaredValueKop: Int? = null, codAmountKop: Int? = null, shoppingList: String? = null,
        fromAddress: String = "", toAddress: String = "",
        deliverBy: String = "",
        weightKg: Double = 0.0, cargoType: String = "", fragile: Boolean = false,
    ): Result<ParcelDto> {
        val body = JSONObject()
            .put("from_city", fromCity).put("to_city", toCity)
            .put("from_lat", fromLat).put("from_lng", fromLng)
            .put("to_lat", toLat).put("to_lng", toLng)
            .put("size", size).put("description", description)
            .put("receiver_name", receiverName).put("receiver_phone", receiverPhone)
            .put("rules_accepted", rulesAccepted)
            .put("delivery_type", deliveryType).put("urgency", urgency)
        declaredValueKop?.let { body.put("declared_value_kop", it) }
        codAmountKop?.let { body.put("cod_amount_kop", it) }
        shoppingList?.takeIf { it.isNotBlank() }?.let { body.put("shopping_list", it) }
        putParcelAddresses(body, fromAddress, toAddress)
        putParcelDeliverBy(body, deliverBy)
        putParcelCargo(body, weightKg, cargoType, fragile)
        return call("POST", "/courier/orders", body, auth = true).map { parseParcel(it) }
            .onSuccess { Analytics.log("courier_order_$deliveryType") }
    }

    /** Курьер (buy_bring): указать фактическую стоимость купленного товара. actualKop — в копейках.
     *  Ответ: {id, settlement:{...}}. 404 — чужой, 409 — не buy_bring/уже завершён,
     *  422 — «Укажи стоимость покупки»/«Сумма покупки слишком большая (лимит 5000 ₽)». */
    suspend fun setGoodsCost(id: Int, actualKop: Int): Result<ParcelSettlementDto> =
        call("POST", "/courier/orders/$id/goods-cost", JSONObject().put("actual_kop", actualKop), auth = true)
            .map { parseParcelSettlement(it.optJSONObject("settlement")) ?: ParcelSettlementDto(actualKop, 0, actualKop, false) }
            .onSuccess { Analytics.log("courier_goods_cost") }

    /** Открыть спор по заказу (отправитель или курьер) с классификацией и приватными фото. */
    suspend fun disputeParcel(
        id: Int,
        reason: String,
        type: String,
        evidenceUrls: List<String>,
    ): Result<Unit> {
        val safeEvidence = evidenceUrls
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val body = JSONObject()
            .put("reason", reason.trim().take(1000))
            .put("type", type.trim().take(32))
            .put("evidence_urls", JSONArray(safeEvidence))
        return call("POST", "/parcels/$id/dispute", body, auth = true).map { }
            .onSuccess { Analytics.log("parcel_dispute") }
    }

    /** Кабинет курьера: заявка + профиль (если одобрен) + выписка (доставлено/сбор). */
    suspend fun getCourierMe(): Result<CourierMeDto> =
        call("GET", "/courier/me", null, auth = true).map { o ->
            val app = if (o.isNull("application")) null else o.optJSONObject("application")?.let { parseCourierApp(it) }
            val p = if (o.isNull("profile")) null else o.optJSONObject("profile")
            val profile = p?.let {
                CourierProfileDto(
                    id = it.optInt("id"), online = it.optBoolean("online"),
                    carClass = it.optString("car_class"), zone = it.optString("zone"),
                    workCity = nStr(it, "work_city"), workDirectionId = nInt(it, "work_direction_id"),
                    workDistrict = nStr(it, "work_district"),
                    workIntercity = it.optBoolean("work_intercity"),
                    workRegions = it.optBoolean("work_regions"),
                    updatedAt = it.optString("updated_at"),
                )
            }
            val s = o.optJSONObject("statement")
            val r = o.optJSONObject("rating")
            CourierMeDto(
                application = app, profile = profile,
                statement = CourierStatementDto(
                    deliveredCount = s?.optInt("delivered_count") ?: 0,
                    commissionEarnedKop = s?.optInt("commission_earned_kop") ?: 0,
                    commissionOwedKop = s?.optInt("commission_owed_kop") ?: 0,
                    commissionPaidKop = s?.optInt("commission_paid_kop") ?: 0,
                    commissionKop = s?.optInt("commission_kop") ?: 0,
                    currentFeePercent = s?.optDouble("current_fee_percent", 0.0) ?: 0.0,
                    feeTier = s?.optString("fee_tier") ?: "",
                    commissionMinKop = s?.optInt("commission_min_kop") ?: 0,
                ),
                rating = CourierRatingDto(
                    avg = r?.let { if (it.isNull("avg")) null else it.optDouble("avg") },
                    count = r?.optInt("count") ?: 0,
                ),
                pausedUntil = nStr(o, "paused_until"),
            )
        }

    /** C3: оценить доставку (обе стороны, ПОСЛЕ вручения). Ответ — новый рейтинг оценённого. */
    suspend fun rateParcel(id: Int, stars: Int, text: String? = null): Result<RateResultDto> {
        val body = JSONObject().put("stars", stars)
        if (!text.isNullOrBlank()) body.put("text", text.trim())
        return call("POST", "/parcels/$id/rate", body, auth = true).map {
            RateResultDto(
                rateeId = it.optInt("ratee_id"),
                rating = it.optDouble("rating", 0.0),
                count = it.optInt("count"),
            )
        }.onSuccess { Analytics.log("parcel_rate") }
    }

    /** C3: курьер оплачивает нашу комиссию (СБП «на доверии»). Идемпотентно. Ответ — реквизиты получателя. */
    suspend fun payCommission(): Result<PayCommissionDto> =
        call("POST", "/courier/pay-commission", JSONObject(), auth = true).map { o ->
            val payee = o.optJSONObject("payee")
            PayCommissionDto(
                status = o.optString("status", "pending"),
                paymentId = o.optInt("payment_id"),
                amountKop = o.optInt("amount_kop"),
                amount = o.optInt("amount"),
                payeePhone = payee?.optString("phone") ?: "",
                payeeBank = payee?.optString("bank") ?: "",
                payeeName = payee?.optString("name") ?: "",
                method = o.optString("method", "sbp_manual"),
                confirmationUrl = o.optString("confirmation_url").ifBlank { null },
            )
        }.onSuccess { Analytics.log("courier_pay_commission") }

    /** Админ: заявки курьеров (pending сверху решает экран). Ответ — массив application. */
    suspend fun adminListCourierApps(): Result<List<CourierApplicationDto>> =
        call("GET", "/admin/courier-applications", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseCourierApp(arr.getJSONObject(it)) }
        }

    suspend fun adminApproveCourier(id: Int): Result<Unit> =
        call("POST", "/admin/courier-applications/$id/approve", JSONObject(), auth = true).map { }

    suspend fun adminRejectCourier(id: Int, reason: String): Result<Unit> =
        call("POST", "/admin/courier-applications/$id/reject", JSONObject().put("reason", reason), auth = true).map { }

    // ---------- Кошелёк водителя (Деньги v1, ledger) ----------
    // Приватность: всегда по СВОЕМУ токену — чужой кошелёк/историю не запросить (сервер фильтрует по id).

    /** Баланс кошелька (сумма всех записей ledger). Копейки + рубли. */
    suspend fun getWalletBalance(): Result<WalletBalanceDto> =
        call("GET", "/wallet/balance", null, auth = true).map { o ->
            WalletBalanceDto(balanceKop = o.optInt("balance_kop"), balanceRub = o.optInt("balance_rub"))
        }

    /** История операций кошелька (начисления/комиссии/выплаты). Ответ — массив (call() кладёт в "items"). */
    suspend fun getWalletLedger(limit: Int = 50): Result<List<WalletLedgerEntryDto>> =
        call("GET", "/wallet/ledger?limit=$limit", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                WalletLedgerEntryDto(
                    id = o.optInt("id"),
                    kind = o.optString("kind"),
                    amountKop = o.optInt("amount_kop"),
                    orderId = if (o.isNull("order_id")) null else o.optInt("order_id"),
                    bookingId = if (o.isNull("booking_id")) null else o.optInt("booking_id"),
                    note = o.optString("note"),
                    createdAt = o.optString("created_at"),
                )
            }
        }

    // ---------- Вывод на карту (Модель Б, за флагом PAYOUTS_ENABLED) ----------
    // Приватность: полный номер карты на сервер НЕ уходит — клиент шлёт ТОЛЬКО последние 4 цифры.
    // enabled=false с сервера → UI рисует честную заглушку «скоро», без кнопок-обманок.

    /** Статус выплат: включены ли, баланс, сохранённая карта, границы суммы. Всё — с сервера (не хардкод). */
    suspend fun getPayoutStatus(): Result<PayoutStatusDto> =
        call("GET", "/wallet/payout/status", null, auth = true).map { o ->
            PayoutStatusDto(
                enabled = o.optBoolean("enabled"),
                balanceKop = o.optInt("balance_kop"),
                hasRequisite = o.optBoolean("has_requisite"),
                cardLast4 = o.optString("card_last4"),
                minKop = o.optInt("min_kop"),
                maxKop = o.optInt("max_kop"),
            )
        }

    /** Сохранить карту для вывода. ВАЖНО: передаём ТОЛЬКО последние 4 цифры — полный номер
     *  не покидает телефон (и никогда не логируется). payoutToken — токен провайдера (prod-путь). */
    suspend fun savePayoutRequisite(cardLast4: String, payoutToken: String = ""): Result<String> =
        call(
            "POST", "/wallet/payout/requisite",
            JSONObject().put("card_last4", cardLast4).put("payout_token", payoutToken),
            auth = true,
        ).map { it.optString("card_last4") }

    /** Вывести с баланса на сохранённую карту. idempotencyKey — ОДИН на попытку (UUID с клиента):
     *  ретрай той же попытки с тем же ключом не спишет баланс дважды (сервер вернёт status=already).
     *  400 → ApiException с человеческим detail (мин/макс/недостаточно); 503 → выплаты ещё выключены. */
    suspend fun requestPayout(amountKop: Int, idempotencyKey: String): Result<PayoutResultDto> =
        call(
            "POST", "/wallet/payout",
            JSONObject().put("amount_kop", amountKop).put("idempotency_key", idempotencyKey),
            auth = true,
        ).map { o ->
            PayoutResultDto(
                status = o.optString("status"),
                entryId = o.optInt("entry_id"),
                amountKop = o.optInt("amount_kop"),
                balanceKop = o.optInt("balance_kop"),
            )
        }

    // ---------- Оплата завершённой поездки онлайн (ЮKassa, за флагом провайдера) ----------
    // 503 = онлайн-оплата ещё не включена (mock в проде) → UI прячет карточку, «на доверии» остаётся.

    /** Оплатить ЗАВЕРШЁННУЮ бронь плановой поездки. methodKey: cash | card | sbp. */
    suspend fun payBooking(bookingId: Int, methodKey: String): Result<PayTripResultDto> =
        call("POST", "/bookings/$bookingId/pay", JSONObject().put("method", methodKey), auth = true)
            .map { it.toPayTripResult() }

    /** Оплатить ЗАВЕРШЁННЫЙ быстрый заказ (такси). methodKey: cash | card | sbp. */
    suspend fun payInstantOrder(orderId: Int, methodKey: String): Result<PayTripResultDto> =
        call("POST", "/instant/orders/$orderId/pay", JSONObject().put("method", methodKey), auth = true)
            .map { it.toPayTripResult() }

    private fun JSONObject.toPayTripResult() = PayTripResultDto(
        status = optString("status"),
        method = optString("method"),
        paymentId = if (isNull("payment_id")) null else optInt("payment_id"),
        confirmationUrl = optString("confirmation_url").ifBlank { null },
    )

    /** История заработка водителя за период (week|month|all): суммарно + разбивка по дням.
     *  ВАЖНО: total/sum — в РУБЛЯХ (₽, целые), НЕ в копейках (см. backend debt.py::driver_earnings). */
    suspend fun getDriverEarnings(period: String = "week"): Result<DriverEarningsDto> =
        call("GET", "/driver/earnings?period=$period", null, auth = true).map { o ->
            val arr = o.optJSONArray("by_day") ?: JSONArray()
            DriverEarningsDto(
                period = o.optString("period", period),
                total = o.optInt("total"),
                trips = o.optInt("trips"),
                byDay = (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    DriverEarningsDayDto(date = d.optString("date"), sum = d.optInt("sum"), trips = d.optInt("trips"))
                },
            )
        }

    // ---------- Сохранённые адреса (Дом/Работа/свои) + недавние ----------
    // Приватность: всё по СВОЕМУ токену — чужие адреса не запросить (сервер фильтрует по id).

    private fun parseSavedPlace(o: JSONObject) = SavedPlaceDto(
        id = o.optInt("id"),
        kind = o.optString("kind"),
        label = o.optString("label"),
        address = o.optString("address"),
        lat = o.optDouble("lat"),
        lng = o.optDouble("lng"),
        createdAt = o.optString("created_at"),
        usedAt = o.optString("used_at"),
    )

    /** Сохранённые адреса (Дом/Работа/свои). Ответ — массив (call() кладёт в "items"). */
    suspend fun getSavedPlaces(): Result<List<SavedPlaceDto>> =
        call("GET", "/places/saved", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { parseSavedPlace(arr.getJSONObject(it)) }
        }

    /** Сохранить адрес. kind: home|work (upsert по kind) | custom (≤20). Возвращает сохранённую запись. */
    suspend fun saveSavedPlace(kind: String, label: String, address: String, lat: Double, lng: Double): Result<SavedPlaceDto> =
        call("POST", "/places/saved", JSONObject()
            .put("kind", kind).put("label", label).put("address", address)
            .put("lat", lat).put("lng", lng), auth = true).map { parseSavedPlace(it) }

    /**
     * Отметить, что сохранённым адресом воспользовались.
     *
     * По этой отметке сервер строит порядок быстрого списка в форме заказа: наверху то,
     * куда человек ездит, а не то, что завёл последним. Best-effort — если не дошло,
     * заказ всё равно оформляется, просто порядок обновится в следующий раз.
     */
    suspend fun markSavedPlaceUsed(id: Int): Result<Unit> =
        call("POST", "/places/saved/$id/used", null, auth = true).map { }

    /** Удалить сохранённый адрес по id (чужое/нет → 404). */
    suspend fun deleteSavedPlace(id: Int): Result<Unit> =
        call("DELETE", "/places/saved/$id", null, auth = true).map { }

    /** Недавние адреса назначения (свежие сверху, ≤10). Ответ — массив. */
    suspend fun getRecentPlaces(): Result<List<RecentPlaceDto>> =
        call("GET", "/places/recent", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentPlaceDto(
                    id = o.optInt("id"),
                    address = o.optString("address"),
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    usedAt = o.optString("used_at"),
                )
            }
        }

    /** Добавить/обновить недавний адрес (дедуп по address на сервере). Best-effort — заказ не блокируем. */
    suspend fun addRecentPlace(address: String, lat: Double, lng: Double): Result<Unit> =
        call("POST", "/places/recent", JSONObject()
            .put("address", address).put("lat", lat).put("lng", lng), auth = true).map { }

    /**
     * Убрать один недавний адрес (чужое/нет → 404).
     *
     * Список копится сам, из каждого заказа: там оседают больница, дом бывшего, старая
     * работа. Право убрать оттуда строку — часть приватности, а не удобство.
     */
    suspend fun deleteRecentPlace(id: Int): Result<Unit> =
        call("DELETE", "/places/recent/$id", null, auth = true).map { }

    /** Очистить весь список недавних одним действием (телефон уходит в чужие руки). */
    suspend fun clearRecentPlaces(): Result<Unit> =
        call("DELETE", "/places/recent", null, auth = true).map { }

    /** Квитанция завершённой поездки (только участник; незавершённая → 409; посторонний → 403). */
    suspend fun getTripReceipt(bookingId: Int): Result<TripReceiptDto> =
        call("GET", "/trips/$bookingId/receipt", null, auth = true).map { o ->
            TripReceiptDto(
                bookingId = o.optInt("booking_id"),
                rideId = o.optInt("ride_id"),
                role = o.optString("role"),
                fromCity = o.optString("from_city"),
                toCity = o.optString("to_city"),
                departAt = o.optString("depart_at"),
                seats = o.optInt("seats"),
                amount = o.optInt("amount"),
                payMethod = o.optString("pay_method"),
                paid = o.optBoolean("paid"),
                driverName = o.optString("driver_name"),
                driverVerified = o.optBoolean("driver_verified"),
            )
        }

    // ═══════════ Закрытие пробелов такси / курьера (аудит 2026-07-26) ═══════════
    // Сервер уже умеет всё ниже; здесь только доступ. Все ручки идемпотентны или дают понятный
    // 409 — экраны показывают текст ошибки как есть (он двуязычный, приходит с сервера).

    /** «Застрял на трассе» в ТАКСИ-заказе: координаты доверенным + сигнал админу.
     *  Зимний протокол работал только для попуток, хотя четыре часа трассы зимой — это такси. */
    /** «Уже выхожу» (волна 160): пассажир спускается, водитель это видит.
     *
     *  Раньше водитель, приехав, знал только одно — тикает бесплатное ожидание. Человек мог
     *  быть уже в лифте, а мог не выйти вовсе, и разницы на экране не было никакой. Одна
     *  кнопка снимает и лишний звонок, и половину поводов для спора о простое.
     */
    suspend fun instantImComing(orderId: Int): Result<Boolean> =
        call("POST", "/instant/orders/$orderId/im-coming", JSONObject(), auth = true)
            .map { it.optBoolean("ok", true) }

    // Тип результата пришёл из main (волна 19x): помощь на дороге теперь возвращает не число,
    // а разбор ситуации. Мой метод «уже выхожу» рядом — они друг другу не мешают.
    suspend fun instantRoadsideHelp(orderId: Int, lat: Double?, lng: Double?, note: String = ""): Result<RoadsideResult> {
        val body = JSONObject().put("note", note.take(500))
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return call("POST", "/instant/orders/$orderId/stuck", body, auth = true).map { roadsideResult(it) }
    }

    /** «Застрял на трассе» в ДОСТАВКЕ. Курьер едет по той же зимней трассе и вдобавок один:
     *  рядом нет пассажира, который заметит беду. Сервер принимал сигнал с 2026-08-06,
     *  но в приложении нажать было негде (аудит 2026-08-06). */
    suspend fun parcelRoadsideHelp(parcelId: Int, lat: Double?, lng: Double?, note: String = ""): Result<RoadsideResult> {
        val body = JSONObject().put("note", note.take(500))
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return call("POST", "/parcels/$parcelId/stuck", body, auth = true).map { roadsideResult(it) }
            .onSuccess { Analytics.log("roadside_help_parcel") }
    }

    /** «Подожду машину» после «рядом никого»: заказ встаёт в очередь, воркер продолжит поиск. */
    suspend fun waitForDriver(orderId: Int): Result<InstantWaitDto> =
        call("POST", "/instant/orders/$orderId/wait", JSONObject(), auth = true).map { o ->
            InstantWaitDto(
                waitUntil = o.optString("wait_until"),
                waitMinutes = o.optInt("wait_minutes"),
                order = o.optJSONObject("order")?.toInstantOrderDto(),
            )
        }

    /** Квитанция за такси-поездку (обе стороны, только после done). Телефонов в чеке нет. */
    suspend fun getInstantReceipt(orderId: Int): Result<InstantReceiptDto> =
        call("GET", "/instant/orders/$orderId/receipt", null, auth = true).map { o ->
            InstantReceiptDto(
                orderId = o.optInt("order_id"),
                role = o.optString("role"),
                fromText = o.optString("from_text"), toText = o.optString("to_text"),
                doneAt = o.optString("done_at"),
                distanceKm = o.optDouble("distance_km", 0.0),
                amount = o.optInt("amount"),
                // Точная сумма с копейками. Сервер отдаёт её давно, но чек читал только
                // округлённое `amount` и показывал 188,50 ₽ как «188 ₽». Фолбэк на старое
                // поле — на случай сервера, который ещё не отдаёт amount_kop.
                amountKop = o.optInt("amount_kop", o.optInt("amount") * 100),
                waitingFeeKop = o.optInt("waiting_fee_kop"),
                paymentMethod = o.optString("payment_method"),
                paid = o.optBoolean("paid"),
                driverName = o.optString("driver_name"),
                driverVerified = o.optBoolean("driver_verified"),
                ridePrice = o.optInt("ride_price"),
                rideBasePrice = o.optInt("ride_base_price"),
                surgeRub = o.optInt("surge_rub"),
                pickupFeeKop = o.optInt("pickup_fee_kop"),
                pickupKm = o.optDouble("pickup_km", 0.0),
                pickupEnroute = o.optBoolean("pickup_enroute"),
                optionsFeeKop = o.optInt("options_fee_kop"),
                weatherFeeKop = o.optInt("weather_fee_kop"),
                weatherKind = o.optString("weather_kind"),
                options = o.optJSONArray("options")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList(),
                driverFeePercent = o.optDouble("driver_fee_percent", 0.0),
                driverFeeKop = o.optInt("driver_fee_kop"),
                driverGrossKop = o.optInt("driver_gross_kop"),
                driverNetKop = o.optInt("driver_net_kop"),
                commissionFreeKop = o.optInt("commission_free_kop"),
            )
        }

    /** «Что-то не так с ценой» — человек спорит с нашим расчётом, а не с водителем.
     *  Уходит и БЕЗ заказа: чаще всего возмущение рождается ДО него. Координат не шлём. */
    suspend fun sendPriceComplaint(
        price: Int, reason: String, comment: String = "", orderId: Int? = null,
        breakdown: Map<String, Int> = emptyMap(),
    ): Result<Unit> {
        val body = JSONObject()
            .put("price", price)
            .put("reason", reason)
            .put("comment", comment.take(500))
        if (orderId != null && orderId > 0) body.put("order_id", orderId)
        if (breakdown.isNotEmpty()) {
            val b = JSONObject()
            breakdown.forEach { (k, v) -> b.put(k, v) }
            body.put("breakdown", b)
        }
        return call("POST", "/instant/price-complaint", body, auth = true).map { }
    }

    /** Админ: жалобы на цену — свежие сверху. Инструмент тарифа: менять цену по фактам,
     *  а не по ощущениям, и видеть, на какой сумме люди отваливаются. */
    suspend fun getPriceComplaints(limit: Int = 50): Result<List<PriceComplaintDto>> =
        call("GET", "/admin/price-complaints?limit=$limit", null, auth = true).map { o ->
            val arr = o.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { r ->
                    PriceComplaintDto(
                        id = r.optInt("id"),
                        orderId = if (r.isNull("order_id")) null else r.optInt("order_id"),
                        price = r.optInt("price"),
                        reason = r.optString("reason"),
                        comment = r.optString("comment"),
                        breakdown = r.optString("breakdown"),
                        createdAt = r.optString("created_at"),
                    )
                }
            }
        }

    /** Квитанция за доставку (обе стороны, только после вручения или возврата).
     *  Чек был у попутки и у такси, а у доставки его не было — хотя деньги там настоящие
     *  (аудит 2026-08-06). Телефонов и адресов в чеке нет: чеком делятся. */
    suspend fun getParcelReceipt(parcelId: Int): Result<ParcelReceiptDto> =
        call("GET", "/parcels/$parcelId/receipt", null, auth = true).map { o ->
            ParcelReceiptDto(
                parcelId = o.optInt("parcel_id"),
                role = o.optString("role"),
                status = o.optString("status"),
                fromCity = o.optString("from_city"), toCity = o.optString("to_city"),
                deliveryType = o.optString("delivery_type"),
                deliveredAt = o.optString("delivered_at"),
                returnedAt = o.optString("returned_at"),
                deliveryPriceKop = o.optInt("delivery_price_kop"),
                goodsKop = o.optInt("goods_kop"),
                totalKop = o.optInt("total_kop"),
                amount = o.optInt("amount"),
                commissionKop = o.optInt("commission_kop"),
                commissionPaid = o.optBoolean("commission_paid"),
                cancelFeeKop = o.optInt("cancel_fee_kop"),
                owedToCourierKop = o.optInt("owed_to_courier_kop"),
                settled = o.optBoolean("settled"),
                declaredValueKop = o.optInt("declared_value_kop"),
                courierName = o.optString("courier_name"),
                courierVerified = o.optBoolean("courier_verified"),
            )
        }

    /** Водитель: «наличные получил». Раньше отметить оплату мог только пассажир — и если он
     *  просто закрывал приложение, заказ навсегда оставался «не оплачен». */
    suspend fun instantCashReceived(orderId: Int): Result<String> =
        call("POST", "/instant/orders/$orderId/cash-received", JSONObject(), auth = true)
            .map { it.optString("status") }

    /** «Я забыл вещь в машине» → чат заказа снова открыт на запись 48 часов (обеим сторонам). */
    suspend fun instantLostItem(orderId: Int): Result<String> =
        call("POST", "/instant/orders/$orderId/lost-item", JSONObject(), auth = true)
            .map { it.optString("chat_open_until") }

    /** То же для попутки. Пока чат попутки не закрывался, выход был не нужен; после того как
     *  мы закрыли его через сутки после поездки, забытый на заднем сиденье телефон стало
     *  не вернуть — номер второй стороны после поездки не виден (аудит 2026-08-06). */
    suspend fun bookingLostItem(bookingId: Int): Result<String> =
        call("POST", "/bookings/$bookingId/lost-item", JSONObject(), auth = true)
            .map { it.optString("chat_open_until") }
            .onSuccess { Analytics.log("lost_item_booking") }

    /** Инфо для «Сказать рәхмәт» после такси-поездки (money != null → водитель оставил СБП). */
    suspend fun getInstantTipInfo(orderId: Int): Result<TipInfoDto> =
        call("GET", "/instant/orders/$orderId/tip", null, auth = true).map { o ->
            val m = o.optJSONObject("money")
            TipInfoDto(
                driverName = o.optString("driver_name"),
                alreadyThanked = o.optBoolean("already_thanked"),
                sbpPhone = m?.optString("sbp")?.takeIf { it.isNotBlank() },
            )
        }

    /** «Рәхмәт» водителю такси — тёплый жест без денег. Идемпотентно. */
    suspend fun sayInstantThanks(orderId: Int): Result<Unit> =
        call("POST", "/instant/orders/$orderId/thanks", JSONObject(), auth = true).map { }

    /** То же для попутки. Сервер умел это с самого начала — «рәхмәт» и придумали для попуток,
     *  но кнопка в итоге появилась только в чеке такси, и обе ручки годами никто не звал
     *  (аудит 2026-08-06). Сосед, который подвёз бесплатно, спасибо заслуживает не меньше. */
    suspend fun getBookingTipInfo(bookingId: Int): Result<TipInfoDto> =
        call("GET", "/bookings/$bookingId/tip", null, auth = true).map { o ->
            val m = o.optJSONObject("money")
            TipInfoDto(
                driverName = o.optString("driver_name"),
                alreadyThanked = o.optBoolean("already_thanked"),
                sbpPhone = m?.optString("sbp")?.takeIf { it.isNotBlank() },
            )
        }

    /** «Рәхмәт» водителю попутки — тёплый жест без денег. Идемпотентно. */
    suspend fun sayBookingThanks(bookingId: Int): Result<Unit> =
        call("POST", "/bookings/$bookingId/thanks", JSONObject(), auth = true).map { }

    /** Мои завершённые такси-заказы с расшифровкой: цена, комиссия, чистыми (закрывает
     *  «Юлдаш говорит 4200, я насчитал 4600 — где мои 400?»). */
    suspend fun getDriverTaxiRides(limit: Int = 100): Result<DriverTaxiRidesDto> =
        call("GET", "/driver/taxi-rides?limit=$limit", null, auth = true).map { o ->
            val arr = o.optJSONArray("rides") ?: JSONArray()
            DriverTaxiRidesDto(
                rides = (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    DriverTaxiRideDto(
                        orderId = r.optInt("order_id"),
                        doneAt = r.optString("done_at"),
                        from = r.optString("from"), to = r.optString("to"),
                        priceRub = r.optInt("price"),
                        feeKop = r.optInt("fee_kop"),
                        netKop = r.optInt("net_kop"),
                        paid = r.optBoolean("paid"),
                        unpaidConfirmed = r.optBoolean("unpaid_confirmed"),
                        paymentMethod = r.optString("payment_method"),
                        feeStatus = r.optString("fee_status"),
                    )
                },
                totalPriceRub = o.optInt("total_price"),
                totalFeeKop = o.optInt("total_fee_kop"),
                totalNetKop = o.optInt("total_net_kop"),
            )
        }

    // ---------- курьер: «что-то пошло не так» ----------

    /** Курьер снимает себя с заказа («не смогу везти»): посылка возвращается в общий список. */
    /**
     * «Я на месте» — курьер приехал. Одна кнопка на ОБА конца: сервер сам понимает по статусу,
     * у кого он стоит (у отправителя или у получателя), и с этой минуты идёт платное ожидание
     * по тем же правилам, что у такси. Повторное нажатие ничего не ломает — счётчик уже идёт.
     */
    /** Мой приоритет водителя: сколько баллов, за что и что их отнимает. */
    suspend fun getDriverPriority(): Result<PriorityDto> =
        call("GET", "/driver/priority", null, auth = true).map { parsePriority(it) }

    /** Мой приоритет курьера — тот же расклад, но по доставкам (роли считаются раздельно). */
    suspend fun getCourierPriority(): Result<PriorityDto> =
        call("GET", "/courier/priority", null, auth = true).map { parsePriority(it) }

    private fun parsePriority(o: JSONObject): PriorityDto {
        fun rows(key: String): List<PriorityPartDto> {
            val arr = o.optJSONArray(key) ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    PriorityPartDto(it.optString("code"), it.optInt("points"), it.optDouble("value", 0.0))
                }
            }
        }
        return PriorityDto(
            kind = o.optString("kind", "taxi"),
            points = o.optInt("points"),
            plus = o.optInt("plus"),
            minus = o.optInt("minus"),
            maxPoints = o.optInt("max_points", 3),
            parts = rows("parts"),
            rules = rows("rules"),
            feedDelaySec = o.optInt("feed_delay_sec"),
        )
    }

    suspend fun parcelArrived(parcelId: Int): Result<CourierArrivedDto> =
        call("POST", "/parcels/$parcelId/arrived", null, auth = true).map { o ->
            CourierArrivedDto(
                where = o.optString("where"),
                waitFreeMin = o.optInt("wait_free_min"),
                waitFeeRubPerMin = o.optInt("wait_fee_rub_per_min"),
                waitingFeeKop = o.optInt("waiting_fee_kop"),
            )
        }

    suspend fun parcelRelease(parcelId: Int, reason: String = ""): Result<Unit> =
        call("POST", "/parcels/$parcelId/release", JSONObject().put("reason", reason.take(200)), auth = true).map { }

    /** Отправитель просит курьера заехать ещё раз: «получатель уже дома».
     *
     *  Ступенька между «не застал» и возвратом. Раньше её не было: посылка либо чудом
     *  вручалась, либо ехала обратно, и отправитель платил почти полную стоимость доставки
     *  за то, что человека не оказалось дома. Заезд по просьбе оплачивается как половина
     *  маршрута — платит тот, кто попросил (правило UPS, забранное себе).
     *
     *  Открыта ли просьба прямо сейчас — решает СЕРВЕР (`canRequestRedelivery`): у телефона
     *  нет ни числа попыток курьера, ни предела из конфига. */
    suspend fun parcelRedeliverRequest(parcelId: Int, reason: String = ""): Result<ParcelDto> =
        call("POST", "/parcels/$parcelId/redeliver-request",
             JSONObject().put("reason", reason.take(200)), auth = true)
            .map { parseParcel(it) }
            .onSuccess { Analytics.log("parcel_redeliver_request") }

    /** Курьер везёт посылку ОБРАТНО (получателя нет / отказался / не выходит на связь). */
    suspend fun parcelReturnStart(parcelId: Int, reason: String = ""): Result<ParcelDto> =
        call("POST", "/parcels/$parcelId/return-start", JSONObject().put("reason", reason.take(200)), auth = true)
            .map { parseParcel(it) }

    /** Курьер вернул посылку отправителю → заказ закрыт. Комиссию за возврат не берём. */
    suspend fun parcelReturnDone(parcelId: Int): Result<ParcelDto> =
        call("POST", "/parcels/$parcelId/return-done", JSONObject(), auth = true).map { parseParcel(it) }

    // ---------- админ: SOS, посылки, долги ----------

    /** Лента сигналов SOS (open сверху). Раньше её не существовало: сигнал уходил одним
     *  сообщением в Telegram, и если его не прочитали ночью — следа не оставалось. */
    suspend fun adminSosList(status: String = "open", limit: Int = 100): Result<List<AdminSosDto>> =
        call("GET", "/admin/sos?status=$status&limit=$limit", null, auth = true).map { obj ->
            // Сервер отдаёт голый массив — call() заворачивает его в {"items": [...]}.
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AdminSosDto(
                    id = o.optInt("id"),
                    status = o.optString("status"),
                    category = o.optString("category"),
                    note = o.optString("note"),
                    createdAt = o.optString("created_at"),
                    handledAt = nStr(o, "handled_at"),
                    handledNote = o.optString("handled_note"),
                    userId = o.optInt("user_id"),
                    userName = o.optString("user_name"),
                    userPhone = o.optString("user_phone"),
                    route = o.optString("route"),
                    orderId = nInt(o, "order_id"),
                    bookingId = nInt(o, "booking_id"),
                )
            }
        }

    /** «Принял» — сигнал взят в работу: кто, когда, что сделал. */
    suspend fun adminSosHandle(eventId: Int, note: String = ""): Result<Unit> =
        call("POST", "/admin/sos/$eventId/handle", JSONObject().put("note", note.take(500)), auth = true).map { }

    /** Водитель включает денежные чаевые: свой номер СБП (пусто — отключить).
     *  Платформа денег не касается — пассажир переводит водителю напрямую. */
    suspend fun setTipsSbp(sbp: String): Result<Boolean> =
        call("POST", "/me/tips-sbp", JSONObject().put("sbp", sbp.trim()), auth = true)
            .map { it.optBoolean("accepting") }

    /** Админ: списать долг по-человечески (пассажир не заплатил, поездка сорвалась). */
    suspend fun adminForgiveDebt(debtId: Int, reason: String = ""): Result<Unit> =
        call("POST", "/admin/debts/$debtId/forgive", JSONObject().put("reason", reason.take(300)), auth = true).map { }

    /** Админ: отменить посылку (курьер пропал, отправитель просит закрыть). */
    suspend fun adminParcelCancel(parcelId: Int, reason: String = ""): Result<Unit> =
        call("POST", "/admin/parcels/$parcelId/cancel", JSONObject().put("reason", reason.take(200)), auth = true).map { }

    /** Админ: снять курьера с посылки (пропал со связи) — заказ вернётся в общий список. */
    suspend fun adminParcelReleaseCourier(parcelId: Int, reason: String = ""): Result<Unit> =
        call("POST", "/admin/parcels/$parcelId/release-courier", JSONObject().put("reason", reason.take(200)), auth = true).map { }

    /** Мои бейджи профиля (G8): поездки, помощь посылкам, стаж, «проверен».
     *  Бэкенд был готов давно, приложение его не звало — награда существовала только в БД. */
    suspend fun getMyAchievements(): Result<AchievementsDto> =
        call("GET", "/me/achievements", null, auth = true).map { o ->
            val arr = o.optJSONArray("achievements") ?: JSONArray()
            AchievementsDto(
                trips = o.optInt("trips"),
                parcelsHelped = o.optInt("parcels_helped"),
                daysWithYuldash = o.optInt("days_with_yuldash"),
                earnedCount = o.optInt("earned_count"),
                items = (0 until arr.length()).map { i ->
                    val b = arr.getJSONObject(i)
                    AchievementDto(
                        code = b.optString("code"),
                        ru = b.optString("ru"),
                        ba = b.optString("ba"),
                        goal = b.optInt("goal"),
                        value = b.optInt("value"),
                        earned = b.optBoolean("earned"),
                    )
                },
            )
        }

    /** Трекинг-ссылка посылки для ПОЛУЧАТЕЛЯ (он без приложения смотрит доставку в браузере).
     *  Дедуп на сервере: повтор возвращает тот же токен — ссылка у получателя не протухает. */
    suspend fun createParcelTrackLink(parcelId: Int): Result<ParcelTrackLinkDto> =
        call("POST", "/parcels/$parcelId/track-link", JSONObject(), auth = true).map { o ->
            ParcelTrackLinkDto(url = o.optString("url"), smsSent = o.optBoolean("sms_sent"))
        }

    /** Отозвать трекинг-ссылку (опечатка в номере → ссылка ушла чужому человеку). */
    suspend fun revokeParcelTrackLink(parcelId: Int): Result<Unit> =
        call("DELETE", "/parcels/$parcelId/track-link", null, auth = true).map { }

    // ---------- Админ: модерация текстовых отзывов о поездке ----------
    // Текст оценки публикуется в профиле ТОЛЬКО после одобрения (Rating.text_published).
    // Очередь на сервере была, экрана не было — поэтому тексты не публиковались НИКОГДА,
    // и в профилях висели одни звёздочки, а люди писали отзывы в пустоту (аудит 2026-07-26).

    /** Тексты, ждущие модерации (свежие сверху). */
    suspend fun adminPendingRatings(): Result<List<PendingRatingDto>> =
        call("GET", "/admin/ratings/pending", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingRatingDto(
                    id = o.optInt("id"),
                    author = o.optString("author"),
                    rateeId = o.optInt("ratee_id"),
                    ratee = o.optString("ratee"),
                    stars = o.optInt("stars"),
                    text = o.optString("text"),
                    createdAt = o.optString("created_at"),
                )
            }
        }

    /** Одобрить текст к показу в публичном профиле (или снять с публикации). */
    suspend fun adminPublishRating(ratingId: Int, published: Boolean = true): Result<Unit> =
        call("POST", "/admin/ratings/$ratingId/publish", JSONObject().put("published", published), auth = true).map { }

    /** «Щит рейтинга»: спорная/мстительная оценка перестаёт влиять на средний балл. */
    suspend fun adminExcludeRating(ratingId: Int, excluded: Boolean = true): Result<Unit> =
        call("POST", "/admin/ratings/$ratingId/exclude", JSONObject().put("excluded", excluded), auth = true).map { }

    // ═══════════ «Справедливость»: двусторонние споры (due process) ═══════════
    // Обе стороны слышимы: заявитель описывает → обвинённый объясняется → админ решает и
    // объясняет обоим. Фото-доказательства приватны (/secure/evidence, видят только стороны и админ).
    // Бэкенд был готов давно, но в приложении подсистемы не существовало (аудит 2026-07-26).

    private fun JSONObject.toIncidentDto(): IncidentDto {
        fun urls(key: String): List<String> =
            optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        return IncidentDto(
            id = optInt("id"),
            bookingId = nInt(this, "booking_id"),
            type = optString("type"),
            severe = optBoolean("severe"),
            status = optString("status"),
            reporterRole = optString("reporter_role"),
            description = optString("description"),
            respondentStatement = optString("respondent_statement"),
            respondedAt = nStr(this, "responded_at"),
            resolution = optString("resolution"),
            fault = optString("fault"),
            resolutionNote = optString("resolution_note"),
            compensationKop = optInt("compensation_kop"),
            appealText = optString("appeal_text"),
            appealStatus = optString("appeal_status"),
            createdAt = optString("created_at"),
            updatedAt = optString("updated_at"),
            resolvedAt = nStr(this, "resolved_at"),
            myRole = optString("my_role"),
            otherName = optString("other_name"),
            evidenceUrls = urls("evidence_urls"),
            respondentEvidenceUrls = urls("respondent_evidence_urls"),
            route = nStr(this, "booking_route"),
            // Только в админ-выдаче (у участников пусто — приватность: телефон второй стороны не отдаём).
            reporterId = optInt("reporter_id"),
            reporterName = optString("reporter_name"),
            reporterPhone = optString("reporter_phone"),
            respondentId = optInt("respondent_id"),
            respondentName = optString("respondent_name"),
            respondentPhone = optString("respondent_phone"),
        )
    }

    /** Мои споры (и где я заявитель, и где обвинён) — лента «Центра справедливости». */
    suspend fun getMyIncidents(): Result<List<IncidentDto>> =
        call("GET", "/incidents/mine", null, auth = true).map { obj ->
            val arr = obj.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).toIncidentDto() }
        }

    /** Один спор (участник или админ). */
    suspend fun getIncident(id: Int): Result<IncidentDto> =
        call("GET", "/incidents/$id", null, auth = true).map { it.toIncidentDto() }

    /** Открыть спор. Контекст обязателен: бронь попутки ИЛИ такси-заказ — иначе сервер даёт 400
     *  (без привязки к общей поездке спор стал бы каналом харассмента). */
    suspend fun fileIncident(
        respondentId: Int, type: String, description: String,
        bookingId: Int? = null, orderId: Int? = null, evidenceUrls: List<String> = emptyList(),
    ): Result<IncidentDto> {
        val body = JSONObject()
            .put("respondent_id", respondentId)
            .put("type", type)
            .put("description", description.take(2000))
        if (bookingId != null) body.put("booking_id", bookingId)
        if (orderId != null) body.put("order_id", orderId)
        if (evidenceUrls.isNotEmpty()) body.put("evidence_urls", JSONArray(evidenceUrls))
        return call("POST", "/incidents", body, auth = true)
            .map { it.toIncidentDto() }.onSuccess { Analytics.log("incident_file") }
    }

    /** «Объясниться» — право на защиту второй стороны (с фото). */
    suspend fun respondIncident(id: Int, statement: String, evidenceUrls: List<String> = emptyList()): Result<IncidentDto> {
        val body = JSONObject().put("statement", statement.take(2000))
        if (evidenceUrls.isNotEmpty()) body.put("evidence_urls", JSONArray(evidenceUrls))
        return call("POST", "/incidents/$id/respond", body, auth = true).map { it.toIncidentDto() }
    }

    /** Апелляция — только на решённый спор и только один раз (сервер даёт 409 иначе). */
    suspend fun appealIncident(id: Int, text: String): Result<IncidentDto> =
        call("POST", "/incidents/$id/appeal", JSONObject().put("text", text.take(2000)), auth = true)
            .map { it.toIncidentDto() }

    /** «Мы решили миром» — закрывает спор без последствий. Может только заявитель и только до вердикта. */
    suspend fun withdrawIncident(id: Int): Result<IncidentDto> =
        call("POST", "/incidents/$id/withdraw", JSONObject(), auth = true).map { it.toIncidentDto() }

    /** Моё положение: Надёжность, страйки, предупреждения, пауза, активные споры. */
    suspend fun getMyStanding(): Result<StandingDto> =
        call("GET", "/me/standing", null, auth = true).map { o ->
            StandingDto(
                standing = o.optString("standing"),
                strikes = o.optInt("strikes"),
                warnings = o.optInt("warnings"),
                reliability = o.optInt("reliability"),
                suspendedUntil = nStr(o, "suspended_until"),
                suspendReason = o.optString("suspend_reason"),
                ratingShield = o.optBoolean("rating_shield"),
                activeIncidents = o.optInt("active_incidents"),
                canAct = o.optBoolean("can_act", true),
            )
        }

    /** Пороги лестницы — показываем ИЗ СЕРВЕРА, чтобы приложение не врало о правилах. */
    suspend fun getSafetyPolicy(): Result<SafetyPolicyDto> =
        call("GET", "/safety/policy", null, auth = true).map { o ->
            SafetyPolicyDto(
                strikesToLimit = o.optInt("strikes_to_limit", 2),
                strikesToSuspend = o.optInt("strikes_to_suspend", 3),
                suspend1Days = o.optInt("suspend_1_days", 3),
                suspend2Days = o.optInt("suspend_2_days", 7),
                suspend3Days = o.optInt("suspend_3_days", 30),
                strikeDecayDays = o.optInt("strike_decay_days", 180),
            )
        }

    /** Загрузить фото-доказательство → приватный URL (/secure/evidence/...). НЕ публичный /media. */
    suspend fun uploadEvidence(bytes: ByteArray, ext: String = "jpg"): Result<String> =
        callMultipart("/upload/evidence", bytes, ext, "evidence.$ext").map { it.optString("url") }

    /** Админ: очередь споров. status: open|awaiting_response|under_review|appealed|resolved|closed. */
    suspend fun adminIncidents(status: String? = null): Result<List<IncidentDto>> =
        call("GET", "/admin/incidents" + (if (status.isNullOrBlank()) "" else "?status=$status"), null, auth = true)
            .map { obj ->
                val arr = obj.optJSONArray("items") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).toIncidentDto() }
            }

    /** Админ: решение по спору. resolution: dismissed|warning|strike|suspend|ban|mutual_resolved;
     *  fault: none|reporter|respondent|both|unclear. Наказание всегда ложится на обвинённого —
     *  сервер отвергает «вина заявителя» вместе со страйком (иначе накажем невиновного). */
    suspend fun adminResolveIncident(
        id: Int, resolution: String, fault: String, note: String,
        compensationKop: Int = 0, strike: Boolean = false, suspendDays: Int? = null,
        excludeRating: Boolean = false, shield: Boolean = false,
    ): Result<IncidentDto> {
        val body = JSONObject()
            .put("resolution", resolution).put("fault", fault).put("note", note.take(2000))
            .put("compensation_kop", compensationKop).put("strike", strike)
            .put("exclude_rating", excludeRating).put("shield", shield)
        if (suspendDays != null) body.put("suspend_days", suspendDays)
        return call("POST", "/admin/incidents/$id/resolve", body, auth = true).map { it.toIncidentDto() }
    }

    /** Админ: закрыть посылку вручную (разобрали спор офлайн).
     *  status: delivered | returned | canceled — при returned/canceled сервер обнуляет комиссию. */
    suspend fun adminParcelClose(parcelId: Int, status: String = "returned", reason: String = ""): Result<Unit> =
        call("POST", "/admin/parcels/$parcelId/close",
            JSONObject().put("status", status).put("reason", reason.take(200)), auth = true).map { }
}

/** Ошибка API с кодом и понятным текстом для пользователя. */
/**
 * Отказ сервера. `status` — код HTTP, `detailCode` — машинная причина, если сервер её прислал
 * (`detail.code`). Причина нужна там, где от неё зависит СЛЕДУЮЩИЙ шаг клиента: у выплат
 * «банк отклонил» и «ответ банка непонятен» выглядят одинаково (оба 400), а вести себя после
 * них надо ровно наоборот — начать новую попытку или не трогать её вовсе (волна 219).
 */
class ApiException(val status: Int, message: String, val detailCode: String = "") : Exception(message)

/** Цена одного класса машины в options оценки — все цены одним запросом.
 *  `open=false` — класс есть в тарифах, но в этом городе ещё не набралось водителей. */
data class InstantClassOption(
    val category: String,
    val price: Int,
    val open: Boolean = true,
    /** Через сколько подъедет машина ИМЕННО этого класса. null = таких рядом нет либо
     *  сервер старый — тогда на карточке минут не пишем вовсе, а не показываем чужие. */
    val pickupEtaMin: Int? = null,
)

/** Что предложить, когда в выбранном классе никого. Цена — уже пересчитанная под этот класс. */
data class InstantAlternativeDto(val category: String, val price: Int, val priceDiff: Int)

/** Один класс на экране водителя: доступен ли машине, включён ли, чего не хватает,
 *  сколько водителей набралось в районе. */
data class DriverClassDto(
    val carClass: String,
    val category: String,
    val available: Boolean,
    val enabled: Boolean,
    val missing: List<String>,
    val driversHave: Int,
    val driversNeed: Int,
    val open: Boolean,
    val first: Boolean,
)

data class DriverClassesDto(
    val place: String,
    val classes: List<DriverClassDto>,
    val options: List<String>,
    val allOptions: List<String>,
    val colorOk: Boolean?,      // null = цвет не распознан, решит модератор
    val carYear: Int?,
    val seats: Int?,
)

/** Один серверный фактор автоматической цены. kind: base | duration | multiplier | cap | notice. */
data class InstantPriceFactorDto(
    val code: String,
    val kind: String,
    val k: Double,
    val active: Boolean,
    val titleRu: String,
    val titleBa: String,
    val descriptionRu: String,
    val descriptionBa: String,
    /** Сколько эта строка стоит в рублях. Заполнена у kind="money" (дальняя подача);
     *  у коэффициентов ноль — там цена берётся умножением, отдельной суммы нет. */
    val amountRub: Int = 0,
)

/** Оценка цены быстрого заказа (сервер считает сам по своей формуле).
 *  surgeK > 1.0 → час пик: плашка surgeNote (RU/BA) показывается ДО заказа, цена уже с k. */
data class InstantEstimateDto(
    val price: Int,
    val distanceKm: Double,
    val etaMin: Double,
    // Через сколько подъедет машина. null = рядом никого / не знаем — честно молчим, а не
    // выдумываем число. Это НЕ etaMin: тот про длительность самой поездки А→Б.
    val pickupEtaMin: Int? = null,
    val zone: String,
    val category: String,
    val tariffId: Int,
    val surgeK: Double = 1.0,
    val surgeNoteRu: String = "",
    val surgeNoteBa: String = "",
    val options: List<InstantClassOption> = emptyList(),
    // Динамический тариф v2. Defaults сохраняют совместимость со старым сервером.
    val basePrice: Int = 0,
    val dynamicK: Double = 1.0,
    val pricingCapK: Double = 1.5,
    val pricingVersion: String = "v1",
    val routeSource: String = "fallback",
    val trafficType: String = "unknown",
    val trafficK: Double = 1.0,
    val pickupK: Double = 1.0,
    val weatherK: Double = 1.0,
    val weatherCode: String = "",
    val hasTolls: Boolean = false,
    val priceFactors: List<InstantPriceFactorDto> = emptyList(),
    // Дальняя подача: с 2026-08-23 это отдельная СТРОКА СЧЁТА в рублях, а не коэффициент.
    // `price` = ridePrice + pickupFee. Деньги идут водителю за дорогу к пассажиру, комиссию
    // с них не берём. Старый сервер полей не шлёт → нули, и строка просто не появится.
    val ridePrice: Int = 0,
    val pickupFee: Int = 0,
    val pickupKm: Double = 0.0,
    // Рядом машин нет: честной цифры не существует. Обещаем потолок (`pickupMaxRub`),
    // точную сумму фиксируем, когда водитель согласится.
    val pickupPending: Boolean = false,
    val pickupMaxRub: Int = 0,
    val pickupNoteRu: String = "",
    val pickupNoteBa: String = "",
    // Водителю и так по пути в эту сторону → подача вдвое дешевле. `pickupFullFee` — сколько
    // она стоила бы, если бы он ехал специально: без этой цифры выгода не видна.
    val pickupEnroute: Boolean = false,
    val pickupFullFee: Int = 0,
    // «Сюда уже едет машина — подождёшь, и за подачу платить не придётся».
    // Минуты = 0 → подсказки нет (машины по пути сюда не видно или экономить нечего).
    val pickupWaitMinutes: Int = 0,
    val pickupWaitSaveRub: Int = 0,
    val pickupWaitRu: String = "",
    val pickupWaitBa: String = "",
    // На сколько секунд эта цена закреплена: пока человек думает, она не вырастет.
    // 0 = заморозка выключена или Redis недоступен — тогда про неё молчим, а не обещаем зря.
    val priceLockedSec: Int = 0,
    // Опции салона деньгами (детское кресло 150 ₽ и т.д.). `optionCatalog` — прайс ВСЕХ опций
    // с сервера: клиент подписывает цену на галочке, не храня второй список у себя.
    val optionsFee: Int = 0,
    val optionCatalog: Map<String, Int> = emptyMap(),
    // Зимняя дорога: компенсация водителю за гололёд/метель/снег/мороз. Тоже вне наценки
    // и без комиссии. `weatherKind` нужен подписи: «Гололёд» объясняет, «погода» — нет.
    val weatherFee: Int = 0,
    val weatherKind: String = "",
    // Промокод-скидка на поездку в такси (kind=taxi_ride). Считает и решает сервер: клиент только
    // показывает выгоду ДО заказа. Пустой код и нули = скидки нет ИЛИ сервер старый — в обоих
    // случаях на экране не должно быть ни «−0 ₽», ни перечёркнутых цен.
    val promoCode: String = "",
    val promoDiscountKop: Int = 0,
    val priceWithDiscount: Int = 0,
    // promo_note приходит объектом {ru, ba} (как surge_note) — надпись обязана быть на двух языках.
    val promoNoteRu: String = "",
    val promoNoteBa: String = "",
    // Круговой рейс. Всё считает сервер: доступен ли (только межгород), сколько стоит и
    // какая скидка. Старый сервер полей не шлёт → false и нули, переключатель не появится.
    val roundTripAvailable: Boolean = false,
    val roundTripPrice: Int = 0,
    val roundTripDiscountPercent: Int = 0,
    val roundTripMaxWaitHours: Int = 4,
    val roundTrip: Boolean = false,
) {
    /** Скидка реально есть и её видно человеку. Всё остальное — «скидки нет», без пустых плашек. */
    val hasPromoDiscount: Boolean get() = promoDiscountKop > 0

    /** Цена, которую человек реально заплатит: со скидкой, если она есть. */
    val priceToPay: Int get() = if (hasPromoDiscount) priceWithDiscount.coerceAtLeast(0) else price
}

/** Что будет, если сменить адрес: цена и из чего она сложилась.
 *
 *  `applied=false` + `waitingDriver=true` — крупная смена (межгород или тройная цена),
 *  ждём слова водителя. `applied=false` без него — это превью, заказ не тронут. */
/** Остановка по пути. С координатами: по ним строится маршрут и считается цена.
 *  `done` — уже проехали (её нельзя убрать, спорить не о чем). */
data class TaxiStop(val lat: Double, val lng: Double, val text: String, val done: Boolean = false)

data class DestinationQuoteDto(
    val price: Int,
    val oldPrice: Int,
    val drivenKm: Double,          // сколько уже проехали — за это платят в любом случае
    val restKm: Double,            // сколько осталось до нового адреса
    val distanceKm: Double,
    val needsDriverOk: Boolean,
    val askReason: String,         // zone | price | ""
    val applied: Boolean,
    val waitingDriver: Boolean,
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
    val funnel: TaxiFunnelDto? = null,   // «посмотрел цену → заказал»; null = сервер не прислал
    val byCity: List<TaxiPulseCityDto>,
)

/** Одна строка приоритета: за что дали (или сняли) баллы и с какой цифрой. */
data class PriorityPartDto(val code: String, val points: Int, val value: Double)

/**
 * Приоритет исполнителя: кому заказ достаётся первым (backend/app/priority.py).
 *
 * Показываем ЦЕЛИКОМ и всегда: скрытый приоритет человек читает как «заказы раздают
 * по блату» — это ровно та боль Яндекса, против которой мы строимся.
 */
data class PriorityDto(
    val kind: String = "taxi",
    val points: Int = 0,
    val plus: Int = 0,
    val minus: Int = 0,
    val maxPoints: Int = 3,
    val parts: List<PriorityPartDto> = emptyList(),
    val rules: List<PriorityPartDto> = emptyList(),
    /** Курьер: через сколько секунд заказ увидят остальные. 0 = видишь сразу. */
    val feedDelaySec: Int = 0,
)

/**
 * Ответ на «Я на месте» у курьера: где он стоит и по каким правилам пошло ожидание.
 * `where`: sender — у отправителя, receiver — у получателя.
 */
data class CourierArrivedDto(
    val where: String = "",
    val waitFreeMin: Int = 0,
    val waitFeeRubPerMin: Int = 0,
    val waitingFeeKop: Int = 0,
)

/** Один день воронки: сколько раз смотрели цену и сколько из этого стало заказом. */
data class TaxiFunnelDayDto(val day: String, val views: Int, val orders: Int)

/**
 * Воронка «посмотрел цену → заказал» (backend/app/funnel.py).
 * Проценты — Double?: null значит «никто не смотрел», и это НЕ то же самое, что 0%.
 */
data class TaxiFunnelDto(
    val windowDays: Int = 7,
    val viewsToday: Int = 0,
    val ordersToday: Int = 0,
    val percentToday: Double? = null,
    val viewsPeriod: Int = 0,
    val ordersPeriod: Int = 0,
    val percentPeriod: Double? = null,
    val byDay: List<TaxiFunnelDayDto> = emptyList(),
)

data class InstantOrderDto(
    val id: Int,
    val status: String,           // created/searching/offered/accepted/arriving/onboard/done/cancelled/expired
    val role: String,             // "driver" | "passenger" — чья это витрина
    val fromLat: Double, val fromLng: Double,
    val toLat: Double, val toLng: Double,
    val fromText: String, val toText: String,
    val category: String,
    /** Чем рассчитываются: cash | sbp | negotiate. Приложение денег не касается — это
     *  запись договорённости, и видят её ОБЕ стороны: спор «я думал, ты переводом»
     *  случается ровно потому, что до высадки об этом никто не говорил. */
    val paymentMethod: String = "negotiate",
    /** «Только женщина за рулём»: экран должен объяснить, почему машину искали дольше
     *  или не нашли вовсе — иначе человек решит, что приложение сломалось. */
    val womenOnly: Boolean = false,
    val priceEstimate: Int,
    val priceFinal: Int?,
    // Из чего сложилась сумма: поездка + дорога водителя к пассажиру (2026-08-23).
    // Пассажиру — чтобы видеть, за что платит; водителю — чтобы видеть, что компенсация
    // за подачу дошла до него целиком (комиссию с неё не берём).
    val ridePrice: Int = 0,
    val pickupFeeKop: Int = 0,
    val pickupKm: Double = 0.0,
    // Заказ создавался, когда рядом не было машин: сумма подачи появится при принятии.
    val pickupPending: Boolean = false,
    // Водителю было по пути → подача вдвое дешевле. Нужен в чеке: иначе не объяснить,
    // почему за такую же дорогу у соседа вышло дороже.
    val pickupEnroute: Boolean = false,
    // Опции салона деньгами (кресло 150 ₽ и т.д.): уходят водителю целиком, без комиссии.
    val optionsFeeKop: Int = 0,
    // Зимняя дорога — тоже его деньги, без комиссии.
    val weatherFeeKop: Int = 0,
    val weatherKind: String = "",
    val distanceKm: Double,
    val etaMin: Double,
    val driverId: Int?,
    // id пассажира — приходит ТОЛЬКО водителю и только после accept (как имя и телефон).
    // Нужен, чтобы водитель мог открыть разбор: спор требует указать вторую сторону.
    val passengerId: Int? = null,
    val offerExpiresAt: String?,  // ISO — когда протухнет текущий оффер (таймер водителя ведём локально)
    val cancelBy: String,         // "" | passenger | driver
    // Сколько раз заказ возвращался в поиск после того, как назначенный водитель отменил.
    // Экрану поиска это нужно, чтобы объяснить человеку, куда делась принятая им машина:
    // без строки он видит просто «ищем машину» и решает, что приложение сбросилось.
    val reassigns: Int = 0,
    // Водитель довёз и уехал, «Завершил» не нажал — считает сервер, когда пассажиру можно
    // закрыть поездку самому. Локальный секундомер экрана перезапуск не переживёт.
    val passengerCanClose: Boolean = false,
    // Оценка. Звёзды жили только на свежем финальном экране — закрыл его, и оценить поездку
    // было негде, хотя окно открыто 60 дней (аудит сценариев 30.08). Считает сервер.
    val myStars: Int = 0,        // сколько звёзд я уже поставил по этой поездке (0 — не оценивал)
    val canRate: Boolean = false, // окно оценки ещё открыто
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
    // Предзаказ «на время» (scheduled): ISO времени подачи. null = обычный (мгновенный) заказ.
    val scheduledAt: String? = null,
    // --- закрытие пробелов такси (аудит 2026-07-26). Значения по умолчанию = поведение старого
    // сервера: клиент новее бэкенда не падает, просто не показывает новое.
    // ДОВЕРИЕ (волна 160). Раньше в такси о водителе знали только имя, рейтинг и марку —
    // в попутке те же данные показываются давно, а до такси не доезжали. Человек садится
    // в чужую машину ночью: лицо, стаж и «свой» решают больше, чем звёздочка.
    // Умолчания пустые: клиент новее сервера просто не покажет новое, а не упадёт.
    val driverAvatar: String = "",     // фото водителя (публичный media-URL). Пассажиру — да, обратно — нет
    val driverTrips: Int = 0,          // завершённых поездок: «312 поездок»
    val driverSince: String = "",      // месяц регистрации "YYYY-MM" → «с нами с марта»
    val driverFrom: String = "",       // город или район водителя: «из Баймака» — то, чего у федералов нет
    val driverPlate: String = "",      // госномер — по нему узнают машину во дворе, «белая Гранта» не помогает
    val comment: String = "",          // «за магазином, синие ворота» — как найти пассажира
    val entrance: String = "",         // подъезд / квартира / этаж
    val forOther: Boolean = false,     // заказ ДЛЯ ДРУГОГО: имя и телефон в payload — того, кого везём
    val lostItemUntil: String? = null, // ISO — до когда чат снова открыт под «забыл вещь»
    val thanked: Boolean = false,      // пассажир уже сказал «рәхмәт»
    val waitUntil: String? = null,     // ISO — заказ в очереди «подожду машину», воркер продолжит поиск
    // Серверная расшифровка денег водителя. В пассажирской витрине все поля = 0.
    val driverGrossKop: Int = 0,
    val driverFeeKop: Int = 0,
    val driverNetKop: Int = 0,
    val driverFeePercent: Double = 0.0,
    // Часы поиска. Свой таймер на экране врёт: свернул приложение — отсчёт начался заново.
    // createdAt — сколько человек ждёт ВСЕГО (перезапуск поиска его не сбрасывает);
    // searchingAt — начало текущего круга подбора (у предзаказа это активация, а не бронирование).
    val createdAt: String? = null,
    val searchingAt: String? = null,
    // Промокод на поездку, зафиксированный в ЭТОМ заказе (копейки). Скидку оплачивает Юлдаш из
    // своей комиссии (не хватило — доплачивает водителю в кошелёк), водитель получает ровно
    // столько же, как без промокода. Оба поля видят обе стороны: иначе водитель попросит полную
    // сумму, а пассажир будет уверен, что платит со скидкой.
    val promoDiscountKop: Int = 0,
    val passengerPriceKop: Int = 0,
    // --- смена адреса (аддитивно: старый сервер этих полей не шлёт) ---
    val destinationChanges: Int = 0,
    // Водитель подтвердил, что видел новый адрес.
    val destinationAck: Boolean = false,
    // Минуту молчит — пассажиру пора предложить позвонить, а не крутить спиннер.
    val destinationAckOverdue: Boolean = false,
    // Крупная смена ждёт слова водителя: текст адреса, цена, причина (zone|price).
    val pendingToText: String = "",
    val pendingPrice: Int = 0,
    val pendingReason: String = "",
    // Когда спросили водителя — для живого счётчика «ждём N минут».
    val pendingAskedAt: String? = null,
    // Водитель завершил поездку досрочно и почему: shift_end|out_of_zone|no_fuel|other.
    val earlyFinishReason: String = "",
    // Остановки по пути и признак «сейчас стоим на остановке» (тикает ожидание).
    val stops: List<TaxiStop> = emptyList(),
    val standing: Boolean = false,
) {
    /** Полная цена поездки в копейках: фактическая, а до завершения — оценка. */
    val fullPriceKop: Int get() = (priceFinal ?: priceEstimate).coerceAtLeast(0) * 100

    /**
     * Сколько пассажир реально отдаёт водителю на руки (копейки) — скидка уже вычтена.
     * Старый сервер обоих полей не шлёт (нули) → берём полную цену: показать «0 ₽» человеку,
     * который вот-вот расплачивается в машине, хуже, чем не показать скидку.
     */
    val passengerPayKop: Int
        get() = if (passengerPriceKop > 0 || promoDiscountKop > 0) passengerPriceKop.coerceAtLeast(0) else fullPriceKop

    /** Скидка по промокоду в этом заказе есть и её видно человеку. */
    val hasPromoDiscount: Boolean get() = promoDiscountKop > 0

    /** С какого момента честно считать «ищем уже M:SS». null = сервер старый, счётчик не показываем. */
    val searchClockFrom: String? get() = if (scheduledAt != null) searchingAt else (createdAt ?: searchingAt)

    /** Терминальный статус — заказ окончен (успех/отмена/протух). */
    val isTerminal: Boolean get() = status == "done" || status == "cancelled" || status == "expired"
    /**
     * Заказ в очереди «подожду машину»: формально expired, но фоновый воркер продолжает искать.
     * Экран и поллинг должны считать такой заказ ЖИВЫМ, иначе человек нажал «подожду» — и
     * приложение тут же перестало следить за заказом, который вот-вот найдёт машину.
     */
    val isWaitingQueue: Boolean
        get() {
            val до = waitUntil
            if (до.isNullOrBlank() || status == "done" || status == "cancelled") return false
            // Срок ожидания СВЕРЯЕМ С ЧАСАМИ (аудит сценариев 30.08, P0). Раньше проверялось
            // только наличие срока — и когда воркер переставал искать, экран продолжал писать
            // «ищем машину дальше» до конца времён. Человек ждал машину, которую никто уже
            // не искал. Не разобрали строку времени — считаем очередь живой: чужой формат
            // даты не повод обрывать поиск, который, может быть, идёт.
            val конец = com.yuldash.app.parseIsoUtcMillis(до) ?: return true
            return конец > System.currentTimeMillis()
        }
    /** Предзаказ «на время», ещё не отправлен в поиск. */
    val isScheduled: Boolean get() = status == "scheduled"
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
    paymentMethod = optString("payment_method").ifBlank { "negotiate" },
    womenOnly = optBoolean("women_only", false),
    priceEstimate = optInt("price_estimate"),
    priceFinal = if (isNull("price_final")) null else optInt("price_final"),
    // Старый сервер этих полей не шлёт: «поездка» = весь чек, подачи нет — прежний вид экрана.
    ridePrice = optInt("ride_price", optInt("price_estimate")),
    pickupFeeKop = optInt("pickup_fee_kop"),
    pickupKm = optDouble("pickup_km", 0.0),
    pickupPending = optBoolean("pickup_pending", false),
    pickupEnroute = optBoolean("pickup_enroute", false),
    optionsFeeKop = optInt("options_fee_kop"),
    weatherFeeKop = optInt("weather_fee_kop"),
    weatherKind = optString("weather_kind"),
    distanceKm = optDouble("distance_km", 0.0),
    etaMin = optDouble("eta_min", 0.0),
    driverId = if (isNull("driver_id")) null else optInt("driver_id"),
    passengerId = if (isNull("passenger_id")) null else optInt("passenger_id"),
    offerExpiresAt = if (isNull("offer_expires_at")) null else optString("offer_expires_at").ifBlank { null },
    cancelBy = optString("cancel_by"),
    reassigns = optInt("reassigns"),
    passengerCanClose = optBoolean("passenger_can_close"),
    myStars = optInt("my_stars"),
    canRate = optBoolean("can_rate"),
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
    scheduledAt = if (isNull("scheduled_at")) null else optString("scheduled_at").ifBlank { null },
    driverPlate = optString("driver_plate"),
    driverAvatar = optString("driver_avatar"),
    driverTrips = optInt("driver_trips"),
    driverSince = optString("driver_since"),
    driverFrom = optString("driver_from"),
    comment = optString("comment"),
    entrance = optString("entrance"),
    forOther = optBoolean("for_other"),
    lostItemUntil = if (isNull("lost_item_until")) null else optString("lost_item_until").ifBlank { null },
    thanked = optBoolean("thanked"),
    waitUntil = if (isNull("wait_until")) null else optString("wait_until").ifBlank { null },
    driverGrossKop = optInt("driver_gross_kop"),
    driverFeeKop = optInt("driver_fee_kop"),
    driverNetKop = optInt("driver_net_kop"),
    driverFeePercent = optDouble("driver_fee_percent", 0.0),
    createdAt = if (isNull("created_at")) null else optString("created_at").ifBlank { null },
    searchingAt = if (isNull("searching_at")) null else optString("searching_at").ifBlank { null },
    promoDiscountKop = optInt("promo_discount_kop"),
    passengerPriceKop = optInt("passenger_price_kop"),
    // --- смена адреса ---
    destinationChanges = optInt("destination_changes"),
    destinationAck = optBoolean("destination_ack", false),
    destinationAckOverdue = optBoolean("destination_ack_overdue", false),
    pendingToText = optJSONObject("pending_destination")?.optString("to_text") ?: "",
    pendingPrice = optJSONObject("pending_destination")?.optInt("price") ?: 0,
    pendingReason = optJSONObject("pending_destination")?.optString("reason") ?: "",
    pendingAskedAt = optJSONObject("pending_destination")?.optString("asked_at")?.ifBlank { null },
    earlyFinishReason = optString("early_finish_reason"),
    stops = optJSONArray("stops")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                TaxiStop(it.optDouble("lat"), it.optDouble("lng"),
                         it.optString("text"), it.optBoolean("done", false))
            }
        }
    } ?: emptyList(),
    standing = optBoolean("standing", false),
)

/** Мои предзаказы «на время»: ещё ждут (scheduled) + активированные ко времени (activated). */
data class ScheduledOrdersDto(
    val scheduled: List<InstantOrderDto>,
    val activated: List<InstantOrderDto>,
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

/**
 * Одно предупреждение о погоде на маршруте.
 *
 * Текст приходит с сервера сразу на двух языках, клиент его только показывает: пороги
 * («что считать метелью») живут в одном месте и правятся без выпуска приложения.
 * kind — для иконки и цвета: ice | blizzard | snow | fog | wind | frost | thunder.
 */
data class WeatherWarningDto(
    val kind: String,
    val ru: String,
    val ba: String,
    val severe: Boolean,
)

/** Погода на маршруте. available=false → карточки нет вовсе (нет данных, сбой источника). */
data class RouteWeatherDto(
    val available: Boolean,
    val warnings: List<WeatherWarningDto>,
    val temperatureC: Double?,
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
    // Сроки документов (580-ФЗ). null = не указан. Значения по умолчанию — на случай старого сервера.
    val osagoUntil: String? = null,
    val permitUntil: String? = null,
    val inspectionUntil: String? = null,   // диагностическая карта (техосмотр)
    // ОСГОП — страховка ответственности перевозчика, обязательна с 01.09.2024. Была в базе,
    // но не доезжала до экрана: не показывалась и никогда не истекала (аудит 30.08).
    val osgopUntil: String? = null,
    val docsExpired: Boolean = false,      // допуск к такси снят до обновления документа
    val docsMissing: List<String> = emptyList(),   // какие сроки не заполнены (модератору и водителю)
    val docsDaysLeft: Int? = null,         // дней до ближайшего истечения (отрицательное = просрочен)
    // Что ответил государственный реестр (580-ФЗ). Три состояния, и различать их обязательно:
    // проверяли и подтвердили; проверяли и разрешения нет; не спрашивали или реестр молчал —
    // тогда не показываем ничего, человек не виноват в нашем таймауте.
    val permitRegistryChecked: Boolean = false,
    val permitRegistryOk: Boolean = false,
    val permitRegistryUntil: String? = null,
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
    osagoUntil = if (isNull("osago_until")) null else optString("osago_until").ifBlank { null },
    permitUntil = if (isNull("permit_until")) null else optString("permit_until").ifBlank { null },
    inspectionUntil = if (isNull("inspection_until")) null else optString("inspection_until").ifBlank { null },
    osgopUntil = if (isNull("osgop_until")) null else optString("osgop_until").ifBlank { null },
    docsExpired = optBoolean("docs_expired"),
    docsMissing = optJSONArray("docs_missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
    docsDaysLeft = if (isNull("docs_days_left")) null else optInt("docs_days_left"),
    permitRegistryChecked = optBoolean("permit_registry_checked"),
    permitRegistryOk = optBoolean("permit_registry_ok"),
    permitRegistryUntil = optString("permit_registry_until").ifBlank { null },
    comment = optString("comment"),
    createdAt = optString("created_at"),
    reviewedAt = if (isNull("reviewed_at")) null else optString("reviewed_at").ifBlank { null },
    userId = optInt("user_id"),
    name = optString("name"),
    phone = optString("phone"),
    invitedBy = if (isNull("invited_by")) null else optString("invited_by").ifBlank { null },
)

/** Один кадр фотоконтроля: что снять, подсказка и что уже прислано. */
data class CarPhotoSlotDto(
    val code: String,          // front | back | left | right | salon | trunk
    val ru: String,
    val ba: String,
    val hintRu: String,
    val hintBa: String,
    val url: String? = null,   // уже присланный кадр (приватная ссылка)
    val verdict: String = "",  // "ok" или код причины: too_small | screenshot | stale | duplicate
)

/**
 * Состояние фотоконтроля машины (580-ФЗ).
 *
 * `stage` — ступень мягкой лестницы: ok (срок не вышел) → remind (1–3 дня) →
 * slow (4–7 дней, приоритет вниз) → blocked (пауза до фото). Экран показывает её словами,
 * а не цифрами: человек должен понимать, что будет дальше, до того как это случится.
 */
data class CarPhotoDto(
    val mode: String = "taxi",
    val enabled: Boolean = false,      // контроль включён на сервере
    val required: Boolean = false,     // прямо сейчас есть открытый контроль
    val status: String = "",           // waiting | review | passed | failed
    val seq: Int = 0,                  // номер контроля: первый — с «шашечками»
    val dueAt: String? = null,
    val daysLeft: Int = 0,             // отрицательное = просрочен
    val stage: String = "ok",
    val lateDays: Int = 0,
    val winter: Boolean = false,       // зимой чистый кузов не требуем
    val checkSigns: Boolean = false,   // первый контроль такси: фонарь и «шашечки»
    val manual: Boolean = false,       // смотрит человек
    val rejectReason: String = "",     // что переснять после отказа
    val slots: List<CarPhotoSlotDto> = emptyList(),
    val missing: List<String> = emptyList(),
    val lastPassedAt: String? = null,
    val keepDays: Int = 90,            // сколько живут сами снимки
    // Требование по жалобе «грязная машина» — если оно сейчас открыто.
    val demand: CarPhotoDemandDto? = null,
    // По каким пунктам смотрят салон. Все три видны на фото; запаха среди них нет.
    val cleanRules: List<BiText> = emptyList(),
)

/**
 * Требование фото по жалобе «грязная машина»: сутки на снимок салона.
 *
 * Отдельно от планового контроля намеренно: плановый — обязанность с лестницей и паузой,
 * требование — просьба показать то, о чём написали. Работу оно не ограничивает вообще.
 */
data class CarPhotoDemandDto(
    val id: Int = 0,
    val status: String = "",          // waiting | review
    val dueAt: String? = null,
    val hoursLeft: Int = 0,
    val overdue: Boolean = false,
    val slots: List<CarPhotoSlotDto> = emptyList(),
    val missing: List<String> = emptyList(),
)

/** Двуязычная строка правила: сервер шлёт оба языка, экран берёт нужный. */
data class BiText(val ru: String, val ba: String)

/** Оффер и причина, почему его нет. `blocked = null` — ждём заказ по-настоящему. */
data class DriverOfferStateDto(
    val offer: InstantOrderDto? = null,
    val blocked: String? = null,
)

/** Ответ на один присланный кадр. */
data class CarPhotoShotDto(
    val url: String,
    val slot: String,
    val ok: Boolean,
    val reason: String,
    val missing: List<String>,
)

private fun JSONObject.toCarPhotoSlots(): List<CarPhotoSlotDto> =
    optJSONArray("slots")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                CarPhotoSlotDto(
                    code = o.optString("code"),
                    ru = o.optString("ru"),
                    ba = o.optString("ba"),
                    hintRu = o.optString("hint_ru"),
                    hintBa = o.optString("hint_ba"),
                    url = if (o.isNull("url")) null else o.optString("url").ifBlank { null },
                    verdict = o.optString("verdict"),
                )
            }
        }
    } ?: emptyList()

private fun JSONObject.toCarPhotoDto(): CarPhotoDto {
    val slots = optJSONArray("slots")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                CarPhotoSlotDto(
                    code = o.optString("code"),
                    ru = o.optString("ru"),
                    ba = o.optString("ba"),
                    hintRu = o.optString("hint_ru"),
                    hintBa = o.optString("hint_ba"),
                    url = if (o.isNull("url")) null else o.optString("url").ifBlank { null },
                    verdict = o.optString("verdict"),
                )
            }
        }
    } ?: emptyList()
    return CarPhotoDto(
        mode = optString("mode", "taxi"),
        enabled = optBoolean("enabled"),
        required = optBoolean("required"),
        status = optString("status"),
        seq = optInt("seq"),
        dueAt = if (isNull("due_at")) null else optString("due_at").ifBlank { null },
        daysLeft = optInt("days_left"),
        stage = optString("stage", "ok"),
        lateDays = optInt("late_days"),
        winter = optBoolean("winter"),
        checkSigns = optBoolean("check_signs"),
        manual = optBoolean("manual"),
        rejectReason = optString("reject_reason"),
        slots = slots,
        missing = optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        lastPassedAt = if (isNull("last_passed_at")) null else optString("last_passed_at").ifBlank { null },
        keepDays = if (optInt("keep_days") > 0) optInt("keep_days") else 90,
        demand = optJSONObject("demand")?.let { d ->
            CarPhotoDemandDto(
                id = d.optInt("id"),
                status = d.optString("status"),
                dueAt = if (d.isNull("due_at")) null else d.optString("due_at").ifBlank { null },
                hoursLeft = d.optInt("hours_left"),
                overdue = d.optBoolean("overdue"),
                slots = d.toCarPhotoSlots(),
                missing = d.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        },
        cleanRules = optJSONArray("clean_rules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> BiText(o.optString("ru"), o.optString("ba")) }
            }
        } ?: emptyList(),
    )
}

/** Предрейсовое подтверждение на сегодня (580-ФЗ, честный минимум — самодекларация, не медосмотр).
 *  required=false → гейт выключен на сервере (пока не выкачено приложение с экраном). */
data class PretripDto(
    val required: Boolean,
    val confirmed: Boolean,
    val day: String,
    val confirmedAt: String?,
    val note: String,
)

/** Город, где включено такси (управляет админ). */
data class TaxiCityDto(val id: Int, val city: String, val enabled: Boolean)

/** Одна запись журнала предрейсовых подтверждений: кто и когда отметился в этот день. */
data class PretripEntryDto(
    val driverId: Int,
    val name: String,
    val phone: String,
    val confirmedAt: String,   // ISO-время подтверждения
    val note: String,          // заметка водителя (может быть пустой)
)

/** Журнал предрейсовых подтверждений за конкретный день (580-ФЗ, юридический след). */
data class PretripJournalDto(val day: String, val items: List<PretripEntryDto>)

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
    val district: String? = null,   // район («Иглинский р-н») — различать деревни-тёзки; у города null
)

/** Зона работы таксиста: city | intercity | region; null = не выбрана (беру всё рядом). */
/**
 * Зона работы. База — один НП (`city` + workCity) ИЛИ весь район (`district` + workDistrict);
 * плюс два согласия: выезд загород и соседние регионы. Старые значения зоны сервер сам
 * переводит в эту схему, поэтому workZone здесь всегда city|district (или null — не выбрана).
 */
data class InstantZoneDto(
    val workZone: String?,
    val workCity: String?,
    val workDistrict: String? = null,
    val workIntercity: Boolean = false,
    val workRegions: Boolean = false,
    val workDirectionId: Int?,
    val workDirection: SettlementDto?,
)

/** Район для выбора зоны: «Абзелиловский р-н», регион и сколько в нём НП. */
data class DistrictDto(val district: String, val region: String, val settlements: Int)

/** Смена такси за местный день (волна 2, §8 Отдых): прогресс к 8-часовому лимиту и блок отдыха. */
/** Свободная машина рядом (для карты такси): анонимная точка + ≈ETA до подачи. Без личности. */
data class NearbyDriverDto(
    val lat: Double,
    val lng: Double,
    val etaMin: Int,
    /** Класс кузова: standard | comfort | business | minivan. null = сервер не сказал,
     *  тогда на карте рисуем общую машинку. Это «какая машина», а не «кто за рулём»:
     *  точка остаётся анонимной. */
    val category: String? = null,
)

data class TaxiWorkdayDto(
    val day: String,                 // местный день учёта, ISO ("2026-07-10")
    val secondsOnline: Int,          // такси-время на линии за день, секунд
    val limitSec: Int,               // лимит смены, секунд (8ч)
    val remainingSec: Int,           // сколько осталось до лимита, секунд
    val limitHours: Int,             // лимит смены, часов (для текстов «из 8»)
    val blocked: Boolean,            // отдых: такси закрыто до unlockAt
    val unlockAt: String?,           // когда снова на линию (ISO, UTC-наивное), null если не заблокирован
    // Недельный потолок. Он был невидим: кабинет писал «смена свободна», а линию закрыла
    // НЕДЕЛЯ, и человек не понимал, почему не идут заказы (аудит сценариев 30.08).
    val weekBlocked: Boolean = false,   // линия закрыта недельным лимитом
    val weekLimitHours: Int = 40,       // недельный лимит, часов
    val weekSeconds: Int = 0,           // сколько уже за рулём за скользящую неделю, секунд
    val returnRideUsed: Boolean,     // «один попутчик домой» уже опубликован
    // Дашборд кабинета (заработок/заказы за сегодня + ступень комиссии по поездкам).
    val earningsToday: Int = 0,      // legacy: валовая сумма за сегодня, ₽
    val grossTodayKop: Int = 0,       // пассажиры заплатили, копейки
    val feeTodayKop: Int = 0,         // комиссия платформы, копейки
    val netTodayKop: Int = 0,         // чистый доход водителя, копейки
    val ordersToday: Int = 0,        // завершённых заказов сегодня
    val feePercent: Double = 0.0,    // текущая комиссия платформы, % (с учётом промо запуска)
    val tenureDays: Int = 0,         // стаж таксиста, дней с первого done-заказа (справка, лесенку не двигает)
    val tripsDone: Int = 0,          // завершённых поездок — позиция на лесенке комиссии
    val feeTiers: List<Double> = emptyList(),      // ступени комиссии [3,8,15]
    val feeTierTrips: List<Int> = emptyList(),     // границы ступеней в поездках [30,100]
    val feeNextPercent: Double? = null,            // следующая ступень, % (null = верхняя, дальше не растёт)
    val feeTripsToNext: Int? = null,               // сколько поездок до следующей ступени (null = верхняя)
    // Промо запуска «первым водителям — 0%». Оно идёт по КАЛЕНДАРЮ, а лесенка выше — по
    // поездкам: две разные шкалы, и путать их нельзя. Пока промо активно, ставку двигает
    // срок (promoDaysLeft), а не поездки, и после него водитель попадёт на СВОЮ ступень
    // (feeAfterPromoPercent), а не на следующую по лесенке.
    val promoActive: Boolean = false,
    val promoDaysLeft: Int? = null,
    val feeAfterPromoPercent: Double? = null,
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
    district = optNullableString("district"),
)

private fun JSONObject.toRouteWeatherDto(): RouteWeatherDto {
    val arr = optJSONArray("warnings") ?: org.json.JSONArray()
    return RouteWeatherDto(
        available = optBoolean("available"),
        warnings = (0 until arr.length()).map { i ->
            val w = arr.getJSONObject(i)
            WeatherWarningDto(
                kind = w.optString("kind"),
                ru = w.optString("ru"),
                ba = w.optString("ba"),
                severe = w.optBoolean("severe"),
            )
        },
        temperatureC = if (isNull("temperature_c")) null else optDouble("temperature_c"),
    )
}

private fun JSONObject.toInstantZoneDto() = InstantZoneDto(
    workZone = optNullableString("work_zone"),
    workCity = optNullableString("work_city"),
    workDistrict = optNullableString("work_district"),
    workIntercity = optBoolean("work_intercity"),
    workRegions = optBoolean("work_regions"),
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

/** Ориентир цены по маршруту. distanceKm/fuelEstimateKop приходят от сервера аддитивно
 *  (могут отсутствовать у старого бэкенда → null, блок «бензин» просто не показываем). */
data class PriceHintDto(
    val avg: Int,
    val count: Int,
    val distanceKm: Float? = null,
    val fuelEstimateKop: Int? = null,
)

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
    quiet = optBoolean("quiet"),
    noMinors = optBoolean("no_minors"),
    waypoints = optString("waypoints").split(" | ").map { it.trim() }.filter { it.isNotBlank() },
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

private fun JSONObject.toSeasonalEventDto() = SeasonalEventDto(
    code = optString("code"),
    nameRu = optString("name_ru"),
    nameBa = optString("name_ba"),
    noteRu = optString("note_ru"),
    noteBa = optString("note_ba"),
    emoji = optString("emoji"),
    category = optString("category"),
    anchor = optString("anchor"),
    startsAt = optString("starts_at"),
    endsAt = optString("ends_at"),
    active = optBoolean("active", false),
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
    val quiet: Boolean = false,
    val noMinors: Boolean = false,   // водитель не берёт младше 18 без взрослого
    val waypoints: List<String> = emptyList(),
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

/** F15: сезонное событие для баннера на карте. Даты считает сервер (авто-обновление по годам).
 *  active=true — праздник уже идёт; иначе «скоро». anchor — главный день (ISO). */
data class SeasonalEventDto(
    val code: String,
    val nameRu: String,
    val nameBa: String,
    val noteRu: String = "",
    val noteBa: String = "",
    val emoji: String = "",
    val category: String = "",
    val anchor: String = "",
    val startsAt: String = "",
    val endsAt: String = "",
    val active: Boolean = false,
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
/** arrivalVerified — сервер сам сверил «подъезжаю» с GPS водителя: машина правда рядом. */
data class TripStateDto(
    val role: String,
    val status: String,
    val driverPhase: String,
    val arrivalVerified: Boolean = false,
    // Ехал(а) не один(на), а теперь остался(ась) в машине один на один с водителем. Клиент
    // ТИХО (без пуша и звука) предлагает поделиться поездкой с близким. Водителю не видно.
    val aloneWithDriver: Boolean = false,
)

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
    // Подтвердил ли модератор пол по фото прав. Пол сам по себе НИЧЕГО не включает:
    // публичный бейдж «женщина за рулём» и женские заказы даёт только подтверждение.
    val genderVerified: Boolean = false,
    val autocheckResult: String = "",   // "" / pass / needs_human / reject / error
    val autocheckData: String = "",      // JSON: распознанные поля + коды причин
    val tipsSbp: String = "",            // СБП водителя для чаевых; "" = не принимает
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
    // Сколько звёзд водитель уже поставил по этой брони: 0 = ещё не оценивал.
    // Без этого после перезагрузки экрана звёзды снова были пустые, и человек оценивал повторно.
    val myStars: Int = 0,
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
    // Госномер и цвет — чтобы сверить машину у подъезда. Пусто до подтверждения брони.
    // Дефолты держат старый сервер: клиент новее бэкенда не падает, просто не показывает.
    val driverPlate: String = "",
    val driverCarColor: String = "",
    // Несовершеннолетний пассажир: пометку видят оба, контакты взрослого — только водитель.
    val minorPassenger: Boolean = false,
    val minorGuardianName: String = "",
    val minorGuardianPhone: String = "",
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
    val autocheckResult: String = "", val autocheckScore: Double = 0.0, val autocheckData: String = "",
    // Что водитель ЗАЯВИЛ о поле ("" / female / male) и подтвердил ли это модератор. Бейдж
    // «женщина за рулём» и женские заказы включает только подтверждение — не самодекларация.
    val genderClaimed: String = "", val genderVerified: Boolean = false)
/** Помеченный текст для админа. Самого текста тут НЕТ — только ссылка (place/refId):
 *  текст лежит в своей записи, админ открывает её и видит в контексте (приватность §8). */
data class TextFlagDto(
    val id: Int, val userId: Int, val userName: String, val userPhone: String,
    val kind: String,            // warn (фишинг) / contact (телефон, увод) / abuse (мат)
    val placeLabel: String,      // «Отзыв», «Имя профиля» — уже по-человечески, с сервера
    val refId: Int?,             // id записи
    val createdAt: String,
    val userFlagsTotal: Int,     // сколько всего у этого человека: разовое ≠ система
)

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
data class RequestFeedDto(val id: Int, val passengerName: String, val from: String, val to: String, val seats: Int, val comment: String, val responded: Boolean, val passengerAvatar: String = "", val prefs: List<String> = emptyList(), val myResponseId: Int? = null,
    // На сколько км заявка уводит с собственного маршрута. null = у водителя нет активных
    // поездок или где-то нет координат — тогда числа не показываем, а не выдумываем.
    val detourKm: Int? = null)
data class ResponseDto(
    val id: Int, val driverId: Int, val driverName: String, val driverRating: Double?,
    val price: Int,                    // первая цена водителя (историческая)
    val comment: String, val status: String, val driverAvatar: String = "",
    // --- Торг о цене (второй круг). Раньше отклик был «бери или уходи». ---
    val currentPrice: Int = 0,         // цена, которая сейчас НА СТОЛЕ
    val lastOfferBy: String = "driver",// чей ход был последним: driver | passenger
    val bargainRounds: Int = 0,        // сколько встречных сделано
    val canCounter: Boolean = false,   // я могу предложить свою цену
    val canAccept: Boolean = false,    // я могу принять то, что на столе
    val bargainHistory: String = "",   // «d:500,p:400,d:450»
) {
    /** Что показывать как цену: пока торга не было — первое предложение водителя. */
    val onTable: Int get() = if (currentPrice > 0) currentPrice else price
    val haggled: Boolean get() = bargainRounds > 0
}

data class ContactDto(
    val id: Int,
    val name: String,
    val relation: String,
    val phone: String,
    val notifyByDefault: Boolean,
)

/** Активный шаринг поездки близкому (B7c): id для отзыва, к какому контакту, готовая live-ссылка. */
data class TripShareDto(
    val id: Int,
    val contactId: Int,
    val link: String? = null,
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
/** Зона спроса для водителя: где сейчас чаще ищут попутку. Анонимно — только агрегат, без личности. */
data class DemandZoneDto(val lat: Double, val lng: Double, val weight: Double, val requests: Int,
                         val distKm: Double? = null)
/** Ответ /instant/demand: список зон спроса + метка времени обновления. */
data class InstantDemandDto(val zones: List<DemandZoneDto>, val updatedAt: String)
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

/** Одно сообщение в обращении в поддержку. sender: user | admin. */
data class SupportMessageDto(
    val id: Int,
    val sender: String,
    val body: String,
    val createdAt: String,
)

/** Тред обращения в поддержку (список сообщений). status: open | closed. */
data class SupportTicketDto(
    val id: Int,
    val subject: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<SupportMessageDto>,
)

/** Строка списка «Мои обращения»: последнее сообщение + метка непрочитанного. */
data class SupportTicketRowDto(
    val id: Int,
    val subject: String,
    val status: String,          // open | closed
    val lastMessage: String,
    val lastSender: String,      // user | admin
    val unread: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/** Список моих обращений + счётчик непрочитанного (бейдж на входе «Поддержка»). */
data class SupportListDto(val unread: Int, val items: List<SupportTicketRowDto>)

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
    /** Сколько из долга — «оплатить сразу после поездки» (дальний межгород), копейки.
     *  0 = обычный недельный цикл. Поля аддитивные: старый сервер их не шлёт → нули. */
    val payNowKop: Int = 0,
    /** До какого момента ждём этот срочный перевод (UTC ISO). null — срочного долга нет. */
    val payNowDueAt: String? = null,
)
/** Долг водителя в админ-очереди подтверждения (сгруппирован по водителю). */
data class AdminDebtDto(
    val debtId: Int, val driverId: Int, val driverName: String, val driverPhone: String,
    /** amount — рубли (совместимость), amountKop — точная сумма, её и показываем админу. */
    val amount: Int, val amountKop: Int, val weeks: List<String>,
)
/** Баланс кошелька водителя (GET /wallet/balance). rub = kop // 100 (считает сервер). */
data class WalletBalanceDto(val balanceKop: Int, val balanceRub: Int)
/** Запись истории кошелька (GET /wallet/ledger). amountKop: приход > 0, списание/комиссия < 0.
 *  kind: earn (начисление за поездку) / fee (комиссия сервиса) / payout … note — человекочитаемо. */
data class WalletLedgerEntryDto(
    val id: Int,
    val kind: String,
    val amountKop: Int,
    val orderId: Int?,
    val bookingId: Int?,
    val note: String,
    val createdAt: String,
)
/** Статус выплат на карту (GET /wallet/payout/status). enabled=false → честная заглушка «скоро».
 *  minKop/maxKop — границы одной выплаты (источник истины — сервер, клиент не хардкодит). */
data class PayoutStatusDto(
    val enabled: Boolean,
    val balanceKop: Int,
    val hasRequisite: Boolean,
    val cardLast4: String,
    val minKop: Int,
    val maxKop: Int,
)
/** Результат вывода (POST /wallet/payout). status: ok | already (идемпотентный повтор той же попытки). */
data class PayoutResultDto(val status: String, val entryId: Int, val amountKop: Int, val balanceKop: Int)
/** Результат оплаты поездки (POST /bookings/{id}/pay, POST /instant/orders/{id}/pay).
 *  status: paid (наличные) | already_paid | succeeded (mock/dev) | pending (ждём подтверждения ЮKassa).
 *  pending + confirmationUrl → открыть браузер, потом поллить getPaymentStatus(paymentId). */
data class PayTripResultDto(
    val status: String,
    val method: String,
    val paymentId: Int?,
    val confirmationUrl: String?,
) {
    val isPaid: Boolean get() = status == "paid" || status == "already_paid" || status == "succeeded"
}
/** День в разбивке заработка (GET /driver/earnings). sum — в ₽ (не копейки). */
data class DriverEarningsDayDto(val date: String, val sum: Int, val trips: Int)
/** Заработок водителя за период (GET /driver/earnings?period=week|month|all).
 *  total/sum — в РУБЛЯХ (₽, целые): сумма цен завершённых такси-заказов. */
data class DriverEarningsDto(
    val period: String,
    val total: Int,
    val trips: Int,
    val byDay: List<DriverEarningsDayDto>,
)
/** Сохранённый адрес (Дом/Работа/свой). kind: home|work|custom. */
data class SavedPlaceDto(
    val id: Int, val kind: String, val label: String,
    val address: String, val lat: Double, val lng: Double, val createdAt: String,
    /** Когда адресом пользовались в последний раз — по нему сервер строит порядок списка. */
    val usedAt: String = "",
)
/** Недавний адрес назначения (GET /places/recent, свежие сверху). */
data class RecentPlaceDto(
    val id: Int, val address: String, val lat: Double, val lng: Double, val usedAt: String,
)
/** Квитанция завершённой поездки (GET /trips/{booking_id}/receipt).
 *  amount — ₽; pay_method: cash|sbp|negotiate; role — «passenger»|«driver» (чья витрина). */
data class TripReceiptDto(
    val bookingId: Int, val rideId: Int, val role: String,
    val fromCity: String, val toCity: String, val departAt: String,
    val seats: Int, val amount: Int, val payMethod: String, val paid: Boolean,
    val driverName: String, val driverVerified: Boolean,
)

/** Ответ на «Подожду машину»: до какого времени ищем и обновлённый заказ. */
data class InstantWaitDto(val waitUntil: String, val waitMinutes: Int, val order: InstantOrderDto?)

/** Квитанция за такси-поездку (GET /instant/orders/{id}/receipt). Телефонов в чеке нет.
 *  amount — ₽ (итог поездки), waitingFeeKop — платное ожидание, копейки. */
/** Жалоба на цену (админ). Координат тут нет: чтобы разобраться в ЦЕНЕ, знать,
 *  откуда человек ехал, не нужно. */
data class PriceComplaintDto(
    val id: Int,
    val orderId: Int?,
    val price: Int,
    val reason: String,
    val comment: String,
    val breakdown: String,
    val createdAt: String,
)

data class InstantReceiptDto(
    val orderId: Int, val role: String,
    val fromText: String, val toText: String, val doneAt: String,
    /** amount — рубли (для старых экранов), amountKop — точная сумма: её и показываем в чеке. */
    val distanceKm: Double, val amount: Int, val amountKop: Int, val waitingFeeKop: Int,
    val paymentMethod: String, val paid: Boolean,
    val driverName: String, val driverVerified: Boolean,
    // --- Из чего сложилась сумма (2026-08-23). Раньше в чеке была одна цифра, и на вопрос
    // «куда делись деньги» ответить было нечем. Старый сервер полей не шлёт → нули, и чек
    // выглядит как прежде.
    val ridePrice: Int = 0,
    val rideBasePrice: Int = 0,
    val surgeRub: Int = 0,
    val pickupFeeKop: Int = 0,
    val pickupKm: Double = 0.0,
    val pickupEnroute: Boolean = false,
    val optionsFeeKop: Int = 0,
    val options: List<String> = emptyList(),
    val weatherFeeKop: Int = 0,
    val weatherKind: String = "",
    // --- Только для водителя: он реально платит комиссию, поэтому видит её целиком.
    // Пассажиру их не шлют вообще (в Модели А он платит водителю напрямую).
    val driverFeePercent: Double = 0.0,
    val driverFeeKop: Int = 0,
    val driverGrossKop: Int = 0,
    val driverNetKop: Int = 0,
    val commissionFreeKop: Int = 0,
)

/** Квитанция за доставку (GET /parcels/{id}/receipt). Телефонов и адресов в чеке нет.
 *  Доставка и товар («купи и привези») разделены: это разные карманы и разные основания.
 *  amount — ₽ (итог: доставка + товар), goodsKop — что курьер реально потратил в магазине. */
data class ParcelReceiptDto(
    val parcelId: Int, val role: String, val status: String,
    val fromCity: String, val toCity: String, val deliveryType: String,
    val deliveredAt: String, val returnedAt: String,
    val deliveryPriceKop: Int, val goodsKop: Int, val totalKop: Int, val amount: Int,
    val commissionKop: Int, val commissionPaid: Boolean,
    val cancelFeeKop: Int, val settled: Boolean, val declaredValueKop: Int,
    val courierName: String, val courierVerified: Boolean,
    // Сколько отправитель возвращает курьеру за товар, купленный курьером на свои (волна 185).
    // Старый сервер поля не присылает → 0, и чек выглядит как прежде.
    val owedToCourierKop: Int = 0,
)

/** «Сказать рәхмәт»: имя водителя, сказали ли уже, и (если включены денежные чаевые
 *  и водитель оставил телефон) номер СБП — перевод идёт мимо платформы, «на доверии». */
data class TipInfoDto(val driverName: String, val alreadyThanked: Boolean, val sbpPhone: String?)

/** Одна завершённая такси-поездка водителя с расшифровкой денег.
 *  feeStatus: pending | paid | declared | void | none (none = долга по заказу нет). */
data class DriverTaxiRideDto(
    val orderId: Int, val doneAt: String, val from: String, val to: String,
    val priceRub: Int, val feeKop: Int, val netKop: Int,
    val paid: Boolean, val paymentMethod: String, val feeStatus: String,
    // Разбор подтвердил: по этой поездке пассажир не заплатил (волна 190). Старый сервер
    // поля не присылает → false, и строка выглядит как прежде.
    val unpaidConfirmed: Boolean = false,
)

/** Список поездок водителя + итоги (GET /driver/taxi-rides). */
data class DriverTaxiRidesDto(
    val rides: List<DriverTaxiRideDto>,
    val totalPriceRub: Int, val totalFeeKop: Int, val totalNetKop: Int,
)

/** Один бейдж профиля: earned=false → показываем прогресс value/goal, а не прячем. */
data class AchievementDto(
    val code: String, val ru: String, val ba: String,
    val goal: Int, val value: Int, val earned: Boolean,
)

/** Бейджи профиля (G8). На распределение заказов не влияют — это про тепло, а не про рейтинг. */
data class AchievementsDto(
    val trips: Int,
    val parcelsHelped: Int,
    val daysWithYuldash: Int,
    val earnedCount: Int,
    val items: List<AchievementDto>,
)

/** Трекинг-ссылка посылки: url для получателя + ушла ли ему SMS. */
data class ParcelTrackLinkDto(val url: String, val smsSent: Boolean)

/** Текстовый отзыв о поездке, ждущий модерации. Пока не одобрен — в профиле его нет. */
data class PendingRatingDto(
    val id: Int,
    val author: String,       // кто оставил (админу; в публичном профиле — тоже без телефона)
    val rateeId: Int,         // кому адресован
    // Имя того, О КОМ отзыв. Без него модератор читал текст вслепую: видно «вёз молча»,
    // а чей это профиль и кому прилетит публикация — нет. Телефон не отдаём, имени хватает.
    val ratee: String = "",
    val stars: Int,
    val text: String,
    val createdAt: String,
)

/** Спор «Справедливости» (двусторонний разбор). Поля `reporterId/Name/Phone` и
 *  `respondentId/Name/Phone` заполнены ТОЛЬКО в админ-выдаче: участникам телефон второй
 *  стороны не отдаём — это приватность, не забывчивость.
 *  (Здесь нельзя писать «reporter» со звёздочкой перед косой чертой: пара символов закрывает
 *  KDoc раньше времени, и весь остаток файла компилятор читает как код.)
 *
 *  status: open | awaiting_response | under_review | appealed | resolved | closed
 *  myRole: reporter (я подал) | respondent (обвинили меня) | admin
 *  resolution: «» пока не решено; dismissed | warning | strike | suspend | ban | mutual_resolved */
data class IncidentDto(
    val id: Int,
    val bookingId: Int?,
    val type: String,
    val severe: Boolean,          // тяжёлый тип — сразу к человеку, без ожидания объяснения
    val status: String,
    val reporterRole: String,     // кем был заявитель в поездке: driver | passenger
    val description: String,
    val respondentStatement: String,
    val respondedAt: String?,
    val resolution: String,
    val fault: String,            // none | reporter | respondent | both | unclear
    val resolutionNote: String,   // человеческое объяснение решения — видят ОБЕ стороны
    val compensationKop: Int,
    val appealText: String,
    val appealStatus: String,     // «» | requested | accepted | rejected
    val createdAt: String,
    val updatedAt: String,
    val resolvedAt: String?,
    val myRole: String,
    val otherName: String,        // имя второй стороны (без телефона)
    val evidenceUrls: List<String>,            // фото заявителя (приватные /secure/evidence)
    val respondentEvidenceUrls: List<String>,  // фото обвинённого
    val route: String?,           // маршрут поездки/доставки/такси-заказа — контекст спора
    // Только админ-выдача:
    val reporterId: Int = 0,
    val reporterName: String = "",
    val reporterPhone: String = "",
    val respondentId: Int = 0,
    val respondentName: String = "",
    val respondentPhone: String = "",
) {
    /** Спор ещё живой — по нему можно что-то сделать. */
    val isActive: Boolean get() = status == "open" || status == "awaiting_response" ||
        status == "under_review" || status == "appealed"
    /** Ждём МОЕГО объяснения (меня обвинили и я ещё не ответил). */
    val needsMyStatement: Boolean get() = myRole == "respondent" && respondentStatement.isBlank() &&
        status != "resolved" && status != "closed"
    /** Решение вынесено — можно обжаловать (один раз). */
    val canAppeal: Boolean get() = status == "resolved" && appealStatus.isBlank()
    /** «Решили миром» — только заявитель и только до вердикта. */
    val canWithdraw: Boolean get() = myRole == "reporter" &&
        (status == "open" || status == "awaiting_response" || status == "under_review")
}

/** Моё положение в «Справедливости»: чем выше Надёжность, тем спокойнее с тобой ехать. */
data class StandingDto(
    val standing: String,         // good | limited | suspended
    val strikes: Int,
    val warnings: Int,
    val reliability: Int,         // 0..100 — доля поездок без срывов
    val suspendedUntil: String?,
    val suspendReason: String,
    val ratingShield: Boolean,    // «щит рейтинга»: спорная оценка не входит в средний
    val activeIncidents: Int,
    val canAct: Boolean,          // false = пауза: новые заказы/брони временно недоступны
)

/** Пороги лестницы наказаний — берём с сервера, чтобы приложение не врало о правилах. */
data class SafetyPolicyDto(
    val strikesToLimit: Int,
    val strikesToSuspend: Int,
    val suspend1Days: Int,
    val suspend2Days: Int,
    val suspend3Days: Int,
    val strikeDecayDays: Int,
)

/** Сигнал SOS в ленте админа. userPhone — чтобы реально позвонить человеку в беде.
 *  category: medical | breakdown | other. status: open | handled. */
data class AdminSosDto(
    val id: Int, val status: String, val category: String, val note: String,
    val createdAt: String, val handledAt: String?, val handledNote: String,
    val userId: Int, val userName: String, val userPhone: String,
    val route: String, val orderId: Int?, val bookingId: Int?,
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
    /** ВСЕ места показа, купленные партнёром (`route`, `ridesList`, `nearby`, `tripDetails`,
     *  `profile`, `help`). Раньше читалось только `placement` — первое из списка, и то лишь
     *  ради совместимости со старым клиентом; в итоге объявление показывалось не там, за что
     *  партнёр заплатил (аудит 2026-08-07). Пусто → показывать негде. */
    val placements: List<String> = emptyList(),
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

// ═══════════ M1: Купоны «Скидки по пути» ═══════════

/** Заведение-партнёр в карточке купона (публичные данные, телефон — для навигации/связи). */
data class CouponPartnerDto(
    val id: Int, val name: String, val category: String, val city: String,
    val address: String = "", val lat: Double? = null, val lng: Double? = null, val phone: String = "",
)

/** Купон в витрине «Скидки по пути». remaining!=null → показываем «осталось N». */
data class CouponDto(
    val id: Int, val partner: CouponPartnerDto,
    val title: String, val description: String, val discountText: String,
    val city: String, val routeHint: List<String>,
    val validFrom: String?, val validUntil: String?,
    val limitTotal: Int, val limitPerUser: Int, val redeemedCount: Int,
    val remaining: Int?, val premium: Boolean, val status: String,
) {
    companion object {
        fun empty() = CouponDto(0, CouponPartnerDto(0, "", "", ""), "", "", "", "", emptyList(), null, null, 0, 0, 0, null, false, "active")
    }
}

/** Активированный купон = код для показа в заведении + статус. */
data class ActivatedCouponDto(val code: String, val status: String, val reservedAt: String, val coupon: CouponDto)

/** Мой купон (все статусы: reserved/redeemed/canceled/expired). */
data class MyCouponDto(
    val code: String, val status: String, val reservedAt: String,
    val redeemedAt: String?, val coupon: CouponDto,
)

/** Тариф подписки партнёра. */
data class PartnerPlanDto(
    val code: String, val title: String, val titleBa: String,
    val amountKop: Int, val periodDays: Int, val premium: Boolean,
)

/** Мой бизнес (кабинет партнёра). status: pending|active|paused|rejected|archived. */
data class PartnerDto(
    val id: Int, val name: String, val category: String, val city: String,
    val address: String, val phone: String, val description: String,
    val lat: Double?, val lng: Double?, val status: String,
    val rejectReason: String, val subscriptionPlan: String,
    val subscriptionUntil: String?, val subscriptionActive: Boolean,
    val hasPremium: Boolean, val createdAt: String,
)

/** Выписка: сколько купонов погашено и к оплате (доход платформы за приведённых клиентов). */
data class StatementDto(val redeemedTotal: Int, val feePerRedemptionKop: Int, val amountKop: Int)

/** Ответ /partner/me: бизнес (null если ещё нет) + выписка. */
data class PartnerMeDto(val partner: PartnerDto?, val statement: StatementDto?)

/** Реквизиты оплаты подписки «на доверии» (СБП). */
data class PartnerSubscribeDto(val paymentId: Int, val amountKop: Int, val plan: String, val status: String)

/** Купон в кабинете партнёра (свой, со счётчиками активаций/погашений). */
data class PartnerCouponDto(
    val id: Int, val partnerId: Int, val title: String, val description: String, val discountText: String,
    val city: String, val routeHint: List<String>, val validFrom: String?, val validUntil: String?,
    val limitTotal: Int, val limitPerUser: Int, val redeemedCount: Int, val activations: Int,
    val premium: Boolean, val status: String, val createdAt: String,
    // Состояние ПРОВЕРКИ, отдельно от status («чего хочет партнёр»). Без него человек видит
    // «Активен» и не понимает, почему купона нет в витрине: held — задержан автопроверкой,
    // pending — ждёт админа (но виден), blocked — снят админом (причина в reviewNote).
    val review: String = "approved", val reviewNote: String = "", val reportsCount: Int = 0,
)

/** Статистика купона партнёра. */
data class CouponStatDto(
    val couponId: Int, val title: String, val status: String,
    val activations: Int, val redeemed: Int, val feePerRedemptionKop: Int, val amountKop: Int,
)

/** Результат погашения кода клиента (партнёр видит, какую скидку дать и кому). */
data class RedeemResultDto(val couponTitle: String, val discountText: String, val customerName: String)

/** Бизнес-партнёр в админ-модерации. */
data class AdminPartnerDto(
    val id: Int, val ownerId: Int, val name: String, val category: String, val city: String,
    val address: String, val phone: String, val description: String, val status: String,
    val rejectReason: String, val subscriptionPlan: String, val subscriptionUntil: String?,
    val subscriptionActive: Boolean, val createdAt: String, val reviewedAt: String?,
)

/**
 * Купон в очереди модерации.
 *
 * `review` — что решила ПРОВЕРКА, отдельно от `status` («чего хочет партнёр»):
 * held — задержан автопроверкой, людям не виден; pending — виден, но админ ещё не смотрел;
 * approved — проверен; blocked — снят админом. `visible` сервер считает сам.
 */
data class AdminCouponDto(
    val id: Int, val partnerId: Int, val partnerName: String, val city: String,
    val title: String, val description: String, val discountText: String,
    val status: String, val review: String, val reviewFlag: String, val reviewNote: String,
    val reportsCount: Int, val visible: Boolean, val createdAt: String,
)

/** Очередь модерации витрины: бизнесы без решения + купоны без решения. */
data class ModerationQueueDto(
    val partners: List<AdminPartnerDto>,
    val coupons: List<AdminCouponDto>,
    val total: Int,
)

// ═══════════ M2: Промокоды и кампании ═══════════

/**
 * Результат применения промокода.
 * kind: "welcome" (приветствие) | "boost" (N бесплатных поднятий) | "taxi_ride" (скидка на такси).
 * discountKop > 0 — человеку выдана скидка на ОДНУ поездку в такси; она сработает сама при заказе.
 */
data class PromoApplyResultDto(
    val ok: Boolean, val kind: String, val perkValue: Int,
    val messageRu: String, val messageBa: String,
    val discountKop: Int = 0,
)

/** Мой активированный промокод (один на аккаунт).
 *  discountAvailable = скидка на такси ещё цела и ждёт следующего заказа (потратил → false). */
data class MyPromoDto(
    val code: String, val title: String, val kind: String, val perkValue: Int,
    val redeemedAt: String?,
    val discountKop: Int = 0,
    val discountAvailable: Boolean = false,
    // На каком заказе скидка сработала. null при discountAvailable=false → кампанию выключили
    // или вышел срок: человек должен понимать, потратил он скидку или её просто больше нет.
    val discountUsedOrderId: Int? = null,
)

/** Статистика кода: воронка applied (ввели) → active (стали активными). */
data class PromoStatDto(
    val code: String, val title: String, val campaign: String,
    val applied: Int, val active: Int,
)

/** Промокод/кампания в админ-панели. active = «живые» приведённые; activeFlag = вкл/выкл кампании. */
data class AdminPromoDto(
    val id: Int, val code: String, val title: String, val description: String,
    val ownerId: Int?, val campaign: String, val kind: String, val perkValue: Int,
    val limitTotal: Int, val limitPerUser: Int, val redeemedCount: Int,
    val applied: Int, val active: Int,
    val validFrom: String?, val validUntil: String?, val activeFlag: Boolean, val createdAt: String,
)

// ═══════════ M3: Доставка посылок ═══════════

/** Курьер, взявший посылку (виден отправителю после accept).
 *  rating=null — у курьера пока нет оценок («новый курьер»); ratingCount — сколько оценок. */
data class ParcelCourierDto(val id: Int, val name: String, val rating: Double?, val ratingCount: Int, val phone: String)

/** Потолок «где забрать / куда привезти»: столько же принимает сервер, и столько же режет форма. */
internal const val PARCEL_ADDRESS_MAX_LEN = 200

/** Посылка. Форма зависит от роли: у отправителя есть confirmCode/receiverPhone/courier;
 *  в списке «доступные» (курьер) телефон и код скрыты (пустые).
 *  status: created/accepted/in_transit/delivered/canceled/returning/returned. */
data class ParcelDto(
    val id: Int,
    val senderId: Int,
    val courierId: Int?,
    val fromCity: String, val toCity: String,
    val fromLat: Double?, val fromLng: Double?, val toLat: Double?, val toLng: Double?,
    val size: String,
    val description: String,
    val receiverName: String,
    val receiverPhone: String,   // "" если скрыт
    val senderName: String = "", // после accept: контакт точки забора/возврата
    val senderPhone: String = "",// после accept; до него сервер не отдаёт
    val feeKop: Int,
    val status: String,
    val confirmCode: String,     // "" если скрыт
    val createdAt: String,
    val acceptedAt: String?,
    val deliveredAt: String?,
    val courier: ParcelCourierDto?,
    // C1: курьер Юлдаша (профессиональная доставка). Для обычной попутки deliveryType="poputka".
    // Что за груз (разбор №2). Видны ДО принятия, в том числе в открытом списке: именно по ним
    // курьер решает «берусь или нет». 0 / "" / false = отправитель не указал.
    val weightKg: Double = 0.0,
    val cargoType: String = "",             // documents|medicine|food|clothes|tech|other
    val fragile: Boolean = false,
    val deliveryType: String = "poputka",   // poputka | courier | buy_bring
    val urgency: String = "",                // bypath | now (для courier/buy_bring)
    val declaredValueKop: Int = 0,           // объявленная ценность (courier)
    val codAmountKop: Int = 0,               // сумма выкупа товара (buy_bring)
    val commissionKop: Int = 0,              // наш сбор (уже входит в priceKop)
    val priceKop: Int = 0,                   // итоговая цена доставки (courier/buy_bring)
    val settlement: ParcelSettlementDto? = null,   // C2: расчёт «купи и привези» (null для остальных типов)
    // Возврат: сервер отдавал эти поля, но старый клиент их терял и не мог объяснить состояние.
    val returnReason: String = "",
    val returnedAt: String? = null,
    val deliveryAttempts: Int = 0,
    val cancelFeeKop: Int = 0,
    // Сколько будет стоить отмена, если нажать ПРЯМО СЕЙЧАС. Нужно, чтобы человек видел
    // сумму ДО решения: раньше диалог честно предупреждал «будет компенсация», но саму
    // цифру показывал уже после отмены — человек соглашался на деньги вслепую.
    val cancelFeePreviewKop: Int = 0,
    // Из ЧЕГО сложится эта сумма. Одно число человек читает как «нас обобрали»; «100 ₽ штраф
    // + 300 ₽ дорога курьера + 70 ₽ ожидание» — то же самое, но с ним не спорят. Нули =
    // старый сервер, тогда показываем только итог.
    val cancelFineKop: Int = 0,
    val cancelPickupKop: Int = 0,
    val cancelWaitingKop: Int = 0,
    // Возврат «получателя не было»: сколько отправитель вернёт курьеру за дорогу и ожидание.
    // Ноль, пока курьер не отметил ни одной попытки вручения — за слова мы не платим.
    val returnFeeKop: Int = 0,
    val returnFeePreviewKop: Int = 0,
    // Из чего сложится возврат: свой маршрут, повторные заезды по просьбе отправителя и
    // сколько срезал потолок «не дороже самой доставки». Ноль = старый сервер.
    val returnRouteKop: Int = 0,
    val returnRedeliverKop: Int = 0,
    val returnCappedKop: Int = 0,
    // Во сколько обойдётся СЛЕДУЮЩИЙ заезд, если попросить его сейчас. Считает сервер:
    // цена зависит от зоны, коэффициента и потолка — телефон, посчитавший её сам, однажды
    // показал бы не то число, которое спишется.
    val nextRedeliverKop: Int = 0,
    // Повторный заезд «получатель уже дома». Сколько уже попросили, сколько всего можно
    // и открыта ли кнопка сейчас — считает сервер: у телефона нет ни попыток, ни предела.
    val redeliverRequests: Int = 0,
    val redeliverMax: Int = 0,
    val canRequestRedelivery: Boolean = false,
    // Две границы ответственности: «взял целой» и «отдал целой». Сервер хранил оба снимка,
    // но клиент не показывал ни одного — в споре о повреждении смотреть было не на что.
    val pickupPhotoUrl: String = "",
    val deliveryPhotoUrl: String = "",
    // «Где именно забрать и куда привезти» — свободный ориентир от отправителя («у мечети,
    // синие ворота»). Приватность: в открытой ленте свободных заказов сервер их НЕ отдаёт, они
    // приходят только принявшему курьеру и самому отправителю → "" = «не пришло», блок не рисуем.
    // Значения по умолчанию обязательны: старый сервер этих ключей не пришлёт.
    val fromAddress: String = "",
    val toAddress: String = "",
    // Срок доставки — не прогноз, а договорённость: отправитель говорит, к какому дню нужно,
    // курьер видит это ДО того, как взяться. Формат сервера — 2026-08-05; null = «не срочно».
    // overdue считает сервер («срок вышел, а доставка не завершена») — клиент его не выводит сам.
    val deliverBy: String? = null,
    val overdue: Boolean = false,
)

/** C2: расчёт «купи и привези» — сколько получатель вернёт курьеру (товар + доставка).
 *  Приходит только для deliveryType="buy_bring", иначе null. goodsActualKop=0 — курьер ещё не указал стоимость покупки. */
data class ParcelSettlementDto(
    val goodsActualKop: Int,   // фактическая стоимость купленного товара (0 — ещё не указана)
    val deliveryKop: Int,      // стоимость доставки
    val totalDueKop: Int,      // всего к оплате получателем (товар + доставка)
    val settled: Boolean,      // расчёт закрыт (оплата получена)
)

// ═══════════ C1: Курьер Юлдаша ═══════════

/** Заявка «Стать курьером». status: pending | approved | rejected. */
/** День заработка курьера (чистыми = цена доставки минус комиссия платформы). */
data class CourierEarningsDayDto(val date: String, val netKop: Int, val deliveries: Int)

/** Заработок курьера за период. Всё в КОПЕЙКАХ (у водителя аналогичный экран — в рублях). */
data class CourierEarningsDto(
    val period: String,
    val netKop: Int,
    val commissionKop: Int,
    val deliveries: Int,
    val byDay: List<CourierEarningsDayDto>,
)

data class CourierApplicationDto(
    val id: Int,
    val transport: String,        // car | cargo
    val status: String,           // pending | approved | rejected
    val selfieUrl: String,
    val fullName: String = "",    // ФИО как в документе (сверка с селфи)
    val carPlate: String = "",    // госномер — по нему узнают машину
    val rulesAccepted: Boolean = false,
    val invitedBy: String?,       // «кто пригласил» (реферал, доверие между своими)
    val rejectReason: String,     // причина отклонения (видит курьер)
    val createdAt: String,
    val reviewedAt: String?,
    // Только в админ-списке (в личной заявке пустые):
    val userId: Int = 0,
    val name: String = "",
    val phone: String = "",
)

/** Разбивка цены доставки курьером — показываем честно (из чего сложилась цена). */
data class CourierEstimateBreakdown(
    val baseKop: Int, val distanceKop: Int, val sizeKop: Int, val urgencyKop: Int, val commissionPercent: Double,
    val commissionMinKop: Int = 0,            // пол комиссии за доставку (25 ₽)
    val commissionEstimated: Boolean = false, // комиссия до вручения — оценка (финал после вручения)
    // «Если получателя не будет» — оценка возврата, показанная ДО заказа. Конституционный суд
    // (декабрь 2022) признал недопустимым брать плату за возврат с человека, которого о ней
    // заранее не предупредили. Без этой строки на экране заказа наша компенсация висит в воздухе.
    val returnFeeEstimateKop: Int = 0,
)

/** Оценка стоимости доставки курьером (сервер считает по своей формуле). */
data class CourierEstimateDto(
    val priceKop: Int, val commissionKop: Int, val distanceKm: Double, val breakdown: CourierEstimateBreakdown,
)

/** Профиль курьера (режим работы). */
data class CourierProfileDto(
    val id: Int, val online: Boolean, val carClass: String, val zone: String,
    val workCity: String?, val workDirectionId: Int?, val updatedAt: String,
    // Зона по-новому: район как база + два согласия (см. InstantZoneDto — правила общие).
    val workDistrict: String? = null,
    val workIntercity: Boolean = false,
    val workRegions: Boolean = false,
)

/** C3: выписка курьера по нашей комиссии.
 *  earned — всего наша комиссия за доставки; owed — к оплате сейчас; paid — уже оплачено.
 *  commissionKop — легаси-поле (== earned), оставлено для совместимости. */
data class CourierStatementDto(
    val deliveredCount: Int,
    val commissionEarnedKop: Int,
    val commissionOwedKop: Int,
    val commissionPaidKop: Int,
    val commissionKop: Int,
    val currentFeePercent: Double = 0.0,     // текущая ставка комиссии курьера, %
    val feeTier: String = "",                // ступень: promo | tier1 | tier2 | tier3
    val commissionMinKop: Int = 0,           // пол комиссии за доставку (25 ₽)
)

/** C3: рейтинг курьера. avg=null — пока нет оценок. */
data class CourierRatingDto(val avg: Double?, val count: Int)

/** C3: ответ /parcels/{id}/rate — новый рейтинг оценённого пользователя. */
data class RateResultDto(val rateeId: Int, val rating: Double, val count: Int)

/** C3: ответ /courier/pay-commission — заявка на оплату комиссии + реквизиты СБП. */
data class PayCommissionDto(
    val status: String, val paymentId: Int, val amountKop: Int, val amount: Int,
    val payeePhone: String, val payeeBank: String, val payeeName: String,
    val method: String = "sbp_manual",           // yookassa → оплата картой, иначе СБП «на доверии»
    val confirmationUrl: String? = null,          // ЮKassa redirect (открыть в браузере)
)

/** Ответ на оплату долга такси: yookassa (оплата картой) или sbp_manual (перевод «на доверии»). */
data class DebtPayResultDto(
    val method: String, val status: String, val paymentId: Int,
    val pendingKop: Int, val confirmationUrl: String?,
)

/** Ответ /courier/me: заявка + профиль (если одобрен) + выписка + рейтинг + пауза по качеству. */
data class CourierMeDto(
    val application: CourierApplicationDto?,
    val profile: CourierProfileDto?,
    val statement: CourierStatementDto,
    val rating: CourierRatingDto = CourierRatingDto(null, 0),
    val pausedUntil: String? = null,
)

/** Выписка по посылкам (админ). Три разных числа вместо одного «собрано», которое врало:
 *  сбор «по пути» никому не выставляется, а показывался как выручка (аудит 2026-07-26). */
data class ParcelStatementDto(
    val deliveredCount: Int,
    val collectedFeeKop: Int,          // деньги дошли: курьеры оплатили комиссию
    val owedCommissionKop: Int = 0,    // начислено курьерам, ещё не оплачено (долг)
    val unbilledFeeKop: Int = 0,       // сбор «по пути»: выставить некому — это не выручка
)

/** Ответ /admin/parcels: все посылки + выписка. */
data class ParcelAdminListDto(val parcels: List<ParcelDto>, val statement: ParcelStatementDto)
