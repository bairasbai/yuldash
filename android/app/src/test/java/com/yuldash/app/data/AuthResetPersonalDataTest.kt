package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AuthResetPersonalDataTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private lateinit var server: MockWebServer
    private lateinit var passportFixture: OfflinePassportFixture03
    private var serverStartAttempted = false
    private var apiInitAttempted = false
    private lateinit var committedPass: String
    private lateinit var context: Context
    private fun box(name: String) = context.getSharedPreferences(name, 0)
    @Before fun setup() = cleanupGuard.runSetup {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        passportFixture = OfflinePassportFixture03(context)
        passportFixture.prepareClock()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    passportFixture.response(request) ?: MockResponse().setBody("{}")
            }
        }
        serverStartAttempted = true
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 300
        apiInitAttempted = true
        ApiClient.init(context)
        passportFixture.captureAuthBacking()
        awaitOfflineWriteSnapshotReset02()
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(7))
        TripPassStore.init(context)
        val pass = passportFixture.prepare(TripPass.fromJson(JSONObject().put("booking_id", 77)
            .put("driver_name", "QA Driver").put("driver_phone", "QA private phone")
            .put("boarding_code", "QA-A-code")), 7)
        assertTrue("The positive seed must be committed before any auth corruption", TripPassStore.save(context, pass))
        committedPass = pass.toJson().toString()
        assertEquals(committedPass, offlinePassportRaw03(77))
        assertNotNull(TripPassStore.load(context, 77))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(77, "QA private message")))
        assertEquals(1, Outbox.count(context, 77))
        box("trip_location_svc").edit().putInt("last_booking", 77).commit()
        box("courier_location_svc").edit().putString("last_parcels", "88").commit()
        box("yuldash_prefs").edit().putString("preferred_role", "Driver")
            .putBoolean("onboarding_completed", true).commit()
        box("yuldash_settings").edit().putString("yuldash_lang", "Ba").commit()
        File(context.cacheDir, "voice_qa-reset.m4a").writeText("QA private audio")
    }
    @After fun cleanup() {
        cleanupGuard.requireHealthy()
        try {
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
    private fun assertCleared() {
        assertFalse(ApiClient.isLoggedIn())
        assertNull("Old trip passport survived", TripPassStore.load(context, 77))
        assertNull("The committed canonical passport must also be erased", offlinePassportRaw03(77))
        assertFalse("Old message queue survived", Outbox.hasPending(context))
        assertFalse(box("trip_location_svc").contains("last_booking"))
        assertFalse(box("courier_location_svc").contains("last_parcels"))
        assertFalse(box("yuldash_prefs").contains("preferred_role"))
        assertFalse(File(context.cacheDir, "voice_qa-reset.m4a").exists())
        assertTrue(box("yuldash_prefs").getBoolean("onboarding_completed", false))
        assertEquals("Ba", box("yuldash_settings").getString("yuldash_lang", null))
    }
    @Test fun corruptedIdentityClearsAssociatedPersonalData() {
        box("yuldash").edit().putInt(AuthStorePolicy.STATE_KEY, 7).commit()
        ApiClient.init(context)
        assertCleared()
    }
    @Test fun interruptedLoginClearsAssociatedPersonalData() {
        box("yuldash").edit().putString(AuthStorePolicy.STATE_KEY, "pending").commit()
        ApiClient.init(context)
        assertCleared()
    }
    @Test fun interruptedLogoutFinishesAssociatedCleanupOnRestart() {
        box("yuldash").edit().putString(AuthStorePolicy.STATE_KEY, "logout").commit()
        ApiClient.init(context)
        assertCleared()
    }
    @Test fun normalAuthenticatedStoreReinitPreservesCommittedTripAndQueue() {
        ApiClient.init(context)
        assertEquals(TripPassAuthorityFixture.jwt(7), ApiClient.currentToken())
        // NOT_SAMEASSERT: init changes session generation; load is a fresh DETAILS projection, not a disk reader.
        assertNull("Store re-init must not reuse private authority from the previous generation", TripPassStore.load(context, 77))
        assertEquals("The confirmed preference record survives same-JVM store re-init", committedPass, offlinePassportRaw03(77))
        assertEquals(1, Outbox.count(context, 77))
        assertEquals(77, box("trip_location_svc").getInt("last_booking", -1))
        assertTrue(File(context.cacheDir, "voice_qa-reset.m4a").exists())
    }
    @Test fun nextAccountCannotSendPreviousAccountsPendingMessage() = runBlocking {
        val requestsBeforeAccountSwitch = server.requestCount
        box("yuldash").edit().putString(AuthStorePolicy.STATE_KEY, "pending").commit()
        ApiClient.init(context)
        awaitOfflineWriteSnapshotReset02()
        ApiClient.saveToken(TripPassAuthorityFixture.jwt(8))
        assertEquals(TripPassAuthorityFixture.jwt(8), ApiClient.currentToken())
        Outbox.flush(context)
        assertEquals("A's action was sent under B's session", requestsBeforeAccountSwitch, server.requestCount)
        assertNull(TripPassStore.load(context, 77))
        assertNull(offlinePassportRaw03(77))
    }
}
