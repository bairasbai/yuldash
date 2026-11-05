package com.yuldash.app.walk.l2_3

import android.app.Application
import android.content.Context
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflinePassportFixture03
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPassAuthorityFixture
import com.yuldash.app.data.awaitOfflineWriteSnapshotReset02
import com.yuldash.app.data.finishOfflineWriteFixture02
import com.yuldash.app.data.offlinePassportRaw03
import com.yuldash.app.data.poisonOfflinePassportFixture03
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.commitOfflineString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * leaf-2.3 (OfflineMigration.kt, R18): `open()` checks `recoverOfflineWrites` first and, if it
 * fails, refuses to move ANY data — not just the one key whose write is still unresolved. This
 * was previously backed only by scenarios where the unresolved key happened to be a trip-pass
 * key itself. Here the unresolved key ("poison") is unrelated to the booking being saved, to
 * prove the guard blocks the whole store, not a per-key filter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineMigrationGuardsTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private lateinit var context: Context
    private lateinit var passportFixture: OfflinePassportFixture03
    private lateinit var server: MockWebServer
    private lateinit var canonicalPass: TripPass
    private var serverStartAttempted = false
    private var apiInitAttempted = false
    private val faultStores = mutableListOf<MemoryDiskPreferences>()
    private fun ownedDisk() = MemoryDiskPreferences().also { faultStores += it }

    @Before fun setup() = cleanupGuard.runSetup {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        passportFixture = OfflinePassportFixture03(context)
        passportFixture.prepareClock()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    passportFixture.response(request) ?: if (request.path == "/auth/logout") MockResponse().setBody("{}")
                    else MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        serverStartAttempted = true
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 500
        apiInitAttempted = true
        ApiClient.init(context)
        passportFixture.captureAuthBacking()
        awaitOfflineWriteSnapshotReset02()
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
        canonicalPass = passportFixture.prepare(TripPass.fromJson(JSONObject().put("booking_id", 7)
            .put("boarding_code", "QA-migration-code")), 7)
        assertTrue("The same authorized candidate must be writable before the unrelated fault",
            TripPassStore.save(context, canonicalPass))
        assertEquals(canonicalPass.toJson().toString(), offlinePassportRaw03(7))
    }

    @After fun cleanup() {
        cleanupGuard.requireHealthy()
        try {
            faultStores.forEach { it.failWriteOf = null; it.failRemovalOf = null }
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

    @Test fun unresolvedRollbackOfAnUnrelatedKeyBlocksSavingAnyBooking() {
        val plain = ownedDisk()
        val secure = ownedDisk()
        // Create a write to an unrelated key whose own rollback also cannot commit — a disk
        // that still refuses this one key, independent of trip passes entirely.
        plain.failWriteOf = "poison"
        plain.failRemovalOf = "poison"
        assertFalse(commitOfflineString(plain, "poison", "x"))

        TripPassStore.initStores(plain, secure)

        assertFalse("an unresolved write recovery elsewhere must block saving an unrelated booking",
            TripPassStore.save(context, canonicalPass))
        // NOT_SAMEASSERT: current own DETAILS remain visible; load() cannot prove a disk write did not occur.
        assertFalse("nothing reached the target plain disk", plain.restarted().contains("pass_7"))
        assertFalse("nothing reached the target secure disk", secure.restarted().contains("pass_7"))
        assertNotNull("The current own HTTP authority remains valid independently of the rejected write",
            TripPassStore.load(context, 7))
    }

    @Test fun oncePoisonedKeyRecoversMigrationResumesNormally() {
        val plain = ownedDisk()
        val secure = ownedDisk()
        plain.failWriteOf = "poison"
        plain.failRemovalOf = "poison"
        assertFalse(commitOfflineString(plain, "poison", "x"))
        TripPassStore.initStores(plain, secure)
        assertFalse(TripPassStore.save(context, canonicalPass))
        assertFalse(plain.restarted().contains("pass_7"))
        assertFalse(secure.restarted().contains("pass_7"))

        // The fault clears; the next initStores must resolve the stale recovery and allow saves.
        plain.failWriteOf = null
        plain.failRemovalOf = null
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, canonicalPass))
        assertEquals(canonicalPass.toJson().toString(), secure.restarted().getString("pass_7", null))
        assertFalse(plain.restarted().contains("pass_7"))
        assertNotNull(TripPassStore.load(context, 7))
    }
}
