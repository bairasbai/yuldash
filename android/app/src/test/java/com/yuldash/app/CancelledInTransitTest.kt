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
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassDeletion
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.TripStateDto
import com.yuldash.app.data.parseBookingDetailsDto
import com.yuldash.app.data.parseBookingMineDto
import com.yuldash.app.data.parseDriverBookingDto
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Отмена, когда пассажир уже в машине, — честная (решение Александра 23.10.2026), экраны приложения.
 *
 * Было: водитель снял поездку на трассе — экран поездки у пассажира сам закрывался, как при любой
 * отмене, и человек оказывался на карте с «Найти другую поездку». Ни SOS, ни «Застрял на трассе», ни
 * госномера машины, из которой его высадили. Стало: по признаку сервера `cancelled_in_transit` экран
 * остаётся — «Поездку отменили в пути», SOS, «Застрял на трассе», «Сообщить о проблеме» (окно 6 ч),
 * машина и госномер; телефона водителя нет. Обычная отмена закрывает экран ровно как раньше.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CancelledInTransitTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val plain = MemoryDiskPreferences()
    private val secure = MemoryDiskPreferences()
    private val mounted = mutableStateOf(false)
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()
    private var tripEndCalls = 0
    private var sosCalls = 0
    private var stoppedCalls = 0
    private var leaveOnSos = false
    @Volatile private var role = "passenger"
    // Признак отмены в пути — у каждой ручки свой: проверяем и случай, когда он пришёл только из одной.
    @Volatile private var roleFlag = true
    @Volatile private var detailsFlag = true
    private var inTransit: Boolean
        get() = roleFlag && detailsFlag
        set(value) { roleFlag = value; detailsFlag = value }
    @Volatile private var reportSec = 3600
    @Volatile private var offline = false   // трасса без связи: каждое соединение рвётся
    @Volatile private var detailsGate: CountDownLatch? = null   // детали брони отвечают только после открытия
    @Volatile private var detailsAfterRole = false   // детали отвечают позже первого опроса /role
    @Volatile private var roleAfterDetails = false   // опрос /role отвечает позже первых деталей
    private val roleServed = CountDownLatch(1)
    private val detailsServed = CountDownLatch(1)

    private fun roleJson() = """{"role":"$role","status":"cancelled","driver_phase":"","peer_id":9,""" +
        """"problem_report_sec":$reportSec,"cancelled_in_transit":$roleFlag}"""

    private fun detailsJson() = """{"booking_id":42,"ride_id":7,"role":"$role","status":"cancelled","contact_unlocked":false,""" +
        """"from_city":"Город А","to_city":"Город Б","driver_name":"Ринат","driver_car":"Лада Веста",""" +
        """"driver_car_color":"серая","driver_plate":"М555ОР102","driver_phone":"","cancelled_in_transit":$detailsFlag}"""

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.logout()   // без входа ChatSocket не открывает настоящий WebSocket
        TripPassStore.initStores(plain, secure)
        InTransitCancels.clearForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.path}")
                    if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    val body = when (request.path) {
                        "/bookings/42/role" -> {
                            if (roleAfterDetails) { detailsServed.await(10, TimeUnit.SECONDS); Thread.sleep(300) }
                            roleJson().also { roleServed.countDown() }
                        }
                        "/bookings/42/details" -> {
                            detailsGate?.await(10, TimeUnit.SECONDS)
                            if (detailsAfterRole) { roleServed.await(10, TimeUnit.SECONDS); Thread.sleep(300) }
                            detailsJson().also { detailsServed.countDown() }
                        }
                        "/bookings/42/messages" -> """{"items":[]}"""
                        "/bookings/42/boarding-code" -> """{"code":""}"""
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        detailsGate?.countDown()
        compose.runOnIdle { mounted.value = false }
        settle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        InTransitCancels.clearForTest()
        server.shutdown()
    }

    // Ожидание простых переменных (счётчики, диск) — idle Looper + доставка снимков (testing.md, правило 5).
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15_000) { settle(); condition() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("$what: запросы=${requests.toList()}, tripEnd=$tripEndCalls, " +
                "память=[${InTransitCancels.describeForTest()}], сессия=${ApiClient.queueSessionGeneration()}, " +
                "экран=${screenTree()}", e)
        }
    }

    // Что нарисовано на самом деле (все корни, в том числе окна): без этого сбой в CI не разобрать.
    private fun screenTree(): String = runCatching {
        "подсказка=${InTransitCancels.hint(42, ApiClient.queueSessionGeneration())} | " +
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = 60).replace("\n", " | ").take(6000)
    }.getOrElse { "не снять: ${it.message}" }

    private fun waitForText(text: String) = waitFor("нет «$text»") {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Паспорт поездки, сохранённый, пока бронь была живой: с телефоном водителя. */
    private fun savePass() {
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("driver_name", "Ринат")
            .put("driver_car", "серая Лада Веста").put("driver_plate", "М555ОР102")
            .put("driver_phone", "+79990001122").put("boarding_code", "4821"))
        assertTrue(TripPassStore.save(context, pass))
    }

    private fun openTrip(language: AppLanguage = AppLanguage.Ru, hint: ActiveTripTerminalHint? = null) {
        mounted.value = true
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    // Без поездки и координат городов настоящий MapKit не поднимается.
                    ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42, terminalHint = hint,
                        onBack = {}, onTripEnd = { tripEndCalls++; mounted.value = false },
                        onSos = { sosCalls++; if (leaveOnSos) mounted.value = false },   // уход на экран SOS
                        onTripStopped = { stoppedCalls++ })
                }
            }
        }
    }

    // ── Экран поездки ──

    @Test fun `отменили в пути — экран не закрывается сам и ведёт к помощи`() {
        savePass()
        openTrip()
        waitForText("Поездку отменили в пути")
        // Паспорт с телефоном водителя всё равно удаляется — остаются машина и номер.
        waitFor("паспорт не удалён") { !secure.restarted().contains("pass_42") }
        repeat(20) { settle() }
        compose.waitForIdle()
        assertEquals("экран отмены в пути закрылся сам", 0, tripEndCalls)
        assertTrue(mounted.value)
        compose.onNodeWithText("Поездку отменили в пути").assertIsDisplayed()
        compose.onNodeWithText("серая Лада Веста").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("М555ОР102").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("+79990001122", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Найти другую поездку").assertCountEquals(0)
        compose.onNodeWithText("SOS — нужна помощь").performScrollTo().performClick()
        assertEquals(1, sosCalls)
        compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        waitFor("«Закрыть» не закрыл") { tripEndCalls == 1 }
        assertFalse(mounted.value)
    }

    @Test fun `обычная отмена закрывает экран как раньше`() {
        inTransit = false
        savePass()
        openTrip()
        waitFor("обычная отмена не закрыла экран") { tripEndCalls > 0 }
        compose.waitForIdle()
        assertEquals(1, tripEndCalls)
        assertFalse(mounted.value)
        assertFalse(secure.restarted().contains("pass_42"))
    }

    @Test fun `водителю отмена в пути закрывает экран как раньше — он отменил сам`() {
        role = "driver"
        openTrip()
        waitFor("экран водителя не закрылся") { tripEndCalls > 0 }
        assertEquals(1, tripEndCalls)
    }

    @Test fun `по пушу — сразу экран помощи, Застрял на трассе и Сообщить о проблеме`() {
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")   // «Застрял» и разбор живут только во входе
        openTrip(hint = knownTerminalTripHint(42, "cancelled", "passenger", inTransit = true))
        compose.onNodeWithText("Поездку отменили в пути").assertIsDisplayed()
        // Паспорта нет — машину и номер экран дочитывает из деталей брони сам.
        waitForText("М555ОР102")
        assertTrue(requests.contains("GET /bookings/42/details"))
        compose.onNodeWithText("Застрял на трассе").performScrollTo().assertIsDisplayed()
        waitForText("Сообщить о проблеме")   // окно разбора и водитель — из ответа /role
        compose.onNodeWithText("Сообщить о проблеме").performScrollTo().performClick()
        waitForText("Нагрубили")   // тип не подставлен: что случилось, знает человек
        assertEquals(0, tripEndCalls)
    }

    @Test fun `окно разбора закрыто — Сообщить о проблеме нет`() {
        reportSec = 0
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        openTrip(hint = knownTerminalTripHint(42, "cancelled", "passenger", inTransit = true))
        waitForText("М555ОР102")
        waitFor("не спросили /role") { requests.contains("GET /bookings/42/role") }
        repeat(10) { settle() }
        compose.onAllNodesWithText("Сообщить о проблеме").assertCountEquals(0)
        compose.onNodeWithText("Застрял на трассе").performScrollTo().assertIsDisplayed()
    }

    @Test fun `по-башкирски — тот же экран`() {
        openTrip(AppLanguage.Ba, knownTerminalTripHint(42, "cancelled", "passenger", inTransit = true))
        compose.onNodeWithText("Сәфәр юлда кире алынды").assertIsDisplayed()
        compose.onNodeWithText("SOS — ярҙам кәрәк").performScrollTo().assertIsDisplayed()
        waitForText("М555ОР102")
        compose.onNodeWithText("Машина һәм дәүләт номеры").assertIsDisplayed()
        compose.onNodeWithText("Ябырға").performScrollTo().assertIsDisplayed()
    }

    // ── «Детали поездки», куда ведёт пуш ──

    private fun openBooking(language: AppLanguage = AppLanguage.Ru) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                BookingScreen(
                    ride = Ride(id = "7", from = "Город А", to = "Город Б", time = "завтра, 09:00", driver = "Ринат",
                        car = "Лада Веста", price = 500, seats = 1, rating = 4.9, verified = true, boosted = false),
                    bookingId = 42, ads = emptyList(), adStats = emptyMap(), onBack = {}, onMessage = {},
                    onAdImpression = {}, onAdClick = {}, bookingStatus = "cancelled", onSos = { sosCalls++ },
                    onConfirmRide = { _, _, _, _, _ -> fail("история брони не создаёт бронь") },
                )
            }
        }
    }

    @Test fun `детали брони — вместо «Найти другую поездку» путь к помощи`() {
        openBooking()
        waitForText("Поездку отменили в пути")
        compose.onAllNodesWithText("Найти другую поездку").assertCountEquals(0)
        compose.onAllNodesWithText("Бронь отменена").assertCountEquals(0)
        compose.onNodeWithText("серая Лада Веста").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("М555ОР102").performScrollTo().assertIsDisplayed()
        waitFor("не дочитали водителя и окно разбора") { requests.contains("GET /bookings/42/role") }
        compose.onNodeWithText("SOS — нужна помощь").performScrollTo().performClick()
        assertEquals(1, sosCalls)
    }

    @Test fun `детали брони по-башкирски`() {
        openBooking(AppLanguage.Ba)
        waitForText("Сәфәр юлда кире алынды")
        compose.onAllNodesWithText("Башҡа сәфәр табыу").assertCountEquals(0)
        compose.onNodeWithText("SOS — ярҙам кәрәк").performScrollTo().assertIsDisplayed()
    }

    @Test fun `обычная отмена в деталях брони — «Найти другую поездку», как раньше`() {
        inTransit = false
        openBooking()
        // Сначала свежие детали с сервера, потом проверка: «Найти другую поездку» видно и до них.
        waitFor("детали не загрузились") {
            requests.contains("GET /bookings/42/details") &&
                compose.onAllNodesWithText("Обновляем подтверждение поездки").fetchSemanticsNodes().isEmpty()
        }
        waitForText("Найти другую поездку")
        compose.onAllNodesWithText("Поездку отменили в пути").assertCountEquals(0)
        assertFalse("обычной отмене /role не нужен", requests.contains("GET /bookings/42/role"))
    }

    // ── Куда ведёт пуш, признак и разбор ответов ──

    @Test fun `пуш пассажиру об отмене в пути ведёт на экран поездки, остальное — как было`() {
        assertEquals(Screen.ActiveTrip, bookingLinkScreen("passenger", "cancelled", completed = false, cancelledInTransit = true))
        assertEquals(Screen.Booking, bookingLinkScreen("passenger", "cancelled", completed = false))
        assertEquals("водителю — кабинет, как раньше", Screen.DriverCabinet,
            bookingLinkScreen("driver", "cancelled", completed = false, cancelledInTransit = true))
        val vm = YuldashViewModel(androidx.lifecycle.SavedStateHandle())
        assertEquals(Screen.ActiveTrip, vm.applyBookingLink(42, "passenger", "cancelled", completed = false, cancelledInTransit = true))
        assertNull("живое гео по отменённой брони не включаем", vm.activeTrip.value)
        assertEquals(42, vm.activeBookingId.value)
        assertTrue(knownTerminalTripHint(42, "cancelled", "passenger", inTransit = true)!!.inTransit)
        assertFalse("признак — только у отменённой", knownTerminalTripHint(42, "done", "passenger", inTransit = true)!!.inTransit)
    }

    @Test fun `признак — только со слова сервера и только у пассажира`() {
        val state = CancelledInTransitState(false)
        assertFalse(state.keepsScreen("passenger"))
        state.accept(TripStateDto("passenger", "cancelled", "", peerId = 9, problemReportSec = 60, cancelledInTransit = false))
        assertFalse("обычная отмена — экран закрывается", state.keepsScreen("passenger"))
        state.accept(TripStateDto("passenger", "cancelled", "", peerId = 9, problemReportSec = 60, cancelledInTransit = true))
        assertTrue(state.keepsScreen("passenger"))
        assertFalse(state.keepsScreen("driver"))
        assertFalse(state.keepsScreen(""))
        assertEquals(9, state.driverId)
        assertEquals(60, state.reportSec)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("driver_car", "серая Лада Веста")
            .put("driver_plate", "М555ОР102").put("driver_phone", "+79990001122"))
        state.keep(pass)
        assertEquals(InTransitCar("серая Лада Веста", "М555ОР102"), state.car)
    }

    @Test fun `ответы сервера — признак разбирается, старый сервер даёт false`() {
        val details = parseBookingDetailsDto(JSONObject(detailsJson()))
        assertTrue(details.cancelledInTransit)
        assertTrue(showsCancelledInTransit(details))
        assertEquals(InTransitCar("серая Лада Веста", "М555ОР102"), inTransitCarOf(details))
        assertFalse(parseBookingDetailsDto(JSONObject("""{"booking_id":42,"status":"cancelled"}""")).cancelledInTransit)
        assertFalse("водителю экрана нет", showsCancelledInTransit(details.copy(role = "driver")))
        assertTrue(parseBookingMineDto(JSONObject("""{"id":42,"status":"cancelled","cancelled_in_transit":true}""")).cancelledInTransit)
        assertFalse(parseBookingMineDto(JSONObject("""{"id":42,"status":"cancelled"}""")).cancelledInTransit)
        assertEquals(7, parseDriverBookingDto(JSONObject("""{"booking_id":42,"ride_id":7,"status":"onboard"}""")).rideId)
        assertEquals("старый сервер без ride_id", 0, parseDriverBookingDto(JSONObject("""{"booking_id":42}""")).rideId)
    }

    // ── Приёмка PR207: поездка гаснет сразу, экран помощи переживает уход и сеть, признак из одной ручки ──

    @Test fun `экран помощи сразу гасит живое гео и маршрут, но сам остаётся`() {
        savePass()
        openTrip()
        waitForText("Поездку отменили в пути")
        waitFor("поездку не остановили") { stoppedCalls == 1 }
        repeat(10) { settle() }
        assertEquals("ровно один раз", 1, stoppedCalls)
        assertEquals("экран помощи не закрылся", 0, tripEndCalls)
        // Приложение по этому сигналу гасит activeTrip: сервис живого гео и маршрут на главной карте.
        val app = File("src/main/java/com/yuldash/app/YuldashApp.kt").readText()
        assertTrue("YuldashApp не гасит поездку", app.contains("onTripStopped = { activeTrip = null }"))
    }

    private fun sosAndBackWithoutNetwork(lostNotification: Boolean) {
        savePass()
        leaveOnSos = true
        openTrip()
        // Сначала экран помощи, потом госномер на нём: госномер виден и в паспорте живой поездки,
        // и SOS, нажатый до экрана помощи, проверял бы не то.
        waitForText("Поездку отменили в пути")
        waitForText("М555ОР102")
        compose.onNodeWithText("SOS — нужна помощь").performScrollTo().performClick()
        waitFor("не ушли на SOS") {
            !mounted.value && compose.onAllNodesWithText("Поездку отменили в пути").fetchSemanticsNodes().isEmpty()
        }
        offline = true   // паспорт уже удалён, сервер недоступен — помнит только этот вход
        val back = {
            compose.runOnIdle { mounted.value = true }
            waitForText("Поездку отменили в пути")
            waitForText("М555ОР102")
        }
        if (lostNotification) withLostGlobalSnapshotNotification(back) else back()
        compose.onNodeWithText("SOS — нужна помощь").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Обновляем данные поездки").assertCountEquals(0)
        assertEquals(1, sosCalls)
        assertEquals(0, tripEndCalls)
    }

    @Test fun `SOS и назад без сети — экран помощи и госномер на месте`() = sosAndBackWithoutNetwork(false)

    @Test fun `SOS и назад без сети — и когда сигнал снимка потерялся`() = sosAndBackWithoutNetwork(true)

    @Test fun `чужой вход не видит запомненную отмену в пути`() {
        val owner = ApiClient.queueSessionGeneration()
        InTransitCancels.record(42, owner, InTransitCar("серая Лада Веста", "М555ОР102"), "Ринат")
        assertEquals(knownTerminalTripHint(42, "cancelled", "passenger", inTransit = true), InTransitCancels.hint(42, owner))
        assertNull("другой вход", InTransitCancels.hint(42, owner + 1))
        assertNull("запись прошлого входа стёрта", InTransitCancels.hint(42, owner))
        val state = CancelledInTransitState(false)
        InTransitCancels.restoreInto(42, owner, state)
        assertFalse(state.cancelled)
        assertNull(state.car)
    }

    @Test fun `признак только в деталях брони — экран помощи всё равно остаётся`() {
        roleFlag = false   // опрос /role о пути молчит
        roleAfterDetails = true   // детали брони (экран читает их сам, пока поездка жива) пришли первыми
        savePass()
        openTrip()
        waitForText("Поездку отменили в пути")
        waitForText("М555ОР102")
        // Ответ опроса применён (водитель и окно разбора — только из /role), и экран всё ещё здесь.
        waitForText("Сообщить о проблеме")
        repeat(10) { settle() }
        assertEquals("опрос без признака закрыл экран помощи", 0, tripEndCalls)
    }

    @Test fun `признак только в опросе — экран помощи, машина из деталей`() {
        detailsFlag = false   // детали о пути молчат, но машину и номер отдают
        detailsAfterRole = true
        openTrip()
        waitForText("Поездку отменили в пути")
        waitForText("М555ОР102")
        repeat(10) { settle() }
        assertEquals("экран помощи закрылся", 0, tripEndCalls)
    }

    @Test fun `паспорт не удалился — «Закрыть» убирает окно, экран помощи остаётся`() {
        plain.failWriteOf = TripPassDeletion.KEY
        savePass()
        openTrip()
        waitForText("Не удалось удалить сохранённую поездку")
        compose.onNode(hasText("Закрыть") and hasAnyAncestor(isDialog())).performClick()
        waitFor("окно не закрылось") {
            compose.onAllNodesWithText("Не удалось удалить сохранённую поездку").fetchSemanticsNodes().isEmpty()
        }
        repeat(10) { settle() }
        assertEquals("ушли с экрана помощи", 0, tripEndCalls)
        compose.onNodeWithText("Поездку отменили в пути").assertIsDisplayed()
    }

    @Test fun `детали отменённой брони ещё грузятся — загрузка, а не «Найти другую поездку»`() {
        val gate = CountDownLatch(1)
        detailsGate = gate
        openBooking()
        waitFor("детали не запрошены") { requests.contains("GET /bookings/42/details") }
        repeat(5) { settle() }
        compose.onNodeWithTag("cancelledBookingLoading").assertIsDisplayed()
        compose.onAllNodesWithText("Найти другую поездку").assertCountEquals(0)
        gate.countDown()
        waitForText("Поездку отменили в пути")
        compose.onAllNodesWithText("Найти другую поездку").assertCountEquals(0)
    }
}
