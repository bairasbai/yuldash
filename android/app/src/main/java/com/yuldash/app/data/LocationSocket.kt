package com.yuldash.app.data

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
 */
class LocationSocket(
    private val bookingId: Int,
    private val onPeer: (Peer) -> Unit,                 // позиция другого участника
    private val onConnected: (Boolean) -> Unit = {},
) {
    data class Peer(val role: String, val lat: Double, val lng: Double, val bearing: Double?, val ts: Long)

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var attempt = 0
    @Volatile private var softAttempt = 0        // мягкий ретрай «поездка не активна» — с потолком MAX_SOFT_ATTEMPTS

    // Сеть вернулась → мгновенный реконнект (не ждём backoff-таймер). Держим как поле:
    // NetworkMonitor хранит слушателей через WeakReference, ссылку не даём собрать GC.
    private val netListener = NetworkMonitor.Listener {
        if (!closed) { attempt = 0; openSocket() }
    }

    companion object {
        private const val MAX_DELAY_SEC = 30L
        private const val SOFT_RETRY_SEC = 15L   // ретрай «поездка ещё не активна»
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

    fun connect() { closed = false; attempt = 0; softAttempt = 0; NetworkMonitor.subscribe(netListener); openSocket() }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return
        ws?.close(4999, "replaced")   // закрываем старый сокет перед новым (гонка reconnect↔connect → двойной GPS-канал); 4999 = терминал, без churn
        val url = "${ApiClient.wsBase()}/ws/trip/$bookingId/location"   // токен НЕ в URL — первым сообщением
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                    attempt = 0; softAttempt = 0
                    onConnected(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        if (o.optString("type") == "loc") {
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
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Отвечаем на серверный graceful-close (деплой/рестарт) → onClosed гарантированно придёт
                    // и отработает реконнект. Без этого handshake не завершается до TCP-таймаута — стрим тихо умирает.
                    webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onConnected(false)
                    // Поездка ещё не активна (бронь pending/подтверждается) — сервер закрывает 1008 "Trip not active".
                    // Это НЕ терминал: бронь станет confirmed → подключимся. Мягкий ретрай раз в 15с (сервис
                    // живёт только во время поездки → не вечный цикл). Иначе сразу после брони стрим бы не запускался.
                    if (code == 1008 && reason.contains("not active", ignoreCase = true)) { softReconnect(); return }
                    // Forbidden / Invalid token / прочие 1008|4xxx — настоящий терминал, не долбимся.
                    if (code != 1008 && code !in 4000..4999) scheduleReconnect()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onConnected(false)
                    scheduleReconnect()
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
        scheduler.schedule({ openSocket() }, delay, TimeUnit.SECONDS)
    }

    /** Поездка ещё не активна → пробуем снова раз в 15с, БЕЗ счётчика попыток (станет confirmed — подключимся).
     *  Цикл ограничен жизнью сервиса: он закрывает сокет, когда поездка кончилась. */
    private fun softReconnect() {
        if (closed || softAttempt >= MAX_SOFT_ATTEMPTS) return
        softAttempt++
        scheduler.schedule({ openSocket() }, SOFT_RETRY_SEC, TimeUnit.SECONDS)
    }

    /** Отправить свою позицию другому участнику. true — ушло. */
    fun sendLoc(lat: Double, lng: Double, bearing: Double? = null): Boolean {
        val o = JSONObject().put("type", "loc").put("lat", lat).put("lng", lng)
            .put("ts", System.currentTimeMillis() / 1000)
        if (bearing != null) o.put("bearing", bearing)
        return ws?.send(o.toString()) ?: false
    }

    fun close() {
        closed = true
        NetworkMonitor.unsubscribe(netListener)   // отписка обязательна — не будим мёртвый канал, не течём
        ws?.close(1000, null)
        ws = null
    }
}
