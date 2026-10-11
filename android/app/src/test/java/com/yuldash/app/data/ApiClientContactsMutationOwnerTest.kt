package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Actual HTTP and actual caller continuation; no mutation/cache hooks or timing sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsMutationOwnerTest {
    private enum class Mutation(val method: String, val path: String) {
        DELETE("DELETE", "/trusted-contacts/7"), ADD("POST", "/trusted-contacts");
        suspend fun execute(): Result<Unit> = when (this) {
            DELETE -> ApiClient.deleteContact(7)
            ADD -> ApiClient.addContact("Synthetic 8", "Synthetic", "+70000000008", true)
        }
    }
    private data class Dispatch(val job: Job?, val block: Runnable)
    private class PausedCaller : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Dispatch>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(Dispatch(context[Job], block)) }
        fun next(label: String): Dispatch = tasks.poll(10, TimeUnit.SECONDS)
            ?: throw AssertionError("Timed out waiting for $label")
    }
    private data class Pending(val request: Deferred<Result<Unit>>, val entered: AtomicBoolean,
        val returned: AtomicBoolean, val completed: CountDownLatch)
    private data class Record(val method: String?, val path: String, val bearer: String?, val body: String)
    private lateinit var server: MockWebServer
    private val caller = PausedCaller()
    private val heldDispatches = CopyOnWriteArrayList<Dispatch>()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + caller)
    private val contactReplies = ConcurrentLinkedQueue<String>()
    private val records = CopyOnWriteArrayList<Record>()
    private val mutationStarted = CountDownLatch(1)
    private val mutationRelease = CountDownLatch(1)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var generationA = 0L
    private var mutationCode = 200
    private fun contacts(vararg ids: Int) = ids.joinToString(prefix = "[", postfix = "]") {
        """{"id":$it,"name":"Synthetic $it","phone":"+70000000007"}"""
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(tokenA)
        generationA = ApiClient.queueSessionGeneration()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                records += Record(request.method, path, request.getHeader("Authorization"), request.body.readUtf8())
                return when {
                    request.method == "GET" && path == "/trusted-contacts" ->
                        json(contactReplies.poll() ?: error("Unexpected contacts GET"))
                    (request.method == "DELETE" && path == "/trusted-contacts/7") ||
                        (request.method == "POST" && path == "/trusted-contacts") -> {
                        mutationStarted.countDown(); check(mutationRelease.await(30, TimeUnit.SECONDS))
                        json("{}", mutationCode)
                    }
                    path == "/auth/logout" || path == "/push/unregister" -> json("{}")
                    else -> json("{}", 404)
                }
            }
        }
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        mutationRelease.countDown(); parent.cancel()
        try {
            heldDispatches.toList().forEach { runDispatch(it) }
            while (!parent.isCompleted) caller.next("cancelled caller completion").block.run()
            runBlocking { parent.join() }
        } finally {
            try { ApiClient.logout(); ApiClient.resetForTest(); ApiClient.sessionExpired.value = false }
            finally { ApiClient.testTimeoutMs = null; server.shutdown() }
        }
    }
    private fun takeDispatch(label: String): Dispatch = caller.next(label).also { heldDispatches += it }
    private fun runDispatch(dispatch: Dispatch) {
        assertTrue(heldDispatches.remove(dispatch)); dispatch.block.run()
    }
    private fun start(mutation: Mutation, code: Int = 200): Pending {
        mutationCode = code
        val entered = AtomicBoolean(false); val returned = AtomicBoolean(false); val completed = CountDownLatch(1)
        val request = scope.async {
            entered.set(true)
            try { mutation.execute() } finally { returned.set(true) }
        }
        request.invokeOnCompletion { completed.countDown() }
        val initial = takeDispatch("initial actual API caller")
        assertSame(request, initial.job); runDispatch(initial)
        assertTrue(mutationStarted.await(10, TimeUnit.SECONDS))
        assertTrue(entered.get()); unfinished(Pending(request, entered, returned, completed))
        assertTrue("HTTP is held; no returning caller Runnable yet", caller.tasks.isEmpty())
        return Pending(request, entered, returned, completed)
    }
    private fun unfinished(p: Pending) {
        assertFalse(p.request.isCompleted); assertFalse(p.request.isCancelled)
        assertFalse(p.returned.get()); assertEquals(1L, p.completed.count)
    }
    private fun returning(p: Pending): Dispatch {
        mutationRelease.countDown()
        // The actual caller's IO block has completed, including call's session check.
        // call has not yet returned to mutateContacts; its second lock runs on resume.
        val continuation = takeDispatch("actual caller returning from completed HTTP IO")
        assertSame(p.request, continuation.job); unfinished(p)
        assertTrue(caller.tasks.isEmpty())
        return continuation
    }
    private fun finish(p: Pending, continuation: Dispatch): Result<Unit> {
        assertSame(p.request, continuation.job); runDispatch(continuation)
        assertTrue("The actual API caller must complete on resume", p.request.isCompleted)
        val result = runBlocking { p.request.join(); p.request.await() }
        assertTrue(p.entered.get()); assertTrue(p.returned.get()); assertTrue(p.completed.await(5, TimeUnit.SECONDS))
        assertFalse(p.request.isCancelled); assertTrue(caller.tasks.isEmpty())
        return result
    }
    private fun read(vararg ids: Int): List<Int> {
        contactReplies += contacts(*ids)
        return runBlocking { ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow().map { it.id } }
    }
    private fun cachedIds() = runBlocking {
        ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow().map { it.id }
    }
    private fun contactRecords() = records.filter { it.path.startsWith("/trusted-contacts") }
    private fun record(m: Mutation, token: String) = Triple(m.method, m.path, "Bearer $token")
    private fun getRecord(token: String) = Triple("GET", "/trusted-contacts", "Bearer $token")
    private fun assertRecords(m: Mutation, expected: List<Triple<String, String, String>>) {
        val contacts = contactRecords()
        assertEquals(expected, contacts.map { Triple(it.method, it.path, it.bearer) })
        assertEquals(1, contacts.count { it.method == m.method && it.path == m.path })
        val body = contacts.single { it.method == m.method && it.path == m.path }.body
        if (m == Mutation.DELETE) assertEquals("", body) else {
            val json = JSONObject(body); assertEquals(4, json.length())
            assertEquals("Synthetic 8", json.getString("name")); assertEquals("Synthetic", json.getString("relation"))
            assertEquals("+70000000008", json.getString("phone")); assertTrue(json.getBoolean("notify_by_default"))
        }
        assertTrue(contactReplies.isEmpty())
    }
    private fun warmB(): Long {
        ApiClient.saveToken(tokenB); val generationB = ApiClient.queueSessionGeneration()
        assertNotEquals(generationA, generationB); assertEquals(listOf(8), read(8)); assertEquals(listOf(8), cachedIds())
        return generationB
    }
    private fun assertB(generationB: Long) {
        assertEquals(tokenB, ApiClient.currentToken()); assertTrue(ApiClient.isLoggedIn())
        assertEquals(generationB, ApiClient.queueSessionGeneration()); assertFalse(ApiClient.sessionExpired.value)
        assertEquals(listOf(8), cachedIds())
    }
    private fun switchedAfterIo(m: Mutation) {
        val pending = start(m); val continuation = returning(pending)
        assertEquals(generationA, ApiClient.queueSessionGeneration())
        val generationB = warmB(); unfinished(pending)
        val result = finish(pending, continuation); assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException); assertFalse(result.exceptionOrNull() is ApiException)
        assertB(generationB); assertRecords(m, listOf(record(m, tokenA), getRecord(tokenB)))
    }
    private fun logoutAfterIo(m: Mutation) {
        val pending = start(m); val continuation = returning(pending)
        assertEquals(generationA, ApiClient.queueSessionGeneration()); ApiClient.logout()
        val guest = ApiClient.queueSessionGeneration(); assertNotEquals(generationA, guest); unfinished(pending)
        val result = finish(pending, continuation); assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException); assertFalse(result.exceptionOrNull() is ApiException)
        assertNull(ApiClient.currentToken()); assertFalse(ApiClient.isLoggedIn()); assertFalse(ApiClient.sessionExpired.value)
        assertEquals(guest, ApiClient.queueSessionGeneration()); assertRecords(m, listOf(record(m, tokenA)))
    }
    private fun currentOwnerInvalidation(m: Mutation) {
        assertEquals(listOf(7), read(7)); val pending = start(m); val continuation = returning(pending)
        assertEquals(listOf(7), cachedIds()); unfinished(pending)
        assertTrue(finish(pending, continuation).isSuccess)
        val fresh = if (m == Mutation.DELETE) intArrayOf() else intArrayOf(7, 8)
        assertEquals(fresh.toList(), read(*fresh)); assertEquals(fresh.toList(), cachedIds())
        assertEquals(tokenA, ApiClient.currentToken()); assertEquals(generationA, ApiClient.queueSessionGeneration())
        assertFalse(ApiClient.sessionExpired.value)
        assertRecords(m, listOf(getRecord(tokenA), record(m, tokenA), getRecord(tokenA)))
    }
    private fun switchedBeforeIoReturns(m: Mutation) {
        val pending = start(m); val generationB = warmB(); unfinished(pending)
        val result = finish(pending, returning(pending)); assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException); assertFalse(result.exceptionOrNull() is ApiException)
        assertB(generationB); assertRecords(m, listOf(record(m, tokenA), getRecord(tokenB)))
    }
    private fun errorAfterIo(m: Mutation, code: Int) {
        val pending = start(m, code); val continuation = returning(pending)
        if (code == 401) {
            assertFalse(ApiClient.isLoggedIn()); assertNull(ApiClient.currentToken())
            assertTrue(ApiClient.sessionExpired.value); assertNotEquals(generationA, ApiClient.queueSessionGeneration())
        } else {
            assertEquals(tokenA, ApiClient.currentToken()); assertEquals(generationA, ApiClient.queueSessionGeneration())
            assertFalse(ApiClient.sessionExpired.value)
        }
        val generationB = warmB(); unfinished(pending)
        val result = finish(pending, continuation); assertTrue(result.isFailure)
        assertEquals(code, (result.exceptionOrNull() as ApiException).status)
        assertB(generationB); assertRecords(m, listOf(record(m, tokenA), getRecord(tokenB)))
    }
    @Test fun deleteReturningToNewAccountPreservesItsCache() = switchedAfterIo(Mutation.DELETE)
    @Test fun addReturningToNewAccountPreservesItsCache() = switchedAfterIo(Mutation.ADD)
    @Test fun deleteReturningAfterLogoutLeavesGuestUnchanged() = logoutAfterIo(Mutation.DELETE)
    @Test fun addReturningAfterLogoutLeavesGuestUnchanged() = logoutAfterIo(Mutation.ADD)
    @Test fun currentDeleteResumedAfterIoInvalidatesWarmCache() = currentOwnerInvalidation(Mutation.DELETE)
    @Test fun currentAddResumedAfterIoInvalidatesWarmCache() = currentOwnerInvalidation(Mutation.ADD)
    @Test fun deleteHttpResponseAfterAccountSwitchPreservesNewCache() = switchedBeforeIoReturns(Mutation.DELETE)
    @Test fun addHttpResponseAfterAccountSwitchPreservesNewCache() = switchedBeforeIoReturns(Mutation.ADD)
    @Test fun delayedDelete503PreservesOriginalFailureAndNewCache() = errorAfterIo(Mutation.DELETE, 503)
    @Test fun delayedAdd503PreservesOriginalFailureAndNewCache() = errorAfterIo(Mutation.ADD, 503)
    @Test fun delayedDelete401PreservesOriginalFailureAfterNewLogin() = errorAfterIo(Mutation.DELETE, 401)
    @Test fun delayedAdd401PreservesOriginalFailureAfterNewLogin() = errorAfterIo(Mutation.ADD, 401)
}
