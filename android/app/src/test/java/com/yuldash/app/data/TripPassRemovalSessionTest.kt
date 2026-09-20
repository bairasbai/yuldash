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
class TripPassRemovalSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun outdatedRemovalCannotHideOrEraseCurrentPassport() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        val pass = TripPass.fromJson(JSONObject().put("booking_id", 42).put("boarding_code", "new-account"))
        assertTrue(TripPassStore.save(context, pass))
        assertEquals(TripPassStore.RemovalResult.NOT_SAVED, TripPassStore.requestRemoval(
            context, 42, expectedGeneration = ApiClient.queueSessionGeneration() - 1))
        assertFalse(plain.contains(TripPassDeletion.KEY))
        assertEquals("new-account", TripPassStore.load(context, 42)?.boardingCode)
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("new-account", TripPassStore.load(context, 42)?.boardingCode)
    }

    @Test fun currentSessionCanRemovePassportDurably() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 42))))
        assertEquals(TripPassStore.RemovalResult.CLEARED, TripPassStore.requestRemoval(
            context, 42, expectedGeneration = ApiClient.queueSessionGeneration()))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 42))
    }

    @Test fun removalCapturedBeforeLogoutCannotAffectReplacementStorage() {
        val oldSession = ApiClient.queueSessionGeneration()
        ApiClient.logout()
        assertNotEquals(oldSession, ApiClient.queueSessionGeneration())
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(
            JSONObject().put("booking_id", 42).put("boarding_code", "replacement"))))
        assertEquals(TripPassStore.RemovalResult.NOT_SAVED,
            TripPassStore.requestRemoval(context, 42, expectedGeneration = oldSession))
        assertFalse(plain.contains(TripPassDeletion.KEY))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("replacement", TripPassStore.load(context, 42)?.boardingCode)
    }
}
