package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP tickets: delaying a response cannot reorder the tickets issued before dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class PassengerBoardingCodeOrderTest : SnapshotWiringFixture() {
    private val events = CopyOnWriteArrayList<String>()

    private fun count(path: String) = requests.count { it.path == "/bookings/11/$path" }

    private suspend fun compatibleFirstDetails(): TripPrivateProjection = coroutineScope {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detailCalls = AtomicInteger()
        ApiClient.testTimeoutMs = 8000
        assertNull(OfflineTripSnapshots.projection(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        handler = { request ->
            when (request.path) {
                "/bookings/11/details" -> {
                    if (detailCalls.incrementAndGet() == 1) {
                        events += "DETAILS-start"
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        events += "DETAILS-response"
                    }
                    defaultResponse(request)
                }
                "/bookings/11/role" -> defaultResponse(request).also { events += "ROLE-response" }
                "/bookings/11/boarding-code" -> defaultResponse(request).also { events += "CODE-response" }
                else -> defaultResponse(request)
            }
        }
        val first = async(Dispatchers.IO) { ApiClient.getBookingDetails(11) }
        try {
            assertTrue("The first actual DETAILS request must be held", started.await(10, TimeUnit.SECONDS))
            val role = ApiClient.getTripStateForNavigation(11)
            assertTrue(role.result.isSuccess)
            val roleTicket = requireNotNull(role.ticket)
            events += "ROLE-accepted"
            assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertNull(OfflineTripSnapshots.projection(11))
            assertNull(OfflineTripSnapshots.privatePass(11))
            assertNull(TripPassStore.load(context, 11))
            assertEquals(0, count("boarding-code"))
            release.countDown()
            val dto = first.await().getOrThrow()
            events += "DETAILS-accepted"
            val actual = requireNotNull(OfflineTripSnapshots.projection(11))
            assertSame(dto, actual.details)
            assertTrue(OfflineTripSnapshots.privateDetails(dto))
            assertEquals("", actual.pass.boardingCode)
            assertEquals(actual.owner, roleTicket.owner)
            assertEquals(actual.proof.request.ticket.process, roleTicket.process)
            assertTrue("Actual DETAILS ticket must precede the accepted ROLE ticket",
                actual.proof.request.ticket.sequence < roleTicket.sequence)
            println("PASSENGER_CODE_READ_ORDER detailsSequence=${actual.proof.request.ticket.sequence} " +
                "roleSequence=${roleTicket.sequence} acceptedPrivate=${OfflineTripSnapshots.privateDetails(dto)} " +
                "detailsStampWall=${actual.stamp.wall} detailsStampBefore=${actual.stamp.before}")
            assertEquals(listOf("DETAILS-start", "ROLE-response", "ROLE-accepted", "DETAILS-response", "DETAILS-accepted"), events.toList())
            assertEquals(1, count("details"))
            assertEquals(1, count("role"))
            actual
        } finally {
            release.countDown()
        }
    }

    @Test fun compatibleFirstDetailsBeforeNewerRoleAttachesCodeWithOneDetailsAndOneCodeGet(): Unit = runBlocking {
        val first = compatibleFirstDetails()
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        assertEquals("No replacement DETAILS is needed for this accepted current projection: $events", 1, count("details"))
        assertEquals("The actual CODE response attaches once: $events", 1, count("boarding-code"))
        val current = requireNotNull(OfflineTripSnapshots.projection(11))
        assertSame(first.details, current.details)
        assertSame(first.proof, current.proof)
        assertEquals(first.stamp, current.stamp)
        assertEquals("4821", requireNotNull(OfflineTripSnapshots.privatePass(11)).boardingCode)
    }

    @Test fun codeAfterTheJointReadOrderKeepsOriginalDetailsLifetimeAndCannotReviveAfterRollback(): Unit = runBlocking {
        val first = compatibleFirstDetails()
        val nearExpiry = OfflineTripSnapshotPolicy.MAX_AGE_MS
        clock = SnapshotClock(3, nearExpiry, nearExpiry, nearExpiry)
        assertEquals("4821", ApiClient.getBoardingCode(11).getOrThrow())
        val current = requireNotNull(OfflineTripSnapshots.projection(11))
        assertSame(first.details, current.details)
        assertSame(first.proof, current.proof)
        assertEquals("CODE cannot replace the original DETAILS clock", first.stamp, current.stamp)
        assertEquals("4821", OfflineTripSnapshots.currentBoardingCode(11))
        clock = SnapshotClock(3, nearExpiry + 1001, nearExpiry + 1001, nearExpiry + 1001)
        assertNull(OfflineTripSnapshots.currentBoardingCode(11))
        assertNull(TripPassStore.load(context, 11))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        clock = SnapshotClock(3, 2000, 3000, 3000)
        assertNull(OfflineTripSnapshots.currentBoardingCode(11))
        assertNull(TripPassStore.load(context, 11))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertEquals(1, count("details"))
        assertEquals(1, count("boarding-code"))
    }

    private suspend fun heldCode(change: suspend (TripPrivateProjection) -> Unit): Unit = coroutineScope {
        val first = compatibleFirstDetails()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        handler = { request -> if (request.path == "/bookings/11/boarding-code") {
            events += "CODE-start"
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            events += "CODE-response"
            MockResponse().setBody("{\"code\":\"4821\"}")
        } else MockResponse().setResponseCode(503).setBody("{}") }
        val pending = async(Dispatchers.IO) { ApiClient.getBoardingCode(11) }
        try {
            assertTrue("Positive CODE HTTP must start before the competing event", started.await(10, TimeUnit.SECONDS))
            change(first)
            release.countDown()
            assertTrue("The old CODE cannot become current success", pending.await().isFailure)
            assertFalse(OfflineTripSnapshots.privateDetails(first.details))
            assertNull(OfflineTripSnapshots.privatePass(11))
            assertNull(OfflineTripSnapshots.currentBoardingCode(11))
            assertNull(TripPassStore.load(context, 11))
            assertEquals(1, count("boarding-code"))
        } finally {
            release.countDown()
        }
    }

    @Test fun heldCodeFromTheJointReadOrderCannotCrossOwnerChange(): Unit = runBlocking {
        heldCode { first ->
            ApiClient.saveToken(jwt(8))
            assertEquals(8, owner().userId)
            assertNotEquals(first.owner, owner())
            assertFalse(OfflineTripSnapshots.allowsLive(11))
        }
        assertEquals("An old owner's operation must not refresh DETAILS for the new owner", 1, count("details"))
    }

    @Test fun heldCodeFromTheJointReadOrderCannotCrossANewerBoardingEpoch(): Unit = runBlocking {
        heldCode { first ->
            val newer = "2026-11-02T01:00:00.123456Z"
            handler = { request -> if (request.path == "/bookings/11/role")
                MockResponse().setBody(roleJson().put("onboard_at", newer).toString())
            else MockResponse().setResponseCode(503).setBody("{}") }
            val state = ApiClient.getTripStateForNavigation(11).result.getOrThrow()
            assertEquals(newer, state.boardingUndo.onboardAt)
            assertTrue(OfflineTripSnapshots.allowsLive(11))
            assertFalse(OfflineTripSnapshots.privateDetails(first.details))
        }
        assertEquals("A fresh DETAILS attempt may recover the new epoch, but its 503 supplies no private proof", 2, count("details"))
    }
}
