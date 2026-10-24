package com.yuldash.app

import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
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
}
