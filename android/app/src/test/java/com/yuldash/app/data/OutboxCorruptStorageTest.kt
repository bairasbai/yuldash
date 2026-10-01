package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A failed read is uncertainty, never permission to replace a stored queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxCorruptStorageTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val validItem = """{"id":101,"booking_id":7,"kind":"message","payload":"synthetic retained message","created_at":1000}"""

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(context)
        ApiClient.saveToken("QA-corrupt-queue-owner")
    }

    @After fun cleanup() {
        Outbox.initStores(MemoryDiskPreferences(), null)
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    @Test fun malformedQueueDoesNotPermitReplacementOrHttp() = runBlocking {
        val rawVariants = listOf("[$validItem,", "[$validItem,17]", "{\"queue\":[$validItem]}")
        for (raw in rawVariants) {
            val store = MemoryDiskPreferences()
            assertTrue(store.edit().putString("queue", raw).commit())
            Outbox.initStores(store, null)
            val version = Outbox.version.value
            assertFalse("a malformed existing queue must not be overwritten", Outbox.enqueue(context, Outbox.newMessage(7, "new")))
            assertEquals(raw, store.getString("queue", null))
            assertEquals(raw, store.restarted().getString("queue", null))
            assertEquals(version, Outbox.version.value)
            assertFalse(Outbox.flush(context))
            assertEquals(0, server.requestCount)
            val restart = store.restarted()
            Outbox.initStores(restart, null)
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "retry after restart")))
            assertEquals(raw, restart.restarted().getString("queue", null))
        }
    }

    @Test fun transientReadFailureDoesNotReplaceThePreviousMessage() {
        val store = MemoryDiskPreferences()
        val raw = "[$validItem]"
        assertTrue(store.edit().putString("queue", raw).commit())
        var reads = 0
        val unreadable = object : SharedPreferences by store {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "queue" && ++reads == 1) error("synthetic read failure")
                return store.getString(key, defValue)
            }
        }
        Outbox.initStores(unreadable, null)
        assertFalse("a failed read must not become an empty snapshot", Outbox.enqueue(context, Outbox.newMessage(7, "new")))
        assertEquals(raw, store.getString("queue", null))
        assertEquals(raw, store.restarted().getString("queue", null))
        Outbox.initStores(store.restarted(), null)
        assertEquals(1, Outbox.count(context, 7))
        assertEquals(0, server.requestCount)
    }

    @Test fun wrongPreferenceTypeRemainsUntouched() = runBlocking {
        val store = MemoryDiskPreferences()
        assertTrue(store.edit().putInt("queue", 17).commit())
        Outbox.initStores(store, null)
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "new")))
        assertFalse(Outbox.flush(context))
        assertEquals(17, store.restarted().getInt("queue", 0))
        assertEquals(0, server.requestCount)
    }

    @Test fun genuinelyAbsentQueueStillAcceptsAConfirmedWrite() {
        val store = MemoryDiskPreferences()
        Outbox.initStores(store, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "new")))
        Outbox.initStores(store.restarted(), null)
        assertEquals(1, Outbox.count(context, 7))
    }

    @Test fun corruptionDuringHttpIsNotErasedByAcknowledgement() = runBlocking {
        val store = MemoryDiskPreferences()
        Outbox.initStores(store, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "first")))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "second")))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("{}")
            }
        }
        val corrupt = "[$validItem,17]"
        val flush = async(Dispatchers.IO) { Outbox.flush(context) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(store.edit().putString("queue", corrupt).commit())
        } finally { release.countDown() }
        assertFalse(flush.await())
        assertEquals(1, server.requestCount)
        assertEquals(corrupt, store.getString("queue", null))
        assertEquals(corrupt, store.restarted().getString("queue", null))
    }
}
