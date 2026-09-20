package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL

/** Explicit opt-in, only the isolated loopback adapter in test-results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRetryLiveBackendTest {
    private lateinit var context: Context
    private var base = ""
    private var booking = 0
    private fun get(path: String): JSONObject = JSONObject(URL(base + path).readText())
    @Before fun setup() {
        base = System.getenv("YULDASH_CHAT_QA_URL").orEmpty()
        assumeTrue("Requires isolated local chat adapter", base == "http://127.0.0.1:5191")
        val fixture = get("/qa/session")
        booking = fixture.getInt("booking_id")
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = base
        ApiClient.testTimeoutMs = 2000
        ApiClient.init(context)
        ApiClient.saveToken(fixture.getString("token"))
        Outbox.clearAll()
    }
    @After fun cleanup() {
        if (::context.isInitialized) {
            Outbox.clearAll()
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
        }
    }
    @Test fun lostRealResponseThenQueueCreatesOneMessage() = runBlocking {
        val action = Outbox.newMessage(booking, "fixture message")
        assertTrue(ApiClient.sendMessage(booking, action.payload, Outbox.messageRequestKey(action)).isFailure)
        assertEquals(1, get("/qa/stats").getInt("messages"))
        assertTrue(Outbox.enqueue(context, action))
        Outbox.init(context)
        assertTrue(Outbox.flush(context))
        val stats = get("/qa/stats")
        assertEquals(2, stats.getInt("requests"))
        assertEquals(1, stats.getInt("messages"))
        assertEquals(1, stats.getInt("receipts"))
        assertEquals(1, stats.getInt("live"))
        assertEquals(1, stats.getInt("push"))
        assertFalse(Outbox.hasPending(context))
        // Same words deliberately sent again must remain a separate message.
        Outbox.enqueue(context, Outbox.newMessage(booking, action.payload))
        assertTrue(Outbox.flush(context))
        assertEquals(2, get("/qa/stats").getInt("messages"))
    }
    @Test fun unkeyedOriginalRequestReproducesDuplicate() = runBlocking {
        assertTrue(ApiClient.sendMessage(booking, "fixture message").isFailure)
        Outbox.enqueue(context, Outbox.newMessage(booking, "fixture message"))
        assertTrue(Outbox.flush(context))
        assertEquals(2, get("/qa/stats").getInt("messages"))
    }
}
