package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Realtime live-позиция участника поездки по WebSocket (/ws/trip/{bookingId}/location).
 * Шлём свой GPS {type:loc,lat,lng,bearing,ts}; принимаем позицию ДРУГОГО участника
 * {type:loc,role,...}. Токен — первым сообщением {type:auth,token} (как чат, не в URL).
 * Сервер ретранслирует ТОЛЬКО в активной поездке; координаты НЕ хранит (приватность).
 * Авто-реконнект с backoff (как ChatSocket): обрыв сети/сон/туннель → сам переподключится.
 * Отказ в ключе («Invalid token» / «Token revoked») — не конец: ключ живёт 12 ч, а запросов, которые
 * обновили бы его по 401, в поездке с погасшим экраном может не быть. Обновляем сами тем же путём,
 * что запросы, и подключаемся только с новым ключом; обновление отказано (вход отозван) — конец.
 */
class LocationSocket(
    private val bookingId: Int,
    private val onPeer: (Peer) -> Unit,                 // позиция другого участника
    private val onConnected: (Boolean) -> Unit = {},
    private val socketFactory: WebSocket.Factory = client,
    private val scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
    private val onTerminated: () -> Unit = {},
    private val currentToken: () -> String? = { ApiClient.currentToken() },
    // Тот же mutex и intent, что у запросов после 401; поколение — сессии-владельца канала: сменился
    // аккаунт — обновление откажет, а не выдаст ключ другого человека. Явный отказ в продлении завершает вход так же.
    private val refreshToken: suspend (String, Long) -> Result<Unit> = { token, owner -> ApiClient.tryRefresh(token, owner) },
    // Чья это поездка: канал живёт в сессии, в которой его открыли (как ChatSocket.ownerSession). Служба точки
    // передаёт СВОЮ сессию-владельца (Д2): канал, созданный после выхода из аккаунта, не станет «чужим своим».
    private val ownerSession: Long = ApiClient.queueSessionGeneration(),
) {
    data class Peer(val role: String, val lat: Double, val lng: Double, val bearing: Double?, val ts: Long)

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var attempt = 0
    @Volatile private var softAttempt = 0        // мягкий ретрай «поездка не активна» — с потолком MAX_SOFT_ATTEMPTS
    @Volatile private var tokenAttempt = 0       // повторы после отказа в ключе — своя лестница 1…30 с
    @Volatile private var rejectedToken: String? = null   // ключ, который сервер отверг: с ним не подключаемся
    @Volatile private var authed = false         // кадр {type:auth} ушёл — только теперь можно слать точки
    @Volatile private var authedAt = 0L

    // Сеть вернулась → мгновенный реконнект (не ждём backoff-таймер). Держим как поле:
    // NetworkMonitor хранит слушателей через WeakReference, ссылку не даём собрать GC.
    private val netListener = NetworkMonitor.Listener {
        if (!closed) { attempt = 0; openSocket() }   // отвергнутый ключ отсекает сам openSocket
    }

    companion object {
        private const val MAX_DELAY_SEC = 30L
        private const val SOFT_RETRY_SEC = 15L   // ретрай «поездка ещё не активна»
        private const val KEY_SURVIVED_MS = 7_000L   // столько соединение живёт после входа — ключ принят
        private const val MAX_SOFT_ATTEMPTS = 40 // ~10 мин по 15с — потолок мягкого ретрая (не долбим сервер вечно, если поездка так и не стала активной)
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "loc-ws-reconnect").apply { isDaemon = true }
            }
        }
    }

    @Synchronized
    fun connect() {
        closed = false; attempt = 0; softAttempt = 0; tokenAttempt = 0; rejectedToken = null
        NetworkMonitor.subscribe(netListener); openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        // Вышли или сменили аккаунт — канал не наш: не подключаемся чужим ключом к этой брони.
        if (!ApiClient.isCurrentSession(ownerSession)) { terminate(); return }
        val token = currentToken() ?: run { terminate(); return }   // ключа нет — вход кончился, GPS не нужен
        // Отвергнутый ключ не шлём никаким путём (сеть вернулась, старый таймер) — ждём обновлённый.
        if (token == rejectedToken) return
        val connection = ++generation
        authed = false
        ws?.close(4999, "replaced")   // закрываем старый сокет перед новым (гонка reconnect↔connect → двойной GPS-канал); 4999 = терминал, без churn
        val url = "${ApiClient.wsBase()}/ws/trip/$bookingId/location"   // токен НЕ в URL — первым сообщением
        ws = socketFactory.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@LocationSocket) {
                        if (closed || generation != connection) return
                        if (!ApiClient.isCurrentSession(ownerSession)) { terminate(); return }
                        webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                        authed = true
                        authedAt = System.currentTimeMillis()
                        attempt = 0
                        onConnected(true)
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@LocationSocket) {
                        if (closed || generation != connection) return
                        runCatching {
                            val o = JSONObject(text)
                            if (o.optString("type") == "loc") {
                                softAttempt = 0
                                tokenAttempt = 0
                                onPeer(
                                    Peer(
                                        role = o.optString("role"),
                                        lat = o.optDouble("lat"),
                                        lng = o.optDouble("lng"),
                                        bearing = if (o.isNull("bearing")) null else o.optDouble("bearing"),
                                        ts = o.optLong("ts"),
                                    )
                                )
                            }
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Отвечаем на серверный graceful-close (деплой/рестарт) → onClosed гарантированно придёт
                    // и отработает реконнект. Без этого handshake не завершается до TCP-таймаута — стрим тихо умирает.
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(this@LocationSocket) {
                        if (closed || generation != connection) return
                        ws = null
                        authed = false
                        onConnected(false)
                        // Поездка ещё не активна (бронь pending/подтверждается) — сервер закрывает 1008 "Trip not active".
                        // Это НЕ терминал: бронь станет confirmed → подключимся. Мягкий ретрай раз в 15с (сервис
                        // живёт только во время поездки → не вечный цикл). Иначе сразу после брони стрим бы не запускался.
                        if (code == 1008 && reason.contains("not active", ignoreCase = true)) { softReconnect(); return }
                        // Ключ истёк или отозван — ждём обновлённый (см. awaitFreshToken), а не гасим точку до конца
                        // поездки: пассажир и близкие молча теряли машину (приёмка PR155).
                        if (code == 1008 && reason.contains("token", ignoreCase = true)) {
                            rejectedToken = token; awaitFreshToken(); return
                        }
                        // Forbidden / Trip ended / Access ended / прочие 1008|4xxx — настоящий терминал, не долбимся.
                        if (code != 1008 && code !in 4000..4999) scheduleReconnect() else terminate()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@LocationSocket) {
                        if (closed || generation != connection) return
                        ws = null
                        authed = false
                        onConnected(false)
                        scheduleReconnect()
                    }
                }
            },
        )
    }

    private fun scheduleReconnect() {
        // M3: пока поездка активна (владелец-сервис не звал close() → closed=false) — НЕ сдаёмся.
        // Раньше после ~10 попыток (≈3 мин) стрим гас до конца поездки на трассах без связи, и никто не будил.
        // Владелец закрывает сокет по завершении поездки (closed=true) → бесконечного цикла нет.
        // Backoff с 30с-cap сохранён: на «мёртвой зоне» пробуем раз в 30с (экономно), сеть вернулась — подхватим за ≤30с.
        if (closed) return
        attempt++
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(attempt - 1, 5))   // 1,2,4,8,16,30… cap 30
        scheduleReconnectAfter(delay)
    }

    /** Ожидаем подтверждение не больше сорока повторов; транспортный handshake не сбрасывает лимит. */
    private fun softReconnect() {
        if (closed) return
        if (softAttempt >= MAX_SOFT_ATTEMPTS) { terminate(); return }
        softAttempt++
        scheduleReconnectAfter(SOFT_RETRY_SEC)
    }

    /**
     * Отвергнутый ключ повторно не шлём (как ChatSocket): по лестнице 1…30 с просим обновление тем же
     * путём, что запросы после 401, и подключаемся, как только ключ другой. Явный отказ в продлении
     * (401/403 — «Выйти со всех устройств») — конец; нет сети — следующий шаг лестницы.
     */
    private fun awaitFreshToken() {
        if (closed) return
        val rejected = rejectedToken ?: return
        tokenAttempt++
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(tokenAttempt - 1, 5))
        val connection = generation
        scheduleTask(Runnable {
            if (closed || generation != connection) return@Runnable
            // Сеть — вне замка: обновление ключа может идти секунды.
            val failure = runCatching { runBlocking { refreshToken(rejected, ownerSession) } }
                .getOrElse { Result.failure<Unit>(it) }.exceptionOrNull()
            synchronized(this) {
                if (closed || generation != connection) return@Runnable
                val fresh = currentToken()
                when {
                    fresh != null && fresh != rejected -> { rejectedToken = null; openSocket() }
                    fresh == null || (failure is ApiException && failure.status in setOf(401, 403)) -> terminate()
                    else -> awaitFreshToken()
                }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun scheduleReconnectAfter(delay: Long) {
        val connection = generation
        scheduleTask(Runnable {
            synchronized(this) {
                if (!closed && generation == connection) openSocket()
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun terminate() {
        if (closed) return
        close()
        onTerminated()
    }

    /** Отправить свою позицию другому участнику. true — ушло. */
    @Synchronized
    fun sendLoc(lat: Double, lng: Double, bearing: Double? = null): Boolean {
        // До кадра входа точку не шлём: OkHttp отправил бы её раньше {type:auth}, сервер принял бы её
        // за попытку входа и закрыл канал «Invalid token» (приёмка PR158). Как `authed` на сайте.
        if (!authed) return false
        // Вышли или сменили аккаунт, а канал ещё открыт — точку не шлём, канал гасим (как ChatSocket).
        if (!ApiClient.isCurrentSession(ownerSession)) { terminate(); return false }
        val now = System.currentTimeMillis()
        val o = JSONObject().put("type", "loc").put("lat", lat).put("lng", lng)
            .put("ts", now / 1000)
        if (bearing != null) o.put("bearing", bearing)
        val sent = ws?.send(o.toString()) ?: false
        // Соединение пережило проверку ключа и шлёт дальше — лестница отказов в ключе с нуля (как на сайте).
        if (sent && now - authedAt >= KEY_SURVIVED_MS) tokenAttempt = 0
        return sent
    }

    @Synchronized
    fun close() {
        closed = true
        authed = false
        generation++
        NetworkMonitor.unsubscribe(netListener)   // отписка обязательна — не будим мёртвый канал, не течём
        ws?.close(1000, null)
        ws = null
    }
}
