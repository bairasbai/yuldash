package com.yuldash.app

import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.TripStateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BoardingUndoFreshReadTest {
    private val epochA = "2026-10-24T12:00:00.000000Z"
    private val epochB = "2026-10-24T12:00:00.000001Z"
    private fun state(phase: String = "", status: String = "confirmed", at: String? = null) =
        TripStateDto("driver", status, phase, boardingUndo = BoardingUndoTerms(onboardAt = at))
    private fun receipt(at: String) = BoardingUndoTerms(confirmedId = "accepted", sourceOnboardAt = at)

    @Test fun initialDetailsSupersedeTheFirstRoleReadWithoutApplyingItsPhase() = runBlocking {
        for (phase in listOf("departed", "arriving")) {
            val reply = CompletableDeferred<Result<TripStateDto>>()
            var calls = 0
            val gate = BoardingUndoState { calls++; reply.await() }
            var appliedPhase = ""
            val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.read(42) }
            assertEquals(1, calls)
            assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
            reply.complete(Result.success(state(phase)))
            val result = pending.await().onSuccess { appliedPhase = it.driverPhase }
            assertEquals(409, (result.exceptionOrNull() as ApiException).status)
            assertEquals("", appliedPhase); assertEquals(1, calls)
            println("boarding-read-causality initialRead=started initialDetails=confirmed latePhase=$phase result=409 appliedPhase=empty calls=$calls")
        }
    }

    @Test fun oneFreshReadGetsDepartedOrArrivingAfterInitialDetailsWin() = runBlocking {
        for (phase in listOf("departed", "arriving")) {
            val reply = CompletableDeferred<Result<TripStateDto>>()
            var calls = 0
            val gate = BoardingUndoState { id ->
                assertEquals(42, id)
                if (++calls == 1) reply.await() else Result.success(state(phase))
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.readFresh(42) }
            assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
            reply.complete(Result.success(state()))
            val fresh = pending.await().getOrThrow()
            assertEquals(phase, fresh.driverPhase); assertEquals(2, calls)
            val next = activeTripNextStatus(fresh.role, fresh.driverPhase, null, fresh.status)
            assertEquals(if (phase == "departed") "arriving" else "boarded", next)
            assertTrue(activeTripShowsDriverFinish(fresh.role, fresh.driverPhase, next))
        }
    }

    @Test fun ordinarySuccessNeedsOnlyOneRead() = runBlocking {
        var calls = 0
        val response = state("arriving")
        val gate = BoardingUndoState { calls++; Result.success(response) }
        assertSame(response, gate.readFresh(42).getOrThrow()); assertEquals(1, calls)
    }

    @Test fun server409NetworkAndDecodeFailuresNeverGetTheLocalRetryIdentity() = runBlocking {
        for (changed in listOf(false, true)) for (failure in listOf(
            ApiException(409, "server conflict"), ApiException(503, "server unavailable"),
            IOException("wire failure"), IllegalArgumentException("invalid decoded role")
        )) {
            val reply = CompletableDeferred<Result<TripStateDto>>()
            var calls = 0
            val gate = BoardingUndoState { calls++; reply.await() }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.readFresh(42) }
            if (changed) assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
            reply.complete(Result.failure(failure))
            val result = pending.await()
            assertEquals(1, calls)
            if (changed) assertEquals("Strict read keeps its pre-existing superseded-failure contract", 409,
                (result.exceptionOrNull() as ApiException).status)
            else assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test fun aSecondSupersededPayloadFailsWithoutAThirdRequest() = runBlocking {
        val first = CompletableDeferred<Result<TripStateDto>>()
        val second = CompletableDeferred<Result<TripStateDto>>()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val gate = BoardingUndoState {
            when (++calls) {
                1 -> first.await()
                2 -> { secondStarted.complete(Unit); second.await() }
                else -> error("A third GET is forbidden")
            }
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.readFresh(42) }
        assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
        first.complete(Result.success(state()))
        secondStarted.await()
        gate.actionCommitted()
        second.complete(Result.success(state("arriving")))
        assertEquals(409, (pending.await().exceptionOrNull() as ApiException).status)
        assertEquals(2, calls)
    }

    @Test fun aNewBoardingEpochDuringRetryStillRejectsTheOldPayloadAndReceipt() = runBlocking {
        val first = CompletableDeferred<Result<TripStateDto>>()
        val second = CompletableDeferred<Result<TripStateDto>>()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val gate = BoardingUndoState {
            if (++calls == 1) first.await() else { secondStarted.complete(Unit); second.await() }
        }
        assertTrue(gate.accept("confirmed", "onboard", BoardingUndoTerms(onboardAt = epochA)))
        val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.readFresh(42) }
        assertTrue(gate.accept("onboard", "confirmed", receipt(epochA)))
        first.complete(Result.success(state(status = "onboard", at = epochA)))
        secondStarted.await()
        assertTrue(gate.accept("confirmed", "onboard", BoardingUndoTerms(onboardAt = epochB)))
        second.complete(Result.success(state(status = "onboard", at = epochA)))
        assertEquals(409, (pending.await().exceptionOrNull() as ApiException).status); assertEquals(2, calls)
        assertFalse(gate.accept("onboard", "onboard", BoardingUndoTerms(onboardAt = epochA)))
        assertFalse(gate.accept("onboard", "confirmed", receipt(epochA)))
        assertFalse(gate.resultIsCurrent("onboard", BoardingResult("confirmed", 0, receipt(epochA))))
        assertTrue(gate.accept("onboard", "confirmed", receipt(epochB)))
    }

    @Test fun terminalStateDuringRetryCannotBeRevived() = runBlocking {
        for (terminal in listOf("done", "cancelled")) {
            val first = CompletableDeferred<Result<TripStateDto>>()
            val second = CompletableDeferred<Result<TripStateDto>>()
            val secondStarted = CompletableDeferred<Unit>()
            var calls = 0
            val gate = BoardingUndoState {
                if (++calls == 1) first.await() else { secondStarted.complete(Unit); second.await() }
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { gate.readFresh(42) }
            assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
            first.complete(Result.success(state()))
            secondStarted.await()
            assertTrue(gate.accept("confirmed", terminal, BoardingUndoTerms()))
            second.complete(Result.success(state("arriving")))
            assertEquals(409, (pending.await().exceptionOrNull() as ApiException).status); assertEquals(2, calls)
            assertFalse(gate.accept(terminal, "confirmed", receipt(epochA)))
            assertFalse(gate.resultIsCurrent(terminal, BoardingResult("confirmed", 0, receipt(epochA))))
        }
    }

    @Test fun cancellationBeforeOrDuringTheOnlyRetryEscapesUnchanged() = runBlocking {
        for (cancelOn in listOf(1, 2)) {
            val cancellation = CancellationException("cancel role read")
            var calls = 0
            lateinit var gate: BoardingUndoState
            gate = BoardingUndoState {
                if (++calls == cancelOn) throw cancellation
                assertTrue(gate.accept("", "confirmed", BoardingUndoTerms()))
                Result.success(state())
            }
            val caught = try { gate.readFresh(42); null } catch (failure: CancellationException) { failure }
            assertSame(cancellation, caught); assertEquals(cancelOn, calls)
        }
    }
}
