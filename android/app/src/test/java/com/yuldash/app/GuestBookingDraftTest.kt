package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestBookingDraftTest {
    private val ride = Ride(
        id = "1", from = "Уфа", to = "Мелеуз", time = "", driver = "", car = "",
        price = 400, seats = 3, rating = 5.0, verified = true, boosted = false,
        waypoints = listOf("Стерлитамак", "Салават"), frontSeatAvailable = true,
        termsVersion = 3,
    )
    private val draft = GuestBookingDraft(
        1, 3, routePlaces(ride), "Стерлитамак", "Салават", 2, front = true, minor = true,
    )

    @Test fun keepsPublicChoicesWhenRideIsUnchanged() {
        val restored = draft.restore(ride)
        assertEquals("Стерлитамак", restored.pickup)
        assertEquals("Салават", restored.dropoff)
        assertEquals(2, restored.seats)
        assertTrue(restored.front)
        assertTrue(restored.minor)
        assertNull(restored.review)
    }

    @Test fun warnsBeforeUsingChangedRouteEvenIfBothSelectedStopsRemain() {
        val fresh = ride.copy(waypoints = listOf("Стерлитамак", "Ишимбай", "Салават"))
        val restored = draft.restore(fresh)
        assertEquals("Стерлитамак", restored.pickup)
        assertEquals("Салават", restored.dropoff)
        assertTrue(restored.review?.routeChanged == true)
    }

    @Test fun restoresTravelerChoiceForTheLoginReturn() {
        assertTrue(draft.copy(forAnother = true).restore(ride).forAnother)
        assertFalse(draft.restore(ride).forAnother)
    }

    @Test fun asksForReviewOfRemovedStopsAndNewRestrictions() {
        val fresh = ride.copy(waypoints = emptyList(), frontSeatAvailable = false, noMinors = true, termsVersion = 4)
        val restored = draft.restore(fresh)
        assertEquals("Уфа", restored.pickup)
        assertEquals("Мелеуз", restored.dropoff)
        assertFalse(restored.front)
        assertFalse(restored.minor)
        assertEquals(GuestBookingReview(true, true, true, true), restored.review)
    }

    @Test fun recognizesFrontSeatOccupiedAndKeepsSeatCountForExplicitReduction() {
        val restored = draft.restore(ride.copy(frontSeatTaken = true, seats = 1))
        assertFalse(restored.front)
        assertEquals(2, restored.seats)
        assertNotNull(restored.review)
        assertTrue(restored.review?.frontUnavailable == true)
    }
}
