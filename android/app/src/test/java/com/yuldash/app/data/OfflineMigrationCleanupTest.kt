package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
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
    private val authority = TripPassAuthorityFixture()
    private var ownsAuthority = false

    private fun startAuthority() {
        ownsAuthority = true
        authority.start()
    }

    @After fun closeAuthority() {
        if (!ownsAuthority) return
        // On timeout keep the owned local origin/server intact; Root must stop this isolated run.
        val parents = listOf(
            ApiClient::class.java.getDeclaredField("bg").apply { isAccessible = true }.get(ApiClient),
            OfflineTripSnapshots::class.java.getDeclaredField("jobs").apply { isAccessible = true }.get(OfflineTripSnapshots),
        ).map { requireNotNull((it as CoroutineScope).coroutineContext[Job]) }
        try {
            runBlocking {
                withTimeout(10_000) {
                    while (true) {
                        val children = parents.flatMap { it.children.toList() }
                        if (children.isEmpty()) break
                        children.forEach { it.join() }
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("MIGRATION_CLEANUP_FIXTURE_POISON_ABORT: jobs still running; local origin/server retained", failure)
        }
        authority.close()
        TripPassStore.initStores(MemoryDiskPreferences(), null)
    }

    private fun pass(code: String) = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", code))

    /** Observe the currently selected legacy store; do not reopen/recover/reset it. */
    private fun legacySelection(): OfflineMigration.Selection = TripPassStore::class.java
        .getDeclaredField("migration").apply { isAccessible = true }.get(TripPassStore)
        as OfflineMigration.Selection

    /** The durable redo journal remains the source when a failed commit changed plain memory. */
    private fun durableJournalPass(plain: MemoryDiskPreferences, id: Int): TripPass {
        val journal = JSONObject(requireNotNull(plain.restarted().getString(OfflineMigration.JOURNAL, null)))
        assertEquals(1, journal.getInt("version"))
        return TripPass.fromJson(JSONObject(journal.getJSONObject("data").getString("pass_$id")))
    }

    private fun assertCurrentAuthority(
        owner: SnapshotOwner, generation: Long, projection: TripPrivateProjection, pass: TripPass,
    ) {
        assertEquals(owner, OfflineTripSnapshots.currentOwner())
        assertEquals(generation, ApiClient.queueSessionGeneration())
        assertSame(projection, OfflineTripSnapshots.projection(7))
        assertSame(pass, TripPassStore.load(context, 7))
    }

    @Test fun repeatedInitKeepsSnapshotReadableAndBlocksDeleteUntilRecovery() {
        startAuthority()
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        secure.edit().putString("pass_99", pass("unrelated").copy(bookingId = 99).toJson().toString()).commit()
        authority.authorizePositive(pass("unrelated").copy(bookingId = 99))
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("old"))))
        authority.authorizePositive(pass("new"))
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        plain.failRemovalOf = "pass_7"
        repeat(2) {
            TripPassStore.initStores(plain, secure)
            // load() is the current server projection; persisted migration data is checked separately.
            assertEquals("old", durableJournalPass(plain, 7).boardingCode)
            assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
            assertFalse(TripPassStore.updateBoardingCode(context, 7, "new"))
            assertEquals("old", durableJournalPass(plain, 7).boardingCode)
            assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
            assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
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
        assertNull(TripPassAuthorityFixture.durable(plain, 7))
        assertNull(TripPassAuthorityFixture.durable(secure, 7))
        assertEquals("unrelated", TripPassAuthorityFixture.durable(secure, 99)?.boardingCode)
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
        startAuthority()
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("old"))))
        val replacement = authority.authorizePositive(pass("new"))
        plain.failWriteOf = OfflineMigration.JOURNAL
        TripPassStore.initStores(plain, secure)
        assertFalse(secure.contains("pass_7"))
        assertFalse(TripPassStore.save(context, replacement))
        plain.failWriteOf = null
        secure.failWriteOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertTrue(plain.contains("pass_7"))
        assertTrue(plain.contains(OfflineMigration.JOURNAL))
        assertFalse(TripPassStore.save(context, replacement))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
        assertTrue(TripPassStore.save(context, replacement))
        assertEquals("new", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
    }

    @Test fun localClearDiscardsLegacyJournalWithoutRevokingCurrentProjection() {
        startAuthority()
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("old"))))
        assertTrue(legacySelection().readable)
        assertTrue(legacySelection().writable)
        assertEquals("old", TripPass.fromJson(JSONObject(requireNotNull(legacySelection().read("pass_7")))).boardingCode)
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        // This value is still the same owner's fresh server projection, not a new account.
        val replacement = authority.authorizePositive(pass("account-B"))
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val generation = ApiClient.queueSessionGeneration()
        val projection = requireNotNull(OfflineTripSnapshots.projection(7))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        TripPassStore.initStores(plain, null)
        plain.failRemovalOf = null
        TripPassStore.clearAll()
        assertNull(legacySelection().read("pass_7"))
        assertFalse(OfflineMigration.resetUnconfirmed(plain))
        assertTrue(plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        assertNull(TripPassAuthorityFixture.durable(plain, 7))
        assertFalse(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertCurrentAuthority(owner, generation, projection, replacement)
        assertTrue(TripPassStore.save(context, replacement))
        assertEquals("account-B", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        assertTrue(plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertEquals("account-B", TripPass.fromJson(JSONObject(requireNotNull(legacySelection().read("pass_7")))).boardingCode)
        assertEquals("account-B", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertNull(TripPassAuthorityFixture.durable(plain, 7))
        assertFalse(plain.restarted().contains(OfflineStoreReset.PENDING))
        assertFalse(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertCurrentAuthority(owner, generation, projection, replacement)
    }

    @Test fun failedLocalClearQuarantinesLegacyReadsUntilConfirmedRecovery() {
        startAuthority()
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("old"))))
        assertTrue(legacySelection().readable)
        assertTrue(legacySelection().writable)
        assertEquals("old", TripPass.fromJson(JSONObject(requireNotNull(legacySelection().read("pass_7")))).boardingCode)
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        val replacement = authority.authorizePositive(pass("new"))
        val owner = requireNotNull(OfflineTripSnapshots.currentOwner())
        val generation = ApiClient.queueSessionGeneration()
        val projection = requireNotNull(OfflineTripSnapshots.projection(7))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertEquals("old", TripPass.fromJson(JSONObject(requireNotNull(legacySelection().read("pass_7")))).boardingCode)
        TripPassStore.clearAll() // the same fault rejects the first local clear
        assertTrue(OfflineMigration.resetUnconfirmed(plain))
        assertFalse(legacySelection().readable)
        assertFalse(legacySelection().writable)
        assertNull(legacySelection().read("pass_7"))
        assertEquals("old", durableJournalPass(plain, 7).boardingCode)
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertCurrentAuthority(owner, generation, projection, replacement)
        TripPassStore.initStores(plain, secure)
        assertTrue(OfflineMigration.resetUnconfirmed(plain))
        assertFalse(legacySelection().readable)
        assertFalse(legacySelection().writable)
        assertNull(legacySelection().read("pass_7"))
        assertFalse(TripPassStore.save(context, replacement))
        assertCurrentAuthority(owner, generation, projection, replacement)
        plain.failRemovalOf = null
        TripPassStore.clearAll()
        TripPassStore.initStores(plain, secure)
        assertFalse(OfflineMigration.resetUnconfirmed(plain))
        assertTrue(legacySelection().readable)
        assertTrue(legacySelection().writable)
        assertNull(legacySelection().read("pass_7"))
        assertNull(TripPassAuthorityFixture.durable(plain, 7))
        assertNull(TripPassAuthorityFixture.durable(secure, 7))
        assertFalse(plain.restarted().contains(OfflineStoreReset.PENDING))
        assertFalse(plain.restarted().contains(OfflineMigration.JOURNAL))
        assertTrue(TripPassStore.save(context, replacement))
        assertEquals("new", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        assertCurrentAuthority(owner, generation, projection, replacement)
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
        startAuthority()
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, null)
        assertTrue(TripPassStore.save(context, authority.authorizePositive(pass("old"))))
        val replacement = authority.authorizePositive(pass("new"))
        plain.failRemovalOf = "pass_7"
        TripPassStore.initStores(plain, secure)
        assertEquals("old", TripPassAuthorityFixture.durable(plain, 7)?.boardingCode)
        val accepted = TripPassStore.save(context, replacement)
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        if (accepted) assertEquals("acknowledged update was overwritten by stale source", "new", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
        else {
            assertEquals("old", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
            assertTrue(TripPassStore.save(context, replacement))
            assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
            assertEquals("new", TripPassAuthorityFixture.durable(secure, 7)?.boardingCode)
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
