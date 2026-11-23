package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxiOnboardFixFreshnessTest {
    private val scope = TaxiOnboardFixScope(70, 5L, 6L, 7L, 100L)
    private val fix = TaxiOnboardFixTimes(scope, 104L, 107L, 8f)
    private val policy = TaxiOnboardFixPolicy(10L, 8f)

    @Test
    fun ageComesFromOriginalFixNotFromSamplingOrSending() {
        assertEquals(8L, onboardFixAgeNanos(fix, scope, 112L, policy))
        assertEquals(10L, onboardFixAgeNanos(fix, scope, 114L, policy))
        assertNull(onboardFixAgeNanos(fix, scope, 115L, policy))
    }

    @Test
    fun reconnectCannotMakeTheOnlyBufferedFixFresh() {
        val next = scope.copy(reconnectGeneration = 8L, captureOpenedElapsedNanos = 109L)
        assertNull(onboardFixAgeNanos(fix, next, 110L, policy))

        // Even a callback sampled after reconnect cannot repurpose an original
        // OS location that predates the current capture window.
        val oldOriginally = fix.copy(scope = next, sampledElapsedNanos = 110L)
        assertNull(onboardFixAgeNanos(oldOriginally, next, 110L, policy))
        val newlyCaptured = oldOriginally.copy(originalFixElapsedNanos = 109L)
        assertEquals(1L, onboardFixAgeNanos(newlyCaptured, next, 110L, policy))
    }

    @Test
    fun identityMustStillMatchOrderSessionReconnectAssignmentAndCaptureWindow() {
        listOf(
            scope.copy(orderId = 71),
            scope.copy(sessionGeneration = 6L),
            scope.copy(reconnectGeneration = 8L),
            scope.copy(assignmentGeneration = 9L),
            scope.copy(captureOpenedElapsedNanos = 101L),
        ).forEach { changed ->
            assertNull(onboardFixAgeNanos(fix, changed, 110L, policy))
        }
    }

    @Test
    fun absentMalformedOrReversedSourceClocksDoNotQualify() {
        listOf(
            null,
            fix.copy(originalFixElapsedNanos = null),
            fix.copy(sampledElapsedNanos = null),
            fix.copy(originalFixElapsedNanos = 0L),
            fix.copy(originalFixElapsedNanos = 109L),
            fix.copy(sampledElapsedNanos = 111L),
        ).forEach { bad ->
            assertNull(onboardFixAgeNanos(bad, scope, 110L, policy))
        }
        assertNull(onboardFixAgeNanos(fix, scope, 1L, policy))
    }

    @Test
    fun onlyKnownFiniteAccuracyWithinTheCallerPolicyQualifies() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f, 8.1f).forEach { accuracy ->
            assertNull(onboardFixAgeNanos(fix.copy(horizontalAccuracyMeters = accuracy), scope, 110L, policy))
        }
        assertEquals(6L, onboardFixAgeNanos(fix, scope, 110L, policy))
        assertNull(onboardFixAgeNanos(fix, scope, 110L, policy.copy(maxAgeNanos = -1L)))
        assertNull(onboardFixAgeNanos(fix, scope, 110L, policy.copy(maxHorizontalAccuracyMeters = Float.NaN)))
    }

    @Test
    fun extremeElapsedValuesDoNotWrapAgeIntoAnAcceptedTime() {
        val briefPolicy = policy.copy(maxAgeNanos = 1L)
        val lastTick = fix.copy(originalFixElapsedNanos = Long.MAX_VALUE - 1L, sampledElapsedNanos = Long.MAX_VALUE)
        assertEquals(1L, onboardFixAgeNanos(lastTick, scope, Long.MAX_VALUE, briefPolicy))
        assertNull(onboardFixAgeNanos(fix, scope, Long.MAX_VALUE, briefPolicy))
    }
}
