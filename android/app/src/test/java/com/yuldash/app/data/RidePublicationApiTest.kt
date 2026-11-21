package com.yuldash.app.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
class RidePublicationApiTest {
    private lateinit var server: MockWebServer
    private val depart = "2030-01-02T10:00:00+05:00"
    private val body = JSONObject().put("from_city", "Сибай").put("to_city", "Акъяр").put("depart_at", depart)
        .put("recurrence", "weekly").put("waypoints", "Ургаза (Баймакский р-н)").put("pickup", "Test pickup")
    private val draft = RidePublicationIntent.Draft("a".repeat(64), depart, "ru")

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
    private suspend fun saved() = checkNotNull(RidePublicationApi.saved(generation()).getOrThrow())

    @Test fun lostSeriesReplyCanRecoverOneReceiptAndRepeatOnlyTheOtherDate() = runBlocking {
        val series = RideSeriesIntent.create(JSONObject(body.toString()).put("recurrence", "none"),
            listOf(depart, "2030-01-03T10:00:00+05:00"))
        val slot = RidePublicationApi.reserveSeries(generation(), series).getOrThrow()
        assertEquals("/me", request().path)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(RidePublicationApi.sendSeries(generation(), slot.key).isFailure)
        val sent = request()
        assertEquals("/rides/series", sent.path)
        assertEquals(slot.key, sent.getHeader("Idempotency-Key"))
        assertEquals(2, JSONObject(sent.body.readUtf8()).getJSONArray("departures").length())
        assertTrue(RidePublicationApi.publish(generation(), body, draft, null, false).isFailure)
        assertEquals(2, server.requestCount)
        startBookingCommandSession(server)
        assertEquals(slot, RidePublicationApi.savedSeries(generation()).getOrThrow())
        assertEquals("/me", request().path)
        val (first, second) = series.dates
        server.enqueue(MockResponse().setBody("""{"scope":"ride.create","state":"applied","ref_kind":"ride","ref_id":42,"result_code":"idempotency_result_gone","current":null}"""))
        server.enqueue(MockResponse().setBody("""{"scope":null,"state":"unseen","ref_id":null,"current":null}"""))
        val found = RidePublicationApi.checkSeries(generation(), slot.key).getOrThrow()
        assertEquals(listOf(second), found.pending.map { it.date })
        assertEquals(42, found.rows.first().result.rideId)
        assertEquals("/command-intents/" + first.commandKey, request().path)
        assertEquals("/command-intents/" + second.commandKey, request().path)
        server.enqueue(MockResponse().setBody("""{"published":1,"failed":0,"unresolved":0,"items":[{"depart_at":"${second.departureUtc}","outcome":"applied","ride_id":43,"already":false}]}"""))
        val applied = RidePublicationApi.sendSeries(generation(), slot.key).getOrThrow()
        val retry = request()
        assertEquals(slot.key, retry.getHeader("Idempotency-Key"))
        assertEquals("/rides/series", retry.path)
        val dates = JSONObject(retry.body.readUtf8()).getJSONArray("departures")
        assertEquals(1, dates.length())
        assertEquals(second.departureUtc, dates.getString(0))
        assertEquals(listOf(42, 43), applied.rows.map { it.result.rideId })
        RidePublicationApi.releaseSeries(generation(), slot.key).getOrThrow()
        assertNull(RidePublicationApi.savedSeries(generation()).getOrThrow())
    }

    @Test fun lostResponseAndReopenedSessionRepeatExactKeyAndWireBody() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(RidePublicationApi.publish(generation(), body, draft, null, retry = false).isFailure)
        assertEquals("/me", request().path)
        val first = request()
        val originalBytes = first.body.readUtf8()
        val slot = saved()
        assertEquals(slot.key, first.getHeader("Idempotency-Key"))
        assertEquals(CommandFingerprint.create("ride.create", null, body).payloadHash, slot.hash)
        startBookingCommandSession(server)
        assertEquals(slot, saved())
        assertEquals("/me", request().path)

        val edited = JSONObject(body.toString()).put("recurrence", "none")
        val changed = RidePublicationApi.publish(generation(), edited, draft, slot.key, retry = true)
        assertEquals(CommandIntent.Problem.Changed, (changed.exceptionOrNull() as RidePublicationIntent.Failure).problem)
        assertEquals(3, server.requestCount)

