package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Приёмка 23.10.2026 (P1): сокет чата подключился не с первой попытки — сообщение собеседника,
 * пришедшее, пока сокета не было, не должно пропасть. Неудачная попытка не рвёт начатое чтение
 * истории, но когда канал оживает, историю перечитываем ровно один раз.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripChatRefusedSocketResyncTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val refusedHandshakes = AtomicInteger()
    private val authenticated = CountDownLatch(1)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    @Volatile private var acceptSocket = false
    @Volatile private var driverWrote = false

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    if (path == "/ws/bookings/42") {
                        // Сервер перезапускается: подключение сокета отклоняют, обычные запросы ходят.
                        if (!acceptSocket) { refusedHandshakes.incrementAndGet(); return MockResponse().setResponseCode(503) }
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                            override fun onMessage(webSocket: WebSocket, text: String) { authenticated.countDown() }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        })
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                        "/bookings/42/messages" ->
                            if (driverWrote) """{"items":[{"id":90,"sender_id":22,"text":"Я у подъезда"}]}""" else """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sockets.forEach { it.close(1000, null) }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun historyReads() = requests.count { it == "GET /bookings/42/messages" }
    private fun waitFor(timeoutMs: Long = 15000, condition: () -> Boolean) = compose.waitUntil(timeoutMs) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun pump(millis: Long) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }

    @Test fun messageSentWhileSocketWasRefusedAppearsAfterFirstSuccessfulConnect() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42, onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        // Первая история прочитана (за ней экран уже спросил детали брони), сокет хотя бы раз отклонён.
        waitFor { requests.contains("GET /bookings/42/details") && refusedHandshakes.get() >= 1 }
        waitFor { compose.onAllNodesWithText("Водитель отметит посадку").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Первая история — одно чтение", 1, historyReads())
        assertEquals("Сокет ещё не подключён", 1L, authenticated.count)

        // Пока сокета нет, водитель пишет: живьём это сообщение не придёт никогда.
        driverWrote = true
        acceptSocket = true
        waitFor { authenticated.count == 0L }
        val reread = runCatching { waitFor(5000) { historyReads() >= 2 } }.isSuccess
        assertTrue("Сокет подключился после отказа, а история не перечитана — сообщение потерялось", reread)

        compose.onNodeWithText("Написать").performClick()
        waitFor { compose.onAllNodesWithText("Я у подъезда").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Я у подъезда").assertIsDisplayed()
        pump(1500)
        assertEquals("После подключения — ровно одно новое чтение истории", 2, historyReads())
    }
}
