package com.yuldash.app

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripLocationDriverGrantTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = MockWebServer()
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private var service: TripLocationService? = null
    private var rows = listOf(row(42), row(43))
    private var partial = false
    private fun row(id: Int, status: String = "onboard") = DriverBookingDto(id, "", null, "", status, rideId = 8)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(app)
        ApiClient.saveToken("fixture.eyJzdWIiOiIxIn0.fixture")
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        DriverGpsActions.revoke()
    }
    @After fun cleanup() {
        service?.onDestroy(); DriverGpsActions.revoke()
        ApiClient.resetForTest(); server.shutdown()
    }
    private fun intent(id: Int = 42) = Intent(app, TripLocationService::class.java)
        .putExtra(TripLocationService.EXTRA_BOOKING, id).putExtra(TripLocationService.EXTRA_DRIVER, true)
    private fun svc() = Robolectric.buildService(TripLocationService::class.java).create().get().also {
        service = it
        it.driverFeed = { Result.success(DriverBookingsFeed(rows, partial)) }
    }
    private fun activate() {
        val ticket = DriverGpsActions.begin(42)!!
        assertTrue(DriverGpsActions.grant.complete(ticket, DriverGpsActions.owner(), 8, 42, Long.MAX_VALUE,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()))
    }
    private fun socket(): CountDownLatch {
        val auth = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { auth.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        return auth
    }
    @Test fun permissionAndReplayedIntentWithoutLiveActionHaveNoGpsOrSocket() {
        val svc = svc()
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(intent(), 0, 1))
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals(0, server.requestCount)
        assertEquals(Service.START_NOT_STICKY, svc.onStartCommand(null, 0, 2))
        assertEquals(0, server.requestCount)
    }
    @Test fun carrierSwitchClosesAAndCarriesBWithinOriginalDeadlineThenLastStops() {
        activate(); val deadline = DriverGpsActions.current()!!.ticket.untilElapsed
        val first = socket(); val svc = svc(); svc.onStartCommand(intent(), 0, 1)
        assertTrue(first.await(5, TimeUnit.SECONDS))
        rows = listOf(row(42, "done"), row(43))
        val second = socket()
        DriverGpsActions.closed(42); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals(43, TripLocationBus.bookingId)
        assertFalse(DriverGpsActions.allowed(app, 42))
        assertEquals(deadline, DriverGpsActions.current()!!.ticket.untilElapsed)
        rows = listOf(row(42, "done"), row(43, "done"))
        DriverGpsActions.closed(43); shadowOf(Looper.getMainLooper()).idle()
        assertNull(DriverGpsActions.current())
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertTrue(shadowOf(svc).isStoppedBySelf)
    }
    @Test fun partialMissingCarrierPausesRatherThanDeclaringLastAndCanResumeBWithoutRenewal() {
        activate(); val deadline = DriverGpsActions.current()!!.ticket.untilElapsed
        val first = socket(); val svc = svc(); svc.onStartCommand(intent(), 0, 1)
        assertTrue(first.await(5, TimeUnit.SECONDS))
        rows = listOf(row(42, "done")); partial = true
        DriverGpsActions.closed(42); shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(DriverGpsActions.current())
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertFalse(shadowOf(svc).isStoppedBySelf)
        rows = listOf(row(43)); partial = true
        val second = socket()
        shadowOf(Looper.getMainLooper()).idleFor(12, TimeUnit.SECONDS)
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals(43, TripLocationBus.bookingId)
        assertEquals(deadline, DriverGpsActions.current()!!.ticket.untilElapsed)
    }
    @Test fun permissionRevokeThenOsGrantCannotRestartOldIntent() {
        activate(); val first = socket(); val svc = svc(); svc.onStartCommand(intent(), 0, 1)
        assertTrue(first.await(5, TimeUnit.SECONDS))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        assertNull(DriverGpsActions.current())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        svc.onStartCommand(intent(), 0, 2)
        assertTrue(shadowOf(manager).locationUpdateListeners.isEmpty())
        assertEquals(1, server.requestCount)
    }
}
