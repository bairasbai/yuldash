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
    private suspend fun pending() = BookingDecisionApi.pending(generation()).getOrThrow()
    private fun json(body: String, code: Int = 200) = MockResponse().setBody(body).setResponseCode(code)
    private fun preview(status: String) = json(JSONObject().put("booking_status", status).put("terms_key", terms).toString())
    private fun role(role: String = "driver") = json(JSONObject().put("role", role).toString())

    @Test fun confirmLostReplyReopensWithNoBodyAndCannotBecomeADifferentAction() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Confirm).isFailure)
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
        assertEquals(CommandIntent.State.Unknown, BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Decline).getOrThrow().state)
        assertEquals("GET", request().method)
        server.enqueue(json("""{"id":21,"ride_id":4,"passenger_id":8,"status":"cancelled"}"""))
        val applied = BookingDecisionApi.repeat(generation(), saved).getOrThrow()
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
        val outcome = BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Decline).getOrThrow()
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
        val failure = BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Decline).exceptionOrNull() as BookingDecisionFailure
        assertEquals(CommandIntent.Problem.Changed, failure.problem)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        server.enqueue(role("passenger"))
        val passengerFailure = BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Decline)
            .exceptionOrNull() as BookingDecisionFailure
        assertEquals(CommandIntent.Problem.Changed, passengerFailure.problem)
        assertEquals("/bookings/21/role", request().path)
        assertEquals(8, server.requestCount)
    }

    @Test fun declineLostReplyRepeatsSameKeyAndTermsAfterRestart() = runBlocking {
        server.enqueue(role())
        server.enqueue(preview("pending"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Decline).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        val first = request()
        val saved = pending().single()
        startBookingCommandSession(server)
        assertEquals(saved, pending().single())
        assertEquals("/me", request().path)
        server.enqueue(json("""{"id":21,"ride_id":4,"passenger_id":8,"status":"cancelled","declined":true}"""))
        assertTrue(BookingDecisionApi.repeat(generation(), saved).getOrThrow().declined)
        val repeat = request()
        assertEquals(first.path, repeat.path)
        assertEquals(first.getHeader("Idempotency-Key"), repeat.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), repeat.body.readUtf8())
    }

    @Test fun oldServerDoesNotFallBackAndClosingSealsExactDecision() = runBlocking {
        server.enqueue(json("{}", 404))
        assertTrue(BookingDecisionApi.decide(generation(), 21, BookingDecisionAction.Confirm).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/commands/v1/confirm", request().path)
        val saved = pending().single()
        server.enqueue(json("""{"state":"closed_no_effect","scope":"booking.confirm","current":null}"""))
        assertEquals(CommandIntent.State.Closed, BookingDecisionApi.check(generation(), saved, true).getOrThrow().state)
        val close = request()
        val input = JSONObject(close.body.readUtf8())
        assertEquals("booking.confirm", input.getString("scope"))
        assertEquals(21, input.getInt("target_id"))
        assertEquals(saved.hash, input.getString("payload_hash"))
        assertTrue(BookingDecisionApi.repeat(generation(), saved).isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun passengerConsentPrecedesPostAndReplaysExactReasonAfterRestart() = runBlocking {
        server.enqueue(role("passenger"))
        server.enqueue(json("""{"booking_status":"confirmed","consequence":"late","terms_key":"$terms"}"""))
        val shown = BookingDecisionApi.preparePassengerCancellation(generation(), 21, "confirmed").getOrThrow()
        assertEquals("late", shown.consequence)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        assertEquals(3, server.requestCount)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(BookingDecisionApi.cancelPassenger(generation(), shown, "found_other").isFailure)
        val first = request()
        val saved = BookingDecisionApi.pending(generation(), passenger = true).getOrThrow().single()
        assertEquals("found_other", saved.reason)
        assertTrue(pending().isEmpty())
        val firstWire = first.body.readUtf8()
        assertEquals("found_other", JSONObject(firstWire).getString("reason"))
        assertEquals(terms, JSONObject(firstWire).getString("expected_terms_key"))

        startBookingCommandSession(server)
        OfflineTripSnapshots.activate(7, null, generation())
        assertEquals(saved, BookingDecisionApi.pending(generation(), passenger = true).getOrThrow().single())
        assertEquals("/me", request().path)
        assertTrue(BookingDecisionApi.cancelPassenger(generation(), shown, "").isFailure)
        server.enqueue(json("""{"id":21,"ride_id":4,"passenger_id":7,"status":"cancelled","contact_then_cancel":true}"""))
        val result = BookingDecisionApi.repeat(generation(), saved).getOrThrow()
        val repeat = request()
        assertEquals("/bookings/21/commands/v1/cancel", repeat.path)
        assertEquals(first.getHeader("Idempotency-Key"), repeat.getHeader("Idempotency-Key"))
        assertEquals(firstWire, repeat.body.readUtf8())
        assertEquals("cancelled", result.bookingStatus)
        assertTrue(result.contactThenCancel)
        assertEquals("passenger", OfflineTripSnapshots.terminal(21)?.role)
        assertTrue(BookingDecisionApi.pending(generation(), passenger = true).getOrThrow().isEmpty())
    }

    @Test fun changedPassengerStageNeverSendsCancellation() = runBlocking {
        server.enqueue(role("passenger"))
        server.enqueue(json("""{"booking_status":"onboard","consequence":"onboard_no_cancel","terms_key":"$terms"}"""))
        val failure = BookingDecisionApi.preparePassengerCancellation(generation(), 21, "confirmed")
            .exceptionOrNull() as BookingDecisionFailure
        assertEquals(CommandIntent.Problem.Changed, failure.problem)
        assertEquals("/me", request().path)
        assertEquals("/bookings/21/role", request().path)
        assertEquals("/bookings/21/cancel-preview", request().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun changedTermsAfterConsentRejectTheOriginalRequestWithoutTakingNewTerms() = runBlocking {
        server.enqueue(role("passenger"))
        server.enqueue(json("""{"booking_status":"pending","consequence":"early","terms_key":"$terms"}"""))
        val shown = BookingDecisionApi.preparePassengerCancellation(generation(), 21, "pending").getOrThrow()
        server.enqueue(json("""{"detail":{"code":"cancel_terms_changed","ru":"Условия отмены изменились."}}""", 409))
        server.enqueue(json("""{"state":"rejected","scope":"booking.cancel","ref_kind":null,"ref_id":null,"current":null,"result_code":"cancel_terms_changed"}"""))
        val result = BookingDecisionApi.cancelPassenger(generation(), shown, "").getOrThrow()
        assertEquals(CommandIntent.State.Rejected, result.state)
        assertEquals("cancel_terms_changed", result.rejectionCode)
        repeat(3) { assertEquals("GET", request().method) }
        val posted = request()
        assertEquals("/bookings/21/commands/v1/cancel", posted.path)
        assertEquals(terms, JSONObject(posted.body.readUtf8()).getString("expected_terms_key"))
        assertEquals("/command-intents/" + posted.getHeader("Idempotency-Key"), request().path)
        assertEquals(5, server.requestCount)
    }

    @Test fun gonePassengerResultSealsOfflinePassportBeforeClearingSavedRequest() = runBlocking {
        OfflineTripSnapshots.activate(7, null, generation())
        val initialEpoch = requireNotNull(OfflineTripSnapshots.gate.beginRead(21, SnapshotAuthorityKind.DETAILS)).epoch
        server.enqueue(role("passenger"))
        server.enqueue(json("""{"booking_status":"confirmed","consequence":"early","terms_key":"$terms"}"""))
        val shown = BookingDecisionApi.preparePassengerCancellation(generation(), 21, "confirmed").getOrThrow()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(BookingDecisionApi.cancelPassenger(generation(), shown, "").isFailure)
        val saved = BookingDecisionApi.pending(generation(), passenger = true).getOrThrow().single()
        server.enqueue(json("""{"state":"applied","scope":"booking.cancel","ref_kind":"booking","ref_id":21,"current":null,"result_code":"idempotency_result_gone"}"""))
        val recovered = BookingDecisionApi.check(generation(), saved, false).getOrThrow()
        assertEquals(CommandIntent.State.Applied, recovered.state)
        assertNull(recovered.bookingStatus)
        assertTrue(requireNotNull(OfflineTripSnapshots.gate.beginRead(21, SnapshotAuthorityKind.DETAILS)).epoch > initialEpoch)
        assertTrue(BookingDecisionApi.pending(generation(), passenger = true).getOrThrow().isEmpty())
    }
}
