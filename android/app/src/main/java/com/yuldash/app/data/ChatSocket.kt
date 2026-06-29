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
 * Realtime-чат по WebSocket (wss://yulbash.ru/ws/bookings/{id}).
 * Токен шлём ПЕРВЫМ сообщением {type:auth,token}, НЕ в URL (query-string утекает в логи прокси).
 * Сервер сохраняет сообщение и рассылает всем подключённым (включая отправителя).
 * REST остаётся для истории; WS — для живой доставки.
 *
 * Авто-реконнект с экспоненциальной задержкой: при обрыве (сеть/сон/рестарт сервера) сам
 * переподключается (раньше после любого обрыва живой приём вставал до перезахода на экран).
 * НЕ реконнектит при явном close() и при терминальных кодах (отказ авторизации) — иначе вечный цикл.
 */
class ChatSocket(
    private val bookingId: Int,
    private val onMessage: (Incoming) -> Unit,
    private val onConnected: (Boolean) -> Unit = {},
) {
    data class Incoming(val id: Int, val senderId: Int, val text: String, val timestamp: String)

    private var ws: WebSocket? = null
    @Volatile private var closed = false   // выставлен из UI-потока в close(), читается из ws-потока
    @Volatile private var attempt = 0

    companion object {
        private const val MAX_ATTEMPTS = 10
        private const val MAX_DELAY_SEC = 30L

        // ОДИН клиент на всё приложение: пул соединений и пул потоков переиспользуются.
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)   // keep-alive, чтобы соединение не засыпало
                .build()
        }
        // Один демон-планировщик на процесс для отложенных реконнектов всех чатов.
        private val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "chat-ws-reconnect").apply { isDaemon = true }
            }
        }
    }

    fun connect() {
        closed = false
        attempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (closed) return
        val token = ApiClient.currentToken() ?: return
        val url = "${ApiClient.wsBase()}/ws/bookings/$bookingId"   // токен НЕ в URL — шлём первым сообщением
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                    attempt = 0                 // успешное соединение → сбрасываем backoff
                    onConnected(true)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val o = JSONObject(text)
                        if (o.optString("type") == "message") {
                            onMessage(
                                Incoming(
                                    id = o.optInt("id"),
                                    senderId = o.optInt("sender_id"),
                                    text = o.optString("text"),
                                    timestamp = o.optString("timestamp"),
                                )
                            )
                        }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onConnected(false)
                    // 1008 (policy/нарушение) и кастомные 4xxx = терминальный отказ (напр. не участник брони,
                    // протухший токен) → НЕ долбимся в цикл. Прочее (рестарт/разрыв) → реконнект.
                    if (code != 1008 && code !in 4000..4999) scheduleReconnect()
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onConnected(false)
                    scheduleReconnect()         // сетевой сбой — всегда пробуем снова (с backoff)
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (closed || attempt >= MAX_ATTEMPTS) return
        attempt++
        // 1,2,4,8,16,30,30… секунд (cap 30) — не флудим сервер при долгом обрыве.
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(attempt - 1, 5))
        scheduler.schedule({ openSocket() }, delay, TimeUnit.SECONDS)
    }

    /** Отправить текст. Сервер сохранит и разошлёт (вернётся и нам). true — ушло. */
    fun send(text: String): Boolean =
        ws?.send(JSONObject().put("type", "message").put("text", text).toString()) ?: false

    fun close() {
        closed = true   // глушит запланированные и будущие реконнекты
        ws?.close(1000, null)
        ws = null
        // НЕ глушим executor общего клиента — он живёт на весь процесс и нужен другим чатам.
    }
}
