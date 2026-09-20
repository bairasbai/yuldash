package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.json.JSONException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientRefreshOutageTest {
    private lateinit var server: MockWebServer
    private fun json(body: String = "{}", code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Before fun setup() = runBlocking {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 300
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.enqueue(json("""{"access_token":"access-old","refresh_token":"refresh-old","name":"Local"}"""))
        ApiClient.verifyCode("+70000000000", "000000", "Local").getOrThrow()
        server.takeRequest(2, TimeUnit.SECONDS) ?: error("Login request missing")
        Unit
    }

    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        ApiClient.testTimeoutMs = null
        server.shutdown()
        ApiClient.resetForTest()
    }

    private suspend fun request(file: Boolean): Result<*> =
        if (file) ApiClient.uploadPhoto(ByteArray(8), "jpg") else ApiClient.getConsents()

    private fun transient(file: Boolean, response: MockResponse, expectedStatus: Int?, expectedType: Class<out Throwable>? = null) = runBlocking {
        server.enqueue(json(code = 401))
        server.enqueue(response)
        val failure = request(file).exceptionOrNull()
        assertNotNull("The unavailable refresh must fail the original request", failure)
        if (expectedType != null) assertTrue("Unexpected failure type: $failure", expectedType.isInstance(failure))
        else if (expectedStatus == null) assertTrue("Network failure must remain a network failure: $failure", failure is IOException)
        else assertEquals(expectedStatus, (failure as? ApiException)?.status)
        assertFalse("An unavailable server does not invalidate a session", ApiClient.sessionExpired.value)
        assertEquals("access-old", ApiClient.currentToken())
        assertTrue(ApiClient.isLoggedIn())
        server.takeRequest(2, TimeUnit.SECONDS) ?: error("Original request missing")
        val failedRefresh = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Refresh request missing")
        assertEquals("/auth/refresh", failedRefresh.path)
        assertNull("Do not automatically replay the original action after failed refresh", server.takeRequest(100, TimeUnit.MILLISECONDS))

        // A later user retry can still exchange the original pair when the server recovers.
        server.enqueue(json(code = 401))
        server.enqueue(json("""{"access_token":"access-new","refresh_token":"refresh-new"}"""))
        server.enqueue(json(if (file) """{"url":"https://example.invalid/photo.jpg"}""" else "{}"))
        assertTrue(request(file).isSuccess)
        server.takeRequest(2, TimeUnit.SECONDS) ?: error("Retry missing")
        val retryRefresh = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Retry refresh missing")
        assertEquals("refresh-old", JSONObject(retryRefresh.body.readUtf8()).getString("refresh_token"))
        val retried = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Authorized retry missing")
        assertEquals("Bearer access-new", retried.getHeader("Authorization"))
        assertFalse(ApiClient.sessionExpired.value)
    }

    private fun terminal(file: Boolean, status: Int) = runBlocking {
        server.enqueue(json(code = 401))
        server.enqueue(json(code = status))
        assertEquals(status, (request(file).exceptionOrNull() as? ApiException)?.status)
        assertTrue(ApiClient.sessionExpired.value)
        assertFalse(ApiClient.isLoggedIn())
    }

    @Test fun serverFailurePreservesNormalSession() = transient(false, json(code = 500), 500)
    @Test fun serverFailurePreservesFileSession() = transient(true, json(code = 503), 503)
    @Test fun lostRefreshResponsePreservesNormalSession() = transient(false, MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), null)
    @Test fun lostRefreshResponsePreservesFileSession() = transient(true, MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), null)
    @Test fun incompletePairDoesNotOverwriteSession() = transient(false, json("""{"access_token":"partial"}"""), 502)
    @Test fun malformedJsonDoesNotEndSession() = transient(false, json("{broken"), null, JSONException::class.java)
    @Test fun numericAccessDoesNotOverwriteSession() = transient(false, json("""{"access_token":123,"refresh_token":"partial"}"""), 502)
    @Test fun numericRefreshDoesNotOverwriteSession() = transient(false, json("""{"access_token":"partial","refresh_token":123}"""), 502)
    @Test fun blankRefreshDoesNotOverwriteSession() = transient(false, json("""{"access_token":"partial","refresh_token":" "}"""), 502)
    @Test fun missingAccessDoesNotOverwriteSession() = transient(false, json("""{"refresh_token":"partial"}"""), 502)
    @Test fun blankAccessDoesNotOverwriteSession() = transient(false, json("""{"access_token":" ","refresh_token":"partial"}"""), 502)
    @Test fun rateLimitedRefreshDoesNotEndSession() = transient(false, json(code = 429), 429)
    @Test fun revokedRefreshEndsNormalSession() = terminal(false, 401)
    @Test fun bannedRefreshEndsNormalSession() = terminal(false, 403)
    @Test fun revokedRefreshEndsFileSession() = terminal(true, 401)
    @Test fun bannedRefreshEndsFileSession() = terminal(true, 403)

    @Test fun unauthorizedFileWithoutRefreshEndsSession() = runBlocking {
        ApiClient.saveToken("access-without-refresh")
        server.enqueue(json(code = 401))
        assertEquals(401, (request(true).exceptionOrNull() as? ApiException)?.status)
        assertTrue(ApiClient.sessionExpired.value)
        assertFalse(ApiClient.isLoggedIn())
    }

    @Test fun unauthorizedFileAfterRefreshEndsSession() = runBlocking {
        server.enqueue(json(code = 401))
        server.enqueue(json("""{"access_token":"access-new","refresh_token":"refresh-new"}"""))
        server.enqueue(json(code = 401))
        assertEquals(401, (request(true).exceptionOrNull() as? ApiException)?.status)
        assertTrue(ApiClient.sessionExpired.value)
        assertFalse(ApiClient.isLoggedIn())
    }
}
