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

/** Actual Compose/HTTP/WS. Saved UI callbacks are explicitly injected to hold the main-thread hop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripChatUiBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val auth = LinkedBlockingQueue<String>()
    private val sent = LinkedBlockingQueue<String>()
    private val closed = AtomicInteger()
    private val refreshed = AtomicInteger()
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
    private fun mount(): Callbacks {
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
        assertEquals(tokenA, auth.poll(10, TimeUnit.SECONDS))
        waitFor { requests.any { it.first == "GET /bookings/42/details" } && compose.onAllNodesWithText(if (language == AppLanguage.Ba) "Мин ултырҙым" else "Я сел").fetchSemanticsNodes().isNotEmpty() }
        ownedNetworkCallbacks = Shadows.shadowOf(connectivity).networkCallbacks.filter { it !in callbacksBefore }
        assertEquals(1, ownedNetworkCallbacks.size)
        pump(500)
        compose.onNodeWithText(if (language == AppLanguage.Ba) "Яҙырға" else "Написать").performClick()
        pump(1000) // Finish the real hero scroll animation before positioning the longer BA content.
        tripList().performScrollToNode(hasText("private-history-A"))
        waitFor { compose.onAllNodesWithText("private-history-A").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("private-history-A").assertIsDisplayed()
        return callbacks(screenSocket(before))
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
    private fun switchAccount() = runBlocking {
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "B").getOrThrow()
    }
    private fun queuedOnMain(callback: () -> Unit, beforeMainResumes: () -> Unit = {}) {
        // This block runs on main. A worker only queues the UI coroutine; join proves that hop was scheduled.
        compose.runOnIdle {
            val worker = thread(start = true, name = "b02-queued-ui-callback") { callback() }
            worker.join(5000)
            assertFalse("Callback worker did not finish", worker.isAlive)
            beforeMainResumes()
        }
    }
    private fun incoming(text: String) = ChatSocket.Incoming(77, 22, text, "")
    private fun assertOldScreenHidden() {
        compose.onAllNodesWithText("private-history-A").assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        compose.onAllNodesWithText("private-draft-A").assertCountEquals(0)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodesWithText(if (language == AppLanguage.Ba) "Мин ултырҙым" else "Я сел").assertCountEquals(0)
        assertEquals("Close callback must fire once while parent keeps this route mounted", 1, backs)
        val currentCallbacks = Shadows.shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks
        assertTrue("Old screen kept its system network subscription", ownedNetworkCallbacks.none { it in currentCallbacks })
        assertFalse("Old screen requested the old booking with B's bearer", requests.any { it.first.contains("/bookings/42/") && it.second == "Bearer $tokenB" })
    }

    @Test fun logoutRemovesPopulatedRussianChatAndDraftWithoutANetworkEvent() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        compose.runOnIdle { ApiClient.logout() }
        pump()
        assertOldScreenHidden()
        waitFor { closed.get() == 1 }
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        pump()
        assertEquals(1, backs)
    }
    @Test fun accountSwitchRemovesPopulatedBashkirChatAndDraftWithoutANetworkEvent() {
        language = AppLanguage.Ba
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        compose.runOnIdle { switchAccount() }
        pump()
        assertOldScreenHidden()
        waitFor { closed.get() == 1 }
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "private-queue-B")))
        compose.runOnIdle { ownedNetworkCallbacks.single().onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(123)) }
        pump()
        assertEquals(1, Outbox.count(context, 99))
        assertFalse(requests.any { it.first == "POST /bookings/99/messages" })
    }
    @Test fun queuedMessageCannotRestoreTheOldScreenAfterAccountSwitch() {
        val callback = mount()
        val before = callback.messages.value
        queuedOnMain({ callback.message(incoming("private-late-A")) }, ::switchAccount)
        pump()
        assertEquals(before, callback.messages.value)
        compose.onAllNodesWithText("private-late-A").assertCountEquals(0)
        assertOldScreenHidden()
    }
    @Test fun queuedConnectionCannotStartHistoryAsTheNewAccount() {
        val callback = mount()
        queuedOnMain({ callback.connected(false) })
        waitFor { callback.connection.value == false }
        queuedOnMain({ callback.connected(true) }, ::switchAccount)
        pump()
        assertFalse(callback.connection.value as Boolean)
        assertOldScreenHidden()
    }
    @Test fun queuedRejectionCannotPublishOldFailuresOrToastAfterAccountSwitch() {
        val callback = mount()
        val before = callback.failures.value
        ShadowToast.reset()
        queuedOnMain({ callback.rejected(-77, "old refusal") }, ::switchAccount)
        pump()
        assertEquals(before, callback.failures.value)
        assertNull(ShadowToast.getTextOfLatestToast())
        assertOldScreenHidden()
    }
    @Test fun completedTripRejectsSavedMessageConnectionAndRejectionCallbacks() = terminalCallbacks("done")
    @Test fun cancelledTripRejectsSavedMessageConnectionAndRejectionCallbacks() = terminalCallbacks("cancelled")
    private fun terminalCallbacks(status: String) {
        val callback = mount()
        remoteStatus = status
        pump(14000)
        if (status == "done") compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        else assertEquals(1, finished)
        val messagesBefore = callback.messages.value
        val failuresBefore = callback.failures.value
        ShadowToast.reset()
        queuedOnMain({ callback.message(incoming("private-after-terminal")); callback.connected(true); callback.rejected(-77, "late refusal") })
        pump()
        assertEquals(messagesBefore, callback.messages.value)
        assertEquals(failuresBefore, callback.failures.value)
        assertFalse(callback.connection.value as Boolean)
        assertNull(ShadowToast.getTextOfLatestToast())
        waitFor { closed.get() == 1 }
        assertNull(TripPassStore.load(context, 42))
    }
    @Test fun sameSessionCallbacksStillRenderSendAndMarkRejectedMessages() {
        val callback = mount()
        queuedOnMain({ callback.message(incoming("current-message")) })
        waitFor { compose.onAllNodesWithText("current-message").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("current-message").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("current-reply")
        compose.onNodeWithContentDescription("Отправить").performClick()
        assertEquals("current-reply", sent.poll(10, TimeUnit.SECONDS))
        queuedOnMain({ callback.rejected(-2, "local refusal") })
        pump()
        assertTrue((callback.failures.value as Set<*>).contains(-2))
        assertNotNull(ShadowToast.getTextOfLatestToast())
        assertEquals(0, backs)
    }
    @Test fun actualRefreshKeepsTheSameScreenHistoryDraftAndConnection() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        val generation = ApiClient.queueSessionGeneration()
        runBlocking { ApiClient.getContacts().getOrThrow() }
        assertEquals(tokenA2, ApiClient.currentToken())
        assertEquals(generation, ApiClient.queueSessionGeneration())
        assertEquals(generation, ApiClient.sessionChanges.value)
        pump()
        compose.onNodeWithText("private-history-A").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("private-draft-A")
        assertEquals(0, backs)
        assertEquals(0, closed.get())
        assertEquals(1, refreshed.get())
    }
    @Test fun directTokenReplacementNotifiesAndClosesTheMountedScreen() {
        mount()
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        assertEquals(ApiClient.queueSessionGeneration(), ApiClient.sessionChanges.value)
        pump()
        assertOldScreenHidden()
    }
    @Test fun authStorageReinitializationNotifiesAndClosesThePreviousGeneration() {
        mount()
        compose.runOnIdle { ApiClient.init(context) }
        assertEquals(ApiClient.queueSessionGeneration(), ApiClient.sessionChanges.value)
        pump()
        assertOldScreenHidden()
    }
    @Test fun reopenedSameBookingBelongsToBAndDoesNotRestoreAsHistoryOrDraft() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("private-draft-A")
        compose.runOnIdle { switchAccount() }
        assertEquals(ApiClient.queueSessionGeneration(), ApiClient.sessionChanges.value)
        pump()
        assertOldScreenHidden()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        waitFor { auth.peek() == tokenB }
        assertEquals(tokenB, auth.poll(10, TimeUnit.SECONDS))
        waitFor { compose.onAllNodesWithText("Написать").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Написать").performClick()
        pump(1000)
        tripList().performScrollToNode(hasText("private-history-B"))
        compose.onNodeWithText("private-history-B").assertIsDisplayed()
        compose.onAllNodesWithText("private-history-A").assertCountEquals(0)
        compose.onNode(hasSetTextAction()).assertTextEquals("")
        assertTrue(sockets.last().send("""{"type":"message","id":88,"sender_id":22,"text":"wire-message-B"}"""))
        waitFor { compose.onAllNodesWithText("wire-message-B").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("wire-message-B").assertIsDisplayed()
        assertEquals(1, backs)
    }
}
