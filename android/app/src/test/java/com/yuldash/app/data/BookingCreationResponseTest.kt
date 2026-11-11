package com.yuldash.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookingCreationResponseTest {
    private lateinit var server: MockWebServer
    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { ApiClient.resetForTest(); server.shutdown() }
    private fun respond(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }
    @Test fun preservesEveryServerActiveBookingStatus() = runBlocking {
        startBookingCommandSession(server)
        for ((i, status) in listOf("pending", "confirmed", "onboard", "cancelled", "done").withIndex()) {
            val rideId = 7 + i
            server.enqueue(bookingCommandResponse(rideId, status))
            assertEquals(BookingCreatedDto(42, status), ApiClient.bookWithStatus(rideId, 1).getOrThrow())
            if (i == 0) assertEquals("/me", server.takeRequest().path)
            val sent = server.takeRequest()
            assertEquals("/bookings/commands/v1", sent.path)
            assertTrue(sent.getHeader("Idempotency-Key")?.startsWith("cmd1_") == true)
        }
    }
    @Test fun malformedBookingMustNotBecomeActiveSuccess() = runBlocking {
        startBookingCommandSession(server)
        for ((index, body) in listOf("{}", """{"id":42}""", """{"id":0,"status":"confirmed"}""", """{"id":42,"status":"unknown"}""").withIndex()) {
            respond(body)
            assertTrue("Unexpected success for $body", ApiClient.bookWithStatus(7 + index, 1).isFailure)
        }
        assertEquals(5, server.requestCount)
    }
    @Test fun publicationRequiresRealPositiveId() = runBlocking {
        for (body in listOf("{}", """{"id":0}""", """{"id":-1}""")) {
            respond(body)
            assertTrue(ApiClient.publishRide("A", "B", "2030-01-02T10:00:00Z", 2, 750, "").isFailure)
        }
    }
}
