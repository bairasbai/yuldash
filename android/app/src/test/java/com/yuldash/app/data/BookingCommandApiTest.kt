package com.yuldash.app.data

import com.yuldash.app.BookingRecoveryState
import com.yuldash.app.PreparedBookingRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookingCommandApiTest {
    private lateinit var server: MockWebServer
    private val other = "test.eyJzdWIiOiI5In0.signature"

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
    private suspend fun slot() = BookingCommandApi.savedBookingCommands(generation()).getOrThrow().single()

    @Test fun forAnotherDoesNotSendBookingToAnOldServer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val failure = ApiClient.bookWithStatus(73, 1, travelerName = "Айгөл").exceptionOrNull() as CommandIntent.Failure
        assertEquals("booking_traveler_unsupported", failure.rejectionCode)
        assertEquals("/me", request().path)
        assertEquals("/bookings/capabilities", request().path)
        assertEquals(2, server.requestCount)
        assertTrue(BookingCommandApi.savedBookingCommands(generation()).getOrThrow().isEmpty())
    }

    @Test fun forAnotherKeepsItsEndpointKeyAndExactNameAfterLostReply() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"traveler_name":true}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val result = ApiClient.bookWithStatus(73, 1, travelerName = "Айгөл")
        assertEquals(CommandIntent.Problem.Unknown, (result.exceptionOrNull() as CommandIntent.Failure).problem)
        assertEquals("/me", request().path)
        assertEquals("/bookings/capabilities", request().path)
        val first = request()
        val stored = slot()
        val original = first.body.readUtf8()
        assertEquals("/bookings/for-another/commands/v1", first.path)
        assertEquals("Айгөл", JSONObject(original).getString("traveler_name"))
        assertEquals(stored.key, first.getHeader("Idempotency-Key"))
        assertTrue(stored.bookingTerms?.forAnother == true)

        assertTrue(ApiClient.bookWithStatus(73, 1, previousKey = stored.key, retry = true).isFailure)
        assertTrue(ApiClient.bookWithStatus(73, 1, previousKey = stored.key, retry = true, travelerName = "Зилә").isFailure)
        assertEquals(3, server.requestCount)
        server.enqueue(bookingCommandResponse(73, "pending"))
        assertEquals("pending", ApiClient.bookWithStatus(73, 1, previousKey = stored.key,
            retry = true, travelerName = "Айгөл").getOrThrow().status)
        val replay = request()
        assertEquals(first.path, replay.path)
        assertEquals(stored.key, replay.getHeader("Idempotency-Key"))
        assertEquals(original, replay.body.readUtf8())
    }

    @Test fun lostReplyAndRestartKeepExactKeyAndReturnActualCancellation() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = ApiClient.bookWithStatus(73, 2, pickupStop = "Өфө").exceptionOrNull() as CommandIntent.Failure
        assertEquals(CommandIntent.Problem.Unknown, failure.problem)
        assertEquals("/me", request().path)
        val first = request()
        val stored = slot()
        val bytes = first.body.readUtf8()
        assertEquals(stored.key, first.getHeader("Idempotency-Key"))
        assertEquals(stored.hash, CommandFingerprint.create("booking.create", 73, JSONObject(bytes)).payloadHash)

        startBookingCommandSession(server)
        assertEquals(stored, slot())
        assertEquals("/me", request().path)
        assertTrue(ApiClient.bookWithStatus(73, 1, previousKey = stored.key, retry = true).isFailure)
        assertTrue(ApiClient.bookWithStatus(73, 2, pickupStop = "Өфө").isFailure)
        assertEquals(3, server.requestCount)
        server.enqueue(bookingCommandResponse(73, "cancelled"))
        assertEquals("cancelled", ApiClient.bookWithStatus(73, 2, pickupStop = "Өфө", previousKey = stored.key,
            retry = true).getOrThrow().status)
        val replay = request()
        assertEquals("/bookings/commands/v1", replay.path)
        assertEquals(stored.key, replay.getHeader("Idempotency-Key"))
        assertEquals(bytes, replay.body.readUtf8())
        assertEquals(CommandIntent.State.Applied, slot().state)
    }

    @Test fun oldServerDoesNotTriggerLegacyBookingAndCloseUsesSavedHash() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(ApiClient.bookWithStatus(73, 1).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/bookings/commands/v1", request().path)
        assertEquals(2, server.requestCount)
        val saved = slot()
        server.enqueue(MockResponse().setBody("""{"state":"closed_no_effect","scope":"booking.create","current":null}"""))
        assertEquals(CommandIntent.State.Closed, BookingCommandApi.reconcileBookingCommand(generation(), saved, true)
            .getOrThrow().state)
        val close = request()
        assertEquals("/command-intents/${saved.key}/close", close.path)
        assertEquals(saved.hash, JSONObject(close.body.readUtf8()).getString("payload_hash"))
        assertEquals(CommandIntent.State.Closed, slot().state)
    }

    @Test fun preparedRetryReusesItsBodyAndCannotAttachAnotherFormToTheKey() = runBlocking {
        var prepared: PreparedBookingRequest? = null
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val failure = ApiClient.bookWithStatus(73, 2, pickupStop = "Өфө", dropoffStop = "Мәләүез",
            expectedTermsVersion = 4, onPrepared = { key, body -> prepared = PreparedBookingRequest(73, key, body) })
            .exceptionOrNull() as CommandIntent.Failure
        assertEquals("/me", request().path)
        val sent = request()
        val original = checkNotNull(prepared)
        val recovery = BookingRecoveryState(generation())
        recovery.failed(failure, original)
        assertSame(original, recovery.preparedRetry)
        assertEquals(original.key, sent.getHeader("Idempotency-Key"))
        assertEquals(original.wireBody, sent.body.readUtf8())

        val conflict = ApiClient.bookWithStatus(73, 1, pickupStop = "Сибай",
            onPrepared = { _, _ -> fail("Another form cannot prepare an existing key") })
        assertTrue(conflict.isFailure)
        assertEquals(2, server.requestCount)
        val otherForm = BookingRecoveryState(generation())
        otherForm.failed(checkNotNull(conflict.exceptionOrNull()))
        assertNull(otherForm.preparedRetry)
        otherForm.failed(failure, PreparedBookingRequest(73, "different-key", original.wireBody))
        assertNull(otherForm.preparedRetry)

        server.enqueue(bookingCommandResponse(73, "pending"))
        assertEquals("pending", BookingCommandApi.repeatPrepared(73, original.wireBody, generation(), original.key)
            .getOrThrow().status)
        val repeated = request()
        assertEquals(original.key, repeated.getHeader("Idempotency-Key"))
        assertEquals(original.wireBody, repeated.body.readUtf8())
        recovery.apply(BookingCommandOutcome(CommandIntent.State.Applied))
        assertNull(recovery.preparedRetry)

        val staleTerms = BookingRecoveryState(generation())
        staleTerms.failed(failure, original)
        staleTerms.apply(BookingCommandOutcome(CommandIntent.State.Rejected, rejectionCode = "ride_terms_changed"))
        assertFalse(staleTerms.canSubmit)
        assertNull(staleTerms.preparedRetry)
        val reopened = BookingRecoveryState(generation())
        reopened.slot = failure.slot?.copy(state = CommandIntent.State.Rejected)
        reopened.apply(BookingCommandOutcome(CommandIntent.State.Rejected, rejectionCode = "price_changed"))
        assertFalse(reopened.canSubmit)
        reopened.freshTermsShown()
        assertTrue(reopened.canSubmit)
    }

    @Test fun deletionConfirmationPurgesOriginalAccountEvenWhenAnotherLogsIn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(ApiClient.bookWithStatus(73, 1).isFailure)
        request()
        request()
        slot()
        val captured = generation()
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setBodyDelay(350, TimeUnit.MILLISECONDS))
        val result = async(Dispatchers.IO) { ApiClient.deleteAccount(captured) }
        assertEquals("/me/delete", request().path)
        ApiClient.saveToken(other)
        assertTrue(result.await().isFailure)
        assertEquals(other, ApiClient.currentToken())
        assertTrue(ApiClient.bookWithStatus(73, 1, expectedGeneration = captured).isFailure)
        assertEquals(3, server.requestCount)
        startBookingCommandSession(server)
        assertTrue(BookingCommandApi.savedBookingCommands(generation()).getOrThrow().isEmpty())
        assertEquals("/me", request().path)
    }

    @Test fun reservedMetadataContainsNoGuardianOrMeetingText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(ApiClient.bookWithStatus(73, 1, minorPassenger = true,
            guardianName = "Тестовый человек", guardianPhone = "000000").isFailure)
        val app = RuntimeEnvironment.getApplication()
        val key = slot().key
        val raw = app.getSharedPreferences("yuldash_booking_commands", 0).all.values
            .filterIsInstance<String>().single { it.contains(key) }
        assertTrue(raw.contains(key))
        assertFalse(raw.contains("Тестовый человек"))
        assertFalse(raw.contains("minor_guardian_phone"))
    }

    @Test fun corruptMetadataCannotBlockDeletionOrTreatFalseAnd204AsConfirmation() = runBlocking {
        val namespace = CommandIntent.account(checkNotNull(ApiClient.testBaseUrl), 7, "2026-01-01T00:00:00Z")
        val prefKey = CommandIntent.prefKey(namespace)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("yuldash_booking_commands", 0)
        assertTrue(prefs.edit().putString(prefKey, "malformed metadata").commit())
        // Confirm the owner through the first /me queued by setup.
        assertTrue(BookingCommandApi.savedBookingCommands(generation()).isFailure)
        assertEquals("/me", request().path)
        for (response in listOf(MockResponse().setBody("""{"ok":false}"""), MockResponse().setResponseCode(204))) {
            server.enqueue(response)
            assertTrue(ApiClient.deleteAccount().isFailure)
            assertEquals("/me/delete", request().path)
            assertTrue(prefs.contains(prefKey))
            assertEquals(COMMAND_TEST_TOKEN, ApiClient.currentToken())
        }
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(ApiClient.deleteAccount().isSuccess)
        assertEquals("/me/delete", request().path)
        assertFalse(prefs.contains(prefKey))
        assertNull(ApiClient.currentToken())
    }

    @Test fun deletionInFlightCannotRaceWithTheFirstBookingReservation() = runBlocking {
        assertTrue(BookingCommandApi.savedBookingCommands(generation()).getOrThrow().isEmpty())
        assertEquals("/me", request().path)
        val captured = generation()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/me/delete") return MockResponse().setResponseCode(500)
                return if (release.await(5, TimeUnit.SECONDS)) MockResponse().setBody("""{"ok":true}""")
                else MockResponse().setResponseCode(504)
            }
        }
        val result = async(Dispatchers.IO) { ApiClient.deleteAccount(captured) }
        try {
            assertEquals("/me/delete", request().path)
            val booking = ApiClient.bookWithStatus(73, 1, expectedGeneration = captured).exceptionOrNull()
            assertEquals(CommandIntent.Problem.Superseded, (booking as? CommandIntent.Failure)?.problem)
            assertEquals(2, server.requestCount)
        } finally { release.countDown() }
        assertTrue(result.await().isSuccess)
    }

    @Test fun legacyConflictOffersOnlyIndependentlyVerifiedOwnBooking() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"detail":{"code":"pickup_stop_conflict","message":"conflict","booking_id":42}}"""))
        server.enqueue(MockResponse().setBody("""{"state":"unseen","scope":null}"""))
        server.enqueue(MockResponse().setBody("""{"id":42,"role":"passenger","ride_id":73,"status":"confirmed"}"""))
        val error = ApiClient.bookWithStatus(73, 1).exceptionOrNull() as CommandIntent.Failure
        assertEquals(CommandIntent.Problem.Unknown, error.problem)
        assertEquals(BookingCreatedDto(42, "confirmed"), error.existingBooking)
        assertEquals(CommandIntent.State.Unknown, slot().state)
        assertEquals("/me", request().path)
        assertEquals("/bookings/commands/v1", request().path)
        assertTrue(checkNotNull(request().path).startsWith("/command-intents/"))
        assertEquals("/bookings/42/details", request().path)
    }
}
