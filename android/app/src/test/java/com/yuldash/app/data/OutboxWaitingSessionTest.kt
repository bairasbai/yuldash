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

/** A waiting flush must retain the account it belonged to before the mutex suspension. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxWaitingSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val posted = CopyOnWriteArrayList<Pair<String, String?>>()
    private val server = MockWebServer()

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(context)
        ApiClient.saveToken("local-account-A")
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.contains("/bookings/") == true) {
                    posted += request.path.orEmpty() to request.getHeader("Authorization")
                    if (request.path == "/bookings/42/messages") {
                        held.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    }
                }
                return MockResponse().setBody("{}")
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 30000
    }

    @After fun cleanup() {
        release.countDown()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
    }

    @Test fun waitingFlushDoesNotAdoptTheNextAccountsQueue() = runBlocking {
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "A-message")))
        val first = async(Dispatchers.IO) { Outbox.flush(context) }
        assertTrue("First flush did not reach HTTP", held.await(5, TimeUnit.SECONDS))
        // UNDISPATCHED guarantees entry and suspension at the occupied production mutex.
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { Outbox.flush(context) }
        assertFalse(waiting.isCompleted)
        ApiClient.logout()
        ApiClient.saveToken("local-account-B")
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "B-message")))
        release.countDown()
        assertFalse(first.await())
        assertFalse("Waiting A-flush adopted B's queue", waiting.await())
        assertEquals(1, Outbox.count(context, 99))
        assertEquals(listOf("/bookings/42/messages" to "Bearer local-account-A"), posted.toList())
        assertTrue("Current B must still be able to send its own queue", Outbox.flush(context))
        assertEquals(0, Outbox.count(context, 99))
        assertEquals("/bookings/99/messages" to "Bearer local-account-B", posted.last())
        assertEquals(2, posted.size)
    }
}
