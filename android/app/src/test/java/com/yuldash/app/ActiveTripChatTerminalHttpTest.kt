package com.yuldash.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Held real HTTP responses across terminal state; actual screen, WS reconnect and queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripChatTerminalHttpTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val auth = LinkedBlockingQueue<String>()
    private val sent = LinkedBlockingQueue<String>()
    private val closed = AtomicInteger()
    private val refreshed = AtomicInteger()
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val postHeld = CountDownLatch(1)
    private val releasePost = CountDownLatch(1)
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val postKeys = CopyOnWriteArrayList<String?>()
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var holdInitialHistory = false
    @Volatile private var holdNextHistory = false
    @Volatile private var historyCode = 200
    @Volatile private var holdPostBooking: Int? = null
    @Volatile private var postReply = "success"
    private var backs = 0
    private var finished = 0
    private var language = AppLanguage.Ru
    private var ownedNetworkCallbacks = emptyList<ConnectivityManager.NetworkCallback>()
    private lateinit var server: MockWebServer
    @Volatile private var remoteStatus = "confirmed"
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenA2 = "header.eyJzdWIiOiIxMSJ9.refreshed"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun prepare() = runBlocking {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val bearer = request.getHeader("Authorization")
                    requests += "${request.method} $path" to bearer
                    if (path == "/ws/bookings/42") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val body = JSONObject(text)
                            if (body.getString("type") == "auth") auth.offer(body.getString("token"))
                            else sent.offer(body.getString("text"))
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.incrementAndGet() }
                    })
                    if (request.method == "POST" && path.matches(Regex("/bookings/[0-9]+/messages"))) {
                        postKeys += request.getHeader("Idempotency-Key")
                        if (path == "/bookings/$holdPostBooking/messages" && postHeld.count == 1L) {
                            postHeld.countDown()
                            if (!releasePost.await(25, TimeUnit.SECONDS)) fixtureFailures += "POST release timeout"
                            if (postReply == "temporary") return MockResponse().setResponseCode(503).setBody("{}")
                            if (postReply == "network") return MockResponse().setBody("{partial-data").setHeader("Content-Length", 1000)
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                        return MockResponse().setBody("{}")
                    }
                    if (request.method == "GET" && path == "/bookings/42/messages") {
                        if (holdInitialHistory || holdNextHistory) {
                            historyHeld.countDown()
                            if (!releaseHistory.await(25, TimeUnit.SECONDS)) fixtureFailures += "History release timeout"
                            return MockResponse().setResponseCode(historyCode).setBody(if (historyCode == 200)
                                """{"items":[{"id":77,"sender_id":22,"text":"late-history"}]}""" else "{}")
                        }
                        return MockResponse().setBody("""{"items":[{"id":1,"sender_id":22,"text":"initial-history"}]}""")
                    }
                    val body = when (path) {
                        "/auth/verify" -> {
                            val account = JSONObject(request.body.readUtf8()).getString("name")
                            """{"access_token":"${if (account == "A") tokenA else tokenB}","refresh_token":"refresh-$account"}"""
                        }
                        "/auth/refresh" -> { refreshed.incrementAndGet(); """{"access_token":"$tokenA2","refresh_token":"refresh-A2"}""" }
                        "/trusted-contacts" -> return MockResponse().setResponseCode(if (bearer == "Bearer $tokenA") 401 else 200).setBody("""{"items":[]}""")
                        "/auth/logout", "/push/unregister" -> "{}"
                        "/bookings/42/role" -> """{"role":"passenger","status":"$remoteStatus","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> """{"items":[{"id":1,"sender_id":22,"text":"${if (bearer == "Bearer $tokenB") "private-history-B" else "private-history-A"}"}]}"""
                        "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed","pay_method":"cash","pay_amount":700}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Водитель","amount":700,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
            start()
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10000
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "1234"))))
        ShadowToast.reset()
        Unit
    }

    @After fun cleanup() {
        releaseHistory.countDown()
        releasePost.countDown()
        jobs.cancel()
        compose.runOnIdle {
            mounted.value = false
            if (owner.registry.currentState != Lifecycle.State.INITIALIZED) owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        sockets.forEach { it.close(1000, null) }
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) waitFor { requests.contains("POST /auth/logout" to bearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
        assertTrue("Fixture timeouts: $fixtureFailures", fixtureFailures.isEmpty())
    }

    private fun listeners(): List<Any> = (NetworkMonitor::class.java.getDeclaredField("listeners").apply { isAccessible = true }
        .get(NetworkMonitor) as List<*>).mapNotNull { (it as WeakReference<*>).get() }
    private fun screenSocket(before: List<Any>): ChatSocket = listeners().filter { it !in before }.flatMap { listener ->
        listener.javaClass.declaredFields.mapNotNull { field -> field.isAccessible = true; field.get(listener) as? ChatSocket }
    }.single()
    private data class Callbacks(
        val message: (ChatSocket.Incoming) -> Unit, val connected: (Boolean) -> Unit,
        val rejected: (Int, String) -> Unit, val messages: MutableState<*>,
        val failures: MutableState<*>, val connection: MutableState<*>,
    )
    @Suppress("UNCHECKED_CAST")
    private fun callbacks(socket: ChatSocket): Callbacks {
        fun field(name: String) = ChatSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.get(socket)
        val message = field("onMessage") as (ChatSocket.Incoming) -> Unit
        val connected = field("onConnected") as (Boolean) -> Unit
        val rejected = field("onRejected") as (Int, String) -> Unit
        // Kotlin's invokedynamic lambdas name captures arg$N; select the unique semantic value type.
        fun state(callback: Any, name: String) = callback.javaClass.declaredFields.mapNotNull {
            it.isAccessible = true; it.get(callback) as? MutableState<*>
        }.single { when (name) {
            "messages" -> it.value is List<*>
            "failedIds" -> it.value is Set<*>
            "wsConnected" -> it.value is Boolean
            else -> false
        } }
        return Callbacks(message, connected, rejected, state(message, "messages"), state(rejected, "failedIds"), state(connected, "wsConnected"))
    }
    private fun mount(openChat: Boolean = !holdInitialHistory): Callbacks {
        val before = listeners()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacksBefore = Shadows.shadowOf(connectivity).networkCallbacks.toSet()
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language, LocalLifecycleOwner provides owner) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = { backs++ }, onTripEnd = { finished++ }, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { auth.peek() == tokenA && requests.any { it.first == "GET /bookings/42/messages" } &&
            compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(tokenA, auth.poll())
        ownedNetworkCallbacks = Shadows.shadowOf(connectivity).networkCallbacks.filter { it !in callbacksBefore }
        assertEquals(1, ownedNetworkCallbacks.size)
        val result = callbacks(screenSocket(before))
        if (openChat) {
            waitFor { requests.any { it.first == "GET /bookings/42/details" } }
            compose.onNodeWithText("Написать").performClick()
            pump(1000)
            tripList().performScrollToNode(hasText("initial-history"))
            compose.onNodeWithText("initial-history").assertIsDisplayed()
        }
        return result
    }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun tripList() = compose.onNode(hasScrollToNodeAction() and
        SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
    private fun pump(millis: Long = 2000) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun terminal(value: String) {
        remoteStatus = value
        // A lifecycle resume starts a real role GET immediately, avoiding a virtual 12-second timer claim.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        pump(300)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { TripPassStore.load(context, 42) == null && (value != "cancelled" || finished == 1) }
        if (value == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
    }
    private fun postCount() = requests.count { it.first.startsWith("POST /bookings/") }
    private fun network() = compose.runOnIdle {
        ownedNetworkCallbacks.single().onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(123))
    }
    private fun heldInitial(value: String, code: Int) {
        holdInitialHistory = true; historyCode = code
        val cb = mount(false)
        waitFor { historyHeld.count == 0L }
        val before = cb.messages.value
        terminal(value)
        releaseHistory.countDown()
        pump(2000)
        assertEquals("Terminal initial HTTP changed messages", before, cb.messages.value)
        assertFalse("Initial chain requested code after terminal", requests.any { it.first == "GET /bookings/42/boarding-code" })
        assertFalse("Initial chain requested details after terminal", requests.any { it.first == "GET /bookings/42/details" })
    }
    @Test fun heldInitialSuccessDone() = heldInitial("done", 200)
    @Test fun heldInitialSuccessCancelled() = heldInitial("cancelled", 200)
    @Test fun heldInitialFailureDone() = heldInitial("done", 503)
    @Test fun heldInitialFailureCancelled() = heldInitial("cancelled", 503)

    private fun heldReconnect(value: String) {
        val cb = mount()
        holdNextHistory = true
        assertTrue(sockets.first().close(1001, "reconnect"))
        waitFor { historyHeld.count == 0L }
        val before = cb.messages.value
        terminal(value)
        releaseHistory.countDown()
        pump(2000)
        assertEquals("Late reconnect history applied after terminal", before, cb.messages.value)
        compose.onAllNodesWithText("late-history").assertCountEquals(0)
    }
    @Test fun heldReconnectHistoryDone() = heldReconnect("done")
    @Test fun heldReconnectHistoryCancelled() = heldReconnect("cancelled")

    private fun waitingFlush(value: String) {
        mount()
        holdPostBooking = 99; postReply = "temporary"
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "other-booking")))
        val first = jobs.async { Outbox.flush(context) }
        waitFor { postHeld.count == 0L }
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "terminal-booking")))
        network(); pump(500)
        terminal(value)
        releasePost.countDown()
        assertFalse(runBlocking { first.await() })
        pump(2000)
        assertEquals("Terminal waiter submitted new actions", 1, postCount())
        assertEquals(1, Outbox.count(context, 42))
        assertEquals(1, Outbox.count(context, 99))
        // Retained system callback must remain harmless after terminal as well.
        network(); pump(1000)
        assertEquals(1, postCount())
    }
    @Test fun waitingUiFlushStopsAtDone() = waitingFlush("done")
    @Test fun waitingUiFlushStopsAtCancelled() = waitingFlush("cancelled")

    private fun activeFlush(value: String) {
        mount()
        holdPostBooking = 42
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "first")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "second")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "other-booking")))
        network(); waitFor { postHeld.count == 0L }
        terminal(value)
        val history = requests.count { it.first == "GET /bookings/42/messages" }
        val roles = requests.count { it.first == "GET /bookings/42/role" }
        releasePost.countDown(); pump(2000)
        assertEquals("Terminal flush submitted another action", 1, postCount())
        assertEquals("Accepted first action not acknowledged", 1, Outbox.count(context, 42))
        assertEquals("Unrelated action lost", 1, Outbox.count(context, 99))
        assertEquals("Terminal flush fetched history", history, requests.count { it.first == "GET /bookings/42/messages" })
        assertEquals("Terminal flush fetched role", roles, requests.count { it.first == "GET /bookings/42/role" })
    }
    @Test fun heldUiPostAcknowledgesFirstAndStopsAtDone() = activeFlush("done")
    @Test fun heldUiPostAcknowledgesFirstAndStopsAtCancelled() = activeFlush("cancelled")

    private fun lateSendFailure(value: String) {
        val cb = mount()
        assertTrue(sockets.first().close(1008, "REST fallback"))
        waitFor { cb.connection.value == false }
        holdPostBooking = 42; postReply = "network"
        tripList().performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("held-send")
        compose.onNodeWithContentDescription("Отправить").performClick()
        waitFor { postHeld.count == 0L }
        terminal(value); ShadowToast.reset()
        releasePost.countDown()
        waitFor { Outbox.count(context, 42) == 1 }
        pump(1000)
        assertNull("Late terminal send emitted a Toast", ShadowToast.getTextOfLatestToast())
        assertFalse(postKeys.single().isNullOrBlank())
        // Reopen the independent disk model and retain the original stable request key.
        val secureField = Outbox::class.java.getDeclaredField("securePrefs").apply { isAccessible = true }
        val disk = (secureField.get(Outbox) as MemoryDiskPreferences).restarted().getString("queue", null)!!
        assertEquals(postKeys.single(), JSONObject("{\"items\":$disk}").getJSONArray("items").getJSONObject(0).getString("request_key"))
    }
    @Test fun lateNetworkFailurePreservesMessageWithoutDoneToast() = lateSendFailure("done")
    @Test fun lateNetworkFailurePreservesMessageWithoutCancelledToast() = lateSendFailure("cancelled")

    @Test fun ordinaryInitialLoadAndRestSendStillWork() {
        val cb = mount()
        assertTrue(sockets.first().close(1008, "REST fallback"))
        waitFor { cb.connection.value == false }
        tripList().performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("ordinary-send")
        compose.onNodeWithContentDescription("Отправить").performClick()
        waitFor { postCount() == 1 && requests.count { it.first == "GET /bookings/42/messages" } >= 2 }
        assertEquals(0, Outbox.count(context, 42)); assertEquals(0, finished)
        assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun ordinaryUiFlushRetainsGlobalQueueContract() {
        mount()
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "own")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "other")))
        network()
        waitFor { postCount() == 2 && Outbox.count(context, 42) == 0 && Outbox.count(context, 99) == 0 && requests.count { it.first == "GET /bookings/42/messages" } >= 2 }
        assertEquals(0, finished)
        assertTrue(postKeys.all { !it.isNullOrBlank() })
    }
    private fun lateSuccessfulSend(value: String) {
        val cb = mount()
        assertTrue(sockets.first().close(1008, "REST fallback"))
        waitFor { cb.connection.value == false }
        holdPostBooking = 42
        tripList().performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextInput("held-success")
        compose.onNodeWithContentDescription("Отправить").performClick()
        waitFor { postHeld.count == 0L }
        terminal(value)
        val histories = requests.count { it.first == "GET /bookings/42/messages" }
        releasePost.countDown(); pump(2000)
        assertEquals(histories, requests.count { it.first == "GET /bookings/42/messages" })
        assertEquals(0, Outbox.count(context, 42))
    }
    @Test fun lateSuccessfulRestSendDoesNotFetchDoneHistory() = lateSuccessfulSend("done")
    @Test fun lateSuccessfulRestSendDoesNotFetchCancelledHistory() = lateSuccessfulSend("cancelled")

}
