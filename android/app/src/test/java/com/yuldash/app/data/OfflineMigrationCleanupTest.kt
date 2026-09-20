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
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMigrationCleanupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun pass(code: String) = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", code))

    @Test fun repeatedInitKeepsSnapshotReadableAndBlocksDeleteUntilRecovery() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        secure.edit().putString("pass_99", pass("unrelated").copy(bookingId = 99).toJson().toString()).commit()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("old")))
        plain.failRemovalOf = "pass_7"
        repeat(2) {
            TripPassStore.initStores(plain, secure)
            assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
            TripPassStore.updateBoardingCode(context, 7, "new")
            assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
            assertNotNull(TripPassStore.load(context, 99))
        }
        // A durable deletion request now hides the snapshot immediately, while physical cleanup
        // still waits for migration recovery. Other passports must remain readable.
        assertEquals(TripPassStore.RemovalResult.DEFERRED, TripPassStore.requestRemoval(context, 7))
        assertNull(TripPassStore.load(context, 7))
        assertNotNull(TripPassStore.load(context, 99))
        plain.failRemovalOf = null
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.remove(context, 7))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 7))
        assertNotNull(TripPassStore.load(context, 99))
    }

    @Test fun pendingQueueWithUnavailableSecureCannotAppendOrSendAfterRestart() = runBlocking {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        Outbox.initStores(plain, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "old")))
        plain.failRemovalOf = "queue"
        Outbox.initStores(plain, secure)
        val restarted = plain.restarted()
        Outbox.initStores(restarted, null)
        assertEquals(1, Outbox.count(context, 7))
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "new")))
        MockWebServer().use { server ->
            server.start()
            ApiClient.resetForTest()
            ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
            try {
                assertFalse(Outbox.flush(context))
                assertEquals(0, server.requestCount)
                assertEquals(1, Outbox.count(context, 7))
            } finally { ApiClient.resetForTest() }
        }
        Outbox.initStores(restarted, secure.restarted())
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "new")))
        assertEquals(2, Outbox.count(context, 7))
    }

    @Test fun failedPreparationDoesNotCopyAndFailedSecureWriteCanResume() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("old")))
        plain.failWriteOf = OfflineMigration.JOURNAL
        TripPassStore.initStores(plain, secure)
        assertFalse(secure.contains("pass_7"))
        assertFalse(TripPassStore.save(context, pass("new")))
        plain.failWriteOf = null
        secure.failWriteOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertTrue(plain.contains("pass_7"))
        assertTrue(plain.contains(OfflineMigration.JOURNAL))
        assertFalse(TripPassStore.save(context, pass("new")))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
        assertTrue(TripPassStore.save(context, pass("new")))
    }

    @Test fun logoutDiscardsJournalEvenWhenSecureIsUnavailable() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("old")))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        TripPassStore.initStores(plain, null)
        plain.failRemovalOf = null
        TripPassStore.clearAll()
        assertNull(TripPassStore.load(context, 7))
        assertTrue(TripPassStore.save(context, pass("account-B")))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("account-B", TripPassStore.load(context, 7)?.boardingCode)
        assertFalse(plain.contains(OfflineMigration.JOURNAL))
    }

    @Test fun failedLogoutQuarantinesSnapshotInCurrentProcess() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("old")))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        TripPassStore.clearAll() // same fault also rejects the initial logout clear
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 7))
        assertFalse(TripPassStore.save(context, pass("new")))
        plain.failRemovalOf = null
        TripPassStore.clearAll()
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 7))
        assertTrue(TripPassStore.save(context, pass("new")))
    }

    @Test fun malformedJournalBlocksMutationsAndDoesNotOverwriteSecure() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        plain.edit().putString(OfflineMigration.JOURNAL, "broken").commit()
        secure.edit().putString("pass_7", pass("secure").toJson().toString()).commit()
        TripPassStore.initStores(plain, secure)
        assertNull(TripPassStore.load(context, 7))
        assertFalse(TripPassStore.save(context, pass("new")))
        assertEquals("secure", JSONObject(secure.getString("pass_7", null)!!).getString("boarding_code"))
    }

    @Test fun acknowledgedPassportUpdateSurvivesFailedCleanupAndRestart() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, pass("old")))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        val accepted = TripPassStore.save(context, pass("new"))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        if (accepted) assertEquals("acknowledged update was overwritten by stale source", "new", TripPassStore.load(context, 7)?.boardingCode)
        else {
            assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
            assertTrue(TripPassStore.save(context, pass("new")))
            assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
        }
    }

    @Test fun acknowledgedQueueAppendSurvivesFailedCleanupAndRestart() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        Outbox.initStores(plain, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "old")))
        plain.failRemovalOf = "queue"
        Outbox.initStores(plain, secure)
        val next = Outbox.newMessage(7, "new")
        val accepted = Outbox.enqueue(context, next)
        Outbox.initStores(plain.restarted(), secure.restarted())
        if (accepted) assertEquals("acknowledged append was overwritten by stale source", 2, Outbox.count(context, 7))
        else {
            assertEquals(1, Outbox.count(context, 7))
            assertTrue(Outbox.enqueue(context, next))
            assertEquals(2, Outbox.count(context, 7))
        }
    }
}
