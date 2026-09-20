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
class OfflineResetMarkerDiskTest {
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
        val plain = plain(false)
        val secure = context.getSharedPreferences("reset-marker-passport", 0)
        TripPassStore.initStores(plain, secure)
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 8))))
        TripPassStore.initStores(plain.restarted(), secure)
        assertNotNull("new account passport erased on restart", TripPassStore.load(context, 8))
    }
}
