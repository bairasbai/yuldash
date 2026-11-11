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
