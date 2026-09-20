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

/** Real BookingScreen + HTTP, with a failed local migration cleanup rather than a fake save result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingOfflineSaveTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private var confirmCalls = 0
    @Volatile private var boardingCodeStatus = 200
    @Volatile private var boardingCodeValue = "4821"
    private val warning = "Не удалось сохранить поездку без интернета"

    @Before fun setup() {
        ApiClient.resetForTest()
        TripPassStore.initStores(plain, null)
        val old = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "old"))
        assertTrue(TripPassStore.save(context, old))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertFalse(TripPassStore.save(context, old.copy(bookingId = 42)))
        assertNull(TripPassStore.load(context, 42))
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    val body = when (request.path) {
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":17,"role":"passenger","status":"confirmed","contact_unlocked":true,"from_city":"Город А","to_city":"Город Б","depart_at":"2031-02-03T09:00:00","driver_name":"Серверный водитель","driver_car":"Lada Vesta","driver_phone":"+70000000000","price":700,"seats":1,"pickup":"Точка встречи"}"""
                        "/bookings/42/boarding-code" -> return MockResponse()
                            .setResponseCode(boardingCodeStatus)
                            .setHeader("Content-Type", "application/json")
                            .setBody(JSONObject().put("code", boardingCodeValue).toString())
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
        plain.failRemovalOf = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    private fun render() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(
                    ride = Ride(id = "17", from = "Город А", to = "Город Б", time = "завтра, 09:00",
                        driver = "Исходный водитель", car = "Lada Vesta", price = 700, seats = 1,
                        rating = 4.9, verified = true, boosted = false),
                    bookingId = 42, ads = emptyList(), adStats = emptyMap(), onBack = {},
                    onMessage = {}, onAdImpression = {}, onAdClick = {}, bookingStatus = "confirmed",
                    onConfirmRide = { _, _, _, _, _ -> confirmCalls++ },
                )
            }
        }
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.contains("GET /bookings/42/boarding-code") &&
                compose.onAllNodesWithText("Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Серверный водитель"))
        compose.onNodeWithText("Серверный водитель").assertIsDisplayed()
    }

    private fun show() {
        render()
        assertNull(TripPassStore.load(context, 42))
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.toList())
    }

    private fun showWarning() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(warning))
        compose.onNodeWithText(warning).assertIsDisplayed()
    }

    @Test fun failedOfflineSaveIsVisibleWithoutLosingConfirmedBooking() {
        show()
        showWarning()
        compose.onNodeWithText("Открыть поездку").assertIsDisplayed()
        assertNull(TripPassStore.load(context, 42))
        assertEquals(0, confirmCalls)
    }

    @Test fun retryAfterStorageRecoverySavesPassportWithoutCreatingAnotherBooking() {
        show()
        showWarning()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            requests.count { it == "GET /bookings/42/boarding-code" } == 2 &&
                compose.onAllNodes(hasText("Сохранить ещё раз") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        showWarning()
        assertNull(TripPassStore.load(context, 42))
        plain.failRemovalOf = null
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassStore.load(context, 42)?.boardingCode == "4821" &&
                compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty()
        }
        val saved = requireNotNull(TripPassStore.load(context, 42))
        assertEquals("Серверный водитель", saved.driverName)
        assertEquals("Город А", saved.fromCity)
        compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(0, confirmCalls)
        assertTrue(requests.all { it.startsWith("GET ") })
        assertEquals(1, requests.count { it == "GET /bookings/42/details" })
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("4821", TripPassStore.load(context, 42)?.boardingCode)
        assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
    }

    @Test fun failedBoardingCodeRefreshPreservesSavedCodeAndCanRetry() {
        verifyBoardingCodeFailure(status = 503, code = "4821")
    }

    @Test fun emptyBoardingCodeRefreshPreservesSavedCodeAndCanRetry() {
        verifyBoardingCodeFailure(status = 200, code = "")
    }

    private fun verifyBoardingCodeFailure(status: Int, code: String) {
        plain.failRemovalOf = null
        TripPassStore.initStores(plain, secure)
        val previous = TripPass.fromJson(JSONObject()
            .put("booking_id", 42)
            .put("boarding_code", "4821")
            .put("driver_name", "Предыдущий водитель"))
        assertTrue(TripPassStore.save(context, previous))
        boardingCodeStatus = status
        boardingCodeValue = code

        // render waits until detailsLoading ends, after the initial save attempt completes.
        render()
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code"), requests.toList())
        // This is intentionally the first failure assertion: HTTP failure must not erase a known code.
        assertEquals("4821", TripPassStore.load(context, 42)?.boardingCode)
        showWarning()
        compose.onNodeWithText("Открыть поездку").assertIsDisplayed()

        boardingCodeValue = "5932"
        boardingCodeStatus = 200
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Сохранить ещё раз"))
        compose.onNodeWithText("Сохранить ещё раз").performClick()
        compose.waitUntil(20000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            TripPassStore.load(context, 42)?.boardingCode == "5932" &&
                compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("Серверный водитель", TripPassStore.load(context, 42)?.driverName)
        compose.onNodeWithText(warning).assertDoesNotExist()
        assertEquals(0, confirmCalls)
        assertEquals(listOf("GET /bookings/42/details", "GET /bookings/42/boarding-code",
            "GET /bookings/42/boarding-code"), requests.toList())
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("5932", TripPassStore.load(context, 42)?.boardingCode)
    }
}
