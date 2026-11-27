package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiLineRunTest {
    private val oldOffer = TaxiOfferPublication.Offer(12, "2026-11-27T07:05:20")
    private val newOffer = TaxiOfferPublication.Offer(13, "2026-11-27T07:05:25")

    @Test fun lateShutdownDoesNotCloseASecondStartInTheSameSession() {
        val line = TaxiLineRun()
        line.started(1, 17)
        val previous = checkNotNull(line.current)
        line.started(1, 18)
        val reopened = checkNotNull(line.current)
        assertFalse(line.close(previous))
        assertSame(reopened, line.current)
        assertTrue(line.close(reopened))
        assertNull(line.current)
    }

    @Test fun terminalCloseRemovesTheAlertAndFencesEarlierReadsEvenIfSlotWasEmpty() {
        val publication = TaxiOfferPublication()
        var displayed: TaxiOfferPublication.Offer? = null
        fun post(ticket: TaxiOfferPublication.Ticket, offer: TaxiOfferPublication.Offer) {
            publication.observe(ticket, offer) { displayed = offer; true }
        }
        post(publication.snapshot(1), oldOffer)
        val oldPoll = publication.snapshot(1)
        publication.invalidate(1) { displayed = null }
        assertNull(displayed)
        post(oldPoll, oldOffer)
        assertNull(displayed)
        val oldPush = publication.snapshot(1)
        publication.invalidate(1) { displayed = null }
        post(oldPush, oldOffer)
        assertNull(displayed)
        post(publication.snapshot(1), newOffer)
        assertEquals(newOffer, displayed)
    }

    @Test fun oldAccountTeardownCannotClearANewAccountsNotification() {
        val publication = TaxiOfferPublication()
        var display: TaxiOfferPublication.Offer? = null
        publication.observe(publication.snapshot(2), newOffer) { display = newOffer; true }
        publication.invalidate(1) { display = null }
        assertEquals(newOffer, display)
    }

    @Test fun exactActiveOwnerCanClearUntrackedAlertAfterProcessRestart() {
        var clears = 0
        val publication = TaxiOfferPublication()
        publication.invalidate(1) { clears++ }
        assertEquals(0, clears)
        publication.invalidate(1, allowUntracked = true) { clears++ }
        assertEquals(1, clears)
    }
}
