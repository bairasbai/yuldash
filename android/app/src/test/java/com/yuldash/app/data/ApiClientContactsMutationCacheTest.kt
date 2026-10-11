package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual HTTP/cache: a completed old GET must not repopulate a successfully invalidated key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsMutationCacheTest {
    private data class Reply(val body: String, val code: Int = 200, val held: Boolean = false,
        val started: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))
    private data class Read(val deferred: Deferred<Result<List<ContactDto>>>, val returned: AtomicBoolean,
        val completed: CountDownLatch)
    private lateinit var server: MockWebServer
    private val reads = ConcurrentLinkedQueue<Reply>()
    private val allReplies = CopyOnWriteArrayList<Reply>()
    private val records = CopyOnWriteArrayList<Triple<String?, String?, String?>>()
    private val postedBodies = CopyOnWriteArrayList<String>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private var generation = 0L
    private var deleteCode = 200
    private fun contacts(vararg ids: Int) = ids.joinToString(prefix = "[", postfix = "]") {
        """{"id":$it,"name":"Synthetic $it","phone":"+70000000007"}"""
    }
    private fun reply(body: String, code: Int = 200, held: Boolean = false): Reply =
        Reply(body, code, held).also { reads += it; allReplies += it }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(tokenA)
        generation = ApiClient.queueSessionGeneration()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                records += Triple(request.method, path, request.getHeader("Authorization"))
                return when {
                    request.method == "GET" && path == "/trusted-contacts" -> {
                        val r = reads.poll() ?: return json("Unexpected contacts GET", 500)
                        r.started.countDown()
                        if (r.held) check(r.release.await(30, TimeUnit.SECONDS))
                        json(r.body, r.code)
                    }
                    request.method == "DELETE" && path.startsWith("/trusted-contacts/") -> json("{}", deleteCode)
                    request.method == "POST" && path == "/trusted-contacts" -> {
                        postedBodies += request.body.readUtf8(); json("{}")
                    }
                    request.method == "GET" && path == "/feed" -> json("""{"today":17}""")
                    else -> json("{}", 404)
                }
            }
        }
        server.start(); ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        allReplies.forEach { it.release.countDown() }
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        try { block() } finally { allReplies.forEach { it.release.countDown() } }
    }
    private fun CoroutineScope.start(r: Reply): Read {
        val returned = AtomicBoolean(false); val completed = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            try { ApiClient.getContacts(expectedGeneration = generation) } finally { returned.set(true) }
        }
        job.invokeOnCompletion { completed.countDown() }
        assertTrue(r.started.await(10, TimeUnit.SECONDS)); assertFalse(job.isCompleted); assertFalse(returned.get())
        return Read(job, returned, completed)
    }
    private suspend fun finish(r: Reply, read: Read): Result<List<ContactDto>> {
        r.release.countDown(); val result = read.deferred.await(); read.deferred.join()
        assertTrue(read.completed.await(5, TimeUnit.SECONDS)); assertTrue(read.returned.get())
        assertTrue(read.deferred.isCompleted); assertFalse(read.deferred.isCancelled)
        return result
    }
    private suspend fun ids() = ApiClient.getContacts(expectedGeneration = generation).getOrThrow().map { it.id }
    private fun counts(gets: Int, deletes: Int, token: String = tokenA) {
        val contact = records.filter { it.second!!.startsWith("/trusted-contacts") }
        assertEquals(gets, contact.count { it.first == "GET" }); assertEquals(deletes, contact.count { it.first == "DELETE" })
        assertEquals(gets + deletes, contact.size); assertTrue(contact.all { it.third == "Bearer $token" })
        assertEquals(generation, ApiClient.queueSessionGeneration())
    }
    @Test fun lateGetCannotOverwriteFreshCacheAfterSuccessfulDelete() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); reply("[]"); assertEquals(emptyList<Int>(), ids()); counts(2, 1)
        assertEquals(listOf(7), finish(old, read).getOrThrow().map { it.id })
        assertEquals("Late old GET must not replace fresh empty cache", emptyList<Int>(), ids()); counts(2, 1)
    }
    @Test fun lateGetBeforeFreshReadCannotRepopulateInvalidatedCache() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); assertEquals(listOf(7), finish(old, read).getOrThrow().map { it.id }); counts(1, 1)
        reply("[]"); assertEquals("Invalidated key must fetch again", emptyList<Int>(), ids()); counts(2, 1)
    }
    @Test fun failedFreshReadDoesNotLetLateGetRestoreDeletedContact() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); reply("{}", 503)
        assertTrue(ApiClient.getContacts(expectedGeneration = generation).isFailure); counts(2, 1)
        assertEquals(listOf(7), finish(old, read).getOrThrow().map { it.id })
        reply("[]"); assertEquals(emptyList<Int>(), ids()); counts(3, 1)
    }
    @Test fun rejectedDeleteDoesNotDiscardValidInFlightRead() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old); deleteCode = 503
        val rejected = ApiClient.deleteContact(7); assertTrue(rejected.isFailure)
        assertEquals(503, (rejected.exceptionOrNull() as ApiException).status)
        assertEquals(listOf(7), finish(old, read).getOrThrow().map { it.id })
        assertEquals(listOf(7), ids()); counts(1, 1)
    }
    @Test fun lateFailedGetDoesNotDamageFreshCache() = scenario {
        val old = reply("{}", 503, held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); reply("[]"); assertEquals(emptyList<Int>(), ids())
        val result = finish(old, read); assertTrue(result.isFailure); assertEquals(503, (result.exceptionOrNull() as ApiException).status)
        assertEquals(emptyList<Int>(), ids()); counts(2, 1)
    }
    @Test fun newAccountCacheSurvivesLateOldReadAfterDelete() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); ApiClient.saveToken(tokenB)
        generation = ApiClient.queueSessionGeneration(); reply(contacts(8)); assertEquals(listOf(8), ids())
        assertTrue(finish(old, read).isFailure); assertEquals(listOf(8), ids())
        assertEquals(listOf("Bearer $tokenA", "Bearer $tokenA", "Bearer $tokenB"), records.map { it.third })
        assertEquals(listOf("GET", "DELETE", "GET"), records.map { it.first }); assertEquals(3, server.requestCount)
    }
    @Test fun twoDeleteRevisionsRejectBothOlderReads() = scenario {
        val first = reply(contacts(7, 8), held = true); val firstRead = start(first)
        ApiClient.deleteContact(7).getOrThrow(); val second = reply(contacts(8), held = true); val secondRead = start(second)
        ApiClient.deleteContact(8).getOrThrow(); reply("[]"); assertEquals(emptyList<Int>(), ids())
        assertEquals(listOf(8), finish(second, secondRead).getOrThrow().map { it.id }); assertEquals(emptyList<Int>(), ids()); counts(3, 2)
        assertEquals(listOf(7, 8), finish(first, firstRead).getOrThrow().map { it.id }); assertEquals(emptyList<Int>(), ids()); counts(3, 2)
    }
    @Test fun contactDeletionPreservesIndependentFeedCache() = scenario {
        val feed = ApiClient.getFeed().getOrThrow(); assertEquals(17, feed.today)
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.deleteContact(7).getOrThrow(); reply("[]"); assertEquals(emptyList<Int>(), ids())
        finish(old, read).getOrThrow(); assertEquals(emptyList<Int>(), ids()); counts(2, 1)
        assertEquals(feed, ApiClient.getFeed().getOrThrow()); assertEquals(1, records.count { it.second == "/feed" })
        assertEquals(4, server.requestCount)
    }
    @Test fun successfulDeleteInvalidatesAlreadyWarmCache() = scenario {
        reply(contacts(7)); assertEquals(listOf(7), ids()); assertEquals(listOf(7), ids()); counts(1, 0)
        ApiClient.deleteContact(7).getOrThrow(); reply("[]"); assertEquals(emptyList<Int>(), ids())
        assertEquals(emptyList<Int>(), ids()); counts(2, 1)
    }
    @Test fun successfulAddKeepsFreshCacheAndExistingRequestContract() = scenario {
        val old = reply(contacts(7), held = true); val read = start(old)
        ApiClient.addContact("Synthetic 8", "Synthetic", "+70000000008", true).getOrThrow()
        reply(contacts(7, 8)); assertEquals(listOf(7, 8), ids())
        assertEquals(listOf(7), finish(old, read).getOrThrow().map { it.id }); assertEquals(listOf(7, 8), ids())
        assertEquals(listOf("GET", "POST", "GET"), records.map { it.first })
        assertTrue(records.all { it.second == "/trusted-contacts" && it.third == "Bearer $tokenA" })
        assertEquals(3, server.requestCount); assertEquals(generation, ApiClient.queueSessionGeneration())
        val body = org.json.JSONObject(postedBodies.single())
        assertEquals(4, body.length()); assertEquals("Synthetic 8", body.getString("name"))
        assertEquals("Synthetic", body.getString("relation")); assertEquals("+70000000008", body.getString("phone"))
        assertTrue(body.getBoolean("notify_by_default"))
    }
}
