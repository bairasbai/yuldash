package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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

/** Real passenger UI/API; the HTTP fixture advances the other participant's statuses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiPassengerJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val creates = CopyOnWriteArrayList<String>()
    private val estimates = CopyOnWriteArrayList<String>()
    private val ratings = CopyOnWriteArrayList<String>()
    private val polls = CopyOnWriteArrayList<String>()
    @Volatile private var status = "searching"
    private var rejectFirst = false

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("local-taxi-passenger")
        LocationPrefs.lastLat = 54.735
        LocationPrefs.lastLng = 55.958
        LocationPrefs.sharingEnabled = false
        NavSignals.openTaxiReceipt.value = 0
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/instant/availability" -> json("""{"enabled":true}""")
                        path == "/instant/orders/mine" -> json("""{"items":[]}""")
                        path == "/places/saved" -> json("""{"items":[{"id":10,"kind":"home","label":"Дом","address":"Уфа, Ленина 10","lat":54.751,"lng":56.001}]}""")
                        path == "/places/recent" -> json("""{"items":[]}""")
                        path == "/instant/nearby-drivers" -> json("""{"drivers":[]}""")
                        path == "/instant/estimate" -> {
                            estimates.add(request.body.readUtf8())
                            json("""{"price":250,"distance_km":4.0,"eta_min":12.0,"zone":"city","category":"standard","tariff_id":1,"options":[{"category":"standard","price":250,"open":true}]}""")
                        }
                        path == "/instant/orders" && request.method == "POST" -> {
                            creates.add(request.body.readUtf8())
                            if (rejectFirst && creates.size == 1) json("""{"detail":"Тестовый временный отказ заказа"}""", 503)
                            else json(order(), 201)
                        }
                        path == "/instant/orders/71" -> { polls.add(status); json(order()) }
                        path == "/instant/orders/71/rate" -> { ratings.add(request.body.readUtf8()); json("{}") }
                        path == "/places/saved/10/used" || path == "/places/recent" -> json("{}")
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
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        LocationPrefs.sharingEnabled = false
        NavSignals.openTaxiReceipt.value = 0
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun orderFromSavedAddressTracksDriverAndCanBeRated() {
        chooseAddress()
        compose.onNodeWithText("Заказать").assertIsEnabled().performClick()
        waitForText("Ищем машину")
        verifyCreate()
        advanceDriver("accepted", "Тестовый водитель едет")
        advanceDriver("arriving", "Машина на месте")
        advanceDriver("onboard", "В пути")
        advanceDriver("done", "Поездка завершена")
        compose.onNodeWithContentDescription("5 звёзд").performScrollTo().performClick()
        compose.onNodeWithText("Отправить оценку").performScrollTo().performClick()
        waitForText("Спасибо за оценку")
        assertEquals(5, JSONObject(ratings.single()).getInt("stars"))
        compose.onNodeWithText("Чек и детали").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(71, NavSignals.openTaxiReceipt.value) }
        assertTrue(polls.containsAll(listOf("accepted", "arriving", "onboard", "done")))
    }

    @Test fun failedCreationKeepsAddressesAndRetryCreatesOrder() {
        rejectFirst = true
        chooseAddress()
        compose.onNodeWithText("Заказать").performClick()
        waitForText("Тестовый временный отказ заказа")
        compose.onNodeWithText("Тестовый временный отказ заказа").assertIsDisplayed()
        compose.onAllNodesWithText("Ищем машину").assertCountEquals(0)
        compose.onNodeWithText("Заказать").assertIsEnabled().performClick()
        waitForText("Ищем машину")
        assertEquals(2, creates.size)
        assertEquals(JSONObject(creates[0]).toString(), JSONObject(creates[1]).toString())
    }

    private fun chooseAddress() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true,
                    payMethod = PayMethods.CASH, renderNativeMap = false)
            }
        }
        waitForText("Дом")
        compose.onNode(hasText("Дом") and hasClickAction()).performClick()
        waitForText("Заказать")
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            estimates.isNotEmpty() && !compose.onNodeWithText("Заказать").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }

    private fun advanceDriver(next: String, label: String) {
        status = next
        waitForText(label)
    }
    private fun waitForText(value: String) {
        compose.waitUntil(15000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun verifyCreate() {
        assertEquals(1, creates.size)
        val body = JSONObject(creates.single())
        assertEquals(54.735, body.getDouble("from_lat"), 0.000001)
        assertEquals(55.958, body.getDouble("from_lng"), 0.000001)
        assertEquals(54.751, body.getDouble("to_lat"), 0.000001)
        assertEquals(56.001, body.getDouble("to_lng"), 0.000001)
        assertEquals("Уфа, Ленина 10", body.getString("to_text"))
        assertEquals("cash", body.getString("payment_method"))
    }
    private fun order() = """{"id":71,"status":"$status","role":"passenger","from_lat":54.735,"from_lng":55.958,"to_lat":54.751,"to_lng":56.001,"from_text":"Моя позиция","to_text":"Уфа, Ленина 10","category":"standard","payment_method":"cash","price_estimate":250,"price_final":250,"driver_name":"Тестовый водитель","driver_car":"Тестовая машина","driver_plate":"А001АА","can_rate":true}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
