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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripReceiptRatingTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val reads = CopyOnWriteArrayList<String>()
    private var role = "passenger"
    private var stars = 5

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    reads += "${request.method} ${request.path}"
                    val body = when(request.path) {
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"$role","from_city":"Уфа","to_city":"Бирск","depart_at":"2026-10-15T10:00:00Z","seats":1,"amount":300,"pay_method":"cash","paid":false,"driver_name":"Водитель","driver_verified":true,"counterparty_name":"Попутчик","my_stars":$stars,"driver_rating":4.2}"""
                        "/trips/42/tip" -> """{"already_thanked":false}"""
                        else -> "{}"
                    }
                    return MockResponse().setHeader("Content-Type","application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.saveToken("local-receipt-rating-only")
    }

    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun screen(language: AppLanguage) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                if (mounted.value) TripReceiptScreen(bookingId = 42, onBack = {})
            }
        }
        try { compose.waitUntil(15000) {
            compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Snapshot.sendApplyNotifications()
            compose.onAllNodesWithText("Уфа → Бирск").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, reads.count { it == "GET /trips/42/receipt" })
        assertTrue(reads.all { it.startsWith("GET ") })
        } catch (e: Throwable) { throw AssertionError("Receipt requests=$reads; tree=" + compose.onRoot().printToString(), e) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun verify(roleValue: String, language: AppLanguage, value: Int = 5) {
        role = roleValue
        stars = value
        screen(language)
        val label = if(language == AppLanguage.Ru) "Твоя оценка" else "Һинең баһаң"
        val score = if(language == AppLanguage.Ru) "5 звёзд" else "5 йондоҙ"
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(score).assertIsDisplayed()
        compose.onAllNodesWithText("4.2").assertCountEquals(0)
    }

    @Test fun passengerOwnRatingRu() = verify("passenger", AppLanguage.Ru)
    @Test fun driverOwnRatingRu() = verify("driver", AppLanguage.Ru)
    @Test fun passengerOwnRatingBa() = verify("passenger", AppLanguage.Ba)
    @Test fun driverOwnRatingBa() = verify("driver", AppLanguage.Ba)

    @Test fun unratedPassengerHasNoInventedRating() {
        stars = 0
        screen(AppLanguage.Ru)
        compose.onAllNodesWithText("Твоя оценка").assertCountEquals(0)
        compose.onAllNodesWithText("5 звёзд").assertCountEquals(0)
    }

    @Test fun invalidDriverRatingIsNotDisplayedAsSaved() {
        role = "driver"
        stars = 6
        screen(AppLanguage.Ba)
        compose.onAllNodesWithText("Һинең баһаң").assertCountEquals(0)
        compose.onAllNodesWithText("6 йондоҙ").assertCountEquals(0)
    }
}
