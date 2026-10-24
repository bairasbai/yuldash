package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.io.File
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Д2 (24.10.2026): куда попадает водитель, закрыв бронь одного пассажира, и что держит GPS рейса.
 *  - «Завершить» с оставшимися живыми бронями рейса → «Едут с тобой»; последний → экран итога, а «Закрыть»
 *    его сеть уже не ждёт; нет сети — ничего не решаем за водителя;
 *  - «Закрыть» итога / отмена: водитель с живым рейсом → «Едут с тобой», пассажир → карта, как раньше;
 *    сеть не ответила за 3 с → кабинет (там свои загрузка и «Повторить»);
 *  - YuldashApp отпускает GPS экрана через release (рейс держит), а выход гасит через stop;
 *  - кабинет сам доезжает до «Едут с тобой» и честно пишет, что машину видят пассажиры рейса.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverRideNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val lookups = AtomicInteger()
    @Volatile private var rows = "[]"
    @Volatile private var code = 200
    @Volatile private var gate: java.util.concurrent.CountDownLatch? = null   // ответ о рейсе «висит», пока не отпустим

    @Before fun prepare() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/driver/bookings") {
                    lookups.incrementAndGet()
                    gate?.await(8, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(rows)
                }
                return MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        val enc = Base64.getUrlEncoder().withoutPadding()
        ApiClient.saveToken(enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString("""{"sub":"5"}""".toByteArray()) + ".x")
    }

    @After fun cleanup() {
        gate?.countDown()
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)
        ApiClient.testBaseUrl = null
        server.shutdown()
        TripLocationBus.rideShareActive = false
    }

    private fun row(id: Int, status: String, ride: Int = 7) = """{"booking_id":$id,"ride_id":$ride,"status":"$status","pickup_stop":""}"""
    private fun serve(vararg r: String) { rows = r.joinToString(",", "[", "]") }

    /** Водитель на экране поездки брони 42, пришёл из кабинета. */
    private fun driverOn42(): YuldashViewModel = YuldashViewModel(SavedStateHandle()).apply {
        navHistory.clear(); navHistory.addAll(listOf(Screen.Home, Screen.DriverCabinet))
        openDriverTrip(42)
    }

    private fun idleUntil(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!cond() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(what, cond())
    }

    @Test fun `«Завершить» с оставшимися пассажирами ведёт в «Едут с тобой»`() {
        val vm = driverOn42()
        serve(row(42, "done"), row(43, "onboard"))
        assertTrue(runBlocking { vm.driverFinishedBooking(42) })
        assertEquals(Screen.DriverCabinet, vm.screen.value)
        assertTrue(vm.focusRidingSection.value)
        assertEquals("«Назад» не ведёт ни в закрытую поездку, ни во второй кабинет", listOf(Screen.Home), vm.navHistory.toList())
    }

    @Test fun `«Завершить» последнего — экран итога, а «Закрыть» сеть уже не ждёт`() {
        val vm = driverOn42()
        serve(row(42, "done"), row(43, "onboard", ride = 9))   // другой рейс того же водителя не считается
        assertFalse(runBlocking { vm.driverFinishedBooking(42) })
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals(42, vm.driverRideOverBooking.value)
        var map = 0
        vm.endTripScreen(42) { map++ }
        assertEquals("последний пассажир — карта, как раньше, сразу", 1, map)
        assertEquals("«Закрыть» второй раз спросил сервер", 1, lookups.get())
    }

    @Test fun `«Завершить» без ответа о рейсе ничего не решает за водителя`() {
        val vm = driverOn42()
        code = 500
        assertFalse(runBlocking { vm.driverFinishedBooking(42) })
        assertEquals(Screen.ActiveTrip, vm.screen.value)
        assertEquals("неизвестный исход не должен выдаваться за «рейс окончен»", 0, vm.driverRideOverBooking.value)
    }

    @Test fun `«Закрыть» итога у водителя с живым рейсом — «Едут с тобой»`() {
        val vm = driverOn42()
        serve(row(42, "cancelled"), row(43, "confirmed"))
        var map = 0
        vm.endTripScreen(42) { map++ }
        idleUntil("водитель не попал в «Едут с тобой»") { vm.screen.value == Screen.DriverCabinet }
        assertEquals(0, map)
    }

    @Test fun `«Закрыть» итога у пассажира — карта, как раньше, без запросов`() {
        val vm = YuldashViewModel(SavedStateHandle()).apply {
            activeBookingId.value = 42; activeTrip.value = bookingLinkStubRide(42); screen.value = Screen.ActiveTrip
        }
        var map = 0
        vm.endTripScreen(42) { map++ }
        assertEquals(1, map)
        assertEquals(0, lookups.get())
    }

    @Test fun `сеть молчит дольше 3 секунд — водитель в кабинете, а не на зависшем экране`() {
        val vm = driverOn42()
        serve(row(42, "done"), row(43, "onboard"))
        gate = java.util.concurrent.CountDownLatch(1)
        vm.endTripScreen(42) { }
        idleUntil("список не спросили") { lookups.get() == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DRIVER_RIDE_LOOKUP_MS + 500))
        idleUntil("«Закрыть» ждёт сеть без конца") { vm.screen.value == Screen.DriverCabinet }
    }

    @Test fun `экран отпускает GPS через release, выход гасит через stop`() {
        val src = File("src/main/java/com/yuldash/app/YuldashApp.kt").readText()
        val effect = src.substringAfter("LaunchedEffect(activeBookingId, activeTrip").substringBefore("\n    }")
        assertTrue("уход с экрана снова гасит GPS рейса", effect.contains("TripLocationService.release(context)"))
        assertFalse(effect.contains("TripLocationService.stop(context)"))
        assertTrue(src.contains("onDriverFinished = { bid -> vm.driverFinishedBooking(bid) }"))
        assertTrue(src.contains("onTripEnd = { vm.endTripScreen(activeBookingId)"))
        val stopper = src.substringAfter("internal fun stopLiveTracking").substringBefore("\n}")
        assertTrue("выход из аккаунта перестал гасить GPS", stopper.contains("TripLocationService.stop(context)"))
    }

    @Test fun `кабинет сам доезжает до «Едут с тобой» и говорит, что машину видят пассажиры рейса`() {
        TripLocationBus.rideShareActive = true
        var done = 0
        val rides = (1..12).map { Ride(id = "$it", from = "Уфа", to = "Сибай $it", time = "", driver = "", car = "", price = 500, seats = 2,
            rating = 0.0, verified = false, boosted = false) }
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverCabinetContent(
                    online = false, driverRides = rides, ratingText = "—", onToggleOnline = {},
                    driverBookings = listOf(DriverBookingDto(bookingId = 43, passengerName = "Айгуль", passengerRating = null,
                        route = "Уфа → Сибай", status = "onboard")),
                    onRate = { _, _, d -> d(true) }, onCreateRide = {}, onVerifyDriver = {}, onBoost = {}, onRequestsFeed = {},
                    focusRiding = true, onFocusRidingDone = { done++ },
                )
            }
        }
        composeRule.waitUntil(10_000) { done > 0 }
        composeRule.onNodeWithText("Едут с тобой").assertIsDisplayed()
        composeRule.onNodeWithText("Машину видят пассажиры этого рейса. Перестанем показывать, когда завершишь последнего.")
            .assertIsDisplayed()
    }
}
