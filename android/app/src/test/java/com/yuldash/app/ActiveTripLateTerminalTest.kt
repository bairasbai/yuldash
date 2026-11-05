package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A successful code response arrives only after remote completion has durably removed the pass. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripLateTerminalTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private val codeRequested = CountDownLatch(1)
    private val releaseCode = CountDownLatch(1)
    private val detailsRequested = CountDownLatch(1)
    private val completeTrip = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<String>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private val phone = "+7 900 000-00-42"
    private lateinit var server: MockWebServer
    @Volatile private var seeding = true

    @Before fun setup() {
        ApiClient.resetForTest()
        TripPassStore.initStores(plain, secure)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (request.method != "GET") return MockResponse().setResponseCode(405)
                    if (seeding || request.path?.startsWith("/bookings/99/") == true) {
                        val id = request.path.orEmpty().split('/').getOrNull(2)?.toIntOrNull() ?: 42
                        val body = if (request.path?.endsWith("/boarding-code") == true)
                            JSONObject().put("code", if (id == 99) "unrelated" else "1234")
                        else JSONObject().put("booking_id", id).put("ride_id", id + 1000).put("role", "passenger")
                            .put("status", "confirmed").put("contact_unlocked", true).put("from_city", "Город А").put("to_city", "Город Б")
                        return MockResponse().setBody(body.toString())
                    }
                    val body = when (request.path) {
                        "/bookings/42/role" -> {
                            // Terminal follows the UI barrier: details were applied while code is held.
                            if (!codeRequested.await(30, TimeUnit.SECONDS) || !completeTrip.await(30, TimeUnit.SECONDS)) {
                                fixtureFailures.add("Ready details / outstanding code barrier did not complete")
                                return MockResponse().setResponseCode(503)
                            }
                            """{"role":"passenger","status":"done","driver_phase":"departed"}"""
                        }
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> {
                            codeRequested.countDown()
                            if (!releaseCode.await(30, TimeUnit.SECONDS)) {
                                fixtureFailures.add("Late response was not released")
                                return MockResponse().setResponseCode(503)
                            }
                            """{"code":"5678"}"""
                        }
                        "/bookings/42/details" -> {
                            // Details no longer wait for the outstanding boarding-code request.
                            detailsRequested.countDown()
                            """{"booking_id":42,"ride_id":1042,"role":"passenger","status":"confirmed","contact_unlocked":true,"driver_phone":"$phone","from_city":"Город А","to_city":"Город Б"}"""
                        }
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":1042,"role":"passenger","from_city":"Город А","to_city":"Город Б","counterparty_name":"Серверный водитель","amount":700,"paid":true}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        TripPassAuthorityFixture.installSession(server.url("/").toString().trimEnd('/'))
        ApiClient.testTimeoutMs = 45000 // A controlled latch, not an artificial network timeout.
        runBlocking {
            ApiClient.getBookingDetails(42).getOrThrow(); ApiClient.getBoardingCode(42).getOrThrow()
            assertTrue(TripPassStore.save(context, requireNotNull(OfflineTripSnapshots.privatePass(42))))
            ApiClient.getBookingDetails(99).getOrThrow(); ApiClient.getBoardingCode(99).getOrThrow()
            assertTrue(TripPassStore.save(context, requireNotNull(OfflineTripSnapshots.privatePass(99))))
            assertEquals("1234", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
            assertEquals("unrelated", TripPassAuthorityFixture.durable(secure, 99)?.boardingCode)
            // Keep the original late-response recorder scoped to that phase. Its initial DETAILS
            // is an actual current HTTP preload; the UI may reuse it but cannot reuse its old code.
            requests.clear(); seeding = false
            ApiClient.getBookingDetails(42).getOrThrow()
        }
    }

    @After fun cleanup() {
        // Release both waiters even when an assertion fails; shutdown must never hang on a fixture.
        releaseCode.countDown()
        codeRequested.countDown()
        completeTrip.countDown()
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        if (::server.isInitialized) server.shutdown()
    }

    @Test fun delayedSuccessfulCodeCannotReviveCompletedTripPassport() {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
        // Реальный барьер применения details: телефон уже показан экраном, код всё ещё удержан.
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            codeRequested.count == 0L && detailsRequested.count == 0L && runCatching {
                compose.onNode(SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                    .performScrollToNode(hasText(phone))
                compose.onNodeWithText(phone).assertIsDisplayed()
            }.isSuccess
        }
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        completeTrip.countDown()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            codeRequested.count == 0L &&
                compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isNotEmpty() &&
                !secure.restarted().contains("pass_42")
        }
        assertEquals("The successful code must still be held after durable deletion", 1L, releaseCode.count)
        assertEquals("Independent details completed before terminal", 0L, detailsRequested.count)
        assertNull(TripPassStore.load(context, 42))

        releaseCode.countDown()
        // Terminal must ignore the late code and must not start another details request.
        // Bounded main/IO observation; absence of a follow-up is not an unbounded join proof.
        repeat(20) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(10)
        }
        compose.waitForIdle()
        assertEquals("Closed trip must not request details after the late code", 1,
            requests.count { it == "GET /bookings/42/details" })
        compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
        compose.onAllNodesWithText("5678").assertCountEquals(0)
        compose.onAllNodesWithText("1234").assertCountEquals(0)
        assertNull(TripPassStore.load(context, 42))
        assertFalse(secure.restarted().contains("pass_42"))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        assertEquals("unrelated", TripPassAuthorityFixture.durable(secure, 99)?.boardingCode)
        assertEquals(1, requests.count { it == "GET /bookings/42/boarding-code" })
        assertFalse(requests.any { it.startsWith("POST ") })
        assertTrue("Fixture timeouts: $fixtureFailures", fixtureFailures.isEmpty())

        compose.runOnIdle { mounted.value = false }
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
    }
}
