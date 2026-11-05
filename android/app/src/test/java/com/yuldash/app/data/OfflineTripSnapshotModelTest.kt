package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OfflineTripSnapshotModelTest {
    private val a = "2026-10-24T12:00:00.000000"
    private val b = "2026-10-24T12:00:00.000001"
    @Test fun receiptBeforePayloadRejectsOldBoardingAndAcceptsOnlyNewerMicrosecond() {
        val corrected = SnapshotEpochState.first(SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true))!!
        assertNull(corrected.merge(SnapshotState("passenger", "onboard", onboardAt = a)))
        val next = corrected.merge(SnapshotState("passenger", "onboard", onboardAt = b))!!
        assertEquals(snapshotEpoch(b), snapshotEpoch(next.maxOnboard))
        assertNull(next.merge(SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true)))
        assertNull(next.merge(SnapshotState("passenger", "confirmed")))
        assertNull(next.merge(SnapshotState("driver", "onboard", onboardAt = b)))
    }
    @Test fun invalidPhasesEpochsAndTerminalCannotBecomeSavedLive() {
        assertNull(SnapshotEpochState.first(SnapshotState("passenger", "onboard")))
        assertNull(SnapshotEpochState.first(SnapshotState("passenger", "onboard", onboardAt = "broken")))
        assertNull(SnapshotEpochState.first(SnapshotState("passenger", "confirmed", phase = "unknown")))
        assertNull(SnapshotEpochState("passenger", "done").merge(SnapshotState("passenger", "confirmed")))
    }
    @Test fun ttlUsesBothClocksAndRejectsRestartBootRollbackAndOverflow() {
        val start = SnapshotClock(3, 1000, 1000, 1000)
        assertEquals(OfflineTripSnapshotPolicy.MAX_AGE_MS - 1, start.remaining(SnapshotClock(3, 1001, 1001, 1001)))
        val deadline = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS
        assertNull(start.remaining(SnapshotClock(3, deadline, deadline, deadline)))
        assertNull(start.remaining(SnapshotClock(4, 1001, 1001, 1001)))
        assertNull(start.remaining(SnapshotClock(3, 999, 1001, 1001)))
        assertNull(SnapshotClock(3, Long.MAX_VALUE, 1, 1).remaining(SnapshotClock(3, 0, 2, 2)))
    }
    @Test fun ownerNamespacesSeparateOriginsUsersAndLoginScopesButNotProcessGeneration() {
        val own = SnapshotOwner(7, UUID.randomUUID().toString(), 1, "https://example.test")
        assertTrue(own.valid)
        assertEquals(own.namespace, own.copy(generation = 2).namespace)
        assertNotEquals(own.namespace, own.copy(origin = "https://other.test").namespace)
        assertNotEquals(own.namespace, own.copy(userId = 8).namespace)
        assertNotEquals(own.namespace, own.copy(scope = UUID.randomUUID().toString()).namespace)
        assertFalse(own.copy(origin = "https://user:secret@example.test").valid)
        assertFalse(own.copy(origin = "https://example.test/?token=x").valid)
        assertFalse(own.copy(scope = "broken").valid)
        assertEquals("https://example.test", snapshotOrigin("https://example.test:443/"))
    }
    @Test fun original401And403RemainNoFallbackEvenAfterBrokenBodyAndReal503() = runBlocking {
        for (status in listOf(401, 403, 404, 410)) {
            val denied = mutableListOf<Int>()
            val trace = SnapshotReadTrace { denied += it }
            trace.attempt(); trace.startWire(authorized = true); trace.status(status)
            trace.persistAccessDenial(); trace.wireFailed()
            trace.attempt(); trace.startWire(authorized = true); trace.status(503)
            assertFalse(trace.allowsFallback)
            assertEquals(if (status == 401) emptyList<Int>() else listOf(status), denied)
        }
    }
    @Test fun real5xxAndWireFailureAllowFallbackBut400BadContractAndRefreshDoNot() {
        val server = SnapshotReadTrace().apply { attempt(); startWire(true); status(503) }
        assertTrue(server.allowsFallback)
        val wire = SnapshotReadTrace().apply { attempt(); startWire(true); wireFailed() }
        assertTrue(wire.allowsFallback)
        assertFalse(SnapshotReadTrace().apply { attempt(); startWire(true); status(400) }.allowsFallback)
        server.invalid(); assertFalse(server.allowsFallback)
        wire.refresh(); assertFalse(wire.allowsFallback)
    }
}
