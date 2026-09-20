package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientRefreshSessionBoundaryTest {
    private lateinit var server: MockWebServer
    private val oldGetArrived = CountDownLatch(1)
    private val refreshArrived = CountDownLatch(1)
    private val releaseOld = CountDownLatch(1)
    private val bothOldGets = CountDownLatch(2)
    private val refreshCalls = AtomicInteger()
    private val bContactCalls = AtomicInteger()
    @Volatile private var mode = "normal"

    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body)
    private fun auth(account: String) = "{\"access_token\":\"access-$account\",\"refresh_token\":\"refresh-$account\",\"user\":{\"name\":\"Local $account\",\"role\":\"passenger\"}}"
    private fun contacts(account: String) = "{\"items\":[{\"id\":1,\"name\":\"Contact $account\",\"phone\":\"+70000000000\"}]}"

    @Before fun setup() = runBlocking {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/auth/verify") {
                        val account = JSONObject(request.body.readUtf8()).getString("name")
                        return json(auth(account))
                    }
                    if (request.path == "/auth/refresh") {
                        refreshCalls.incrementAndGet()
                        val refresh = JSONObject(request.body.readUtf8()).getString("refresh_token")
                        refreshArrived.countDown()
                        if (mode == "late-refresh" && !releaseOld.await(5, TimeUnit.SECONDS)) return json("{}", 504)
                        return when (refresh) {
                            "refresh-A" -> json(auth("A-new"))
                            "refresh-B" -> json(auth("B-new"))
                            else -> json("{}", 401)
                        }
                    }
                    if (request.path == "/trusted-contacts") {
                        return when (request.getHeader("Authorization")) {
                            "Bearer access-A" -> {
                                oldGetArrived.countDown()
                                if (mode == "late-401" && !releaseOld.await(5, TimeUnit.SECONDS)) return json("{}", 504)
                                if (mode == "parallel") {
                                    bothOldGets.countDown()
                                    if (!bothOldGets.await(5, TimeUnit.SECONDS)) return json("{}", 504)
                                }
                                json("{\"detail\":\"expired\"}", 401)
                            }
                            "Bearer access-A-new" -> json(contacts("A"))
                            "Bearer access-B", "Bearer access-B-new" -> {
                                bContactCalls.incrementAndGet()
                                json(contacts("B"))
                            }
                            else -> json("{}", 401)
                        }
                    }
                    if (request.path == "/auth/logout" || request.path == "/push/unregister") return json("{\"ok\":true}")
                    return json("{}", 503)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10_000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        login("A")
    }

    private suspend fun login(account: String) {
        ApiClient.verifyCode("+70000000000", "000000", account).getOrThrow()
        assertEquals("access-$account", ApiClient.currentToken())
    }

    @After fun cleanup() {
        releaseOld.countDown()
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.shutdown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    @Test fun lateRefreshCannotReplaceTheNewAccountsTokens() = runBlocking {
        mode = "late-refresh"
        val old = async(Dispatchers.IO) { ApiClient.getContacts() }
        try {
            assertTrue("A refresh must reach the local server", refreshArrived.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            login("B")
        } finally {
            releaseOld.countDown()
        }
        old.await()
        assertEquals("Old refresh must not overwrite B's access token", "access-B", ApiClient.currentToken())
        assertFalse(ApiClient.sessionExpired.value)
        assertEquals("Contact B", ApiClient.getContacts().getOrThrow().single().name)
    }

    @Test fun oldUnauthorizedRequestCannotRetryUnderTheNewAccount() = runBlocking {
        mode = "late-401"
        val old = async(Dispatchers.IO) { ApiClient.getContacts() }
        try {
            assertTrue("A GET must reach the server before switching accounts", oldGetArrived.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            login("B") // B has its own refresh token, exercising the refresh branch.
        } finally {
            releaseOld.countDown()
        }
        val result = old.await()
        assertEquals("Do not retry A's operation with B's bearer", 0, bContactCalls.get())
        assertTrue("A's request must not return B's data", result.isFailure)
        assertEquals(0, refreshCalls.get())
        assertEquals("access-B", ApiClient.currentToken())
        assertFalse(ApiClient.sessionExpired.value)
    }

    @Test fun normalTokenRefreshKeepsTheSessionWorking() = runBlocking {
        assertEquals("Contact A", ApiClient.getContacts().getOrThrow().single().name)
        assertEquals("access-A-new", ApiClient.currentToken())
        assertEquals(1, refreshCalls.get())
        assertFalse(ApiClient.sessionExpired.value)
    }

    @Test fun parallelUnauthorizedRequestsShareOneRefresh() = runBlocking {
        mode = "parallel"
        val first = async(Dispatchers.IO) { ApiClient.getContacts() }
        val second = async(Dispatchers.IO) { ApiClient.getContacts() }
        assertEquals("Contact A", first.await().getOrThrow().single().name)
        assertEquals("Contact A", second.await().getOrThrow().single().name)
        assertEquals("Both original requests must actually have reached the server", 0L, bothOldGets.count)
        assertEquals("Only one token refresh is needed", 1, refreshCalls.get())
        assertEquals("access-A-new", ApiClient.currentToken())
    }
}
