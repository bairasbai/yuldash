package com.yuldash.app.data

import android.app.Application
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
class SosRetryIntentTest {
    private lateinit var prefs: SharedPreferences
    private var now = 100_000L
    private val body get() = JSONObject().put("note", "private synthetic emergency")
        .put("lat", 54.123456).put("lng", 55.654321).put("booking_id", 42)
    @Before fun prepare() {
        prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("sos-intent-test", 0)
        prefs.edit().clear().commit()
    }
    @Test fun storesOnlyOpaqueMetadata() {
        SosRetryIntent(prefs) { now }.prepare(body)
        val saved = JSONObject(prefs.getString(SosRetryIntent.PREF_KEY, null)!!).getJSONObject("sos")
        assertEquals(setOf("key", "fingerprint", "created"), saved.keys().asSequence().toSet())
        assertFalse(saved.toString().contains("private"))
        assertFalse(saved.toString().contains("54.123456"))
    }
    @Test fun expiryDoesNotExtendWithRetry() {
        val store = SosRetryIntent(prefs) { now }
        val key = store.prepare(body)
        now += 14 * 60_000
        assertEquals(key, store.prepare(body))
        now += 2 * 60_000
        assertNotEquals(key, store.prepare(body))
    }
    @Test fun futureTimestampIsNotReused() {
        val key = SosRetryIntent(prefs) { now }.prepare(body)
        now--
        assertNotEquals(key, SosRetryIntent(prefs) { now }.prepare(body))
    }
    @Test fun malformedMetadataDoesNotBlockNewEmergency() {
        prefs.edit().putString(SosRetryIntent.PREF_KEY, "{broken").commit()
        assertTrue(SosRetryIntent(prefs) { now }.prepare(body).isNotBlank())
    }
    @Test fun lateAcknowledgementDoesNotClearNewIntent() {
        val store = SosRetryIntent(prefs) { now }
        val first = store.prepare(body)
        val secondBody = body.put("note", "new emergency")
        val second = store.prepare(secondBody)
        store.acknowledge(first)
        assertEquals(second, SosRetryIntent(prefs) { now }.prepare(secondBody))
    }
    private fun failingWrites() = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            val editor = prefs.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun remove(key: String?): SharedPreferences.Editor = this
                override fun commit() = false
            }
        }
    }
    @Test fun failedWriteKeepsMemoryRetryButCannotPromiseRestartProtection() {
        val failing = failingWrites()
        val store = SosRetryIntent(failing) { now }
        val first = store.prepare(body)
        assertEquals(first, store.prepare(body))
        assertNotEquals(first, SosRetryIntent(failing) { now }.prepare(body))
    }
    @Test fun failedAcknowledgementCleanupDoesNotResurrectWithinProcess() {
        val first = SosRetryIntent(prefs) { now }.prepare(body)
        val store = SosRetryIntent(failingWrites()) { now }
        assertEquals(first, store.prepare(body))
        store.acknowledge(first)
        val next = store.prepare(body)
        assertNotEquals(first, next)
        assertEquals(next, store.prepare(body))
    }
    @Test fun acknowledgementOnlyClearsItsScopeAcrossRestart() {
        val store = SosRetryIntent(prefs) { now }
        val sos = store.prepare(body)
        val roadside = store.prepare(body, "roadside-booking-42")
        store.acknowledge(roadside, "roadside-booking-42")
        val restored = SosRetryIntent(prefs) { now }
        assertEquals(sos, restored.prepare(body))
        assertNotEquals(roadside, restored.prepare(body, "roadside-booking-42"))
    }
    @Test fun roadsideCapacityNeverEvictsSosOrEarlierActiveSlots() {
        val store = SosRetryIntent(prefs) { now }
        val sos = store.prepare(body)
        val first = store.prepare(body, "roadside-booking-1")
        for (id in 2..33) store.prepare(body, "roadside-booking-$id")
        val memoryOnly = store.prepare(body, "roadside-booking-33")
        assertEquals(memoryOnly, store.prepare(body, "roadside-booking-33"))
        val restored = SosRetryIntent(prefs) { now }
        assertEquals(sos, restored.prepare(body))
        assertEquals(first, restored.prepare(body, "roadside-booking-1"))
        assertNotEquals(memoryOnly, restored.prepare(body, "roadside-booking-33"))
    }
    @Test fun boundedMemoryOverflowCannotEvictDurableActiveSlots() {
        val store = SosRetryIntent(prefs) { now }
        val sos = store.prepare(body)
        val first = store.prepare(body, "roadside-booking-1")
        for (id in 2..65) store.prepare(body, "roadside-booking-$id")
        assertEquals(sos, store.prepare(body))
        assertEquals(first, store.prepare(body, "roadside-booking-1"))
        assertEquals(first, SosRetryIntent(prefs) { now }.prepare(body, "roadside-booking-1"))
    }
}
