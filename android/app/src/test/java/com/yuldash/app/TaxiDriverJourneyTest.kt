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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private enum class AcceptMode { Success, LostAfterCommit, LostBeforeExpiry }
    @Volatile private var acceptMode = AcceptMode.Success
    @Volatile private var status = "offered"
    @Volatile private var expiredReadAllowed = false
    @Volatile private var nextExpiry: String? = null
    private val posted = CountDownLatch(1)
    private val deliverResponse = CountDownLatch(1)
    @Volatile private var expiryMs = System.currentTimeMillis() + 600_000L
    // Fixture only: JWT payload is {"sub":"9"}; no real token or signature.
    private val fixtureBearer = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI5In0.synthetic"

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(fixtureBearer)
        assertEquals(9, ApiClient.myUserId())
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
                        path == "/instant/driver/offer" -> {
                            val next = nextExpiry
                            val offered = when {
                                next != null && status == "expired" -> order(72, "offered", next)
                                status == "offered" -> order()
                                else -> "null"
                            }
                            json("""{"offer":$offered,"blocked":""}""")
                        }
                        path == "/instant/demand" -> json("""{"zones":[]}""")
                        path == "/instant/orders/71" -> {
                            assertEquals("GET", request.method)
                            assertEquals("Bearer $fixtureBearer", request.getHeader("Authorization"))
                            when {
                                acceptMode != AcceptMode.LostBeforeExpiry -> json(order())
                                reads.count { it == path } == 1 || !expiredReadAllowed -> json("{}", 503)
                                else -> {
                                    status = "expired"
                                    json("""{"detail":{"ru":"Нет доступа к заказу","ba":"Заказға рөхсәт юҡ"}}""", 403)
                                }
                            }
                        }
                        request.method == "POST" && path.endsWith("/decline") -> {
                            writes.add("decline")
                            json(order())
                        }
                        request.method == "POST" && path in listOf("/instant/orders/71/accept", "/instant/orders/71/arrived", "/instant/orders/71/onboard", "/instant/orders/71/done") -> {
                            assertEquals("Bearer $fixtureBearer", request.getHeader("Authorization"))
                            val action = path.substringAfterLast('/')
                            writes.add(action)
                            if (action == "accept" && acceptMode != AcceptMode.Success) {
                                if (acceptMode == AcceptMode.LostAfterCommit) status = "accepted"
                                posted.countDown()
                                if (!deliverResponse.await(10, TimeUnit.SECONDS)) return json("{}", 598)
                                return json("{}", 503)
                            }
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
        ApiClient.testTimeoutMs = 12_000
    }

    @After fun cleanup() {
        deliverResponse.countDown()
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

    @Test fun late503AfterCommitRecoversTripWithoutSecondAccept() {
        acceptMode = AcceptMode.LostAfterCommit
        holdAcceptAcrossDeadline()
        deliverResponse.countDown()
        finishTrip()
        assertEquals(listOf("accept", "arrived", "onboard", "done"), writes.toList())
        assertTrue(reads.count { it == "/instant/orders/71" } >= 2)
    }

    @Test fun late503WhileUnassignedKeepsCheckingThenOffersNextOrder() {
        acceptMode = AcceptMode.LostBeforeExpiry
        holdAcceptAcrossDeadline()
        deliverResponse.countDown()
        waitForText("Проверяем, принят ли заказ")
        compose.waitUntil(15_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            reads.contains("/instant/orders/71")
        }
        compose.runOnIdle { assertNull(openedOrder.value) }
        assertEquals(listOf("accept"), writes.toList())
        nextExpiry = Instant.ofEpochMilli(System.currentTimeMillis() + 600_000L).toString()
        expiredReadAllowed = true
        waitForText("Принять")
        compose.onNodeWithText("Принять").assertIsEnabled()
        assertEquals("expired", status)
        assertTrue(reads.count { it == "/instant/orders/71" } >= 2)
        assertTrue(reads.count { it == "/instant/driver/offer" } >= 2)
        assertEquals(listOf("accept"), writes.toList())
    }

    private fun holdAcceptAcrossDeadline() {
        expiryMs = System.currentTimeMillis() + 6_000L
        mount()
        waitForText("Принять")
        compose.onNodeWithText("Принять").performClick()
        compose.waitUntil(10_000) { posted.count == 0L }
        // The Compose virtual clock does not drive the screen's wall-clock deadline.
        compose.waitUntil(10_000) { System.currentTimeMillis() > expiryMs }
        waitForText("Время вышло")
        assertEquals(listOf("accept"), writes.toList())
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
    private fun order(id: Int = 71, stage: String = status, deadline: String = Instant.ofEpochMilli(expiryMs).toString()) =
        """{"id":$id,"status":"$stage","role":"driver","offer_expires_at":"$deadline","from_lat":54.735,"from_lng":55.958,"to_lat":54.751,"to_lng":56.001,"from_text":"Пункт А","to_text":"Пункт Б","category":"standard","payment_method":"cash","price_estimate":250,"price_final":250,"passenger_name":"Тестовый пассажир","driver_id":9}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
