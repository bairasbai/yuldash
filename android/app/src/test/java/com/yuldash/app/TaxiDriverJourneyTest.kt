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
import com.yuldash.app.data.DriverCurrentOrder
import com.yuldash.app.data.readDriverCurrentOrder
import kotlinx.coroutines.runBlocking
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
    private val online = mutableStateOf(true)
    private val openedOrder = mutableStateOf<Int?>(null)
    private val writes = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<String>()
    private var returnedToLine = 0
    private enum class AcceptMode { Success, LostAfterCommit, LostBeforeExpiry, AlreadyOnTrip, ShutdownBeforeCommit }
    @Volatile private var acceptMode = AcceptMode.Success
    @Volatile private var status = "offered"
    @Volatile private var currentLookup: MockResponse? = null
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
        ApiClient.setUiLanguageBashkir(false)
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
                        path == "/instant/driver/current-order" -> {
                            assertEquals("GET", request.method)
                            assertEquals("Bearer $fixtureBearer", request.getHeader("Authorization"))
                            currentLookup ?: json("""{"order_id":null}""")
                        }
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
                            if (action == "accept" && acceptMode == AcceptMode.AlreadyOnTrip) {
                                return json("""{"detail":{"ru":"У тебя уже есть активная поездка — заверши её сначала","ba":"Һинең актив сәфәрең бар — башта уны тамамла"}}""", 409)
                            }
                            if (action == "accept" && acceptMode != AcceptMode.Success) {
                                if (acceptMode == AcceptMode.LostAfterCommit) status = "accepted"
                                posted.countDown()
                                if (!deliverResponse.await(20, TimeUnit.SECONDS)) return json("{}", 598)
                                if (acceptMode == AcceptMode.ShutdownBeforeCommit) status = "accepted"
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

    @Test fun startupLookupReadsCommittedOrderWithoutMutatingIt() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        assertEquals(DriverCurrentOrder.None, ApiClient.readDriverCurrentOrder(owner, 9))
        status = "accepted"
        currentLookup = json("""{"order_id":71}""")
        val current = ApiClient.readDriverCurrentOrder(owner, 9) as DriverCurrentOrder.Active
        assertEquals(71, current.order.id)
        assertEquals(listOf("/instant/driver/current-order", "/instant/driver/current-order", "/instant/orders/71"), reads.toList())
        assertTrue(writes.isEmpty())
    }

    @Test fun startupLookupDistinguishesUnavailableAndRejectsANewLogin() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        currentLookup = json("""{"order_id":"71"}""")
        assertEquals(DriverCurrentOrder.Unavailable, ApiClient.readDriverCurrentOrder(owner, 9))
        currentLookup = json("{}", 503)
        assertEquals(DriverCurrentOrder.Unavailable, ApiClient.readDriverCurrentOrder(owner, 9))
        assertTrue(reads.none { it == "/instant/orders/71" })
        ApiClient.saveToken(fixtureBearer)
        assertEquals(DriverCurrentOrder.SessionChanged, ApiClient.readDriverCurrentOrder(owner, 9))
        assertEquals(2, reads.size)
        assertTrue(writes.isEmpty())
    }

    @Test fun offlineDriverCabinetReturnsToCommittedRideWithoutAccept() {
        status = "accepted"
        currentLookup = json("""{"order_id":71}""")
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                DriverCabinetScreen(
                    rides = emptyList(), onBack = {}, onCreateRide = {}, onVerifyDriver = {}, onBoost = {},
                    onInstantTrip = { openedOrder.value = it },
                )
            }
        }
        val label = "Сәфәргә ҡайтыу"
        waitForText(label)
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(71, openedOrder.value) }
        assertTrue(reads.containsAll(listOf("/instant/driver/current-order", "/instant/orders/71")))
        assertFalse(reads.contains("/instant/driver/offer"))
        assertTrue(writes.isEmpty())
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

    @Test fun acceptedOrderRecoversEvenWhenDriverLeavesLineBeforeReply() {
        acceptMode = AcceptMode.LostAfterCommit
        holdAcceptAcrossDeadline()
        compose.runOnIdle { online.value = false }
        compose.waitForIdle()
        val offerReads = reads.count { it == "/instant/driver/offer" }
        deliverResponse.countDown()

        finishTrip()
        assertEquals(offerReads, reads.count { it == "/instant/driver/offer" })
        assertEquals(listOf("accept", "arrived", "onboard", "done"), writes.toList())
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

    @Test fun anotherActiveTripShowsServerExplanationInRussian() {
        assertAnotherActiveTrip(AppLanguage.Ru, "Принять", "У тебя уже есть активная поездка — заверши её сначала")
    }

    @Test fun anotherActiveTripShowsServerExplanationInBashkir() {
        assertAnotherActiveTrip(AppLanguage.Ba, "Ҡабул итеү", "Һинең актив сәфәрең бар — башта уны тамамла")
    }

    @Test fun remountsBeforeCommitReconcileOriginalAcceptWithoutReplay() {
        acceptMode = AcceptMode.ShutdownBeforeCommit
        mount()
        waitForText("Принять")
        compose.onNodeWithText("Принять").performClick()
        compose.waitUntil(10_000) { posted.count == 0L }
        var owner = ApiClient.queueSessionGeneration()
        assertEquals(71, ApiClient.readTaxiAcceptIntent(owner)?.orderId)

        repeat(2) {
            val prior = reads.count { it == "/instant/orders/71" }
            compose.runOnIdle { mounted.value = false; online.value = false }
            compose.waitForIdle()
            ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
            val reloaded = ApiClient.queueSessionGeneration()
            assertNotEquals(owner, reloaded)
            assertNull(ApiClient.readTaxiAcceptIntent(owner))
            owner = reloaded
            assertEquals(71, ApiClient.readTaxiAcceptIntent(owner)?.orderId)
            compose.runOnIdle { mounted.value = true }
            waitForText("Проверяем, принят ли заказ")
            compose.waitUntil(10_000) { reads.count { it == "/instant/orders/71" } > prior }
            assertEquals("offered", status)
            assertEquals(71, ApiClient.readTaxiAcceptIntent(owner)?.orderId)
        }

        deliverResponse.countDown()
        waitForText("Я на месте")
        assertNull(ApiClient.readTaxiAcceptIntent(owner))
        assertEquals(listOf("accept"), writes.toList())
    }

    @Test fun sameUserNewLoginCannotRecoverOrClearPreviousAcceptIntent() {
        val first = ApiClient.queueSessionGeneration()
        val old = requireNotNull(ApiClient.beginTaxiAcceptIntent(71, first))
        assertEquals(old, ApiClient.readTaxiAcceptIntent(first))
        assertNull(ApiClient.beginTaxiAcceptIntent(72, first))
        ApiClient.saveToken(fixtureBearer)
        val next = ApiClient.queueSessionGeneration()
        assertNull(ApiClient.readTaxiAcceptIntent(next))
        val fresh = requireNotNull(ApiClient.beginTaxiAcceptIntent(71, next))
        ApiClient.clearTaxiAcceptIntent(old)
        assertEquals(fresh, ApiClient.readTaxiAcceptIntent(next))
        ApiClient.clearTaxiAcceptIntent(fresh)
        assertNull(ApiClient.readTaxiAcceptIntent(next))
    }

    private fun assertAnotherActiveTrip(language: AppLanguage, acceptLabel: String, expected: String) {
        acceptMode = AcceptMode.AlreadyOnTrip
        ApiClient.setUiLanguageBashkir(language == AppLanguage.Ba)
        mount(language)
        waitForText(acceptLabel)
        compose.onNodeWithText(acceptLabel).performClick()
        compose.waitUntil(10_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast() != null
        }
        assertEquals(expected, ShadowToast.getTextOfLatestToast())
        assertEquals(listOf("accept"), writes.toList())
        assertFalse(reads.contains("/instant/orders/71"))
        compose.runOnIdle { assertNull(openedOrder.value) }
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

    private fun mount(language: AppLanguage = AppLanguage.Ru) {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                val id = openedOrder.value
                if (id == null) {
                    InstantDriverOnlineController(online = online.value, mapContent = { Box(it) },
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
