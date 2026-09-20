package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxHttpFailureTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 500
        ApiClient.init(context)
        ApiClient.saveToken("QA-http")
        Outbox.clearAll()
    }
    @After fun cleanup() {
        Outbox.clearAll()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    private fun response(code: Int) = MockResponse().setResponseCode(code).setBody("{}")

    private fun retainsThenRetries(code: Int) = runBlocking {
        for (kind in listOf("message", "trip_status", "driver_status")) {
            val action = Outbox.newMessage(7, "test").copy(kind = kind)
            assertTrue(Outbox.enqueue(context, action))
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(8, "later")))
            server.enqueue(response(code))
            assertFalse("$code/$kind must retain the queue", Outbox.flush(context))
            assertEquals(1, Outbox.count(context, 7))
            assertEquals(1, Outbox.count(context, 8))
            val failed = server.takeRequest()
            // Reopen the actual stored queue before a later retry.
            Outbox.init(context)
            server.enqueue(response(200))
            server.enqueue(response(200))
            assertTrue(Outbox.flush(context))
            assertEquals(failed.path, server.takeRequest().path)
            assertEquals("/bookings/8/messages", server.takeRequest().path)
            assertFalse(Outbox.hasPending(context))
        }
    }
    @Test fun unavailable503RetainsAllKinds() = retainsThenRetries(503)
    @Test fun rateLimited429RetainsAllKinds() = retainsThenRetries(429)
    @Test fun requestTimeout408RetainsAllKinds() = retainsThenRetries(408)
    @Test fun otherServerFailuresRetainAllKinds() {
        for (code in listOf(500, 502, 504)) retainsThenRetries(code)
    }
    @Test fun finalRejectionDoesNotBlockNextMessage() = runBlocking {
        for (code in listOf(400, 403, 404, 409, 422)) {
            Outbox.enqueue(context, Outbox.newMessage(7, "rejected"))
            Outbox.enqueue(context, Outbox.newMessage(8, "valid"))
            server.enqueue(response(code))
            server.enqueue(response(200))
            assertTrue(Outbox.flush(context))
            assertEquals("/bookings/7/messages", server.takeRequest().path)
            assertEquals("/bookings/8/messages", server.takeRequest().path)
            assertFalse(Outbox.hasPending(context))
        }
    }
}
