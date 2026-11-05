package com.yuldash.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SnapshotAuthorityGateTest {
    private val owner = SnapshotOwner(7, UUID.randomUUID().toString(), 1, "https://example.test")
    private val active = AtomicReference<SnapshotOwner?>(owner)
    private val gate = SnapshotAuthorityGate(active)
    private val confirmed = SnapshotState("passenger", "confirmed")
    private val state = SnapshotEpochState.first(confirmed)!!
    private val fingerprint = snapshotHash("CURRENT synthetic private projection")
    private fun proof(request: SnapshotReadTicket) = SnapshotReadTrace().apply {
        attempt(); startWire(authorized = true); status(200)
    }.confirmed200(request, owner)!!

    @Test fun newlyConstructedProcessGateIsDeniedDespiteSameDurableOwner() {
        val request = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        assertNotNull(gate.acceptRead(proof(request), confirmed))
        assertTrue(gate.allowsSnapshot(owner, 11, state, null))
        val cold = SnapshotAuthorityGate(active)
        assertFalse(cold.allowsSnapshot(owner, 11, state, null))
        assertFalse(cold.allowsPrivate(owner, 11, fingerprint))
        assertNull(cold.acceptRead(proof(request), confirmed))
    }
    @Test fun freshRoleNeverUnlocksAnOldPrivateBlobWhenDetailsFails() {
        val request = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        gate.acceptRead(proof(request), confirmed)
        for (httpStatus in listOf(400, 503)) {
            val details = gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
            val trace = SnapshotReadTrace().apply { attempt(); startWire(true); status(httpStatus) }
            assertNull(trace.confirmed200(details, owner))
            assertFalse(gate.allowsPrivate(owner, 11, snapshotHash("OLD private passport")))
            assertFalse(gate.allowsPrivate(owner, 11, fingerprint))
            assertEquals(httpStatus == 503, trace.allowsFallback)
        }
    }
    @Test fun detailsCapabilityMatchesTheActualCurrentProjectionOnlyAndDoesNotCrossBooking() {
        val request = gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        gate.acceptRead(proof(request), confirmed, rideId = 22, contactUnlocked = true, privateFingerprint = fingerprint)
        assertTrue(gate.allowsPrivate(owner, 11, fingerprint))
        assertFalse(gate.allowsPrivate(owner, 11, snapshotHash("OLD private passport")))
        assertFalse(gate.allowsPrivate(owner, 12, fingerprint))
        assertFalse(gate.allowsPrivate(owner.copy(origin = "https://other.test"), 11, fingerprint))
        val newer = gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        gate.acceptRead(proof(newer), confirmed, rideId = 22, contactUnlocked = false)
        assertFalse(gate.allowsPrivate(owner, 11, fingerprint))
    }
    @Test fun postAloneCannotGrantColdLiveAndReceiptCannotGrantActiveOrPrivate() {
        val mutation = gate.beginMutation(11)!!
        val ack = gate.acknowledge(mutation, confirmed)
        assertNotNull(ack)
        assertFalse(gate.allowsSnapshot(owner, 11, state, null))
        val late = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        val receipt = gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        assertNotNull(gate.acceptHistory(proof(receipt), fingerprint))
        assertTrue(gate.allowsHistory(owner, 11, fingerprint))
        assertFalse(gate.allowsSnapshot(owner, 11, state, null))
        assertFalse(gate.allowsPrivate(owner, 11, fingerprint))
        assertNull(gate.acceptRead(proof(late), confirmed))
        val again = gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        assertNotNull(gate.acceptHistory(proof(again), fingerprint))
    }
    @Test fun acceptedCorrectionInvalidatesOldReadAndPreservesNewerBoardingEpoch() {
        val a = "2026-10-24T12:00:00.000000"
        val b = "2026-10-24T12:00:00.000001"
        val onboard = SnapshotState("passenger", "onboard", onboardAt = a)
        val first = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        gate.acceptRead(proof(first), onboard)
        val late = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        val mutation = gate.beginMutation(11)!!
        val corrected = gate.acknowledge(mutation, confirmed.copy(receiptSource = a, receiptAccepted = true))!!
        assertTrue(gate.allowsSnapshot(owner, 11, corrected.state!!, null))
        assertNull(gate.acceptRead(proof(late), onboard))
        val same = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        assertNull(gate.acceptRead(proof(same), onboard))
        val next = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        assertNotNull(gate.acceptRead(proof(next), onboard.copy(onboardAt = b)))
        assertFalse(gate.allowsSnapshot(owner, 11, corrected.state!!, null))
    }
    @Test fun authorityNeedsActualAuthorized200AndCurrentTransportOwnerAfterRefresh() {
        val request = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        val trace = SnapshotReadTrace().apply { attempt(); startWire(); status(200) }
        assertNull(trace.confirmed200(request, owner))
        trace.attempt(); trace.startWire(true); trace.status(401); trace.refresh()
        trace.attempt(); trace.startWire(true); trace.status(200)
        assertFalse(trace.allowsFallback)
        assertNotNull(gate.acceptRead(trace.confirmed200(request, owner)!!, confirmed))
        active.set(owner.copy(generation = 2))
        assertNull(gate.acceptRead(trace.confirmed200(request, owner)!!, confirmed))
        val next = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        assertNull(trace.confirmed200(next, owner))
        trace.invalid(); assertNull(trace.confirmed200(next, active.get()!!))
    }
    @Test fun terminalAndActualDenialCannotBeReopenedByOldOrNewReadInSameProcess() {
        val first = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        gate.acceptRead(proof(first), confirmed)
        val late = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        gate.denied(late, 403)
        assertFalse(gate.allowsSnapshot(owner, 11, state, null))
        assertNull(gate.acceptRead(proof(late), confirmed))
        val fresh = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        assertNull(gate.acceptRead(proof(fresh), confirmed))
    }
}
