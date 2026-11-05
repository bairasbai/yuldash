package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineDurableWriteTest {
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

    private lateinit var context: Context
    private lateinit var disk: SharedPreferences
    private var writes = 0
    private fun store(fail: Boolean = false, mutateOnFailure: Boolean = false) = object : SharedPreferences by disk {
        override fun edit(): SharedPreferences.Editor {
            val editor = disk.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value); return this
                }
                override fun apply() { /* Model an async write that has not reached disk yet. */ }
                override fun commit(): Boolean {
                    writes++
                    if (fail) {
                        if (mutateOnFailure) editor.commit()
                        return false
                    }
                    return editor.commit()
                }
            }
        }
    }
    @Before fun setup() = cleanupGuard.runSetup {
        context = ApplicationProvider.getApplicationContext()
        disk = context.getSharedPreferences("qa-durable", 0)
        disk.edit().clear().commit(); writes = 0
    }
    @Test fun enqueueReturnsOnlyAfterDurableWrite() {
        Outbox.initStores(store(), null)
        val result: Any = Outbox.enqueue(context, Outbox.newMessage(7, "QA message"))
        assertEquals(true, result)
        assertTrue(writes > 0)
        Outbox.initStores(disk, null)
        assertEquals(1, Outbox.count(context, 7))
    }
    @Test fun failedEnqueueDoesNotClaimSuccessOrChangeCounter() {
        Outbox.initStores(store(fail = true), null)
        val version = Outbox.version.value
        val result: Any = Outbox.enqueue(context, Outbox.newMessage(7, "QA message"))
        assertEquals(false, result)
        assertEquals(version, Outbox.version.value)
        assertFalse(disk.contains("queue"))
    }
    @Test fun passportReturnsOnlyAfterDurableWrite() {
        val passport = preparePassport(TripPass.fromJson(JSONObject().put("booking_id", 7)))
        TripPassStore.initStores(store(), null)
        val result: Any = TripPassStore.save(context, passport)
        assertEquals(true, result)
        assertTrue(writes > 0)
        assertNotNull(disk.getString("pass_7", null))
        TripPassStore.initStores(disk, null)
        assertNotNull(TripPassStore.load(context, 7))
        assertNotNull(offlineWriteLegacyPass02(7))
    }
    @Test fun failedPassportSaveReturnsFailure() {
        val passport = preparePassport(TripPass.fromJson(JSONObject().put("booking_id", 7)))
        TripPassStore.initStores(store(fail = true), null)
        val result: Any = TripPassStore.save(context, passport)
        assertEquals(false, result)
        assertTrue("The authorized save must reach the failing commit", writes > 0)
        assertNull(disk.getString("pass_7", null))
        assertNull(offlineWriteLegacyPass02(7))
        // A failed local write does not revoke a separately confirmed server projection.
        assertNotNull(TripPassStore.load(context, 7))
    }
    @Test fun failedCommitRestoresPreviousQueueEvenIfMemoryChanged() {
        Outbox.initStores(disk, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(1, "QA previous")))
        Outbox.initStores(store(fail = true, mutateOnFailure = true), null)
        val version = Outbox.version.value
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(2, "QA rejected")))
        assertEquals(version, Outbox.version.value)
        assertEquals(1, Outbox.count(context, 1))
        assertEquals(0, Outbox.count(context, 2))
    }
    @Test fun concurrentAcceptedEnqueuesAreNotLost() {
        Outbox.initStores(disk, null)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (1..20).map { n -> workers.submit<Boolean> {
                start.await()
                Outbox.enqueue(context, Outbox.newMessage(7, "QA $n"))
            } }
            start.countDown()
            results.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(20, Outbox.count(context, 7))
        } finally { workers.shutdownNow() }
    }
}
