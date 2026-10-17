package com.yuldash.app

import android.Manifest
import android.app.Application
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Пульс стоянки сквозь настоящий сервис: GPS → [TripLocationService] → сокет → сервер.
 *
 * Проверка безопасности PR170: сервис просил у Android точки «не ближе 10 м». Стоящая машина
 * не получала ни одной, сервер через 120 с забывал её — и мама по ссылке, и сигнал SOS теряли
 * машину на стоянке. Правила темпа — `TripLocationPacerTest`; здесь — что они правда доходят
 * до канала, а после остановки сервиса или отзыва гео не уходит ничего. Пульс — только водителю
 * (приёмка PR179): стоящий пассажир молчит, роль переживает воскрешение сервиса; после обрыва
 * канала точка уходит сразу, а не через 45 с.
 *
 * Точки GPS подаёт Robolectric (он же применяет фильтры запроса, как Android), часы — тоже его:
 * сервис читает `SystemClock.elapsedRealtime()`. Кадры принимает локальный MockWebServer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripLocationHeartbeatTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var service: TripLocationService? = null
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val prefs get() = app.getSharedPreferences("trip_location_svc", Context.MODE_PRIVATE)
    private val frames = LinkedBlockingQueue<JSONObject>()   // кадры {type:loc}, дошедшие до сервера
    private val serverSide = AtomicReference<WebSocket>()     // серверный конец последнего канала

    private val fixMs = TripLocationService.FIX_INTERVAL_MS
    private val lat0 = 54.7388
    private val lng0 = 55.9721
    private fun north(m: Double) = lat0 + m / 111_195.0

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        ApiClient.saveToken("local-trip-heartbeat-test")
    }

    @After fun cleanup() {
        service?.onDestroy()
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply {
            isAccessible = true
        }.invoke(ApiClient)
        ApiClient.testBaseUrl = null
        server.shutdown()
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
    }

    /** Сервер готов принять следующий канал; защёлка — кадр входа дошёл. */
    private fun queueSocket(): CountDownLatch {
        val authenticated = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "loc") frames.add(frame)
                else { serverSide.set(webSocket); authenticated.countDown() }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        return authenticated
    }

    /**
     * Поездка началась: сервис поднят, канал открыт и кадр входа дошёл до сервера.
     * [revive] — Android воскресил сервис после смерти процесса: intent пустой, всё из prefs.
     */
    private fun startTrip(bookingId: Int = 42, isDriver: Boolean = true, revive: Boolean = false): TripLocationService {
        val authenticated = queueSocket()
        val svc = Robolectric.buildService(TripLocationService::class.java).create().get().also { service = it }
        svc.onStartCommand(if (revive) null else Intent(app, TripLocationService::class.java)
            .putExtra(TripLocationService.EXTRA_BOOKING, bookingId)
            .putExtra(TripLocationService.EXTRA_DRIVER, isDriver), 0, 1)
        assertTrue("канал поездки не открылся", authenticated.await(5, TimeUnit.SECONDS))
        settle()
        return svc
    }

    /** Канал вошёл → сервис из потока сокета ставит в главный поток сброс темпа. Даём ему дойти. */
    private fun settle() = repeat(15) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }

    /** Точка GPS «сейчас» по часам Robolectric; доставка слушателю идёт через главный поток. */
    private fun gps(lat: Double) {
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lng0
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun later(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    private fun nextFrame(): JSONObject? = frames.poll(5, TimeUnit.SECONDS)
    private fun noMoreFrames(): Boolean = frames.poll(700, TimeUnit.MILLISECONDS) == null

    @Test fun parkedCarStillReportsBeforeTheServerForgetsIt() {
        startTrip()
        assertTrue("роль водителя не сохранена — после смерти процесса пульс пропадёт", prefs.getBoolean("last_driver", false))
        gps(lat0)
        assertNotNull("первая точка поездки должна уйти сразу", nextFrame())

        // Стоим 42 с: точка GPS каждые 3 с, GPS «гуляет» на сантиметры — слать пока незачем.
        for (i in 1..14) { later(fixMs); gps(north(0.1 * i)) }
        assertTrue("стоянка не должна слать точку каждые 7 с", noMoreFrames())

        later(fixMs); gps(north(1.5))   // 45 с от первой отправки
        val beat = nextFrame()
        assertNotNull(
            "стоящая машина не отозвалась за 45 с — через 120 с сервер забудет её, и близкие по ссылке " +
                "и сигнал SOS потеряют машину на стоянке",
            beat,
        )
        assertEquals("пульс несёт свежую точку, а не старую", north(1.5), beat!!.getDouble("lat"), 1e-9)
    }

    @Test fun movingCarKeepsTheSevenSecondPaceWithoutExtraBeats() {
        startTrip()
        // Минута езды, 30 м за точку (≈36 км/ч): уходят точки 0, 9, 18 … 54 с — каждая третья.
        for (i in 0..20) {
            if (i > 0) later(fixMs)
            gps(north(30.0 * i))
        }
        // Какая по счёту точка GPS ушла: широта → номер (30 м на точку).
        val expected = (0..20 step 3).toList()
        val got = expected.map { _ -> nextFrame()?.let { Math.round((it.getDouble("lat") - lat0) * 111_195.0 / 30.0).toInt() } }
        assertEquals("в движении темп — раз в 7 с (с точкой GPS раз в 3 с — 9 с)", expected, got)
        assertTrue("пульс добавил лишние кадры к езде", noMoreFrames())
    }

    @Test fun stoppedServiceSendsNothingEvenIfGpsKeepsTicking() {
        val svc = startTrip()
        gps(lat0)
        assertNotNull(nextFrame())

        svc.onDestroy()   // поездка кончилась: YuldashApp остановил сервис
        service = null
        assertTrue("остановленный сервис обязан отпустить GPS", shadowOf(manager).locationUpdateListeners.isEmpty())
        later(TripLocationService.HEARTBEAT_MS); gps(lat0)
        later(fixMs); gps(north(100.0))
        assertTrue("после конца поездки точка ушла — это утечка места", noMoreFrames())
    }

    @Test fun revokedLocationPermissionStopsTheBeatAndTheService() {
        val svc = startTrip()
        gps(lat0)
        assertNotNull(nextFrame())

        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        later(TripLocationService.HEARTBEAT_MS); gps(lat0)
        assertTrue("гео отозвали — пульс не должен уйти никому", noMoreFrames())
        assertTrue("без разрешения сервис с плашкой «показываем позицию» не держим", shadowOf(svc).isStoppedBySelf)
    }

    @Test fun parkedPassengerSendsNoHeartbeat() {
        startTrip(isDriver = false)
        assertFalse(prefs.getBoolean("last_driver", true))
        gps(lat0)
        assertNotNull("первая точка пассажира уходит, как раньше", nextFrame())

        for (i in 1..30) { later(fixMs); gps(north(0.1 * i)) }   // полторы минуты стоим
        assertTrue("стоящий пассажир шлёт пульс — его точку сервер не хранит, слать незачем", noMoreFrames())

        later(fixMs); gps(north(50.0))   // пошёл
        assertNotNull("в движении точка пассажира уходит, как раньше", nextFrame())
    }

    @Test fun revivedDriverServiceKeepsTheHeartbeat() {
        // Android убил процесс посреди поездки и поднял сервис сам: intent пустой, роль — из prefs.
        prefs.edit().putInt("last_booking", 42).putString("last_lang", "Ru").putBoolean("last_driver", true).apply()
        startTrip(revive = true)
        gps(lat0)
        assertNotNull(nextFrame())
        for (i in 1..14) { later(fixMs); gps(lat0) }
        assertTrue(noMoreFrames())
        later(fixMs); gps(lat0)
        assertNotNull("воскрешённый сервис забыл, что он у водителя, — машина пропадёт на стоянке", nextFrame())
    }

    @Test fun reconnectedChannelSendsTheNextPointAtOnce() {
        startTrip()
        gps(lat0)
        assertNotNull(nextFrame())
        later(fixMs); gps(lat0)
        assertTrue(noMoreFrames())

        // Сервер перезапускался — канал оборвался, приложение само подключается заново.
        val reconnected = queueSocket()
        serverSide.get().close(1001, "restart")
        assertTrue("канал не переподключился", reconnected.await(10, TimeUnit.SECONDS))
        settle()

        later(fixMs); gps(lat0)   // 6 с от прошлой отправки
        assertNotNull("после обрыва стоящая машина молчала бы до 45 с", nextFrame())
    }
}
