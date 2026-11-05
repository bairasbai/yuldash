package com.yuldash.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.BookingDetailsDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ActiveTripDetailsBoardingUndoTest {
    private val a = "2026-10-24T12:00:00.000001Z"
    private val b = "2026-10-24T12:00:00.000002Z"
    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
    }
    @After fun cleanup() { ApiClient.resetForTest() }
    private fun gate(at: String = a) = BoardingUndoState().also {
        assertTrue(it.accept("confirmed", "onboard", BoardingUndoTerms(onboardAt = at)))
    }
    private fun details(at: String = a) = BookingDetailsDto(
        bookingId = 42, rideId = 8, role = "passenger", status = "confirmed", contactUnlocked = true,
        fromCity = "Уфа", toCity = "Сибай", departAt = "", seats = 1, price = 300,
        driverName = "", driverVerified = false, driverPhone = "", driverCar = "", pickup = "",
        pickupLat = null, pickupLng = null, fromLat = null, fromLng = null, toLat = null, toLng = null,
        boardingUndo = BoardingUndoTerms(confirmedId = "request-A", sourceOnboardAt = at),
    )
    private fun loader() = ActiveTripDetailsState(42, ApiClient.queueSessionGeneration())
    private fun predicate(gate: BoardingUndoState): (String, BookingDetailsDto) -> Boolean = { current, incoming ->
        gate.canConfirmCorrection(current, incoming.status, incoming.boardingUndo)
    }

    @Test fun failedRoleThenManualDetailsRetryAcceptsCurrentReceiptWithoutMutatingGateEarly() = runBlocking {
        val gate = gate()
        assertTrue(BoardingUndoState { Result.failure(IllegalStateException("role unavailable")) }.readFresh(42).isFailure)
        val state = loader()
        assertNull(state.load({ true }, { "onboard" }, { "passenger" },
            fetch = { Result.failure(IllegalStateException("details unavailable")) }, confirmsBoardingCorrection = predicate(gate)))
        state.retry()
        val fresh = state.load({ true }, { "onboard" }, { "passenger" },
            fetch = { Result.success(details()) }, confirmsBoardingCorrection = predicate(gate))
        assertNotNull(fresh)
        assertNull(state.failure)
        // Loading only validates: until the caller commits, the same A receipt is still admissible.
        assertTrue(gate.canConfirmCorrection("onboard", "confirmed", details().boardingUndo))
        assertTrue(gate.accept("onboard", fresh!!.status, fresh.boardingUndo))
        assertFalse(gate.accept("confirmed", "onboard", BoardingUndoTerms(onboardAt = a)))
        assertTrue(gate.accept("confirmed", "onboard", BoardingUndoTerms(onboardAt = b)))
    }

    @Test fun oldReceiptAfterNewBoardingAndMissingReceiptStayRejected() = runBlocking {
        val gate = gate(b)
        for (incoming in listOf(details(), details(b).copy(boardingUndo = BoardingUndoTerms()))) {
            assertNull(loader().load({ true }, { "onboard" }, { "passenger" },
                fetch = { Result.success(incoming) }, confirmsBoardingCorrection = predicate(gate)))
        }
        assertTrue(gate.canConfirmCorrection("onboard", "confirmed", details(b).boardingUndo))
    }

    @Test fun bookingRoleAndTerminalGuardsRunBeforeCorrectionPredicate() = runBlocking {
        for ((status, incoming) in listOf("onboard" to details().copy(bookingId = 43),
            "onboard" to details().copy(role = "driver"), "done" to details(), "cancelled" to details(),
            "pending" to details())) {
            var checked = false
            assertNull(loader().load({ true }, { status }, { "passenger" }, fetch = { Result.success(incoming) },
                confirmsBoardingCorrection = { _, _ -> checked = true; true }))
            assertFalse(checked)
        }
    }

    @Test fun changedOwnerOrUnmountRejectsHeldDetailsBeforePredicate() = runBlocking {
        for (changeOwner in listOf(false, true)) {
            val state = loader()
            val held = CompletableDeferred<Result<BookingDetailsDto>>()
            var live = true
            var checked = false
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                state.load({ live }, { "onboard" }, { "passenger" }, fetch = { held.await() },
                    confirmsBoardingCorrection = { _, _ -> checked = true; true })
            }
            if (changeOwner) ApiClient.saveToken("fixture-next-owner") else live = false
            held.complete(Result.success(details()))
            assertNull(pending.await())
            assertFalse(checked)
        }
    }

    @Test fun supersededDetailsCannotValidateReceiptOrReplaceNewerFailure() = runBlocking {
        val state = loader()
        val held = CompletableDeferred<Result<BookingDetailsDto>>()
        var checked = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            state.load({ true }, { "onboard" }, { "passenger" }, fetch = { held.await() },
                confirmsBoardingCorrection = { _, _ -> checked = true; true })
        }
        val failure = IllegalStateException("new details failure")
        assertNull(state.load({ true }, { "onboard" }, { "passenger" }, fetch = { Result.failure(failure) }))
        held.complete(Result.success(details()))
        assertNull(pending.await())
        assertFalse(checked)
        assertSame(failure, state.failure)
    }
}
