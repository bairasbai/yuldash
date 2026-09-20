package com.yuldash.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantLocationSocket
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CourierLocationTerminalTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private var service: CourierLocationService? = null
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val prefs get() = app.getSharedPreferences("courier_location_svc", Context.MODE_PRIVATE)
    private class Channel {
        val authenticated = CountDownLatch(1)
        val locationReceived = CountDownLatch(1)
        val socket = AtomicReference<WebSocket>()
    }
    private val channels = ConcurrentHashMap<Int, Channel>()

    @Before fun prepare() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        prefs.edit().clear().apply()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = Regex("/ws/parcel/(\\d+)/location").matchEntire(request.path.orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()
                    ?: return MockResponse().setResponseCode(404)
                val channel = channels[id] ?: return MockResponse().setResponseCode(404)
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (JSONObject(text).optString("type")) {
                            "auth" -> {
                                channel.socket.set(webSocket)
                                channel.authenticated.countDown()
                            }
                            "loc" -> channel.locationReceived.countDown()
                        }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(app)
        ApiClient.saveToken("local-courier-location-test")
    }

    @After fun cleanup() {
        service?.onDestroy()
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply {
            isAccessible = true
        }.invoke(ApiClient)
        ApiClient.testBaseUrl = null
        server.shutdown()
        prefs.edit().clear().apply()
    }

    private fun start(vararg ids: Int): CourierLocationService {
        ids.forEach { channels.putIfAbsent(it, Channel()) }
        val svc = service ?: Robolectric.buildService(CourierLocationService::class.java)
            .create().get().also { service = it }
        svc.onStartCommand(Intent(app, CourierLocationService::class.java)
            .putExtra(CourierLocationService.EXTRA_PARCELS, ids), 0, 1)
        ids.forEach { assertTrue("Parcel $it must authenticate locally",
            channels.getValue(it).authenticated.await(5, TimeUnit.SECONDS)) }
        return svc
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun sockets(svc: CourierLocationService): List<InstantLocationSocket> =
        CourierLocationService::class.java.getDeclaredField("sockets").apply {
            isAccessible = true
        }.get(svc) as List<InstantLocationSocket>

    @Test fun lastParcelTerminalCloseStopsGpsAndClearsRestorationBeforeDestroy() {
        val svc = start(42)
        assertFalse(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals("42", prefs.getString("last_parcels", null))
        channels.getValue(42).socket.get().close(1008, "Parcel ended")
        awaitMain { shadowOf(svc).isStoppedBySelf }

        // No onDestroy yet: the server's terminal decision must immediately release tracking.
        assertTrue("Last finished parcel must stop the service", shadowOf(svc).isStoppedBySelf)
        assertTrue("Finished delivery must release GPS", shadowOf(manager).locationUpdateListeners.isEmpty())
        assertFalse("Restart must not restore a finished parcel", prefs.contains("last_parcels"))
        assertTrue(sockets(svc).isEmpty())
    }

    @Test fun oneTerminalParcelKeepsOtherChannelAndGpsUntilLastParcelEnds() {
        val svc = start(42, 43)
        assertFalse(shadowOf(manager).locationUpdateListeners.isEmpty())
        channels.getValue(42).socket.get().close(1008, "Parcel ended")
        awaitMain { prefs.getString("last_parcels", null) == "43" }

        assertEquals("Only the active delivery may be restored", "43", prefs.getString("last_parcels", null))
        assertFalse("One finished delivery must not stop another", shadowOf(svc).isStoppedBySelf)
        assertFalse(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals(1, sockets(svc).size)
        assertTrue(sockets(svc).single().sendLoc(54.7, 55.9, null))
        assertTrue("The remaining delivery channel must still transmit",
            channels.getValue(43).locationReceived.await(5, TimeUnit.SECONDS))

        channels.getValue(43).socket.get().close(1008, "Parcel ended")
        awaitMain { shadowOf(svc).isStoppedBySelf }
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertFalse(prefs.contains("last_parcels"))
    }

    @Test fun delayedTerminalCallbackFromPreviousSetDoesNotStopNewDelivery() {
        val svc = start(42)
        val oldSocket = sockets(svc).single()
        @Suppress("UNCHECKED_CAST")
        val oldCallback = InstantLocationSocket::class.java.getDeclaredField("onTerminated").apply {
            isAccessible = true
        }.get(oldSocket) as () -> Unit

        start(43)
        oldCallback()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("Old callback must not stop a replacement delivery", shadowOf(svc).isStoppedBySelf)
        assertEquals("43", prefs.getString("last_parcels", null))
        assertFalse(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals(1, sockets(svc).size)
        assertTrue(sockets(svc).single().sendLoc(54.7, 55.9, null))
        assertTrue(channels.getValue(43).locationReceived.await(5, TimeUnit.SECONDS))
    }
}
