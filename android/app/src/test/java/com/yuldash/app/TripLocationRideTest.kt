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
 * Что держит набор (условия проверки плана P1-1…P1-6):
 *  - без шага водителя на этом телефоне служба ведёт себя как раньше: канал закрылся — GPS гаснет, список
 *    не спрашиваем, уход с экрана гасит (P1-1);
 *  - в режиме рейса закрытая несущая сменяется следующей живой бронью того же рейса, последняя — гасит;
 *  - устаревший экран не возвращает GPS в закрытый канал;
 *  - выход из аккаунта во время поиска — ни сокета, ни GPS, ни записи на диске (P1-5);
 *  - бронь в начале маршрута несёт GPS только до срока «Не пришёл» (P1-3), рейс — не дольше 6 часов (P1-2);
 *  - воскрешение под другим входом и жёсткий стоп без службы ничего не оставляют (P1-6).
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
    @Volatile private var offline = false                                 // запросы списка обрываются (нет сети)

    private val lat0 = 54.7388
    private val lng0 = 55.9721

    @Before fun prepare() {
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
                if (path == "/driver/bookings") {
                    lookups.incrementAndGet()
                    if (offline) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    lookupGate?.await(5, TimeUnit.SECONDS)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(rows)
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
        service?.onDestroy()
        logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
        TripLocationBus.driverActionListener = null
        TripLocationBus.driverStepListener = null
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

    /** Водитель на этом телефоне ведёт бронь 101 рейса 7 вместе со 102: «Сел» по 101 включил режим рейса. */
    private fun rideOf101and102(): TripLocationService {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "onboard"), row(102, "onboard"))
        TripLocationBus.driverAction(101, closed = false)
        waitUntil("режим рейса не включился") { TripLocationBus.rideShareActive }
        assertEquals(1, lookups.get())
        return s
    }

    // ------------------------------------------------------------------ без режима рейса — как раньше (P1-1)

    @Test fun withoutDriverStepClosedChannelStopsGpsAndAsksNothing() {
        val s = start(101)
        expectOpened(101)
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("закрытый канал без режима рейса обязан погасить GPS") { stopped(s) }
        assertTrue(gpsOff())
        assertEquals("планшет дома не должен сам искать чужую бронь", 0, lookups.get())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun releaseStopsWithoutRideModeButKeepsTheRide() {
        start(101)
        expectOpened(101)
        TripLocationService.release(app)
        assertTrue("ушёл с экрана без режима рейса — GPS гаснет, как раньше", shadowOf(app).nextStoppedService != null)

        serve(row(101, "onboard"), row(102, "onboard"))
        TripLocationBus.driverAction(101, closed = false)
        waitUntil("режим рейса не включился") { TripLocationBus.rideShareActive }
        TripLocationService.release(app)
        assertNull("ведёт рейс — уход с экрана GPS не гасит", shadowOf(app).nextStoppedService)
    }

    @Test fun stepOnAnotherRideDoesNotEnterRideMode() {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "onboard"), row(201, "onboard", ride = 9))
        TripLocationBus.driverAction(201, closed = false)
        waitUntil("список не спросили") { lookups.get() == 1 }
        repeat(10) { idle(); Thread.sleep(10) }
        assertFalse("шаг по брони другого рейса режим этого рейса не включает", TripLocationBus.rideShareActive)
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("без режима рейса закрытый канал гасит GPS") { stopped(s) }
    }

    // ------------------------------------------------------------------ режим рейса

    @Test fun closedCarrierHandsGpsToTheNextLiveBooking() {
        val s = rideOf101and102()
        serve(row(101, "done"), row(102, "onboard"))
        sockets.getValue(101).close(1008, "Trip ended")
        expectOpened(102)
        assertFalse("остальные ещё едут — GPS не гаснет", stopped(s))
        assertFalse(gpsOff())
        assertEquals("воскрешение продолжит новую несущую", 102, prefs.getInt("last_booking", -1))
        gps()
        assertEquals("точка ушла в канал следующей брони", 102, frameTo())
    }

    @Test fun finishOnThisPhoneSwitchesAtOnceWithoutWaitingForTheServer() {
        rideOf101and102()
        serve(row(101, "done"), row(102, "onboard"))
        TripLocationBus.driverAction(101, closed = true)   // «Завершить» по 101 принят сервером
        expectOpened(102)
        gps()
        assertEquals(102, frameTo())
    }

    @Test fun lastLiveBookingClosedTurnsGpsOff() {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "onboard"))
        TripLocationBus.driverAction(101, closed = false)
        waitUntil("режим рейса не включился") { TripLocationBus.rideShareActive }
        serve(row(101, "done"))
        TripLocationBus.driverAction(101, closed = true)
        waitUntil("последняя бронь рейса закрыта — GPS обязан погаснуть") { stopped(s) }
        assertTrue(gpsOff())
        assertTrue("воскрешать нечего", prefs.all.isEmpty())
        assertFalse(TripLocationBus.rideShareActive)
        gps()
        assertTrue(noFrames())
    }

    // «Завершить» — первый шаг водителя на этом телефоне (приёмка Д2, P1-А): экран, узнав, что пассажиры остались,
    // уводит в «Едут с тобой» и отпускает поездку (release), пока служба ещё ждёт свой список.
    @Test fun releaseWhileTheFirstFinishLookupIsInFlightKeepsGpsForTheOthers() {
        val s = start(101)
        expectOpened(101)
        lookupGate = CountDownLatch(1)
        serve(row(101, "done"), row(102, "onboard"))
        TripLocationBus.driverAction(101, closed = true)
        waitUntil("служба не пошла за списком") { lookups.get() == 1 }
        TripLocationService.release(app)
        assertNull("экран ушёл в «Едут с тобой» — служба погасла, второй пассажир теряет машину", shadowOf(app).nextStoppedService)
        lookupGate!!.countDown()
        expectOpened(102)
        assertFalse(stopped(s))
        assertTrue(TripLocationBus.rideShareActive)
    }

    @Test fun releaseWhileTheLookupIsInFlightStopsWhenNoOneIsLeft() {
        val s = start(101)
        expectOpened(101)
        lookupGate = CountDownLatch(1)
        serve(row(101, "done"))
        TripLocationBus.driverAction(101, closed = true)
        waitUntil("служба не пошла за списком") { lookups.get() == 1 }
        TripLocationService.release(app)
        lookupGate!!.countDown()
        waitUntil("последний пассажир закрыт, экран отпустил — GPS обязан погаснуть") { stopped(s) }
        assertTrue(gpsOff())
        assertTrue(prefs.all.isEmpty())
    }

    // Вторая приёмка Д2, P1: сервер записал «done» и закрыл канал брони РАНЬШЕ, чем ответил на «Завершить»
    // (бонус, пуш). Служба знает о шаге с момента отправки и не гаснет, а ждёт ответ.
    @Test fun channelClosedBeforeTheFinishReplyStillHandsGpsOver() {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "done"), row(102, "onboard"))
        val result = TripLocationBus.driverStep(101, closing = true) {
            sockets.getValue(101).close(1008, "Trip ended")   // канал закрылся, ответа ещё нет
            repeat(50) { idle(); Thread.sleep(10) }           // закрытие дошло до службы раньше ответа
            assertFalse("служба погасла до ответа на «Завершить» — второй пассажир теряет машину", stopped(s))
            Result.success(Unit)
        }
        assertTrue(result.isSuccess)
        expectOpened(102)
        assertFalse(stopped(s))
    }

    @Test fun finishRefusedAfterTheChannelClosedTurnsGpsOff() {
        val s = start(101)
        expectOpened(101)
        TripLocationBus.driverStep(101, closing = true) {
            sockets.getValue(101).close(1008, "Trip ended")
            repeat(50) { idle(); Thread.sleep(10) }
            assertFalse("ответа ещё нет — служба ждёт его, а не гаснет", stopped(s))
            Result.failure<Unit>(com.yuldash.app.data.ApiException(409, "Поездка не активна"))
        }
        waitUntil("сервер отказал, канал закрыт — ждать нечего, GPS обязан погаснуть") { stopped(s) }
        assertEquals("после отказа список не спрашивают", 0, lookups.get())
    }

    @Test fun noNetworkForTheRideListAfterFinishIsRetried() {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "done"), row(102, "onboard"))
        offline = true   // запросы списка обрываются — нет сети
        TripLocationBus.driverStep(101, closing = true) {
            sockets.getValue(101).close(1008, "Trip ended")   // сервер закрыл канал «Завершить»
            repeat(50) { idle(); Thread.sleep(10) }
            Result.success(Unit)
        }
        waitUntil("служба не пошла за списком") { lookups.get() >= 1 }
        repeat(200) { idle(); Thread.sleep(10) }   // повторы самого клиента тоже не дошли
        assertFalse("нет сети на миг — и GPS погас при живом рейсе", stopped(s))
        offline = false
        var got: Int? = null
        repeat(15) { if (got == null) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)); Thread.sleep(50); got = opened.poll() } }
        if (got == null) waitUntil("повтор так и не открыл канал следующей брони") { got = opened.poll(); got != null }
        assertEquals(102, got)
        assertFalse(stopped(s))
    }

    @Test fun staleStartWhileSearchingDoesNotReopenTheClosedChannel() {
        rideOf101and102()
        lookupGate = CountDownLatch(1)
        serve(row(101, "done"), row(102, "onboard"))
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("служба не пошла за списком") { lookups.get() == 2 }
        start(101)   // экран закрытой поездки переотправил старт, пока служба ищет следующую бронь
        assertTrue("служба снова открыла канал закрытой брони и бросила поиск", noneOpened())
        lookupGate!!.countDown()
        expectOpened(102)
    }

    @Test fun staleScreenDoesNotReturnGpsToTheClosedChannel() {
        rideOf101and102()
        serve(row(101, "done"), row(102, "onboard"))
        TripLocationBus.driverAction(101, closed = true)
        expectOpened(102)
        start(101)   // экран закрытой поездки ещё висит и переотправил старт (смена языка)
        assertTrue("служба вернулась в канал закрытой брони", noneOpened())
        assertEquals(102, TripLocationBus.bookingId)
    }

    @Test fun logoutDuringTheLookupLeavesNoSocketNoGpsNoRecord() {
        val s = rideOf101and102()
        serve(row(101, "done"), row(102, "onboard"))
        lookupGate = CountDownLatch(1)
        sockets.getValue(101).close(1008, "Trip ended")
        waitUntil("служба не пошла за списком") { lookups.get() == 2 }
        logout()                     // вышел, пока ответ в пути
        lookupGate!!.countDown()
        waitUntil("после выхода служба обязана погаснуть") { stopped(s) }
        assertTrue("ответ после выхода открыл канал следующей брони", noneOpened())
        assertTrue(gpsOff())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun startOfRouteBookingCarriesGpsOnlyUntilItsNoShowTime() {
        val s = start(101)
        expectOpened(101)
        serve(row(101, "onboard"), row(102, "confirmed", wait = 120))
        TripLocationBus.driverAction(101, closed = false)
        waitUntil("режим рейса не включился") { TripLocationBus.rideShareActive }
        serve(row(101, "done"), row(102, "confirmed", wait = 120))
        TripLocationBus.driverAction(101, closed = true)
        expectOpened(102)
        gps()
        assertEquals(102, frameTo())

        serve(row(102, "confirmed", wait = 0))   // срок «Не пришёл» настал
        ShadowSystemClock.advanceBy(Duration.ofSeconds(121))
        gps(lat0 + 0.01)
        assertTrue("после срока «Не пришёл» кадр ушёл бы не севшей пассажирке по её каналу", noFrames())
        waitUntil("подходящей несущей нет — GPS гаснет") { stopped(s) }
    }

    @Test fun rideCeilingIsNotExtendedBySwitchesOrScreens() {
        val s = rideOf101and102()
        val until = prefs.getLong("until_elapsed", 0L)
        assertTrue(until > SystemClock.elapsedRealtime())
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        start(102)   // открыл поездку второго пассажира того же рейса
        expectOpened(102)
        assertEquals("явный экран в режиме рейса потолок не продлевает", until, prefs.getLong("until_elapsed", 0L))
        ShadowSystemClock.advanceBy(Duration.ofHours(5).plusSeconds(1))
        gps()
        waitUntil("6 часов с начала рейса — GPS гаснет") { stopped(s) }
    }

    // ------------------------------------------------------------------ воскрешение и выход (P1-3, P1-6)

    @Test fun revivedRideRePicksTheCarrierBeforeConnecting() {
        prefs.edit().putInt("last_booking", 101).putString("last_lang", "Ru").putBoolean("last_driver", true)
            .putInt("last_user", 5).putLong("until_elapsed", SystemClock.elapsedRealtime() + 3_600_000L)
            .putLong("until_wall", System.currentTimeMillis() + 3_600_000L)
            .putInt("ride_id", 7).putString("ride_bookings", "101,102").apply()
        serve(row(101, "done"), row(102, "onboard"))
        val s = svc()
        assertEquals(Service.START_STICKY, s.onStartCommand(null, 0, 1))
        expectOpened(102)
        assertEquals(1, lookups.get())
    }

    @Test fun revivalUnderAnotherLoginStopsWithoutConnecting() {
        prefs.edit().putInt("last_booking", 101).putBoolean("last_driver", true).putInt("last_user", 6)
            .putLong("until_elapsed", SystemClock.elapsedRealtime() + 3_600_000L)
            .putLong("until_wall", System.currentTimeMillis() + 3_600_000L).apply()
        val s = svc()
        assertEquals(Service.START_NOT_STICKY, s.onStartCommand(null, 0, 1))
        assertTrue(stopped(s))
        assertTrue("под чужим входом канал прошлого водителя открылся", noneOpened())
        assertEquals(0, lookups.get())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun hardStopClearsTheRecordEvenWithoutARunningService() {
        prefs.edit().putInt("last_booking", 101).putInt("last_user", 5).putInt("ride_id", 7).apply()
        TripLocationService.stop(app)
        assertTrue("выход оставил на диске бронь, по которой служба воскреснет", prefs.all.isEmpty())
    }
}
