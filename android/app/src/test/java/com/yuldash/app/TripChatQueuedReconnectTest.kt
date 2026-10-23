package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Приёмка 18.10.2026, находка 4. Сервер был недоступен (интернет у телефона был): сообщение
 * встало «В очереди», а когда сервер вернулся — пропало из ленты и ушло только при следующем
 * запуске приложения. Человек решит, что оно потерялось, напишет снова — и будет дубль.
 * Настоящие TripChatState/Outbox поверх HTTP; ключ запроса в журнале сервера — как дедупликация
 * на бэкенде (`request_id`/`Idempotency-Key`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripChatQueuedReconnectTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var clock: TestCoroutineScheduler
    private lateinit var chat: TripChatState
    private val failures = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()
    private val ledger = linkedMapOf<String, String>()   // ключ запроса → текст: так сервер не задваивает
    /** Сервер лежит целиком (как в приёмке: туннель есть, сервера нет). */
    @Volatile private var serverDown = false
    /** Лента отвечает, а запись — нет (сервер поднимается). */
    @Volatile private var postsDown = false
    /** Сервер отвечает, но запись окончательно отклоняет (например, чат брони закрыт). */
    @Volatile private var refusePosts = false
    private val text = "Жду у второго подъезда"
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        clock = TestCoroutineScheduler()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    if (path == "/auth/logout") return MockResponse().setBody("{}")
                    if (serverDown || (postsDown && request.method == "POST")) {
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    if (refusePosts && request.method == "POST" && path == "/bookings/42/messages") {
                        return MockResponse().setResponseCode(403).setBody("""{"detail":"closed"}""")
                    }
                    if (path == "/bookings/42/messages" && request.method == "POST") {
                        val key = request.getHeader("Idempotency-Key").orEmpty()
                        if (key.isBlank()) failures += "POST без ключа запроса"
                        synchronized(ledger) { ledger.getOrPut(key) { JSONObject(request.body.readUtf8()).getString("text") } }
                        return MockResponse().setBody("{}")
                    }
                    if (path == "/bookings/42/messages" && request.method == "GET") {
                        val items = synchronized(ledger) {
                            JSONArray().apply {
                                ledger.values.forEachIndexed { i, t -> put(JSONObject().put("id", 501 + i).put("sender_id", 11).put("text", t)) }
                            }
                        }
                        return MockResponse().setBody(JSONObject().put("items", items).toString())
                    }
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1500
        ApiClient.init(context)
        ApiClient.saveToken(token)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        val generation = ApiClient.queueSessionGeneration()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clock) +
            CoroutineExceptionHandler { _, error -> failures += error.stackTraceToString() })
        chat = TripChatState(context, 42, generation, scope,
            isActive = { ApiClient.isCurrentSession(generation) },
            isSessionCurrent = { ApiClient.isCurrentSession(generation) },
            texts = mutableStateOf(TripChatTexts("send", "fast", "queued", "disk", "send", "action", "voice")),
        )
    }

    @After fun cleanup() {
        try {
            chat.socket?.close()
            chat.scope.cancel()
            waitFor { chat.scope.coroutineContext[Job]?.children?.none() != false }
        } finally {
            Outbox.initStores(MemoryDiskPreferences(), null)
            TripPassStore.initStores(MemoryDiskPreferences(), null)
            try {
                ApiClient.logout()
                waitFor { requests.contains("POST /auth/logout") }
            } finally {
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                server.shutdown()
            }
        }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            clock.runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        clock.runCurrent()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) fail("Не дождались. messages=${chat.messages} queued=${chat.queuedIds} " +
            "failed=${chat.failedIds} outbox=${Outbox.count(context, 42)} ledger=$ledger requests=$requests failures=$failures")
    }

    /** То, что делает экран, когда сокет чата снова подключился (TripChatConnection). */
    private fun socketConnected() {
        chat.wsConnected = true
        var done = false
        chat.scope.launch { chat.refreshAfterReconnect(); done = true }
        waitFor { done }
    }

    /** Связь была, сервер лёг, сообщение ушло в очередь. */
    private fun queueWhileServerDown() {
        socketConnected()                 // первое подключение экрана
        serverDown = true
        chat.wsConnected = false          // сокет оборвался вместе с сервером
        chat.sendText(text)
        waitFor { -2 in chat.queuedIds }
        assertEquals(1, Outbox.count(context, 42))
        assertTrue(chat.messages.any { it.id == -2 && it.text == text })
    }

    @Test fun queuedMessageIsSentOnceWhenServerComesBackAndStaysVisibleMeanwhile() {
        queueWhileServerDown()
        serverDown = false
        socketConnected()                 // сервер вернулся — сокет переподключился
        waitFor { synchronized(ledger) { ledger.size } == 1 && Outbox.count(context, 42) == 0 && chat.queuedIds.isEmpty() }
        waitFor { chat.messages.count { it.text == text } == 1 && chat.messages.single { it.text == text }.id > 0 }
        assertEquals("Сообщение на сервере ровно одно", listOf(text), synchronized(ledger) { ledger.values.toList() })
        assertTrue(chat.failedIds.isEmpty())
    }

    @Test fun queuedMessageDoesNotVanishWhenFeedRefreshesBeforeItIsSent() {
        queueWhileServerDown()
        serverDown = false
        postsDown = true                  // лента уже отвечает, а отправка ещё нет
        socketConnected()
        waitFor { requests.count { it == "GET /bookings/42/messages" } >= 1 }
        assertTrue("Неотправленное должно остаться на экране", chat.messages.any { it.id == -2 && it.text == text })
        assertTrue(-2 in chat.queuedIds)
        assertEquals(1, Outbox.count(context, 42))

        postsDown = false
        socketConnected()
        waitFor { chat.messages.count { it.text == text } == 1 && chat.messages.single { it.text == text }.id > 0 }
        assertEquals(1, synchronized(ledger) { ledger.size })
        assertEquals(0, Outbox.count(context, 42))
    }

    @Test fun refusedQueuedMessageStaysVisibleAsNotDelivered() {
        queueWhileServerDown()
        serverDown = false
        refusePosts = true
        socketConnected()
        waitFor { Outbox.count(context, 42) == 0 && -2 in chat.failedIds }
        assertTrue("Отказанное не прячем — «Не доставлено · Повторить»", chat.messages.any { it.id == -2 && it.text == text })
        assertFalse(-2 in chat.queuedIds)
        assertEquals(0, synchronized(ledger) { ledger.size })
    }

    @Test fun messageSentByAnotherFlushReloadsFeedAndIsNotDuplicated() {
        queueWhileServerDown()
        serverDown = false
        var flushed = false
        chat.scope.launch { assertTrue(Outbox.flush(context)); flushed = true }
        waitFor { flushed }
        assertTrue(-2 in chat.queuedIds) // Общий отправитель сам состояние экрана не меняет.
        var reconciled = false
        chat.scope.launch { assertTrue(chat.flushQueued()); reconciled = true }
        waitFor { reconciled }
        assertEquals(1, chat.messages.count { it.text == text })
        assertTrue(chat.messages.single { it.text == text }.id > 0)
        assertTrue(chat.queuedIds.isEmpty())
        assertTrue(chat.failedIds.isEmpty())
        assertEquals(1, synchronized(ledger) { ledger.size })
    }

    @Test fun messageRefusedByAnotherFlushRemainsVisibleAsNotDelivered() {
        queueWhileServerDown()
        serverDown = false
        refusePosts = true
        var flushed = false
        chat.scope.launch { assertTrue(Outbox.flush(context)); flushed = true }
        waitFor { flushed }
        var reconciled = false
        chat.scope.launch { assertTrue(chat.flushQueued()); reconciled = true }
        waitFor { reconciled }
        assertTrue(chat.messages.any { it.id == -2 && it.text == text })
        assertFalse(-2 in chat.queuedIds)
        assertTrue(-2 in chat.failedIds)
        assertEquals(0, synchronized(ledger) { ledger.size })
    }

    @Test fun sharedRefusalIdsAreClearedWhenSessionChanges() {
        val generation = ApiClient.queueSessionGeneration()
        val action = Outbox.newMessage(42, text)
        assertTrue(Outbox.enqueue(context, action, generation))
        refusePosts = true
        var flushed = false
        chat.scope.launch { assertTrue(Outbox.flush(context)); flushed = true }
        waitFor { flushed }
        assertTrue(Outbox.wasRefused(action, generation))
        ApiClient.saveToken(token)
        assertFalse(Outbox.wasRefused(action, generation))
        assertFalse(Outbox.wasRefused(action, ApiClient.queueSessionGeneration()))
    }
}
