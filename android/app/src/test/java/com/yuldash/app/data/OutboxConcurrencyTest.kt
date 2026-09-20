package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxConcurrencyTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val sent = ConcurrentLinkedQueue<String>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().contains("/messages")) {
                        sent.add(request.path.orEmpty())
                        if (sent.size == 1) {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                    return MockResponse().setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 4000
        ApiClient.init(context)
        ApiClient.saveToken("QA-A")
        Outbox.clearAll()
    }
    @After fun cleanup() {
        release.countDown()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }
    @Test fun enqueueDuringHttpIsNotOverwrittenByOlderSnapshot() = runBlocking {
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(1, "first")))
        val flush = async(Dispatchers.Default) { Outbox.flush(context) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(2, "new")))
        } finally { release.countDown() }
        flush.await()
        assertEquals(listOf("/bookings/1/messages", "/bookings/2/messages"), sent.toList())
        assertFalse(Outbox.hasPending(context))
    }
    @Test fun clearDuringHttpStopsOldRemainderAndPreservesNewQueue() = runBlocking {
        Outbox.enqueue(context, Outbox.newMessage(1, "old first"))
        Outbox.enqueue(context, Outbox.newMessage(2, "old second"))
        val flush = async(Dispatchers.Default) { Outbox.flush(context) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Outbox.clearAll()
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(3, "new queue")))
        } finally { release.countDown() }
        flush.await()
        assertEquals(listOf("/bookings/1/messages"), sent.toList())
        assertEquals(1, Outbox.count(context, 3))
        assertEquals(0, Outbox.count(context, 2))
    }
    @Test fun staleSessionCannotEnqueueAfterNewLogin() {
        val previous = ApiClient.queueSessionGeneration()
        ApiClient.saveToken("QA-B")
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(1, "late A"), previous))
        assertFalse(Outbox.hasPending(context))
    }
    @Test fun staleSessionCannotStartAnyQueuedHttp() = runBlocking {
        val previous = ApiClient.queueSessionGeneration()
        ApiClient.saveToken("QA-B")
        for (kind in listOf("message", "trip_status", "driver_status")) {
            val action = Outbox.newMessage(1, "late A").copy(kind = kind)
            assertTrue(ApiClient.sendQueuedAction(action, previous).isFailure)
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun simultaneousFlushesDoNotDuplicateMessages() = runBlocking {
        Outbox.enqueue(context, Outbox.newMessage(1, "first"))
        Outbox.enqueue(context, Outbox.newMessage(2, "second"))
        val first = async(Dispatchers.Default) { Outbox.flush(context) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { Outbox.flush(context) }
        release.countDown()
        first.await()
        second.await()
        assertEquals(listOf("/bookings/1/messages", "/bookings/2/messages"), sent.toList())
        assertFalse(Outbox.hasPending(context))
    }
    @Test fun replacingStoreDuringHttpPreservesNewQueue() = runBlocking {
        Outbox.enqueue(context, Outbox.newMessage(1, "old first"))
        Outbox.enqueue(context, Outbox.newMessage(2, "old second"))
        val flush = async(Dispatchers.Default) { Outbox.flush(context) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Outbox.initStores(context.getSharedPreferences("qa-newqueue", 0), null)
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(3, "new queue")))
        } finally { release.countDown() }
        flush.await()
        assertEquals(listOf("/bookings/1/messages"), sent.toList())
        assertEquals(1, Outbox.count(context, 3))
        assertEquals(0, Outbox.count(context, 2))
    }
    @Test fun logoutDuringHttpPreservesNextAccountsQueue() = runBlocking {
        Outbox.enqueue(context, Outbox.newMessage(1, "old first"))
        Outbox.enqueue(context, Outbox.newMessage(2, "old second"))
        val flush = async(Dispatchers.Default) { Outbox.flush(context) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            ApiClient.logout()
            ApiClient.saveToken("QA-B")
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(3, "B queue")))
        } finally { release.countDown() }
        flush.await()
        assertEquals(listOf("/bookings/1/messages"), sent.toList())
        assertEquals(1, Outbox.count(context, 3))
    }
}
