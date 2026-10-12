package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONException
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Separate server state and actual API completion; refresh must never resend the mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsMutationReceiptTest {
    private enum class Mutation(val method: String, val path: String, val after: List<Int>) {
        DELETE("DELETE", "/trusted-contacts/7", emptyList()), ADD("POST", "/trusted-contacts", listOf(7, 8));
        suspend fun execute(): Result<Unit> = when (this) {
            DELETE -> ApiClient.deleteContact(7)
            ADD -> ApiClient.addContact("Synthetic 8", "Synthetic", "+70000000008", true)
        }
    }
    private enum class Reply { MALFORMED, TRUNCATED, READ_TIMEOUT, REJECTED }
    private data class Dispatch(val job: Job?, val block: Runnable)
    private class PausedCaller : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Dispatch>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(Dispatch(context[Job], block)) }
        fun next() = tasks.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing actual caller dispatch")
    }
    private data class Pending<T>(val job: Deferred<Result<T>>, val returned: AtomicBoolean, val completed: CountDownLatch)
    private data class Record(val method: String?, val path: String, val bearer: String?, val body: String)
    private lateinit var server: MockWebServer
    private var serverClosed = false
    private val caller = PausedCaller()
    private val heldDispatches = CopyOnWriteArrayList<Dispatch>()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + caller)
    private val state = AtomicReference(listOf(7))
    private val records = CopyOnWriteArrayList<Record>()
    private val committed = CountDownLatch(1)
    private val mutationRelease = CountDownLatch(1)
    private val oldGetStarted = CountDownLatch(1)
    private val oldGetRelease = CountDownLatch(1)
    private val holdNextGet = AtomicBoolean(false)
    private var reply = Reply.MALFORMED
    private var rejectionCode = 503
    private val failNextGet = AtomicBoolean(false)
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var generationA = 0L
    private fun contacts(ids: List<Int>) = ids.joinToString(prefix = "[", postfix = "]") {
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
                val record = Record(request.method, path, request.getHeader("Authorization"), request.body.readUtf8())
                records += record
                return when {
                    record.method == "GET" && path == "/trusted-contacts" -> {
                        val snapshot = if (record.bearer == "Bearer $tokenB") listOf(9) else state.get()
                        if (holdNextGet.compareAndSet(true, false)) {
                            oldGetStarted.countDown(); check(oldGetRelease.await(30, TimeUnit.SECONDS))
                            json(contacts(snapshot))
                        } else if (failNextGet.compareAndSet(true, false)) json("{}", 503)
                        else json(contacts(snapshot))
                    }
                    record.method == "GET" && path == "/feed" -> json("""{"today":17}""")
                    (record.method == "DELETE" && path == "/trusted-contacts/7") ||
                        (record.method == "POST" && path == "/trusted-contacts") -> {
                        check(record.bearer == "Bearer $tokenA")
                        if (reply != Reply.REJECTED) state.set(if (record.method == "DELETE") emptyList() else listOf(7, 8))
                        committed.countDown(); check(mutationRelease.await(30, TimeUnit.SECONDS))
                        when (reply) {
                            Reply.MALFORMED -> json("{\"ok\":")
                            Reply.TRUNCATED -> json("{\"padding\":\"" + "x".repeat(8192) + "\"}")
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                            Reply.READ_TIMEOUT -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            Reply.REJECTED -> json("{}", rejectionCode)
                        }
                    }
                    path == "/auth/logout" || path == "/push/unregister" -> json("{}")
                    else -> json("{}", 404)
                }
            }
        }
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        mutationRelease.countDown(); oldGetRelease.countDown(); parent.cancel()
        try {
            heldDispatches.toList().forEach { runDispatch(it) }
            while (!parent.isCompleted) caller.next().block.run()
            runBlocking { parent.join() }
        } finally {
            try { ApiClient.logout(); ApiClient.resetForTest(); ApiClient.sessionExpired.value = false }
            finally { ApiClient.testTimeoutMs = null; if (!serverClosed) server.shutdown() }
        }
    }
    private fun <T> startPending(dispatcher: CoroutineDispatcher, block: suspend () -> Result<T>): Pending<T> {
        val returned = AtomicBoolean(false); val completed = CountDownLatch(1)
        val job = scope.async(dispatcher) { try { block() } finally { returned.set(true) } }
        job.invokeOnCompletion { completed.countDown() }
        return Pending(job, returned, completed)
    }
    private fun takeDispatch() = caller.next().also { heldDispatches += it }
    private fun runDispatch(dispatch: Dispatch) { assertTrue(heldDispatches.remove(dispatch)); dispatch.block.run() }
    private fun start(m: Mutation): Pending<Unit> {
        val pending = startPending(caller) { m.execute() }
        val initial = takeDispatch(); assertSame(pending.job, initial.job); runDispatch(initial)
        assertTrue(committed.await(10, TimeUnit.SECONDS)); assertFalse(pending.job.isCompleted)
        assertFalse(pending.returned.get()); assertEquals(1L, pending.completed.count); assertTrue(caller.tasks.isEmpty())
        assertEquals(if (reply == Reply.REJECTED) listOf(7) else m.after, state.get())
        return pending
    }
    private fun returning(p: Pending<Unit>): Dispatch {
        mutationRelease.countDown(); val dispatch = takeDispatch()
        assertSame(p.job, dispatch.job); assertFalse(p.job.isCompleted); assertFalse(p.returned.get())
        assertEquals(1L, p.completed.count); return dispatch
    }
    private fun <T> finish(p: Pending<T>): Result<T> = runBlocking {
        val result = p.job.await(); p.job.join()
        assertTrue(p.returned.get()); assertTrue(p.completed.await(5, TimeUnit.SECONDS))
        assertTrue(p.job.isCompleted); assertFalse(p.job.isCancelled); result
    }
    private fun finishMutation(p: Pending<Unit>, dispatch: Dispatch = returning(p)): Result<Unit> {
        runDispatch(dispatch); assertTrue(p.job.isCompleted); return finish(p)
    }
    private fun ids(): List<Int> = runBlocking {
        ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow().map { it.id }
    }
    private fun failure(result: Result<Unit>) {
        assertTrue(result.isFailure)
        if (reply == Reply.MALFORMED) assertTrue(result.exceptionOrNull() is JSONException)
        else assertTrue(result.exceptionOrNull() is IOException || result.exceptionOrNull() is JSONException)
    }
    private fun assertRequests(m: Mutation, methods: List<String>, tokens: List<String> = List(methods.size) { tokenA }) {
        val contacts = records.filter { it.path.startsWith("/trusted-contacts") }
        assertEquals(methods, contacts.map { it.method }); assertEquals(tokens.map { "Bearer $it" }, contacts.map { it.bearer })
        assertEquals(methods.map { if (it == "DELETE") m.path else "/trusted-contacts" }, contacts.map { it.path })
        assertEquals(1, contacts.count { it.method == m.method })
        val body = contacts.single { it.method == m.method }.body
        if (m == Mutation.DELETE) assertEquals("", body) else {
            val obj = JSONObject(body); assertEquals(4, obj.length()); assertEquals("Synthetic 8", obj.getString("name"))
            assertEquals("Synthetic", obj.getString("relation")); assertEquals("+70000000008", obj.getString("phone"))
            assertTrue(obj.getBoolean("notify_by_default"))
        }
    }
    private fun warm(m: Mutation, mode: Reply = Reply.MALFORMED) {
        reply = mode; assertEquals(listOf(7), ids()); assertEquals(listOf(7), ids())
        if (mode == Reply.READ_TIMEOUT) ApiClient.testTimeoutMs = 300
        val result = finishMutation(start(m)); failure(result)
        if (mode == Reply.READ_TIMEOUT) assertTrue(result.exceptionOrNull() is SocketTimeoutException)
        ApiClient.testTimeoutMs = 60000; assertEquals(m.after, state.get())
        assertEquals("Unconfirmed mutation must force a server read", m.after, ids()); assertEquals(m.after, ids())
        assertRequests(m, listOf("GET", m.method, "GET")); assertEquals(generationA, ApiClient.queueSessionGeneration())
    }
    private fun retryFresh(m: Mutation) {
        assertEquals(listOf(7), ids()); failure(finishMutation(start(m))); failNextGet.set(true)
        val failed = runBlocking { ApiClient.getContacts(expectedGeneration = generationA) }
        assertTrue(failed.isFailure); assertEquals(503, (failed.exceptionOrNull() as ApiException).status)
        assertEquals(m.after, ids()); assertEquals(m.after, ids()); assertEquals(m.after, state.get())
        assertRequests(m, listOf("GET", m.method, "GET", "GET"))
    }
    private fun lateGet(m: Mutation, freshFirst: Boolean) {
        holdNextGet.set(true)
        val old = startPending(Dispatchers.Default) { ApiClient.getContacts(expectedGeneration = generationA) }
        assertTrue(oldGetStarted.await(10, TimeUnit.SECONDS)); assertFalse(old.job.isCompleted); assertFalse(old.returned.get())
        failure(finishMutation(start(m))); assertEquals(m.after, state.get())
        if (freshFirst) assertEquals(m.after, ids())
        oldGetRelease.countDown(); assertEquals(listOf(7), finish(old).getOrThrow().map { it.id })
        assertEquals(m.after, ids()); assertEquals(m.after, ids()); assertRequests(m, listOf("GET", m.method, "GET"))
    }
    private fun rejected(m: Mutation, code: Int) {
        reply = Reply.REJECTED; rejectionCode = code; assertEquals(listOf(7), ids())
        val result = finishMutation(start(m)); assertTrue(result.isFailure)
        assertEquals(code, (result.exceptionOrNull() as ApiException).status); assertEquals(listOf(7), state.get())
        assertEquals(listOf(7), ids()); assertRequests(m, listOf("GET", m.method))
    }
    private fun feed(m: Mutation) {
        val feed = runBlocking { ApiClient.getFeed().getOrThrow() }; assertEquals(17, feed.today)
        warm(m); assertEquals(feed, runBlocking { ApiClient.getFeed().getOrThrow() })
        assertEquals(1, records.count { it.path == "/feed" })
    }
    private fun newOwner(m: Mutation, afterIo: Boolean) {
        assertEquals(listOf(7), ids()); val pending = start(m)
        val dispatch = if (afterIo) returning(pending) else null
        ApiClient.saveToken(tokenB); val generationB = ApiClient.queueSessionGeneration()
        assertNotEquals(generationA, generationB); assertEquals(listOf(9), ids()); assertEquals(listOf(9), ids())
        val result = finishMutation(pending, dispatch ?: returning(pending))
        if (afterIo) failure(result) else {
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals("Session changed", result.exceptionOrNull()!!.message)
        }
        assertEquals(m.after, state.get()); assertEquals(listOf(9), ids()); assertEquals(listOf(9), ids())
        assertEquals(generationB, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken())
        assertRequests(m, listOf("GET", m.method, "GET"), listOf(tokenA, tokenA, tokenB))
    }
    private fun neverSent(m: Mutation) {
        assertEquals(listOf(7), ids()); server.shutdown(); serverClosed = true
        val p = startPending(caller) { m.execute() }
        val initial = takeDispatch(); assertSame(p.job, initial.job); runDispatch(initial)
        val dispatch = takeDispatch(); assertSame(p.job, dispatch.job)
        val result = finishMutation(p, dispatch); assertTrue(result.exceptionOrNull() is ConnectException)
        assertEquals(listOf(7), state.get()); assertEquals(listOf(7), ids()); assertEquals(generationA, ApiClient.queueSessionGeneration())
        assertEquals(listOf("GET"), records.map { it.method }); assertEquals(1L, committed.count)
    }
    @Test fun malformedCommittedDeleteRefreshesWarmCache() = warm(Mutation.DELETE)
    @Test fun malformedCommittedAddRefreshesWarmCache() = warm(Mutation.ADD)
    @Test fun truncatedCommittedDeleteRefreshesWarmCache() = warm(Mutation.DELETE, Reply.TRUNCATED)
    @Test fun truncatedCommittedAddRefreshesWarmCache() = warm(Mutation.ADD, Reply.TRUNCATED)
    @Test fun timeoutAfterCommittedDeleteRefreshesWarmCache() = warm(Mutation.DELETE, Reply.READ_TIMEOUT)
    @Test fun timeoutAfterCommittedAddRefreshesWarmCache() = warm(Mutation.ADD, Reply.READ_TIMEOUT)
    @Test fun deleteFreshReadFailureCanRetryWithoutResendingMutation() = retryFresh(Mutation.DELETE)
    @Test fun addFreshReadFailureCanRetryWithoutResendingMutation() = retryFresh(Mutation.ADD)
    @Test fun lateGetBeforeFreshDeleteReadCannotRestoreOldCache() = lateGet(Mutation.DELETE, false)
    @Test fun lateGetBeforeFreshAddReadCannotRestoreOldCache() = lateGet(Mutation.ADD, false)
    @Test fun lateGetAfterFreshDeleteReadCannotReplaceNewCache() = lateGet(Mutation.DELETE, true)
    @Test fun lateGetAfterFreshAddReadCannotReplaceNewCache() = lateGet(Mutation.ADD, true)
    @Test fun rejectedDelete403PreservesUnchangedCache() = rejected(Mutation.DELETE, 403)
    @Test fun rejectedAdd403PreservesUnchangedCache() = rejected(Mutation.ADD, 403)
    @Test fun rejectedDelete503PreservesUnchangedCache() = rejected(Mutation.DELETE, 503)
    @Test fun rejectedAdd503PreservesUnchangedCache() = rejected(Mutation.ADD, 503)
    @Test fun unknownDeletePreservesIndependentFeedCache() = feed(Mutation.DELETE)
    @Test fun unknownAddPreservesIndependentFeedCache() = feed(Mutation.ADD)
    @Test fun oldMalformedDeleteContinuationDoesNotInvalidateNewOwnerCache() = newOwner(Mutation.DELETE, true)
    @Test fun oldMalformedAddContinuationDoesNotInvalidateNewOwnerCache() = newOwner(Mutation.ADD, true)
    @Test fun oldDeleteHttpResponseDoesNotInvalidateNewOwnerCache() = newOwner(Mutation.DELETE, false)
    @Test fun oldAddHttpResponseDoesNotInvalidateNewOwnerCache() = newOwner(Mutation.ADD, false)
    @Test fun unsentDeleteConnectionFailurePreservesWarmCache() = neverSent(Mutation.DELETE)
    @Test fun unsentAddConnectionFailurePreservesWarmCache() = neverSent(Mutation.ADD)
}
