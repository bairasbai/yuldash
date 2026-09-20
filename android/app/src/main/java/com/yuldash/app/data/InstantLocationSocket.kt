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
 * Live-позиция «Быстрого заказа» (такси, B7a-3) по WebSocket /ws/instant/{orderId}/location.
 * Зеркало [LocationSocket] (трек брони) для такси-заказа: водитель шлёт свой GPS
 * {type:loc,lat,lng,bearing,ts}, пассажир принимает и видит движущуюся машину.
 * Токен — первым сообщением {type:auth,token} (не в URL). Сервер ретранслирует ТОЛЬКО
 * в активном заказе (accepted/arriving/onboard); координаты не хранит (приватность).
 * Авто-реконнект с backoff; «Order not active» — мягкий ретрай (заказ вот-вот станет активным).
 */
class InstantLocationSocket private constructor(
    private val wsPath: String,                          // путь WS-канала (такси-заказ или доставка посылки)
    private val onPeer: (LocationSocket.Peer) -> Unit,   // позиция другого участника (пассажиру — машина, отправителю — курьер)
    private val onConnected: (Boolean) -> Unit = {},
    private val socketFactory: WebSocket.Factory = client,
    private val scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
    private val onTerminated: () -> Unit = {},
) {
    // Такси-заказ (существующие вызовы не меняются): InstantLocationSocket(orderId, onPeer = …).
    constructor(orderId: Int, onPeer: (LocationSocket.Peer) -> Unit, onConnected: (Boolean) -> Unit = {},
                socketFactory: WebSocket.Factory = client,
                scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
                onTerminated: () -> Unit = {})
        : this("/ws/instant/$orderId/location", onPeer, onConnected, socketFactory, scheduleTask, onTerminated)

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var attempt = 0
    @Volatile private var softAttempt = 0

    // Сеть вернулась → мгновенный реконнект (не ждём backoff-таймер). Держим как поле:
    // NetworkMonitor хранит слушателей через WeakReference, ссылку не даём собрать GC.
    private val netListener = NetworkMonitor.Listener {
        if (!closed) { attempt = 0; openSocket() }
    }

    companion object {
        // Доставка посылки (курьер ↔ отправитель): тот же трек-сокет, другой путь.
        fun forParcel(parcelId: Int, onPeer: (LocationSocket.Peer) -> Unit, onConnected: (Boolean) -> Unit = {},
                      socketFactory: WebSocket.Factory = client,
                      scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
                      onTerminated: () -> Unit = {}) =
            InstantLocationSocket("/ws/parcel/$parcelId/location", onPeer, onConnected, socketFactory, scheduleTask, onTerminated)

        private const val MAX_DELAY_SEC = 30L
        private const val SOFT_RETRY_SEC = 10L    // заказ ещё не активен → ретрай чуть чаще, чем у брони
        private const val MAX_SOFT_ATTEMPTS = 30  // ~5 мин потолок — не долбим сервер вечно
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "instant-loc-ws").apply { isDaemon = true }
            }
        }
    }

    @Synchronized
    fun connect() { closed = false; attempt = 0; softAttempt = 0; NetworkMonitor.subscribe(netListener); openSocket() }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return
        val connection = ++generation
        ws?.close(4999, "replaced")   // закрываем старый сокет перед новым (гонка reconnect↔connect → двойной GPS-канал); 4999 = терминал, без churn
        val url = "${ApiClient.wsBase()}$wsPath"   // токен НЕ в URL — первым сообщением
        ws = socketFactory.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@InstantLocationSocket) {
                        if (closed || generation != connection) return
                        webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                        attempt = 0
                        onConnected(true)
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@InstantLocationSocket) {
                        if (closed || generation != connection) return
                        runCatching {
                            val o = JSONObject(text)
                            if (o.optString("type") == "loc") {
                                softAttempt = 0
                                onPeer(
                                    LocationSocket.Peer(
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
                    synchronized(this@InstantLocationSocket) {
                        if (closed || generation != connection) return
                        ws = null
                        onConnected(false)
                        // Order/Delivery not active: заказ ещё ожидает активации, повтор ограничен.
                        if (code == 1008 && reason.contains("not active", ignoreCase = true)) { softReconnect(); return }
                        // Forbidden / Invalid token / прочие 1008|4xxx — настоящий терминал, не долбимся.
                        if (code != 1008 && code !in 4000..4999) scheduleReconnect() else terminate()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@InstantLocationSocket) {
                        if (closed || generation != connection) return
                        ws = null
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

    /** Ожидаем активацию не больше тридцати повторов; транспортный handshake не сбрасывает лимит. */
    private fun softReconnect() {
        if (closed) return
        if (softAttempt >= MAX_SOFT_ATTEMPTS) { terminate(); return }
        softAttempt++
        scheduleReconnectAfter(SOFT_RETRY_SEC)
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
        val o = JSONObject().put("type", "loc").put("lat", lat).put("lng", lng)
            .put("ts", System.currentTimeMillis() / 1000)
        if (bearing != null) o.put("bearing", bearing)
        return ws?.send(o.toString()) ?: false
    }

    @Synchronized
    fun close() {
        closed = true
        generation++
        NetworkMonitor.unsubscribe(netListener)   // отписка обязательна — не будим мёртвый канал, не течём
        ws?.close(1000, null)
        ws = null
    }
}
