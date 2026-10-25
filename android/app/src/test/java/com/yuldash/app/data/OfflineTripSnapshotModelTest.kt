package com.yuldash.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OfflineTripSnapshotModelTest {
    private val a = "2026-10-24T12:00:00.000000"
    private val b = "2026-10-24T12:00:00.000001"
    private fun state(status: String, at: String? = null, receipt: String? = null) =
        SnapshotState("passenger", status, onboardAt = at, receiptSource = receipt, receiptAccepted = receipt != null)

    @Test fun receiptBeforePayloadPreventsOldBoardingAndAllowsNewerBoarding() {
        val corrected = SnapshotEpochState.first(state("confirmed", receipt = a))!!
        assertNull(corrected.merge(state("onboard", a)))
        val next = corrected.merge(state("onboard", b))!!
        assertNull(next.merge(state("confirmed", receipt = a)))
        assertNull(next.merge(state("onboard", a)))
        assertEquals("confirmed", next.merge(state("confirmed", receipt = b))!!.status)
    }
    @Test fun ordinaryConfirmedOrMissingEpochCannotEraseKnownBoarding() {
        val onboard = SnapshotEpochState.first(state("onboard", a))!!
        assertNull(onboard.merge(state("confirmed")))
        assertNull(onboard.merge(state("onboard")))
        assertNull(onboard.merge(state("onboard", "bad")))
        assertNull(onboard.merge(state("onboard", b).copy(role = "driver")))
    }
    @Test fun terminalCannotBeRevived() {
        for (end in listOf("done", "cancelled")) assertNull(SnapshotEpochState("driver", end).merge(SnapshotState("driver", "onboard", onboardAt = b)))
    }
    @Test fun timeExpiresExactlyAtBoundaryAndDoesNotSurviveAnotherBoot() {
        val stamp = SnapshotClock(4, 1000, 500, 500)
        val ttl = OfflineTripSnapshotPolicy.MAX_AGE_MS
        assertEquals(1L, stamp.remaining(SnapshotClock(4, 1000 + ttl - 1, 500 + ttl - 1, 500 + ttl - 1)))
        assertNull(stamp.remaining(SnapshotClock(4, 1000 + ttl, 500 + ttl, 500 + ttl)))
        for (elapsed in listOf(0L, 9999L)) assertNull(stamp.remaining(SnapshotClock(5, 2000, elapsed, elapsed)))
        assertNull(stamp.remaining(SnapshotClock(-1, 2000, 1500, 1500)))
    }
    @Test fun negativeOrOverflowClockFailsClosedAndWallRollbackCannotExtendMonotonicLimit() {
        val stamp = SnapshotClock(4, 1000, 500, 500)
        assertNull(stamp.remaining(SnapshotClock(4, 999, 1500, 1500)))
        assertNull(stamp.remaining(SnapshotClock(4, 2000, 499, 499)))
        assertNull(stamp.remaining(SnapshotClock(4, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)))
        assertNull(stamp.remaining(SnapshotClock(4, 1001, 500 + OfflineTripSnapshotPolicy.MAX_AGE_MS, 500 + OfflineTripSnapshotPolicy.MAX_AGE_MS)))
    }
    @Test fun onlyOriginalWireAndActualOriginal5xxPermitFallback() {
        val wire = SnapshotReadTrace(); wire.attempt(); wire.startWire(); wire.wireFailed()
        assertTrue(wire.allowsFallback)
        val authIo = SnapshotReadTrace(); authIo.attempt()
        assertFalse(authIo.allowsFallback)
        val server = SnapshotReadTrace(); server.attempt(); server.startWire(); server.status(503)
        assertTrue(server.allowsFallback)
        server.invalid(); assertFalse(server.allowsFallback)
    }
    @Test fun deniedCodeAndRefreshRemainDeniedAcrossNetworkRetries() {
        for (code in listOf(401, 403, 404, 410, 429)) {
            val trace = SnapshotReadTrace(); trace.attempt(); trace.startWire(); trace.status(code); trace.wireFailed()
            assertFalse(trace.allowsFallback)
            trace.attempt(); trace.startWire(); trace.wireFailed(); assertFalse(trace.allowsFallback)
            trace.attempt(); trace.startWire(); trace.status(503); assertFalse(trace.allowsFallback)
        }
        val refresh = SnapshotReadTrace(); refresh.refresh(); refresh.attempt(); refresh.startWire(); refresh.status(503)
        assertFalse(refresh.allowsFallback)
    }
    @Test fun actualAccessDenialPersistsBeforeBodyFailureAndSurvivesRetriesButInitial401DoesNotClose() = runBlocking {
        for (code in listOf(403, 404, 410)) {
            var closes = 0
            val trace = SnapshotReadTrace { actual -> assertEquals(code, actual); closes++ }
            trace.attempt(); trace.startWire(); trace.status(401); trace.persistAccessDenial()
            assertEquals(0, closes)
            trace.refresh(); trace.attempt(); trace.startWire(); trace.status(code); trace.persistAccessDenial()
            assertEquals(1, closes)
            trace.wireFailed(); trace.attempt(); trace.startWire(); trace.status(503); trace.persistAccessDenial()
            assertEquals(1, closes); assertFalse(trace.allowsFallback)
            trace.invalid(); trace.persistAccessDenial(); assertEquals(1, closes)
        }
    }
}
