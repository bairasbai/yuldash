package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
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
class ApiClientStaleAccountResponseTest {
    private lateinit var server: MockWebServer
    private val oldRequestArrived = CountDownLatch(1)
    private val releaseOldResponse = CountDownLatch(1)
    private val newAccountRequests = AtomicInteger()
    private var oldStatus = 200

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/trusted-contacts") {
                        when (request.getHeader("Authorization")) {
                            "Bearer local-account-A" -> {
                                oldRequestArrived.countDown()
                                if (!releaseOldResponse.await(5, TimeUnit.SECONDS)) {
                                    return MockResponse().setResponseCode(504)
                                }
                                return MockResponse().setResponseCode(oldStatus).setBody(
                                    if (oldStatus == 200) contacts("Private contact of A") else "{\"detail\":\"expired\"}"
                                )
                            }
                            "Bearer local-account-B" -> {
                                newAccountRequests.incrementAndGet()
                                return MockResponse().setResponseCode(200).setBody(contacts("Contact of B"))
                            }
                        }
                        return MockResponse().setResponseCode(401).setBody("{}")
                    }
                    if (request.path == "/auth/logout" || request.path == "/auth/push/unregister") {
                        return MockResponse().setResponseCode(200).setBody("{\"ok\":true}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10_000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout() // resetForTest resets transport hooks, not private cached responses.
        ApiClient.sessionExpired.value = false
        ApiClient.saveToken("local-account-A")
    }

    @After fun cleanup() {
        releaseOldResponse.countDown()
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.shutdown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    private fun contacts(name: String) = "{\"items\":[{\"id\":1,\"name\":\"$name\",\"relation\":\"family\",\"phone\":\"+70000000000\"}]}"

    @Test fun delayedOldContactsCannotPopulateTheNewAccountsCache() = runBlocking {
        val oldRequest = async(Dispatchers.IO) { ApiClient.getContacts() }
        try {
            assertTrue("A contacts GET must reach the local server before switching accounts", oldRequestArrived.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            ApiClient.saveToken("local-account-B")
        } finally {
            releaseOldResponse.countDown()
        }
        oldRequest.await() // Old success or cancellation must never become B's cached data.
        val contacts = ApiClient.getContacts().getOrThrow()
        assertEquals("New account must read its own contacts", "Contact of B", contacts.single().name)
        assertEquals("B must authenticate its own contacts request", 1, newAccountRequests.get())
        assertEquals("local-account-B", ApiClient.currentToken())
    }

    @Test fun delayedUnauthorizedResponseCannotLogOutTheNewAccount() = runBlocking {
        oldStatus = 401
        val oldRequest = async(Dispatchers.IO) { ApiClient.getContacts() }
        try {
            assertTrue("A contacts GET must reach the local server before releasing its 401", oldRequestArrived.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            ApiClient.saveToken("local-account-B")
        } finally {
            releaseOldResponse.countDown()
        }
        assertTrue(oldRequest.await().isFailure)
        assertEquals("Old 401 must not destroy B's session", "local-account-B", ApiClient.currentToken())
        assertFalse("Old request must not navigate B back to login", ApiClient.sessionExpired.value)
        assertEquals("Contact of B", ApiClient.getContacts().getOrThrow().single().name)
        assertEquals(1, newAccountRequests.get())
    }
}
