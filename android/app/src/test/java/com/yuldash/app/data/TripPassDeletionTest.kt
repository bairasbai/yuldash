package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripPassDeletionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun pass(id: Int, code: String) = TripPass.fromJson(
        JSONObject().put("booking_id", id).put("boarding_code", code),
    )

    @Test fun deletionWhileSecureIsUnavailableSurvivesItsReturn() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, pass(7, "finished")))
        assertTrue(TripPassStore.save(context, pass(99, "other-trip")))

        TripPassStore.initStores(plain.restarted(), null)
        TripPassStore.remove(context, 7)

        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull("Deleted trip returned when encrypted storage became available", TripPassStore.load(context, 7))
        assertEquals("other-trip", TripPassStore.load(context, 99)?.boardingCode)
    }

    @Test fun deletionDuringUnfinishedMigrationSurvivesDiskRestart() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        assertTrue(secure.edit().putString("pass_99", pass(99, "other-trip").toJson().toString()).commit())
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass(7, "finished")))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertTrue("Setup must reach a copied but not cleaned migration", secure.contains("pass_7"))
        assertTrue(plain.restarted().contains(OfflineMigration.JOURNAL))

        // Completion may mean a durable deletion request while physical cleanup is pending.
        TripPassStore.remove(context, 7)

        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull("Migration replay restored a trip deleted after its copy", TripPassStore.load(context, 7))
        assertEquals("other-trip", TripPassStore.load(context, 99)?.boardingCode)
    }

    @Test fun lateSaveCannotRestoreDeletedTripButAnotherTripCanBeSaved() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        val staleResponse = pass(7, "late-response")
        assertTrue(TripPassStore.save(context, pass(7, "finished")))
        assertTrue(TripPassStore.remove(context, 7))

        assertFalse("A delayed response must not recreate a completed trip", TripPassStore.save(context, staleResponse))
        assertTrue(TripPassStore.save(context, pass(99, "other-trip")))

        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 7))
        assertEquals("other-trip", TripPassStore.load(context, 99)?.boardingCode)
    }
}
