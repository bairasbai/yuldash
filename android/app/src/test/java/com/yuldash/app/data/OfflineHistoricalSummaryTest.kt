package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/** Historical display is not fresh trip authority. Reuses the existing real-file repository fixture. */
class OfflineHistoricalSummaryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SnapshotCoreFixture(SnapshotCoreDisk(temporary.newFolder().toPath()))

    @Test fun processReopenReturnsOnlyHistoricalFactsWithoutGrantingLiveOrPrivateAccess() = runBlocking {
        val f = fixture()
        assertEquals(SnapshotPersistence.COMMITTED, f.save())
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val saved = cold.repo.historical(cold.owner).single()
        assertEquals(11, saved.bookingId)
        assertEquals(SnapshotCoreFixture.ROUTE, saved.route)
        assertEquals("onboard", saved.state.status) // historical, never the current UI status
        assertNull(cold.repo.load(cold.owner, 11))
        assertFalse(cold.gate.allowsSnapshot(cold.owner, 11, saved.state, saved.rideId, saved.route))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 11, snapshotHash("private")))
        assertFalse(cold.gate.allowsHistorical(cold.owner, 11, 22, "forged"))
    }

    @Test fun otherAccountOriginScopeAndRetiredScopeCannotReadHistoricalFacts() = runBlocking {
        val f = fixture(); f.save()
        for (other in listOf(f.owner.copy(userId = 8), f.owner.copy(origin = "https://other.test"),
            f.owner.copy(scope = UUID.randomUUID().toString()))) {
            val cold = SnapshotCoreFixture(f.disk, other)
            assertTrue(cold.repo.historical(other).isEmpty())
            assertTrue(cold.repo.historical(f.owner).isEmpty())
        }
        val retired = SnapshotCoreFixture(f.disk, f.owner)
        retired.gate.retire(retired.owner)
        assertTrue(retired.repo.historical(retired.owner).isEmpty())
    }

    @Test fun expirationClockRollbackDifferentBootAndUnknownClockStayClosed() = runBlocking {
        val f = fixture(); f.save()
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        cold.now = SnapshotClock(3, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS,
            1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS)
        assertTrue(cold.repo.historical(cold.owner).isEmpty())
        cold.now = SnapshotClock(3, 1000, 1000, 1000)
        assertTrue("Expiration is latched before a rollback", cold.repo.historical(cold.owner).isEmpty())
        val rebooted = SnapshotCoreFixture(f.disk, f.owner)
        rebooted.now = SnapshotClock(4, 1000, 1000, 1000)
        assertTrue(rebooted.repo.historical(rebooted.owner).isEmpty())
        val unknown = SnapshotCoreFixture(f.disk, f.owner)
        unknown.clockAvailable = false
        assertTrue(unknown.repo.historical(unknown.owner).isEmpty())
    }

    @Test fun committedTerminalAndPendingWriteBarrierSuppressHistoricalRows() = runBlocking {
        val f = fixture(); f.save()
        assertTrue(f.disk.setBarrier(f.owner.barrier("booking", 11), "blocked"))
        assertTrue(SnapshotCoreFixture(f.disk, f.owner).repo.historical(f.owner).isEmpty())
        assertTrue(f.disk.setBarrier(f.owner.barrier("booking", 11), null))
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.close(f.repo.ticket()!!, 11))
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertTrue(cold.repo.historical(cold.owner).isEmpty())
        assertNull(cold.repo.load(cold.owner, 11))
    }

    @Test fun totalWriteFailureCanLeaveHistoryButNeverResurrectsAnActiveTrip() = runBlocking {
        val f = fixture(); f.save()
        f.disk.failPayload = true; f.disk.failBarrier = true
        assertEquals(SnapshotPersistence.NOT_DURABLE, f.repo.close(f.repo.ticket()!!, 11))
        assertTrue("Known terminal is hidden in its process", f.repo.historical(f.owner).isEmpty())
        f.disk.recoverWrites()
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val historical = cold.repo.historical(cold.owner).single()
        assertEquals("onboard", historical.state.status) // only a past saved fact; current status remains unknown
        assertNull(cold.repo.load(cold.owner, 11))
        assertFalse(cold.gate.allowsSnapshot(cold.owner, 11, historical.state, 22, historical.route))
        assertFalse(cold.gate.allowsPrivate(cold.owner, 11, snapshotHash("private")))
    }

    @Test fun liveDenialAndOwnerChangeRevokeAnAlreadyDisplayedHistoricalCandidate() = runBlocking {
        val f = fixture(); f.save()
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        val historical = cold.repo.historical(cold.owner).single()
        assertTrue(cold.gate.allowsHistorical(cold.owner, 11, 22, historical.candidate))
        val request = cold.gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        cold.gate.denied(request, 403)
        assertFalse(cold.gate.allowsHistorical(cold.owner, 11, 22, historical.candidate))
        assertTrue(cold.repo.historical(cold.owner).isEmpty())
        cold.active.set(cold.owner.copy(userId = 8))
        assertFalse(cold.gate.allowsHistorical(cold.owner, 11, 22, historical.candidate))
    }
}