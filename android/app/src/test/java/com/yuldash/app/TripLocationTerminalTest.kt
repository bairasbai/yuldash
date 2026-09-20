package com.yuldash.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.LocationSocket
import com.yuldash.app.data.TripLocationBus
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripLocationTerminalTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var service: TripLocationService? = null
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val prefs get() = app.getSharedPreferences("trip_location_svc", Context.MODE_PRIVATE)

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        prefs.edit().clear().apply()
        TripLocationBus.bookingId = null
        TripLocationBus.peer = null
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        ApiClient.saveToken("local-trip-location-test")
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

    private fun start(id: Int): TripLocationService {
        val svc = service ?: Robolectric.buildService(TripLocationService::class.java)
            .create().get().also { service = it }
        svc.onStartCommand(Intent(app, TripLocationService::class.java)
            .putExtra(TripLocationService.EXTRA_BOOKING, id), 0, id)
        return svc
    }

    private fun queueSocket(): Pair<CountDownLatch, AtomicReference<WebSocket>> {
        val authenticated = CountDownLatch(1)
        val socket = AtomicReference<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                socket.set(webSocket)
                authenticated.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        return authenticated to socket
    }

    @Test fun terminalServerCloseStopsGpsAndClearsRestorationBeforeDestroy() {
        val (authenticated, socket) = queueSocket()
        val svc = start(42)
        assertTrue("Local socket must receive authentication", authenticated.await(5, TimeUnit.SECONDS))
        assertFalse(shadowOf(svc).isStoppedBySelf)
        assertFalse("Test must start actual GPS subscription", shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals(42, prefs.getInt("last_booking", -1))
        socket.get().close(1008, "Trip ended")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!shadowOf(svc).isStoppedBySelf && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Do not call onDestroy before these assertions: cleanup must begin at the terminal event.
        assertTrue("Finished trip must stop its foreground service", shadowOf(svc).isStoppedBySelf)
        assertTrue("Finished trip must release GPS", shadowOf(manager).locationUpdateListeners.isEmpty())
        assertFalse("A killed process must not restore the finished trip", prefs.contains("last_booking"))
        assertNull(TripLocationBus.bookingId)
    }

    @Test fun delayedTerminalCallbackForOldBookingDoesNotStopNewBooking() {
        val (firstAuth, _) = queueSocket()
        val svc = start(42)
        assertTrue(firstAuth.await(5, TimeUnit.SECONDS))
        val socketField = TripLocationService::class.java.getDeclaredField("socket").apply { isAccessible = true }
        val oldSocket = socketField.get(svc) as LocationSocket
        val callbackField = LocationSocket::class.java.getDeclaredField("onTerminated").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val oldCallback = callbackField.get(oldSocket) as () -> Unit

        val (secondAuth, _) = queueSocket()
        start(43)
        assertTrue(secondAuth.await(5, TimeUnit.SECONDS))
        oldCallback()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("Old connection must not stop the next trip", shadowOf(svc).isStoppedBySelf)
        assertEquals(43, TripLocationBus.bookingId)
        assertEquals(43, prefs.getInt("last_booking", -1))
        assertFalse(shadowOf(manager).locationUpdateListeners.isEmpty())
    }
}
