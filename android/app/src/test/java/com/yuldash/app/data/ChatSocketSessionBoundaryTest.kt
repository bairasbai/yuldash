package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real loopback WebSockets: session ownership must survive late frames and reconnect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatSocketSessionBoundaryTest {
    private val server = MockWebServer()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val requestedPaths = CopyOnWriteArrayList<String>()
    private val auth = LinkedBlockingQueue<String>()
    private val incoming = LinkedBlockingQueue<ChatSocket.Incoming>()
    private val outgoing = LinkedBlockingQueue<String>()
    private val rejected = LinkedBlockingQueue<Int>()
    private val connected = LinkedBlockingQueue<Boolean>()
    private lateinit var chat: ChatSocket

    @Before fun prepare() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestedPaths += request.path.orEmpty()
                if (request.path != "/ws/bookings/42") return MockResponse().setBody("{}")
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { sockets += webSocket }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val body = JSONObject(text)
                        if (body.optString("type") == "auth") auth.offer(body.getString("token"))
                        else outgoing.offer(text)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })
            }
        }
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.saveToken("local-session-A")
        chat = ChatSocket(42, onMessage = { incoming.offer(it) },
            onConnected = { connected.offer(it) }, onRejected = { id, _ -> rejected.offer(id) })
        chat.connect()
        assertEquals("local-session-A", auth.poll(5, TimeUnit.SECONDS))
        assertEquals(true, connected.poll(5, TimeUnit.SECONDS))
    }

    @After fun cleanup() {
        chat.close()
        sockets.forEach { it.close(1000, null) }
        ApiClient.logout()
        ApiClient.resetForTest()
        server.shutdown()
    }

    private fun frame(id: Int, text: String) =
        """{"type":"message","id":$id,"sender_id":22,"text":"$text","timestamp":"2026-10-07T09:00:00Z"}"""

    @Test fun currentSessionReceivesAndSendsNormally() {
        assertTrue(sockets.first().send(frame(1, "current")))
        assertEquals("current", incoming.poll(5, TimeUnit.SECONDS)?.text)
        assertTrue(chat.send("reply", -2))
        assertEquals("reply", JSONObject(outgoing.poll(5, TimeUnit.SECONDS)!!).getString("text"))
    }

    @Test fun lateFrameFromPreviousAccountIsIgnored() {
        ApiClient.saveToken("local-session-B")
        assertTrue(sockets.first().send(frame(2, "private-A")))
        assertNull("Previous account frame was delivered", incoming.poll(700, TimeUnit.MILLISECONDS))
    }

    @Test fun lateRejectionFromPreviousAccountIsIgnored() {
        ApiClient.saveToken("local-session-B")
        assertTrue(sockets.first().send("""{"type":"rejected","temp_id":-2,"reason":"rate_limit"}"""))
        assertNull("Previous account rejection reached the new UI", rejected.poll(700, TimeUnit.MILLISECONDS))
    }

    @Test fun previousAccountCannotSendOnItsOldConnection() {
        ApiClient.saveToken("local-session-B")
        assertFalse("Old connection accepted a send after login changed", chat.send("wrong-owner", -3))
        assertNull(outgoing.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun reconnectNeverAuthenticatesThePreviousChatWithNewAccount() {
        ApiClient.saveToken("local-session-B")
        assertTrue(sockets.first().close(1001, "restart"))
        assertNull("Old chat reauthenticated with another account", auth.poll(2500, TimeUnit.MILLISECONDS))
        assertEquals(1, sockets.size)
    }

    @Test fun reconnectWorksForTheUnchangedSession() {
        assertTrue(sockets.first().close(1001, "restart"))
        assertEquals("local-session-A", auth.poll(5, TimeUnit.SECONDS))
        assertEquals(2, sockets.size)
        assertTrue(sockets.last().send(frame(3, "after-reconnect")))
        assertEquals("after-reconnect", incoming.poll(5, TimeUnit.SECONDS)?.text)
    }

    @Test fun reconnectAlreadyScheduledBeforeLoginChangeStillKeepsItsOwner() {
        assertTrue(sockets.first().close(1001, "restart"))
        assertEquals(false, connected.poll(5, TimeUnit.SECONDS))
        ApiClient.saveToken("local-session-B")
        assertNull(auth.poll(2500, TimeUnit.MILLISECONDS))
        assertEquals(1, sockets.size)
    }

    @Test fun networkReturnDoesNotReopenThePreviousAccountsChat() {
        ApiClient.saveToken("local-session-B")
        NetworkMonitor::class.java.getDeclaredMethod("notifyAvailable").apply { isAccessible = true }.invoke(NetworkMonitor)
        assertNull(auth.poll(700, TimeUnit.MILLISECONDS))
        assertEquals(1, sockets.size)
    }

    @Test fun explicitOldOwnerCannotStartAConnectionUnderNewLogin() {
        val previousGeneration = ApiClient.queueSessionGeneration()
        ApiClient.saveToken("local-session-B")
        val stale = ChatSocket(99, onMessage = {}, expectedGeneration = previousGeneration)
        try {
            stale.connect()
            assertNull(auth.poll(300, TimeUnit.MILLISECONDS))
            assertEquals(1, sockets.size)
            assertFalse("Stale owner attempted an HTTP upgrade", requestedPaths.contains("/ws/bookings/99"))
        } finally { stale.close() }
    }
}
