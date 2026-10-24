package com.yuldash.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripLocationBus
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
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
import java.util.concurrent.atomic.AtomicInteger

/** Настоящие служба, HTTP role и WS: пассажир после исправления снова получает машину. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PassengerCorrectionLocationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("trip_location_svc", Context.MODE_PRIVATE)
    private val server = MockWebServer()
    private val channels = LinkedBlockingQueue<WebSocket>()
    private val checks = AtomicInteger()
    @Volatile private var role = "passenger"
    @Volatile private var status = "confirmed"
    @Volatile private var gate: CountDownLatch? = null
    private var service: TripLocationService? = null

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(app); ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        prefs.edit().clear().apply(); TripLocationBus.peer = null
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/bookings/101/role") {
                    checks.incrementAndGet(); gate?.await(10, TimeUnit.SECONDS)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("""{"role":"$role","status":"$status"}""")
                }
                if (request.path == "/ws/trip/101/location") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).optString("type") == "auth") channels.add(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                })
                return MockResponse().setResponseCode(404)
            }
        }
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() {
        gate?.countDown(); service?.onDestroy(); ApiClient.resetForTest(); server.shutdown()
        prefs.edit().clear().apply(); TripLocationBus.peer = null; TripLocationBus.bookingId = null
    }
    private fun start(): WebSocket {
        val s = Robolectric.buildService(TripLocationService::class.java).create().get().also { service = it }
        s.onStartCommand(Intent(app, TripLocationService::class.java).putExtra(TripLocationService.EXTRA_BOOKING, 101)
            .putExtra(TripLocationService.EXTRA_DRIVER, false), 0, 1)
        return channel()
    }
    private fun await(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!cond() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); Thread.sleep(10)
        }
        assertTrue(what, cond())
    }
    private fun channel(): WebSocket {
        var ws: WebSocket? = null
        await("новый авторизованный канал не открылся") { ws = ws ?: channels.poll(); ws != null }
        return ws!!
    }
    private fun peer(ws: WebSocket, lat: Double) {
        ws.send("""{"type":"loc","role":"driver","lat":$lat,"lng":55.0,"ts":1}""")
        await("новая точка машины не дошла") { TripLocationBus.peer?.lat == lat }
    }
    @Test fun confirmedCorrectionAndNextBoardingKeepReceivingCarWithoutExtendingLifetime() {
        val first = start(); peer(first, 54.0)
        val until = prefs.getLong("until_elapsed", 0); val wall = prefs.getLong("until_wall", 0)
        first.close(1008, "Boarding corrected")
        val confirmed = channel(); peer(confirmed, 54.1)
        assertEquals(1, checks.get()); assertFalse(shadowOf(service!!).isStoppedBySelf)
        assertEquals(until, prefs.getLong("until_elapsed", 0)); assertEquals(wall, prefs.getLong("until_wall", 0))
        status = "onboard"; confirmed.close(1008, "Boarding corrected")
        peer(channel(), 54.2)
        assertEquals(2, checks.get()); assertEquals(until, prefs.getLong("until_elapsed", 0))
    }
    @Test fun cancelledBookingDoesNotOpenAnotherChannel() {
        val first = start(); status = "cancelled"; first.close(1008, "Boarding corrected")
        await("закрытая бронь должна остановить отправку") { shadowOf(service!!).isStoppedBySelf }
        assertTrue(channels.isEmpty())
    }
    @Test fun changedRoleDoesNotOpenAnotherChannel() {
        val first = start(); role = "driver"; first.close(1008, "Boarding corrected")
        await("другая роль не должна продолжать пассажирскую отправку") { shadowOf(service!!).isStoppedBySelf }
        assertTrue(channels.isEmpty())
    }
    @Test fun delayedRoleCannotResumeAfterLogout() {
        val first = start(); gate = CountDownLatch(1); first.close(1008, "Boarding corrected")
        await("состояние не запрошено") { checks.get() == 1 }
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)
        gate!!.countDown()
        await("после выхода служба должна погаснуть") { shadowOf(service!!).isStoppedBySelf }
        assertTrue(channels.isEmpty())
    }
    @Test fun delayedRoleCannotRenewSixHourDeadline() {
        val first = start(); gate = CountDownLatch(1); first.close(1008, "Boarding corrected")
        await("состояние не запрошено") { checks.get() == 1 }
        ShadowSystemClock.advanceBy(Duration.ofHours(6).plusSeconds(1)); gate!!.countDown()
        await("исходные шесть часов вышли") { shadowOf(service!!).isStoppedBySelf }
        assertTrue(channels.isEmpty())
    }
}
