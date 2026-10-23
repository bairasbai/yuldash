package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real wire requests; a dropped reply is never mistaken for an acknowledgement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientSosRetryIntentTest {
    private lateinit var server: MockWebServer
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(app)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.synthetic")
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        ApiClient.testBaseUrl = "http://127.0.0.1:${server.port}"
        ApiClient.testTimeoutMs = 700
    }
    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun request(): RecordedRequest = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    private fun key(request: RecordedRequest): String = checkNotNull(request.getHeader("Idempotency-Key"))
        .also { assertTrue(it.matches(Regex("[A-Za-z0-9_-]{16,128}"))) }
    private suspend fun lost(note: String = "Synthetic emergency", booking: Int = 42, lat: Double? = null): RecordedRequest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(ApiClient.sos("other", note, bookingId = booking, lat = lat, lng = lat).isFailure)
        return request()
    }
    @Test fun lostReplyReusesKeyForExplicitSameBodyRetry() = runBlocking {
        val first = lost()
        val second = lost()
        assertEquals(first.body.clone().readUtf8(), second.body.clone().readUtf8())
        assertEquals(key(first), key(second))
        assertEquals(2, server.requestCount)
    }
    @Test fun pendingSurvivesApiRestartWithoutSendingAutomatically() = runBlocking {
        val first = lost()
        ApiClient.init(app)
        assertEquals(1, server.requestCount)
        assertEquals(key(first), key(lost()))
    }
    @Test fun changedBodyStartsAnotherIntent() = runBlocking {
        assertNotEquals(key(lost()), key(lost(note = "A different emergency")))
    }
    @Test fun changedFreshLocationStartsAnotherIntent() = runBlocking {
        assertNotEquals(key(lost(lat = 54.0)), key(lost(lat = 54.001)))
    }
    @Test fun changedBookingStartsAnotherIntent() = runBlocking {
        assertNotEquals(key(lost()), key(lost(booking = 43)))
    }
    @Test fun explicitLoginEvenSameAccountCannotReuseOldIntent() = runBlocking {
        val first = lost()
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.synthetic")
        assertNotEquals(key(first), key(lost()))
    }
    @Test fun acknowledgedIntentIsNotReusedForANewSignal() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"id\":7,\"status\":\"new\"}"))
        assertTrue(ApiClient.sos("other", "Synthetic emergency", bookingId = 42).isSuccess)
        val first = request()
        assertNotEquals(key(first), key(lost()))
    }
    @Test fun temporaryRateLimitDoesNotClearPendingIntent() = runBlocking {
        val first = lost()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("{}"))
        assertTrue(ApiClient.sos("other", "Synthetic emergency", bookingId = 42).isFailure)
        assertEquals(key(first), key(request()))
        assertEquals(key(first), key(lost()))
    }
    private suspend fun lostRoadside(booking: Int = 42): RecordedRequest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(ApiClient.roadsideHelp(booking, null, null, "Synthetic roadside emergency").isFailure)
        return request()
    }
    @Test fun interleavedSosAndRoadsideKeepSeparateIdentitiesAcrossRestart() = runBlocking {
        val sos = key(lost())
        val roadside = key(lostRoadside())
        assertNotEquals(sos, roadside)
        assertEquals(sos, key(lost()))
        assertEquals(roadside, key(lostRoadside()))
        ApiClient.init(app)
        assertEquals(sos, key(lost()))
        assertEquals(roadside, key(lostRoadside()))
    }
}
