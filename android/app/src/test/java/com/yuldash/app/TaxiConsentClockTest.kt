package com.yuldash.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaxiConsentClockTest {
    @Test fun reducedMotionLeavesOneSecondBetweenChecksAndCancellationStopsThem() = runTest {
        val original = CanonMotion.enabled
        CanonMotion.enabled = false
        try {
            val checks = mutableListOf<Long>()
            val clock = launch {
                pollTaxiConsentClock {
                    // Fail immediately on delay(0), before a busy loop can hang the test worker.
                    assertTrue("Clock did not suspend between checks", checks.lastOrNull() != currentTime)
                    checks.add(currentTime)
                }
            }
            runCurrent()
            assertEquals(listOf(0L), checks)
            advanceTimeBy(999L)
            runCurrent()
            assertEquals(listOf(0L), checks)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf(0L, 1_000L), checks)
            clock.cancelAndJoin()
            advanceTimeBy(2_000L)
            runCurrent()
            assertEquals(listOf(0L, 1_000L), checks)
        } finally {
            CanonMotion.enabled = original
        }
    }
}
