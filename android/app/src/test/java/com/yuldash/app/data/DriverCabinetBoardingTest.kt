package com.yuldash.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DriverCabinetBoardingTest {
    private val firstBoarding = "2026-11-12T19:00:00"
    private val nextBoarding = "2026-11-12T19:01:00"
    private val booking = DriverBookingDto(1, "Пассажир", null, "Уфа → Белебей", "confirmed")

    @Test fun boardingResultKeepsTheStatusAndUsesTheOriginalDeadlineAfterScrolling() {
        val boarded = booking.applyBoardingResult(result(firstBoarding), nowElapsedMs = 10_000)

        assertEquals("onboard", boarded.status)
        assertEquals(240, boarded.boardingUndoSecondsAt(70_000))
        assertEquals(0, boarded.boardingUndoSecondsAt(310_000))
    }

    @Test fun repeatedReadForTheSameBoardingDoesNotExtendTheWindow() {
        val boarded = booking.applyBoardingResult(result(firstBoarding), nowElapsedMs = 10_000)
        val reread = booking.copy(status = "onboard", boardingUndoSec = 300, boardingUndo = result(firstBoarding).undo)
            .observeBoardingUndo(nowElapsedMs = 70_000, previous = boarded)

        assertEquals(240, reread.boardingUndoSecondsAt(70_000))
    }

    @Test fun pendingCorrectionRemainsOnboardAndCannotOfferAnotherRequest() {
        val boarded = booking.applyBoardingResult(result(firstBoarding), nowElapsedMs = 10_000)
        val requested = boarded.applyBoardingResult(
            result(firstBoarding).copy(undoSec = 0, undo = BoardingUndoTerms(pending = true, onboardAt = firstBoarding)),
            nowElapsedMs = 20_000,
        )

        assertEquals("onboard", requested.status)
        assertEquals(true, requested.boardingUndo.pending)
        assertEquals(0, requested.boardingUndoSecondsAt(20_000))
    }

    @Test fun lateResponseCannotReplaceAnObservedNewBoardingOrCancelledBooking() {
        val current = booking.applyBoardingResult(result(nextBoarding), nowElapsedMs = 70_000)
        val cancelled = current.copy(status = "cancelled")

        assertSame(current, current.applyBoardingResult(result(firstBoarding), nowElapsedMs = 75_000))
        assertSame(cancelled, cancelled.applyBoardingResult(result(nextBoarding), nowElapsedMs = 75_000))
    }

    private fun result(onboardAt: String) =
        BoardingResult("onboard", 300, BoardingUndoTerms(onboardAt = onboardAt))
}
