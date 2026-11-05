package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** Pure repository/gate failures on real temporary files; actual Android adapter/process tests remain required. */
class OfflineTripSnapshotAuthorityFaultTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SnapshotCoreFixture(SnapshotCoreDisk(temporary.newFolder().toPath()))
    private fun fail(disk: SnapshotCoreDisk, kind: String) {
        disk.failPayload = kind in setOf("payload", "both")
        disk.failBarrier = kind in setOf("barrier", "both")
        disk.failClear = kind == "clear"
    }

    @Test fun allFourDiskFailuresKeepClosureLateWritesColdLoadsAndOtherOwnersClosed() = runBlocking {
        for (kind in listOf("payload", "barrier", "both", "clear")) {
            val f = fixture(); assertEquals(SnapshotPersistence.COMMITTED, f.save())
            val late = f.read()
            fail(f.disk, kind)
            val expected = when (kind) {
                "payload" -> SnapshotPersistence.DURABLY_BLOCKED
                "both" -> SnapshotPersistence.NOT_DURABLE
                else -> SnapshotPersistence.COMMITTED // close never needs to clear its permanent barrier.
            }
            assertEquals(kind, expected, f.repo.close(f.repo.ticket()!!, 11))
            assertNull(kind, f.repo.load(f.owner, 11))
            assertEquals(kind, SnapshotPersistence.STALE, f.repo.observe(late.request.ticket, 11,
                SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = late.permit))
            f.disk.recoverWrites()
            val cold = SnapshotCoreFixture(f.disk, f.owner) // genuinely new authority instance and request process ID.
            assertNull(kind, cold.repo.load(cold.owner, 11))
            val oldGet = cold.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
            val terminal = cold.gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
            assertNull(cold.gate.acceptRead(cold.proof(terminal), SnapshotState("passenger", "done")))
            assertNull(cold.accept(oldGet, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
            assertNull(kind, cold.repo.load(cold.owner, 11))
            for (other in listOf(cold.owner.copy(userId = 8), cold.owner.copy(origin = "https://other.test"),
                cold.owner.copy(scope = UUID.randomUUID().toString(), generation = 2))) {
                cold.active.set(other)
                assertNull(kind, cold.repo.load(cold.owner, 11))
                assertFalse(cold.gate.allowsPrivate(other, 11, snapshotHash("OLD private projection")))
            }
        }
    }
    @Test fun totalFailureRetainsOldBytesButNeverClaimsAColdAuthorization() = runBlocking {
        val f = fixture(); f.save()
        f.disk.failBarrier = true; f.disk.failPayload = true
        assertEquals(SnapshotPersistence.NOT_DURABLE, f.repo.close(f.repo.ticket()!!, 11))
        f.disk.recoverWrites()
        // Reproduces the SOURCE R limitation: no disk accepted the closing fact.
        assertEquals("onboard", JSONObject(f.disk.read(f.owner.namespace)!!).getJSONObject("records").getJSONObject("11").getString("status"))
        assertNull(f.disk.barrier(f.owner.barrier("booking", 11)))
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertNull(cold.repo.load(cold.owner, 11))
        // Cold wire failure/503 cannot manufacture a fresh-200 proof for these surviving bytes.
        for (httpStatus in listOf(0, 503, 400)) {
            val read = cold.gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
            val trace = SnapshotReadTrace().apply {
                attempt(); startWire(true)
                if (httpStatus == 0) wireFailed() else status(httpStatus)
            }
            assertNull(trace.confirmed200(read, cold.owner))
            assertNull(cold.repo.load(cold.owner, 11))
        }
    }
    @Test fun failedCorrectionOrClearNeverRevealsOldOnboardAndNewerEpochCanRecover() = runBlocking {
        for (kind in listOf("payload", "both", "clear")) {
            val f = fixture(); f.save()
            val request = f.gate.beginMutation(11)!!
            val permit = f.gate.acknowledge(request, SnapshotCoreFixture.CORRECTION)!!
            fail(f.disk, kind)
            assertEquals(if (kind == "both") SnapshotPersistence.NOT_DURABLE else SnapshotPersistence.DURABLY_BLOCKED,
                f.repo.observe(request.ticket, 11, SnapshotCoreFixture.CORRECTION, acknowledgement = true, permit = permit))
            assertNull(f.repo.load(f.owner, 11))
            f.disk.recoverWrites()
            val sameProcessLate = f.read()
            assertNull(sameProcessLate.permit)
            val cold = SnapshotCoreFixture(f.disk, f.owner)
            assertNull(cold.repo.load(cold.owner, 11))
            val newer = SnapshotCoreFixture.ONBOARD.copy(onboardAt = SnapshotCoreFixture.B)
            assertEquals(SnapshotPersistence.COMMITTED, cold.save(newer))
            assertEquals(snapshotEpoch(SnapshotCoreFixture.B), snapshotEpoch(cold.repo.load(cold.owner, 11)!!.state.maxOnboard))
        }
    }
    @Test fun failedClearAllowsOnlyIdenticalCommittedCandidateRepairWithoutTtlRenewal() = runBlocking {
        val f = fixture()
        f.disk.failClear = true
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, f.save())
        assertNull(f.repo.load(f.owner, 11))
        f.disk.recoverWrites()
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        cold.now = cold.now.copy(wall = 2000, before = 2000, after = 2000)
        assertEquals(SnapshotPersistence.COMMITTED, cold.save())
        val saved = cold.repo.load(cold.owner, 11)!!
        assertEquals(1000L, saved.payloadStamp!!.wall)
        assertEquals(1000L, saved.stateStamp.wall)
    }
    @Test fun unavailableClockAndUnrepresentableAckCanPersistBlockWithoutGrantingColdAccess() = runBlocking {
        val f = fixture(); f.save()
        val mutation = f.gate.beginMutation(11)!!
        val permit = f.gate.acknowledge(mutation, SnapshotCoreFixture.CORRECTION)!!
        f.clockAvailable = false
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, f.repo.observe(mutation.ticket, 11,
            SnapshotCoreFixture.CORRECTION, acknowledgement = true, permit = permit))
        assertNull(SnapshotCoreFixture(f.disk, f.owner).repo.load(f.owner, 11))
        val unknown = fixture(); unknown.save()
        val ack = unknown.gate.beginMutation(11)!!
        assertNull(unknown.gate.acknowledge(ack, SnapshotState("passenger", "confirmed")))
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, unknown.repo.block(ack.ticket, 11))
        assertNull(unknown.repo.load(unknown.owner, 11))
    }
    @Test fun wholeRideClosedBeforeFirstDetailsRejectsFirstLateBookingAndNewRequests() = runBlocking {
        val f = fixture()
        val late = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.close(f.repo.ticket()!!, 22, ride = true))
        assertNull(f.accept(late, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))
        assertEquals(SnapshotPersistence.STALE, f.save())
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertNull(cold.repo.load(cold.owner, 11))
        assertEquals(SnapshotPersistence.STALE, cold.save())
    }
    @Test fun durableTerminalDiscoveredByRepositoryAlsoClosesTheSharedPrivatePassportGate() = runBlocking {
        val f = fixture(); f.save()
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.close(f.repo.ticket()!!, 22, ride = true))
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val request = cold.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        val fingerprint = snapshotHash("current synthetic private projection")
        val permit = cold.gate.acceptRead(cold.proof(request), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint)!!
        assertEquals(SnapshotPersistence.STALE, cold.repo.observe(request.ticket, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, permit = permit))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 11, fingerprint))
        assertNull(cold.repo.load(cold.owner, 11))
    }
    @Test fun discoveredRideTerminalRevokesEveryBookingEvenWhenItsDiskRecordsWereRemoved() = runBlocking {
        val f = fixture(); f.save(); f.repo.close(f.repo.ticket()!!, 22, ride = true)
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val fingerprint = snapshotHash("current synthetic private projection")
        for (booking in listOf(11, 12)) {
            val read = cold.gate.beginRead(booking, SnapshotAuthorityKind.DETAILS)!!
            assertNotNull(cold.gate.acceptRead(cold.proof(read), SnapshotCoreFixture.ONBOARD,
                22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint))
            assertTrue(cold.gate.allowsPrivate(cold.owner, booking, fingerprint))
        }
        val pendingHistory = cold.gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        assertNull(cold.repo.load(cold.owner, 11))
        for (booking in listOf(11, 12)) assertFalse(cold.gate.allowsPrivate(cold.owner, booking, fingerprint))
        // Learning terminal closes LIVE, not a fresh authenticated history response already in flight.
        assertNotNull(cold.gate.acceptHistory(cold.proof(pendingHistory), fingerprint))
        assertTrue(cold.gate.allowsHistory(cold.owner, 11, fingerprint))
        assertNull(cold.repo.load(cold.owner, 11))
        assertTrue(cold.gate.allowsHistory(cold.owner, 11, fingerprint))
    }
    @Test fun scopeRetirementDiscoveredOnReadRevokesAllLivePrivateAndHistoryRights() = runBlocking {
        val f = fixture(); f.save()
        assertTrue(f.disk.setBarrier(f.owner.barrier("scope", 0), "retired"))
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val fingerprint = snapshotHash("current synthetic projection")
        for (booking in listOf(11, 12)) {
            val read = cold.gate.beginRead(booking, SnapshotAuthorityKind.DETAILS)!!
            cold.gate.acceptRead(cold.proof(read), SnapshotCoreFixture.ONBOARD,
                22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint)
        }
        val history = cold.gate.beginRead(13, SnapshotAuthorityKind.HISTORY)!!
        assertNotNull(cold.gate.acceptHistory(cold.proof(history), fingerprint))
        assertNull(cold.repo.load(cold.owner, 11))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 11, fingerprint))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 12, fingerprint))
        assertFalse(cold.gate.allowsHistory(cold.owner, 13, fingerprint))
        assertNull(cold.gate.ticket())
    }
    @Test fun foreignCorruptedHeaderOrWrongGenerationCannotRevokeCurrentOwnersFreshProjection() = runBlocking {
        val f = fixture(); f.save()
        val request = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        val fingerprint = snapshotHash("current own private projection")
        f.gate.acceptRead(f.proof(request), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = fingerprint)
        assertFalse(f.gate.durableTerminals(f.owner.copy(generation = 2), rides = setOf(22)))
        assertTrue(f.gate.allowsPrivate(f.owner, 11, fingerprint))
        val foreign = JSONObject(f.disk.read(f.owner.namespace)!!).put("origin", "https://other.test")
        foreign.getJSONArray("closed_rides").put(22)
        f.disk.write(f.owner.namespace, foreign.toString())
        assertNull(f.repo.load(f.owner, 11))
        assertTrue(f.gate.allowsPrivate(f.owner, 11, fingerprint))
    }
}
