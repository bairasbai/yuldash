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
 * Лёгкий сигнальный WebSocket карты (/ws/map). Сервер шлёт {"type":"refresh"}, когда на карте что-то
 * изменилось (новая поездка/заявка, бронь, отмена) → клиент перетягивает /rides/near + /requests/near
 * МГНОВЕННО, не дожидаясь 25-сек опроса. Токен — первым сообщением (как чат). Авто-реконнект с backoff.
 * Данных в пинге НЕТ (только сигнал) → приватность не задета. Не залогинен → не подключаемся (остаётся polling).
 */
class MapFeedSocket(private val onRefresh: () -> Unit) {
    private var ws: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var attempt = 0

    // Сеть вернулась → мгновенный реконнект (не ждём backoff-таймер). Держим как поле:
    // NetworkMonitor хранит слушателей через WeakReference, ссылку не даём собрать GC.
    private val netListener = NetworkMonitor.Listener {
        if (!closed) { attempt = 0; openSocket() }
    }

    companion object {
        private const val MAX_DELAY_SEC = 30L
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
        }
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "map-ws-reconnect").apply { isDaemon = true }
            }
        }
    }

    fun connect() { closed = false; attempt = 0; NetworkMonitor.subscribe(netListener); openSocket() }

    @Synchronized
    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return   // только залогиненные; аноним — на 25-сек опросе
        ws?.close(4999, "replaced")   // закрываем старый сокет перед новым (гонка reconnect↔connect); 4999 = терминал, без churn
        ws = client.newWebSocket(
            Request.Builder().url("${ApiClient.wsBase()}/ws/map").build(),   // токен НЕ в URL — первым сообщением
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                    attempt = 0
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { if (JSONObject(text).optString("type") == "refresh") onRefresh() }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)   // ответный close на серверный graceful-close → onClosed придёт, реконнект отработает
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1008 && code !in 4000..4999) scheduleReconnect()   // 1008/4xxx (битый токен) — терминал
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scheduleReconnect() }
            },
        )
    }

    private fun scheduleReconnect() {
        // M3: пока карта открыта (владелец не звал close() → closed=false) — НЕ сдаёмся, иначе сигнал «обнови карту»
        // тихо гас после ~8 попыток и оставался только 25-сек опрос. close() (onDispose) → closed=true → цикл не вечен.
        // Backoff с 30с-cap сохранён (лёгкий сигнальный сокет, без данных — приватность не задета).
        if (closed) return
        attempt++
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(attempt - 1, 5))   // 1,2,4,8,16,30… cap 30
        scheduler.schedule({ openSocket() }, delay, TimeUnit.SECONDS)
    }

    fun close() {
        closed = true
        NetworkMonitor.unsubscribe(netListener)   // отписка обязательна — не будим мёртвый канал, не течём
        ws?.close(1000, null)
        ws = null
    }
}
