package com.yuldash.app

import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.TripStateDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoardingUndoStateTest {
    private val first = "2026-10-24T12:00:00.000000"
    private val next = "2026-10-24T12:00:00.000001"
    private fun epoch(at: String) = BoardingUndoTerms(onboardAt = at)
    private fun receipt(at: String) = BoardingUndoTerms(confirmedId = "confirmed-request", sourceOnboardAt = at)

    @Test fun oldPollCannotBringCorrectedBoardingBack() {
        val gate = BoardingUndoState()
        assertTrue(gate.accept("confirmed", "onboard", epoch(first)))
        assertTrue(gate.accept("onboard", "confirmed", receipt(first)))
        assertFalse(gate.accept("confirmed", "onboard", epoch(first)))
        assertTrue(gate.accept("confirmed", "onboard", epoch(next)))
    }

    @Test fun oldPassengerAnswerCannotChangeTheNextBoarding() {
        val gate = BoardingUndoState()
        gate.accept("confirmed", "onboard", epoch(first))
        gate.accept("onboard", "confirmed", receipt(first))
        gate.accept("confirmed", "onboard", epoch(next))
        assertFalse(gate.resultIsCurrent("onboard", BoardingResult("onboard", 0, epoch(first))))
        assertFalse(gate.resultIsCurrent("onboard", BoardingResult("confirmed", 0, receipt(first))))
        assertTrue(gate.resultIsCurrent("onboard", BoardingResult("onboard", 0, epoch(next))))
    }

    @Test fun terminalBookingCannotBeOpenedByACorrectionReceipt() {
        val gate = BoardingUndoState()
        gate.accept("confirmed", "onboard", epoch(first))
        for (status in listOf("done", "cancelled")) {
            assertFalse(gate.accept(status, "confirmed", receipt(first)))
            assertFalse(gate.resultIsCurrent(status, BoardingResult("confirmed", 0, receipt(first))))
        }
    }

    @Test fun ordinaryConfirmedPollCannotEraseBoarding() {
        val gate = BoardingUndoState()
        gate.accept("confirmed", "onboard", epoch(first))
        assertFalse(gate.accept("onboard", "confirmed", BoardingUndoTerms()))
        assertTrue(gate.accept("onboard", "confirmed", receipt(first)))
    }

    @Test fun newerBoardingCannotBePoisonedByTheOlderPendingSnapshot() {
        val gate = BoardingUndoState()
        assertTrue(gate.accept("confirmed", "onboard", epoch(first)))
        assertTrue(gate.accept("onboard", "onboard", epoch(next)))
        assertFalse(gate.accept("onboard", "onboard", epoch(first).copy(pending = true, requestId = "old-A")))
        assertTrue(gate.resultIsCurrent("onboard", BoardingResult("confirmed", 0, receipt(next))))
        assertTrue(gate.accept("onboard", "confirmed", receipt(next)))
    }

    @Test fun aNewEpochInvalidatesAnOlderRoleReadEvenThoughStatusDidNotChange() = runBlocking {
        val reply = CompletableDeferred<Result<TripStateDto>>()
        val gate = BoardingUndoState { reply.await() }
        gate.accept("confirmed", "onboard", epoch(first))
        val oldRead = async(start = CoroutineStart.UNDISPATCHED) { gate.read(42) }
        assertTrue(gate.accept("onboard", "onboard", epoch(next)))
        reply.complete(Result.success(TripStateDto("passenger", "onboard", "", boardingUndo = epoch(first))))
        assertTrue("ответ, начатый до новой посадки, уже не текущий", oldRead.await().isFailure)
        assertTrue(gate.accept("onboard", "confirmed", receipt(next)))
    }

    @Test fun duplicateEpochDoesNotInvalidateACurrentRoleRead() = runBlocking {
        val reply = CompletableDeferred<Result<TripStateDto>>()
        val gate = BoardingUndoState { reply.await() }
        gate.accept("confirmed", "onboard", epoch(next))
        val currentRead = async(start = CoroutineStart.UNDISPATCHED) { gate.read(42) }
        assertTrue(gate.accept("onboard", "onboard", epoch("2026-10-24T12:00:00.000001Z")))
        reply.complete(Result.success(TripStateDto("passenger", "onboard", "", boardingUndo = epoch(next))))
        assertTrue(currentRead.await().isSuccess)
    }

    @Test fun missingOrInvalidEpochCannotEraseKnownBoardingAndNewerCStillWorks() {
        val gate = BoardingUndoState()
        gate.accept("confirmed", "onboard", epoch(next))
        assertFalse(gate.accept("onboard", "onboard", BoardingUndoTerms()))
        assertFalse(gate.accept("onboard", "onboard", epoch("not-a-time")))
        assertTrue(gate.accept("onboard", "confirmed", receipt(next)))
        assertFalse(gate.accept("confirmed", "onboard", BoardingUndoTerms()))
        assertFalse(gate.accept("confirmed", "onboard", epoch(first)))
        val third = "2026-10-24T12:00:00.000002"
        assertTrue(gate.accept("confirmed", "onboard", epoch(third)))
        assertFalse(gate.accept("onboard", "onboard", epoch(next)))
        assertTrue(gate.accept("onboard", "confirmed", receipt(third)))
    }
}
