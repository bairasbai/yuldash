package com.yuldash.app.data

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientRefreshReplayTest {
    private lateinit var server: MockWebServer
    private val attempts = CopyOnWriteArrayList<JSONObject>()
    private var failResponse = true
    private var persistedAtRequest = false
    private val prefsField = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private fun prefs() = prefsField.get(ApiClient) as SharedPreferences
    private fun json(body: String = "{}", code: Int = 200) = MockResponse().setBody(body).setResponseCode(code)

    @Before fun setup() = runBlocking {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/auth/verify" -> json("""{"access_token":"old-access","refresh_token":"old-refresh"}""")
                    "/auth/refresh" -> {
                        val body = JSONObject(request.body.readUtf8())
                        attempts.add(body)
                        persistedAtRequest = prefs().getString("refresh_rotation_id", null) == body.optString("rotation_id") &&
                            prefs().getString("refresh_rotation_token", null) == "old-refresh"
                        if (failResponse) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else json("""{"access_token":"new-access","refresh_token":"new-refresh"}""")
                    }
                    "/auth/logout" -> json()
                    else -> json(code = if (request.getHeader("Authorization") == "Bearer new-access") 200 else 401)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 250
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "Local").getOrThrow()
        Unit
    }

    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun lostResponseRetryUsesPersistedIntentAndClearsItWithPair() = runBlocking {
        assertTrue(ApiClient.getConsents().isFailure)
        assertTrue("Intent must be durable before the POST", persistedAtRequest)
        val id = attempts.single().getString("rotation_id")
        assertTrue(id.matches(Regex("[0-9a-f]{64}")))
        failResponse = false
        assertTrue(ApiClient.getConsents().isSuccess)
        assertEquals(id, attempts.last().getString("rotation_id"))
        assertEquals("new-refresh", prefs().getString("refresh_token", null))
        assertFalse(prefs().contains("refresh_rotation_id"))
        assertFalse(prefs().contains("refresh_rotation_token"))
    }

    @Test fun coldInitializationReusesPersistedIntent() = runBlocking {
        ApiClient.getConsents()
        val id = attempts.single().getString("rotation_id")
        ApiClient.init(ApplicationProvider.getApplicationContext())
        failResponse = false
        assertTrue(ApiClient.getConsents().isSuccess)
        assertEquals(id, attempts.last().getString("rotation_id"))
    }

    @Test fun logoutClearsIntent() = runBlocking {
        ApiClient.getConsents()
        assertTrue(prefs().contains("refresh_rotation_id"))
        ApiClient.logout()
        assertFalse(prefs().contains("refresh_rotation_id"))
        assertFalse(prefs().contains("refresh_rotation_token"))
    }

    @Test fun newLoginDoesNotReusePriorIntentEvenWithSameRefresh() = runBlocking {
        ApiClient.getConsents()
        val old = attempts.single().getString("rotation_id")
        ApiClient.verifyCode("+70000000000", "000000", "Local").getOrThrow()
        ApiClient.getConsents()
        assertNotEquals(old, attempts.last().getString("rotation_id"))
    }

    @Test fun failedIntentCommitPreventsRefreshPost() = runBlocking {
        val original = prefs()
        val failing = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val delegate = original.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit() = false
                }
            }
        }
        prefsField.set(ApiClient, failing)
        try {
            assertTrue(ApiClient.getConsents().isFailure)
            assertTrue("No refresh may leave the device without durable replay intent", attempts.isEmpty())
            assertEquals("old-access", ApiClient.currentToken())
        } finally { prefsField.set(ApiClient, original) }
    }

    @Test fun logoutRegistryIncludesBothIntentKeys() {
        assertTrue(SessionKeys.CLEARED_ON_LOGOUT.contains("refresh_rotation_id"))
        assertTrue(SessionKeys.CLEARED_ON_LOGOUT.contains("refresh_rotation_token"))
    }

    @Test fun missingPreferencesPreventsRefreshPost() = runBlocking {
        val original = prefs()
        prefsField.set(ApiClient, null)
        try {
            assertTrue(ApiClient.getConsents().isFailure)
            assertTrue(attempts.isEmpty())
            assertEquals("old-access", ApiClient.currentToken())
        } finally { prefsField.set(ApiClient, original) }
    }

    @Test fun throwingIntentCommitPreventsRefreshPost() = runBlocking {
        val original = prefs()
        val failing = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val delegate = original.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit(): Boolean = throw IllegalStateException("Storage unavailable")
                }
            }
        }
        prefsField.set(ApiClient, failing)
        try {
            assertTrue(ApiClient.getConsents().isFailure)
            assertTrue(attempts.isEmpty())
            assertEquals("old-access", ApiClient.currentToken())
        } finally { prefsField.set(ApiClient, original) }
    }

    @Test fun intentForAnotherRefreshIsNotReused() = runBlocking {
        val foreignId = "a".repeat(64)
        assertTrue(prefs().edit().putString("refresh_rotation_id", foreignId)
            .putString("refresh_rotation_token", "foreign-refresh").commit())
        ApiClient.getConsents()
        assertNotEquals(foreignId, attempts.single().getString("rotation_id"))
        assertTrue(persistedAtRequest)
    }

    @Test fun failedPairCommitKeepsOldPairAndSameIntentForRetry() = runBlocking {
        val original = prefs()
        val failing = object : SharedPreferences by original {
            override fun edit(): SharedPreferences.Editor {
                val delegate = original.edit()
                var writesNewPair = false
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == "token" && value == "new-access") writesNewPair = true
                        delegate.putString(key, value)
                        return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit(): Boolean {
                        delegate.commit() // Model the RAM update even when commit reports failure.
                        return !writesNewPair
                    }
                }
            }
        }
        prefsField.set(ApiClient, failing)
        failResponse = false
        try {
            assertTrue(ApiClient.getConsents().isFailure)
            assertEquals("old-access", ApiClient.currentToken())
            assertEquals("old-refresh", original.getString("refresh_token", null))
            assertEquals(attempts.single().getString("rotation_id"), original.getString("refresh_rotation_id", null))
        } finally { prefsField.set(ApiClient, original) }
        assertTrue(ApiClient.getConsents().isSuccess)
        assertEquals(attempts.first().getString("rotation_id"), attempts.last().getString("rotation_id"))
    }
}
