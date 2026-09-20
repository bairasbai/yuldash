package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Additional regression checks for guarded auth commits, not separate pre-fix findings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientLateLoginSessionTest {
    private lateinit var server: MockWebServer
    private val oldLoginArrived = CountDownLatch(1)
    private val releaseOldLogin = CountDownLatch(1)
    private val refreshBodies = CopyOnWriteArrayList<String>()

    @Before fun prepare() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path in listOf("/auth/verify", "/auth/tg/verify")) {
                        val body = JSONObject(request.body.readUtf8())
                        val isOldAccount = request.path == "/auth/tg/verify" || body.optString("name") == "Account A"
                        if (isOldAccount) {
                            oldLoginArrived.countDown()
                            if (!releaseOldLogin.await(5, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                            return ok(auth("A"))
                        }
                        return ok(auth("B"))
                    }
                    if (request.path == "/trusted-contacts") {
                        return if (request.getHeader("Authorization") == "Bearer access-B-refreshed") {
                            ok("""{"items":[]}""")
                        } else MockResponse().setResponseCode(401).setBody("{}")
                    }
                    if (request.path == "/auth/refresh") {
                        val body = request.body.readUtf8()
                        refreshBodies.add(body)
                        return if (JSONObject(body).optString("refresh_token") == "refresh-B") {
                            ok("""{"access_token":"access-B-refreshed","refresh_token":"refresh-B-next"}""")
                        } else MockResponse().setResponseCode(401).setBody("{}")
                    }
                    if (request.path == "/auth/logout" || request.path == "/auth/push/unregister") return ok("{}")
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10_000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
    }

    @After fun cleanup() {
        releaseOldLogin.countDown()
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.shutdown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun auth(account: String) =
        """{"access_token":"access-$account","refresh_token":"refresh-$account","name":"Account $account"}"""

    private fun delayedLoginCannotReplaceNewLogin(telegram: Boolean) = runBlocking {
        val oldLogin = async(Dispatchers.IO) {
            if (telegram) ApiClient.tgVerify("local-request-A", "123456")
            else ApiClient.verifyCode("+70000000001", "1234", "Account A")
        }
        try {
            assertTrue("The first login must be waiting for its local HTTP response", oldLoginArrived.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            ApiClient.verifyCode("+70000000002", "1234", "Account B").getOrThrow()
            assertEquals("access-B", ApiClient.currentToken())
            assertEquals("Account B", ApiClient.cachedName())
        } finally {
            releaseOldLogin.countDown()
        }
        assertTrue("Old login response must not report successful authentication", oldLogin.await().isFailure)
        assertEquals("Late login must not replace the current access token", "access-B", ApiClient.currentToken())
        assertEquals("Late login must not replace the current profile name", "Account B", ApiClient.cachedName())
        // Exercise refresh through the real public API, rather than inspecting private fields.
        assertTrue(ApiClient.getContacts().getOrThrow().isEmpty())
        assertEquals(1, refreshBodies.size)
        assertEquals("refresh-B", JSONObject(refreshBodies.single()).getString("refresh_token"))
        assertEquals("access-B-refreshed", ApiClient.currentToken())
        assertEquals("Account B", ApiClient.cachedName())
        assertFalse(ApiClient.sessionExpired.value)
    }

    @Test fun lateSmsLoginCannotOverwriteTheCompletedLoginOfAnotherAccount() = delayedLoginCannotReplaceNewLogin(false)

    @Test fun lateTelegramLoginCannotOverwriteTheCompletedLoginOfAnotherAccount() = delayedLoginCannotReplaceNewLogin(true)
}
