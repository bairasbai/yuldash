package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA-B01-010: fixture models an already mixed installation, not a new logout failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegacyOfflineOwnerTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private lateinit var passportFixture: OfflinePassportFixture03
    private var serverStartAttempted = false
    private var apiInitAttempted = false
    private lateinit var context: LegacyContext
    private lateinit var server: MockWebServer

    private class LegacyContext(base: Context) : ContextWrapper(base) {
        var passes = MemoryDiskPreferences()
        var queue = MemoryDiskPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "yuldash_trippass" -> passes
            "yuldash_outbox" -> queue
            else -> super.getSharedPreferences(name, mode)
        }
    }

    @Before fun setup() = cleanupGuard.runSetup { runBlocking {
        ApiClient.resetForTest()
        context = LegacyContext(ApplicationProvider.getApplicationContext())
        passportFixture = OfflinePassportFixture03(context)
        passportFixture.prepareClock()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = passportFixture.response(request) ?: when (request.path) {
                    "/auth/verify" -> MockResponse().setBody(
                        """{"access_token":"${TripPassAuthorityFixture.jwt(8)}","refresh_token":"B-refresh","user":{"name":"Name B","role":"passenger"}}"""
                    )
                    "/auth/logout", "/push/register", "/push/unregister" -> MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }
        serverStartAttempted = true
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 500
        apiInitAttempted = true
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.init(context)
        passportFixture.captureAuthBacking()
        awaitOfflineWriteSnapshotReset02()
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
    } }

    @After fun cleanup() {
        cleanupGuard.requireHealthy()
        try {
            if (::context.isInitialized) {
                context.passes.failRemovalOf = null
                context.queue.failRemovalOf = null
            }
            if (apiInitAttempted) ApiClient.logout()
            finishOfflineWriteFixture02(cleanupGuard) {
                cleanupGuard.requireHealthy()
                if (serverStartAttempted) server.shutdown()
                ApiClient.resetForTest()
                ApiClient.testBaseUrl = null
                ApiClient.testTimeoutMs = null
                if (::passportFixture.isInitialized) passportFixture.restoreClock()
            }
        } catch (failure: Throwable) {
            poisonOfflinePassportFixture03(cleanupGuard, failure)
        }
    }

    private fun seedLegacy() {
        // Account A is the intended author in this fixture ONLY. The real old format has no
        // owner field: neither booking ID nor text proves which account originally saved it.
        // Seed disk directly, bypassing new-login guards, to model an upgrade of old data.
        val oldPass = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "A-code"))
        val oldQueue = JSONArray().put(JSONObject()
            .put("id", 1L).put("booking_id", 7).put("kind", "message")
            .put("payload", "Message originally queued by A").put("created_at", 1L))
        context.passes = MemoryDiskPreferences().also {
            assertTrue(it.edit().putString("pass_7", oldPass.toJson().toString()).commit())
        }.restarted()
        context.queue = MemoryDiskPreferences().also {
            assertTrue(it.edit().putString("queue", oldQueue.toString()).commit())
        }.restarted()

    }

    @Test fun validSessionMustNotExposeUnattributedLegacyOfflineData() {
        seedLegacy()
        ApiClient.init(context)

        assertEquals("Startup still resolves the current B session", TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        val visiblePass = TripPassStore.load(context, 7)
        val visibleQueueCount = Outbox.count(context, 7)
        assertTrue(
            "Unattributed legacy data leaked into B: passport=${visiblePass != null}, queueCount=$visibleQueueCount",
            visiblePass == null && visibleQueueCount == 0,
        )
    }
    @Test fun upgradeKeepsLegacyDiskButNeverSendsItsQueue() = runBlocking {
        seedLegacy()
        val passBefore = context.passes.restarted().getString("pass_7", null)
        val queueBefore = context.queue.restarted().getString("queue", null)
        ApiClient.init(context)
        val requests = server.requestCount
        assertFalse(Outbox.flush(context))
        assertEquals(requests, server.requestCount)
        assertEquals(passBefore, context.passes.restarted().getString("pass_7", null))
        assertEquals(queueBefore, context.queue.restarted().getString("queue", null))
    }

    @Test fun newAccountCommittedDataSurvivesStoreReinitWithoutAdoptingLegacy() {
        seedLegacy()
        val legacyPass = context.passes.restarted().getString("pass_7", null)
        val legacyQueue = context.queue.restarted().getString("queue", null)
        ApiClient.init(context)
        // Prove the old unattributed A values stay invisible BEFORE B receives its own details.
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))
        assertEquals(legacyPass, context.passes.restarted().getString("pass_7", null))
        assertEquals(legacyQueue, context.queue.restarted().getString("queue", null))
        val pass = passportFixture.prepare(TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "B-code")), 8)
        assertTrue(TripPassStore.save(context, pass))
        val committed = pass.toJson().toString()
        assertEquals(committed, offlinePassportRaw03(7))
        assertEquals("B-code", TripPassStore.load(context, 7)?.boardingCode)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "B-message")))
        ApiClient.init(context)
        // NOT_SAMEASSERT: a real re-init revokes the previous generation's current projection.
        assertNull(TripPassStore.load(context, 7))
        assertEquals(committed, offlinePassportRaw03(7))
        assertEquals(1, Outbox.count(context, 7))
        assertEquals(legacyPass, context.passes.restarted().getString("pass_7", null))
        assertEquals(legacyQueue, context.queue.restarted().getString("queue", null))
        assertTrue(context.queue.restarted().getString("queue", null)!!.contains("queued by A"))
    }

    @Test fun logoutClearsLegacyAndActiveStores() {
        seedLegacy()
        ApiClient.init(context)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "B-message")))
        ApiClient.logout()
        assertFalse(context.passes.restarted().contains("pass_7"))
        assertFalse(context.queue.restarted().contains("queue"))
        assertEquals(0, Outbox.count(context, 7))
    }

    @Test fun failedLegacyCleanupCannotReExposeDataAfterRestart() = runBlocking {
        seedLegacy()
        context.passes.failRemovalOf = "pass_7"
        context.queue.failRemovalOf = "queue"
        ApiClient.logout()
        context.passes = context.passes.restarted()
        context.queue = context.queue.restarted()
        assertTrue(context.passes.contains("pass_7"))
        assertTrue(context.queue.contains("queue"))
        context.passes.failRemovalOf = "pass_7"
        context.queue.failRemovalOf = "queue"
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        ApiClient.init(context)
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        assertTrue(context.passes.restarted().contains("pass_7"))
        assertTrue(context.queue.restarted().contains("queue"))
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))
    }

    @Test fun legacyCleanupHandlesUnavailableSecureThenRecovery() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        plain.edit().putString("queue", "old-plain").commit()
        secure.edit().putString("queue", "old-secure").commit()
        assertFalse(OfflineLegacyStores.clearStores(plain, null))
        assertTrue(plain.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        assertTrue(OfflineLegacyStores.clearStores(plain.restarted(), secure))
        assertFalse(secure.restarted().contains("queue"))
    }

}
