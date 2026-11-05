package com.yuldash.app.data

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real API results; only retained-result delivery uses the existing screen seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class CancelledCarDetailsAdmissionTest : SnapshotWiringFixture() {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(false)
    private var contentSet = false
    private var completionProven = false
    private val callers = CopyOnWriteArrayList<Job>()
    private val active = AtomicInteger()
    private val seen = AtomicInteger()
    private val wireFailure = AtomicReference<String?>(null)
    private val delivered = AtomicReference<Result<BookingDetailsDto>?>(null)
    override fun permitWiringCleanup(): Boolean = completionProven

    private suspend fun <T> api(block: suspend () -> T): T = withTimeout(4000) {
        async(Dispatchers.IO) {
            callers += currentCoroutineContext().job
            block()
        }.await()
    }
    private suspend fun role(): TripStateDto {
        val read = api { ApiClient.getTripStateForNavigation(11) }
        val value = read.result.getOrThrow()
        requireNotNull(read.acceptedRole)
        val terminal = requireNotNull(OfflineTripSnapshots.terminal(11))
        assertEquals(owner(), terminal.owner)
        assertEquals("passenger", terminal.role)
        assertEquals("cancelled", terminal.status)
        assertTrue(terminal.inTransit)
        assertTrue(value.cancelledInTransit)
        return value
    }
    private fun epoch() = requireNotNull(OfflineTripSnapshots.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)).epoch
    private fun accepted(value: BookingDetailsDto): TripPrivateProjection {
        val p = requireNotNull(OfflineTripSnapshots.projection(11))
        assertSame(value, p.details)
        assertEquals(owner(), p.owner)
        assertEquals(owner(), p.proof.owner)
        assertEquals(owner(), p.proof.request.ticket.owner)
        assertEquals(11, p.proof.request.bookingId)
        assertEquals(SnapshotAuthorityKind.DETAILS, p.proof.request.kind)
        assertTrue(p.proof.verified())
        assertTrue(OfflineTripSnapshots.gate.current(p.proof.request.ticket))
        assertEquals(epoch(), p.proof.request.epoch)
        assertTrue(OfflineTripSnapshots.freshDetails(value))
        assertEquals("passenger", value.role)
        assertEquals("cancelled", value.status)
        assertNull(p.permit)
        return p
    }
    private fun closed(value: BookingDetailsDto) {
        assertFalse(OfflineTripSnapshots.privateDetails(value))
        assertFalse(OfflineTripSnapshots.allowsLive(11))
        assertNull(OfflineTripSnapshots.privatePass(11))
        assertNull(OfflineTripSnapshots.currentBoardingCode(11))
    }
    private fun settle() {
        compose.mainClock.advanceTimeBy(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun finish(expected: List<String>) {
        if (contentSet) {
            mounted.value = false
            compose.waitUntil(2000) {
                settle()
                compose.onAllNodesWithTag("cancelledInTransit").fetchSemanticsNodes().isEmpty()
            }
        }
        callers.filterNot { it.isCompleted }.forEach { it.cancel() }
        runBlocking { withTimeout(1500) { callers.forEach { it.join() } } }
        check(callers.all { it.isCompleted } && active.get() == 0) { "CLEANUP_INCOMPLETE_NO_BASE_RESET" }
        check(wireFailure.get() == null) { wireFailure.get().orEmpty() }
        assertEquals(expected, requests.map { it.method + " " + it.path })
        assertEquals(expected.size, seen.get())
        InTransitCancels.clearForTest()
        completionProven = true
    }
    private fun body(plate: String, flag: Boolean) = JSONObject()
        .put("booking_id", 11).put("ride_id", 33).put("role", "passenger").put("status", "cancelled")
        .put("contact_unlocked", false).put("cancelled_in_transit", flag).put("driver_phone", "")
        .put("driver_name", "QA Driver").put("driver_car", "QA car-" + plate).put("driver_car_color", "gray")
        .put("driver_plate", plate).toString()

    private fun scenario(mode: Int, flag: Boolean = true) = runBlocking {
        check(ApiClient.testTimeoutMs == 500) { "CONTROL_NETWORK_TIMEOUT_CHANGED" }
        val suffixes = listOf("/role", "/details") + when (mode) {
            3, 4 -> listOf("/details")
            5 -> listOf("/role")
            else -> emptyList()
        }
        val expected = suffixes.map { "GET /bookings/11" + it }
        handler = { request ->
            active.incrementAndGet()
            try {
                val index = seen.getAndIncrement()
                val user = if (mode == 5 && index == 2) 8 else 7
                val wire = request.method + " " + request.path
                if (expected.getOrNull(index) != wire || request.getHeader("Authorization") != "Bearer " + jwt(user)) {
                    wireFailure.compareAndSet(null, "CONTROL_UNEXPECTED_WIRE " + wire)
                    MockResponse().setResponseCode(598).setBody("{}")
                } else if (suffixes[index] == "/role") MockResponse().setBody(
                    roleJson(status = "cancelled").put("cancelled_in_transit", true).toString())
                else if (mode == 4 && index == 2) MockResponse().setResponseCode(400).setBody("{}")
                else MockResponse().setResponseCode(if (mode == 2) 201 else 200)
                    .setBody(body(if (mode == 3 && index == 2) "CURRENT-plate" else "OLD-plate", flag))
            } finally { active.decrementAndGet() }
        }
        try {
            var state = CancelledInTransitState(false).apply { accept(role()) }
            val originalOwner = owner()
            var replay: Result<BookingDetailsDto>? = null
            var original: TripPrivateProjection? = null
            if (mode >= 3) {
                val old = api { ApiClient.getBookingDetails(11) }
                val projection = accepted(old.getOrThrow())
                replay = old
                original = projection
                when (mode) {
                    3 -> {
                        val newer = api { ApiClient.getBookingDetails(11) }.getOrThrow()
                        assertNotSame(old.getOrThrow(), accepted(newer).details)
                        assertFalse(OfflineTripSnapshots.freshDetails(old.getOrThrow()))
                    }
                    4 -> {
                        val error = api { ApiClient.getBookingDetails(11) }.exceptionOrNull() as? ApiException
                        assertEquals(400, error?.status)
                        assertSame(projection, OfflineTripSnapshots.projection(11))
                        assertSame(old.getOrThrow(), projection.details)
                        assertTrue(OfflineTripSnapshots.freshDetails(old.getOrThrow()))
                        assertTrue(projection.proof.verified())
                        assertTrue(OfflineTripSnapshots.current(projection.owner))
                        assertNotEquals(projection.proof.request.epoch, epoch())
                    }
                    5 -> {
                        ApiClient.saveToken(jwt(8))
                        state = CancelledInTransitState(false).apply { accept(role()) }
                        assertNotEquals(originalOwner, owner())
                        assertEquals(8, owner().userId)
                        assertFalse(OfflineTripSnapshots.current(projection.owner))
                        assertFalse(OfflineTripSnapshots.freshDetails(old.getOrThrow()))
                        assertNull(OfflineTripSnapshots.projection(11))
                    }
                }
            }
            val screenOwner = ApiClient.queueSessionGeneration()
            assertEquals(owner().generation, screenOwner)
            val retained = replay
            compose.mainClock.autoAdvance = true
            mounted.value = true
            contentSet = true
            compose.setContent {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    if (mounted.value) CancelledInTransitScreen(11, state, screenOwner, {}, {},
                        fetchDetails = { id ->
                            callers += currentCoroutineContext().job
                            (retained ?: api { ApiClient.getBookingDetails(id) }).also { delivered.set(it) }
                        })
                }
            }
            compose.waitUntil(5000) {
                settle()
                delivered.get() != null && callers.all { it.isCompleted }
            }
            compose.waitForIdle()
            check(wireFailure.get() == null) { wireFailure.get().orEmpty() }
            val value = requireNotNull(delivered.get()).getOrThrow()
            if (retained != null) assertSame(retained.getOrThrow(), value)
            if (mode < 2) {
                accepted(value)
                assertEquals(flag, value.cancelledInTransit)
                closed(value)
                compose.onNodeWithText(value.driverPlate).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(inTransitCarOf(value).car).performScrollTo().assertIsDisplayed()
            } else {
                if (mode == 2) {
                    assertNull(OfflineTripSnapshots.projection(11))
                    assertFalse(OfflineTripSnapshots.freshDetails(value))
                }
                if (mode == 4) {
                    assertSame(original, OfflineTripSnapshots.projection(11))
                    assertSame(value, requireNotNull(original).details)
                    assertTrue(OfflineTripSnapshots.freshDetails(value))
                    assertNotEquals(original.proof.request.epoch, epoch())
                }
                closed(value)
                assertTrue("FORBIDDEN_PLATE_VISIBLE", compose.onAllNodesWithText(value.driverPlate).fetchSemanticsNodes().isEmpty())
                assertTrue("FORBIDDEN_CAR_VISIBLE", compose.onAllNodesWithText(inTransitCarOf(value).car).fetchSemanticsNodes().isEmpty())
                compose.onNodeWithText("Не удалось загрузить машину и госномер").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Повторить").performScrollTo().assertIsDisplayed()
            }
            compose.onAllNodesWithText("Загружаем машину и госномер").assertCountEquals(0)
            compose.onNodeWithText("Поездку отменили в пути").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("SOS — нужна помощь").performScrollTo().assertIsDisplayed()
            assertEquals(screenOwner, ApiClient.queueSessionGeneration())
            assertEquals(expected, requests.map { it.method + " " + it.path })
        } finally { finish(expected) }
    }

    @Test(timeout = 30000) fun currentTerminal200True() = scenario(0)
    @Test(timeout = 30000) fun currentTerminal200False() = scenario(1, flag = false)
    @Test(timeout = 30000) fun actual201HasNoAcceptedProjection() = scenario(2)
    @Test(timeout = 30000) fun replacedExactOldDTOIsRejected() = scenario(3)
    @Test(timeout = 30000) fun retainedTerminalProjectionFromOldEpochIsRejected() = scenario(4)
    @Test(timeout = 30000) fun ownerAResultDeliveredToCurrentOwnerBScreenIsRejected() = scenario(5)
}
