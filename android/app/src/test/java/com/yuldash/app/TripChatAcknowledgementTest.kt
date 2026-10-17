package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.TripPassStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * QA-B05-001: actual TripChatState/ChatSocket/Outbox over loopback HTTP and WebSocket.
 * The fixture stores each WS message but deliberately withholds its acknowledgement.
 * Its keyed ledger models the server contract; actual database deduplication is a backend gate.
 * MemoryDiskPreferences.restarted() is a fresh disk read, not physical Android process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripChatAcknowledgementTest {
    private data class SentFrame(val body: JSONObject, val durableQueue: String?)
    private data class Post(val key: String, val text: String, val bearer: String?)
    private data class StoredMessage(val id: Int, val text: String)

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var clock: TestCoroutineScheduler
    private lateinit var chat: TripChatState
    private var plain = MemoryDiskPreferences()
    private val chats = mutableListOf<TripChatState>()
    private val frames = CopyOnWriteArrayList<SentFrame>()
    private val posts = CopyOnWriteArrayList<Post>()
    private val auth = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val closedServerSockets = CopyOnWriteArrayList<WebSocket>()
    /** Server-side close handshake and failures, in order: printed only when a wait times out. */
    private val serverSocketEvents = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<String>()
    private val historyCalls = AtomicInteger()
    private val ledger = linkedMapOf<String, StoredMessage>()
    @Volatile private var rejectStatus = 0
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        clock = TestCoroutineScheduler()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path" to request.getHeader("Authorization")
                    if (path == "/ws/bookings/42") return MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSockets += webSocket
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = JSONObject(text)
                                when (frame.optString("type")) {
                                    "auth" -> auth += frame.getString("token")
                                    "message" -> {
                                        // Normally accept without echo; rejection cases never persist.
                                        val status = rejectStatus
                                        if (status == 0) store(frame.getString("request_id"), frame.getString("text"))
                                        frames += SentFrame(frame, plain.restarted().getString("queue", null))
                                        if (status != 0) webSocket.send(JSONObject().put("type", "rejected")
                                            .put("status_code", status).put("reason", if (status == 429) "too_fast" else "invalid_request")
                                            .put("temp_id", frame.getInt("temp_id"))
                                            .put("request_id", frame.getString("request_id")).toString())
                                    }
                                }
                            }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                serverSocketEvents += "ws#${serverSockets.indexOf(webSocket)} closing $code $reason"
                                webSocket.close(code, null)
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                serverSocketEvents += "ws#${serverSockets.indexOf(webSocket)} closed $code"
                                closedServerSockets += webSocket
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                // A failed socket never reaches onClosed, so cleanup would wait on it in vain.
                                serverSocketEvents += "ws#${serverSockets.indexOf(webSocket)} failed $t"
                            }
                        },
                    )
                    if (path == "/bookings/42/messages" && request.method == "POST") {
                        val key = request.getHeader("Idempotency-Key").orEmpty()
                        val text = JSONObject(request.body.readUtf8()).getString("text")
                        posts += Post(key, text, request.getHeader("Authorization"))
                        store(key, text)
                        return MockResponse().setBody("{}")
                    }
                    if (path == "/bookings/42/messages" && request.method == "GET") {
                        historyCalls.incrementAndGet()
                        val items = synchronized(ledger) {
                            JSONArray().apply {
                                ledger.values.forEach { item -> put(JSONObject().put("id", item.id)
                                    .put("sender_id", 11).put("text", item.text)) }
                            }
                        }
                        return MockResponse().setBody(JSONObject().put("items", items).toString())
                    }
                    return MockResponse().setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1500
        ApiClient.init(context)
        ApiClient.saveToken(tokenA)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(plain, null)
        chat = newChat()
    }

    @After fun cleanup() {
        chats.forEach { it.socket?.close(); it.scope.cancel() }
        waitFor { chats.all { it.scope.coroutineContext[Job]?.children?.none() != false } }
        serverSockets.forEach { it.close(1000, null) }
        waitFor { serverSockets.all { it in closedServerSockets } }
        // Detach fault stores so teardown does not consume the storage failure under test.
        Outbox.initStores(MemoryDiskPreferences(), null)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        val bearer = ApiClient.currentToken()?.let { "Bearer $it" }
        ApiClient.logout()
        if (bearer != null) waitFor { requests.contains("POST /auth/logout" to bearer) }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private fun store(key: String, text: String): StoredMessage = synchronized(ledger) {
        if (key.isBlank()) failures += "Missing message request identity"
        ledger.getOrPut(key) { StoredMessage(501 + ledger.size, text) }
    }
    private fun storedCount() = synchronized(ledger) { ledger.size }
    private fun messageId(frame: SentFrame) = synchronized(ledger) {
        requireNotNull(ledger[frame.body.getString("request_id")]).id
    }
    private fun newChat(): TripChatState {
        val generation = ApiClient.queueSessionGeneration()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clock) +
            CoroutineExceptionHandler { _, error -> failures += error.stackTraceToString() })
        return TripChatState(context, 42, generation, scope,
            isActive = { ApiClient.isCurrentSession(generation) },
            isSessionCurrent = { ApiClient.isCurrentSession(generation) },
            texts = mutableStateOf(TripChatTexts("send", "fast", "queued", "disk", "send", "action", "voice")),
        ).also { chats += it }
    }
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        do {
            clock.runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            assertTrue(failures.toString(), failures.isEmpty())
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        // Work dispatched during the last sleep still counts as inside the bound.
        clock.runCurrent()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (!condition()) fail("Expected bounded asynchronous boundary\n" + pendingWork())
    }
    /**
     * Main went red once in cleanup waiting for server sockets to reach onClosed (17.10, run 39009915095,
     * line `waitFor { serverSockets.all { it in closedServerSockets } }`); the log did not say why.
     * Name what is still open, how each server socket ended, and what the socket threads are doing.
     */
    private fun pendingWork(): String = buildString {
        append("failures=$failures\n")
        chats.forEachIndexed { i, c ->
            val job = c.scope.coroutineContext[Job]
            append("chat[$i] scope=$job children=${job?.children?.toList()}\n")
        }
        serverSockets.forEachIndexed { i, s ->
            append("ws#$i ${if (s in closedServerSockets) "closed" else "NOT closed"} $s\n")
        }
        append("server socket events=$serverSocketEvents\n")
        Thread.getAllStackTraces().forEach { (thread, stack) ->
            if (stack.any { f -> listOf("com.yuldash", "okhttp3", "mockwebserver").any { f.className.contains(it) } }) {
                append("thread ${thread.name} ${thread.state}\n")
                stack.take(25).forEach { append("    at $it\n") }
            }
        }
    }
    private fun connect() {
        chat.socket!!.connect()
        waitFor { chat.wsConnected && auth.contains(tokenA) }
    }
    private fun frame(index: Int = 0): SentFrame {
        waitFor { frames.size > index }
        return frames[index]
    }
    private fun ack(frame: SentFrame, sender: Int = 11, id: Int = messageId(frame)) {
        assertTrue(serverSockets.last().send(JSONObject().put("type", "message").put("id", id)
            .put("sender_id", sender).put("text", frame.body.getString("text"))
            .put("request_id", frame.body.getString("request_id")).toString()))
    }
    private fun ackWindow() {
        // Never advanceUntilIdle: it would also consume timers that this test must hold.
        clock.advanceTimeBy(TripChatState.WS_ACK_TIMEOUT_MS + 1)
        clock.runCurrent()
    }
    private fun durableItems() = JSONArray(plain.restarted().getString("queue", "[]"))
    private fun stopCurrentScreen() {
        chat.socket!!.close()
        chat.scope.cancel()
        waitFor { chat.scope.coroutineContext[Job]!!.children.none() }
    }

    @Test fun lostAcknowledgementFallsBackWithDurableSameKeyAndOneMessage() {
        connect()
        chat.sendText("uncertain delivery")
        val sent = frame()
        val key = sent.body.getString("request_id")
        assertTrue("The disk commit must precede the WS frame", sent.durableQueue.orEmpty().contains(key))
        assertEquals(key, durableItems().getJSONObject(0).getString("request_key"))
        assertEquals(1, Outbox.count(context, 42))
        assertTrue(posts.isEmpty())
        repeat(3) { chat.retry(-2, "uncertain delivery") }
        ackWindow()
        waitFor { posts.size == 1 && Outbox.count(context, 42) == 0 && historyCalls.get() == 1 }
        waitFor { chat.messages.size == 1 && chat.messages.single().id == messageId(sent) }
        assertEquals(Post(key, "uncertain delivery", "Bearer $tokenA"), posts.single())
        assertEquals(1, frames.size)
        assertEquals(1, storedCount())
        ackWindow()
        assertEquals(1, posts.size)
    }

    @Test fun recreatedQueueAfterLostAcknowledgementReplaysOriginalKey() = runBlocking {
        connect()
        chat.sendText("restart before ack")
        val sent = frame()
        stopCurrentScreen()
        plain = plain.restarted()
        Outbox.initStores(plain, null)
        assertEquals(1, Outbox.count(context, 42))
        assertTrue(Outbox.flush(context))
        assertEquals(sent.body.getString("request_id"), posts.single().key)
        assertEquals(1, storedCount())
        assertEquals(0, Outbox.count(context, 42))
        assertFalse(Outbox.flush(context))
        ackWindow()
        assertEquals(1, posts.size)
    }

    @Test fun earlyAcknowledgementRemovesOnlyItsActionAndPreventsRestFallback() {
        val unrelated = Outbox.newMessage(99, "other booking")
        assertTrue(Outbox.enqueue(context, unrelated))
        connect()
        chat.sendText("early acknowledgement")
        val sent = frame()
        ack(sent)
        waitFor { Outbox.count(context, 42) == 0 && chat.messages.single().id == messageId(sent) }
        assertEquals(1, Outbox.count(context, 99))
        assertEquals(unrelated.requestKey, durableItems().getJSONObject(0).getString("request_key"))
        chat.retry(-2, "early acknowledgement")
        ackWindow()
        assertTrue(posts.isEmpty())
        assertEquals(1, frames.size)
    }

    @Test fun duplicateEchoAndOtherSenderDoNotConsumeSecondSameTextPendingMessage() {
        connect()
        chat.sendText("same text")
        val first = frame()
        chat.sendText("same text")
        val second = frame(1)
        assertNotEquals(first.body.getString("request_id"), second.body.getString("request_id"))
        ack(first)
        waitFor { Outbox.count(context, 42) == 1 && chat.messages.any { it.id == messageId(first) } }
        ack(first)
        // An incoming participant message cannot acknowledge our pending send, even with its key.
        ack(second, sender = 22, id = 901)
        waitFor { chat.messages.any { it.id == 901 } }
        assertEquals(1, chat.messages.count { it.id == messageId(first) })
        assertEquals(1, chat.messages.count { it.id == -3 })
        assertEquals(1, Outbox.count(context, 42))
        assertEquals(second.body.getString("request_id"), durableItems().getJSONObject(0).getString("request_key"))
        ackWindow()
        waitFor { posts.size == 1 && Outbox.count(context, 42) == 0 }
        assertEquals(second.body.getString("request_id"), posts.single().key)
        assertEquals(2, storedCount())
    }

    @Test fun permanentRejectionThenImmediateScreenCloseCannotReplayAfterQueueReopen() = runBlocking {
        connect()
        rejectStatus = 422
        chat.sendText("permanently rejected")
        frame()
        waitFor { -2 in chat.failedIds }
        // Cancel as soon as the rejection is visible, before advancing the 5-second timer.
        stopCurrentScreen()
        plain = plain.restarted()
        Outbox.initStores(plain, null)
        assertEquals(0, Outbox.count(context, 42))
        assertEquals(0, durableItems().length())
        assertFalse(Outbox.flush(context))
        ackWindow()
        assertTrue(posts.isEmpty())
        assertEquals(1, frames.size)
        assertEquals(0, storedCount())
    }

    @Test fun temporaryRejectionSurvivesScreenCloseAndReplaysOriginalKey() = runBlocking {
        connect()
        rejectStatus = 429
        chat.sendText("temporarily rejected")
        val sent = frame()
        waitFor { -2 in chat.queuedIds }
        stopCurrentScreen()
        plain = plain.restarted()
        Outbox.initStores(plain, null)
        assertEquals(1, Outbox.count(context, 42))
        val key = sent.body.getString("request_id")
        assertEquals(key, durableItems().getJSONObject(0).getString("request_key"))
        assertTrue(posts.isEmpty())
        assertEquals(0, storedCount())
        assertTrue(Outbox.flush(context))
        assertEquals(Post(key, "temporarily rejected", "Bearer $tokenA"), posts.single())
        assertEquals(1, storedCount())
        assertEquals(0, Outbox.count(context, 42))
        ackWindow()
        assertEquals(1, posts.size)
    }

    @Test fun failedFirstDiskCommitSendsNeitherWebSocketNorHttp() {
        connect()
        plain.failWriteOf = "queue"
        chat.sendText("must remain unsent")
        waitFor { -2 in chat.failedIds }
        assertEquals(0, Outbox.count(context, 42))
        assertEquals(0, durableItems().length())
        ackWindow()
        assertTrue(frames.isEmpty())
        assertTrue(posts.isEmpty())
        assertEquals(0, storedCount())
    }

    @Test fun failedAcknowledgementCleanupKeepsOriginalKeyWithoutResendingFromScreen() = runBlocking {
        connect()
        chat.sendText("ack with failed cleanup")
        val sent = frame()
        plain.failWriteOf = "queue"
        ack(sent)
        waitFor { chat.messages.single().id == messageId(sent) && Outbox.count(context, 42) == 1 }
        chat.retry(-2, "ack with failed cleanup")
        ackWindow()
        assertTrue(posts.isEmpty())
        assertEquals(1, frames.size)
        assertEquals(sent.body.getString("request_id"), durableItems().getJSONObject(0).getString("request_key"))
        stopCurrentScreen()
        plain = plain.restarted()
        Outbox.initStores(plain, null)
        assertTrue(Outbox.flush(context))
        assertEquals(sent.body.getString("request_id"), posts.single().key)
        assertEquals(1, storedCount())
    }

    @Test fun ownerChangeWhileWaitingForAcknowledgementCannotPostAsNextAccount() {
        connect()
        chat.sendText("belongs to A")
        frame()
        ApiClient.saveToken(tokenB)
        ackWindow()
        assertEquals(tokenB, ApiClient.currentToken())
        assertTrue(posts.isEmpty())
        assertEquals(1, frames.size)
    }

    @Test fun ownerChangeInsideQueueCommitCannotSendForEitherAccount() {
        val switched = AtomicInteger()
        // One synchronous hook isolates a login boundary inside a real durable queue write.
        // The normal logout's separate queue reset is covered by the owner-boundary suite.
        val fault = OnQueueCommit(plain) {
            ApiClient.saveToken(tokenB)
            switched.incrementAndGet()
        }
        Outbox.initStores(fault, null)
        connect()
        chat.sendText("owner changed during save")
        waitFor { switched.get() == 1 && chat.scope.coroutineContext[Job]!!.children.none() }
        ackWindow()
        assertEquals(tokenB, ApiClient.currentToken())
        assertTrue(frames.isEmpty())
        assertTrue(posts.isEmpty())
        assertEquals(0, storedCount())
    }

    @Test fun acknowledgementCannotRemoveDifferentActionWithSameNumericId() {
        val current = Outbox.newMessage(42, "current action")
        assertTrue(Outbox.enqueue(context, current))
        assertTrue(Outbox.acknowledge(context, current.copy(payload = "old action"), ApiClient.queueSessionGeneration()))
        assertEquals(1, Outbox.count(context, 42))
        assertTrue(Outbox.acknowledge(context, current, ApiClient.queueSessionGeneration()))
        assertEquals(0, Outbox.count(context, 42))
    }

    private class OnQueueCommit(
        private val delegate: MemoryDiskPreferences,
        private var beforeFirstWrite: (() -> Unit)?,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val real = delegate.edit()
            return object : SharedPreferences.Editor by real {
                private var writesQueue = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (key == "queue" && value != null) writesQueue = true
                    real.putString(key, value)
                    return this
                }
                override fun commit(): Boolean {
                    if (writesQueue) beforeFirstWrite?.also { beforeFirstWrite = null }?.invoke()
                    return real.commit()
                }
            }
        }
    }
}
