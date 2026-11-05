package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineResetMarkerDiskTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val authority = TripPassAuthorityFixture()
    private var ownsAuthority = false

    private fun preparePassport(pass: TripPass): TripPass {
        cleanupGuard.requireHealthy()
        if (!ownsAuthority) {
            ownsAuthority = true
            authority.start()
        }
        return authority.authorizePositive(pass)
    }

    @After fun closeAuthority() {
        cleanupGuard.requireHealthy()
        if (!ownsAuthority) return
        finishOfflineWriteFixture02(cleanupGuard) {
            authority.close()
            TripPassStore.initStores(MemoryDiskPreferences(), null)
        }
    }

    @Before fun checkCleanupHealth() = cleanupGuard.requireHealthy()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun plain(failRestore: Boolean) = MemoryDiskPreferences().apply {
        edit().putBoolean(OfflineStoreReset.PENDING, true).commit()
        failMarkerRemoval = true
        failMarkerRestore = failRestore
    }
    private fun queueSurvives(failRestore: Boolean) {
        val plain = plain(failRestore)
        val secure = context.getSharedPreferences("reset-marker-queue", 0)
        Outbox.initStores(plain, secure)
        Outbox.initStores(plain, secure) // same-process retry must not trust the missing in-memory marker
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(8, "B")))
        Outbox.initStores(plain.restarted(), secure) // disk still had the marker if B was wrongly written to secure
        assertEquals("new account queue erased on restart", 1, Outbox.count(context, 8))
    }
    @Test fun failedMarkerRemovalDoesNotEraseNewQueueAfterRestart() = queueSurvives(false)
    @Test fun failedMarkerRestoreCannotBypassPendingResetInSameProcess() = queueSurvives(true)
    @Test fun failedMarkerRemovalDoesNotEraseNewPassportAfterRestart() {
        val passport = preparePassport(TripPass.fromJson(JSONObject().put("booking_id", 8)))
        val plain = plain(false)
        val secure = context.getSharedPreferences("reset-marker-passport", 0)
        TripPassStore.initStores(plain, secure)
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, passport))
        assertNotNull(offlineWriteLegacyPass02(8))
        TripPassStore.initStores(plain.restarted(), secure)
        assertNotNull("new account passport erased on restart", TripPassStore.load(context, 8))
        assertNotNull("Stored passport must survive the marker recovery", offlineWriteLegacyPass02(8))
        assertEquals(8, JSONObject(requireNotNull(secure.getString("pass_8", null))).getInt("booking_id"))
    }
}
