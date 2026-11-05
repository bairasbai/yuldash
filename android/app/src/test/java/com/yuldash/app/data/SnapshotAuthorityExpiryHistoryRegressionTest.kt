package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** New regression expectations against core02. Root owns the RED/GREEN executions and XML evidence. */
class SnapshotAuthorityExpiryHistoryRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SnapshotCoreFixture(SnapshotCoreDisk(temporary.newFolder().toPath()))

    @Test fun onceObservedExpiredCandidateCannotReviveAfterWallRollbackWithoutFreshServerRead() = runBlocking {
        val f = fixture()
        assertEquals(SnapshotPersistence.COMMITTED, f.save())
        val privateFingerprint = snapshotHash("current private projection before clock anomaly")
        val details = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        val permit = f.gate.acceptRead(f.proof(details), SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, contactUnlocked = true, privateFingerprint = privateFingerprint)!!
        assertEquals(SnapshotPersistence.UNCHANGED, f.repo.observe(details.ticket, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, permit = permit))
        val original = f.repo.load(f.owner, 11)!!
        assertTrue(f.gate.allowsPrivate(f.owner, 11, privateFingerprint))
        val late = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!

        // Only wall time crosses the deadline. The monotonic clock keeps increasing normally.
        f.now = SnapshotClock(3, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, 2000, 2000)
        assertNull("The candidate must actually be observed expired first", f.repo.load(f.owner, 11))
        f.now = SnapshotClock(3, 2000, 3000, 3000)
        assertNull("A clock rollback must not revive an already rejected candidate without a new GET",
            f.repo.load(f.owner, 11))
        assertFalse("Expiry must revoke the shared private-pass authority too",
            f.gate.allowsPrivate(f.owner, 11, privateFingerprint))
        assertFalse(f.gate.allowsSnapshot(f.owner, 11, original.state, 22, SnapshotCoreFixture.ROUTE))
        assertNull("A GET ticket captured before expiry cannot grant fresh authority after the latch",
            f.accept(late, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE))

        // Expiry is a soft denial, not a permanent terminal booking: a genuinely NEW
        // authorized details response can replace/revalidate the candidate and recover.
        val fresh = f.read()
        assertNotNull(fresh.permit)
        f.repo.observe(fresh.request.ticket, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, permit = fresh.permit)
        assertNotNull("Fresh current server details must be able to recover the live projection", f.repo.load(f.owner, 11))
        assertFalse("A new generic details read must not reopen the old private projection",
            f.gate.allowsPrivate(f.owner, 11, privateFingerprint))
    }

    @Test fun olderHistoryResponseCannotOverwriteNewerReceiptAndLaterInFlightReceiptStillSucceeds() {
        val f = fixture()
        val older = f.gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        val newer = f.gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        val later = f.gate.beginRead(11, SnapshotAuthorityKind.HISTORY)!!
        val a = snapshotHash("older current receipt projection A")
        val b = snapshotHash("newer current receipt projection B")
        val c = snapshotHash("later current receipt projection C")

        assertNotNull(f.gate.acceptHistory(f.proof(newer), b))
        assertTrue(f.gate.allowsHistory(f.owner, 11, b))
        assertNull("The delayed older HTTP 200 must not replace the newer receipt projection",
            f.gate.acceptHistory(f.proof(older), a))
        assertTrue("Rejecting A must preserve the accepted B projection", f.gate.allowsHistory(f.owner, 11, b))
        assertFalse(f.gate.allowsHistory(f.owner, 11, a))

        assertNotNull("Ordering must not invalidate the already in-flight newer request C",
            f.gate.acceptHistory(f.proof(later), c))
        assertTrue(f.gate.allowsHistory(f.owner, 11, c))
        assertFalse(f.gate.allowsHistory(f.owner, 11, b))
        assertFalse(f.gate.allowsPrivate(f.owner, 11, c))
        assertFalse(f.gate.allowsSnapshot(f.owner, 11,
            SnapshotEpochState.first(SnapshotState("passenger", "confirmed"))!!, null))
    }
}
