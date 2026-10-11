package com.yuldash.app.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real API callers; cancellation is witnessed by finally/completion/Deferred.join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsDeletionEventsTest {
    private lateinit var server: MockWebServer
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val deleted = AtomicBoolean(false)
    private var hold = false
    private var code = 200
    private var malformed = false
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>()); ApiClient.saveToken(tokenA)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/trusted-contacts" && request.method == "GET") {
                        val rows = if (deleted.get()) "[]" else """[{"id":7,"name":"A","relation":"Synthetic","phone":"+70000000007","notify_by_default":true}]"""
                        return MockResponse().setBody("""{"items":$rows}""")
                    }
                    if (request.method == "DELETE") {
                        started.countDown(); if (hold) check(release.await(20, TimeUnit.SECONDS))
                        if (code == 200) deleted.set(true)
                        return MockResponse().setResponseCode(code).setBody(if (malformed) "{\"ok\":" else "{\"ok\":true}")
                    }
                    return MockResponse().setBody("{\"ok\":true}")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() {
        release.countDown(); ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun version() = ApiClient.contactDeletions.value.version
    private suspend fun acceptedIds() = ApiClient.getContacts().getOrThrow().map { it.id }
    private fun receipt(before: Long, owner: Long, id: Int = 7) {
        val event = ApiClient.contactDeletions.value
        assertEquals(before + 1, event.version); assertEquals(owner, event.generation); assertEquals(id, event.contactId)
    }
    private fun settled(malformedReply: Boolean) = runBlocking {
        malformed = malformedReply; val before = version(); val owner = ApiClient.queueSessionGeneration()
        assertEquals(listOf(7), acceptedIds()); val result = ApiClient.deleteContact(7, owner)
        assertEquals(!malformedReply, result.isSuccess); receipt(before, owner); assertTrue(acceptedIds().isEmpty())
        assertEquals(3, server.requestCount)
        repeat(3) { val request = server.takeRequest(1, TimeUnit.SECONDS)!!; assertEquals("Bearer $tokenA", request.getHeader("Authorization")) }
    }
    @Test fun successfulDeleteEmitsOneOwnerScopedReceipt() = settled(false)
    @Test fun malformedDeleteEmitsOneReceiptAndKeepsOriginalFailure() = settled(true)
    @Test fun rejectedDeleteDoesNotEmitOrInvalidateWarmSnapshot() = runBlocking {
        code = 503; assertEquals(listOf(7), acceptedIds()); val before = version()
        val error = ApiClient.deleteContact(7).exceptionOrNull()!!; assertTrue(error is ApiException)
        assertEquals(before, version()); assertEquals(listOf(7), acceptedIds()); assertEquals(2, server.requestCount)
    }
    private fun cancelled(switchOwner: Boolean = false, logout: Boolean = false) = runBlocking {
        hold = true; assertEquals(listOf(7), acceptedIds()); val before = version(); val owner = ApiClient.queueSessionGeneration()
        val finished = AtomicBoolean(false); val completion = AtomicBoolean(false)
        val job = async(Dispatchers.Default) { try { ApiClient.deleteContact(7, owner) } finally { finished.set(true) } }
        job.invokeOnCompletion { completion.set(true) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); assertFalse(deleted.get()); assertFalse(job.isCompleted)
        if (switchOwner) ApiClient.saveToken(tokenB)
        if (logout) ApiClient.logout()
        job.cancel(); assertFalse(job.isCompleted); release.countDown(); job.join()
        try { job.await(); fail("Cancelled caller must throw") } catch (_: CancellationException) { }
        assertTrue(job.isCompleted); assertTrue(job.isCancelled); assertTrue(finished.get()); assertTrue(completion.get())
        if (switchOwner || logout) assertEquals(before, version()) else receipt(before, owner)
        assertTrue(deleted.get())
        if (!logout) { assertTrue(acceptedIds().isEmpty()); assertEquals(if (switchOwner) tokenB else tokenA, ApiClient.currentToken()) }
        val mutation = (1..server.requestCount).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS) }.single { it.method == "DELETE" }
        assertEquals("/trusted-contacts/7", mutation.path); assertEquals("Bearer $tokenA", mutation.getHeader("Authorization")); assertEquals(0L, mutation.bodySize)
    }
    @Test fun cancelledHeldDeleteEmitsOnlyAfterActualCallerCompletes() = cancelled()
    @Test fun cancelledOldOwnerDoesNotEmitIntoNewOwner() = cancelled(switchOwner = true)
    @Test fun cancelledLoggedOutOwnerDoesNotEmit() = cancelled(logout = true)
    @Test fun cancellationBeforeCallerEntrySendsAndEmitsNothing() = runBlocking {
        assertEquals(listOf(7), acceptedIds()); val before = version(); val entered = AtomicBoolean(false)
        val job = async(start = CoroutineStart.LAZY) { entered.set(true); ApiClient.deleteContact(7) }
        job.cancel(); job.join(); assertTrue(job.isCompleted); assertFalse(entered.get())
        assertEquals(before, version()); assertEquals(listOf(7), acceptedIds()); assertEquals(1, server.requestCount)
    }
    @Test fun twoDeletesHaveDistinctMonotonicVersions() = runBlocking {
        val before = version(); val owner = ApiClient.queueSessionGeneration()
        ApiClient.deleteContact(7, owner).getOrThrow(); receipt(before, owner)
        ApiClient.deleteContact(8, owner).getOrThrow(); receipt(before + 1, owner, 8); assertEquals(2, server.requestCount)
    }
    @Test fun successfulAddDoesNotClaimADeletionReceipt() = runBlocking {
        val before = version(); ApiClient.addContact("B", "Synthetic", "+70000000008", true).getOrThrow()
        assertEquals(before, version()); val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/trusted-contacts", request.path)
    }
    @Test fun completedOldOwnerDeleteKeepsStaleResultAndEmitsNothing() = runBlocking {
        hold = true; val before = version(); val owner = ApiClient.queueSessionGeneration()
        val job = async(Dispatchers.Default) { ApiClient.deleteContact(7, owner) }
        assertTrue(started.await(5, TimeUnit.SECONDS)); ApiClient.saveToken(tokenB); release.countDown()
        val result = job.await(); job.join(); assertTrue(job.isCompleted); assertTrue(result.isFailure)
        assertEquals(before, version()); assertEquals(tokenB, ApiClient.currentToken())
    }
}
