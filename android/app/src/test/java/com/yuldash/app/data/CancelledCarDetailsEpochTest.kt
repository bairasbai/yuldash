package com.yuldash.app.data

import android.app.Application
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Race/reverse cases: DETAILS,ROLE,DETAILS (3 GET); 201: ROLE,DETAILS (2);
 * owner B: A DETAILS,B ROLE,B DETAILS (3). Every other wire request is rejected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class CancelledCarDetailsEpochTest : SnapshotWiringFixture() {
    private var completionProven = false
    override fun permitWiringCleanup(): Boolean = completionProven

    private class Hold {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }

    private inner class Control(
        val scope: CoroutineScope, val expected: List<String>, val users: List<Int>, val code: Int, val flag: Boolean,
    ) {
        val old = Hold()
        val fresh = Hold()
        val jobs = mutableListOf<Deferred<*>>()
        val active = AtomicInteger()
        val seen = AtomicInteger()
        val details = AtomicInteger()
        val failure = AtomicReference<String?>(null)
        fun <T> call(block: suspend () -> T): Deferred<T> =
            scope.async(Dispatchers.IO) { block() }.also { jobs.add(it) }

        fun respond(request: RecordedRequest): MockResponse {
            active.incrementAndGet()
            try {
                val index = seen.getAndIncrement()
                val wire = request.method + " " + request.path
                val user = users.getOrNull(index)
                if (expected.getOrNull(index) != wire || user == null ||
                    request.getHeader("Authorization") != "Bearer " + jwt(user)) {
                    failure.compareAndSet(null, "CONTROL_UNEXPECTED_WIRE " + wire)
                    return MockResponse().setResponseCode(598).setBody("{}")
                }
                if (request.path == "/bookings/11/role") return MockResponse().setBody(
                    roleJson(status = "cancelled").put("cancelled_in_transit", true).toString())
                val hold = if (details.getAndIncrement() == 0) old else fresh
                hold.started.complete(Unit)
                if (!hold.release.await(350, TimeUnit.MILLISECONDS)) {
                    failure.compareAndSet(null, "CONTROL_HOLD_DEADLINE_350MS")
                    return MockResponse().setResponseCode(598).setBody("{}")
                }
                val body = JSONObject().put("booking_id", 11).put("ride_id", 33)
                    .put("role", "passenger").put("status", "cancelled").put("contact_unlocked", false)
                    .put("driver_name", "QA Driver").put("driver_car", "QA car").put("driver_car_color", "gray")
                    .put("driver_plate", if (hold === old) "OLD-plate" else "FRESH-plate")
                    .put("driver_phone", "").put("cancelled_in_transit", flag)
                return MockResponse().setResponseCode(code).setBody(body.toString())
            } finally {
                active.decrementAndGet()
            }
        }

        fun clean() { check(failure.get() == null) { failure.get().orEmpty() } }
        fun exactWire() {
            clean()
            val actual = requests.map { it.method + " " + it.path }
            check(actual == expected && seen.get() == expected.size) { "CONTROL_WIRE_COUNT_OR_ORDER " + actual }
        }
    }

    private val racePaths = listOf("GET /bookings/11/details", "GET /bookings/11/role", "GET /bookings/11/details")
    private fun scenario(
        paths: List<String>, users: List<Int>, code: Int = 200, flag: Boolean = true, action: suspend Control.() -> Unit,
    ) = runBlocking {
        check(ApiClient.testTimeoutMs == 500) { "CONTROL_NETWORK_TIMEOUT_CHANGED" }
        val c = Control(this, paths, users, code, flag)
        handler = c::respond
        try {
            try {
                withTimeout(300) { c.action() }
            } catch (failure: TimeoutCancellationException) {
                throw AssertionError("CONTROL_DEADLINE_300MS", failure)
            }
        } finally {
            c.old.release.countDown()
            c.fresh.release.countDown()
            c.jobs.filterNot { it.isCompleted }.forEach { it.cancel() }
            completionProven = withContext(NonCancellable) {
                withTimeoutOrNull(1500) { c.jobs.forEach { it.join() }; true } == true
            } && c.jobs.all { it.isCompleted } && c.active.get() == 0
            check(completionProven) { "CLEANUP_INCOMPLETE_NO_BASE_RESET" }
            c.exactWire()
        }
    }

    private fun Control.old409(result: Result<BookingDetailsDto>) {
        clean()
        val error = result.exceptionOrNull() as? ApiException
        check(error != null && error.status == 409 && error.detailCode == "snapshot_superseded") { "CONTROL_OLD_NOT_SUPERSEDED" }
    }

    private fun Control.accepted(
        result: Result<BookingDetailsDto>, expectedEpoch: Long, causalRed: Boolean = false,
    ): TripPrivateProjection {
        clean()
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            val race = causalRed && error is ApiException && error.status == 409 && error.detailCode == "snapshot_superseded"
            throw AssertionError(if (race) "RACE_RED_FRESH_200_SUPERSEDED" else "CONTROL_FRESH_TRANSPORT_FAILURE", error)
        }
        val value = result.getOrThrow()
        val projection = requireNotNull(OfflineTripSnapshots.projection(11))
        assertSame(value, projection.details)
        assertEquals(owner(), projection.owner)
        assertEquals(owner(), projection.proof.owner)
        assertEquals(owner(), projection.proof.request.ticket.owner)
        assertTrue(OfflineTripSnapshots.gate.current(projection.proof.request.ticket))
        assertTrue(projection.proof.verified())
        assertEquals(SnapshotAuthorityKind.DETAILS, projection.proof.request.kind)
        assertEquals(11, projection.proof.request.bookingId)
        assertEquals(expectedEpoch, projection.proof.request.epoch)
        assertEquals(expectedEpoch, requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)).epoch)
        assertEquals("FRESH-plate", value.driverPlate)
        assertEquals("cancelled", value.status)
        assertEquals("passenger", value.role)
        assertEquals(flag, value.cancelledInTransit)
        assertNull(projection.permit)
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertFalse(OfflineTripSnapshots.privateDetails(value))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertNull(OfflineTripSnapshots.currentBoardingCode(11))
        return projection
    }

    private fun heldOrder(flag: Boolean, reverse: Boolean) {
        scenario(racePaths, listOf(7, 7, 7), flag = flag) {
            val oldCall = call { ApiClient.getBookingDetails(11) }
            old.started.await()
            val role = call { ApiClient.getTripStateForNavigation(11) }.await()
            assertNotNull(role.acceptedRole)
            assertEquals(owner(), requireNotNull(OfflineTripSnapshots.terminal(11)).owner)
            val currentEpoch = requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)).epoch
            val freshCall = call { ApiClient.getBookingDetails(11) }
            fresh.started.await()
            if (reverse) {
                fresh.release.countDown()
                val projection = accepted(freshCall.await(), currentEpoch)
                old.release.countDown()
                old409(oldCall.await())
                assertSame(projection, OfflineTripSnapshots.projection(11))
                assertEquals(currentEpoch, requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)).epoch)
            } else {
                old.release.countDown()
                old409(oldCall.await())
                fresh.release.countDown()
                accepted(freshCall.await(), currentEpoch, causalRed = true)
            }
        }
    }

    @Test fun oldBeforeFreshWithServerConsistentFlag() = heldOrder(flag = true, reverse = false)
    @Test fun oldBeforeFreshWithOriginalFalseDetailsFlag() = heldOrder(flag = false, reverse = false)
    @Test fun freshBeforeOldRetainsAcceptedProjection() = heldOrder(flag = true, reverse = true)

    @Test fun valid201AfterOwnTerminalHasNoAcceptedDetailsProjection() =
        scenario(listOf("GET /bookings/11/role", "GET /bookings/11/details"), listOf(7, 7), code = 201) {
            val role = call { ApiClient.getTripStateForNavigation(11) }.await()
            assertNotNull(role.acceptedRole)
            old.release.countDown()
            val result = call { ApiClient.getBookingDetails(11) }.await()
            clean()
            val value = result.getOrThrow()
            assertEquals("OLD-plate", value.driverPlate)
            assertNull(OfflineTripSnapshots.projection(11))
            assertFalse(OfflineTripSnapshots.freshDetails(value))
            assertFalse(OfflineTripSnapshots.allowsLive(11))
            assertNull(OfflineTripSnapshots.privatePass(11))
            assertNull(OfflineTripSnapshots.currentBoardingCode(11))
        }

    @Test fun lateOwnerADetailsCannotCorruptAcceptedOwnerBDetails() =
        scenario(racePaths, listOf(7, 8, 8)) {
            val previous = owner()
            val oldCall = call { ApiClient.getBookingDetails(11) }
            old.started.await()
            ApiClient.saveToken(jwt(8))
            val role = call { ApiClient.getTripStateForNavigation(11) }.await()
            assertNotNull(role.acceptedRole)
            assertEquals(8, owner().userId)
            assertNotEquals(previous, owner())
            val currentEpoch = requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)).epoch
            fresh.release.countDown()
            val projection = accepted(call { ApiClient.getBookingDetails(11) }.await(), currentEpoch)
            old.release.countDown()
            val rejected = oldCall.await()
            clean()
            assertTrue(rejected.isFailure)
            assertEquals("Session changed", rejected.exceptionOrNull()?.message)
            assertEquals("SessionChangedException", rejected.exceptionOrNull()?.javaClass?.simpleName)
            assertSame(projection, OfflineTripSnapshots.projection(11))
            assertEquals(owner(), projection.owner)
        }
}
