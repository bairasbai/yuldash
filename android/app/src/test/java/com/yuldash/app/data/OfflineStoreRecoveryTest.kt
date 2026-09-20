package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineStoreRecoveryTest {
    private lateinit var context: Context
    private val plain get() = context.getSharedPreferences("qa-offline-plain", 0)
    private val secure get() = context.getSharedPreferences("qa-offline-secure", 0)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        plain.edit().clear().commit(); secure.edit().clear().commit()
    }
    private fun pass(id: Int) = TripPass.fromJson(JSONObject().put("booking_id", id).put("driver_name", "QA"))
    @Test fun passportCannotReturnAfterLogoutWithUnavailableSecure() {
        TripPassStore.initStores(plain, secure)
        TripPassStore.save(context, pass(1))
        TripPassStore.initStores(plain, null)
        TripPassStore.clearAll()
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 1))
        assertFalse(secure.contains("pass_1"))
    }
    @Test fun oldQueueCannotReturnAfterLogoutWithUnavailableSecure() {
        Outbox.initStores(plain, secure)
        Outbox.enqueue(context, Outbox.newMessage(1, "QA account A"))
        Outbox.initStores(plain, null)
        Outbox.clearAll()
        Outbox.initStores(plain, secure)
        assertFalse(Outbox.hasPending(context))
        assertFalse(secure.contains("queue"))
    }
    @Test fun nextAccountsPlainPassportSurvivesWhileOldSecureIsErased() {
        TripPassStore.initStores(plain, secure)
        TripPassStore.save(context, pass(1))
        TripPassStore.initStores(plain, null)
        TripPassStore.clearAll()
        TripPassStore.save(context, pass(2))
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 1))
        assertNotNull(TripPassStore.load(context, 2))
    }
    @Test fun nextAccountsPlainQueueSurvivesRecovery() {
        Outbox.initStores(plain, secure)
        Outbox.enqueue(context, Outbox.newMessage(1, "QA A"))
        Outbox.initStores(plain, null)
        Outbox.clearAll()
        Outbox.enqueue(context, Outbox.newMessage(2, "QA B"))
        Outbox.initStores(plain, secure)
        assertEquals(0, Outbox.count(context, 1))
        assertEquals(1, Outbox.count(context, 2))
    }
    @Test fun temporaryOutageWithoutLogoutPreservesSecureData() {
        TripPassStore.initStores(plain, secure)
        TripPassStore.save(context, pass(1))
        TripPassStore.initStores(plain, null)
        TripPassStore.initStores(plain, secure)
        assertNotNull(TripPassStore.load(context, 1))
    }
    private fun failedWrites(prefs: SharedPreferences) = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val editor = prefs.edit()
            return object : SharedPreferences.Editor by editor {
                override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean = false
            }
        }
    }
    @Test fun failedSecureEraseKeepsOldQueueHiddenAndRetriesBeforeMigration() {
        Outbox.initStores(plain, secure)
        Outbox.enqueue(context, Outbox.newMessage(1, "QA A"))
        Outbox.initStores(plain, null); Outbox.clearAll()
        Outbox.enqueue(context, Outbox.newMessage(2, "QA B"))
        Outbox.initStores(plain, failedWrites(secure))
        assertTrue(plain.getBoolean(OfflineStoreReset.PENDING, false))
        assertEquals(0, Outbox.count(context, 1))
        assertEquals(1, Outbox.count(context, 2))
        assertTrue(secure.getString("queue", null)!!.contains("QA A"))
        Outbox.initStores(plain, secure)
        assertFalse(plain.contains(OfflineStoreReset.PENDING))
        assertEquals(0, Outbox.count(context, 1))
        assertEquals(1, Outbox.count(context, 2))
    }
    @Test fun failedMarkerRemovalDoesNotExposeSecureOrLoseNewPlainPassport() {
        TripPassStore.initStores(plain, secure)
        TripPassStore.save(context, pass(1))
        TripPassStore.initStores(plain, null); TripPassStore.clearAll()
        TripPassStore.save(context, pass(2))
        TripPassStore.initStores(failedWrites(plain), secure)
        assertTrue(plain.getBoolean(OfflineStoreReset.PENDING, false))
        assertNull(TripPassStore.load(context, 1))
        assertNotNull(TripPassStore.load(context, 2))
        assertFalse(secure.contains("pass_1"))
        TripPassStore.initStores(plain, secure)
        assertFalse(plain.contains(OfflineStoreReset.PENDING))
        assertNull(TripPassStore.load(context, 1))
        assertNotNull(TripPassStore.load(context, 2))
    }
}
