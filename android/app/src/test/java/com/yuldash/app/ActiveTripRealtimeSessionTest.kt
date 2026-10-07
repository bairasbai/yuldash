package com.yuldash.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
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
import java.util.concurrent.TimeUnit

/** QA-B02-003: actual ActiveTrip, controlled HTTP/WS and lifecycle transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripRealtimeSessionTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val authenticated = CountDownLatch(1)
    private val secondPost = CountDownLatch(1)
    private val releasePost = CountDownLatch(1)
    private val postReturned = CountDownLatch(1)
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    @Volatile private var posted = 0
    @Volatile private var holdSecondPost = false
    @Volatile private var holdHistory = false
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path" to request.getHeader("Authorization")
                    if (path == "/ws/bookings/42") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                        override fun onMessage(webSocket: WebSocket, text: String) { authenticated.countDown() }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                    if (request.method == "POST") {
                        if (path == "/bookings/42/messages") {
                            posted++
                            if (holdSecondPost && posted == 2) {
                                secondPost.countDown()
                                if (!releasePost.await(30, TimeUnit.SECONDS)) fixtureFailures += "Held POST not released"
                                postReturned.countDown()
                            }
                        }
                        return MockResponse().setBody("{}")
                    }
                    val body = when (path) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"departed"}"""
                        "/bookings/42/messages" -> {
                            if (holdHistory) {
                                historyHeld.countDown()
                                if (!releaseHistory.await(30, TimeUnit.SECONDS)) fixtureFailures += "Held history not released"
                                """{"items":[{"id":77,"sender_id":22,"text":"late-reconnect-A"}]}"""
                            } else """{"items":[]}"""
                        }
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
        releasePost.countDown()
        releaseHistory.countDown()
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

    private fun mount(): List<ConnectivityManager.NetworkCallback> {
        val before = Shadows.shadowOf(connectivity).networkCallbacks.toSet()
        mounted.value = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { authenticated.count == 0L && requests.any { it.first == "GET /bookings/42/details" } }
        waitFor { compose.onAllNodesWithText("Я сел").fetchSemanticsNodes().isNotEmpty() }
        pump(500)
        return Shadows.shadowOf(connectivity).networkCallbacks.filter { it !in before }
    }

    private fun switchAccount() = compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken(tokenB) }
    private fun polls() = requests.count { it.first == "GET /bookings/42/role" }
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15000) {
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
    private fun assertNoOldBookingRequestAsB() {
        val leaked = requests.filter { it.first.contains("/bookings/42/") && it.second == "Bearer $tokenB" }
        assertTrue("Old screen made requests as B: $leaked", leaked.isEmpty())
        assertTrue("Fixture failures: $fixtureFailures", fixtureFailures.isEmpty())
    }

    @Test fun lateSocketFrameIsNotRenderedAfterAccountSwitch() {
        mount()
        compose.onNodeWithText("Написать").performClick()
        pump(1000)
        assertTrue(sockets.first().send("""{"type":"message","id":1,"sender_id":22,"text":"before-switch"}"""))
        waitFor { compose.onAllNodesWithText("before-switch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("before-switch").assertIsDisplayed()
        switchAccount()
        // Closing the old UI also closes its socket; send may already be refused locally.
        sockets.first().send("""{"type":"message","id":2,"sender_id":22,"text":"private-A-after-switch"}""")
        pump(1500)
        compose.onAllNodesWithText("before-switch").assertCountEquals(0)
        compose.onAllNodesWithText("private-A-after-switch").assertCountEquals(0)
    }

    @Test fun backgroundPollingStopsAndSameSessionResumesImmediately() {
        mount()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        pump(1000)
        val stopped = polls()
        pump(14000)
        assertEquals("Polling continued in background", stopped, polls())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor { polls() > stopped }
        assertTrue(requests.filter { it.first == "GET /bookings/42/role" }.all { it.second == "Bearer $tokenA" })
    }

    @Test fun lifecycleResumeOfOldScreenDoesNotPollWithNewAccount() {
        mount()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        pump(500)
        val stopped = polls()
        switchAccount()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        pump(14000)
        assertEquals(stopped, polls())
        assertNoOldBookingRequestAsB()
    }

    @Test fun oldNetworkCallbackDoesNotFlushTheNewAccountsQueue() {
        val callbacks = mount()
        assertEquals("Expected the screen's default network callback", 1, callbacks.size)
        switchAccount()
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "B-private-queue")))
        compose.runOnIdle { callbacks.single().onAvailable(org.robolectric.shadows.ShadowNetwork.newInstance(123)) }
        pump(2000)
        assertEquals("Old screen drained B's queue", 1, Outbox.count(context, 99))
        assertFalse(requests.any { it.first == "POST /bookings/99/messages" })
        assertNoOldBookingRequestAsB()
    }

    @Test fun changedFlushDoesNotStartOldBookingFollowupsAfterAccountSwitch() {
        holdSecondPost = true
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "first-A")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "second-A")))
        mount()
        waitFor { secondPost.count == 0L }
        switchAccount()
        releasePost.countDown()
        assertTrue(postReturned.await(5, TimeUnit.SECONDS))
        pump(2000)
        assertNoOldBookingRequestAsB()
    }

    @Test fun staleBoardingButtonDoesNotWriteAsNewAccount() {
        mount()
        val oldClick = compose.onNodeWithText("Я сел").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        switchAccount()
        compose.runOnIdle { oldClick() }
        pump(1500)
        assertNoOldBookingRequestAsB()
        compose.onAllNodesWithText("Я сел").assertCountEquals(0)
    }

    @Test fun lateReconnectHistoryIsIgnoredAfterAccountSwitch() {
        mount()
        compose.onNodeWithText("Написать").performClick()
        pump(1000)
        holdHistory = true
        assertTrue(sockets.first().close(1001, "restart"))
        waitFor { historyHeld.count == 0L }
        assertTrue(sockets.last().send("""{"type":"message","id":76,"sender_id":22,"text":"visible-reconnect-marker"}"""))
        waitFor { compose.onAllNodesWithText("visible-reconnect-marker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("visible-reconnect-marker").assertIsDisplayed()
        switchAccount()
        releaseHistory.countDown()
        pump(2000)
        compose.onAllNodesWithText("visible-reconnect-marker").assertCountEquals(0)
        compose.onAllNodesWithText("late-reconnect-A").assertCountEquals(0)
        assertNoOldBookingRequestAsB()
    }
}
