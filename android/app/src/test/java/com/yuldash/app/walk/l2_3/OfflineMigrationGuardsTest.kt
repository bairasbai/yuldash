package com.yuldash.app.walk.l2_3

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.commitOfflineString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * leaf-2.3 (OfflineMigration.kt, R18): `open()` checks `recoverOfflineWrites` first and, if it
 * fails, refuses to move ANY data — not just the one key whose write is still unresolved. This
 * was previously backed only by scenarios where the unresolved key happened to be a trip-pass
 * key itself. Here the unresolved key ("poison") is unrelated to the booking being saved, to
 * prove the guard blocks the whole store, not a per-key filter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMigrationGuardsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun pass(id: Int) = TripPass.fromJson(JSONObject().put("booking_id", id))

    @Test fun unresolvedRollbackOfAnUnrelatedKeyBlocksSavingAnyBooking() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        // Create a write to an unrelated key whose own rollback also cannot commit — a disk
        // that still refuses this one key, independent of trip passes entirely.
        plain.failWriteOf = "poison"
        plain.failRemovalOf = "poison"
        assertFalse(commitOfflineString(plain, "poison", "x"))

        TripPassStore.initStores(plain, secure)

        assertFalse("an unresolved write recovery elsewhere must block saving an unrelated booking",
            TripPassStore.save(context, pass(7)))
        assertNull("nothing must have been written while recovery is unresolved",
            TripPassStore.load(context, 7))
    }

    @Test fun oncePoisonedKeyRecoversMigrationResumesNormally() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        plain.failWriteOf = "poison"
        plain.failRemovalOf = "poison"
        assertFalse(commitOfflineString(plain, "poison", "x"))
        TripPassStore.initStores(plain, secure)
        assertFalse(TripPassStore.save(context, pass(7)))

        // The fault clears; the next initStores must resolve the stale recovery and allow saves.
        plain.failWriteOf = null
        plain.failRemovalOf = null
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass(7)))
        assertNotNull(TripPassStore.load(context, 7))
    }
}
