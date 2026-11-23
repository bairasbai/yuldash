package com.yuldash.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiDriverRecoveryJournalTest {
    private val owner = SnapshotOwner(
        41, "2dac726f-d4ed-4c8f-b611-418c240f644e", 10, "http://127.0.0.1:8000",
    )
    private val freshScope = "cd26bc81-04aa-4516-8dff-82cae0d5b508"
    private val handle = requireNotNull(TaxiDriverRecoveryHandle.parse("U3ludGhldGljT25seQ=="))

    private class Disk : TaxiDriverRecoveryStorage {
        var row: String? = null
        var commitReturned = true
        var readable = true
        var writes = true
        override fun read(): String? {
            check(readable)
            return row
        }
        override fun commit(value: String?): Boolean {
            if (writes) row = value
            return commitReturned
        }
    }

    private class Login(var active: SnapshotOwner?) : TaxiDriverRecoverySessionFence {
        var guarded = false
        override fun ifCurrent(owner: SnapshotOwner, action: () -> Unit) {
            if (active != owner) return
            check(!guarded)
            guarded = true
            try {
                action()
            } finally {
                guarded = false
            }
        }
    }

    @Test fun checkedWriteArmsOneExplicitAdmissionButRestartOnlyRestoresStatus() {
        val slot = Disk()
        val login = Login(owner)
        val journal = TaxiDriverRecoveryJournal(slot, login)
        val permit = requireNotNull(journal.prepare(owner, handle))
        assertEquals("1\n${owner.origin}\n${owner.namespace}\n${handle.value}", slot.row)
        assertFalse(slot.row.orEmpty().contains(owner.scope))
        assertFalse(handle.toString().contains(handle.value))
        var admissions = 0
        var admittedWithinFence = false
        assertTrue(journal.consumeSendPermit(permit) {
            admittedWithinFence = login.guarded
            admissions++
        })
        assertTrue(admittedWithinFence)
        assertEquals(1, admissions)
        assertFalse(journal.consumeSendPermit(permit) { admissions++ })
        assertEquals(1, admissions)
        assertNull(journal.prepare(owner, handle))

        val reopened = TaxiDriverRecoveryJournal(slot, login)
        val restoredOwner = owner.copy(generation = 0)
        login.active = restoredOwner
        assertNull(journal.restoreForStatus(owner))
        assertEquals(handle.value, reopened.restoreForStatus(restoredOwner)?.value)
        assertFalse(reopened.consumeSendPermit(permit) { admissions++ })
        assertNull(reopened.prepare(restoredOwner, handle))
        assertEquals(1, admissions)
    }

    @Test fun newSessionAccountEnvironmentAndStaleCallbacksNeverSeeOrClearOriginal() {
        val slot = Disk()
        val login = Login(owner)
        val journal = TaxiDriverRecoveryJournal(slot, login)
        val permit = requireNotNull(journal.prepare(owner, handle))
        val original = slot.row
        val sameAccountRelogin = owner.copy(scope = freshScope, generation = 11)
        listOf(
            sameAccountRelogin,
            owner.copy(userId = 42, generation = 11),
            owner.copy(origin = "https://example.invalid", generation = 11),
        ).forEach { now ->
            login.active = now
            assertNull(journal.restoreForStatus(now))
            assertNull(journal.prepare(now, handle))
            assertFalse(journal.clearCommitted(now, handle))
            assertFalse(journal.clearAtSessionBoundary(owner))
            var admissions = 0
            assertFalse(journal.consumeSendPermit(permit) { admissions++ })
            assertEquals(0, admissions)
            assertEquals(original, slot.row)
        }
        login.active = sameAccountRelogin
        assertTrue(journal.clearAtSessionBoundary(sameAccountRelogin))
        assertNull(slot.row)
        assertNotNull(journal.prepare(sameAccountRelogin, handle))
        assertTrue(journal.clearAtSessionBoundary(sameAccountRelogin)) // before logout removes the owner
        login.active = null
        assertNull(journal.restoreForStatus(sameAccountRelogin))
        assertNull(slot.row)
    }

    @Test fun failedCommitReadbackOrReplacementCannotAdmitAndUnknownRetainsBarrier() {
        val disk = Disk()
        val journal = TaxiDriverRecoveryJournal(disk, Login(owner))
        disk.commitReturned = false
        assertNull(journal.prepare(owner, handle))
        assertNotNull(disk.row) // storage may have updated its process cache before reporting failure
        assertNull(journal.restoreForStatus(owner))
        disk.commitReturned = true
        assertNull(journal.prepare(owner, handle))
        assertTrue(journal.clearAtSessionBoundary(owner))

        disk.writes = false
        assertNull(journal.prepare(owner, handle))
        disk.writes = true
        assertTrue(journal.clearAtSessionBoundary(owner))
        disk.readable = false
        assertNull(journal.prepare(owner, handle))
        disk.readable = true

        val permit = requireNotNull(journal.prepare(owner, handle))
        val another = requireNotNull(TaxiDriverRecoveryHandle.parse("different"))
        assertFalse(journal.clearCommitted(owner, another))
        assertEquals(handle.value, journal.restoreForStatus(owner)?.value)
        val original = disk.row
        disk.row = disk.row!!.substringBeforeLast('\n') + "\n" + another.value
        var admissions = 0
        assertFalse(journal.consumeSendPermit(permit) { admissions++ })
        assertEquals(0, admissions)
        // A failed final read consumes the permit: restoring the old bytes cannot rearm it.
        val replacement = disk.row
        disk.row = original
        assertFalse(journal.consumeSendPermit(permit) { admissions++ })
        assertEquals(0, admissions)
        disk.row = replacement
        assertTrue(journal.clearCommitted(owner, another))
        assertNull(disk.row)
    }

    @Test fun malformedRecordIsNotStatusEvidenceAndCannotBeOverwrittenByNewClick() {
        val disk = Disk()
        val journal = TaxiDriverRecoveryJournal(disk, Login(owner))
        listOf("bad", "2\n${owner.origin}\n${owner.namespace}\n${handle.value}", "1\n${owner.origin}\n${owner.namespace}\nhttps://bad").forEach {
            disk.row = it
            assertNull(journal.restoreForStatus(owner))
            assertNull(journal.prepare(owner, handle))
            assertEquals(it, disk.row)
        }
    }
}
