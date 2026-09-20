package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxMessageRetryTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 800
        ApiClient.init(context)
        ApiClient.saveToken("QA-retry")
        Outbox.clearAll()
    }
    @After fun cleanup() {
        Outbox.clearAll()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    @Test fun lostResponseAndReopenedQueueUseSameMessageKey() = runBlocking {
        Outbox.enqueue(context, Outbox.newMessage(7, "hello"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertFalse(Outbox.flush(context))
        assertEquals(1, Outbox.count(context, 7))
        val first = server.takeRequest()
        Outbox.init(context)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(Outbox.flush(context))
        val second = server.takeRequest()
        assertFalse("server needs a stable key", first.getHeader("Idempotency-Key").isNullOrBlank())
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
        assertEquals(first.body.readUtf8(), second.body.readUtf8())
    }
    @Test fun identicalButSeparateMessagesHaveDifferentKeys() = runBlocking {
        repeat(2) {
            Outbox.enqueue(context, Outbox.newMessage(7, "hello"))
            server.enqueue(MockResponse().setBody("{}"))
        }
        Outbox.flush(context)
        val a = server.takeRequest().getHeader("Idempotency-Key")
        val b = server.takeRequest().getHeader("Idempotency-Key")
        assertFalse(a.isNullOrBlank())
        assertFalse(b.isNullOrBlank())
        assertNotEquals(a, b)
    }
    @Test fun firstRestAndQueuedRetryUseSameKey() = runBlocking {
        val action = Outbox.newMessage(7, "hello")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertTrue(ApiClient.sendMessage(7, action.payload, Outbox.messageRequestKey(action)).isFailure)
        val first = server.takeRequest()
        assertTrue(Outbox.enqueue(context, action))
        Outbox.init(context)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(Outbox.flush(context))
        val second = server.takeRequest()
        assertEquals(action.requestKey, first.getHeader("Idempotency-Key"))
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
    }
    @Test fun refreshRetryKeepsMessageKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"access_token":"old","refresh_token":"refresh-old"}"""))
        ApiClient.verifyCode("+70000000000", "000000", "Local").getOrThrow()
        server.takeRequest()
        val action = Outbox.newMessage(7, "hello")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"refresh-new"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        ApiClient.sendMessage(7, action.payload, action.requestKey).getOrThrow()
        assertEquals(action.requestKey, server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals("/auth/refresh", server.takeRequest().path)
        assertEquals(action.requestKey, server.takeRequest().getHeader("Idempotency-Key"))
    }
    @Test fun legacyActionGetsStableKeyAfterReopening() = runBlocking {
        val plain = context.getSharedPreferences("legacy-queue-test", 0)
        plain.edit().putString("queue", """[{"id":11,"booking_id":7,"kind":"message","payload":"legacy","created_at":1}]""").commit()
        Outbox.initStores(plain, null)
        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertFalse(Outbox.flush(context))
        val first = server.takeRequest()
        Outbox.initStores(plain, null)
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(Outbox.flush(context))
        val second = server.takeRequest()
        assertFalse(first.getHeader("Idempotency-Key").isNullOrBlank())
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"))
    }
}
