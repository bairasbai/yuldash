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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ApiClient login/logout/startup; only the two offline plain disks inject write failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginAfterOfflineResetFailureTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private lateinit var passportFixture: OfflinePassportFixture03
    private var serverStartAttempted = false
    private var apiInitAttempted = false
    private fun accountToken() = TripPassAuthorityFixture.jwt(if (account == "A") 7 else 8)
    private lateinit var context: OfflineContext
    private lateinit var server: MockWebServer
    @Volatile private var account = "A"

    private class OfflineContext(base: Context) : ContextWrapper(base) {
        var passes = MemoryDiskPreferences()
        var outbox = MemoryDiskPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "yuldash_trippass_v2" -> passes
            "yuldash_outbox_v2" -> outbox
            else -> super.getSharedPreferences(name, mode)
        }
        fun restartDisks() {
            passes = passes.restarted()
            outbox = outbox.restarted()
        }
    }

    private fun pass() = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "account-A"))
    private suspend fun login() = ApiClient.verifyCode("+70000000000", "000000", "Fallback")

    @Before fun setup() = cleanupGuard.runSetup { runBlocking {
        ApiClient.resetForTest()
        context = OfflineContext(ApplicationProvider.getApplicationContext())
        passportFixture = OfflinePassportFixture03(context)
        passportFixture.prepareClock()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = passportFixture.response(request) ?: when (request.path) {
                    "/auth/verify" -> {
                        val current = account
                        MockResponse().setBody("""{"access_token":"${accountToken()}","refresh_token":"$current-refresh","user":{"name":"Name $current","role":"passenger"}}""")
                    }
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
        login().getOrThrow()
        // Complete any guest-startup reset first. The later fault must reject the FIRST durable
        // marker for account A, rather than accidentally rely on an older marker already on disk.
        TripPassStore.initStores(context.passes, MemoryDiskPreferences())
        Outbox.initStores(context.outbox, MemoryDiskPreferences())
        assertFalse(context.passes.restarted().contains(OfflineStoreReset.PENDING))
        assertFalse(context.outbox.restarted().contains(OfflineStoreReset.PENDING))
        // Secure storage becomes unavailable again; account A now uses the fallback disks.
        TripPassStore.initStores(context.passes, null)
        Outbox.initStores(context.outbox, null)
        val canonicalPass = passportFixture.prepare(pass(), 7)
        assertTrue(TripPassStore.save(context, canonicalPass))
        assertEquals(canonicalPass.toJson().toString(), context.passes.restarted().getString("pass_7", null))
        assertNotNull(TripPassStore.load(context, 7))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "account-A queued message")))
        assertTrue(context.outbox.restarted().getString("queue", null)!!.contains("account-A queued message"))
    } }

    @After fun cleanup() {
        cleanupGuard.requireHealthy()
        try {
            if (::context.isInitialized) {
                context.passes.failWriteOf = null
                context.outbox.failWriteOf = null
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

    private suspend fun assertLoginWaitsForReset(failPasses: Boolean) {
        val failingDisk = if (failPasses) context.passes else context.outbox
        failingDisk.failWriteOf = OfflineStoreReset.PENDING
        ApiClient.logout()
        assertFalse(ApiClient.isLoggedIn())
        assertFalse("The first reset marker must not have reached disk", failingDisk.restarted().contains(OfflineStoreReset.PENDING))
        account = "B"
        val attempt = login()
        val publishedB = ApiClient.currentToken() == TripPassAuthorityFixture.jwt(8)

        // Fresh disk objects discard the failed commit's memory changes and the in-process quarantine.
        // Actual ApiClient.init decides whether auth requires another personal-data reset.
        if (attempt.isSuccess) {
            context.restartDisks()
            ApiClient.init(context)
            assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
            assertNull("Account A passport returned after B login and restart", TripPassStore.load(context, 7))
            assertEquals("Account A queue returned after B login and restart", 0, Outbox.count(context, 7))
        }
        assertTrue("Login B must fail before durable offline reset", attempt.isFailure)
        assertFalse("Rejected login must not publish B", publishedB)
        assertFalse(ApiClient.isLoggedIn())
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))

        // Recover the failed disk and retry login in this process, without a manual extra logout.
        failingDisk.failWriteOf = null
        // Only the independent real Snapshot cleanup may settle here; auth must recover its own legacy reset.
        awaitOfflineWriteSnapshotReset02()
        login().getOrThrow()
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        context.restartDisks()
        ApiClient.init(context)
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))
    }

    @Test fun rejectedPassportResetMustPreventNewLogin() = runBlocking {
        assertLoginWaitsForReset(failPasses = true)
    }

    @Test fun rejectedQueueResetMustPreventNewLogin() = runBlocking {
        assertLoginWaitsForReset(failPasses = false)
    }

    @Test fun durableResetMarkerAllowsLoginWhileSecureStorageIsUnavailable() = runBlocking {
        val securePasses = MemoryDiskPreferences()
        val secureQueue = MemoryDiskPreferences()
        TripPassStore.initStores(context.passes, securePasses)
        Outbox.initStores(context.outbox, secureQueue)
        TripPassStore.initStores(context.passes, null)
        Outbox.initStores(context.outbox, null)
        ApiClient.logout()
        assertTrue(context.passes.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        assertTrue(context.outbox.restarted().getBoolean(OfflineStoreReset.PENDING, false))
        account = "B"
        // Both original durable legacy markers were asserted above; this waits only for Snapshot cleanup.
        awaitOfflineWriteSnapshotReset02()
        login().getOrThrow()
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))
        // The same secure disks return later: the durable marker must erase account A first.
        TripPassStore.initStores(context.passes.restarted(), securePasses.restarted())
        Outbox.initStores(context.outbox.restarted(), secureQueue.restarted())
        assertNull(TripPassStore.load(context, 7))
        assertEquals(0, Outbox.count(context, 7))
    }
}
