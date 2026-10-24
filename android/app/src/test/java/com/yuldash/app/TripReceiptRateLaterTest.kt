package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
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
 * Приёмка 18.10, п.7: пассажир пропустил экран «Поездка завершена» — и оценить водителя позже было
 * негде. В истории только «Повторить маршрут» и «Квитанция», а квитанция показывала оценку, только
 * если она уже стоит. Теперь в квитанции — «Оценить водителя», пока сервер разрешает (`can_rate`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripReceiptRateLaterTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val reads = CopyOnWriteArrayList<String>()
    private val rateBodies = CopyOnWriteArrayList<String>()
    private var role = "passenger"
    private var canRate = true

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    reads += "${request.method} ${request.path}"
                    val body = when (request.path) {
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"$role","from_city":"Уфа","to_city":"Бирск","depart_at":"2026-10-15T10:00:00Z","seats":1,"amount":300,"pay_method":"cash","paid":false,"driver_name":"Айдар","driver_verified":true,"counterparty_name":"Айдар","my_stars":0,"can_rate":$canRate}"""
                        "/bookings/42/rate" -> { rateBodies += request.body.readUtf8(); """{"ratee_id":7,"rating":4.0,"count":1}""" }
                        else -> "{}"
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.saveToken("local-receipt-rate-later")
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun screen(language: AppLanguage = AppLanguage.Ru) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                if (mounted.value) TripReceiptScreen(bookingId = 42, onBack = {})
            }
        }
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.onAllNodesWithText("Уфа → Бирск").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @Test fun unratedPassengerCanRateFromReceiptAndSeesTheSavedScore() {
        screen()
        compose.onNodeWithText("Оценить водителя").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription("4 из 5").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("Твоя оценка").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(1, rateBodies.size)
        assertEquals(4, JSONObject(rateBodies.single()).getInt("stars"))
        compose.onNodeWithText("4 звезды").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Оценить водителя").assertCountEquals(0)
    }

    @Test fun bashkirReceiptOffersTheSameRating() {
        screen(AppLanguage.Ba)
        compose.onNodeWithText("Йөрөтөүсене баһалау").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("5-тән 5").assertExists()
    }

    @Test fun closedRatingWindowOffersNothing() {
        canRate = false
        screen()
        compose.onAllNodesWithText("Оценить водителя").assertCountEquals(0)
        assertTrue(reads.none { it.startsWith("POST ") })
    }

    @Test fun driverRatesPassengersInTheCabinetNotHere() {
        role = "driver"
        screen()
        compose.onAllNodesWithText("Оценить водителя").assertCountEquals(0)
    }
}
