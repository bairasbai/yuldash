package com.yuldash.app.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverBookingDecisionApiTest {
    private lateinit var server: MockWebServer
    private val terms = "a".repeat(64)

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        startBookingCommandSession(server)
    }

    @After fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun request() = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    private fun generation() = ApiClient.queueSessionGeneration()
    private suspend fun pending() = DriverBookingDecisionApi.pending(generation()).getOrThrow()
    private fun json(body: String, code: Int = 200) = MockResponse().setBody(body).setResponseCode(code)
    private fun preview(status: String) = json(JSONObject().put("booking_status", status).put("terms_key", terms).toString())
    private fun role(role: String = "driver") = json(JSONObject().put("role", role).toString())

    @Test fun confirmLostReplyReopensWithNoBodyAndCannotBecomeADifferentAction() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Confirm).isFailure)
        assertEquals("/me", request().path)
        val first = request()
        assertEquals("/bookings/21/commands/v1/confirm", first.path)
        assertEquals(0L, first.bodySize)
        val saved = pending().single()
        assertEquals(saved.key, first.getHeader("Idempotency-Key"))

        startBookingCommandSession(server)
        assertEquals(saved, pending().single())
        assertEquals("/me", request().path)
        server.enqueue(json("""{"state":"unseen","scope":null,"ref_id":null,"current":null}"""))
        assertEquals(CommandIntent.State.Unknown, DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Decline).getOrThrow().state)
        assertEquals("GET", request().method)
        server.enqueue(json("""{"id":21,"ride_id":4,"passenger_id":8,"status":"cancelled"}"""))
        val applied = DriverBookingDecisionApi.repeat(generation(), saved).getOrThrow()
        assertEquals("cancelled", applied.bookingStatus)
        val repeat = request()
        assertEquals(first.path, repeat.path)
        assertEquals(saved.key, repeat.getHeader("Idempotency-Key"))
        assertEquals(0L, repeat.bodySize)
        assertTrue(pending().isEmpty())
    }

    @Test fun declineRequiresPendingTermsAndDoesNotRetryWithNewTerms() = runBlocking {
        server.enqueue(role())
        server.enqueue(preview("pending"))
        server.enqueue(json("""{"detail":{"ru":"Условия отмены изменились.","code":"cancel_terms_changed"}}""", 409))
        server.enqueue(json("""{"state":"rejected","scope":"booking.cancel","current":null}"""))
        val outcome = DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Decline).getOrThrow()
        assertEquals(CommandIntent.State.Rejected, outcome.state)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        val sent = request()
        assertEquals("/bookings/21/commands/v1/cancel", sent.path)
        val input = JSONObject(sent.body.readUtf8())
        assertEquals(terms, input.getString("expected_terms_key"))
        assertEquals("", input.getString("reason"))
        assertEquals("GET", request().method)
        server.enqueue(role())
        server.enqueue(preview("confirmed"))
        val failure = DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Decline).exceptionOrNull() as DriverDecisionFailure
        assertEquals(CommandIntent.Problem.Changed, failure.problem)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        server.enqueue(role("passenger"))
        val passengerFailure = DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Decline)
            .exceptionOrNull() as DriverDecisionFailure
        assertEquals(CommandIntent.Problem.Changed, passengerFailure.problem)
        assertEquals("/bookings/21/role", request().path)
        assertEquals(8, server.requestCount)
    }

    @Test fun declineLostReplyRepeatsSameKeyAndTermsAfterRestart() = runBlocking {
        server.enqueue(role())
        server.enqueue(preview("pending"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Decline).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        val first = request()
        val saved = pending().single()
        startBookingCommandSession(server)
        assertEquals(saved, pending().single())
        assertEquals("/me", request().path)
        server.enqueue(json("""{"id":21,"ride_id":4,"passenger_id":8,"status":"cancelled","declined":true}"""))
        assertTrue(DriverBookingDecisionApi.repeat(generation(), saved).getOrThrow().declined)
        val repeat = request()
        assertEquals(first.path, repeat.path)
        assertEquals(first.getHeader("Idempotency-Key"), repeat.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), repeat.body.readUtf8())
    }

    @Test fun oldServerDoesNotFallBackAndClosingSealsExactDecision() = runBlocking {
        server.enqueue(json("{}", 404))
        assertTrue(DriverBookingDecisionApi.decide(generation(), 21, DriverDecisionAction.Confirm).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/commands/v1/confirm", request().path)
        val saved = pending().single()
        server.enqueue(json("""{"state":"closed_no_effect","scope":"booking.confirm","current":null}"""))
        assertEquals(CommandIntent.State.Closed, DriverBookingDecisionApi.check(generation(), saved, true).getOrThrow().state)
        val close = request()
        val input = JSONObject(close.body.readUtf8())
        assertEquals("booking.confirm", input.getString("scope"))
        assertEquals(21, input.getInt("target_id"))
        assertEquals(saved.hash, input.getString("payload_hash"))
        assertTrue(DriverBookingDecisionApi.repeat(generation(), saved).isFailure)
        assertEquals(3, server.requestCount)
    }
}
