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
) {
    // Такси-заказ (существующие вызовы не меняются): InstantLocationSocket(orderId, onPeer = …).
    constructor(orderId: Int, onPeer: (LocationSocket.Peer) -> Unit, onConnected: (Boolean) -> Unit = {})
        : this("/ws/instant/$orderId/location", onPeer, onConnected)

    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var attempt = 0
    @Volatile private var softAttempt = 0

    companion object {
        // Доставка посылки (курьер ↔ отправитель): тот же трек-сокет, другой путь.
        fun forParcel(parcelId: Int, onPeer: (LocationSocket.Peer) -> Unit, onConnected: (Boolean) -> Unit = {}) =
            InstantLocationSocket("/ws/parcel/$parcelId/location", onPeer, onConnected)

        private const val MAX_ATTEMPTS = 10
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

    fun connect() { closed = false; attempt = 0; softAttempt = 0; openSocket() }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return
        ws?.close(4999, "replaced")   // гонка reconnect↔connect → не плодим двойной канал
        val url = "${ApiClient.wsBase()}$wsPath"   // токен НЕ в URL
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
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)   // отвечаем на серверный graceful-close → реконнект отработает
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onConnected(false)
                    // «Order not active» — заказ ещё accepted не стал (или уже кончился): мягкий ретрай.
                    if (code == 1008 && reason.contains("not active", ignoreCase = true)) { softReconnect(); return }
                    // Forbidden / Invalid token / 4xxx — терминал, не долбимся.
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
        if (closed || attempt >= MAX_ATTEMPTS) return
        attempt++
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(attempt - 1, 5))   // 1,2,4,8,16,30… cap 30
        scheduler.schedule({ openSocket() }, delay, TimeUnit.SECONDS)
    }

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
        ws?.close(1000, null)
        ws = null
    }
}
