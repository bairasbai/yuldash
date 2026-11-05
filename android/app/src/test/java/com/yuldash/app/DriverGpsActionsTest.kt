package com.yuldash.app

import android.Manifest
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.Outbox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverGpsActionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun feed() = DriverBookingsFeed(listOf(DriverBookingDto(42, "", null, "", "onboard", rideId = 8)), false)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(app)
        ApiClient.saveToken("fixture.eyJzdWIiOiIxIn0.fixture")
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        DriverGpsActions.revoke()
    }
    @After fun cleanup() { DriverGpsActions.revoke(); ApiClient.resetForTest() }
    @Test fun lateEmptyFeedCannotRevokeNewerGrantForAnotherRide() = lateEmptyFeedCannotRevokeNewerGrant(false)
    @Test fun lateEmptyFeedCannotRevokeNewerAttemptWithReusedGrantId() = lateEmptyFeedCannotRevokeNewerGrant(true)

    private fun lateEmptyFeedCannotRevokeNewerGrant(reuseId: Boolean) = runBlocking {
        if (reuseId) {
            val initial = DriverGpsActions.begin(42, 8)!!
            assertTrue(DriverGpsActions.grant.complete(initial, DriverGpsActions.owner(), 8, 42, Long.MAX_VALUE,
                SystemClock.elapsedRealtime(), System.currentTimeMillis()))
        }
        val ticketA = DriverGpsActions.begin(42, 8)!!
        val lateReply = CompletableDeferred<Result<DriverBookingsFeed>>()
        var startsA = 0
        val pendingA = async(start = CoroutineStart.UNDISPATCHED) {
            DriverGpsActions.complete(ticketA, app, AppLanguage.Ru, { true }, fetch = { lateReply.await() }, start = { startsA++ })
        }
        val rideB = if (reuseId) 8 else 9
        val ticketB = DriverGpsActions.begin(43, rideB)!!
        assertNotSame(ticketA, ticketB)
        assertEquals("The second case must exercise distinct attempt identity under the same grant id", reuseId, ticketA.id == ticketB.id)
        var startsB = 0
        val feedB = DriverBookingsFeed(listOf(DriverBookingDto(43, "", null, "", "onboard", rideId = rideB)), false)
        assertTrue(DriverGpsActions.complete(ticketB, app, AppLanguage.Ru, { true }, fetch = { Result.success(feedB) }, start = { startsB++ }))
        assertTrue("B must already publish before the old response arrives", DriverGpsActions.allowed(app, 43))
        lateReply.complete(Result.success(DriverBookingsFeed(emptyList(), false)))
        assertFalse(pendingA.await())
        assertEquals("A late empty feed must not revoke the already activated B grant", ticketB.id, DriverGpsActions.current()?.ticket?.id)
        assertEquals(ticketB.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
        assertTrue(DriverGpsActions.allowed(app, 43))
        assertEquals(0, startsA)
        assertEquals(1, startsB)
    }

    @Test fun heldLookupExpiryUnmountAndOwnerChangeCannotActivate() = runBlocking {
        for (boundary in listOf("expiry", "unmount", "owner")) {
            DriverGpsActions.revoke()
            val ticket = DriverGpsActions.begin(42)!!
            val reply = CompletableDeferred<Result<DriverBookingsFeed>>()
            var live = true; var starts = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                DriverGpsActions.complete(ticket, app, AppLanguage.Ru, { live }, fetch = { reply.await() }, start = { starts++ })
            }
            when (boundary) {
                "expiry" -> ShadowSystemClock.advanceBy(Duration.ofMillis(DRIVER_GPS_MAX_MS + 1))
                "unmount" -> live = false
                "owner" -> ApiClient.saveToken("fixture.eyJzdWIiOiIyIn0.fixture")
            }
            reply.complete(Result.success(feed()))
            assertFalse(pending.await()); assertEquals(0, starts); assertNull(DriverGpsActions.current())
        }
    }
    @Test fun networkFailureDeniedPermissionAndWrongCanonicalRideNeverStart() = runBlocking {
        var starts = 0
        val first = DriverGpsActions.begin(42)!!
        assertFalse(DriverGpsActions.complete(first, app, AppLanguage.Ru, { true },
            fetch = { Result.failure(IllegalStateException("offline")) }, start = { starts++ }))
        val second = DriverGpsActions.begin(42)!!
        assertFalse(DriverGpsActions.complete(second, app, AppLanguage.Ru, { true },
            fetch = { Result.success(DriverBookingsFeed(feed().rows.map { it.copy(rideId = 0) }, false)) }, start = { starts++ }))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val third = DriverGpsActions.begin(42)!!
        assertFalse(DriverGpsActions.complete(third, app, AppLanguage.Ru, { true },
            fetch = { fail("No lookup may follow denied permission"); Result.success(feed()) }, start = { starts++ }))
        assertEquals(0, starts); assertNull(DriverGpsActions.current())
    }
    @Test fun successfulOwnRowsActivateSameTicketAndReplayDoesNotStartAgain() = runBlocking {
        val ticket = DriverGpsActions.begin(42)!!
        var starts = 0
        assertTrue(DriverGpsActions.complete(ticket, app, AppLanguage.Ru, { true }, fetch = { Result.success(feed()) }, start = { starts++ }))
        assertEquals(ticket.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
        assertFalse(DriverGpsActions.complete(ticket, app, AppLanguage.Ru, { true }, fetch = { Result.success(feed()) }, start = { starts++ }))
        assertEquals(1, starts)
        assertTrue(DriverGpsActions.allowed(app, 42))
    }
    @Test fun genericPartialHeaderSurvivesTheRealNativeHttpParser() = runBlocking {
        val server = MockWebServer()
        try {
            server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setHeader("X-Yuldash-Partial", "1")
                .setBody("""[{"booking_id":42,"ride_id":8,"status":"onboard"}]"""))
            val feed = gpsDriverBookings(ApiClient.queueSessionGeneration()).getOrThrow()
            assertTrue(feed.partial); assertEquals(8, feed.rows.single().rideId)
            assertEquals("/driver/bookings", server.takeRequest().path)
        } finally { server.shutdown() }
    }
    @Test fun directTransportAndQueuedReplayNeverCreateOrRenewGpsPermission() = runBlocking {
        val server = MockWebServer()
        try {
            server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
            repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
            assertTrue(ApiClient.driverStatus(42, "departed").isSuccess)
            assertNull(DriverGpsActions.current())
            val queued = Outbox.newDriverStatus(42, "departed")
            assertTrue(ApiClient.sendQueuedAction(queued, ApiClient.queueSessionGeneration()).isSuccess)
            assertNull(DriverGpsActions.current())
            val ticket = DriverGpsActions.begin(42)!!
            assertTrue(DriverGpsActions.grant.complete(ticket, DriverGpsActions.owner(), 8, 42, Long.MAX_VALUE,
                SystemClock.elapsedRealtime(), System.currentTimeMillis()))
            assertTrue(ApiClient.sendQueuedAction(queued, ApiClient.queueSessionGeneration()).isSuccess)
            assertEquals(ticket.untilElapsed, DriverGpsActions.current()!!.ticket.untilElapsed)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
