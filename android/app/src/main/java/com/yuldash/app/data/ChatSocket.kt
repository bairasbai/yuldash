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
    // Чат такси-заказа (B7b-1) живёт на другом пути (/ws/instant/{id}/chat) — тот же протокол.
    // null → прежний booking-чат (/ws/bookings/{bookingId}); поведение старых вызовов не меняется.
    private val path: String? = null,
    // Сервер отказался принять сообщение (например, слишком быстрый поток — потолок пушей).
    // Без этого сигнала сообщение висело бы на экране как отправленное: эхо не придёт никогда,
    // и человек узнал бы об отказе только когда сообщение пропадёт при обновлении истории.
    private val onRejected: (tempId: Int, reason: String) -> Unit = { _, _ -> },
    private val socketFactory: WebSocket.Factory = client,
    private val scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
    expectedGeneration: Long? = null,
) {
    data class Incoming(
        val id: Int, val senderId: Int, val text: String, val timestamp: String,
        val flag: String = "",           // анти-фишинг (B8-6): "warn" → плашка получателю
        val fromAdmin: Boolean = false,  // официальность (B8-9): бейдж «Юлдаш ✓»
    )

    private var ws: WebSocket? = null
    @Volatile private var closed = false   // выставлен из UI-потока в close(), читается из ws-потока
    @Volatile private var attempt = 0
    private var generation = 0L
    // A screen/socket belongs to the login that created it, not to the next account's token.
    private val ownerSession = expectedGeneration ?: ApiClient.queueSessionGeneration()

    // Сеть вернулась → мгновенный реконнект (не ждём backoff-таймер). Держим как поле:
    // NetworkMonitor хранит слушателей через WeakReference, ссылку не даём собрать GC.
    private val netListener = NetworkMonitor.Listener {
        if (!closed) { attempt = 0; openSocket() }
    }

    companion object {
        private const val MAX_DELAY_SEC = 30L

        /** Чат такси-заказа (B7b-1): тот же сокет-протокол, путь /ws/instant/{orderId}/chat. */
        fun forOrder(
            orderId: Int,
            onMessage: (Incoming) -> Unit,
            onConnected: (Boolean) -> Unit = {},
            onRejected: (tempId: Int, reason: String) -> Unit = { _, _ -> },
            socketFactory: WebSocket.Factory = client,
            scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
            expectedGeneration: Long? = null,
        ) = ChatSocket(orderId, onMessage, onConnected,
                       path = "/ws/instant/$orderId/chat", onRejected = onRejected,
                       socketFactory = socketFactory, scheduleTask = scheduleTask, expectedGeneration = expectedGeneration)

        /**
         * Чат доставки: отправитель ↔ курьер. Тот же протокол, путь /ws/parcel/{parcelId}/chat.
         *
         * Зачем отдельный канал: до него по посылке можно было только позвонить. «Оставь у соседей»,
         * «домофон не работает, звони», «я на работе до шести» — вещи, которые в селе решаются
         * одной фразой, а без чата превращаются в звонок или в потерянную посылку.
         */
        fun forParcel(
            parcelId: Int,
            onMessage: (Incoming) -> Unit,
            onConnected: (Boolean) -> Unit = {},
            onRejected: (tempId: Int, reason: String) -> Unit = { _, _ -> },
            socketFactory: WebSocket.Factory = client,
            scheduleTask: (Runnable, Long, TimeUnit) -> Unit = { task, delay, unit -> scheduler.schedule(task, delay, unit); Unit },
            expectedGeneration: Long? = null,
        ) = ChatSocket(parcelId, onMessage, onConnected,
                       path = "/ws/parcel/$parcelId/chat", onRejected = onRejected,
                       socketFactory = socketFactory, scheduleTask = scheduleTask, expectedGeneration = expectedGeneration)

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

    @Synchronized
    fun connect() {
        if (!ownsSession()) return
        closed = false
        attempt = 0
        NetworkMonitor.subscribe(netListener)
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (closed || !ownsSession()) return
        val token = ApiClient.currentToken() ?: return
        val connection = ++generation
        // Закрываем предыдущий сокет ПЕРЕД созданием нового: при гонке reconnect↔connect иначе оставались
        // бы два живых WS на один канал → дубли сообщений. Код 4999 (терминальный диапазон) → его onClosed
        // НЕ запустит реконнект (без churn). @Synchronized сериализует параллельные openSocket.
        ws?.close(4999, "replaced")
        val url = "${ApiClient.wsBase()}${path ?: "/ws/bookings/$bookingId"}"   // токен НЕ в URL — шлём первым сообщением
        ws = socketFactory.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@ChatSocket) {
                        if (!isCurrent(connection)) { webSocket.close(1000, null); return }
                        webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
                        attempt = 0                 // успешное соединение → сбрасываем backoff
                        onConnected(true)
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    synchronized(this@ChatSocket) {
                        if (!isCurrent(connection)) return
                        runCatching {
                            val o = JSONObject(text)
                            if (o.optString("type") == "message") {
                                onMessage(
                                    Incoming(
                                        id = o.optInt("id"),
                                        senderId = o.optInt("sender_id"),
                                        text = o.optString("text"),
                                        timestamp = o.optString("timestamp"),
                                        flag = o.optString("flag"),
                                        fromAdmin = o.optBoolean("from_admin"),
                                    )
                                )
                            } else if (o.optString("type") == "rejected") {
                                // temp_id identifies the optimistic message rejected by the server.
                                onRejected(o.optInt("temp_id"), o.optString("reason"))
                            }
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Отвечаем на серверный graceful-close (в т.ч. 1008 «не участник»/«битый токен»): без этого
                    // onClosed может не прийти → onFailure → долбёжка реконнекта даже при терминальном отказе.
                    webSocket.close(code, null)
                    synchronized(this@ChatSocket) {
                        if (!isCurrent(connection)) return
                        if (code == 1008 || code in 4000..4999) {
                            close()
                            onConnected(false)
                        }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(this@ChatSocket) {
                        if (!isCurrent(connection)) return
                        ws = null
                        onConnected(false)
                        // Policy/auth rejections stop both timers and network-triggered reconnects.
                        if (code != 1008 && code !in 4000..4999) scheduleReconnect() else close()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@ChatSocket) {
                        if (!isCurrent(connection)) return
                        ws = null
                        onConnected(false)
                        scheduleReconnect()         // сетевой сбой — всегда пробуем снова (с backoff)
                    }
                }
            },
        )
    }

    private fun ownsSession(): Boolean {
        if (ownerSession == ApiClient.queueSessionGeneration()) return true
        close()
        return false
    }

    private fun isCurrent(connection: Long): Boolean =
        !closed && generation == connection && ownsSession()

    private fun scheduleReconnect() {
        // M3: пока экран чата открыт (владелец не звал close() → closed=false) — НЕ сдаёмся.
        // Раньше после ~10 попыток (≈3 мин) живой приём вставал до перезахода на экран; на трассе без связи это часто.
        // close() (onDispose экрана) ставит closed=true → бесконечного цикла нет. Backoff с 30с-cap сохранён.
        if (closed) return
        attempt++
        // 1,2,4,8,16,30,30… секунд (cap 30) — не флудим сервер при долгом обрыве.
        val delay = minOf(MAX_DELAY_SEC, 1L shl minOf(attempt - 1, 5))
        val connection = generation
        scheduleTask(Runnable {
            synchronized(this) {
                if (isCurrent(connection)) openSocket()
            }
        }, delay, TimeUnit.SECONDS)
    }

    /** Отправить текст. Сервер сохранит и разошлёт (вернётся и нам). true — ушло. */
    /**
     * Отправить сообщение. `tempId` — номер «оптимистичного» сообщения на экране: сервер вернёт
     * его обратно, если откажется принять (см. onRejected). Сервер поле игнорирует, если не знает.
     */
    @Synchronized
    fun send(text: String, tempId: Int = 0): Boolean {
        if (closed || !ownsSession()) return false
        return ws?.send(
            JSONObject().put("type", "message").put("text", text).put("temp_id", tempId).toString()
        ) ?: false
    }

    @Synchronized
    fun close() {
        closed = true   // глушит запланированные и будущие реконнекты
        generation++
        NetworkMonitor.unsubscribe(netListener)   // отписка обязательна — не будим мёртвый канал, не течём
        ws?.close(1000, null)
        ws = null
        // НЕ глушим executor общего клиента — он живёт на весь процесс и нужен другим чатам.
    }
}
