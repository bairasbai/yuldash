package com.yuldash.app.data

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs the production listener and reconnect logic; only transport and waiting are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationSocketLifecycleTest {
    private val factory = ControlledFactory()
    private val scheduled = ArrayDeque<Runnable>()
    private val delays = mutableListOf<Long>()
    private var terminated = 0
    private lateinit var socket: LocationSocket

    @Before
    fun setUp() {
        ApiClient.testBaseUrl = "http://localhost:1"
        ApiClient.saveToken("local-location-lifecycle")
        socket = LocationSocket(
            bookingId = 42,
            onPeer = {},
            onTerminated = { terminated++ },
            socketFactory = factory,
            scheduleTask = { task, delay, unit ->
                delays.add(unit.toSeconds(delay))
                scheduled.addLast(task)
            },
        )
    }

    @After
    fun tearDown() {
        socket.close()
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply {
            isAccessible = true
        }.invoke(ApiClient)
        ApiClient.testBaseUrl = null
    }

    @Test
    fun inactiveTripStopsAfterFortySoftRetriesDespiteSuccessfulTransportHandshakes() {
        socket.connect()
        // First attempt plus forty retries: each HTTP upgrade succeeds, then the server
        // rejects the application-level subscription because the booking is not active.
        repeat(41) { index ->
            factory.latest.open()
            factory.latest.serverClose(1008, "Trip not active")
            if (index < 40) {
                assertEquals("One delayed retry after inactive rejection $index", 1, scheduled.size)
                scheduled.removeFirst().run()
            }
        }
        assertEquals(41, factory.connections.size)
        assertTrue("Inactive booking must stop retrying after the documented limit", scheduled.isEmpty())
        assertTrue(delays.all { it == 15L })
        networkReturned()
        assertEquals("Network restoration must not revive exhausted inactive booking", 41, factory.connections.size)
        assertEquals("Exhausted retries must stop the owner once", 1, terminated)
        factory.latest.listener.onClosed(factory.latest, 1008, "Trip not active")
        assertEquals(1, terminated)
    }

    @Test
    fun forbiddenSubscriptionCannotBeRevivedByNetworkRestoration() {
        socket.connect()
        factory.latest.open()
        factory.latest.serverClose(1008, "Forbidden")
        assertTrue(scheduled.isEmpty())
        networkReturned()
        assertEquals("Terminal server rejection must remove the network listener", 1, factory.connections.size)
        assertFalse("Terminal connection must not accept more GPS", socket.sendLoc(54.0, 56.0))
        factory.latest.listener.onClosed(factory.latest, 1008, "Forbidden")
        assertEquals("Repeated close callbacks must not stop the owner twice", 1, terminated)
    }

    @Test
    fun terminalRejectionAlsoNeutralizesAnOlderQueuedRetry() {
        socket.connect()
        factory.latest.listener.onFailure(factory.latest, IOException("temporary disconnect"), null)
        assertEquals(1, scheduled.size)
        networkReturned()
        assertEquals(2, factory.connections.size)
        factory.latest.open()
        factory.latest.serverClose(1008, "Invalid token")
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        assertEquals("Old timers must not revive a terminal subscription", 2, factory.connections.size)
        assertEquals(1, terminated)
    }

    @Test
    fun temporaryNetworkFailureStillReconnectsWhenNetworkReturns() {
        socket.connect()
        factory.latest.open()
        factory.latest.listener.onFailure(factory.latest, IOException("temporary disconnect"), null)
        assertEquals(listOf(1L), delays)
        networkReturned()
        assertEquals(2, factory.connections.size)
        factory.latest.open()
        assertTrue(socket.sendLoc(54.0, 56.0))
        assertTrue(factory.latest.sent.last().contains("\"type\":\"loc\""))
        assertEquals("A temporary outage must not stop the owner", 0, terminated)
    }

    @Test
    fun replacedSocketLateCloseCannotTerminateItsReplacement() {
        socket.connect()
        val replaced = factory.latest
        replaced.open()
        networkReturned()
        factory.latest.open()
        replaced.listener.onClosed(replaced, 4999, "replaced")
        assertTrue("The replacement must still accept GPS", socket.sendLoc(54.0, 56.0))
        factory.latest.listener.onFailure(factory.latest, IOException("temporary disconnect"), null)
        assertEquals("The replacement must retain its reconnect behavior", 1, scheduled.size)
        scheduled.removeFirst().run()
        assertEquals(3, factory.connections.size)
        assertEquals(0, terminated)
    }

    @Test
    fun explicitClosePreventsNetworkAndTimerReconnects() {
        socket.connect()
        factory.latest.listener.onFailure(factory.latest, IOException("temporary disconnect"), null)
        socket.close()
        networkReturned()
        while (scheduled.isNotEmpty()) scheduled.removeFirst().run()
        assertEquals(1, factory.connections.size)
        assertFalse(socket.sendLoc(54.0, 56.0))
        assertEquals("Owner-initiated close must not call the owner back", 0, terminated)
    }

    @Test
    fun realWebSocketTripEndedHandshakeStopsOwnerAndCannotReconnect() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val received = LinkedBlockingQueue<String>()
        val serverSide = AtomicReference<WebSocket?>()
        val terminalSignal = CountDownLatch(1)
        val terminalCalls = AtomicInteger()
        val scheduledCalls = AtomicInteger()
        val connectionAttempts = AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSide.set(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.offer(text)
                webSocket.close(1008, "Trip ended")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        socket = LocationSocket(
            bookingId = 42,
            onPeer = {},
            socketFactory = object : WebSocket.Factory {
                override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                    connectionAttempts.incrementAndGet()
                    return client.newWebSocket(request, listener)
                }
            },
            scheduleTask = { _, _, _ -> scheduledCalls.incrementAndGet(); Unit },
            onTerminated = { terminalCalls.incrementAndGet(); terminalSignal.countDown() },
        )
        try {
            socket.connect()
            val auth = received.poll(5, TimeUnit.SECONDS)
            assertTrue("The real WebSocket must deliver the first authentication frame", auth != null)
            assertEquals("auth", JSONObject(auth!!).getString("type"))
            assertEquals("/ws/trip/42/location", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertTrue("Trip ended must notify the owner after the real close handshake", terminalSignal.await(5, TimeUnit.SECONDS))
            networkReturned()
            assertEquals("A terminal connection must not schedule another attempt", 0, scheduledCalls.get())
            assertEquals("Network restoration must not create a second connection", 1, connectionAttempts.get())
            assertEquals("Network restoration must not send a second upgrade", 1, server.requestCount)
            assertFalse(socket.sendLoc(54.0, 56.0))
            socket.close()
            assertEquals("Explicit cleanup must not duplicate terminal notification", 1, terminalCalls.get())
        } finally {
            socket.close()
            serverSide.get()?.close(1000, null)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    private fun networkReturned() {
        NetworkMonitor::class.java.getDeclaredMethod("notifyAvailable").apply {
            isAccessible = true
        }.invoke(NetworkMonitor)
    }

    private class ControlledFactory : WebSocket.Factory {
        val connections = mutableListOf<ControlledSocket>()
        val latest get() = connections.last()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            ControlledSocket(request, listener).also { connections.add(it) }
    }

    private class ControlledSocket(
        private val request: Request,
        val listener: WebSocketListener,
    ) : WebSocket {
        val sent = mutableListOf<String>()
        private var closed = false
        fun open() {
            listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(101).message("Switching Protocols").build())
        }
        fun serverClose(code: Int, reason: String) {
            listener.onClosing(this, code, reason)
            listener.onClosed(this, code, reason)
        }
        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            if (closed) return false
            sent.add(text)
            return true
        }
        override fun send(bytes: ByteString): Boolean = !closed
        override fun close(code: Int, reason: String?): Boolean {
            closed = true
            return true
        }
        override fun cancel() { closed = true }
    }
}
