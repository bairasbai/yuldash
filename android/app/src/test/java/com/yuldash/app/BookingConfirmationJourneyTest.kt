package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingConfirmationJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val stage = mutableStateOf(0)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val bookingBodies = CopyOnWriteArrayList<String>()
    private val tripStatuses = CopyOnWriteArrayList<String>()
    private var rejectBookingOnce = false
    private val ratingBodies = CopyOnWriteArrayList<String>()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    @Volatile private var status = "pending"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().clear()
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("local-passenger")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path" to request.getHeader("Authorization"))
                    val body = when {
                        path == "/rides/near" -> """{"items":[{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}],"count":1}"""
                        path in listOf("/requests/near", "/seasonal-events", "/popular-routes", "/rides") -> """{"items":[]}"""
                        request.method == "POST" && path == "/bookings" -> {
                            bookingBodies.add(request.body.readUtf8())
                            if (rejectBookingOnce && bookingBodies.size == 1) return json("""{"detail":"Тестовый временный отказ брони"}""", 503)
                            """{"id":42,"ride_id":7,"status":"$status"}"""
                        }
                        path == "/bookings/42/confirm" -> {
                            if (request.getHeader("Authorization") != "Bearer local-driver") return json("{}", 403)
                            status = "confirmed"
                            "{}"
                        }
                        path == "/bookings/mine" -> """{"items":[{"id":42,"ride_id":7,"status":"$status","seats":1,"price":750,"from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Тестовый водитель"}]}"""
                        path == "/bookings/42/trip-status" -> {
                            if (request.getHeader("Authorization") != "Bearer local-passenger") return json("{}", 403)
                            val next = JSONObject(request.body.readUtf8()).getString("status")
                            tripStatuses.add(next)
                            if (next == "done") status = "done"
                            "{}"
                        }
                        path == "/trips/42/receipt" -> """{"booking_id":42,"ride_id":7,"role":"passenger","from_city":"Пункт А","to_city":"Пункт Б","amount":750,"pay_method":"cash","my_stars":0,"counterparty_name":"Тестовый водитель"}"""
                        path == "/bookings/42/tip" -> """{"already_thanked":false}"""
                        path == "/bookings/42/rate" -> { ratingBodies.add(request.body.readUtf8()); "{}" }
                        path == "/driver/bookings" -> """{"items":[{"booking_id":42,"passenger_name":"Тестовый пассажир","route":"Пункт А → Пункт Б","status":"$status"}]}"""
                        path == "/driver/rides" -> """{"items":[{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","price":750,"seats_total":3,"seats_left":2,"status":"active"}]}"""
                        path == "/bookings/42/details" -> """{"booking_id":42,"ride_id":7,"role":"passenger","status":"$status","from_city":"Пункт А","to_city":"Пункт Б","contact_unlocked":false}"""
                        path == "/bookings/42/role" -> """{"role":"passenger","status":"$status"}"""
                        path == "/bookings/42/messages" -> """{"items":[]}"""
                        path == "/bookings/42/boarding-code" -> if (status == "confirmed") """{"code":"5678"}""" else return json("{}", 403)
                        else -> return json("{}", 404)
                    }
                    return json(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Home", "yuldash_tab" to "Map"))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { stage.value = -1 }
        owner.viewModelStore.clear()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun newBookingWaitsForDriverWhoConfirmsFromCabinet() {
        mount()
        choosePublicRide()
        compose.onNodeWithText("Забронировать место").performClick()
        compose.waitUntil(15000) {
            settle()
            vm.activeBookingId.value == 42
        }
        compose.runOnIdle {
            assertEquals("Server pending must stay on booking details", Screen.Booking, vm.screen.value)
            assertNull("Pending booking must not enable active-trip tracking", vm.activeTrip.value)
        }
        compose.onNodeWithText("Отменить бронь").assertIsDisplayed()
        assertEquals(1, bookingBodies.size)
        assertEquals(7, JSONObject(bookingBodies.single()).getInt("ride_id"))

        compose.runOnIdle { stage.value = -1 }
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken("local-driver"); stage.value = 1 }
        compose.waitUntil(15000) {
            settle()
            requests.any { it.first == "GET /driver/bookings" }
        }
        // Запрос броней ушёл — но кнопка «Подтвердить» рисуется по его ОТВЕТУ. В CI тест искал
        // её раньше (PR #116, 2026-09-26: «No node … 'Подтвердить' in scrollable container»).
        awaitInList("Подтвердить")
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(15000) {
            settle()
            status == "confirmed" && requests.count { it.first == "GET /driver/bookings" } >= 2
        }
        assertEquals(1, requests.count { it == ("POST /bookings/42/confirm" to "Bearer local-driver") })
        // Условие выше ждёт, что повторный запрос броней УШЁЛ, а не что его ответ уже нарисован.
        // На быстрой машине разницы не видно; в CI кнопка «Подтвердить» ещё стояла на экране.
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText("Подтвердить").fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithText("Подтвердить").assertCountEquals(0)

        // Reopen the same booking via the production restoration path. Login itself is fixture setup.
        compose.runOnIdle { stage.value = -1 }
        compose.runOnIdle {
            ApiClient.logout()
            ApiClient.saveToken("local-passenger")
            vm.selectedRide.value = null
            vm.activeTrip.value = null
            vm.screen.value = Screen.Booking
            stage.value = 0
        }
        waitForText("Открыть поездку")
        compose.onNodeWithText("Открыть поездку").performClick()
        waitForText("Я сел")
        compose.waitUntil(15000) { requests.any { it.first == "GET /bookings/42/boarding-code" } }
        awaitInList("Код посадки")   // тот же шаблон: ждём сам блок, а не отправку запроса
        waitForText("5678")
        compose.onAllNodesWithText("5678").assertCountEquals(1)
        compose.onNodeWithText("Я сел").performClick()
        compose.waitUntil(15000) { tripStatuses.contains("sat") }
        waitForText("Доехал")
        compose.onNodeWithText("Доехал").performClick()
        compose.waitUntil(15000) { tripStatuses.contains("arrived") }
        waitForText("Завершить")
        compose.onNodeWithText("Завершить").performClick()
        waitForText("Поездка завершена")
        assertEquals(listOf("sat", "arrived", "done"), tripStatuses.toList())
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareStar5"))
        compose.onNodeWithTag("rideshareStar5").performClick()
        compose.onNodeWithTag("rideshareRatingSubmit").performClick()
        waitForText("Готово")
        assertEquals(1, ratingBodies.size)
        assertEquals(5, JSONObject(ratingBodies.single()).getInt("stars"))
        assertTrue(requests.any { it.first == "GET /trips/42/receipt" })
    }

    @Test fun existingConfirmedBookingKeepsActiveDestination() {
        status = "confirmed"
        mount()
        choosePublicRide()
        compose.onNodeWithText("Забронировать место").performClick()
        compose.waitUntil(15000) { vm.activeBookingId.value == 42 }
        compose.runOnIdle {
            assertEquals(Screen.ActiveTrip, vm.screen.value)
            assertNotNull(vm.activeTrip.value)
        }
        assertEquals(1, bookingBodies.size)
    }

    @Test @Config(qualifiers = "w320dp-h470dp")
    fun publicRideRemainsReachableInSmallWindow() {
        mount()
        choosePublicRide()
        compose.onNodeWithText("Забронировать место").assertIsDisplayed()
    }

    @Test fun bookingRefusalKeepsSelectedRideAndAllowsRetry() {
        rejectBookingOnce = true
        mount()
        choosePublicRide()
        compose.onNodeWithText("Забронировать место").performClick()
        compose.waitUntil(15000) {
            settle()
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast() == "Тестовый временный отказ брони"
        }
        compose.runOnIdle {
            assertEquals(Screen.Booking, vm.screen.value)
            assertEquals("7", vm.selectedRide.value?.id)
            assertNull(vm.activeBookingId.value)
            assertNull(vm.activeTrip.value)
        }
        compose.onNodeWithText("Забронировать место").performClick()
        compose.waitUntil(15000) { vm.activeBookingId.value == 42 }
        compose.onNodeWithText("Отменить бронь").assertIsDisplayed()
        assertEquals(2, bookingBodies.size)
        assertEquals(JSONObject(bookingBodies[0]).toString(), JSONObject(bookingBodies[1]).toString())
    }

    @Test fun knownBookingWaitsForSummaryInsteadOfGoingBack() = checkMissingSummary(42, 0)

    @Test fun missingRideWithoutBookingStillGoesBack() = checkMissingSummary(null, 1)

    private fun checkMissingSummary(bookingId: Int?, expectedBack: Int) {
        var backCalls = 0
        compose.setContent {
            if (stage.value != -1) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(ride = null, bookingId = bookingId, ads = emptyList(), adStats = emptyMap(),
                    onBack = { backCalls++ }, onMessage = {}, onAdImpression = {}, onAdClick = {},
                    onConfirmRide = { _, _, _, _, _ -> })
            }
        }
        compose.waitForIdle()
        assertEquals("Known booking must wait for its parent to load the summary", expectedBack, backCalls)
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalAppLanguage provides AppLanguage.Ru,
                LocalPoolingNativeMapEnabled provides false) {
                YuldashTheme {
                    when (stage.value) {
                        0 -> YuldashApp()
                        1 -> DriverCabinetScreen(rides = emptyList(), onBack = {}, onCreateRide = {}, onVerifyDriver = {}, onBoost = {})
                    }
                }
            }
        }
    }
    private fun choosePublicRide() {
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertNull("No selected Ride may be preseeded", vm.selectedRide.value) }
        compose.waitUntil(15000) {
            settle()
            requests.any { it.first == "GET /rides/near" }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val viewport = scroll.fetchSemanticsNode().boundsInRoot
        val config = ApplicationProvider.getApplicationContext<Context>().resources.configuration
        assertTrue("Public rides list needs usable height in ${config.screenWidthDp}x${config.screenHeightDp}dp: $viewport", viewport.height > 0f)
        for (attempt in 0 until 12) {
            if (compose.onAllNodesWithText("Поехать").fetchSemanticsNodes().isNotEmpty()) break
            scroll.performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeBy(500)
        }
        compose.onNodeWithText("Поехать").assertIsDisplayed()
        compose.onNodeWithText("Поехать").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertEquals(Screen.Booking, vm.screen.value)
            assertEquals("7", vm.selectedRide.value?.id)
            assertNull(vm.activeBookingId.value)
        }
    }

    // Сообщить Compose о записях состояния, сделанных вне композиции (сигнал из теста, ответы
    // ViewModel). Обычно это делает GlobalSnapshotManager, но в Robolectric он может «уснуть»
    // до конца прогона: сброс главного Looper между тестами выкидывает его отложенную отправку,
    // а флаг «уже отправлено» остаётся поднятым — экран больше не узнаёт об изменениях
    // (CI 2026-09-26: после оценки так и не появилось «Готово»). Встроенное ожидание Compose делает то же.
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun waitForText(text: String) {
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
    // Ждём, пока строка появится в списке: удачная прокрутка к ней и есть признак, что экран
    // дорисован по ответу сервера, а не только что запрос ушёл (как в CreateRidePublishJourneyTest).
    private fun awaitInList(text: String) = compose.waitUntil(15000) {
        settle()
        runCatching {
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText(text))
        }.isSuccess
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
