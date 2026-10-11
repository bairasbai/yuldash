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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/** Cancellation is a thrown exception, not a Result. Server commit and actual Job are independent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsCancellationTest {
    private enum class Mutation(val method: String, val path: String, val after: List<Int>) {
        DELETE("DELETE", "/trusted-contacts/7", emptyList()), ADD("POST", "/trusted-contacts", listOf(7, 8));
        suspend fun execute(owner: Long): Result<Unit> = when (this) {
            DELETE -> ApiClient.deleteContact(7, expectedGeneration = owner)
            ADD -> ApiClient.addContact("Synthetic 8", "Synthetic", "+70000000008", true, expectedGeneration = owner)
        }
    }
    private data class Dispatch(val job: Job?, val block: Runnable)
    private class PausedCaller : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Dispatch>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(Dispatch(context[Job], block)) }
        fun next() = tasks.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing actual caller continuation")
    }
    private data class Pending<T>(val job: Deferred<Result<T>>, val entered: AtomicBoolean, val returned: AtomicBoolean, val completed: CountDownLatch)
    private data class Record(val method: String?, val path: String, val bearer: String?, val body: String)
    private val caller = PausedCaller()
    private val held = CopyOnWriteArrayList<Dispatch>()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + caller)
    private lateinit var server: MockWebServer
    private val state = AtomicReference(listOf(7))
    private val records = CopyOnWriteArrayList<Record>()
    private val committed = CountDownLatch(1)
    private val mutationRelease = CountDownLatch(1)
    private val oldGetStarted = CountDownLatch(1)
    private val oldGetRelease = CountDownLatch(1)
    private val holdNextGet = AtomicBoolean(false)
    private var applyMutation = true
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var ownerA = 0L
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun contacts(ids: List<Int>) = ids.joinToString(prefix = "[", postfix = "]") {
        """{"id":$it,"name":"Synthetic $it","phone":"+70000000007"}"""
    }
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(tokenA)
        ownerA = ApiClient.queueSessionGeneration()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val p = request.requestUrl!!.encodedPath
                    val r = Record(request.method, p, request.getHeader("Authorization"), request.body.readUtf8()); records += r
                    return when {
                        r.method == "GET" && p == "/trusted-contacts" -> {
                            val snapshot = if (r.bearer == "Bearer $tokenB") listOf(9) else state.get()
                            if (holdNextGet.compareAndSet(true, false)) {
                                oldGetStarted.countDown(); check(oldGetRelease.await(30, TimeUnit.SECONDS))
                            }
                            json(contacts(snapshot))
                        }
                        r.method == "GET" && p == "/feed" -> json("""{"today":17}""")
                        (r.method == "DELETE" && p == "/trusted-contacts/7") || (r.method == "POST" && p == "/trusted-contacts") -> {
                            check(r.bearer == "Bearer $tokenA")
                            if (applyMutation) state.set(if (r.method == "DELETE") emptyList() else listOf(7, 8))
                            committed.countDown(); check(mutationRelease.await(30, TimeUnit.SECONDS)); json("{}")
                        }
                        else -> json("{}")
                    }
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        mutationRelease.countDown(); oldGetRelease.countDown(); parent.cancel()
        try {
            held.toList().forEach { resume(it) }
            while (!parent.isCompleted) caller.next().block.run()
            runBlocking { parent.join() }
        } finally { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    private fun <T> pending(block: suspend () -> Result<T>): Pending<T> {
        val entered = AtomicBoolean(false); val returned = AtomicBoolean(false); val completed = CountDownLatch(1)
        val job = scope.async { entered.set(true); try { block() } finally { returned.set(true) } }
        job.invokeOnCompletion { completed.countDown() }; return Pending(job, entered, returned, completed)
    }
    private fun take() = caller.next().also { held += it }
    private fun resume(dispatch: Dispatch) { assertTrue(held.remove(dispatch)); dispatch.block.run() }
    private fun start(m: Mutation): Pending<Unit> {
        val p = pending { m.execute(ownerA) }; val initial = take(); assertSame(p.job, initial.job); resume(initial)
        assertTrue(committed.await(10, TimeUnit.SECONDS)); assertTrue(p.entered.get()); assertFalse(p.returned.get())
        assertFalse(p.job.isCompleted); assertFalse(p.job.isCancelled); assertEquals(1L, p.completed.count)
        assertTrue(caller.tasks.isEmpty()); assertEquals(if (applyMutation) m.after else listOf(7), state.get()); return p
    }
    private fun returning(p: Pending<*>): Dispatch {
        mutationRelease.countDown(); val continuation = take(); assertSame(p.job, continuation.job)
        assertFalse(p.job.isCompleted); assertFalse(p.returned.get()); return continuation
    }
    private fun cancelled(p: Pending<*>, continuation: Dispatch) {
        p.job.cancel(); assertTrue(p.job.isCancelled); assertFalse(p.job.isCompleted); resume(continuation)
        runBlocking { withTimeout(5000) { p.job.join() } }
        assertTrue(p.job.isCompleted); assertTrue(p.returned.get()); assertTrue(p.completed.await(5, TimeUnit.SECONDS))
        assertTrue(caller.tasks.isEmpty())
        try { runBlocking { p.job.await() }; fail("Cancellation must propagate to caller") }
        catch (_: CancellationException) { }
    }
    private fun ids(): List<Int> = runBlocking { ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow().map { it.id } }
    private fun feed() = runBlocking { ApiClient.getFeed().getOrThrow() }
    private fun contactsRecords() = records.filter { it.path.startsWith("/trusted-contacts") }
    private fun contract(m: Mutation, methods: List<String>, tokens: List<String> = List(methods.size) { tokenA }) {
        val r = contactsRecords(); assertEquals(methods, r.map { it.method }); assertEquals(tokens.map { "Bearer $it" }, r.map { it.bearer })
        assertEquals(methods.map { if (it == "DELETE") m.path else "/trusted-contacts" }, r.map { it.path })
        val mutations = r.filter { it.method == m.method }; assertEquals(1, mutations.size)
        if (m == Mutation.DELETE) assertEquals("", mutations.single().body) else {
            val body = JSONObject(mutations.single().body); assertEquals(4, body.length())
            assertEquals("Synthetic 8", body.getString("name")); assertEquals("Synthetic", body.getString("relation"))
            assertEquals("+70000000008", body.getString("phone")); assertTrue(body.getBoolean("notify_by_default"))
        }
    }
    private fun current(m: Mutation, cancelDuringIo: Boolean, commit: Boolean = true) {
        applyMutation = commit; assertEquals(listOf(7), ids()); assertEquals(listOf(7), ids()); val feedBefore = feed()
        val p = start(m)
        val continuation = if (cancelDuringIo) {
            p.job.cancel(); assertFalse(p.job.isCompleted); returning(p)
        } else returning(p)
        // The caller still owns the old cache until its cancelled continuation is resumed.
        assertEquals(listOf(7), ids()); cancelled(p, continuation)
        val expected = if (commit) m.after else listOf(7)
        assertEquals("Cancelled sent mutation must force a fresh server read", expected, ids())
        assertEquals(expected, ids()); assertEquals(feedBefore, feed()); assertEquals(1, records.count { it.path == "/feed" })
        assertEquals(ownerA, ApiClient.queueSessionGeneration()); assertEquals(tokenA, ApiClient.currentToken())
        assertFalse(ApiClient.sessionExpired.value); contract(m, listOf("GET", m.method, "GET"))
    }
    private fun newOwner(m: Mutation, cancelDuringIo: Boolean) {
        assertEquals(listOf(7), ids()); val p = start(m)
        val continuation = if (cancelDuringIo) { p.job.cancel(); null } else returning(p)
        ApiClient.saveToken(tokenB); val ownerB = ApiClient.queueSessionGeneration(); assertNotEquals(ownerA, ownerB)
        assertEquals(listOf(9), ids()); assertEquals(listOf(9), ids())
        cancelled(p, continuation ?: returning(p)); assertEquals(listOf(9), ids())
        assertEquals(ownerB, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken()); assertFalse(ApiClient.sessionExpired.value)
        contract(m, listOf("GET", m.method, "GET"), listOf(tokenA, tokenA, tokenB))
    }
    private fun guest(m: Mutation) {
        val p = start(m); val continuation = returning(p); ApiClient.logout(); val guest = ApiClient.queueSessionGeneration()
        cancelled(p, continuation); assertNotEquals(ownerA, guest); assertEquals(guest, ApiClient.queueSessionGeneration())
        assertNull(ApiClient.currentToken()); assertFalse(ApiClient.isLoggedIn()); assertFalse(ApiClient.sessionExpired.value)
        contract(m, listOf(m.method))
    }
    private fun beforeEntry(m: Mutation) {
        assertEquals(listOf(7), ids()); val p = pending { m.execute(ownerA) }; val initial = take()
        assertSame(p.job, initial.job); p.job.cancel(); resume(initial); runBlocking { p.job.join() }
        assertTrue(p.job.isCancelled); assertTrue(p.job.isCompleted); assertFalse(p.entered.get()); assertFalse(p.returned.get())
        assertTrue(p.completed.await(5, TimeUnit.SECONDS)); assertEquals(listOf(7), ids()); assertEquals(1, contactsRecords().size)
        assertEquals("GET", contactsRecords().single().method); assertEquals(listOf(7), state.get()); assertTrue(caller.tasks.isEmpty())
    }
    private fun oldGet(m: Mutation) {
        holdNextGet.set(true); val old = pending { ApiClient.getContacts(expectedGeneration = ownerA) }
        val initial = take(); assertSame(old.job, initial.job); resume(initial); assertTrue(oldGetStarted.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(7), ids()); val p = start(m); cancelled(p, returning(p))
        assertEquals(m.after, ids()); oldGetRelease.countDown(); val late = take(); assertSame(old.job, late.job); resume(late)
        val oldIds = runBlocking { old.job.join(); old.job.await().getOrThrow().map { it.id } }
        assertEquals(listOf(7), oldIds); assertTrue(old.returned.get()); assertTrue(old.completed.await(5, TimeUnit.SECONDS))
        assertFalse(old.job.isCancelled); assertEquals(m.after, ids()); contract(m, listOf("GET", "GET", m.method, "GET"))
    }
    @Test fun deleteCancelledWhileResponseHeldRefreshesCache() = current(Mutation.DELETE, true)
    @Test fun addCancelledWhileResponseHeldRefreshesCache() = current(Mutation.ADD, true)
    @Test fun deleteCancelledAfterIoBeforeCallerResumeRefreshesCache() = current(Mutation.DELETE, false)
    @Test fun addCancelledAfterIoBeforeCallerResumeRefreshesCache() = current(Mutation.ADD, false)
    @Test fun deleteCancelledWhileResponseHeldPreservesNewOwnerCache() = newOwner(Mutation.DELETE, true)
    @Test fun addCancelledWhileResponseHeldPreservesNewOwnerCache() = newOwner(Mutation.ADD, true)
    @Test fun deleteCancelledAfterIoPreservesNewOwnerCache() = newOwner(Mutation.DELETE, false)
    @Test fun addCancelledAfterIoPreservesNewOwnerCache() = newOwner(Mutation.ADD, false)
    @Test fun cancelledDeleteAfterLogoutLeavesGuestUnchanged() = guest(Mutation.DELETE)
    @Test fun cancelledAddAfterLogoutLeavesGuestUnchanged() = guest(Mutation.ADD)
    @Test fun deleteCancelledBeforeCallerEntrySendsNothingAndKeepsCache() = beforeEntry(Mutation.DELETE)
    @Test fun addCancelledBeforeCallerEntrySendsNothingAndKeepsCache() = beforeEntry(Mutation.ADD)
    @Test fun oldReadCannotRestoreCacheAfterCancelledDelete() = oldGet(Mutation.DELETE)
    @Test fun oldReadCannotRestoreCacheAfterCancelledAdd() = oldGet(Mutation.ADD)
    @Test fun uncommittedCancelledDeleteReadsUnchangedServerOnce() = current(Mutation.DELETE, false, false)
    @Test fun uncommittedCancelledAddReadsUnchangedServerOnce() = current(Mutation.ADD, false, false)
    @Test fun explicitFreshReadBypassesWarmCacheAndSeedsNewSnapshot() {
        assertEquals(listOf(7), ids()); state.set(listOf(8))
        assertEquals(listOf(7), ids())
        assertEquals(listOf(8), runBlocking { ApiClient.getContacts(ownerA, forceRefresh = true).getOrThrow().map { it.id } })
        assertEquals(listOf(8), ids()); assertEquals(listOf("GET", "GET"), contactsRecords().map { it.method })
    }
    @Test fun staleOwnerFreshReadCannotInvalidateNewOwnerCacheOrSendHttp() {
        assertEquals(listOf(7), ids()); ApiClient.saveToken(tokenB); val ownerB = ApiClient.queueSessionGeneration()
        assertEquals(listOf(9), ids())
        val result = runBlocking { ApiClient.getContacts(ownerA, forceRefresh = true) }
        assertTrue(result.isFailure); assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(9), ids()); assertEquals(ownerB, ApiClient.queueSessionGeneration()); assertEquals(tokenB, ApiClient.currentToken())
        assertEquals(listOf("Bearer $tokenA", "Bearer $tokenB"), contactsRecords().map { it.bearer })
    }
    @Test fun explicitFreshReadStopsEarlierColdReadFromRefillingCache() {
        holdNextGet.set(true); val old = pending { ApiClient.getContacts(ownerA) }
        val initial = take(); assertSame(old.job, initial.job); resume(initial); assertTrue(oldGetStarted.await(10, TimeUnit.SECONDS))
        state.set(listOf(8))
        assertEquals(listOf(8), runBlocking { ApiClient.getContacts(ownerA, forceRefresh = true).getOrThrow().map { it.id } })
        oldGetRelease.countDown(); val late = take(); assertSame(old.job, late.job); resume(late)
        assertEquals(listOf(7), runBlocking { old.job.join(); old.job.await().getOrThrow().map { it.id } })
        assertTrue(old.returned.get()); assertTrue(old.completed.await(5, TimeUnit.SECONDS)); assertFalse(old.job.isCancelled)
        assertEquals(listOf(8), ids()); assertEquals(listOf("GET", "GET"), contactsRecords().map { it.method })
    }
}
