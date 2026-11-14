package com.yuldash.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriverStageApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        startBookingCommandSession(server)
    }

    @After
    fun cleanup() {
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun reply(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private fun request() = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))

    @Test
    fun lostBoardedKeepsItsKeyAndBlocksCompletionUntilReconciled() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(DriverStageApi.advance(generation, 42, DriverStageApi.Stage.Boarded).isFailure)
        assertEquals("/me", request().path)
        val boarding = request()
        val saved = DriverStageApi.pending(generation).getOrThrow().single()
        assertEquals(saved.slot.key, boarding.getHeader("Idempotency-Key"))
        assertEquals("/bookings/42/commands/v1/driver-status", boarding.path)
        val body = boarding.body.readUtf8()
        assertEquals("""{"status":"boarded"}""", body)

        reply("""{"state":"unseen","scope":null,"ref_id":null,"ref_kind":null,"current":null}""")
        assertEquals(CommandIntent.State.Unknown, DriverStageApi.advance(generation, 42, DriverStageApi.Stage.Done).getOrThrow().state)
        assertEquals("GET", request().method)

        reply("""{"ok":true,"id":42,"status":"onboard"}""")
        reply("""{"role":"driver","status":"onboard","driver_phase":"arriving","onboard_at":"2026-11-14T03:00:00","boarding_undo_sec":246}""")
        val changed = DriverStageApi.repeat(generation, saved).getOrThrow()
        val replay = request()
        assertEquals(saved.slot.key, replay.getHeader("Idempotency-Key"))
        assertEquals(body, replay.body.readUtf8())
        assertEquals("/bookings/42/role", request().path)
        assertEquals(246, changed.trip?.boardingUndoSec)
        assertTrue(DriverStageApi.pending(generation).getOrThrow().isEmpty())
    }

    @Test
    fun failedRoleReadAfterCompletionKeepsTheOriginalCommand() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        reply("""{"ok":true,"id":42,"status":"done"}""")
        reply("""{"detail":"not found"}""", 404)
        assertTrue(DriverStageApi.advance(generation, 42, DriverStageApi.Stage.Done).isFailure)
        assertEquals("/me", request().path)
        val action = request()
        assertEquals("/bookings/42/role", request().path)
        val saved = DriverStageApi.pending(generation).getOrThrow().single()
        assertEquals(saved.slot.key, action.getHeader("Idempotency-Key"))

        reply("""{"state":"applied","scope":"booking.driver_status","ref_id":42,"ref_kind":"booking","current":{"id":42}}""")
        reply("""{"role":"driver","status":"done","driver_phase":""}""")
        assertEquals("done", DriverStageApi.check(generation, saved, close = false).getOrThrow().trip?.status)
        assertEquals("GET", request().method)
        assertEquals("GET", request().method)
        assertTrue(DriverStageApi.pending(generation).getOrThrow().isEmpty())
    }

    @Test
    fun explicitResultGoneIsDistinctFromAnUnverifiedRole() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(DriverStageApi.advance(generation, 42, DriverStageApi.Stage.Done).isFailure)
        assertEquals("/me", request().path)
        assertEquals("POST", request().method)
        val saved = DriverStageApi.pending(generation).getOrThrow().single()

        reply("""{"state":"applied","scope":"booking.driver_status","ref_id":42,"ref_kind":"booking","current":null,"result_code":"idempotency_result_gone"}""")
        val result = DriverStageApi.check(generation, saved, close = false).getOrThrow()
        assertEquals("GET", request().method)
        assertEquals("idempotency_result_gone", result.code)
        assertEquals(null, result.trip)
        assertEquals(3, server.requestCount)
        assertTrue(DriverStageApi.pending(generation).getOrThrow().isEmpty())
    }

    @Test
    fun expiredLegacyActionUsesOneReadAndKeepsItsWarningOnDisk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Outbox.init(context)
        Outbox.clearAll()
        val generation = ApiClient.queueSessionGeneration()
        try {
            DriverStageApi.pending(generation).getOrThrow()
            assertEquals("/me", request().path)
            val old = Outbox.newDriverStatus(42, "done").copy(
                createdAt = System.currentTimeMillis() - Outbox.STATUS_MAX_AGE_MS - 1000
            )
            assertTrue(Outbox.enqueue(context, old, generation))
            reply("""{"role":"driver","status":"cancelled","driver_phase":""}""")
            assertTrue(Outbox.flush(context, generation))
            val read = request()
            assertEquals("GET", read.method)
            assertEquals("/bookings/42/role", read.path)
            assertFalse(Outbox.hasPending(context))
            Outbox.init(context)
            assertEquals(setOf(42), Outbox.staleDriverStages(context, generation))
            assertTrue(Outbox.dismissStaleDriverStage(context, 42, generation))
            assertTrue(Outbox.staleDriverStages(context, generation).isEmpty())
            assertEquals(2, server.requestCount)
        } finally {
            Outbox.clearAll()
        }
    }

    @Test
    fun legacyCompletionChecksTheTripWithoutSendingAMutation() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        // Reuse the standard fixture's first authenticated read.
        DriverStageApi.pending(generation).getOrThrow()
        assertEquals("/me", request().path)
        val action = Outbox.newDriverStatus(42, "done")
        reply("""{"role":"driver","status":"confirmed","driver_phase":"arriving"}""")
        assertFalse(ApiClient.sendQueuedAction(action, generation).isSuccess)
        assertEquals("GET", request().method)
        reply("""{"role":"driver","status":"cancelled","driver_phase":""}""")
        assertFalse(ApiClient.sendQueuedAction(action, generation).isSuccess)
        assertEquals("GET", request().method)
        reply("""{"role":"driver","status":"done","driver_phase":""}""")
        assertTrue(ApiClient.sendQueuedAction(action, generation).isSuccess)
        assertEquals("GET", request().method)
        assertEquals(4, server.requestCount)
    }
}
