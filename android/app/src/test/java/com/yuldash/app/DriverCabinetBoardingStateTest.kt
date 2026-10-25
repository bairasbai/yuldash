package com.yuldash.app

import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.DriverBookingDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverCabinetBoardingStateTest {
    private val first = "2026-10-25T19:30:24.000001"
    private val next = "2026-10-25T19:40:24.000001"
    private fun dto(
        status: String = "onboard", seconds: Int = 300,
        terms: BoardingUndoTerms = BoardingUndoTerms(onboardAt = first),
    ) = DriverBookingDto(42, "Айгуль", null, "Уфа → Мелеуз", status,
        rideId = 9, boardingUndoSec = seconds, boardingUndo = terms)

    @Test fun laterIssuedReadWinsEvenForTheSameStatusAndEpoch() = runBlocking {
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { true })
        host.acceptRead(host.beginRead(), listOf(dto()))
        val a = host.beginRead()
        val b = host.beginRead()
        val refused = dto(seconds = 0)
        assertTrue(host.acceptRead(b, listOf(refused)))
        assertFalse(host.acceptRead(a, listOf(dto(terms = BoardingUndoTerms(true, "old-pending", 120, first)))))
        assertEquals(0, host.remaining(42))
        assertFalse(host.row(42)!!.undo.pending)
    }

    @Test fun aFailedLaterReadDoesNotAuthorizeAnOlderFallback() = runBlocking {
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { true })
        host.acceptRead(host.beginRead(), listOf(dto(seconds = 0)))
        val a = host.beginRead()
        host.beginRead() // The later HTTP failed: it publishes no payload.
        assertFalse(host.acceptRead(a, listOf(dto())))
        assertEquals(0, host.remaining(42))
    }

    @Test fun initialOnboardSeedsTheReceiptGateAndOldEpochCannotReturn() = runBlocking {
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { true })
        host.acceptRead(host.beginRead(), listOf(dto()))
        host.acceptRead(host.beginRead(), listOf(dto("confirmed", 0, BoardingUndoTerms())))
        assertEquals("onboard", host.row(42)!!.status)
        val receipt = BoardingUndoTerms(confirmedId = "passenger-answer", sourceOnboardAt = first)
        host.acceptRead(host.beginRead(), listOf(dto("confirmed", 0, receipt)))
        assertEquals("confirmed", host.row(42)!!.status)
        host.acceptRead(host.beginRead(), listOf(dto()))
        assertEquals("confirmed", host.row(42)!!.status)
        host.acceptRead(host.beginRead(), listOf(dto(terms = BoardingUndoTerms(onboardAt = next))))
        assertEquals(next, host.row(42)!!.undo.onboardAt)
        host.acceptRead(host.beginRead(), listOf(dto()))
        assertEquals(next, host.row(42)!!.undo.onboardAt)
    }

    @Test fun timeOutsideCompositionAndAnotherReadCannotRenewTheWindow() = runBlocking {
        var elapsed = 1000L
        val host = DriverCabinetBoardingState(1, this, now = { elapsed }, currentOwner = { true })
        host.acceptRead(host.beginRead(), listOf(dto()))
        val deadline = host.row(42)!!.deadline
        elapsed += 30_000
        assertEquals(270, host.remaining(42))
        host.acceptRead(host.beginRead(), listOf(dto()))
        assertEquals(deadline, host.row(42)!!.deadline)
        assertEquals(270, host.remaining(42))
        host.acceptRead(host.beginRead(), listOf(dto(seconds = 0)))
        host.acceptRead(host.beginRead(), listOf(dto()))
        assertEquals(0, host.remaining(42))
        host.acceptRead(host.beginRead(), listOf(dto(terms = BoardingUndoTerms(onboardAt = next))))
        assertEquals(300, host.remaining(42))
    }

    @Test fun theSameProjectionDrivesCardsAndTheCancellationWarning() = runBlocking {
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { true })
        host.acceptRead(host.beginRead(), listOf(dto("confirmed", 0, BoardingUndoTerms())))
        assertEquals(0, onboardPassengers(9, host.effectiveRows))
        host.acceptRead(host.beginRead(), listOf(dto()))
        assertEquals(1, onboardPassengers(9, host.effectiveRows))
        host.acceptRead(host.beginRead(), listOf(dto("confirmed", 0,
            BoardingUndoTerms(confirmedId = "answer", sourceOnboardAt = first))))
        assertEquals(0, onboardPassengers(9, host.effectiveRows))
    }

    @Test fun terminalAndNewOwnerRejectLaterReadsAndActions() = runBlocking {
        var owned = true
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { owned })
        host.acceptRead(host.beginRead(), listOf(dto()))
        host.acceptRead(host.beginRead(), listOf(dto("done", 0)))
        host.acceptRead(host.beginRead(), listOf(dto(terms = BoardingUndoTerms(onboardAt = next))))
        assertEquals("done", host.row(42)!!.status)
        owned = false
        assertFalse(host.acceptRead(host.beginRead(), listOf(dto())))
        assertFalse(host.mark(42) {})
        assertFalse(host.noShow(42, {}, {}))
        assertFalse(host.undo(42) {})
        assertFalse(host.arriving(42, {}, {}))
    }

    @Test fun cancelledReloadCannotPublishEvenWhenItsReaderReturnsAPayload() = runBlocking {
        val response = CompletableDeferred<List<DriverBookingDto>>()
        val host = DriverCabinetBoardingState(1, this, now = { 1000 }, currentOwner = { true },
            readRows = { withContext(NonCancellable) { Result.success(response.await()) } })
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { host.reload() }
        reader.cancel()
        response.complete(listOf(dto()))
        reader.join()
        assertTrue(host.effectiveRows.isEmpty())
    }
}
