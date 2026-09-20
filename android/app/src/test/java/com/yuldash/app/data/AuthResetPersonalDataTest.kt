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
    private lateinit var context: Context
    private fun box(name: String) = context.getSharedPreferences(name, 0)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:9"
        ApiClient.testTimeoutMs = 300
        ApiClient.init(context)
        ApiClient.saveToken("qa-account-A")
        TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 77)
            .put("driver_name", "QA Driver").put("driver_phone", "QA private phone")))
        Outbox.enqueue(context, Outbox.newMessage(77, "QA private message"))
        box("trip_location_svc").edit().putInt("last_booking", 77).commit()
        box("courier_location_svc").edit().putString("last_parcels", "88").commit()
        box("yuldash_prefs").edit().putString("preferred_role", "Driver")
            .putBoolean("onboarding_completed", true).commit()
        box("yuldash_settings").edit().putString("yuldash_lang", "Ba").commit()
        File(context.cacheDir, "voice_qa-reset.m4a").writeText("QA private audio")
    }
    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }
    private fun assertCleared() {
        assertFalse(ApiClient.isLoggedIn())
        assertNull("Old trip passport survived", TripPassStore.load(context, 77))
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
    @Test fun normalAuthenticatedRestartPreservesOfflineTripAndQueue() {
        ApiClient.init(context)
        assertEquals("qa-account-A", ApiClient.currentToken())
        assertNotNull(TripPassStore.load(context, 77))
        assertEquals(1, Outbox.count(context, 77))
        assertEquals(77, box("trip_location_svc").getInt("last_booking", -1))
        assertTrue(File(context.cacheDir, "voice_qa-reset.m4a").exists())
    }
    @Test fun nextAccountCannotSendPreviousAccountsPendingMessage() = runBlocking {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{}")
            }
            start()
        }
        try {
            ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
            box("yuldash").edit().putString(AuthStorePolicy.STATE_KEY, "pending").commit()
            ApiClient.init(context)
            ApiClient.saveToken("qa-account-B")
            Outbox.flush(context)
            assertEquals("A's action was sent under B's session", 0, server.requestCount)
            assertNull(TripPassStore.load(context, 77))
        } finally { server.shutdown() }
    }
}
