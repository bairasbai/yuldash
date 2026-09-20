package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
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

/** Loading terminal booking details must not recreate a previously removed offline passport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingTerminalPassTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    @Volatile private var terminalStatus = "done"

    @Before fun setup() {
        ApiClient.resetForTest()
        TripPassStore.initStores(plain, secure)
        val previous = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "old"))
        assertTrue(TripPassStore.save(context, previous))
        assertTrue(TripPassStore.save(context, previous.copy(bookingId = 99, boardingCode = "unrelated")))
        assertTrue(TripPassStore.remove(context, 42))
        assertNull(TripPassStore.load(context, 42))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val body = when (request.path) {
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":17,"role":"passenger","status":"$terminalStatus","contact_unlocked":true,"from_city":"Город А","to_city":"Город Б","depart_at":"2031-02-03T09:00:00","driver_name":"Серверный водитель","driver_car":"Lada Vesta","driver_phone":"+70000000000","price":700,"seats":1,"pickup":"Точка встречи"}"""
                        "/bookings/42/boarding-code" -> """{"code":"4821"}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    @Test fun completedBookingDoesNotFetchCodeOrRecreateRemovedPassport() {
        verifyTerminalBooking("done")
    }

    @Test fun cancelledBookingDoesNotFetchCodeOrRecreateRemovedPassport() {
        verifyTerminalBooking("cancelled")
    }

    private fun verifyTerminalBooking(status: String) {
        terminalStatus = status
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(
                    ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "завтра, 09:00",
                        driver = "Исходный водитель", car = "Lada Vesta", price = 700, seats = 1,
                        rating = 4.9, verified = true, boosted = false),
                    bookingId = 42, ads = emptyList(), adStats = emptyMap(), onBack = {},
                    onMessage = {}, onAdImpression = {}, onAdClick = {}, bookingStatus = status,
                    onConfirmRide = { _, _, _, _, _ -> fail("Opening history must not create a booking") },
                )
            }
        }
        // detailsLoading ends only after the code/save branch returns. Require server-derived UI
        // as well, so an initially absent loading node cannot make this check pass prematurely.
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /bookings/42/details") &&
                compose.onAllNodesWithText("Серверный водитель").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Серверный водитель"))
        compose.onNodeWithText("Серверный водитель").assertIsDisplayed()
        compose.waitForIdle()
        // Check network independently: even a storage tombstone must not conceal this UI bug.
        assertEquals("Terminal details must not request a fresh boarding code",
            listOf("GET /bookings/42/details"), requests.toList())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
        assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
    }
}
