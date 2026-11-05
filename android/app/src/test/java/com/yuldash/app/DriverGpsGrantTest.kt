package com.yuldash.app

import org.junit.Assert.*
import org.junit.Test

class DriverGpsGrantTest {
    private val owner = DriverGpsOwner("https://fixture", 1, 7, "process-A")
    private fun active(g: DriverGpsGrant): DriverGpsTicket {
        val t = g.begin(owner, 42, 100, 1000)!!
        assertTrue(g.complete(t, owner, 8, 42, Long.MAX_VALUE, 110, 1010))
        return t
    }
    @Test fun coldPermissionAndReplayNeverCreatePermission() {
        val g = DriverGpsGrant()
        g.permission(true)
        assertNull(g.current(owner, 100, 1000))
        val old = active(g)
        assertFalse(DriverGpsGrant().complete(old, owner, 8, 42, Long.MAX_VALUE, 120, 1020))
        assertFalse(g.complete(old, owner, 8, 42, Long.MAX_VALUE, 120, 1020))
    }
    @Test fun deadlineStartsBeforeNetworkAndLateSuccessCannotActivate() {
        val g = DriverGpsGrant()
        val t = g.begin(owner, 42, 100, 1000)!!
        assertEquals(100 + DRIVER_GPS_MAX_MS, t.untilElapsed)
        assertFalse(g.complete(t, owner, 8, 42, Long.MAX_VALUE, t.untilElapsed, 1010))
        assertNull(g.current(owner, 100, 1000))
    }
    @Test fun repeatedLiveActionsAndCarrierChangesKeepOriginalDeadline() {
        val g = DriverGpsGrant(); val first = active(g)
        val next = g.begin(owner, 43, 200, 1100)!!
        assertEquals(first.id, next.id); assertEquals(first.untilElapsed, next.untilElapsed)
        assertTrue(g.complete(next, owner, 8, 43, Long.MAX_VALUE, 210, 1110))
        assertTrue(g.transfer(owner, 8, 44, Long.MAX_VALUE, 300, 1200))
        assertEquals(first.untilWall, g.current(owner, 300, 1200)!!.ticket.untilWall)
        assertFalse(g.canPublish(owner, 42, true, 300, 1200))
        assertTrue(g.canPublish(owner, 44, true, 300, 1200))
    }
    @Test fun initialPermissionDiffersFromRevokedThenRegrantedPermission() {
        val g = DriverGpsGrant(); val first = active(g)
        assertFalse(g.canPublish(owner, 42, false, 120, 1020))
        assertTrue(g.canPublish(owner, 42, true, 130, 1030))
        g.permission(false); g.permission(true)
        assertFalse(g.canPublish(owner, 42, true, 140, 1040))
        assertFalse(g.complete(first, owner, 8, 42, Long.MAX_VALUE, 140, 1040))
        val fresh = g.begin(owner, 42, 150, 1050)!!
        assertNotEquals(first.id, fresh.id)
        assertTrue(g.complete(fresh, owner, 8, 42, Long.MAX_VALUE, 160, 1060))
    }
    @Test fun ownerOriginSessionProcessChangesInvalidateTheGrant() {
        for (other in listOf(owner.copy(user = 2), owner.copy(origin = "https://other"), owner.copy(session = 8), owner.copy(process = "process-B"))) {
            val g = DriverGpsGrant(); active(g)
            assertNull(g.current(other, 120, 1020))
            assertNull(g.current(owner, 120, 1020))
        }
    }
    @Test fun eitherClockExpiresPermanentlyAndBackwardClockCannotRevive() {
        for (wallExpires in listOf(true, false)) {
            val g = DriverGpsGrant(); val t = active(g)
            assertNull(g.current(owner, if (wallExpires) 120 else t.untilElapsed, if (wallExpires) t.untilWall else 1020))
            assertNull(g.current(owner, 100, 1000))
        }
    }
    @Test fun staleTicketAndFailureCannotActivateAndExistingLeaseIsNotRenewed() {
        val g = DriverGpsGrant()
        val old = g.begin(owner, 42, 100, 1000)!!
        val newer = g.begin(owner, 42, 200, 1100)!!
        assertFalse(g.complete(old, owner, 8, 42, Long.MAX_VALUE, 210, 1110))
        g.fail(newer)
        assertFalse(g.complete(newer, owner, 8, 42, Long.MAX_VALUE, 210, 1110))
        val t = active(g)
        assertTrue(g.transfer(owner, 8, 42, 500, 200, 1100))
        assertTrue(g.transfer(owner, 8, 42, 800, 300, 1200))
        assertEquals(500L, g.current(owner, 300, 1200)!!.carrierUntil)
        assertEquals(t.untilElapsed, g.current(owner, 300, 1200)!!.ticket.untilElapsed)
    }
}
