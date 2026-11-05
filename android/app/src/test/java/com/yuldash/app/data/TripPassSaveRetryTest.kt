package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripPassSaveRetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = TripPassAuthorityFixture()
    @Before fun authorizeSession() { authority.start() }
    @After fun closeSession() { authority.close() }
    private fun pass(id: Int) = TripPass.fromJson(JSONObject().put("booking_id", id).put("boarding_code", "4821"))

    @Test fun explicitRetryResumesMigrationAndSurvivesDiskRestart() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass(7))))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        val fresh = authority.authorizePositive(pass(42))
        assertFalse(TripPassStore.save(context, fresh, retryMigration = true))
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        plain.failRemovalOf = null
        assertTrue(TripPassStore.save(context, fresh, retryMigration = true))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("4821", TripPassStore.load(context, 42)?.boardingCode)
        assertNotNull(TripPassStore.load(context, 7))
        assertEquals("4821", TripPassAuthorityFixture.durable(secure, 42)?.boardingCode)
        assertNotNull(TripPassAuthorityFixture.durable(secure, 7))
    }

    @Test fun saveRetryCannotReleaseUnconfirmedLogoutQuarantine() {
        val plain = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass(7))))
        plain.failWriteOf = OfflineStoreReset.PENDING
        TripPassStore.clearAll()
        plain.failWriteOf = null
        authority.nextAccount()
        val fresh = authority.authorizePositive(pass(42))
        assertFalse(TripPassStore.save(context, fresh, retryMigration = true))
        assertNull(TripPassStore.load(context, 7))
        assertNull(TripPassAuthorityFixture.durable(plain, 42))
        assertTrue(OfflineMigration.resetUnconfirmed(plain))
        authority.coldAuthority()
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun outdatedSessionCannotResumeMigrationOrSave() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass(7))))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        plain.failRemovalOf = null
        assertNull(TripPassStore.load(context, 42))
        val fresh = authority.authorizePositive(pass(42))
        assertFalse(TripPassStore.save(context, fresh, retryMigration = true,
            expectedGeneration = ApiClient.queueSessionGeneration() - 1))
        assertTrue(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertNull(TripPassAuthorityFixture.durable(secure, 42))
        authority.coldAuthority()
        assertNull(TripPassStore.load(context, 42))
    }
}
