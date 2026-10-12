package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
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

/**
 * The HTTP snapshot, caller continuation and accepted publication have independent witnesses.
 * Layering pilot: "publication" used to be ApiClient calling the caller's onAccepted callback. It is now
 * what every screen actually observes — an accepted list of account A appearing in ContactsRepository.state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsPublicationTest {
    private data class Dispatch(val job: Job?, val block: Runnable)
    private class PausedCaller : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Dispatch>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(Dispatch(context[Job], block)) }
        fun next() = tasks.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("Missing caller continuation")
    }
    private data class Pending(val job: Deferred<Result<List<ContactDto>>>, val entered: AtomicBoolean,
        val returned: AtomicBoolean, val completed: CountDownLatch)
    private data class Record(val method: String?, val path: String, val bearer: String?, val body: String)
    private val caller = PausedCaller()
    private val held = CopyOnWriteArrayList<Dispatch>()
    private val parent = SupervisorJob()
    private val scope = CoroutineScope(parent + caller)
    private lateinit var server: MockWebServer
    private val state = AtomicReference(listOf(7))
    private val records = CopyOnWriteArrayList<Record>()
    private val publications = CopyOnWriteArrayList<List<Int>>()
    private val recorder = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val oldStarted = CountDownLatch(1)
    private val oldRelease = CountDownLatch(1)
    private val holdNext = AtomicBoolean(false)
    @Volatile private var replyCode = 200
    @Volatile private var malformed = false
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var ownerA = 0L
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun rows(ids: List<Int>) = ids.joinToString(prefix = "[", postfix = "]") {
        """{"id":$it,"name":"Synthetic $it","phone":"+70000000007"}"""
    }
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(tokenA)
        ownerA = ApiClient.queueSessionGeneration()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val r = Record(request.method, request.requestUrl!!.encodedPath, request.getHeader("Authorization"), request.body.readUtf8())
                    records += r
                    return when {
                        r.method == "GET" && r.path == "/trusted-contacts" -> {
                            val snapshot = if (r.bearer == "Bearer $tokenB") listOf(9) else state.get()
                            val code = replyCode; val bad = malformed
                            if (holdNext.compareAndSet(true, false)) {
                                oldStarted.countDown(); check(oldRelease.await(30, TimeUnit.SECONDS))
                            }
                            json(if (bad) """{"items":[{"id":"invalid","name":"Synthetic"}]}""" else rows(snapshot), code)
                        }
                        r.method == "DELETE" && r.path == "/trusted-contacts/7" -> { state.set(emptyList()); json("{}") }
                        r.method == "POST" && r.path == "/trusted-contacts" -> { state.set(listOf(7, 8)); json("{}") }
                        else -> json("{}")
                    }
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        recorder.launch {
            ContactsRepository.shared.state.filter { it.owner == ownerA }.mapNotNull { it.contacts }.distinctUntilChanged()
                .collect { list -> publications += list.map { c -> c.id } }
        }
    }
    @After fun cleanup() {
        recorder.cancel(); oldRelease.countDown(); parent.cancel()
        try {
            held.toList().forEach { resume(it) }
            while (!parent.isCompleted) caller.next().block.run()
            runBlocking { parent.join() }
        } finally { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    }
    private fun publish(owner: Long = ownerA) = runBlocking {
        TestContacts.read(owner)
    }
    private fun ids(fresh: Boolean = false): List<Int> = runBlocking {
        TestContacts.read(ApiClient.queueSessionGeneration(), force = fresh).getOrThrow().map { it.id }
    }
    private fun pending(): Pending {
        val entered = AtomicBoolean(false); val returned = AtomicBoolean(false); val completed = CountDownLatch(1)
        val job = scope.async {
            entered.set(true)
            try { TestContacts.read(ownerA) }
            finally { returned.set(true) }
        }
        job.invokeOnCompletion { completed.countDown() }; return Pending(job, entered, returned, completed)
    }
    private fun take() = caller.next().also { held += it }
    private fun resume(d: Dispatch) { assertTrue(held.remove(d)); d.block.run() }
    private fun start(): Pending {
        holdNext.set(true); val p = pending(); val initial = take(); assertSame(p.job, initial.job); resume(initial)
        assertTrue(oldStarted.await(10, TimeUnit.SECONDS)); assertTrue(p.entered.get()); assertFalse(p.returned.get())
        assertFalse(p.job.isCompleted); assertEquals(1L, p.completed.count); assertTrue(caller.tasks.isEmpty()); return p
    }
    private fun returning(p: Pending): Dispatch {
        oldRelease.countDown(); val d = take(); assertSame(p.job, d.job)
        assertFalse(p.job.isCompleted); assertFalse(p.returned.get()); return d
    }
    private fun finish(p: Pending, d: Dispatch): Result<List<ContactDto>> {
        resume(d); runBlocking { withTimeout(5000) { p.job.join() } }
        assertTrue(p.returned.get()); assertTrue(p.completed.await(5, TimeUnit.SECONDS)); assertTrue(p.job.isCompleted)
        assertFalse(p.job.isCancelled); assertTrue(caller.tasks.isEmpty()); return runBlocking { p.job.await() }
    }
    private fun cancel(p: Pending, d: Dispatch) {
        p.job.cancel(); assertTrue(p.job.isCancelled); assertFalse(p.job.isCompleted); resume(d)
        runBlocking { withTimeout(5000) { p.job.join() } }
        assertTrue(p.job.isCompleted); assertTrue(p.returned.get()); assertTrue(p.completed.await(5, TimeUnit.SECONDS))
        assertTrue(caller.tasks.isEmpty()); assertTrue(publications.isEmpty())
        try { runBlocking { p.job.await() }; fail("Expected thrown cancellation") } catch (_: CancellationException) { }
    }
    private fun wire(methods: List<String>, tokens: List<String> = List(methods.size) { tokenA }) {
        val contacts = records.filter { it.path.startsWith("/trusted-contacts") }
        assertEquals(methods, contacts.map { it.method }); assertEquals(tokens.map { "Bearer $it" }, contacts.map { it.bearer })
        assertEquals(methods.map { if (it == "DELETE") "/trusted-contacts/7" else "/trusted-contacts" }, contacts.map { it.path })
        contacts.filter { it.method != "POST" }.forEach { assertEquals("", it.body) }
    }
    private fun accepted(empty: Boolean, warm: Boolean) {
        state.set(if (empty) emptyList() else listOf(7)); val expected = state.get()
        if (warm) assertEquals(expected, ids())
        assertEquals(expected, publish().getOrThrow().map { it.id }); assertEquals(listOf(expected), publications.toList())
        assertEquals(expected, ids()); wire(listOf("GET"))
    }
    private fun failure(bad: Boolean) {
        malformed = bad; replyCode = if (bad) 200 else 503
        assertTrue(publish().isFailure); assertTrue(publications.isEmpty()); wire(listOf("GET"))
        malformed = false; replyCode = 200
        assertEquals(listOf(7), publish().getOrThrow().map { it.id }); assertEquals(listOf(listOf(7)), publications.toList())
        wire(listOf("GET", "GET"))
    }
    private fun ownerChange(queued: Boolean, logout: Boolean = false) {
        val p = start(); val d = if (queued) returning(p) else null
        if (logout) ApiClient.logout() else ApiClient.saveToken(tokenB)
        val owner = ApiClient.queueSessionGeneration(); assertNotEquals(ownerA, owner)
        if (!logout) assertEquals(listOf(9), ids())
        assertTrue(finish(p, d ?: returning(p)).isFailure); assertTrue(publications.isEmpty())
        if (logout) { assertNull(ApiClient.currentToken()); wire(listOf("GET")) }
        else { assertEquals(listOf(9), ids()); assertEquals(tokenB, ApiClient.currentToken()); wire(listOf("GET", "GET"), listOf(tokenA, tokenB)) }
        assertEquals(owner, ApiClient.queueSessionGeneration()); assertFalse(ApiClient.sessionExpired.value)
    }
    private fun superseded(add: Boolean?, queued: Boolean, failedFresh: Boolean = false) {
        val p = start(); val d = if (queued) returning(p) else null
        val expected = when (add) {
            true -> {
                runBlocking { TestContacts.add("Synthetic 8", "Synthetic", "+70000000008", true, ownerA).getOrThrow() }
                listOf(7, 8)
            }
            false -> { runBlocking { TestContacts.delete(7, ownerA).getOrThrow() }; emptyList() }
            null -> { state.set(listOf(8)); listOf(8) }
        }
        if (failedFresh) {
            replyCode = 503; assertTrue(runBlocking { TestContacts.read(ownerA, force = true) }.isFailure); replyCode = 200
        } else assertEquals(expected, ids(fresh = add == null))
        assertEquals(listOf(7), finish(p, d ?: returning(p)).getOrThrow().map { it.id })
        assertEquals("Rejected snapshot must never reach the state; only the newer read may", if (failedFresh) emptyList() else listOf(expected), publications.toList())
        assertEquals(expected, ids()); assertEquals(expected, ids()); assertEquals(ownerA, ApiClient.queueSessionGeneration())
        val methods = if (add == null) List(if (failedFresh) 3 else 2) { "GET" } else listOf("GET", if (add) "POST" else "DELETE", "GET")
        wire(methods)
        if (add == true) {
            val b = JSONObject(records.single { it.method == "POST" }.body); assertEquals(4, b.length())
            assertEquals("Synthetic 8", b.getString("name")); assertEquals("Synthetic", b.getString("relation"))
            assertEquals("+70000000008", b.getString("phone")); assertTrue(b.getBoolean("notify_by_default"))
        }
    }
    @Test fun coldSnapshotPublishesOnce() = accepted(false, false)
    @Test fun coldEmptySnapshotPublishesOnce() = accepted(true, false)
    @Test fun warmSnapshotPublishesOnceWithoutHttp() = accepted(false, true)
    @Test fun warmEmptySnapshotPublishesOnceWithoutHttp() = accepted(true, true)
    @Test fun serviceFailureDoesNotPublishOrRetry() = failure(false)
    @Test fun malformedSuccessDoesNotPublishOrCache() = failure(true)
    @Test fun staleOwnerCannotPublishOrInvalidateWarmNewOwnerCache() {
        ApiClient.saveToken(tokenB); assertEquals(listOf(9), ids()); assertTrue(publish(ownerA).isFailure)
        assertTrue(publications.isEmpty()); assertEquals(listOf(9), ids()); wire(listOf("GET"), listOf(tokenB))
    }
    @Test fun guestCannotPublishOrSendHttp() {
        ApiClient.logout(); assertTrue(publish(ApiClient.queueSessionGeneration()).isFailure)
        assertTrue(publications.isEmpty()); wire(emptyList())
    }
    @Test fun changedOwnerWhileHttpHeldRejectsPublication() = ownerChange(false)
    @Test fun changedOwnerAfterIoBeforeResumeRejectsPublication() = ownerChange(true)
    @Test fun logoutAfterIoBeforeResumeRejectsPublication() = ownerChange(true, true)
    @Test fun cancelledWhileHttpHeldNeverPublishes() {
        val p = start(); p.job.cancel(); cancel(p, returning(p)); assertEquals(listOf(7), ids()); wire(listOf("GET", "GET"))
    }
    @Test fun cancelledAfterIoBeforeResumeNeverPublishes() {
        val p = start(); cancel(p, returning(p)); assertEquals(listOf(7), ids()); wire(listOf("GET", "GET"))
    }
    @Test fun cancelledBeforeEntryDoesNotPublishWarmCacheOrSendHttp() {
        assertEquals(listOf(7), ids()); publications.clear() // the warm-up acceptance is not under test
        val p = pending(); val d = take(); assertSame(p.job, d.job)
        p.job.cancel(); resume(d); runBlocking { withTimeout(5000) { p.job.join() } }
        assertFalse(p.entered.get()); assertFalse(p.returned.get()); assertTrue(p.job.isCancelled); assertTrue(p.completed.await(5, TimeUnit.SECONDS))
        assertTrue(publications.isEmpty()); assertEquals(listOf(7), ids()); assertTrue(caller.tasks.isEmpty()); wire(listOf("GET"))
    }
    @Test fun confirmedDeleteWhileHttpHeldRejectsPublication() = superseded(false, false)
    @Test fun confirmedAddWhileHttpHeldRejectsPublication() = superseded(true, false)
    @Test fun confirmedDeleteAfterIoBeforeResumeRejectsPublication() = superseded(false, true)
    @Test fun confirmedAddAfterIoBeforeResumeRejectsPublication() = superseded(true, true)
    @Test fun freshReadWhileOldHttpHeldRejectsPublication() = superseded(null, false)
    @Test fun freshReadAfterOldIoBeforeResumeRejectsPublication() = superseded(null, true)
    @Test fun failedFreshReadStillRejectsEarlierPublication() = superseded(null, true, true)
}
