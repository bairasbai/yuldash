package com.yuldash.app.data

import org.junit.Test
import org.junit.Assert.*

/** Pure counters: never instantiate the actual shared fixture or create jobs/servers. */
class SnapshotWiringCleanupGuardTest {
    private fun rejected(action: () -> Unit) {
        var rejected = false
        try { action() } catch (failure: AssertionError) {
            assertEquals(SnapshotWiringCleanupGuard.POISON_MARKER, failure.message)
            rejected = true
        }
        assertTrue("Guard failed to reject unsafe mutation", rejected)
    }
    @Test fun failedDrainPermissionBlocksCleanupNextSetupAndLaterBypass() {
        val guard = SnapshotWiringCleanupGuard()
        var resets = 0; var shutdowns = 0; var setups = 0
        // false is exactly the permission left behind when the subclass drain throws/times out.
        rejected { guard.runCleanup(false) { resets++; shutdowns++ } }
        assertEquals(0, resets); assertEquals(0, shutdowns)
        rejected { guard.runSetup { setups++ } }
        rejected { guard.runCleanup(true) { resets++; shutdowns++ } }
        rejected { guard.requireHealthy() }
        assertEquals(0, setups); assertEquals(0, resets); assertEquals(0, shutdowns)
    }
    @Test fun healthyIndependentGuardInvokesSetupAndCleanupExactlyOnce() {
        val guard = SnapshotWiringCleanupGuard()
        var setups = 0; var resets = 0; var shutdowns = 0
        guard.runSetup { setups++ }
        guard.runCleanup(true) { resets++; shutdowns++ }
        guard.requireHealthy()
        assertEquals(1, setups); assertEquals(1, resets); assertEquals(1, shutdowns)
    }
}
