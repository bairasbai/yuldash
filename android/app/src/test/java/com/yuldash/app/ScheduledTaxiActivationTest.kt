package com.yuldash.app

import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaxiActivationTest {
    private val userId = 17
    private val order = ApiClient.parseTaxiConsentOrder(ConsentFixture.orderJson().put("passenger_id", userId))

    @Test fun scheduledReplyStaysInListAndOnlyConfirmedSearchOrExpiredOpensTheCard() {
        val cases = mapOf(
            "scheduled" to ScheduledActivation.WAITING,
            "created" to ScheduledActivation.OPEN,
            "searching" to ScheduledActivation.OPEN,
            "offered" to ScheduledActivation.OPEN,
            "accepted" to ScheduledActivation.OPEN,
            "arriving" to ScheduledActivation.OPEN,
            "onboard" to ScheduledActivation.OPEN,
            "expired" to ScheduledActivation.OPEN,
            "done" to ScheduledActivation.ENDED,
            "cancelled" to ScheduledActivation.ENDED,
            "unknown" to ScheduledActivation.INVALID,
        )
        for ((status, expected) in cases) {
            val response = order.copy(status = status)
            assertEquals(status, expected, scheduledActivation(response, order.id, userId))
            assertEquals(status, expected in setOf(ScheduledActivation.OPEN, ScheduledActivation.ENDED),
                matchesScheduledTaxiCard(response, order.id, userId))
        }
    }

    @Test fun mismatchedCardIsRejectedEvenWhenStatusLooksActive() {
        for (candidate in listOf(order.copy(id = order.id + 1), order.copy(role = "driver"),
                order.copy(passengerId = userId + 1))) {
            assertEquals(ScheduledActivation.INVALID, scheduledActivation(candidate, order.id, userId))
            assertFalse(matchesScheduledTaxiCard(candidate, order.id, userId))
        }
        assertFalse(matchesScheduledTaxiCard(order.copy(id = 0), 0, userId))
        assertTrue(matchesScheduledTaxiCard(order.copy(passengerId = null, status = "expired"), order.id, userId))
    }
}
