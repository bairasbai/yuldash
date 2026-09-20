package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientAuthStorageIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var plain: SharedPreferences
    private val prefsField = ApiClient::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    @Volatile private var account = "A"
    private fun prefs() = prefsField.get(ApiClient) as SharedPreferences

    @Before fun setup() {
        ApiClient.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        plain = context.getSharedPreferences("yuldash", Context.MODE_PRIVATE)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val selected = account
                    return when (request.path) {
                        "/auth/verify" -> MockResponse().setBody(
                            """{"access_token":"$selected-access","refresh_token":"$selected-refresh","user":{"name":"Name $selected","role":"driver"}}"""
                        )
                        "/auth/logout", "/push/register", "/push/unregister" -> MockResponse().setBody("{}")
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 500
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.init(context)
    }

    @After fun cleanup() {
        // Restore the real selected store even when a test injected a failing editor.
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun loginPersistsCompleteIdentityBeforePublishingSession() = runBlocking {
        val real = prefs()
        var sawAtomicIdentity = false
        prefsField.set(ApiClient, object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
                        assertFalse("Memory must not publish login before durable identity", ApiClient.isLoggedIn())
                        val saved = editor.commit()
                        if (saved) {
                            assertEquals("A-access", real.getString("token", null))
                            assertEquals("A-refresh", real.getString("refresh_token", null))
                            assertEquals("Name A", real.getString("user_name", null))
                            assertEquals("driver", real.getString("user_role", null))
                            sawAtomicIdentity = true
                        }
                        return saved
                    }
                }
            }
        })
        assertTrue(ApiClient.verifyCode("+70000000000", "000000", "Fallback").isSuccess)
        assertTrue(sawAtomicIdentity)
        assertEquals(if (ApiClient.secureStorageUnavailable) "plain" else "secure", plain.getString(AuthStorePolicy.STATE_KEY, null))
        assertEquals("Name A", ApiClient.cachedName())
        assertEquals("driver", ApiClient.cachedRole())
        ApiClient.init(context)
        assertEquals("A-access", ApiClient.currentToken())
        assertEquals("Name A", ApiClient.cachedName())
        assertEquals("driver", ApiClient.cachedRole())
    }

    @Test fun logoutMarkerPreventsSessionRestoration() = runBlocking {
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        ApiClient.logout()
        assertEquals("logout", plain.getString(AuthStorePolicy.STATE_KEY, null))
        ApiClient.init(context)
        assertFalse(ApiClient.isLoggedIn())
        assertNull(ApiClient.cachedName())
        assertNull(ApiClient.cachedRole())
        assertNull(prefs().getString("refresh_token", null))
    }

    @Test fun failedLoginCommitDoesNotPublishOtherAccountOrRestoreOldOne() = runBlocking {
        ApiClient.verifyCode("+70000000000", "000000", "Fallback").getOrThrow()
        val real = prefs()
        prefsField.set(ApiClient, object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean = false
                }
            }
        })
        account = "B"
        assertTrue(ApiClient.verifyCode("+70000000000", "000000", "Fallback").isFailure)
        assertEquals("A-access", ApiClient.currentToken())
        assertEquals("Name A", ApiClient.cachedName())
        assertEquals("pending", plain.getString(AuthStorePolicy.STATE_KEY, null))
        ApiClient.init(context)
        assertFalse(ApiClient.isLoggedIn())
        assertNull(ApiClient.cachedName())
        assertNull(ApiClient.cachedRole())
        assertNull(prefs().getString("refresh_token", null))
    }
}
