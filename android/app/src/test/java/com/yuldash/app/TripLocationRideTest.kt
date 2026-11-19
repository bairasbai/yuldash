package com.yuldash.app

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPS водителя на весь рейс (Д2, решение Александра 24.10.2026) — сквозь настоящую службу:
 * GPS → [TripLocationService] → канал брони на локальном сервере, список рейса — `/driver/bookings`.
 *
 * После интеграции используется живое разрешение S: обычная сеть/очередь не создаёт и не продлевает его.
 * Проверяем реальные HTTP/WS-кадры, смену несущей, раннее закрытие канала, сеть, сроки и выход.
 * Холодный старт по prefs запрещён; уход с экрана проверяет отдельный TripLocationPermissionEffectTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripLocationRideTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var service: TripLocationService? = null
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val prefs get() = app.getSharedPreferences("trip_location_svc", Context.MODE_PRIVATE)

    private val opened = LinkedBlockingQueue<Int>()                       // брони, чей канал вошёл
    private val sockets = ConcurrentHashMap<Int, WebSocket>()             // серверный конец канала брони
    private val frames = LinkedBlockingQueue<Pair<Int, JSONObject>>()     // кадры точки: бронь → кадр
    private val lookups = AtomicInteger()                                 // сколько раз спросили /driver/bookings
    @Volatile private var rows = "[]"
    @Volatile private var lookupGate: CountDownLatch? = null
    @Volatile private var stepReplyGate: CountDownLatch? = null
    private val stepReached = CountDownLatch(1)
    @Volatile private var partial = false
    @Volatile private var offline = false                                 // запросы списка обрываются (нет сети)
    @Volatile private var closeBeforeReply = 0                            // канал этой брони сервер закроет до ответа на шаг

    private val lat0 = 54.7388
    private val lng0 = 55.9721

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = 1_000
        DriverGpsActions.revoke()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                Regex("^/ws/trip/(\\d+)/location$").find(path)?.let { m ->
                    val id = m.groupValues[1].toInt()
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val o = JSONObject(text)
                            if (o.optString("type") == "auth") { sockets[id] = webSocket; opened.add(id) } else frames.add(id to o)
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    })
                }
                if (request.method == "POST" && (path.endsWith("/driver-status") || path.endsWith("/no-show"))) {
                    val id = Regex("^/bookings/(\\d+)/").find(path)?.groupValues?.get(1)?.toInt()
                    if (id != null && id == closeBeforeReply) {
                        sockets[id]?.close(1008, "Trip ended")   // как bookings.driver_status: канал закрыт до ответа
                        stepReached.countDown()
                        check(stepReplyGate?.await(10, TimeUnit.SECONDS) != false)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true,"status":"done"}""")
                }
                if (path == "/driver/bookings") {
                    lookups.incrementAndGet()
                    if (offline) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    lookupGate?.await(5, TimeUnit.SECONDS)
                    return MockResponse().setHeader("Content-Type", "application/json").setHeader("X-Yuldash-Partial", if (partial) "1" else "0").setBody(rows)
                }
                return MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        ApiClient.saveToken(jwt(5))
    }

    @After fun cleanup() {
        lookupGate?.countDown()
        stepReplyGate?.countDown()
        service?.onDestroy()
        DriverGpsActions.revoke()
        ApiClient.testTimeoutMs = null
        logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
        ApiClient.resetForTest()
    }

    // ------------------------------------------------------------------ стенд

    private fun jwt(user: Int): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString("""{"sub":"$user"}""".toByteArray()) + ".x"
    }

    private fun logout() = ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)

    private fun row(id: Int, status: String, ride: Int = 7, stop: String = "", wait: Int = 0) =
        JSONObject().put("booking_id", id).put("ride_id", ride).put("status", status).put("pickup_stop", stop).put("no_show_wait_sec", wait)

    private fun serve(vararg r: JSONObject) { rows = JSONArray(r.toList()).toString() }

    private fun svc(): TripLocationService =
        service ?: Robolectric.buildService(TripLocationService::class.java).create().get().also { service = it }

    private fun start(id: Int, driver: Boolean = true): TripLocationService {
        val s = svc()
        s.onStartCommand(Intent(app, TripLocationService::class.java)
            .putExtra(TripLocationService.EXTRA_BOOKING, id).putExtra(TripLocationService.EXTRA_DRIVER, driver), 0, id)
        return s
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!cond() && System.nanoTime() < deadline) { idle(); Thread.sleep(10) }
        idle()
        assertTrue(what, cond())
    }

    /** Ждём, пока канал брони [id] войдёт; ни один другой не должен войти раньше. */
    private fun expectOpened(id: Int) {
        var got: Int? = null
        waitUntil("канал брони $id не открылся") { got = got ?: opened.poll(); got != null }
        assertEquals("GPS пошёл не в тот канал", id, got)
    }

    private fun noneOpened(): Boolean { repeat(30) { idle(); Thread.sleep(20) }; return opened.isEmpty() }

    private fun gps(lat: Double = lat0) {
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = lng0; accuracy = 5f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        idle()
    }

    private fun frameTo(): Int? = frames.poll(5, TimeUnit.SECONDS)?.first
    private fun noFrames(): Boolean = frames.poll(700, TimeUnit.MILLISECONDS) == null
    private fun stopped(s: TripLocationService) = shadowOf(s).isStoppedBySelf
    private fun gpsOff() = shadowOf(manager).locationUpdateListeners.isEmpty()

    private fun activate(booking: Int = 101, ride: Int = 7) {
        val ticket = requireNotNull(DriverGpsActions.begin(booking, ride))
        assertTrue(DriverGpsActions.grant.complete(ticket, DriverGpsActions.owner(), ride, booking,
            Long.MAX_VALUE, SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }

    private fun rideOf101and102(): TripLocationService {
        serve(row(101, "onboard"), row(102, "onboard"))
        activate()
        return start(101).also { expectOpened(101) }
    }

    @Test fun withoutLiveGrantThereIsNoGpsSocketOrLookup() {
        val s = start(101)
        assertTrue(stopped(s)); assertTrue(gpsOff())
        assertEquals(0, lookups.get()); assertEquals(0, server.requestCount)
        assertTrue(noneOpened())
    }

    @Test fun closedCarrierHandsGpsToTheNextLiveBooking() {
        val s = rideOf101and102()
        serve(row(101, "done"), row(102, "onboard"))
        sockets.getValue(101).close(1008, "Trip ended")
        expectOpened(102)
        assertFalse(stopped(s)); assertFalse(gpsOff())
        assertEquals(102, DriverGpsActions.current()!!.carrier)
        gps(); assertEquals(102, frameTo())
    }

    @Test fun acceptedFinishSwitchesCarrierWithoutRenewingTheGrant() {
        rideOf101and102()
        val before = DriverGpsActions.current()!!.ticket
        serve(row(101, "done"), row(102, "onboard"))
        DriverGpsActions.closed(101)
        expectOpened(102); gps(); assertEquals(102, frameTo())
        assertEquals(before.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
        assertEquals(before.untilWall, DriverGpsActions.current()!!.ticket.untilWall)
    }

    @Test fun lastLiveBookingClosedTurnsGpsOff() {
        val s = rideOf101and102()
        serve(row(101, "done"), row(102, "done"))
        DriverGpsActions.closed(101)
        waitUntil("последняя бронь закрыта — GPS гаснет") { stopped(s) }
        assertTrue(gpsOff()); assertNull(DriverGpsActions.current())
        gps(); assertTrue(noFrames())
    }

    private fun closingStepSurvivesTheEarlyChannelClose(send: suspend () -> Result<Unit>) = runBlocking {
        val s = rideOf101and102()
        val until = DriverGpsActions.current()!!.ticket.untilElapsed
        serve(row(101, "done"), row(102, "onboard"))
        closeBeforeReply = 101
        stepReplyGate = CountDownLatch(1)
        ApiClient.testTimeoutMs = 10_000
        val response = async(Dispatchers.IO) { send() }
        try {
            assertTrue(stepReached.await(5, TimeUnit.SECONDS))
            expectOpened(102)
            assertFalse(stopped(s))
            gps(); assertEquals("кадр102 ушёл ещё до HTTP-ответа", 102, frameTo())
            assertEquals(1L, stepReplyGate!!.count)
        } finally { stepReplyGate!!.countDown() }
        assertTrue(response.await().isSuccess)
        assertEquals(until, DriverGpsActions.current()!!.ticket.untilElapsed)
    }

    @Test fun finishThroughApiClientSurvivesTheEarlyChannelClose() =
        closingStepSurvivesTheEarlyChannelClose { ApiClient.driverStatus(101, "done") }

    @Test fun noShowThroughApiClientSurvivesTheEarlyChannelClose() =
        closingStepSurvivesTheEarlyChannelClose { ApiClient.markNoShow(101) }

    @Test fun oldQueuedFinishDoesNotMutateTheBooking() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.sendQueuedAction(
            com.yuldash.app.data.OutboxAction(1L, 101, "driver_status", "done", System.currentTimeMillis()),
            generation,
        ).isFailure)
        val read = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("GET", read?.method)
        assertEquals("/bookings/101/role", read?.path)
        assertEquals(1, server.requestCount)
    }

    @Test fun noNetworkPausesAndRetriesWithoutExtendingPermission() {
        val s = rideOf101and102()
        val until = DriverGpsActions.current()!!.ticket.untilElapsed
        val initialLookups = lookups.get()
        serve(row(101, "done"), row(102, "onboard")); offline = true
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("поиск после закрытия должен начаться") { lookups.get() > initialLookups }
        Thread.sleep(1_200); idle()
        assertFalse(stopped(s)); assertTrue(gpsOff())
        assertEquals(until, DriverGpsActions.current()!!.ticket.untilElapsed)
        offline = false
        shadowOf(Looper.getMainLooper()).idleFor(12, TimeUnit.SECONDS)
        expectOpened(102); gps(); assertEquals(102, frameTo())
        assertEquals(until, DriverGpsActions.current()!!.ticket.untilElapsed)
    }

    @Test fun partialFeedPausesThenResumesTheSameGrant() {
        val s = rideOf101and102()
        val until = DriverGpsActions.current()!!.ticket.untilElapsed
        val initialLookups = lookups.get()
        serve(row(101, "done")); partial = true
        DriverGpsActions.closed(101)
        waitUntil("неполный список обработан") { lookups.get() > initialLookups && gpsOff() }
        assertFalse(stopped(s)); assertEquals(until, DriverGpsActions.current()!!.ticket.untilElapsed)
        serve(row(102, "onboard"))
        shadowOf(Looper.getMainLooper()).idleFor(12, TimeUnit.SECONDS)
        expectOpened(102); gps(); assertEquals(102, frameTo())
        assertEquals(until, DriverGpsActions.current()!!.ticket.untilElapsed)
    }

    @Test fun logoutDuringLookupLeavesNoSocketOrGps() {
        val s = rideOf101and102(); val before = lookups.get()
        serve(row(101, "done"), row(102, "onboard")); lookupGate = CountDownLatch(1)
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("поиск должен начаться") { lookups.get() > before }
        logout(); lookupGate!!.countDown()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        waitUntil("выход останавливает службу") { stopped(s) }
        assertTrue(noneOpened()); assertTrue(gpsOff()); assertNull(DriverGpsActions.current())
    }

    @Test fun startOfRouteBookingCarriesOnlyUntilItsNoShowTime() {
        val s = rideOf101and102()
        serve(row(101, "done"), row(102, "confirmed", wait = 120))
        DriverGpsActions.closed(101); expectOpened(102)
        gps(); assertEquals(102, frameTo())
        serve(row(102, "confirmed", wait = 0))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(121))
        gps(lat0 + 0.01)
        assertTrue(noFrames())
        idle(); waitUntil("срок несущей истёк") { stopped(s) }
    }

    @Test fun rideCeilingIsNotExtendedByCarrierSwitchOrCurrentScreen() {
        val s = rideOf101and102(); val ticket = DriverGpsActions.current()!!.ticket
        serve(row(101, "done"), row(102, "onboard"))
        DriverGpsActions.closed(101); expectOpened(102)
        start(DriverGpsActions.current()!!.carrier)
        assertEquals(ticket.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
        assertEquals(ticket.untilWall, DriverGpsActions.current()!!.ticket.untilWall)
        ShadowSystemClock.advanceBy(Duration.ofHours(6).plusSeconds(1))
        gps(); waitUntil("общий потолок истёк") { stopped(s) }
        assertTrue(gpsOff()); assertNull(DriverGpsActions.current())
    }

    @Test fun coldPrefsNeverRestoreGpsUnderSameOrAnotherLogin() {
        for (oldUser in listOf(5, 6)) {
            prefs.edit().putInt("last_booking", 101).putBoolean("last_driver", true).putInt("last_user", oldUser)
                .putLong("until_elapsed", SystemClock.elapsedRealtime() + 3_600_000L)
                .putLong("until_wall", System.currentTimeMillis() + 3_600_000L).apply()
            val s = svc()
            assertEquals(Service.START_NOT_STICKY, s.onStartCommand(null, 0, 1))
            assertTrue(stopped(s)); assertTrue(gpsOff()); assertTrue(noneOpened())
            assertEquals(0, lookups.get()); assertNull(DriverGpsActions.current())
        }
    }

    @Test fun logoutAndStopRevokeGrantEvenWithoutRunningService() {
        activate(); logout(); TripLocationService.stop(app)
        assertNull(DriverGpsActions.current()); assertTrue(gpsOff())
    }
}
