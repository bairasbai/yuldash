package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Экран брони пассажира после отказа водителя (приёмка 18.10, п.6; ревью Opus 23.10 просило UI-тест).
 * Пуш говорил «Водитель отклонил бронь», а экран — общее «Бронь отменена». Теперь экран берёт
 * `declined` из деталей брони и говорит то же, что пуш; обычная отмена остаётся «Бронь отменена».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingDeclinedScreenTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    @Volatile private var declined = true

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-declined-screen")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (request.path == "/bookings/73/details") JSONObject()
                        .put("booking_id", 73).put("ride_id", 42).put("role", "passenger").put("status", "cancelled")
                        .put("contact_unlocked", false).put("from_city", "Уфа").put("to_city", "Сибай")
                        .put("depart_at", "2031-02-03T09:00:00").put("price", 300).put("seats", 1)
                        .put("driver_name", "Ринат").put("declined", declined).toString()
                    else "{\"items\":[]}"
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun open() {
        val ride = Ride("42", "Уфа", "Сибай", "завтра", driver = "Ринат", car = "Lada", price = 300, seats = 2,
            rating = 5.0, verified = true, boosted = false)
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
            YuldashTheme { BookingScreen(ride, 73, emptyList(), emptyMap(), {}, {}, {}, {}, bookingStatus = "pending",
                onConfirmRide = { _, _, _, _, _ -> }) }
        } }
    }

    @Test fun passengerSeesThatTheDriverDeclined() {
        open()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Водитель отклонил бронь").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Бронь отменена").assertCountEquals(0)
        compose.onNodeWithText("Водитель не сможет взять тебя", substring = true).assertExists()
    }

    @Test fun ordinaryCancellationStaysCancelled() {
        declined = false
        open()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Бронь отменена").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Водитель отклонил бронь").assertCountEquals(0)
    }
}
