package com.yuldash.app.data

import android.app.Application
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Production callbacks/retry code with controlled transport and timer order; no live service. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatSocketLifecycleTest(private val channel: String) {
    private val factory = ControlledFactory()
    private val scheduled = ArrayDeque<Runnable>()
    private val received = mutableListOf<ChatSocket.Incoming>()
    private val connected = mutableListOf<Boolean>()
    private val rejected = mutableListOf<Int>()
    private lateinit var socket: ChatSocket

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun channels(): List<Array<Any>> = listOf(arrayOf("booking"), arrayOf("taxi"), arrayOf("parcel"))
    }

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://localhost:1"
        ApiClient.saveToken("local-chat-A")
        val onMessage: (ChatSocket.Incoming) -> Unit = { received.add(it); Unit }
        val onConnected: (Boolean) -> Unit = { connected.add(it); Unit }
        val onRejected: (Int, String) -> Unit = { id, _ -> rejected.add(id); Unit }
        val schedule: (Runnable, Long, TimeUnit) -> Unit = { task, _, _ -> scheduled.addLast(task) }
        socket = when (channel) {
            "taxi" -> ChatSocket.forOrder(42, onMessage, onConnected, onRejected, factory, schedule)
            "parcel" -> ChatSocket.forParcel(42, onMessage, onConnected, onRejected, factory, schedule)
            else -> ChatSocket(42, onMessage, onConnected, onRejected = onRejected,
                socketFactory = factory, scheduleTask = schedule)
        }
    }

    @After fun cleanup() {
        socket.close()
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply {
            isAccessible = true
        }.invoke(ApiClient)
        ApiClient.resetForTest()
    }

    @Test fun currentConnectionAuthenticatesAndDeliversMessages() {
        socket.connect()
        factory.latest.open()
        val expectedPath = when (channel) {
            "taxi" -> "/ws/instant/42/chat"
            "parcel" -> "/ws/parcel/42/chat"
            else -> "/ws/bookings/42"
        }
        assertEquals(expectedPath, factory.latest.request().url.encodedPath)
        assertEquals("local-chat-A", JSONObject(factory.latest.sent.single()).getString("token"))
        factory.latest.message()
        assertEquals("hello", received.single().text)
        assertTrue(socket.send("reply", -7))
        assertEquals(-7, JSONObject(factory.latest.sent.last()).getInt("temp_id"))
        factory.latest.reject()
        assertEquals(listOf(-7), rejected)
    }

    @Test fun oldScreenCannotReconnectAsNextAccount() {
        socket.connect()
        factory.latest.open()
        factory.latest.fail()
        assertEquals(1, scheduled.size)
        ApiClient.saveToken("local-chat-B")
        networkReturned()
        drainTimers()
        socket.connect() // Even an explicit resume of the old owner must not adopt B.
        assertEquals(1, factory.connections.size)
        assertFalse(socket.send("must not cross accounts"))
    }

    @Test fun accountSwitchBeforeHandshakeDoesNotAuthenticateOldSocket() {
        socket.connect()
        ApiClient.saveToken("local-chat-B")
        factory.latest.open()
        assertTrue(factory.latest.sent.isEmpty())
        assertTrue(connected.isEmpty())
        assertTrue(factory.latest.closed)
    }

    @Test fun accountSwitchBetweenScreenCaptureAndSocketConstructionCannotAdoptNewOwner() {
        val screenOwner = ApiClient.queueSessionGeneration()
        socket.close()
        ApiClient.saveToken("local-chat-B")
        socket = when (channel) {
            "taxi" -> ChatSocket.forOrder(42, onMessage = {}, socketFactory = factory, expectedGeneration = screenOwner)
            "parcel" -> ChatSocket.forParcel(42, onMessage = {}, socketFactory = factory, expectedGeneration = screenOwner)
            else -> ChatSocket(42, onMessage = {}, socketFactory = factory, expectedGeneration = screenOwner)
        }
        socket.connect()
        networkReturned()
        assertTrue(factory.connections.isEmpty())
        assertFalse(socket.send("old screen draft"))
    }

    @Test fun accountSwitchRejectsLateMessagesAndRejections() {
        socket.connect()
        factory.latest.open()
        ApiClient.saveToken("local-chat-B")
        factory.latest.message()
        factory.latest.reject()
        factory.latest.fail()
        assertTrue(received.isEmpty())
        assertTrue(rejected.isEmpty())
        assertEquals(listOf(true), connected)
        assertTrue(scheduled.isEmpty())
        assertFalse(socket.send("stale"))
    }

    @Test fun terminalPolicyCloseCannotReviveOnNetworkReturnOrOldTimer() {
        socket.connect()
        factory.latest.fail()
        assertEquals(1, scheduled.size)
        networkReturned()
        factory.latest.open()
        factory.latest.serverClose(1008)
        networkReturned()
        drainTimers()
        assertEquals(2, factory.connections.size)
        assertFalse(socket.send("terminal"))
        assertEquals(listOf(false, true, false), connected)
    }

    @Test fun terminalCustomCloseWithoutClosingCallbackAlsoStopsReconnect() {
        socket.connect()
        factory.latest.open()
        factory.latest.listener.onClosed(factory.latest, 4403, "forbidden")
        networkReturned()
        drainTimers()
        assertEquals(1, factory.connections.size)
        assertFalse(socket.send("terminal"))
    }

    @Test fun replacedSocketCannotChangeReplacementStateOrDeliverOldMessages() {
        socket.connect()
        val old = factory.latest
        old.open()
        networkReturned()
        factory.latest.open()
        old.listener.onClosed(old, 4999, "replaced")
        old.fail()
        old.message()
        old.reject()
        old.open()
        assertEquals(listOf(true, true), connected)
        assertTrue(received.isEmpty())
        assertTrue(rejected.isEmpty())
        assertTrue(scheduled.isEmpty())
        assertTrue(socket.send("replacement works"))
    }

    @Test fun staleTimerCannotReplaceHealthyNetworkReconnect() {
        socket.connect()
        factory.latest.fail()
        networkReturned()
        factory.latest.open()
        drainTimers()
        assertEquals(2, factory.connections.size)
        assertTrue(socket.send("still connected"))
    }

    @Test fun temporaryFailureStillRetriesForSameAccount() {
        socket.connect()
        factory.latest.open()
        factory.latest.fail()
        drainTimers()
        assertEquals(2, factory.connections.size)
        factory.latest.open()
        factory.latest.message()
        assertEquals(1, received.size)
        assertTrue(socket.send("recovered"))
    }

    @Test fun explicitCloseSuppressesLateCallbacksAndRetry() {
        socket.connect()
        val old = factory.latest
        old.fail()
        socket.close()
        old.open()
        old.message()
        old.reject()
        old.fail()
        networkReturned()
        drainTimers()
        assertEquals(1, factory.connections.size)
        assertTrue(received.isEmpty())
        assertTrue(rejected.isEmpty())
        assertEquals(listOf(false), connected)
        assertFalse(socket.send("closed"))
    }

    private fun networkReturned() {
        NetworkMonitor::class.java.getDeclaredMethod("notifyAvailable").apply {
            isAccessible = true
        }.invoke(NetworkMonitor)
    }

    private fun drainTimers() {
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
    }

    private class ControlledFactory : WebSocket.Factory {
        val connections = mutableListOf<ControlledSocket>()
        val latest get() = connections.last()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            ControlledSocket(request, listener).also { connections.add(it) }
    }

    private class ControlledSocket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var closed = false
        fun open() = listener.onOpen(this, Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        fun message() = listener.onMessage(this, """{"type":"message","id":1,"sender_id":9,"text":"hello"}""")
        fun reject() = listener.onMessage(this, """{"type":"rejected","temp_id":-7,"reason":"rate_limit"}""")
        fun fail() = listener.onFailure(this, IOException("temporary disconnect"), null)
        fun serverClose(code: Int) {
            listener.onClosing(this, code, "forbidden")
            listener.onClosed(this, code, "forbidden")
        }
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (closed) return false
            sent.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = !closed
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { closed = true }
    }
}
