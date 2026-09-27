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
import com.yuldash.app.data.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real screens + local HTTP, with retained-screen/account transitions ordered by latches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenOwnerTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val orders = AtomicInteger()
    private val messagePosts = AtomicInteger()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private var holdInitialOrder = false
    private var holdSecondOutbox = false
    @Volatile private var currentOrderStatus = "accepted"
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val body = when (request.path) {
                        "/instant/orders/42" -> {
                            val number = orders.incrementAndGet()
                            val snapshot = if (number == 1) "accepted" else currentOrderStatus
                            if (holdInitialOrder && number == 1) awaitRelease()
                            """{"id":42,"status":"$snapshot","role":"passenger"}"""
                        }
                        "/bookings/42/messages" -> {
                            if (request.method == "POST") {
                                if (holdSecondOutbox && messagePosts.incrementAndGet() == 2) awaitRelease()
                                "{}"
                            } else """{"items":[]}"""
                        }
                        "/instant/orders/42/messages", "/parcels/42/messages" -> """{"items":[]}"""
                        "/bookings/42/role" -> """{"role":"passenger","status":"confirmed","driver_phase":"waiting"}"""
                        "/bookings/42/boarding-code" -> """{"code":"5678"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"role":"passenger","status":"confirmed"}"""
                        "/parcels/mine" -> """{"items":[{"id":42,"status":"in_transit"}]}"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 45000
        ApiClient.init(context)
        clearSession()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        ApiClient.saveToken("local-chat-A")
    }

    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false; owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        clearSession()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun taxiInitialChainDoesNotStartMessagesAsNewAccount() {
        holdInitialOrder = true
        mount("taxi")
        awaitHeld()
        switchAccount()
        release.countDown()
        pump(1000)
        assertTrue(requests.none { it.path == "/instant/orders/42/messages" })
        assertNoBRequests()
    }

    @Test fun retainedTaxiDraftCannotFallBackToNewAccountRest() = checkRetainedDraft("taxi")
    @Test fun retainedParcelDraftCannotFallBackToNewAccountRest() = checkRetainedDraft("parcel")

    private fun checkRetainedDraft(channel: String) {
        mount(channel)
        val path = if (channel == "taxi") "/instant/orders/42/messages" else "/parcels/42/messages"
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); requests.any { it.path == path } }
        pump(500)
        compose.onNode(hasSetTextAction()).performTextInput("A private draft")
        switchAccount()
        compose.onNodeWithContentDescription("Отправить").performClick()
        pump(500)
        assertTrue(requests.none { it.method == "POST" && it.path == path })
        assertNoBRequests()
        assertEquals(0, Outbox.count(context, 42))
    }

    @Test fun lateTaxiInitialStatusCannotReopenCompletedChat() {
        holdInitialOrder = true
        mount("taxi")
        awaitHeld()
        currentOrderStatus = "done"
        pump(16000)
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); orders.get() >= 2 }
        pump(500)
        compose.onNodeWithText("Поездка завершена — чат доступен только для чтения").assertIsDisplayed()
        release.countDown()
        pump(1000)
        compose.onNodeWithText("Поездка завершена — чат доступен только для чтения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
    }

    @Test fun partialOutboxFlushCannotStartFollowupRestAsNewAccount() {
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "first A")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "second A")))
        holdSecondOutbox = true
        mount("booking")
        awaitHeld()
        assertEquals(2, messagePosts.get()) // First action already succeeded: flush changed=true.
        switchAccount()
        release.countDown()
        pump(1000)
        assertNoBRequests()
    }

    @Test fun taxiPauseResumeDoesNotRestartPollingAsNewAccount() = checkResume("taxi", 16000)
    @Test fun parcelPauseResumeDoesNotRestartPollingAsNewAccount() = checkResume("parcel", 21000)

    private fun checkResume(channel: String, period: Long) {
        mount(channel)
        pump(500)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        val before = requests.count { it.path == "/instant/orders/42" || it.path == "/parcels/mine" }
        pump(period)
        assertEquals(before, requests.count { it.path == "/instant/orders/42" || it.path == "/parcels/mine" })
        switchAccount()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        pump(period)
        assertNoBRequests()
    }

    private fun mount(channel: String) {
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner) {
                when (channel) {
                    "taxi" -> InstantChatScreen(42, onBack = {})
                    "parcel" -> ParcelChatScreen(42, peerIsCourier = true, parcelStatus = "in_transit", onBack = {})
                    else -> ActiveTripScreen(null, emptyList(), 42, onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
    }

    private fun switchAccount() = compose.runOnIdle { clearSession(); ApiClient.saveToken("local-chat-B") }
    private fun assertNoBRequests() = assertTrue("Old screen issued HTTP with B credentials",
        requests.none { it.getHeader("Authorization") == "Bearer local-chat-B" })
    private fun awaitRelease() {
        held.countDown()
        check(release.await(30, TimeUnit.SECONDS)) { "Controlled response was not released" }
    }
    private fun awaitHeld() = compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); held.count == 0L }
    private fun pump(millis: Long) {
        repeat((millis / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun clearSession() {
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)
    }
}
