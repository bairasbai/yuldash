package com.yuldash.app

import android.Manifest
import android.app.Application
import android.location.LocationManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiDriverTerminalTrackingTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val server = MockWebServer()
    private val statuses = ConcurrentHashMap<Int, String>()
    private val sockets = ConcurrentHashMap<Int, AtomicReference<WebSocket>>()
    private val authentications = ConcurrentHashMap<Int, AtomicInteger>()
    private val reads = ConcurrentHashMap<Int, AtomicInteger>()
    private val allSockets = CopyOnWriteArrayList<WebSocket>()
    private val displayedOrder = mutableStateOf<InstantOrderDto?>(null)

    @Before fun prepare() {
        ApiClient.resetForTest()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val wsId = Regex("/ws/instant/(\\d+)/location").matchEntire(request.path.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (wsId != null) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).optString("type") == "auth") {
                            allSockets.add(webSocket)
                            sockets.computeIfAbsent(wsId) { AtomicReference() }.set(webSocket)
                            authentications.computeIfAbsent(wsId) { AtomicInteger() }.incrementAndGet()
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                    }
                })
                val id = Regex("/instant/orders/(\\d+)").matchEntire(request.path.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (id != null && statuses.containsKey(id)) {
                    reads.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
                    return MockResponse().setBody("""{"id":$id,"status":"${statuses[id]}","role":"driver",
                        "from_lat":54.0,"from_lng":55.0,"to_lat":54.1,"to_lng":55.1,
                        "from_text":"Тест А","to_text":"Тест Б","category":"standard","price_estimate":300,
                        "distance_km":8.0,"eta_min":20.0,"driver_id":2}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        ApiClient.saveToken("local-taxi-driver-terminal")
    }

    @After fun cleanup() {
        compose.runOnIdle { displayedOrder.value = null }
        compose.waitForIdle()
        allSockets.forEach { it.close(1000, null) }
        ApiClient.resetForTest()
        server.shutdown()
    }

    private fun show(status: String) {
        statuses[42] = status
        displayedOrder.value = order(42, status)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                displayedOrder.value?.let { initial ->
                    InstantDriverTripScreen(
                        orderId = initial.id, onBack = {}, onFinished = {},
                        initialOrder = initial, observeRemote = true,
                        mapContent = { modifier -> Box(modifier) },
                    )
                }
            }
        }
        awaitTracking(42)
    }

    private fun awaitTracking(id: Int) {
        compose.waitUntil(5_000) {
            (authentications[id]?.get() ?: 0) >= 1 && (reads[id]?.get() ?: 0) >= 1
        }
        compose.runOnIdle { assertFalse("Active order $id must request real location updates", shadowOf(manager).locationUpdateListeners.isEmpty()) }
    }

    private fun stopFromServer(id: Int) {
        assertTrue(sockets.getValue(id).get().close(1008, "Order ended"))
        compose.waitUntil(5_000) {
            // WS callback changes Compose state; flush its disposal before inspecting GPS.
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            shadowOf(manager).locationUpdateListeners.isEmpty()
        }
        compose.runOnIdle { assertTrue("Terminal server decision must release GPS before HTTP catches up", shadowOf(manager).locationUpdateListeners.isEmpty()) }
    }

    @Test fun acceptedDriverStopsGpsEvenWhenHttpKeepsReturningAccepted() {
        show("accepted")
        stopFromServer(42)
        assertEquals("accepted", statuses[42])
    }

    @Test fun onboardDriverStopsGpsEvenWhenHttpKeepsReturningOnboard() {
        show("onboard")
        stopFromServer(42)
        assertEquals("onboard", statuses[42])
    }

    @Test fun temporaryServerDisconnectKeepsGpsAndReconnects() {
        show("accepted")
        assertTrue(sockets.getValue(42).get().close(1001, "Temporary restart"))
        compose.waitUntil(5_000) { (authentications[42]?.get() ?: 0) >= 2 }
        compose.runOnIdle { assertFalse("Temporary transport outage must keep active-trip GPS", shadowOf(manager).locationUpdateListeners.isEmpty()) }
    }

    @Test fun newOrderAfterTerminalDecisionStartsTrackingAgain() {
        show("accepted")
        stopFromServer(42)
        statuses[43] = "accepted"
        compose.runOnIdle { displayedOrder.value = order(43, "accepted") }
        awaitTracking(43)
    }

    private fun order(id: Int, status: String) = InstantOrderDto(
        id = id, status = status, role = "driver", fromLat = 54.0, fromLng = 55.0,
        toLat = 54.1, toLng = 55.1, fromText = "Тест А", toText = "Тест Б", category = "standard",
        priceEstimate = 300, priceFinal = null, distanceKm = 8.0, etaMin = 20.0, driverId = 2,
        offerExpiresAt = null, cancelBy = "", cancelReason = "", surgeK = 1.0,
        waitingStartedAt = null, waitingFeeKop = 0, cancelFeeKop = 0, noShow = false,
        waitFreeMin = 5, waitFeeRubPerMin = 5, noShowAt = null, cancelFeeNowKop = 0,
        passengerRating = null, passengerTrips = 0, driverName = "Тест", driverCar = "Тест",
        driverVerified = false, driverRating = 0.0, driverPhone = "", passengerName = "Тест", passengerPhone = "",
    )
}
