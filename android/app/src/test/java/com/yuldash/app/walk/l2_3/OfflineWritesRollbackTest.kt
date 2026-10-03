package com.yuldash.app.walk.l2_3

import android.app.Application
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.commitOfflineString
import com.yuldash.app.data.readOfflineString
import com.yuldash.app.data.recoverOfflineWrites
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * leaf-2.3 (OfflineWrites.kt): до этого теста откат неудачной записи (общий механизм, которым
 * пользуются и офлайн-паспорт поездки, и очередь исходящих) проверялся только КОСВЕННО, через
 * TripPassStore/Outbox. Здесь — напрямую: половинчатая запись не должна становиться "настоящим"
 * значением ни в памяти, ни тем более на диске, а провал самого отката обязан честно
 * докладываться, а не тихо считаться завершённым.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineWritesRollbackTest {
    @Test fun failedWriteRestoresThePreviousValueNotJustAbsence() {
        val plain = MemoryDiskPreferences()
        assertTrue(commitOfflineString(plain, "k", "old-value"))

        plain.failWriteOf = "k"
        assertFalse("a disk refusal must be reported, not swallowed", commitOfflineString(plain, "k", "new-value"))

        assertEquals("a half-applied write must roll back to the real previous value, not drop it to null",
            "old-value", readOfflineString(plain, plain, "k"))
        assertEquals("the previous value must still be the one durably on disk",
            "old-value", plain.restarted().getString("k", null))
    }

    @Test fun whenRollbackAlsoFailsRecoveryHonestlyReportsItUntilItReallySucceeds() {
        val plain = MemoryDiskPreferences()
        // The key never existed: the correct rollback target is "absent", and even that
        // "removal" can fail on a disk that refuses every write to this key.
        plain.failWriteOf = "k"
        plain.failRemovalOf = "k"

        assertFalse(commitOfflineString(plain, "k", "new-value"))
        assertFalse("the dirty write must never reach disk while its rollback is unconfirmed",
            plain.restarted().contains("k"))
        assertFalse("recovery must not pretend a still-failing rollback succeeded",
            recoverOfflineWrites(plain, null))

        plain.failRemovalOf = null
        assertTrue("once the disk actually accepts the rollback, recovery must finish the job",
            recoverOfflineWrites(plain, null))
        assertNull(readOfflineString(plain, plain, "k"))
    }

    // R11w: a CONFIRMED absence (the key never existed before the failed write) must be
    // served from the tracked rollback map, not from whatever happens to be on disk right
    // now. This matters because the key's rollback (a removal) can itself keep failing while
    // something else — a stale value written before this guard existed, a differently-scoped
    // writer — leaves unrelated content physically sitting under the same key.
    @Test fun confirmedAbsenceIsNotHiddenByLeftoverDiskContent() {
        val plain = MemoryDiskPreferences()
        plain.failWriteOf = "k"
        plain.failRemovalOf = "k"
        assertFalse(commitOfflineString(plain, "k", "new-value"))
        // The rollback (a removal, since "k" never existed) is still unresolved: failRemovalOf
        // keeps failing it. Confirm that setup before touching the disk out of band.
        assertFalse("recovery must still be unresolved for this setup to be meaningful",
            recoverOfflineWrites(plain, null))

        // Simulate disk content arriving through a path that does not go through this guard
        // at all (direct SharedPreferences write), while the confirmed-absent rollback for
        // "k" is still pending in memory.
        plain.failWriteOf = null
        plain.failRemovalOf = null
        assertTrue(plain.edit().putString("k", "leftover-unrelated-value").commit())

        assertNull("a still-pending confirmed absence must win over leftover disk content",
            readOfflineString(plain, plain, "k"))
    }
}
