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
import com.yuldash.app.data.BookingCommandApi
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingConfirmationJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val stage = mutableStateOf(0)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val responseLedger = CopyOnWriteArrayList<String>()
    private val bookingBodies = CopyOnWriteArrayList<String>()
    private val bookingKeys = CopyOnWriteArrayList<String>()
    private val tripStatuses = CopyOnWriteArrayList<String>()
    private var rejectBookingOnce = false
    private var conflictBooking = false
    private val ratingBodies = CopyOnWriteArrayList<String>()
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    @Volatile private var status = "pending"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().clear()
            .putString("mode_last", "pooling").putBoolean("mode_hint_shown", true).commit()
        ApiClient.resetForTest()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    requests.add("${request.method} $path" to request.getHeader("Authorization"))
                    val bearer = request.getHeader("Authorization")
                    val actor = when (bearer) {
                        "Bearer ${TripPassAuthorityFixture.jwt(7)}" -> 7
                        "Bearer ${TripPassAuthorityFixture.jwt(9)}" -> 9
                        else -> null
                    }
                    val body = when {
                        path == "/me" && actor != null ->
                            """{"id":$actor,"created_at":"2026-01-01T00:00:00Z","role":"${if (actor == 9) "driver" else "passenger"}"}"""
                        path == "/rides/near" -> """{"items":[{"id":7,"from_city":"Пункт А","to_city":"Пункт Б","depart_at":"2030-01-02T10:00:00+03:00","seats_total":3,"seats_left":3,"price":750,"category":"regular","driver_id":9,"driver_name":"Тестовый водитель","driver_verified":true}],"count":1}"""
                        path in listOf("/requests/near", "/seasonal-events", "/popular-routes", "/rides") -> """{"items":[]}"""
                        request.method == "POST" && path == "/bookings/commands/v1" -> {
                            if (actor != 7) return response(path, "{}", 403)
                            val key = request.getHeader("Idempotency-Key")
                            if (key.isNullOrBlank()) return response(path, "{}", 422)
                            bookingKeys.add(key)
                            bookingBodies.add(request.body.readUtf8())
                            if (rejectBookingOnce && bookingBodies.size == 1) return response(path, """{"detail":"Тестовый временный отказ брони"}""", 503)
                            if (conflictBooking) return response(path, """{"detail":{"ru":"У тебя уже есть бронь с другим количеством мест.","ba":"Һинең икенсе һандағы урындар менән бронең бар инде.","code":"booking_seats_conflict","booking_id":42}}""", 409)
                            """{"id":42,"ride_id":7,"passenger_id":7,"status":"$status"}"""
                        }
                        request.method == "GET" && actor == 7 && path == "/command-intents/${bookingKeys.lastOrNull()}" && conflictBooking ->
                            """{"state":"unseen","scope":null,"ref_id":null,"current":null}"""
                        request.method == "POST" && path == "/bookings/42/commands/v1/confirm" -> {
                            if (actor != 9) return response(path, "{}", 403)
                            if (request.getHeader("Idempotency-Key").isNullOrBlank()) return response(path, "{}", 422)
                            status = "confirmed"
                            """{"id":42,"ride_id":7,"passenger_id":7,"status":"confirmed"}"""
                        }
                        path == "/bookings/mine" -> """{"items":[{"id":42,"ride_id":7,"status":"$status","seats":1,"price":750,"from_city":"Пункт А","to_city":"Пункт Б","driver_name":"Тестовый водитель"}]}"""
                        path == "/bookings/42/trip-status" -> {
                            if (request.getHeader("Authorization") != "Bearer ${TripPassAuthorityFixture.jwt(7)}") return response(path, "{}", 403)
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
                        path == "/bookings/42/details" && actor == 7 -> JSONObject().put("booking_id", 42).put("ride_id", 7).put("role", "passenger")
                            .put("status", status).put("from_city", "Пункт А").put("to_city", "Пункт Б")
                            .put("contact_unlocked", status in setOf("confirmed", "onboard"))
                            .put("onboard_at", if (status == "onboard") "2031-02-03T09:00:00.123456Z" else JSONObject.NULL).toString()
                        path == "/bookings/42/role" -> JSONObject().put("role", "passenger").put("status", status).put("driver_phase", "")
                            .put("onboard_at", if (status == "onboard") "2031-02-03T09:00:00.123456Z" else JSONObject.NULL).toString()
                        path == "/bookings/42/messages" -> """{"items":[]}"""
                        path == "/bookings/42/boarding-code" -> if (status in setOf("confirmed", "onboard")) """{"code":"5678"}""" else return response(path, "{}", 403)
                        else -> return response(path, "{}", 404)
                    }
                    return response(path, body)
                }
            }
            start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        TripPassAuthorityFixture.installSession(server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'))
        BookingCommandApi.init(context.getSharedPreferences("booking_confirmation_journey_commands", Context.MODE_PRIVATE).apply {
            edit().clear().commit()
        })
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

    @Test fun publicZeroSeatsCannotSubmitEvenWithDefaultActiveFlag() {
        assertPublicCapacity(AppLanguage.Ru, "Мест нет", "Забронировать место")
    }

    @Test fun bashkirPublicZeroSeatsCannotSubmitEvenWithDefaultActiveFlag() {
        assertPublicCapacity(AppLanguage.Ba, "Урын юҡ", "Урынды бронләү")
    }

    private fun assertPublicCapacity(language: AppLanguage, unavailable: String, book: String) {
        val ride = mutableStateOf(Ride(
            id = "7", from = "Пункт А", to = "Пункт Б", time = "",
            driver = "Тестовый водитель", car = "", price = 750, seats = 0,
            rating = 5.0, verified = true, boosted = false,
        ))
        var submitted = 0
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    BookingScreen(
                        ride = ride.value, ads = emptyList(), adStats = emptyMap(),
                        onBack = {}, onMessage = {}, onAdImpression = {}, onAdClick = {},
                        // Omit canOpenActiveTrip: its default must not authorize a new booking.
                        onConfirmRide = { _, _, _, _, _, _ -> submitted++ },
                    )
                }
            }
        }
        compose.onNodeWithText(unavailable).assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, submitted); ride.value = ride.value.copy(seats = 1) }
        compose.onNodeWithText(book).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
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
        compose.runOnIdle { ApiClient.logout(); ApiClient.saveToken(TripPassAuthorityFixture.jwt(9)); stage.value = 1 }
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
        assertEquals(1, requests.count { it == ("POST /bookings/42/commands/v1/confirm" to "Bearer ${TripPassAuthorityFixture.jwt(9)}") })
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
            ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
            vm.selectedRide.value = null
            vm.activeTrip.value = null
            vm.screen.value = Screen.Booking
            stage.value = 0
        }
        waitForText("Открыть поездку")
        compose.onNodeWithText("Открыть поездку").performClick()
        // Посадку отмечает водитель (решение 17.10.2026): у пассажира «Водитель отметит посадку»,
        // своего «Я сел» нет — его можно было нажать из дома, чтобы уйти от «Не пришёл».
        waitForText("Водитель отметит посадку")
        compose.onAllNodesWithText("Я сел").assertCountEquals(0)
        compose.waitUntil(15000) { requests.any { it.first == "GET /bookings/42/boarding-code" } }
        awaitInList("Код посадки")   // тот же шаблон: ждём сам блок, а не отправку запроса
        waitForText("5678")
        compose.onAllNodesWithText("5678").assertCountEquals(1)
        // Водитель отметил «Сел» — пассажир узнаёт это из очередного опроса состояния (раз в ~12 с).
        status = "onboard"
        advance(14_000)
        waitForText("Доехал")
        compose.onNodeWithText("Доехал").performClick()
        compose.waitUntil(15000) { tripStatuses.contains("arrived") }
        waitForText("Завершить")
        compose.onNodeWithText("Завершить").performClick()
        waitForText("Поездка завершена")
        assertEquals("пассажир сам посадку не отмечает", listOf("arrived", "done"), tripStatuses.toList())
        compose.waitUntil(15000) {
            settle()
            runCatching {
                compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareStar5"))
                compose.onNodeWithTag("rideshareStar5").assertIsDisplayed().assertIsEnabled()
            }.isSuccess
        }
        compose.onNodeWithTag("rideshareStar5").performClick()
        // Выбор звезды и включение кнопки — разные кадры; не отправляем на ещё выключенную кнопку.
        compose.waitUntil(15000) {
            settle()
            compose.onAllNodesWithTag("rideshareRatingSubmit").fetchSemanticsNodes()
                .singleOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled().performClick()
        waitForSavedRating()
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
        compose.waitUntil(15000) { settle(); OfflineTripSnapshots.allowsLive(42) && vm.activeTrip.value != null }
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
        val book = compose.onNodeWithText("Забронировать место")
        compose.waitUntil(15000) {
            settle()
            runCatching { book.assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        book.performClick()
        waitForText("Повторить тот же запрос")
        compose.runOnIdle {
            assertEquals(Screen.Booking, vm.screen.value)
            assertEquals("7", vm.selectedRide.value?.id)
            assertNull(vm.activeBookingId.value)
            assertNull(vm.activeTrip.value)
        }
        compose.onNodeWithText("Повторить тот же запрос").performClick()
        compose.waitUntil(15000) { vm.activeBookingId.value == 42 }
        compose.onNodeWithText("Отменить бронь").assertIsDisplayed()
        assertEquals(2, bookingBodies.size)
        assertEquals(listOf(bookingKeys.first(), bookingKeys.first()), bookingKeys.toList())
        assertEquals(JSONObject(bookingBodies[0]).toString(), JSONObject(bookingBodies[1]).toString())
    }

    @Test fun multiseatSelectionSendsTotalAndKeepsPayloadOnRetry() {
        rejectBookingOnce = true
        mount()
        choosePublicRide()
        compose.onNodeWithText("Количество мест").performScrollTo()
        repeat(2) { compose.onNodeWithContentDescription("Больше мест").performClick() }
        compose.onNodeWithText("Итого: 2 250 ₽").assertExists()
        // Кнопка называет выбранное число мест (приёмка #189).
        compose.onNodeWithText("Забронировать 3 места").performClick()
        waitForText("Повторить тот же запрос")
        compose.onNodeWithText("Повторить тот же запрос").performClick()
        compose.waitUntil(15000) { vm.activeBookingId.value == 42 }
        assertEquals(2, bookingBodies.size)
        assertEquals(listOf(bookingKeys.first(), bookingKeys.first()), bookingKeys.toList())
        val first = JSONObject(bookingBodies[0])
        assertEquals(3, first.getInt("seats"))
        assertEquals(2250, first.getInt("pay_amount"))
        assertEquals(first.toString(), JSONObject(bookingBodies[1]).toString())
    }

    /** Бронь на 2 места уже есть (например, по заявке), человек открыл ту же поездку из ленты: отказ
     *  «другое число мест» называет его бронь — приложение открывает её (приёмка #189). */
    @Test fun existingBookingConflictOpensOwnBooking() {
        conflictBooking = true
        mount()
        choosePublicRide()
        compose.onNodeWithText("Забронировать место").performClick()
        awaitInList("Открыть бронь")
        compose.runOnIdle { assertNull(vm.activeBookingId.value) }
        compose.onNodeWithText("Открыть бронь").performClick()
        compose.waitUntil(15000) { settle(); vm.activeBookingId.value == 42 }
        assertEquals(1, bookingBodies.size)
    }

    @Test fun knownBookingWaitsForSummaryInsteadOfGoingBack() = checkMissingSummary(42, 0)

    @Test fun missingRideWithoutBookingStillGoesBack() = checkMissingSummary(null, 1)

    private fun checkMissingSummary(bookingId: Int?, expectedBack: Int) {
        var backCalls = 0
        compose.setContent {
            if (stage.value != -1) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingScreen(ride = null, bookingId = bookingId, ads = emptyList(), adStats = emptyMap(),
                    onBack = { backCalls++ }, onMessage = {}, onAdImpression = {}, onAdClick = {},
                    onConfirmRide = { _, _, _, _, _, _ -> })
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
    /** Прокрутить время вперёд: опрос состояния поездки ходит раз в ~12 с. */
    private fun advance(millis: Long) = repeat((millis / 100).toInt()) {
        compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        Snapshot.sendApplyNotifications()
    }
    private fun waitForSavedRating() {
        try {
            compose.waitUntil(15000) {
                settle()
                ratingBodies.size == 1 && compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Оценка не подтверждена: POST=${ratingBodies.size}, " +
                "stage=${stage.value}, screen=${vm.screen.value}, requests=${requests.map { it.first }}, " +
                "responses=$responseLedger", failure)
        }
    }
    private fun waitForText(text: String) {
        try {
            compose.waitUntil(15000) {
                compose.mainClock.advanceTimeByFrame()
                settle()
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            val target = text.takeUnless { value -> value.any { it.isDigit() } } ?: "<скрытое значение>"
            // Только синтетические пути/HTTP-статусы и этапы: без bearer, тел, телефонов или кодов.
            throw AssertionError("Не появился экранный шаг '$target': stage=${stage.value}, " +
                "screen=${vm.screen.value}, booking=${vm.activeBookingId.value}, " +
                "serverStatus=$status, live=${OfflineTripSnapshots.allowsLive(42)}, " +
                "terminal=${OfflineTripSnapshots.terminal(42)?.status}, posts=${tripStatuses.toList()}, " +
                "requests=${requests.map { it.first }}, responses=$responseLedger", failure)
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
    private fun response(path: String, body: String, code: Int = 200): MockResponse {
        val tripStatus = if (path in setOf("/bookings/42/role", "/bookings/42/details"))
            ":${JSONObject(body).optString("status")}" else ""
        responseLedger += "$path:$code$tripStatus"
        return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    }
}
