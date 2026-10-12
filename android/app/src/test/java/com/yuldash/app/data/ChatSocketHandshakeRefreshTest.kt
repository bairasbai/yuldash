package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual HTTP401/refresh and loopback WS; old listener injection is explicitly separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatSocketHandshakeRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var chat: ChatSocket
    private val upgrades = AtomicInteger()
    private val refreshes = AtomicInteger()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val opened = LinkedBlockingQueue<Pair<Int, WebSocket>>()
    private val auth = LinkedBlockingQueue<Pair<Int, String>>()
    private val sent = LinkedBlockingQueue<Pair<Int, String>>()
    private val received = LinkedBlockingQueue<ChatSocket.Incoming>()
    private val rejections = LinkedBlockingQueue<Int>()
    private val events = CopyOnWriteArrayList<Boolean>()
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val fixtureFailures = CopyOnWriteArrayList<String>()
    @Volatile private var holdFirst = false
    @Volatile private var rejectAuth = false
    @Volatile private var reconnectInCallback = false

    @Before fun prepare() = runBlocking {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path" to request.getHeader("Authorization")
                    if (path == "/ws/bookings/42") {
                        val index = upgrades.incrementAndGet()
                        if (holdFirst && index == 1) {
                            held.countDown()
                            if (!release.await(30, TimeUnit.SECONDS)) fixtureFailures += "Held upgrade expired"
                        }
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                sockets += webSocket
                                opened.offer(index to webSocket)
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val body = JSONObject(text)
                                if (body.optString("type") == "auth") {
                                    auth.offer(index to body.getString("token"))
                                    if (rejectAuth) webSocket.close(1008, "local policy refusal")
                                } else sent.offer(index to body.getString("text"))
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                webSocket.close(code, null)
                            }
                        })
                    }
                    val body = when (path) {
                        "/auth/verify" -> {
                            val account = JSONObject(request.body.readUtf8()).getString("name")
                            """{"access_token":"access-$account","refresh_token":"refresh-$account"}"""
                        }
                        "/auth/refresh" -> {
                            refreshes.incrementAndGet()
                            val payload = JSONObject(request.body.readUtf8())
                            if (payload.getString("refresh_token") != "refresh-A" ||
                                !payload.getString("rotation_id").matches(Regex("[0-9a-f]{64}")))
                                fixtureFailures += "Unexpected refresh payload"
                            """{"access_token":"access-A2","refresh_token":"refresh-A2"}"""
                        }
                        "/trusted-contacts" -> {
                            if (request.getHeader("Authorization") == "Bearer access-A")
                                return MockResponse().setResponseCode(401).setBody("{}")
                            """{"items":[]}"""
                        }
                        "/auth/logout", "/push/unregister" -> "{}"
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    return MockResponse().setBody(body)
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "A").getOrThrow()
        chat = newChat()
        Unit
    }

    private fun newChat() = ChatSocket(42, onMessage = { received.offer(it) },
        onConnected = { value ->
            events += value
            if (!value && reconnectInCallback) chat.connect()
        }, onRejected = { id, _ -> rejections.offer(id) })

    @After fun cleanup() {
        release.countDown()
        chat.close()
        sockets.forEach { it.close(1000, null) }
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) waitFor { requests.contains("POST /auth/logout" to bearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        server.shutdown()
        assertTrue(fixtureFailures.toString(), fixtureFailures.isEmpty())
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("Expected asynchronous boundary", condition())
    }
    private fun firstSocket(): WebSocket {
        chat.connect()
        val pair = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertEquals(1, pair.first)
        assertEquals(1 to "access-A", auth.poll(5, TimeUnit.SECONDS))
        waitFor { events == listOf(true) }
        return pair.second
    }
    private fun startHeld(): WebSocket {
        holdFirst = true
        chat.connect()
        assertTrue(held.await(5, TimeUnit.SECONDS))
        return clientSocket()
    }
    private fun refreshSameSession() = runBlocking {
        val generation = ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.getContacts().isSuccess)
        assertEquals("access-A2", ApiClient.currentToken())
        assertEquals("Refresh is not a different login", generation, ApiClient.queueSessionGeneration())
        assertEquals(1, refreshes.get())
        assertEquals(1, requests.count { it == ("GET /trusted-contacts" to "Bearer access-A") })
        assertEquals(1, requests.count { it == ("GET /trusted-contacts" to "Bearer access-A2") })
    }
    private fun clientSocket() = ChatSocket::class.java.getDeclaredField("ws").apply { isAccessible = true }.get(chat) as WebSocket
    private fun listener(socket: WebSocket) = socket.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(socket) as WebSocketListener
    private fun networkReturn() {
        NetworkMonitor::class.java.getDeclaredMethod("notifyAvailable").apply { isAccessible = true }.invoke(NetworkMonitor)
    }
    private fun frame(id: Int, text: String) = """{"type":"message","id":$id,"sender_id":22,"text":"$text"}"""
    private fun assertCurrentConversation(serverSocket: WebSocket, index: Int) {
        assertTrue(serverSocket.send(frame(index, "current-$index")))
        assertEquals("current-$index", received.poll(5, TimeUnit.SECONDS)?.text)
        assertTrue(chat.send("reply-$index", -index))
        assertEquals(index to "reply-$index", sent.poll(5, TimeUnit.SECONDS))
    }
    private fun awaitReplacement(): WebSocket {
        val pair = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertEquals(2, pair.first)
        assertEquals(2 to ApiClient.currentToken(), auth.poll(5, TimeUnit.SECONDS))
        waitFor { events.count { it } == 2 }
        return pair.second
    }

    @Test fun heldHandshakeCannotAuthenticateAsEitherAccountAfterSwitch() = runBlocking {
        startHeld()
        ApiClient.logout()
        ApiClient.verifyCode("+70000000000", "000000", "B").getOrThrow()
        release.countDown()
        assertEquals(1, requireNotNull(opened.poll(5, TimeUnit.SECONDS)).first)
        assertNull("Stale held socket sent an auth frame", auth.poll(1500, TimeUnit.MILLISECONDS))
        assertTrue(events.toString(), events.isEmpty())
        assertFalse(chat.send("wrong-owner"))
        chat.close()
        chat = newChat()
        chat.connect()
        val current = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertEquals(2 to "access-B", auth.poll(5, TimeUnit.SECONDS))
        assertCurrentConversation(current.second, 2)
        assertTrue(requests.filter { it.first.startsWith("GET /ws/") }.all { it.second == null && !it.first.contains('?') })
    }

    @Test fun closeBeforeHeldHandshakeDoesNotPublishAuthOrConnected() {
        startHeld()
        chat.close()
        release.countDown()
        assertNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertNull(auth.poll(1500, TimeUnit.MILLISECONDS))
        assertTrue(events.isEmpty())
        networkReturn()
        assertNull(opened.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(1, upgrades.get())
    }

    @Test fun heldHandshakeUsesRefreshedTokenOfTheSameSession() {
        startHeld()
        refreshSameSession()
        release.countDown()
        val current = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertEquals("Expired pre-upgrade token must not be sent", 1 to "access-A2", auth.poll(5, TimeUnit.SECONDS))
        assertCurrentConversation(current.second, 1)
    }

    @Test fun normalReconnectAfterRefreshUsesTheCurrentToken() {
        val original = firstSocket()
        refreshSameSession()
        assertTrue(original.close(1001, "local restart"))
        assertCurrentConversation(awaitReplacement(), 2)
        assertEquals(2, upgrades.get())
    }

    @Test fun expiredOldSocketRecoversAfterTheSameSessionAlreadyRefreshed() {
        val original = firstSocket()
        refreshSameSession()
        assertTrue(original.close(1008, "local expired old token"))
        val current = awaitReplacement()
        assertCurrentConversation(current, 2)
        assertEquals("Do not refresh again just for the already-updated socket", 1, refreshes.get())
        assertNull(auth.poll(1500, TimeUnit.MILLISECONDS))
        assertEquals(2, upgrades.get())
    }

    @Test fun policyRefusalWithUnchangedCredentialsIsTerminal() {
        val original = firstSocket()
        assertTrue(original.close(1008, "local policy refusal"))
        waitFor { events == listOf(true, false) }
        assertNull(auth.poll(1500, TimeUnit.MILLISECONDS))
        assertEquals(1, upgrades.get())
    }

    @Test fun policyRefusalOfFreshHandshakeCredentialsDoesNotLoop() {
        startHeld()
        refreshSameSession()
        rejectAuth = true
        release.countDown()
        assertEquals(1 to "access-A2", auth.poll(5, TimeUnit.SECONDS))
        waitFor { events == listOf(true, false) }
        assertNull(auth.poll(1500, TimeUnit.MILLISECONDS))
        assertEquals(1, upgrades.get())
    }

    @Test fun lateOpenFromReplacedHeldConnectionDoesNotPublishAuthOrConnected() {
        val old = startHeld()
        val oldListener = listener(old)
        networkReturn()
        val current = requireNotNull(opened.poll(5, TimeUnit.SECONDS))
        assertEquals(2, current.first)
        assertEquals(2 to "access-A", auth.poll(5, TimeUnit.SECONDS))
        waitFor { events == listOf(true) }
        release.countDown()
        assertEquals(1, requireNotNull(opened.poll(5, TimeUnit.SECONDS)).first)
        // Deterministic callback injection in addition to the actual delayed upgrade above.
        oldListener.onOpen(old, Response.Builder().request(Request.Builder().url(server.url("/")).build())
            .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        assertNull(auth.poll(700, TimeUnit.MILLISECONDS))
        assertEquals(listOf(true), events.toList())
        assertCurrentConversation(current.second, 2)
    }

    @Test fun replacedConnectionMessagesAndRejectionsCannotReachTheCurrentConversation() {
        firstSocket()
        val old = clientSocket()
        val oldListener = listener(old)
        networkReturn()
        val current = awaitReplacement()
        // Simulates callbacks already queued by the old connection; not live wire delivery.
        oldListener.onMessage(old, frame(77, "old-private-frame"))
        oldListener.onMessage(old, """{"type":"rejected","temp_id":-77,"reason":"old refusal"}""")
        assertNull(received.poll(300, TimeUnit.MILLISECONDS))
        assertNull(rejections.poll(300, TimeUnit.MILLISECONDS))
        assertCurrentConversation(current, 2)
        assertEquals(listOf(true, true), events.toList())
    }

    @Test fun replacedConnectionClosingClosedAndFailureCannotDisconnectOrRestartCurrentSocket() {
        firstSocket()
        val old = clientSocket()
        val oldListener = listener(old)
        networkReturn()
        val current = awaitReplacement()
        oldListener.onClosing(old, 1008, "old closing")
        oldListener.onClosed(old, 1001, "old closed")
        oldListener.onFailure(old, IOException("local old failure"), null)
        assertEquals(listOf(true, true), events.toList())
        assertCurrentConversation(current, 2)
        assertNull(auth.poll(1500, TimeUnit.MILLISECONDS))
        assertEquals(2, upgrades.get())
    }

    @Test fun reconnectFromDisconnectedCallbackIsNotReplacedByAnOldTimer() {
        val original = firstSocket()
        reconnectInCallback = true
        assertTrue(original.close(1001, "local restart"))
        val current = awaitReplacement()
        assertCurrentConversation(current, 2)
        assertNull("Old close callback scheduled a timer over the replacement", auth.poll(1800, TimeUnit.MILLISECONDS))
        assertEquals(2, upgrades.get())
    }

    @Test fun reconnectFromFailureCallbackIsNotReplacedByAnOldTimer() {
        firstSocket()
        val originalClient = clientSocket()
        reconnectInCallback = true
        // Cancel the actual OkHttp client transport, producing onFailure rather than a policy close.
        originalClient.cancel()
        val current = awaitReplacement()
        assertCurrentConversation(current, 2)
        assertNull("Old failure callback scheduled a timer over the replacement", auth.poll(1800, TimeUnit.MILLISECONDS))
        assertEquals(2, upgrades.get())
    }
}
