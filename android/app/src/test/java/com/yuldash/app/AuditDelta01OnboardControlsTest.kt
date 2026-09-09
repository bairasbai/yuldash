package com.yuldash.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TaxiStop
import com.yuldash.app.data.ApiClient
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditDelta01OnboardControlsTest {
    @get:Rule
    val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private val navigatorIntents = CopyOnWriteArrayList<Intent>()

    @Before fun setupNetwork() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1000
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun cleanupNetwork() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun order() = InstantOrderDto(
        id = 7001,
        status = "onboard",
        role = "driver",
        fromLat = 54.0,
        fromLng = 55.0,
        toLat = 54.1,
        toLng = 55.1,
        fromText = "Тест А",
        toText = "Тест Б",
        category = "standard",
        priceEstimate = 300,
        priceFinal = null,
        distanceKm = 8.0,
        etaMin = 20.0,
        driverId = 2,
        offerExpiresAt = null,
        cancelBy = "",
        cancelReason = "",
        surgeK = 1.0,
        waitingStartedAt = null,
        waitingFeeKop = 0,
        cancelFeeKop = 0,
        noShow = false,
        waitFreeMin = 5,
        waitFeeRubPerMin = 5,
        noShowAt = null,
        cancelFeeNowKop = 0,
        passengerRating = null,
        passengerTrips = 0,
        driverName = "Тест",
        driverCar = "Тест",
        driverVerified = false,
        driverRating = 0.0,
        driverPhone = "",
        passengerName = "Тест",
        passengerPhone = "",
    )

    private fun show(order: InstantOrderDto) {
        compose.setContent {
            val context = LocalContext.current
            val navigatorContext = object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { navigatorIntents.add(intent) }
            }
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru, LocalContext provides navigatorContext) {
                InstantDriverTripScreen(
                    orderId = order.id,
                    onBack = {},
                    onFinished = {},
                    initialOrder = order,
                    observeRemote = false,
                    mapContent = { modifier -> Box(modifier) },
                )
            }
        }
    }

    private fun assertReachable(text: String) {
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun acceptedResponse() = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"ok":true,"order":{"id":7001,"status":"onboard","role":"driver","to_lat":55.75,"to_lng":37.61,"to_text":"Новый адрес"}}""")

    @Test fun `accept destination reads authoritative order envelope`() = runBlocking {
        server.enqueue(acceptedResponse())
        val accepted = ApiClient.acceptDestination(7001).getOrThrow()
        assertEquals(7001, accepted.id)
        assertEquals(55.75, accepted.toLat, 0.000001)
        assertEquals(37.61, accepted.toLng, 0.000001)
    }

    @Test fun `onboard acceptance navigates to accepted new destination`() {
        server.enqueue(acceptedResponse())
        show(order().copy(pendingToText = "Новый адрес", pendingPrice = 900, pendingReason = "zone"))
        compose.onNodeWithText("Согласен").performScrollTo().performClick()
        compose.waitUntil(10000) { navigatorIntents.isNotEmpty() }
        assertEquals("/instant/orders/7001/destination/accept", server.takeRequest().path)
        assertEquals("yandexnavi://build_route_on_map?lat_to=55.75&lon_to=37.61", navigatorIntents.single().dataString)
    }

    @Test fun `failed acceptance does not launch navigator`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Нечего подтверждать"}"""))
        show(order().copy(pendingToText = "Новый адрес", pendingPrice = 900, pendingReason = "zone"))
        compose.onNodeWithText("Согласен").performScrollTo().performClick()
        compose.waitUntil(10000) { server.requestCount == 1 }
        // Flush the real HTTP response and UI coroutine, then inspect captured ACTION_VIEW intents.
        compose.waitUntil(10000) { navigatorIntents.isNotEmpty() || compose.onAllNodesWithText("Нечего подтверждать").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("При отказе сервера маршрут открываться не должен", navigatorIntents.isEmpty())
    }

    @Test
    fun `onboard pending destination keeps accept and decline actions`() {
        show(
            order().copy(
                pendingToText = "Новое направление",
                pendingPrice = 900,
                pendingReason = "zone",
            ),
        )

        assertReachable("Согласен")
        assertReachable("Не смогу")
    }

    @Test
    fun `onboard changed payment keeps acknowledgement action`() {
        show(order().copy(paymentMethod = "cash", paymentChanged = true))

        assertReachable("Способ расчёта изменился")
        assertReachable("Понял")
    }

    @Test
    fun `onboard stop can start waiting`() {
        show(order().copy(stops = listOf(TaxiStop(54.05, 55.05, "Аптека"))))

        assertReachable("Стоим")
    }

    @Test
    fun `onboard stop can finish waiting`() {
        show(
            order().copy(
                stops = listOf(TaxiStop(54.05, 55.05, "Аптека")),
                standing = true,
            ),
        )

        assertReachable("Поехали")
    }
}
