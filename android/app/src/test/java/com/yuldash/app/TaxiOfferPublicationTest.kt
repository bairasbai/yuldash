package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TaxiOfferPublicationTest {
    private val publication = TaxiOfferPublication()
    private val a = TaxiOfferPublication.Offer(12, "2026-11-27T07:05:20.123456")
    private val b = TaxiOfferPublication.Offer(13, "2026-11-27T07:05:25.123456")
    private val posted = mutableListOf<TaxiOfferPublication.Offer>()

    private fun post(ticket: TaxiOfferPublication.Ticket, offer: TaxiOfferPublication.Offer) {
        publication.observe(ticket, offer) { posted.add(offer) }
    }

    @Test fun latePushConfirmationDoesNotReplaceTheNewerPolledOffer() {
        val awaitingServer = publication.snapshot(1)
        post(publication.snapshot(1), b)
        post(awaitingServer, a)
        assertEquals(listOf(b), posted)
    }

    @Test fun aNewGenerationOfTheSameOrderCanStillBePublished() {
        post(publication.snapshot(1), a)
        val newer = a.copy(deadline = b.deadline)
        post(publication.snapshot(1), newer)
        post(publication.snapshot(1), newer)
        assertEquals(listOf(a, newer), posted)
    }

    @Test fun oldResponseCannotPostAfterAnEmptyPollOrAChangedSession() {
        val awaitingServer = publication.snapshot(1)
        publication.observe(publication.snapshot(1), null)
        post(awaitingServer, a)
        val awaitingPreviousSession = publication.snapshot(1)
        post(publication.snapshot(2), b)
        post(awaitingPreviousSession, a)
        assertEquals(listOf(b), posted)
    }

    @Test fun acceptedEmptyClearsPreviouslyPostedOfferAndLateEmptyDoesNotClearNewOffer() {
        var displayed: TaxiOfferPublication.Offer? = null
        fun publish(offer: TaxiOfferPublication.Offer) {
            publication.observe(publication.snapshot(1), offer) {
                displayed = offer
                true
            }
        }
        fun clear(ticket: TaxiOfferPublication.Ticket) {
            publication.observe(ticket, null, clear = { displayed = null })
        }

        publish(a)
        clear(publication.snapshot(1))
        assertEquals(null, displayed)
        val awaitingEmpty = publication.snapshot(1)
        publish(b)
        clear(awaitingEmpty)
        assertEquals(b, displayed)
    }

    @Test fun unchangedPollCanRecoverIfPublicationWasNotPermitted() {
        publication.observe(publication.snapshot(1), a) { false }
        post(publication.snapshot(1), a)
        assertEquals(listOf(a), posted)
    }
}
