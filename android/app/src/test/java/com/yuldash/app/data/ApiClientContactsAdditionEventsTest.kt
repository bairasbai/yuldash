package com.yuldash.app.data

import android.app.Application
import android.content.Context
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual API Deferred cancellation is observed through finally/completion/join, not inferred from navigation.
 * Layering pilot: the add/delete event versions of ApiClient became ContactsRepository.state.changes —
 * one owner-guarded "the server may have changed" signal that every screen reconciles on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsAdditionEventsTest {
    private lateinit var server: MockWebServer
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val added = AtomicBoolean(false)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var hold = false
    private var code = 200
    private var malformed = false
    private var timeout = false
    private var upsert = false
    private val initial = """{"id":7,"name":"A","relation":"Old","phone":"+70000000007","notify_by_default":true}"""
    private val addition get() = """{"id":${if (upsert) 7 else 8},"name":"B","relation":"New","phone":"${if (upsert) "+70000000007" else "+70000000008"}","notify_by_default":false}"""
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>()); ApiClient.saveToken(tokenA)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.method == "GET" && request.requestUrl!!.encodedPath == "/trusted-contacts") {
                        val rows = if (!added.get()) initial else if (upsert) addition else "$initial,$addition"
                        return MockResponse().setBody("""{"items":[$rows]}""")
                    }
                    if (request.method == "POST" && request.requestUrl!!.encodedPath == "/trusted-contacts") {
                        started.countDown(); if (hold) check(release.await(20, TimeUnit.SECONDS))
                        if (code == 200) added.set(true)
                        return if (timeout) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else MockResponse().setResponseCode(code).setBody(if (malformed) "{\"id\":" else addition)
                    }
                    return MockResponse().setBody("{}")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        release.countDown(); ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun version() = TestContacts.changes
    private fun receipt(before: Long, owner: Long) {
        // Exactly one signal, and only while its account is still the current one.
        assertEquals(before + 1, version()); assertEquals(owner, ApiClient.queueSessionGeneration())
    }
    private suspend fun add(owner: Long? = null) = TestContacts.add("B", "New", if (upsert) "+70000000007" else "+70000000008", false, owner)
    private suspend fun ids() = TestContacts.read().getOrThrow().map { it.id }
    private fun exactPost() {
        val request = requests.single { it.method == "POST" && it.requestUrl!!.encodedPath == "/trusted-contacts" }
        assertEquals("/trusted-contacts", request.path); assertEquals("Bearer $tokenA", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8()); assertEquals(4, body.length())
        assertEquals("B", body.getString("name")); assertEquals("New", body.getString("relation"))
        assertEquals(if (upsert) "+70000000007" else "+70000000008", body.getString("phone")); assertFalse(body.getBoolean("notify_by_default"))
    }
    private fun settled(broken: Boolean) = runBlocking {
        malformed = broken; val before = version(); val owner = ApiClient.queueSessionGeneration()
        assertEquals(listOf(7), ids()); val result = add(owner)
        assertEquals(!broken, result.isSuccess); receipt(before, owner); assertEquals(listOf(7, 8), ids())
        assertEquals(3, server.requestCount); exactPost()
    }
    @Test fun successfulAdditionEmitsOneOwnerScopedEvent() = settled(false)
    @Test fun malformedAdditionEmitsOneEventAndKeepsFailure() = settled(true)
    @Test fun rejectedAdditionKeepsWarmSnapshotAndEmitsNothing() = runBlocking {
        code = 503; assertEquals(listOf(7), ids()); val before = version()
        assertTrue(add().exceptionOrNull() is ApiException); assertEquals(before, version()); assertEquals(listOf(7), ids())
        assertEquals(2, server.requestCount); exactPost()
    }
    private fun cancelled(switchOwner: Boolean = false, logout: Boolean = false) = runBlocking {
        hold = true; assertEquals(listOf(7), ids()); val before = version(); val owner = ApiClient.queueSessionGeneration()
        val finished = AtomicBoolean(false); val completion = AtomicBoolean(false)
        val job = async(Dispatchers.Default) { try { add(owner) } finally { finished.set(true) } }
        job.invokeOnCompletion { completion.set(true) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); assertFalse(added.get()); assertFalse(job.isCompleted)
        if (switchOwner) ApiClient.saveToken(tokenB)
        if (logout) ApiClient.logout()
        job.cancel(); assertFalse(job.isCompleted); release.countDown(); job.join()
        try { job.await(); fail("Cancelled caller must throw") } catch (_: CancellationException) { }
        assertTrue(job.isCompleted); assertTrue(job.isCancelled); assertTrue(finished.get()); assertTrue(completion.get())
        if (switchOwner || logout) assertEquals(before, version()) else receipt(before, owner)
        assertTrue(added.get()); exactPost()
        if (!logout) { assertEquals(listOf(7, 8), ids()); assertEquals(if (switchOwner) tokenB else tokenA, ApiClient.currentToken()) }
    }
    @Test fun cancelledHeldAdditionIsObservedAfterJoinedCaller() = cancelled()
    @Test fun cancelledOldOwnerDoesNotEmitIntoNewOwner() = cancelled(switchOwner = true)
    @Test fun cancelledLoggedOutOwnerDoesNotEmit() = cancelled(logout = true)
    @Test fun cancellationBeforeCallerEntrySendsAndEmitsNothing() = runBlocking {
        assertEquals(listOf(7), ids()); val before = version(); val entered = AtomicBoolean(false)
        val job = async(start = CoroutineStart.LAZY) { entered.set(true); add() }
        job.cancel(); job.join(); assertTrue(job.isCompleted); assertFalse(entered.get())
        assertEquals(before, version()); assertEquals(listOf(7), ids()); assertEquals(1, server.requestCount)
    }
    @Test fun successiveAdditionsHaveDistinctMonotonicVersions() = runBlocking {
        val before = version(); val owner = ApiClient.queueSessionGeneration()
        add(owner).getOrThrow(); receipt(before, owner); add(owner).getOrThrow(); receipt(before + 1, owner); assertEquals(2, server.requestCount)
    }
    // Was: a DELETE does not bump the separate addition channel. There is one channel now: a DELETE of a
    // contact absent from no known list is one change, and it is still a single DELETE, never a POST.
    @Test fun successfulDeleteIsOneChangeAndNeverAPost() = runBlocking {
        val before = version(); TestContacts.delete(7).getOrThrow(); assertEquals(before + 1, version())
        assertEquals(1, server.requestCount); assertEquals("DELETE", requests.single().method)
    }
    @Test fun completedOldOwnerAdditionKeepsStaleFailureAndEmitsNothing() = runBlocking {
        hold = true; val before = version(); val owner = ApiClient.queueSessionGeneration()
        val job = async(Dispatchers.Default) { add(owner) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); ApiClient.saveToken(tokenB); release.countDown()
        val result = job.await(); job.join(); assertTrue(job.isCompleted); assertTrue(result.isFailure)
        assertEquals(before, version()); assertEquals(tokenB, ApiClient.currentToken()); exactPost()
    }
    @Test fun responseTimeoutReadsServerTruthWithoutSecondPost() = runBlocking {
        timeout = true; ApiClient.testTimeoutMs = 1000; val before = version(); val owner = ApiClient.queueSessionGeneration()
        assertEquals(listOf(7), ids()); assertTrue(add(owner).isFailure); receipt(before, owner)
        assertEquals(listOf(7, 8), ids()); assertEquals(3, server.requestCount); exactPost()
    }
    @Test fun existingPhoneUpsertInvalidatesWarmFieldsWithSameId() = runBlocking {
        upsert = true; val before = version(); val owner = ApiClient.queueSessionGeneration()
        assertEquals("Old", TestContacts.read().getOrThrow().single().relation); add(owner).getOrThrow(); receipt(before, owner)
        val row = TestContacts.read().getOrThrow().single()
        assertEquals(7, row.id); assertEquals("B", row.name); assertEquals("New", row.relation); assertFalse(row.notifyByDefault)
        assertEquals(3, server.requestCount); exactPost()
    }
}
