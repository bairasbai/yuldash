package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import org.robolectric.shadows.ShadowToast
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiDriverJourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val openedOrder = mutableStateOf<Int?>(null)
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private var returnedToLine = 0
    private var rejectFirst = false
    @Volatile private var status = "offered"
    private val expiry = Instant.now().plusSeconds(600).toString()

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-driver-journey")
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        LocationPrefs.sharingEnabled = false
        NavSignals.openTaxiReceipt.value = 0
        ShadowToast.reset()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.method == "GET") reads.add(path)
                    return when {
                        path == "/instant/driver/offer" -> json("""{"offer":${if (status == "offered") order() else "null"},"blocked":""}""")
                        path == "/instant/demand" -> json("""{"zones":[]}""")
                        path == "/instant/orders/71" -> json(order())
                        request.method == "POST" && path in listOf("/instant/orders/71/accept", "/instant/orders/71/arrived", "/instant/orders/71/onboard", "/instant/orders/71/done") -> {
                            assertEquals("Bearer local-driver-journey", request.getHeader("Authorization"))
                            writes.add(path.substringAfterLast('/'))
                            if (rejectFirst && writes.size == 1) return json("""{"detail":"Тестовый отказ принятия"}""", 503)
                            val action = writes.last()
                            val expected = mapOf("accept" to "offered", "arrived" to "accepted", "onboard" to "arriving", "done" to "onboard")
                            if (status != expected.getValue(action)) return json("{}", 409)
                            status = mapOf("accept" to "accepted", "arrived" to "arriving", "onboard" to "onboard", "done" to "done").getValue(action)
                            json(order())
                        }
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        NavSignals.openTaxiReceipt.value = 0
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun driverAcceptsArrivesBoardsAndFinishesThroughRealButtons() {
        mount()
        waitForText("Принять")
        compose.onNodeWithText("Принять").performClick()
        finishTrip()
        assertEquals(listOf("accept", "arrived", "onboard", "done"), writes.toList())
    }

    @Test fun refusalKeepsOfferAndRetryCanFinishTrip() {
        rejectFirst = true
        mount()
        waitForText("Принять")
        compose.onNodeWithText("Принять").performClick()
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() == "Тестовый отказ принятия"
        }
        compose.runOnIdle { assertNull(openedOrder.value) }
        assertEquals("offered", status)
        compose.onNodeWithText("Принять").assertIsEnabled().performClick()
        finishTrip()
        assertEquals(listOf("accept", "accept", "arrived", "onboard", "done"), writes.toList())
    }

    private fun mount() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                val id = openedOrder.value
                if (id == null) {
                    InstantDriverOnlineController(online = true, mapContent = { Box(it) },
                        onOpenTrip = { openedOrder.value = it })
                } else {
                    InstantDriverTripScreen(orderId = id, onBack = {},
                        onFinished = { returnedToLine++ }, mapContent = { Box(it) })
                }
            }
        }
    }

    private fun finishTrip() {
        waitForText("Я на месте")
        compose.runOnIdle { assertEquals(71, openedOrder.value) }
        assertTrue(reads.contains("/instant/orders/71"))
        compose.onNodeWithText("Я на месте").performClick()
        waitForText("Пассажир сел")
        compose.onNodeWithText("Пассажир сел").performClick()
        waitForText("Завершить поездку")
        compose.onNodeWithText("Завершить поездку").performClick()
        waitForText("Вернуться на линию")
        assertEquals("done", status)
        compose.onNodeWithText("Вернуться на линию").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, returnedToLine) }
    }

    private fun waitForText(label: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun order() = """{"id":71,"status":"$status","role":"driver","offer_expires_at":"$expiry","from_lat":54.735,"from_lng":55.958,"to_lat":54.751,"to_lng":56.001,"from_text":"Пункт А","to_text":"Пункт Б","category":"standard","payment_method":"cash","price_estimate":250,"price_final":250,"passenger_name":"Тестовый пассажир","driver_id":9}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
