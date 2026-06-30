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

    companion object {
        private const val MAX_ATTEMPTS = 10
        private const val MAX_DELAY_SEC = 30L
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "loc-ws-reconnect").apply { isDaemon = true }
            }
        }
    }

    fun connect() { closed = false; attempt = 0; openSocket() }

    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return
        val url = "${ApiClient.wsBase()}/ws/trip/$bookingId/location"   // токен НЕ в URL — первым сообщением
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                    attempt = 0
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
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onConnected(false)
                    // 1008 / 4xxx = терминальный отказ (не участник, поездка не активна) → не долбимся в цикл.
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
