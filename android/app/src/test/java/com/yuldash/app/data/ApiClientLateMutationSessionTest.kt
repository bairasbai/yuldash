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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientLateMutationSessionTest {
    private lateinit var server: MockWebServer
    private val requestArrived = CountDownLatch(1)
    private val releaseResponse = CountDownLatch(1)
    private val oldRequest = AtomicReference<RecordedRequest>()

    @Before fun prepare() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path in listOf("/me/delete", "/upload/chat-photo")) {
                        oldRequest.set(request)
                        requestArrived.countDown()
                        if (!releaseResponse.await(5, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                        return MockResponse().setResponseCode(200).setBody(
                            if (request.path == "/upload/chat-photo") """{"url":"/uploads/account-A-private.jpg"}""" else "{}",
                        )
                    }
                    if (request.path == "/auth/logout" || request.path == "/auth/push/unregister") {
                        return MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
                    }
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
        ApiClient.saveToken("local-account-A")
    }

    @After fun cleanup() {
        releaseResponse.countDown()
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.shutdown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    private fun switchAccountsWhileResponseIsHeld() {
        try {
            assertTrue("Old mutation must reach the local server before switching accounts", requestArrived.await(5, TimeUnit.SECONDS))
            assertEquals("POST", oldRequest.get().method)
            assertEquals("Bearer local-account-A", oldRequest.get().getHeader("Authorization"))
            ApiClient.logout()
            ApiClient.saveToken("local-account-B")
        } finally {
            releaseResponse.countDown()
        }
    }

    @Test fun successfulOldAccountDeletionMustNotClearTheNewSession() = runBlocking {
        val request = async(Dispatchers.IO) { ApiClient.deleteAccount() }
        switchAccountsWhileResponseIsHeld()
        request.await()
        assertEquals("A's completed deletion must not erase B's login", "local-account-B", ApiClient.currentToken())
        assertFalse("Old mutation must not navigate B to login", ApiClient.sessionExpired.value)
    }

    @Test fun successfulOldMultipartUploadMustNotPublishItsResultToTheNewAccount() = runBlocking {
        val request = async(Dispatchers.IO) { ApiClient.uploadChatPhoto("local synthetic image bytes".toByteArray()) }
        switchAccountsWhileResponseIsHeld()
        val result = request.await()
        assertTrue("Public upload API must use the real multipart transport", oldRequest.get().getHeader("Content-Type").orEmpty().startsWith("multipart/form-data;"))
        assertTrue("A's private upload URL must not be returned as B's success", result.isFailure)
        assertEquals("local-account-B", ApiClient.currentToken())
        assertFalse(ApiClient.sessionExpired.value)
    }
}