        server.enqueue(MockResponse().setBody("""{"id":42,"driver_id":7,"status":"cancelled"}"""))
        val repeated = RidePublicationApi.publish(generation(), body, slot.draft, slot.key, retry = true).getOrThrow()
        assertEquals("cancelled", repeated.outcome.ride?.status)
        val replay = request()
        assertEquals("/rides/commands/v1", replay.path)
        assertEquals(slot.key, replay.getHeader("Idempotency-Key"))
        assertEquals(originalBytes, replay.body.readUtf8())
        assertEquals(CommandIntent.State.Applied, saved().state)
    }

    @Test fun oldServerNeverFallsBackAndCloseUsesNullTargetAndStoredHash() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(RidePublicationApi.publish(generation(), body, draft, null, retry = false).isFailure)
        assertEquals("/me", request().path)
        assertEquals("/rides/commands/v1", request().path)
        assertEquals(2, server.requestCount)
        val slot = saved()
        server.enqueue(MockResponse().setBody("""{"scope":"ride.create","state":"closed_no_effect","current":null}"""))
        assertEquals(CommandIntent.State.Closed, RidePublicationApi.check(generation(), slot, close = true).getOrThrow().state)
        val close = request()
        val sent = JSONObject(close.body.readUtf8())
        assertEquals("/command-intents/" + slot.key + "/close", close.path)
        assertEquals("ride.create", sent.getString("scope"))
        assertTrue(sent.has("target_id") && sent.isNull("target_id"))
        assertEquals(slot.hash, sent.getString("payload_hash"))
    }

    @Test fun restoredSeriesRefusalRetainsBothLanguages() = runBlocking {
        val intent = RideSeriesIntent.create(JSONObject(body.toString()).put("recurrence", "none"), listOf(depart))
        val slot = RidePublicationApi.reserveSeries(generation(), intent).getOrThrow()
        assertEquals("/me", request().path)
        server.enqueue(MockResponse().setBody("""{"scope":"ride.create","state":"rejected","current":null,
            "result_code":"time_passed","ru":"Время выезда уже прошло","ba":"Сығыу ваҡыты үтеп киткән"}"""))
        val verified = RidePublicationApi.checkSeries(generation(), slot.key).getOrThrow()
        assertEquals("/command-intents/" + intent.dates.single().commandKey, request().path)
        val refusal = verified.rows.single().result
        assertEquals(CommandIntent.State.Rejected, refusal.state)
        assertEquals("Время выезда уже прошло", refusal.ru)
        assertEquals("Сығыу ваҡыты үтеп киткән", refusal.ba)
        assertEquals(verified, RidePublicationApi.savedSeries(generation()).getOrThrow())
    }

    @Test fun latePublishResponseCannotOverwriteCloseOutcome() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":42,"driver_id":7,"status":"active"}""")
            .setBodyDelay(550, TimeUnit.MILLISECONDS))
        val pending = async(Dispatchers.IO) { RidePublicationApi.publish(generation(), body, draft, null, retry = false) }
        assertEquals("/me", request().path)
        val sent = request()
        val slot = saved()
        assertEquals(slot.key, sent.getHeader("Idempotency-Key"))
        server.enqueue(MockResponse().setBody("""{"scope":"ride.create","state":"closed_no_effect","current":null}"""))
        assertEquals(CommandIntent.State.Closed, RidePublicationApi.check(generation(), slot, close = true).getOrThrow().state)
        assertTrue(request().path!!.endsWith("/close"))
        val failure = pending.await().exceptionOrNull() as RidePublicationIntent.Failure
        assertEquals(CommandIntent.Problem.Superseded, failure.problem)
        assertEquals(CommandIntent.State.Closed, saved().state)
    }

    @Test fun deletionPurgesOriginalPublicationWithoutUsingTheReplacementAccount() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(RidePublicationApi.publish(generation(), body, draft, null, retry = false).isFailure)
        request()
        request()
        val originalSlot = saved()
        val originalGeneration = generation()
        server.enqueue(MockResponse().setBody("""{"ok":true}""").setBodyDelay(400, TimeUnit.MILLISECONDS))
        val deleting = async(Dispatchers.IO) { ApiClient.deleteAccount(originalGeneration) }
        assertEquals("/me/delete", request().path)
        ApiClient.saveToken("test.eyJzdWIiOiI5In0.signature")
        assertTrue(RidePublicationApi.publish(originalGeneration, body, draft, originalSlot.key, retry = true).isFailure)
        assertTrue(deleting.await().isFailure)
        startBookingCommandSession(server)
        assertNull(RidePublicationApi.saved(generation()).getOrThrow())
        assertEquals("/me", request().path)
        assertEquals(4, server.requestCount)
    }

    @Test fun confirmedRefusalKeepsServerExplanation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"detail":{"ru":"Время выезда уже прошло","ba":"Сығыу ваҡыты үткән"}}"""))
        server.enqueue(MockResponse().setBody("""{"scope":"ride.create","state":"rejected","current":null}"""))
        val result = RidePublicationApi.publish(generation(), body, draft, null, retry = false).getOrThrow().outcome
        assertEquals(CommandIntent.State.Rejected, result.state)
        assertEquals("Время выезда уже прошло", result.rejectionMessage)
        assertEquals(CommandIntent.State.Rejected, saved().state)
    }

    @Test fun receiptRejectsWrongOwnerAndReferenceAndRecognizesGoneResult() {
        val receipt = JSONObject("""{"scope":"ride.create","state":"applied","ref_kind":"ride","ref_id":42,
            "current":{"id":42,"driver_id":8,"status":"done"}}""")
        assertTrue(runCatching { RidePublicationOutcome.fromReceipt(receipt, 7, false) }.isFailure)
        assertEquals("done", RidePublicationOutcome.fromReceipt(receipt, 8, false).ride?.status)
        receipt.put("ref_id", 43)
        assertTrue(runCatching { RidePublicationOutcome.fromReceipt(receipt, 8, false) }.isFailure)
        receipt.put("current", JSONObject.NULL).put("result_code", "idempotency_result_gone")
        val gone = RidePublicationOutcome.fromReceipt(receipt, 8, false)
        assertEquals(CommandIntent.State.Applied, gone.state)
        assertNull(gone.ride)
    }
}
