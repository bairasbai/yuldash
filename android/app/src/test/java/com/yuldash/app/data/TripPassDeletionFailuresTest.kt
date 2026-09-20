package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.TripPassStore.RemovalResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripPassDeletionFailuresTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun pass(code: String) = TripPass.fromJson(
        JSONObject().put("booking_id", 7).put("boarding_code", code),
    )

    @Test fun failedDeletionMarkerIsHiddenButNeverReportedDurableUntilRetrySucceeds() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass("account-A")))
        plain.failWriteOf = TripPassDeletion.KEY

        repeat(2) {
            assertEquals(RemovalResult.NOT_SAVED, TripPassStore.requestRemoval(context, 7))
            assertNull(TripPassStore.load(context, 7))
            assertTrue("Fault must change memory without persisting the marker", plain.contains(TripPassDeletion.KEY))
            assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
            assertTrue(secure.restarted().contains("pass_7"))
        }

        plain.failWriteOf = null
        assertEquals(RemovalResult.CLEARED, TripPassStore.requestRemoval(context, 7))
        val restartedPlain = plain.restarted()
        val restartedSecure = secure.restarted()
        assertTrue(restartedPlain.contains(TripPassDeletion.KEY))
        assertFalse(restartedSecure.contains("pass_7"))
        TripPassStore.initStores(restartedPlain, restartedSecure)
        assertNull(TripPassStore.load(context, 7))
    }

    @Test fun failedPhysicalDeletionRemainsHiddenAndIsRetriedAfterRestart() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass("account-A")))
        secure.failRemovalOf = "pass_7"

        assertEquals(RemovalResult.DEFERRED, TripPassStore.requestRemoval(context, 7))
        assertTrue("Failed deletion must still exist on disk", secure.restarted().contains("pass_7"))
        assertTrue(plain.restarted().contains(TripPassDeletion.KEY))
        assertNull(TripPassStore.load(context, 7))

        val restartedPlain = plain.restarted()
        val restartedSecure = secure.restarted()
        TripPassStore.initStores(restartedPlain, restartedSecure)
        assertNull(TripPassStore.load(context, 7))
        assertFalse("Recovery must erase the physical copy", restartedSecure.restarted().contains("pass_7"))
        assertFalse(restartedPlain.restarted().contains("pass_7"))
    }

    @Test fun malformedDeletionMarkerHidesExistingPassAndBlocksReplacement() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        assertTrue(secure.edit().putString("pass_7", pass("account-A").toJson().toString()).commit())
        assertTrue(plain.edit().putString(TripPassDeletion.KEY, "not-json").commit())
        TripPassStore.initStores(plain, secure)

        assertNull(TripPassStore.load(context, 7))
        assertFalse(TripPassStore.save(context, pass("replacement")))
        assertEquals(RemovalResult.NOT_SAVED, TripPassStore.requestRemoval(context, 7))
        val restartedPlain = plain.restarted()
        val restartedSecure = secure.restarted()
        assertEquals("account-A", JSONObject(restartedSecure.getString("pass_7", null)!!).getString("boarding_code"))
        assertEquals("not-json", restartedPlain.getString(TripPassDeletion.KEY, null))
        TripPassStore.initStores(restartedPlain, restartedSecure)
        assertNull(TripPassStore.load(context, 7))
        assertFalse(TripPassStore.save(context, pass("replacement")))
    }

    @Test fun durableLogoutClearsCommittedDeletionBarrierForNextAccount() {
        checkLogoutClearsBarrier(failMarker = false)
    }

    @Test fun durableLogoutCancelsUncommittedDeletionBarrierForNextAccount() {
        checkLogoutClearsBarrier(failMarker = true)
    }

    private fun checkLogoutClearsBarrier(failMarker: Boolean) {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass("account-A")))
        if (failMarker) plain.failWriteOf = TripPassDeletion.KEY
        assertEquals(
            if (failMarker) RemovalResult.NOT_SAVED else RemovalResult.CLEARED,
            TripPassStore.requestRemoval(context, 7),
        )
        TripPassStore.clearAll()
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        assertFalse(secure.restarted().contains("pass_7"))
        // Use the same preferences object: logout must cancel its in-memory barrier too.
        assertTrue(TripPassStore.save(context, pass("account-B")))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("account-B", TripPassStore.load(context, 7)?.boardingCode)
        assertEquals("account-B", JSONObject(secure.restarted().getString("pass_7", null)!!).getString("boarding_code"))
    }

    @Test fun logoutDuringPendingMigrationAndDeletionPreservesNewAccountWhenSecureReturns() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("account-A")))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertTrue(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertTrue(secure.restarted().contains("pass_7"))
        assertEquals(RemovalResult.DEFERRED, TripPassStore.requestRemoval(context, 7))
        TripPassStore.initStores(plain, null)

        plain.failRemovalOf = null
        TripPassStore.clearAll()
        assertTrue(plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        assertFalse(plain.restarted().contains(TripPassDeletion.KEY))
        assertFalse(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertTrue(TripPassStore.save(context, pass("account-B")))
        assertEquals("account-A", JSONObject(secure.restarted().getString("pass_7", null)!!).getString("boarding_code"))

        val restartedPlain = plain.restarted()
        val restartedSecure = secure.restarted()
        TripPassStore.initStores(restartedPlain, restartedSecure)
        assertEquals("account-B", TripPassStore.load(context, 7)?.boardingCode)
        assertEquals("account-B", JSONObject(restartedSecure.restarted().getString("pass_7", null)!!).getString("boarding_code"))
        assertFalse(restartedPlain.restarted().contains("pass_7"))
        assertFalse(restartedPlain.restarted().contains(TripPassDeletion.KEY))
        assertFalse(restartedPlain.restarted().contains(OfflineMigration.JOURNAL))
        assertFalse(restartedPlain.restarted().contains(OfflineStoreReset.PENDING))
    }
}
