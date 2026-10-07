package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real HTTP and joined flush jobs: caller lifetime does not become a global queue policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxCallerBoundaryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val disk = MemoryDiskPreferences()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val active = AtomicBoolean(true)
    private val posted = CopyOnWriteArrayList<Pair<String, String?>>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    private lateinit var server: MockWebServer
    @Volatile private var holdFirst = false
    @Volatile private var reply = "success"

    @Before fun prepare() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path?.startsWith("/bookings/") == true) {
                        posted += request.path.orEmpty() to request.getHeader("Idempotency-Key")
                        if (holdFirst && held.count == 1L) {
                            held.countDown()
                            if (!release.await(15, TimeUnit.SECONDS)) fixtureFailures += "HTTP release timeout"
                            if (reply == "temporary") return MockResponse().setResponseCode(503).setBody("{}")
                            if (reply == "network") return MockResponse().setBody("{partial-data").setHeader("Content-Length", 1000)
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                    }
                    return MockResponse().setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 20000
        ApiClient.init(context)
        ApiClient.saveToken("synthetic-caller-A")
        Outbox.initStores(MemoryDiskPreferences(), disk)
    }
    @After fun cleanup() {
        release.countDown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
        assertTrue("Fixture timeout: $fixtureFailures", fixtureFailures.isEmpty())
    }
    private fun stored() = disk.restarted().getString("queue", null)
    private fun enqueue(booking: Int, text: String): OutboxAction = Outbox.newMessage(booking, text).also {
        assertTrue(Outbox.enqueue(context, it))
    }

    @Test fun inactiveCallerLeavesStorageAndQueueUntouched() = runBlocking {
        enqueue(42, "closed"); enqueue(99, "other")
        assertTrue(Outbox.enqueue(context, OutboxAction(123, 42, "trip_status", "arrived", System.currentTimeMillis() - 20 * 3600_000L)))
        val before = stored(); val version = Outbox.version.value
        active.set(false)
        assertFalse(Outbox.flush(context, shouldContinue = active::get))
        assertEquals(before, stored()); assertEquals(version, Outbox.version.value)
        assertTrue(posted.isEmpty())
    }
    @Test fun waiterRechecksCallerAfterActualMutexSuspension() = runBlocking {
        holdFirst = true; reply = "temporary"
        val other = enqueue(99, "other")
        val first = async(Dispatchers.IO) { Outbox.flush(context) }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        val own = enqueue(42, "closed")
        val before = stored()
        // With the production mutex held, UNDISPATCHED entry proves this second job suspended there.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            Outbox.flush(context, shouldContinue = active::get)
        }
        assertFalse(waiting.isCompleted)
        active.set(false); release.countDown()
        assertFalse(first.await()); assertFalse(waiting.await())
        assertEquals(before, stored())
        assertEquals(listOf("/bookings/99/messages" to other.requestKey), posted.toList())
        assertEquals(1, Outbox.count(context, 42)); assertEquals(1, Outbox.count(context, 99))
        // The global default remains allowed to reconcile both same-owner actions later.
        assertTrue(Outbox.flush(context))
        assertEquals(listOf(other.requestKey, other.requestKey, own.requestKey), posted.map { it.second })
        assertEquals(0, Outbox.count(context, 42)); assertEquals(0, Outbox.count(context, 99))
    }
    @Test fun acceptedHeldActionIsAcknowledgedBeforeStoppingNextAction() = runBlocking {
        holdFirst = true
        val firstAction = enqueue(42, "accepted")
        val next = enqueue(42, "pending")
        val other = enqueue(99, "other")
        val job = async(Dispatchers.IO) { Outbox.flush(context, shouldContinue = active::get) }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        active.set(false); release.countDown()
        assertTrue(job.await())
        assertEquals(listOf("/bookings/42/messages" to firstAction.requestKey), posted.toList())
        assertEquals(1, Outbox.count(context, 42)); assertEquals(1, Outbox.count(context, 99))
        val raw = stored().orEmpty()
        assertFalse(raw.contains(firstAction.requestKey))
        assertTrue(raw.contains(next.requestKey)); assertTrue(raw.contains(other.requestKey))
    }
    @Test fun failedHeldActionRetainsExactPayloadAndRequestKey() = runBlocking {
        holdFirst = true; reply = "network"
        val action = enqueue(42, "may-have-arrived")
        val before = stored()
        val job = async(Dispatchers.IO) { Outbox.flush(context, shouldContinue = active::get) }
        assertTrue(held.await(5, TimeUnit.SECONDS))
        active.set(false); release.countDown()
        assertFalse(job.await())
        assertEquals(before, stored())
        assertEquals(listOf("/bookings/42/messages" to action.requestKey), posted.toList())
    }
    @Test fun ordinaryGlobalFlushStillSendsBothBookingsWithoutUiPredicate() = runBlocking {
        val own = enqueue(42, "own"); val other = enqueue(99, "other")
        active.set(false)
        assertTrue(Outbox.flush(context))
        assertEquals(listOf(own.requestKey, other.requestKey), posted.map { it.second })
        assertEquals(0, Outbox.count(context, 42)); assertEquals(0, Outbox.count(context, 99))
    }
}
