package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import okhttp3.mockwebserver.*
import com.yuldash.app.data.ChatSocket
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.poisonOfflinePassportFixture03
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.net.InetAddress
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PATH-03: actual driver CTA, authoritative HTTP phases, completion and retry.
 * Since 17.10.2026 the driver marks boarding: «Я выехал» → «Подъезжаю» → «Пассажир сел» → «Завершить»;
 * a driver who never marked boarding still finishes with «Завершить поездку» next to «Пассажир сел».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareDriverExecutionJourneyTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val requestJobs = RequestJobs()
    private var contentSet = false
    private var serverStarted = false
    private var sessionInstalled = false
    @get:Rule val compose = createComposeRule(effectContext = requestJobs)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(false)
    private val posted = CopyOnWriteArrayList<String>()
    private val server = MockWebServer()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private lateinit var legacyBytes: String
    private val ownTrace: (String) -> Unit = { if (it.startsWith("вошли в вызов ")) requestJobs.record() }
    private val onboardMark = "2026-10-20T09:00:00Z"
    @Volatile private var phase = ""
    @Volatile private var bookingStatus = "confirmed"
    @Volatile private var rejectedStatus = ""
    @Volatile private var refusal = 409

    @Before fun prepare() {
        cleanupGuard.requireHealthy()
        check(ApiClient.testTrace == null)
        TripPassStore.initStores(plain, secure)
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/bookings/42/") == true &&
                    request.getHeader("Authorization") != "Bearer " + TripPassAuthorityFixture.jwt(7)) return MockResponse().setResponseCode(403)
                if (request.path == "/bookings/42/driver-status") {
                    val status = JSONObject(request.body.readUtf8()).getString("status")
                    posted += status
                    if (status == rejectedStatus) return MockResponse().setResponseCode(refusal)
                        .setBody("""{"detail":{"ru":"Переход отклонён","ba":"Күсеү кире ҡағылды"}}""")
                    // As the server: «boarded» moves the booking, not the driver's phase.
                    when (status) {
                        "done" -> bookingStatus = "done"
                        "boarded" -> bookingStatus = "onboard"
                        else -> phase = status
                    }
                    val ack = when (status) {
                        "done" -> JSONObject().put("ok", true).put("status", "done")
                        "boarded" -> JSONObject().put("ok", true).put("status", "onboard").put("onboard_at", onboardMark)
                            .put("boarding_undo_sec", 0).put("boarding_undo_pending", false).put("boarding_undo_wait_sec", 0)
                        else -> JSONObject().put("ok", true).put("driver_phase", phase)
                    }
                    return MockResponse().setBody(ack.toString())
                }
                val body = when (request.path) {
                    "/bookings/42/role" -> JSONObject().put("role", "driver").put("status", bookingStatus).put("driver_phase", phase)
                        .put("onboard_at", if (bookingStatus == "onboard") onboardMark else JSONObject.NULL).put("boarding_undo_sec", 0).toString()
                    "/bookings/42/messages" -> """{"items":[]}"""
                    "/bookings/42/boarding-code" -> """{"code":"1234"}"""
                    "/bookings/42/details" -> JSONObject().put("booking_id", 42).put("ride_id", 7).put("role", "driver")
                        .put("status", bookingStatus).put("contact_unlocked", true).put("pay_method", "negotiate")
                        .put("onboard_at", if (bookingStatus == "onboard") onboardMark else JSONObject.NULL).toString()
                    "/trips/42/receipt" -> """{"booking_id":42,"ride_id":7,"role":"driver","amount":700,"paid":false}"""
                    "/bookings/42/tip" -> """{"already_thanked":false}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{}")
                }
                return MockResponse().setBody(body)
            }
        }
        server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        serverStarted = true
        TripPassAuthorityFixture.installSession(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'), 7)
        sessionInstalled = true
        ApiClient.testTrace = ownTrace
        // Old persisted bytes are untrusted legacy data, never a driver's passenger passport.
        legacyBytes = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old")).toJson().toString()
        assertTrue(secure.edit().putString("pass_42", legacyBytes).commit())
        assertLegacyCopyPresent()
    }

    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            if (contentSet) {
                compose.runOnIdle { mounted.value = false }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            requestJobs.awaitCompleted(if (serverStarted) server.port else null) { compose.mainClock.advanceTimeByFrame() }
            if (sessionInstalled) {
                ApiClient.logout()
            }
            finishOfflineWriteFixture02(cleanupGuard) {
                check(ApiClient.testTrace == null || ApiClient.testTrace === ownTrace)
                if (serverStarted) {
                    server.shutdown()
                }
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                TripPassStore.initStores(MemoryDiskPreferences(), null)
                Outbox.initStores(MemoryDiskPreferences(), null)
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        } finally {
            if (ApiClient.testTrace === ownTrace) {
                ApiClient.testTrace = null
            }
        }
    }
    /** Observes real API jobs; no Job/dispatcher replacement and no shared-client shutdown. */
    private class RequestJobs : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RequestJobs>
        private val current = ThreadLocal<Job?>()
        private val started = CopyOnWriteArrayList<Job>()
        @Volatile private var missingParent = false
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        fun record() { current.get()?.let { started += it; val caller = it.parent; if (caller == null) missingParent = true else started += caller } }
        fun awaitCompleted(port: Int?, advanceFrame: () -> Unit) = runBlocking { withTimeout(5_000) {
            check(!missingParent) { "Owned caller completion unavailable" }
            val lazyClient = ChatSocket::class.java.getDeclaredField("client\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
            val client = lazyClient.takeIf { it.isInitialized() }?.value as? okhttp3.OkHttpClient
            while (started.any { !it.isCompleted } || (client?.dispatcher?.let { it.runningCalls() + it.queuedCalls() }.orEmpty()
                    .any { it.request().url.host == "127.0.0.1" && it.request().url.port == port })) {
                Snapshot.sendApplyNotifications()
                advanceFrame()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        } }
    }
    private fun assertLegacyCopyPresent() {
        assertEquals(legacyBytes, secure.restarted().getString("pass_42", null))
        assertFalse(plain.restarted().contains("pass_42"))
        assertNull(OfflineTripSnapshots.privatePass(42)); assertNull(TripPassStore.load(context, 42))
    }
    private fun awaitLegacyCopyRemoved() {
        compose.waitUntil(10000) { !secure.restarted().contains("pass_42") && !plain.restarted().contains("pass_42") }
        assertNull(OfflineTripSnapshots.privatePass(42)); assertNull(TripPassStore.load(context, 42))
    }
    private fun mount(text: String) {
        mounted.value = true
        contentSet = true
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                    onBack = {}, onTripEnd = {}, onSos = {})
            }
        }
        waitFor(text)
    }
    private fun waitFor(text: String) = compose.waitUntil(10000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun pump() {
        repeat(15) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun openFinishConfirmation(label: String = "Завершить") {
        compose.onNodeWithText(label).performClick()
        compose.onNodeWithText("Завершить поездку?").assertIsDisplayed()
    }

    @Test fun driverExecutesEveryPhaseAndConfirmsCompletionBeforeTheOnlyDonePost() {
        mount("Я выехал")
        compose.onNodeWithText("Я выехал").performClick()
        waitFor("Подъезжаю")
        assertEquals(listOf("departed"), posted.toList())
        compose.onNodeWithText("Подъезжаю").performClick()
        waitFor("Пассажир сел")
        assertEquals(listOf("departed", "arriving"), posted.toList())
        compose.onNodeWithText("Пассажир сел").performClick()
        waitFor("Завершить")
        openFinishConfirmation()
        assertEquals(listOf("departed", "arriving", "boarded"), posted.toList())
        compose.onNodeWithText("Не завершать").performClick()
        assertLegacyCopyPresent()
        assertEquals(3, posted.size)
        openFinishConfirmation()
        assertLegacyCopyPresent()
        compose.onNodeWithText("Да, завершить").performClick()
        waitFor("Поездка завершена")
        awaitLegacyCopyRemoved()
        assertEquals(listOf("departed", "arriving", "boarded", "done"), posted.toList())
    }

    @Test fun rejectedArrivalKeepsItsPreviousPhaseAndRetriesTheSameTransition() {
        phase = "departed"
        rejectedStatus = "arriving"
        mount("Подъезжаю")
        compose.onNodeWithText("Подъезжаю").performClick()
        pump()
        compose.onNodeWithText("Подъезжаю").assertIsDisplayed()
        assertEquals("departed", phase)
        assertEquals(0, Outbox.count(context, 42))
        rejectedStatus = ""
        compose.onNodeWithText("Подъезжаю").performClick()
        waitFor("Пассажир сел")
        assertEquals(listOf("arriving", "arriving"), posted.toList())
        assertEquals("arriving", phase)
    }

    /** Boarding never marked: the driver still closes the trip himself, next to «Пассажир сел». */
    @Test fun refusedCompletionKeepsTheLegacyCopyAndRetriesOnlyAfterConfirmation() {
        phase = "arriving"
        rejectedStatus = "done"
        refusal = 503
        mount("Завершить поездку")
        compose.onNodeWithText("Пассажир сел").assertIsDisplayed()
        openFinishConfirmation("Завершить поездку")
        assertEquals(0, posted.size)
        assertLegacyCopyPresent()
        compose.onNodeWithText("Да, завершить").performClick()
        pump()
        compose.onNodeWithText("Завершить поездку").assertIsDisplayed()
        compose.onAllNodesWithText("Поездка завершена").assertCountEquals(0)
        assertLegacyCopyPresent()
        assertEquals(0, Outbox.count(context, 42))
        rejectedStatus = ""
        openFinishConfirmation("Завершить поездку")
        assertEquals(listOf("done"), posted.toList())
        assertLegacyCopyPresent()
        compose.onNodeWithText("Да, завершить").performClick()
        waitFor("Поездка завершена")
        awaitLegacyCopyRemoved()
        assertEquals(listOf("done", "done"), posted.toList())
    }
}
