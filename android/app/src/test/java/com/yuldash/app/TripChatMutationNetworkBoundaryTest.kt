package com.yuldash.app

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real TripChatState handlers, HTTP and Outbox. Synthetic ledger is not the production DB. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripChatMutationNetworkBoundaryTest {
    private lateinit var context: Context
    private lateinit var clock: TestCoroutineScheduler
    private lateinit var server: MockWebServer
    private lateinit var chat: TripChatState
    private val requests = CopyOnWriteArrayList<String>()
    private val failures = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String>()
    private val ledger = linkedMapOf<String, JSONObject>()
    private val files = mutableListOf<File>()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile private var mode = "normal"
    @Volatile private var text = "initial"
    @Volatile private var deleted = false
    @Volatile private var hidden = false
    @Volatile private var hideLedger = false
    @Volatile private var mounted = true
    @Volatile private var uploadCount = 0
    @Volatile private var failHistory = false
    @Volatile private var holdHistory = false
    private val historyHeld = CountDownLatch(1)
    private val releaseHistory = CountDownLatch(1)
    private val socketOpened = CountDownLatch(1)
    private var serverSocket: WebSocket? = null
    private val sentFrames = CopyOnWriteArrayList<JSONObject>()
    private val activeReads = AtomicInteger()
    private val maxReads = AtomicInteger()
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"

    @Before fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        clock = TestCoroutineScheduler()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    requests += "${request.method} $path"
                    if (path.startsWith("/ws/bookings/42")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            serverSocket = webSocket; socketOpened.countDown()
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = JSONObject(text)
                            if (frame.optString("type") == "message") sentFrames += frame
                        }
                    })
                    if (path == "/auth/logout") return MockResponse().setBody("{}")
                    if (request.method == "GET" && path == "/bookings/42/messages") {
                        val active = activeReads.incrementAndGet(); maxReads.updateAndGet { maxOf(it, active) }
                        try {
                        if (failHistory) return MockResponse().setResponseCode(503).setBody("{}")
                        val items = JSONArray()
                        if (!hidden) items.put(JSONObject().put("id", 1).put("sender_id", 11).put("text", text).put("deleted", deleted))
                        items.put(JSONObject().put("id", 2).put("sender_id", 22).put("text", "peer"))
                        synchronized(ledger) {
                            if (!hideLedger) ledger.values.forEachIndexed { i, value -> items.put(JSONObject(value.toString()).put("id", 100+i).put("sender_id", 11)) }
                        }
                        val snapshot = JSONObject().put("items", items).toString()
                        if (holdHistory) {
                            holdHistory = false; historyHeld.countDown()
                            if (!releaseHistory.await(12, TimeUnit.SECONDS)) failures += "held history timed out"
                        }
                        return MockResponse().setBody(snapshot)
                        } finally { activeReads.decrementAndGet() }
                    }
                    if (path == "/bookings/42/messages/1/edit" || request.method == "DELETE") {
                        val operationMode = mode
                        if (operationMode == "held" || operationMode == "held-lost") {
                            held.countDown()
                            if (!release.await(12, TimeUnit.SECONDS)) failures += "held action timed out"
                        }
                        if (operationMode == "before") return MockResponse().setResponseCode(503).setBody("{}")
                        if (request.method == "DELETE") {
                            if (path.endsWith("scope=me")) hidden = true else { deleted = true; text = "" }
                        } else text = JSONObject(request.body.readUtf8()).getString("text")
                        return MockResponse().setResponseCode(if (operationMode == "lost" || operationMode == "held-lost") 503 else 200).setBody("{}")
                    }
                    if (path == "/upload/chat-photo" || path == "/voice") {
                        uploadCount++
                        if (mode == "upload-held") {
                            held.countDown()
                            if (!release.await(12, TimeUnit.SECONDS)) failures += "held upload timed out"
                        }
                        if (mode == "upload-fail") return MockResponse().setResponseCode(503).setBody("{}")
                        return MockResponse().setBody(JSONObject().put("url", server.url(if (path == "/voice") "/voice.m4a" else "/photo.jpg").toString()).toString())
                    }
                    if (request.method == "POST" && path == "/bookings/42/messages") {
                        val key = request.getHeader("Idempotency-Key").orEmpty()
                        keys += key
                        val payload = JSONObject(request.body.readUtf8())
                        if (mode == "post-refused") return MockResponse().setResponseCode(403).setBody("""{"detail":"blocked"}""")
                        synchronized(ledger) { ledger.getOrPut(key.ifBlank { "unkeyed-${keys.size}" }) { payload } }
                        if (mode == "post-held-lost" || mode == "post-held") {
                            held.countDown()
                            if (!release.await(12, TimeUnit.SECONDS)) failures += "held voice message timed out"
                        }
                        val reply = JSONObject(payload.toString()).put("id", 100).put("sender_id", 11)
                        if (mode.startsWith("hidden-receipt")) {
                            val receipt = JSONObject().put("id", 100).put("sender_id", 11).put("request_id", key).put("hidden_for_me", true)
                            when (mode) {
                                "hidden-receipt-id" -> receipt.put("id", 100.5)
                                "hidden-receipt-owner" -> receipt.put("sender_id", 22)
                                "hidden-receipt-key" -> receipt.put("request_id", "wrong-key")
                            }
                            return MockResponse().setBody(receipt.toString())
                        }
                        when (mode) {
                            "voice-bad-id" -> reply.put("id", 0)
                            "voice-bad-owner" -> reply.put("sender_id", 22)
                            "voice-bad-url" -> reply.put("voice_url", "https://invalid.example/other.m4a")
                            "voice-deleted" -> reply.put("deleted", true)
                        }
                        return MockResponse().setResponseCode(if (mode == "post-lost" || mode == "post-held-lost") 503 else 200)
                            .setBody(if (mode == "voice-empty") "{}" else reply.toString())
                    }
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1500
        ApiClient.init(context)
        ApiClient.saveToken(token)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        val generation = ApiClient.queueSessionGeneration()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clock) +
            CoroutineExceptionHandler { _, error -> failures += error.stackTraceToString() })
        chat = TripChatState(context, 42, generation, scope,
            isActive = { mounted && ApiClient.isCurrentSession(generation) },
            isSessionCurrent = { ApiClient.isCurrentSession(generation) },
            texts = mutableStateOf(TripChatTexts("send", "fast", "queued", "disk", "send", "unknown", "voice")))
        chat.scope.launch { chat.loadHistory(42) }
        idle()
        assertEquals(listOf(1, 2), chat.messages.map { it.id })
    }

    @After fun cleanup() {
        release.countDown()
        releaseHistory.countDown()
        chat.socket?.close()
        chat.scope.cancel()
        waitFor { chat.scope.coroutineContext[Job]?.children?.none() != false }
        Outbox.initStores(MemoryDiskPreferences(), null)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        ApiClient.logout()
        waitFor { requests.contains("POST /auth/logout") }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
        files.forEach { it.delete() }
        assertTrue(failures.toString(), failures.isEmpty())
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            clock.runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertTrue("Timed out: requests=$requests failures=$failures", condition())
    }
    private fun idle() = waitFor { chat.scope.coroutineContext[Job]?.children?.none() != false }
    private fun edit() { chat.startEdit(chat.messages.first { it.id == 1 }); chat.updateDraft("changed"); chat.submitDraft() }
    private fun voiceFile(): File = File.createTempFile("voice_", ".m4a", context.cacheDir).also {
        it.writeBytes(byteArrayOf(1, 2, 3, 4)); files += it
    }

    @Test fun missedOwnHideThenVoiceRetryAcknowledgesWithoutReappearing() {
        mode = "post-lost"; chat.sendVoice(voiceFile().absolutePath); idle()
        assertTrue(chat.canRetryAction)
        hideLedger = true; chat.scope.launch { chat.loadHistory(42) }; idle()
        mode = "hidden-receipt"; failHistory = true; chat.retryAction(); idle()
        assertFalse("Hidden receipt was treated as failed delivery", chat.canRetryAction)
        assertTrue(chat.visibleMessages.none { it.id == 100 })
        assertEquals(1, uploadCount); assertEquals(2, keys.size); assertEquals(1, keys.distinct().size)
    }

    @Test fun delayedOriginalVoiceAfterConnectionLossCannotUndoMissedHide() {
        connectLive(); mode = "post-held"; chat.sendVoice(voiceFile().absolutePath)
        waitFor { held.count == 0L }
        serverSocket!!.close(1000, "synthetic network boundary")
        waitFor { !chat.wsConnected }
        hideLedger = true; chat.scope.launch { chat.loadHistory(42) }
        waitFor { historyCount() >= 2 }; failHistory = true; release.countDown(); idle()
        assertTrue(chat.visibleMessages.none { it.id == 100 })
        assertFalse(chat.canRetryAction); assertEquals(1, uploadCount); assertEquals(1, keys.size)
    }

    @Test fun malformedHiddenVoiceReceiptsKeepSameUncertainIntent() {
        mode = "post-lost"; chat.sendVoice(voiceFile().absolutePath); idle(); hideLedger = true; failHistory = true
        for (bad in listOf("id", "owner", "key")) {
            mode = "hidden-receipt-$bad"; chat.retryAction(); idle()
            assertTrue("Malformed $bad receipt consumed voice intent", chat.canRetryAction)
            assertTrue(chat.visibleMessages.none { it.id == 100 })
        }
        mode = "hidden-receipt"; chat.retryAction(); idle(); assertFalse(chat.canRetryAction)
        assertEquals(1, keys.distinct().size); assertEquals(1, uploadCount)
    }

    @Test fun malformedHiddenQueuedReceiptCannotRemoveDurableIntent() {
        mode = "post-lost"; chat.sendText("private queued text"); idle()
        assertEquals(1, Outbox.count(context, 42)); hideLedger = true; failHistory = true
        for (bad in listOf("id", "owner", "key")) {
            mode = "hidden-receipt-$bad"; chat.scope.launch { chat.flushQueued() }; idle()
            assertEquals(1, Outbox.count(context, 42)); assertTrue(chat.messages.any { it.id < 0 })
        }
        mode = "hidden-receipt"; chat.scope.launch { chat.flushQueued() }; idle()
        assertEquals(0, Outbox.count(context, 42)); assertTrue(chat.messages.none { it.id < 0 || it.id == 100 })
    }

    @Test fun backgroundQueueHiddenReceiptAlsoRemovesPreviouslyLoadedPositiveWhenGetFails() {
        mode = "post-lost"; chat.sendText("previously loaded private"); idle()
        chat.scope.launch { chat.loadHistory(42) }; idle()
        assertTrue(chat.messages.any { it.id == 100 }); assertTrue(chat.messages.any { it.id < 0 })
        hideLedger = true; failHistory = true; mode = "hidden-receipt"
        chat.scope.launch { Outbox.flush(context, expectedGeneration = ApiClient.queueSessionGeneration()) }; idle()
        assertEquals(0, Outbox.count(context, 42)); assertTrue(chat.messages.none { it.id < 0 || it.id == 100 })
    }

    @Test fun ownedUnknownWsAckRemovesOnlyConfirmedTempEvenWhenHistoryFails() {
        connectLive(); changed(); waitFor { historyCount() >= 2 }; idle(); failHistory = true
        chat.sendText("first private"); chat.sendText("second still unconfirmed")
        waitFor { sentFrames.size == 2 }
        val key = sentFrames[0].getString("request_id")
        assertTrue(serverSocket!!.send(JSONObject().put("type", "message").put("id", 777)
            .put("sender_id", 11).put("request_id", key).put("text", "first private").toString()))
        waitFor { Outbox.count(context, 42) == 1 && chat.messages.none { it.text == "first private" } }
        assertTrue(chat.messages.any { it.id < 0 && it.text == "second still unconfirmed" })
        assertTrue(chat.messages.none { it.id == 777 })
        mounted = false // Cancel the remaining send before its ordinary timeout; it is still durable.
    }

    @Test fun ownHiddenWsAckRemovesBothPendingAndKnownRowsWithoutContent() {
        connectLive(); failHistory = true; chat.sendText("pending hidden")
        waitFor { sentFrames.size == 1 }
        assertTrue(serverSocket!!.send(JSONObject().put("type", "hidden_ack").put("id", 1)
            .put("sender_id", 11).put("request_id", sentFrames.single().getString("request_id"))
            .put("hidden_for_me", true).toString()))
        waitFor { Outbox.count(context, 42) == 0 && chat.messages.none { it.id < 0 || it.id == 1 } }
        assertEquals(listOf(2), chat.messages.map { it.id })
    }

    @Test fun successfulHistoryRemembersKnownHideWithoutLiveSignal() {
        hidden = true; chat.scope.launch { chat.loadHistory(42) }; idle()
        connectLive()
        assertTrue(serverSocket!!.send("""{"type":"message","id":1,"sender_id":11,"text":"late hidden echo"}"""))
        Thread.sleep(120); clock.runCurrent(); idle()
        assertEquals(listOf(2), chat.messages.map { it.id })
    }

    @Test fun newVoiceAfterEarlierGapStillUsesStableFirstReceiptWhenHistoryFails() {
        failHistory = true; chat.continuityLost(); idle()
        chat.sendVoice(voiceFile().absolutePath); idle()
        assertTrue(chat.historyError); assertEquals(1, chat.visibleMessages.count { it.voiceUrl != null })
        assertEquals(1, keys.size); assertFalse(chat.canRetryAction)
    }

    @Test fun returnFencesVoicePostStartedWhileApplicationWasInBackground() {
        chat.continuityLost(); idle(); mode = "post-held"; chat.sendVoice(voiceFile().absolutePath)
        assertEquals("Уход в фон историю не перекачивает", 1, historyCount())
        waitFor { held.count == 0L }; hideLedger = true; chat.refreshAfterReturn()
        // Возврат читает историю один раз — уже со скрытием на сервере.
        waitFor { historyCount() >= 2 }; failHistory = true; release.countDown(); idle()
        assertTrue(chat.visibleMessages.none { it.id == 100 }); assertFalse(chat.canRetryAction)
        assertEquals(1, keys.size)
    }

    @Test fun continuityEpochInvalidatesReadBeforeMainCallbackRuns() {
        holdHistory = true; chat.scope.launch { chat.loadHistory(42) }; waitFor { historyHeld.count == 0L }
        hidden = true; chat.continuityLost(); releaseHistory.countDown(); idle()
        assertEquals(listOf(2), chat.messages.map { it.id }); assertEquals(1, maxReads.get())
        assertTrue(historyCount() >= 3)
    }

    @Test fun liveSocketReplacementWithoutFalseTransitionRequiresAuthorizedUnknownRows() {
        connectLive(); idle()
        // Кадр принят живым каналом ДО замены сокета, а применяется ПОСЛЕ неё (планировщик экрана стоит):
        // такой задержанный кадр не показывает неизвестную строку — решает только история (её там нет).
        assertTrue(serverSocket!!.send("""{"type":"message","id":777,"sender_id":22,"text":"missed hidden"}"""))
        awaitQueuedCallback()
        chat.socket!!.connect()
        waitFor { historyCount() >= 2 }; idle()
        assertTrue(chat.messages.none { it.id == 777 }); assertTrue(chat.wsConnected)
    }

    @Test fun freshFrameAfterReconnectIsShownLiveWithoutAnotherHistoryRead() {
        connectLive(); chat.socket!!.connect()
        // Замена сокета: историю дотягиваем один раз, когда новый канал жив.
        waitFor { historyCount() >= 2 }; idle()
        val reads = historyCount()
        assertTrue(serverSocket!!.send("""{"type":"message","id":778,"sender_id":22,"text":"fresh after reconnect"}"""))
        waitFor { chat.messages.any { it.id == 778 } }; idle()
        assertEquals("Новое сообщение после переподключения не перекачивает историю", reads, historyCount())
    }

    /** Настоящий WS-колбэк уже поставил работу экрана в очередь; сам планировщик экрана не двигаем. */
    private fun awaitQueuedCallback() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (chat.scope.coroutineContext[Job]!!.children.none()) {
            assertTrue("Кадр не дошёл до колбэка клиента", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }

    @Test fun confirmedVoiceRemainsVisibleWhenHistoryReadFails() {
        failHistory = true; chat.sendVoice(voiceFile().absolutePath); idle()
        val voice = chat.visibleMessages.singleOrNull { it.voiceUrl != null }
        assertNotNull("Confirmed voice was lost only because the following GET failed", voice)
        assertEquals(100, voice!!.id); assertEquals(11, voice.senderId)
        assertEquals(1, uploadCount); assertEquals(1, keys.size)
        assertFalse(chat.canRetryAction)
        assertTrue(files.none { it.exists() })
    }

    private fun invalidVoiceReceipt(replyMode: String) {
        mode = replyMode; chat.sendVoice(voiceFile().absolutePath); idle()
        assertTrue("An invalid voice receipt must remain an uncertain same-key send", chat.canRetryAction)
        assertTrue(chat.actionError.isNotBlank())
        assertTrue(chat.visibleMessages.none { it.voiceUrl != null })
        mode = "normal"; chat.retryAction(); idle()
        assertEquals(2, keys.size); assertEquals(keys[0], keys[1]); assertEquals(1, uploadCount)
        assertEquals(1, chat.visibleMessages.count { it.voiceUrl != null })
    }
    @Test fun emptyVoiceReceiptCannotConfirm() = invalidVoiceReceipt("voice-empty")
    @Test fun foreignVoiceReceiptCannotConfirm() = invalidVoiceReceipt("voice-bad-owner")
    @Test fun differentVoiceUrlCannotConfirm() = invalidVoiceReceipt("voice-bad-url")
    @Test fun nonpositiveVoiceIdCannotConfirm() = invalidVoiceReceipt("voice-bad-id")
    @Test fun deletedVoiceReceiptCannotConfirm() = invalidVoiceReceipt("voice-deleted")

    private fun connectLive() {
        chat.socket!!.connect(); waitFor { socketOpened.count == 0L && chat.wsConnected }
    }
    private fun historyCount() = requests.count { it == "GET /bookings/42/messages" }
    private fun changed() { assertTrue(serverSocket!!.send("""{"type":"history_changed"}""")) }

    @Test fun liveHistorySignalRefreshesExistingMessageWithoutDuplicate() {
        connectLive(); text = "edited remotely"; changed()
        waitFor { chat.messages.firstOrNull { it.id == 1 }?.text == "edited remotely" }
        assertEquals(listOf(1, 2), chat.messages.map { it.id }); assertEquals(2, historyCount())
    }

    @Test fun liveSignalsDuringHeldReadCoalesceAndDiscardOldSnapshot() {
        connectLive(); holdHistory = true; text = "old snapshot"; changed()
        waitFor { historyHeld.count == 0L }; text = "latest snapshot"; changed(); changed()
        // Let both signals reach the actual WebSocket callback while the first GET is held.
        Thread.sleep(120); clock.runCurrent(); releaseHistory.countDown()
        waitFor { chat.messages.firstOrNull { it.id == 1 }?.text == "latest snapshot" }; idle()
        assertEquals(1, maxReads.get()); assertEquals(3, historyCount())
    }

    @Test fun historySignalFailureRetainsRowsAndExposesReadError() {
        connectLive(); failHistory = true; changed()
        waitFor { chat.historyError }; idle()
        assertEquals(listOf(1, 2), chat.messages.map { it.id })
        assertFalse(chat.canRetryAction); assertEquals(0, keys.size)
    }

    @Test fun lateUnknownFrameAfterInvalidationMustUseAuthoritativeHistory() {
        connectLive(); changed(); waitFor { historyCount() == 2 }; idle()
        assertTrue(serverSocket!!.send("""{"type":"message","id":777,"sender_id":22,"text":"hidden unseen row"}"""))
        waitFor { historyCount() == 3 }; idle()
        assertTrue(chat.messages.none { it.id == 777 })
        assertEquals(listOf(1, 2), chat.messages.map { it.id })
    }

    @Test fun duplicateFrameCannotUndoAuthoritativeEdit() {
        connectLive(); text = "current edit"; changed()
        waitFor { chat.messages.firstOrNull { it.id == 1 }?.text == "current edit" }; idle()
        assertTrue(serverSocket!!.send("""{"type":"message","id":1,"sender_id":11,"text":"old echo"}"""))
        Thread.sleep(120); clock.runCurrent(); idle()
        assertEquals("current edit", chat.messages.first { it.id == 1 }.text)
        assertEquals(2, historyCount())
    }

    @Test fun localHideFiltersLateFrameAndSubsequentHistory() {
        connectLive(); chat.delete(1, "me"); idle()
        assertTrue(chat.messages.none { it.id == 1 })
        assertTrue(serverSocket!!.send("""{"type":"message","id":1,"sender_id":11,"text":"late hidden echo"}"""))
        Thread.sleep(120); clock.runCurrent(); chat.scope.launch { chat.loadHistory(42) }; idle()
        assertEquals(listOf(2), chat.messages.map { it.id })
    }

    @Test fun voiceReceiptAfterInvalidationCannotRevealUnknownRowWhenHistoryFails() {
        connectLive(); changed(); waitFor { historyCount() == 2 }; idle()
        failHistory = true; chat.sendVoice(voiceFile().absolutePath); idle()
        assertTrue(chat.historyError); assertFalse(chat.canRetryAction)
        assertTrue(chat.messages.none { it.voiceUrl != null })
        failHistory = false; chat.scope.launch { chat.loadHistory(42) }; idle()
        assertEquals(1, chat.messages.count { it.voiceUrl != null })
        assertEquals(1, uploadCount); assertEquals(1, keys.size)
    }

    @Test fun oldReadCannotEraseConfirmedVoiceEvenWhenFollowingReadFails() {
        holdHistory = true; chat.scope.launch { chat.loadHistory(42) }
        waitFor { historyHeld.count == 0L }
        chat.sendVoice(voiceFile().absolutePath)
        waitFor { chat.messages.any { it.id == 100 && it.voiceUrl != null } }
        failHistory = true; releaseHistory.countDown(); idle()
        assertTrue(chat.historyError)
        assertEquals(1, chat.messages.count { it.id == 100 && it.voiceUrl != null })
        assertEquals(1, maxReads.get()); assertEquals(1, uploadCount); assertEquals(1, keys.size)
    }

    private fun lateHistoryBoundary(ownerChange: Boolean) {
        connectLive(); holdHistory = true; text = "private late change"; changed()
        waitFor { historyHeld.count == 0L }
        if (ownerChange) ApiClient.saveToken("header.eyJzdWIiOiIzMyJ9.signature") else mounted = false
        releaseHistory.countDown(); idle()
        assertEquals("initial", chat.messages.first { it.id == 1 }.text)
        assertFalse(chat.historyError)
    }
    @Test fun historyInvalidationAfterOwnerChangeCannotApply() = lateHistoryBoundary(true)
    @Test fun historyInvalidationAfterUnmountCannotApply() = lateHistoryBoundary(false)

    @Test fun editNotSavedKeepsDraftAndEditingModeForExplicitRetry() {
        mode = "before"; edit(); idle()
        assertEquals("initial", text)
        assertEquals("changed", chat.draft)
        assertEquals(1, chat.editingId)
        mode = "normal"; chat.submitDraft(); idle()
        assertEquals("changed", text)
        assertEquals("", chat.draft)
        assertNull(chat.editingId)
        assertFalse(requests.any { it == "POST /bookings/42/messages" })
    }
    @Test fun editCommittedWithLostReplyKeepsExactIntentForExplicitRetry() {
        mode = "lost"; edit(); idle()
        assertEquals("changed", text)
        assertEquals("changed", chat.draft)
        assertEquals(1, chat.editingId)
        mode = "normal"; chat.submitDraft(); idle()
        assertEquals(2, requests.count { it.endsWith("/1/edit") })
        assertEquals("changed", chat.messages.first { it.id == 1 }.text)
    }
    @Test fun duplicateDeleteBeforeCoroutineStartsDispatchesOnce() {
        chat.delete(1, "me"); chat.delete(1, "me"); idle()
        assertEquals(1, requests.count { it == "DELETE /bookings/42/messages/1?scope=me" })
        assertFalse(chat.messages.any { it.id == 1 })
    }
    @Test fun deleteMineNotCommittedKeepsRowAndOffersSameScopeRetry() {
        mode = "before"; chat.delete(1, "me"); idle()
        assertFalse(hidden); assertTrue(chat.messages.any { it.id == 1 })
        assertEquals("unknown", chat.actionError)
        assertTrue(chat.canRetryAction)
        mode = "normal"; chat.retryAction(); idle()
        assertFalse(chat.messages.any { it.id == 1 })
        assertTrue(chat.actionError.isEmpty())
        assertFalse(chat.canRetryAction)
        assertEquals(2, requests.count { it == "DELETE /bookings/42/messages/1?scope=me" })
        assertFalse(requests.any { it.endsWith("scope=all") })
    }
    @Test fun deleteMineLostReplyUsesVisibleRetryWithoutChangingScope() {
        mode = "lost"; chat.delete(1, "me"); idle()
        assertEquals("unknown", chat.actionError)
        assertFalse(chat.actionBusy)
        mode = "normal"; chat.retryAction(); idle()
        assertTrue(chat.actionError.isEmpty())
        assertFalse(chat.messages.any { it.id == 1 })
        assertEquals("initial", text)
        assertFalse(deleted)
    }
    @Test fun synchronousBusyFenceAlsoStopsRetainedSecondEditSend() {
        mode = "held"; edit()
        assertTrue(chat.actionBusy)
        chat.submitDraft()
        waitFor { held.count == 0L }; release.countDown(); idle()
        assertFalse(chat.actionBusy)
        assertEquals(1, requests.count { it.endsWith("/1/edit") })
        assertFalse(requests.any { it == "POST /bookings/42/messages" })
    }
    @Test fun retainedRetryAfterEditCancelledCannotSendNewDraftAsMessage() {
        mode = "before"; edit(); idle()
        chat.cancelEdit(); chat.updateDraft("new unsent text")
        mode = "normal"; chat.retryAction(); idle()
        assertEquals("new unsent text", chat.draft)
        assertFalse(requests.any { it == "POST /bookings/42/messages" })
    }
    @Test fun photoUploadFailureWhileEditingCannotOfferUnrelatedEditRetry() {
        chat.startEdit(chat.messages.first { it.id == 1 }); chat.updateDraft("unsent edit")
        mode = "upload-fail"; chat.sendPhoto(byteArrayOf(1, 2, 3)); idle()
        assertEquals("unsent edit", chat.draft)
        assertEquals(1, chat.editingId)
        assertTrue(chat.actionError.isNotBlank())
        assertFalse("Upload failure must ask for a photo, not offer an edit retry", chat.canRetryAction)
        mode = "normal"; chat.retryAction(); idle()
        assertEquals("unsent edit", chat.draft)
        assertFalse(requests.any { it.endsWith("/1/edit") || it == "POST /bookings/42/messages" })
        assertEquals(1, uploadCount)
    }
    @Test fun cancelledHeldEditLateFailureCannotOfferRetryForNewEditIntent() {
        mode = "held-lost"; edit(); waitFor { held.count == 0L }
        chat.cancelEdit(); chat.updateDraft("new unsent text")
        release.countDown(); idle()
        assertTrue("Cancelled edit must not revive its late error", chat.actionError.isEmpty())
        assertFalse(chat.canRetryAction)
        chat.startEdit(chat.messages.first { it.id == 1 }); chat.updateDraft("new edit intent")
        mode = "normal"; chat.retryAction(); idle()
        assertEquals("new edit intent", chat.draft)
        assertEquals(1, requests.count { it.endsWith("/1/edit") })
        assertFalse(requests.any { it == "POST /bookings/42/messages" })
    }
    @Test fun confirmedDeleteMineStillHidesRowWhenSubsequentHistoryFails() {
        failHistory = true; chat.delete(1, "me"); idle()
        assertTrue(hidden); assertFalse(chat.messages.any { it.id == 1 })
        assertTrue(chat.actionError.isEmpty())
        assertEquals("initial", text)
    }
    @Test fun confirmedEditStillShowsTextWhenSubsequentHistoryFails() {
        failHistory = true; edit(); idle()
        assertEquals("changed", chat.messages.first { it.id == 1 }.text)
        assertTrue(chat.messages.first { it.id == 1 }.edited)
        assertEquals("", chat.draft)
        assertNull(chat.editingId)
        assertTrue(chat.actionError.isEmpty())
    }
    @Test fun retainedPhotoCallbackWhileUploadingCannotStartSecondUpload() {
        chat.sendPhoto(byteArrayOf(1, 2, 3)); chat.sendPhoto(byteArrayOf(1, 2, 3)); idle()
        assertEquals(1, uploadCount)
        assertEquals(1, keys.size)
        assertEquals(1, chat.messages.count { it.text.startsWith(ApiClient.IMG_PREFIX) })
    }
    @Test fun delayedEditCannotClobberNewerDraft() {
        mode = "held"; edit(); waitFor { held.count == 0L }
        chat.updateDraft("newer draft")
        release.countDown(); idle()
        assertEquals("newer draft", chat.draft)
        assertEquals(1, chat.editingId)
    }
    @Test fun deleteMineLostReplyRetryHidesOwnFeedWithoutDeletingStoredText() {
        mode = "lost"; chat.delete(1, "me"); idle()
        assertTrue(hidden); assertFalse(deleted); assertEquals("initial", text)
        assertTrue(chat.messages.any { it.id == 1 })
        mode = "normal"; chat.delete(1, "me"); idle()
        assertFalse(chat.messages.any { it.id == 1 })
        assertEquals("initial", text)
    }
    @Test fun deleteAllLostReplyRetryShowsTombstone() {
        mode = "lost"; chat.delete(1, "all"); idle()
        assertTrue(deleted); assertTrue(chat.messages.first { it.id == 1 }.text.isNotBlank())
        mode = "normal"; chat.delete(1, "all"); idle()
        assertTrue(chat.messages.first { it.id == 1 }.deleted)
    }
    @Test fun ownerChangeBeforeQueuedMutationCannotDispatchAsNextOwner() {
        edit(); ApiClient.saveToken("header.eyJzdWIiOiIzMyJ9.signature"); idle()
        assertFalse(requests.any { it.contains("/1/edit") })
        assertEquals("initial", text)
    }
    @Test fun unmountDuringDeleteCannotApplyLateHistory() {
        mode = "held"; chat.delete(1, "me"); waitFor { held.count == 0L }
        mounted = false; release.countDown(); idle()
        assertTrue(hidden)
        assertTrue(chat.messages.any { it.id == 1 })
        assertEquals(1, requests.count { it == "GET /bookings/42/messages" })
    }
    @Test fun photoCommittedMessageWithLostReplyUsesDurableSameKeyRecovery() {
        mode = "post-lost"; chat.sendPhoto(byteArrayOf(1, 2, 3)); idle()
        assertEquals(1, uploadCount)
        assertEquals(1, synchronized(ledger) { ledger.size })
        assertEquals(1, Outbox.count(context, 42))
        assertEquals(1, chat.queuedIds.size)
        assertTrue(keys.single().isNotBlank())
        val originalKey = keys.single()
        mode = "normal"; chat.scope.launch { chat.flushQueued() }; idle()
        assertEquals(listOf(originalKey, originalKey), keys.toList())
        assertEquals(1, uploadCount)
        assertEquals(1, synchronized(ledger) { ledger.size })
        assertEquals(0, Outbox.count(context, 42))
        assertTrue(chat.queuedIds.isEmpty())
        assertEquals(1, chat.messages.count { it.text.startsWith(ApiClient.IMG_PREFIX) })
    }
    @Test fun photoUploadFailureDoesNotPretendMediaIsDurablyQueued() {
        mode = "upload-fail"; chat.sendPhoto(byteArrayOf(1, 2, 3)); idle()
        assertEquals(1, uploadCount)
        assertTrue(keys.isEmpty())
        assertEquals(0, Outbox.count(context, 42))
        assertTrue(chat.queuedIds.isEmpty())
    }
    @Test fun voiceCommittedMessageWithLostReplyRetriesSameKeyWithoutReupload() {
        val file = voiceFile()
        mode = "post-lost"; chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertEquals(1, uploadCount)
        assertEquals(1, synchronized(ledger) { ledger.size })
        assertTrue(keys.single().isNotBlank())
        assertTrue(chat.canRetryAction)
        assertTrue(chat.actionError.isNotBlank())
        assertEquals(0, Outbox.count(context, 42))
        val key = keys.single()
        mode = "normal"; chat.retryAction(); idle()
        assertEquals(listOf(key, key), keys.toList())
        assertEquals(1, uploadCount)
        assertEquals(1, synchronized(ledger) { ledger.size })
        assertEquals(1, chat.messages.count { it.voiceUrl != null })
        assertTrue(chat.actionError.isEmpty())
        assertFalse(chat.canRetryAction)
    }
    @Test fun voiceUploadFailureClearsFileAndRequiresNewRecording() {
        val file = voiceFile()
        mode = "upload-fail"; chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
        assertTrue(chat.actionError.isNotBlank())
        assertFalse(chat.canRetryAction)
        mode = "normal"; chat.retryAction(); idle()
        assertTrue(keys.isEmpty())
        assertEquals(1, uploadCount)
        assertEquals(0, Outbox.count(context, 42))
    }
    @Test fun duplicateCompletedVoiceCallbackCannotDeleteOrUploadTheOwnedFileTwice() {
        val file = voiceFile()
        mode = "post-lost"
        chat.sendVoice(file.absolutePath); chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertEquals(1, uploadCount)
        assertEquals(1, keys.size)
        assertTrue(chat.canRetryAction)
    }
    @Test fun voiceUploadFailureWhileEditingCannotRetryUnsentEdit() {
        chat.startEdit(chat.messages.first { it.id == 1 }); chat.updateDraft("unsent edit")
        val file = voiceFile()
        mode = "upload-fail"; chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertEquals("unsent edit", chat.draft)
        assertEquals(1, chat.editingId)
        assertTrue(chat.actionError.isNotBlank())
        assertFalse(chat.canRetryAction)
        mode = "normal"; chat.retryAction(); idle()
        assertFalse(requests.any { it.endsWith("/1/edit") || it == "POST /bookings/42/messages" })
    }
    @Test fun knownVoiceMessageRejectionDoesNotOfferUnknownDeliveryRetry() {
        val file = voiceFile()
        mode = "post-refused"; chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertEquals(1, uploadCount)
        assertTrue(synchronized(ledger) { ledger.isEmpty() })
        assertTrue(chat.actionError.isNotBlank())
        assertFalse(chat.canRetryAction)
        mode = "normal"; chat.retryAction(); idle()
        assertEquals(1, keys.size)
        assertEquals(0, Outbox.count(context, 42))
    }
    private fun voiceUploadBoundary(ownerChange: Boolean) {
        val file = voiceFile()
        mode = "upload-held"; chat.sendVoice(file.absolutePath); waitFor { held.count == 0L }
        if (ownerChange) ApiClient.saveToken("header.eyJzdWIiOiIzMyJ9.signature") else mounted = false
        release.countDown(); idle()
        assertFalse(file.exists())
        assertTrue(keys.isEmpty())
        assertTrue(chat.actionError.isEmpty())
        assertFalse(chat.canRetryAction)
    }
    @Test fun voiceUploadAfterOwnerChangeCannotSubmitMessage() = voiceUploadBoundary(true)
    @Test fun voiceUploadAfterUnmountCannotSubmitMessage() = voiceUploadBoundary(false)
    private fun voiceRetryBoundary(ownerChange: Boolean) {
        val file = voiceFile()
        mode = "post-lost"; chat.sendVoice(file.absolutePath); idle()
        assertFalse(file.exists())
        assertTrue(chat.canRetryAction)
        val before = requests.size
        if (ownerChange) ApiClient.saveToken("header.eyJzdWIiOiIzMyJ9.signature") else mounted = false
        mode = "normal"; chat.retryAction(); idle()
        assertEquals(before, requests.size)
        assertEquals(1, uploadCount)
        assertEquals(1, keys.size)
    }
    @Test fun voiceRetainedRetryAfterOwnerChangeCannotDispatch() = voiceRetryBoundary(true)
    @Test fun voiceRetainedRetryAfterUnmountCannotDispatch() = voiceRetryBoundary(false)
    private fun voiceLateMessageBoundary(ownerChange: Boolean) {
        val file = voiceFile()
        mode = "post-held-lost"; chat.sendVoice(file.absolutePath); waitFor { held.count == 0L }
        if (ownerChange) ApiClient.saveToken("header.eyJzdWIiOiIzMyJ9.signature") else mounted = false
        release.countDown(); idle()
        assertFalse(file.exists())
        assertTrue(chat.actionError.isEmpty())
        assertFalse(chat.canRetryAction)
        assertEquals(1, requests.count { it == "GET /bookings/42/messages" })
    }
    @Test fun voiceMessageLostReplyAfterOwnerChangeCannotApplyLateError() = voiceLateMessageBoundary(true)
    @Test fun voiceMessageLostReplyAfterUnmountCannotApplyLateError() = voiceLateMessageBoundary(false)
}
