package com.yuldash.app

import android.content.DialogInterface
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.Manifest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.Calendar
import java.util.Collections

/** DELTA08: предзаказ обязан сохранить всё, что пассажир выбрал в форме. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuditDelta08ScheduledPayloadTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private val estimateBodies = Collections.synchronizedList(mutableListOf<String>())
    private val scheduleBodies = Collections.synchronizedList(mutableListOf<String>())
    private var mounted: MutableState<Boolean>? = null

    @Before
    fun setup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1_000
        ApiClient.logout()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/auth/verify" -> json(
                        """{"access_token":"delta08-token","refresh_token":"delta08-refresh","user":{"name":"Тест"}}"""
                    )
                    request.method == "GET" && path.startsWith("/instant/availability?") -> json("""{"enabled":true}""")
                    request.method == "GET" && path == "/instant/orders/mine?limit=5" -> json("""{"items":[]}""")
                    request.method == "GET" && path == "/places/saved" -> json(
                        """{"items":[{"id":808,"kind":"home","label":"Дом","address":"Уфа, Ленина 10","lat":54.735,"lng":55.958,"created_at":"2026-09-01T00:00:00Z","used_at":"2026-09-01T00:00:00Z"}]}"""
                    )
                    request.method == "GET" && path == "/places/recent" -> json("""{"items":[]}""")
                    request.method == "GET" && path.startsWith("/instant/nearby-drivers?") -> json("""{"drivers":[]}""")
                    request.method == "GET" && path.startsWith("/geocode?q=") -> json(
                        """{"items":[{"title":"Уфа, проспект Октября, 25","lat":54.751,"lon":56.001}]}"""
                    )
                    request.method == "POST" && path == "/instant/estimate" -> {
                        val body = request.body.readUtf8()
                        estimateBodies += body
                        val response = json(
                            """{"price":900,"distance_km":130.0,"eta_min":120.0,"zone":"intercity","category":"standard","tariff_id":1,"round_trip_available":true,"round_trip_max_wait_hours":4,"options":[{"category":"standard","price":900,"open":true}]}"""
                        )
                        if (JSONObject(body).optString("scheduled_at").isNotBlank()) {
                            response.setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                        response
                    }
                    request.method == "POST" && path == "/instant/schedule" -> {
                        val body = request.body.readUtf8()
                        scheduleBodies += body
                        json(
                            """{"id":8080,"status":"scheduled","role":"passenger","from_lat":52.598,"from_lng":58.4419,"to_lat":54.735,"to_lng":55.958,"from_text":"Моя позиция","to_text":"Уфа, Ленина 10","scheduled_at":"2026-09-10T12:00:00+03:00"}"""
                        )
                    }
                    request.method == "POST" && path == "/places/saved/808/used" -> json("{}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        runBlocking {
            ApiClient.verifyCode("+79990000808", "0000", "Тест").getOrThrow()
        }
        LocationPrefs.lastLat = 52.598
        LocationPrefs.lastLng = 58.4419
        LocationPrefs.sharingEnabled = false
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.INTERNET)
    }

    @After
    fun teardown() {
        mounted?.let { state ->
            composeRule.runOnIdle { state.value = false }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        LocationPrefs.lastLat = null
        LocationPrefs.lastLng = null
        LocationPrefs.sharingEnabled = false
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test
    fun scheduledOrderKeepsUiDetailsAndReestimatesForChosenTime() {
        val screenMounted = mutableStateOf(true)
        mounted = screenMounted
        composeRule.setContent {
            if (screenMounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    InstantOrderScreen(
                        onBack = {},
                        onLoginRequired = {},
                        embedded = true,
                        payMethod = PayMethods.SBP,
                        renderNativeMap = false,
                    )
                }
            }
        }

        waitForText("Дом")
        composeRule.onNode(hasText("Дом").and(hasClickAction())).performClick()
        waitForText("Заказать")
        waitForRequests(estimateBodies, 1)

        composeRule.onNodeWithContentDescription("Заехать по пути").performClick()
        waitForText("Куда заехать?")
        composeRule.onNodeWithText("Адрес остановки")
            .performTextInput("Проспект Октября 25")
        waitForText("Уфа, проспект Октября, 25")
        composeRule.onNode(hasText("Уфа, проспект Октября, 25").and(hasClickAction()))
            .performClick()
        waitForText("Уфа, проспект Октября, 25")

        composeRule.onNodeWithText("СБП").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Детали заказа").performClick()

        composeRule.onNode(hasText("Нужно кресло, коляска, животное?").and(hasClickAction()))
            .performScrollTo().performClick()
        composeRule.onNode(hasText("Бустер 7–12").and(hasClickAction()))
            .performScrollTo().performClick()
        composeRule.onNodeWithText("Выбрано: 1").assertIsDisplayed()

        composeRule.onNode(hasText("Как меня найти").and(hasClickAction()))
            .performScrollTo().performClick()
        composeRule.onNodeWithText("Подъезд, квартира, этаж")
            .performScrollTo().performTextInput("Подъезд 3, этаж 2")
        composeRule.onNodeWithText("Комментарий водителю")
            .performScrollTo().performTextInput("Синие ворота за магазином")
        composeRule.onNode(hasText("Заказ для другого человека").and(hasClickAction()))
            .performScrollTo().performClick()
        composeRule.onNodeWithText("Кого везём (имя)")
            .performScrollTo().performTextInput("Айгуль")
        composeRule.onNodeWithText("Его телефон")
            .performScrollTo().performTextInput("+79991234567")

        val roundTripSwitch = composeRule.onAllNodes(isToggleable())[0]
        roundTripSwitch.performScrollTo().performClick().assertIsOn()
        waitForText("Сколько ждать на месте")
        composeRule.onNode(hasText("3 ч").and(hasClickAction()))
            .performScrollTo().performClick()

        val womenSwitch = composeRule.onAllNodes(isToggleable())[1]
        womenSwitch.performScrollTo().performClick().assertIsOn()

        waitForConfiguredEstimate()
        val estimatesBeforeTime = synchronized(estimateBodies) { estimateBodies.size }

        composeRule.onNode(hasText("На время").and(hasClickAction()))
            .performScrollTo().performClick()
        chooseTomorrowAt(hour = 14, minute = 35)
        waitForText("Изменить")
        val scheduledButtons = composeRule.onAllNodes(hasText("Заказать на", substring = true).and(hasClickAction()))
        val scheduledButton = scheduledButtons[scheduledButtons.fetchSemanticsNodes().lastIndex]

        // Цена предыдущего времени не разрешает новый заказ до ответа новой оценки.
        scheduledButton.assertIsNotEnabled()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !scheduledButton.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }

        // Даём эффекту выбранного времени поставить повторную оценку в очередь, если он существует.
        repeat(20) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.mainClock.advanceTimeByFrame()
        }

        // AnimatedContent держит уходящую копию текста; вызываем действие именно найденной
        // production-кнопки, не обходя её onClick и реальный ApiClient.
        scheduledButton.performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        waitForRequests(scheduleBodies, 1)

        val failures = mutableListOf<String>()
        val estimatesAfterTime = synchronized(estimateBodies) { estimateBodies.drop(estimatesBeforeTime) }
        if (estimatesAfterTime.none { JSONObject(it).optString("scheduled_at").isNotBlank() }) {
            failures += "после выбора «На время» /instant/estimate не пересчитан с scheduled_at"
        }

        val scheduled = JSONObject(synchronized(scheduleBodies) { scheduleBodies.single() })
        fun expect(key: String, expected: Any) {
            val actual = scheduled.opt(key)
            if (actual != expected) failures += "$key: ожидалось <$expected>, получено <$actual>"
        }
        expect("comment", "Синие ворота за магазином")
        expect("entrance", "Подъезд 3, этаж 2")
        expect("for_name", "Айгуль")
        expect("for_phone", "+79991234567")
        expect("women_only", true)
        expect("payment_method", PayMethods.SBP)
        expect("round_trip", true)
        expect("return_wait_min", 180)
        val options = scheduled.optJSONArray("options")
        if (options == null || (0 until options.length()).none { options.optString(it) == "booster" }) {
            failures += "options: выбранный booster не отправлен"
        }
        val waypoints = scheduled.optJSONArray("waypoints")
        if (waypoints == null || waypoints.length() != 1) {
            failures += "waypoints: ожидалась одна выбранная остановка"
        } else {
            val stop = waypoints.getJSONObject(0)
            if (kotlin.math.abs(stop.optDouble("lat") - 54.751) > 0.000001) {
                failures += "waypoints[0].lat: ожидалось <54.751>, получено <${stop.opt("lat")}>"
            }
            if (kotlin.math.abs(stop.optDouble("lng") - 56.001) > 0.000001) {
                failures += "waypoints[0].lng: ожидалось <56.001>, получено <${stop.opt("lng")}>"
            }
            if (stop.optString("text") != "Уфа, проспект Октября, 25") {
                failures += "waypoints[0].text: ожидался выбранный адрес, получено <${stop.opt("text")}>"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    private fun chooseTomorrowAt(hour: Int, minute: Int) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dateDialog = ShadowAlertDialog.getLatestAlertDialog() as DatePickerDialog
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        dateDialog.datePicker.updateDate(
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH),
            tomorrow.get(Calendar.DAY_OF_MONTH),
        )
        dateDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val timeDialog = ShadowAlertDialog.getLatestAlertDialog() as TimePickerDialog
        timeDialog.updateTime(hour, minute)
        timeDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRequests(requests: List<String>, count: Int) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            synchronized(requests) { requests.size >= count }
        }
    }

    private fun waitForConfiguredEstimate() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            synchronized(estimateBodies) {
                estimateBodies.any { raw ->
                    val body = JSONObject(raw)
                    body.optBoolean("round_trip") &&
                        body.optInt("return_wait_min") == 180 &&
                        body.optJSONArray("waypoints")?.length() == 1
                }
            }
        }
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
