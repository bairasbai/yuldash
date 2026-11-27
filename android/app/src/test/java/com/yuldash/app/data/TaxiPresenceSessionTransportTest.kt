package com.yuldash.app.data

import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxiPresenceSessionTransportTest {
    private lateinit var server: MockWebServer
    private val previous = "header.eyJzdWIiOiIxMSJ9.synthetic"
    private val current = "header.eyJzdWIiOiIyMiJ9.synthetic"

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 5_000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.saveToken(previous)
    }

    @After fun cleanup() {
        ApiClient.testTimeoutMs = null
        ApiClient.resetForTest()
        server.shutdown()
    }

    @Test fun oldLineCannotSendItsPositionWithTheNewDriversBearer() = runBlocking {
        val oldLineOwner = ApiClient.queueSessionGeneration()
        // Hold an old service tick before it reaches the API client.
        ApiClient.saveToken(current)
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val stale = ApiClient.instantPresence(51.0, 57.0, expectedGeneration = oldLineOwner)
        assertTrue("Stale line tick must be rejected before sending", stale.isFailure)
        assertEquals(0, server.requestCount)

        val newLineOwner = ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.instantPresence(52.0, 58.0, expectedGeneration = newLineOwner).getOrThrow())
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        checkNotNull(request)
        assertEquals("POST", request.method)
        assertEquals("/instant/presence", request.path)
        assertEquals("Bearer $current", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(52.0, body.getDouble("lat"), 0.0)
        assertEquals(58.0, body.getDouble("lng"), 0.0)
        assertEquals(1, server.requestCount)
    }
}
