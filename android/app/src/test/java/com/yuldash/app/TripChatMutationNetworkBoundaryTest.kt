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
    @Volatile private var mounted = true
    @Volatile private var uploadCount = 0
    @Volatile private var failHistory = false
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
                    if (path == "/auth/logout") return MockResponse().setBody("{}")
                    if (request.method == "GET" && path == "/bookings/42/messages") {
                        if (failHistory) return MockResponse().setResponseCode(503).setBody("{}")
                        val items = JSONArray()
                        if (!hidden) items.put(JSONObject().put("id", 1).put("sender_id", 11).put("text", text).put("deleted", deleted))
                        items.put(JSONObject().put("id", 2).put("sender_id", 22).put("text", "peer"))
                        synchronized(ledger) {
                            ledger.values.forEachIndexed { i, value -> items.put(JSONObject(value.toString()).put("id", 100+i).put("sender_id", 11)) }
                        }
                        return MockResponse().setBody(JSONObject().put("items", items).toString())
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
                        if (mode == "post-held-lost") {
                            held.countDown()
                            if (!release.await(12, TimeUnit.SECONDS)) failures += "held voice message timed out"
                        }
                        return MockResponse().setResponseCode(if (mode == "post-lost" || mode == "post-held-lost") 503 else 200).setBody("{}")
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
