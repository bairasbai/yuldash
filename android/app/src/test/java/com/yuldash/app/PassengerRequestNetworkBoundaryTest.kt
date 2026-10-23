package com.yuldash.app

import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Real request creation API: malformed success must never become a phantom request with ID zero. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassengerRequestNetworkBoundaryTest {
    private lateinit var server: MockWebServer

    @Before fun prepare() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun create() = runBlocking {
        ApiClient.createRequest(
            fromCity = "Ургаза", toCity = "Бурибай", seats = 1, category = "regular",
            withKids = false, comment = "У остановки", maxPrice = 350,
            desiredAt = "2030-01-02T10:00:00+05:00",
        )
    }

    private fun rejectMalformed(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        val result = create()
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("POST", request?.method)
        assertEquals("/requests", request?.path)
        assertEquals("Exactly one creation attempt", 1, server.requestCount)
        assertTrue("Malformed success must stay a failure; body=$body, result=$result", result.isFailure)
    }

    @Test fun emptySuccessfulBodyDoesNotCreateARequest() = rejectMalformed("")
    @Test fun emptySuccessfulObjectDoesNotCreateARequest() = rejectMalformed("{}")
    @Test fun missingIdDoesNotCreateARequest() = rejectMalformed("""{"status":"active"}""")
    @Test fun zeroIdDoesNotCreateARequest() = rejectMalformed("""{"id":0}""")
    @Test fun negativeIdDoesNotCreateARequest() = rejectMalformed("""{"id":-1}""")
    @Test fun nullIdDoesNotCreateARequest() = rejectMalformed("""{"id":null}""")
    @Test fun booleanIdDoesNotCreateARequest() = rejectMalformed("""{"id":true}""")
    @Test fun nonNumericStringIdDoesNotCreateARequest() = rejectMalformed("""{"id":"missing"}""")
    @Test fun numericStringIdDoesNotCreateARequest() = rejectMalformed("""{"id":"42"}""")
    @Test fun fractionalIdDoesNotCreateARequest() = rejectMalformed("""{"id":42.5}""")
    @Test fun objectIdDoesNotCreateARequest() = rejectMalformed("""{"id":{"value":42}}""")
    @Test fun arrayIdDoesNotCreateARequest() = rejectMalformed("""{"id":[42]}""")

    private fun positiveId(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
            .setBody("""{"id":42}"""))
        assertEquals(42, create().getOrThrow())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/requests", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("Ургаза", body.getString("from_city"))
        assertEquals("Бурибай", body.getString("to_city"))
        assertEquals("У остановки", body.getString("comment"))
        assertEquals("2030-01-02T10:00:00+05:00", body.getString("desired_at"))
        assertEquals(1, server.requestCount)
    }

    @Test fun positiveIntegerIdWith200IsPreserved() = positiveId(200)
    @Test fun positiveIntegerIdWith201IsPreserved() = positiveId(201)

    @Test fun serverRefusalRemainsAnErrorAndManualRetryUsesTheSameData() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Попробуй позже"}"""))
        val failed = create()
        assertTrue(failed.exceptionOrNull() is ApiException)
        assertEquals(503, (failed.exceptionOrNull() as ApiException).status)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":42}"""))
        assertEquals(42, create().getOrThrow())
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val retry = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals(first.path, retry.path)
        assertEquals(first.body.readUtf8(), retry.body.readUtf8())
        assertEquals(2, server.requestCount)
    }
}
