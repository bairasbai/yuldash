package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.ChatScreenSession
import com.yuldash.app.launchChatVoiceUpload
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exact owner handoff boundaries, including the interval after the UI guard. No live services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatExpectedOwnerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(
                        """{"id":42,"booking_id":42,"status":"accepted","role":"passenger","items":[],"code":"1234","url":"/uploads/test"}""")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.init(context)
        clearSession()
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        ApiClient.saveToken("local-chat-A")
    }

    @After fun cleanup() {
        clearSession()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private data class Operation(val name: String, val call: suspend (Long) -> Result<*>)
    private fun operations() = listOf(
        Operation("booking history") { ApiClient.getMessages(42, it) },
        Operation("booking send") { ApiClient.sendMessage(42, "draft", "local-key", it) },
        Operation("booking edit") { ApiClient.editMessage(42, 7, "edit", it) },
        Operation("booking delete") { ApiClient.deleteMessage(42, 7, "me", it) },
        Operation("booking code") { ApiClient.getBoardingCode(42, it) },
        Operation("booking details") { ApiClient.getBookingDetails(42, it) },
        Operation("booking state") { ApiClient.getTripState(42, it) },
        Operation("driver status") { ApiClient.driverStatus(42, "departed", it) },
        Operation("trip status") { ApiClient.setTripStatus(42, "onboard", it) },
        Operation("taxi state") { ApiClient.getInstantOrder(42, it) },
        Operation("taxi history") { ApiClient.getOrderMessages(42, it) },
        Operation("taxi send") { ApiClient.sendOrderMessage(42, "draft", it) },
        Operation("parcel history") { ApiClient.getParcelMessages(42, it) },
        Operation("parcel send") { ApiClient.sendParcelMessage(42, "draft", it) },
        Operation("sent parcels") { ApiClient.getMyParcels(it) },
        Operation("carried parcels") { ApiClient.getCarryingParcels(it) },
        Operation("voice upload") { ApiClient.uploadVoice(byteArrayOf(1, 2), it) },
        Operation("voice send") { ApiClient.sendVoiceMessage(42, "/uploads/test", it) },
        Operation("photo upload") { ApiClient.uploadChatPhoto(byteArrayOf(1, 2), expectedGeneration = it) },
        Operation("photo send") { ApiClient.sendPhotoMessage(42, "/uploads/test", it) },
    )

    @Test fun everyStaleChatOperationRejectsBeforeHttpUnderNewAccount() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        ApiClient.saveToken("local-chat-B")
        for (operation in operations()) assertTrue(operation.name, operation.call(owner).isFailure)
        assertTrue("Old owner sent HTTP using B: ${requests.map { it.path }}", requests.isEmpty())
        assertEquals("local-chat-B", ApiClient.currentToken())
    }

    @Test fun allBindingsStillReachServerForCurrentOwner() = runBlocking {
        val owner = ApiClient.queueSessionGeneration()
        for (operation in operations()) assertTrue(operation.name, operation.call(owner).isSuccess)
        assertEquals(operations().size, requests.size)
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer local-chat-A" })
    }

    @Test fun switchBetweenUiGuardAndApiSnapshotCannotAdoptNewBearer() = runBlocking {
        val screen = ChatScreenSession()
        var cancelled = false
        try {
            screen.run {
                // The screen guard has passed, but ApiClient has not captured credentials yet.
                ApiClient.saveToken("local-chat-B")
                ApiClient.sendOrderMessage(42, "A draft", screen.generation)
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(requests.isEmpty())
    }

    @Test fun staleFlushWaitingForMutexCannotDrainNewAccountQueue() = runBlocking {
        val ownerA = ApiClient.queueSessionGeneration()
        val mutex = Outbox::class.java.getDeclaredField("flushMutex").apply { isAccessible = true }.get(Outbox) as Mutex
        mutex.lock()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { Outbox.flush(context, ownerA) }
        try {
            assertFalse(waiting.isCompleted)
            clearSession()
            ApiClient.saveToken("local-chat-B")
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "B draft"), ApiClient.queueSessionGeneration()))
        } finally { mutex.unlock() }
        assertFalse(waiting.await())
        assertEquals(1, Outbox.count(context, 42))
        assertTrue(requests.isEmpty())
        assertTrue(Outbox.flush(context, ApiClient.queueSessionGeneration()))
        assertEquals(0, Outbox.count(context, 42))
        assertEquals("Bearer local-chat-B", requests.single().getHeader("Authorization"))
    }

    @Test fun uploadSuccessThenAccountSwitchCannotSendVoiceOrReadHistory() = runBlocking {
        val screen = ChatScreenSession()
        val uploaded = screen.run { ApiClient.uploadVoice(byteArrayOf(1, 2), screen.generation) }.getOrThrow()
        ApiClient.saveToken("local-chat-B")
        var cancelled = false
        try {
            screen.run { ApiClient.sendVoiceMessage(42, uploaded, screen.generation) }
                .onSuccess { screen.run { ApiClient.getMessages(42, screen.generation) } }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(listOf("/voice"), requests.map { it.path })
    }

    @Test fun swallowedCancellationCannotRunFailureOrQueueContinuation() = runBlocking {
        val screen = ChatScreenSession()
        var continued = false
        val work = launch {
            screen.run {
                currentCoroutineContext().cancel()
                Result.failure<Unit>(CancellationException("transport swallowed cancellation"))
            }.onFailure { continued = true }
        }
        work.join()
        assertTrue(work.isCancelled)
        assertFalse(continued)
        assertEquals(0, Outbox.count(context, 42))
    }

    @Test fun queuedCallbackChecksOwnerWhenItActuallyRuns() = runBlocking {
        val screen = ChatScreenSession()
        val gate = CompletableDeferred<Unit>()
        var changedUi = false
        val callback = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.await()
            screen.requireCurrent()
            changedUi = true
        }
        ApiClient.saveToken("local-chat-B")
        gate.complete(Unit)
        callback.join()
        assertTrue(callback.isCancelled)
        assertFalse(changedUi)
    }

    @Test fun disposedOwnerCannotContinueEvenWithoutAccountChange() = runBlocking {
        val screen = ChatScreenSession()
        screen.close()
        var ran = false
        val job = launch { screen.run { ran = true } }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(ran)
    }

    @Test fun voiceRecordingIsRemovedWhenCancelledBeforeUploadStarts() = runBlocking {
        val recording = java.io.File.createTempFile("voice_", ".m4a", context.cacheDir)
        val parent = Job().apply { cancel() }
        var ran = false
        val job = CoroutineScope(parent + Dispatchers.Unconfined).launchChatVoiceUpload(recording.absolutePath) {
            ran = true
        }
        job.join()
        assertFalse(ran)
        assertFalse(recording.exists())
    }

    @Test fun voiceRecordingIsRemovedWhenCancelledDuringUpload() = runBlocking {
        val recording = java.io.File.createTempFile("voice_", ".m4a", context.cacheDir)
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val job = CoroutineScope(parent + Dispatchers.Unconfined).launchChatVoiceUpload(recording.absolutePath) {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        assertTrue(recording.exists())
        parent.cancel()
        job.join()
        assertFalse(recording.exists())
    }

    private fun clearSession() {
        ApiClient::class.java.getDeclaredMethod("clearLocalSession").apply { isAccessible = true }.invoke(ApiClient)
    }
}
