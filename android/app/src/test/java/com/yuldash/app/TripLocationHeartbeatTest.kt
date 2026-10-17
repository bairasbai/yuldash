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

/**
 * Пульс стоянки сквозь настоящий сервис: GPS → [TripLocationService] → сокет → сервер.
 *
 * Проверка безопасности PR170: сервис просил у Android точки «не ближе 10 м». Стоящая машина
 * не получала ни одной, сервер через 120 с забывал её — и мама по ссылке, и сигнал SOS теряли
 * машину на стоянке. Правила темпа — `TripLocationPacerTest`; здесь — что они правда доходят
 * до канала, а после остановки сервиса или отзыва гео не уходит ничего.
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

    /** Поездка началась: сервис поднят, канал открыт и кадр входа дошёл до сервера. */
    private fun startTrip(bookingId: Int = 42): TripLocationService {
        val authenticated = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                if (frame.optString("type") == "loc") frames.add(frame) else authenticated.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        val svc = Robolectric.buildService(TripLocationService::class.java).create().get().also { service = it }
        svc.onStartCommand(Intent(app, TripLocationService::class.java)
            .putExtra(TripLocationService.EXTRA_BOOKING, bookingId), 0, 1)
        assertTrue("канал поездки не открылся", authenticated.await(5, TimeUnit.SECONDS))
        return svc
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
}
