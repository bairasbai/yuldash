package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiPresenceHealthTest {
    @Test fun successfulHttpWithoutPublishedPresenceWarnsAndARealHeartbeatRecovers() {
        val health = TaxiPresenceHealth()
        assertFalse(health.record(Result.success(false)))
        assertFalse(health.record(Result.success(false)))
        assertFalse(health.lost)
        assertTrue(health.record(Result.success(false)))
        assertTrue(health.lost)
        assertFalse(health.record(Result.success(false)))
        assertTrue(health.record(Result.success(true)))
        assertFalse(health.lost)
    }

    @Test fun rejectedPositionsAndNetworkFailuresCountTowardTheSameThreshold() {
        val health = TaxiPresenceHealth()
        assertFalse(health.record(Result.success(false)))
        assertFalse(health.record(Result.failure(IllegalStateException())))
        assertTrue(health.record(Result.success(false)))
        assertTrue(health.lost)
    }
}
