package com.yuldash.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realtime-чат по WebSocket (wss://yulbash.ru/ws/bookings/{id}).
 * Токен шлём ПЕРВЫМ сообщением {type:auth,token}, НЕ в URL (query-string утекает в логи прокси).
 * Сервер сохраняет сообщение и рассылает всем подключённым (включая отправителя).
 * REST остаётся для истории; WS — для живой доставки.
 */
class ChatSocket(
    private val bookingId: Int,
    private val onMessage: (Incoming) -> Unit,
    private val onConnected: (Boolean) -> Unit = {},
) {
    data class Incoming(val id: Int, val senderId: Int, val text: String, val timestamp: String)

    private var ws: WebSocket? = null

    companion object {
        // ОДИН клиент на всё приложение: пул соединений и пул потоков переиспользуются.
        // Раньше каждый чат создавал свой OkHttpClient (новый thread-pool + сокеты) и при
        // close() глушил его executor — лишние аллокации и утечка потоков на каждый чат.
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)   // keep-alive, чтобы соединение не засыпало
                .build()
        }
    }

    fun connect() {
        val token = ApiClient.currentToken() ?: return
        val url = "${ApiClient.wsBase()}/ws/bookings/$bookingId"   // токен НЕ в URL — шлём первым сообщением
        ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
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
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onConnected(false)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onConnected(false)
            },
        )
    }

    /** Отправить текст. Сервер сохранит и разошлёт (вернётся и нам). true — ушло. */
    fun send(text: String): Boolean =
        ws?.send(JSONObject().put("type", "message").put("text", text).toString()) ?: false

    fun close() {
        ws?.close(1000, null)
        ws = null
        // НЕ глушим executor общего клиента — он живёт на весь процесс и нужен другим чатам.
    }
}
