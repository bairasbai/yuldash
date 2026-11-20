package com.yuldash.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Audit evidence: assertions intentionally describe defects in unchanged main. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditMainEvidenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var server: MockWebServer
    private fun refresh(value: String) {
        ApiClient::class.java.getDeclaredMethod("saveRefresh", String::class.java).apply { isAccessible = true }.invoke(ApiClient, value)
    }
    private fun clearSession() {
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)
    }
    @Before fun setup() {
        server = MockWebServer(); server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 2000
        clearSession(); ApiClient.sessionExpired.value = false
        Outbox.init(ctx); Outbox.clearAll()
    }
    @After fun cleanup() {
        clearSession(); Outbox.clearAll(); ApiClient.sessionExpired.value = false
        server.shutdown(); ApiClient.testBaseUrl = null; ApiClient.testTimeoutMs = null
    }
    private fun response(code: Int = 200) = MockResponse().setResponseCode(code).setBody("{}")
    @Test fun refresh503DeletesSessionAndPendingMessage() = runBlocking {
        ApiClient.saveToken("audit_A"); refresh("audit_refresh_A")
        Outbox.enqueue(ctx, Outbox.newMessage(7, "pending"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = when {
                r.path == "/auth/refresh" -> response(503)
                r.method == "DELETE" -> response(401)
                else -> response()
            }
        }
        val result = ApiClient.deleteContact(7)
        assertTrue(result.isFailure)
        assertFalse("BUG: temporary refresh failure deleted session", ApiClient.isLoggedIn())
        assertFalse("BUG: temporary refresh failure deleted queue", Outbox.hasPending(ctx))
        assertTrue(ApiClient.sessionExpired.value)
    }
    @Test fun delayedOldPostIsRepeatedWithNewAccountToken() = runBlocking {
        ApiClient.saveToken("audit_A"); refresh("refresh_A")
        val requests = CopyOnWriteArrayList<String>()
        val n = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests.add(r.getHeader("Authorization") ?: "none")
                if (n.incrementAndGet() == 1) {
                    clearSession(); ApiClient.saveToken("audit_B"); refresh("refresh_B")
                    return response(401)
                }
                return response()
            }
        }
        assertTrue(ApiClient.sendMessage(7, "from A").isSuccess)
        assertEquals("BUG: old mutating request replayed as B", listOf("Bearer audit_A", "Bearer audit_B"), requests)
    }
    @Test fun concurrentEnqueueIsLostAfterFlush() = runBlocking {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "A"))
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                if (calls.incrementAndGet() == 1) {
                    Outbox.enqueue(ctx, Outbox.newMessage(7, "B"))
                    assertEquals(2, Outbox.count(ctx, 7))
                }
                return response()
            }
        }
        Outbox.flush(ctx)
        assertEquals(1, calls.get())
        assertFalse("BUG: B disappeared without request", Outbox.hasPending(ctx))
    }
    @Test fun temporary503DeletesQueuedMessage() = runBlocking {
        Outbox.enqueue(ctx, Outbox.newMessage(7, "A"))
        server.enqueue(response(503)); Outbox.flush(ctx)
        assertFalse("BUG: temporary HTTP error deleted message", Outbox.hasPending(ctx))
    }
}
