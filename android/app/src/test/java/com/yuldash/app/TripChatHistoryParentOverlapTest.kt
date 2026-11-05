package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.Outbox
import com.yuldash.app.data.SnapshotOwner
import com.yuldash.app.data.SnapshotWiringCleanupGuard
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.data.snapshotOrigin
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Positive helper controls with real HTTP. Does not certify the Compose owner or manufacture trip authority. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TripChatHistoryParentOverlapTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestCoroutineScheduler()
    private val clientContext = ClientJobContext()
    private val ownerJob = SupervisorJob()
    private val asyncFailures = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(ownerJob + StandardTestDispatcher(clock) + clientContext +
        CoroutineExceptionHandler { _, failure -> asyncFailures += failure })
    private lateinit var server: MockWebServer
    private lateinit var ownedBase: String
    private var apiOwned = false
    private lateinit var loginOwner: SnapshotOwner
    private var generation = 0L
    private lateinit var history: TripChatHistoryPump
    private val requests = CopyOnWriteArrayList<String>()
    private val reads = CopyOnWriteArrayList<ClientRead>()
    private val acceptedIds = CopyOnWriteArrayList<Int>()
    private val historyCalls = AtomicInteger()
    private val authorizedCalls = AtomicInteger()
    private val firstArrived = CountDownLatch(1)
    private val firstRelease = CountDownLatch(1)
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    @Volatile private var ready = false
    @Volatile private var parentAlive = true

    @Before fun setup() = cleanupGuard.runSetup {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = requireNotNull(request.requestUrl).encodedPath
                requests += request.method + " " + path
                if (path in setOf("/auth/logout", "/push/unregister")) return json(JSONObject())
                if (request.method != "GET" || path != "/bookings/42/messages")
                    return MockResponse().setResponseCode(404).setBody("{}")
                val ordinal = historyCalls.incrementAndGet()
                if (request.getHeader("Authorization") != "Bearer " + token)
                    return MockResponse().setResponseCode(401).setBody("{}")
                authorizedCalls.incrementAndGet()
                if (ordinal == 1) {
                    firstArrived.countDown()
                    check(firstRelease.await(60, TimeUnit.SECONDS))
                }
                return json(JSONObject().put("items", JSONArray().put(JSONObject()
                    .put("id", ordinal).put("sender_id", 22).put("text", "overlap-" + ordinal))))
            }
        }
        server.start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        val base = server.url("/").newBuilder().host("127.0.0.1").build()
        check(base.host in setOf("localhost", "127.0.0.1", "::1"))
        ownedBase = base.toString().trimEnd('/')
        apiOwned = true
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = ownedBase
        assertBoundOrigin()
        ApiClient.testTimeoutMs = 60_000 // Held response must not turn into a timeout during bounded assertions.
        TripPassStore.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
        ApiClient.init(context)
        ApiClient.logout()
        awaitReset()
        awaitFixtureJobs()
        ApiClient.saveToken(token)
        generation = ApiClient.queueSessionGeneration()
        loginOwner = requireNotNull(OfflineTripSnapshots.currentOwner())
        assertCurrentLogin()
        ApiClient.testTrace = { line ->
            if (line == "вошли в вызов GET /bookings/42/messages") {
                val transport = clientContext.currentJob()
                reads += ClientRead(transport, requireNotNull(transport.parent))
            }
        }
        // Positional existing API: the same test source compiles before and after MAIN08.
        history = TripChatHistoryPump(42, scope, { historyParentCurrent() && ready }, ::historyParentCurrent,
            { 0L }, { acceptedIds.toSet() }, { rows, _ -> acceptedIds += rows.map { it.id } })
    }

    private fun historyParentCurrent() = parentAlive && ownerJob.isActive &&
        ApiClient.isCurrentSession(generation) && OfflineTripSnapshots.currentOwner() == loginOwner
    private fun assertBoundOrigin() { assertEquals(ownedBase, ApiClient.apiBase()) }
    private fun assertCurrentLogin() {
        assertBoundOrigin()
        assertTrue(loginOwner.userId == 11 && loginOwner.origin == snapshotOrigin(ownedBase) &&
            ApiClient.isCurrentSession(generation) && OfflineTripSnapshots.currentOwner() == loginOwner)
    }
    private fun json(value: JSONObject) = MockResponse()
        .setHeader("Content-Type", "application/json").setBody(value.toString())

    private class ClientJobContext : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ClientJobContext>
        private val current = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
        fun currentJob(): Job = requireNotNull(current.get()) { "Owned HTTP coroutine context missing" }
    }
    private data class ClientRead(val transport: Job, val caller: Job)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        do {
            clock.runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Owned helper coroutine failed", asyncFailures.isEmpty())
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        clock.runCurrent()
        assertTrue("Bounded owned helper condition timed out", condition())
    }
    private fun awaitCompleted(vararg jobs: Job) = waitFor {
        jobs.all { it.isCompleted } && reads.all { it.transport.isCompleted && it.caller.isCompleted }
    }
    private fun assertOnlyReadControl() {
        val allowed = setOf("GET /bookings/42/messages", "POST /auth/logout", "POST /push/unregister")
        assertTrue("Helper control sent an unexpected request", requests.all { it in allowed })
        assertTrue("Owned helper coroutine failed", asyncFailures.isEmpty())
    }

    @Test fun normalReadBeforeInitialSettlesSoftCancellationAndExplicitRetry() {
        var firstReadyResult: Boolean? = null
        val notReady = scope.launch { firstReadyResult = history.loadInitial(42, history.tick) }
        awaitCompleted(notReady)
        assertEquals(false, firstReadyResult)
        assertEquals(0, historyCalls.get())

        ready = true
        val normal = scope.launch { history.load(42) }
        waitFor { firstArrived.count == 0L && reads.size == 1 }
        val oldRead = reads.single()
        assertSame(normal, oldRead.caller)
        assertFalse(oldRead.transport.isCompleted)
        assertTrue(history.loading)
        assertFalse(history.error)

        var initialEntered = false
        var initialResult: Boolean? = null
        val initial = scope.launch {
            initialEntered = true
            initialResult = history.loadInitial(42, history.tick)
        }
        clock.runCurrent()
        assertTrue(initialEntered)
        assertTrue(initial.isActive)
        assertFalse(initial.isCompleted)
        assertNull(initialResult)
        assertEquals(1, historyCalls.get())
        assertEquals(1, reads.size)
        assertEquals(1L, firstRelease.count)

        ready = false
        normal.cancel()
        firstRelease.countDown()
        awaitCompleted(normal, initial)
        assertTrue(normal.isCancelled)
        assertTrue(oldRead.transport.isCompleted && oldRead.caller.isCompleted)
        assertFalse(initialResult ?: true)
        assertFalse(history.loading)
        assertTrue(history.error)
        assertTrue(acceptedIds.isEmpty())
        assertEquals(1, historyCalls.get())
        assertCurrentLogin()

        ready = true
        var recoveredResult: Boolean? = null
        val recoveredInitial = scope.launch { recoveredResult = history.loadInitial(42, history.tick) }
        awaitCompleted(recoveredInitial)
        assertEquals(true, recoveredResult)
        assertEquals(1, historyCalls.get())
        assertEquals(1, reads.size)
        assertFalse(history.loading)
        assertTrue(history.error)

        val sameTick = history.tick
        var retryResult: Boolean? = null
        val retry = scope.launch { retryResult = history.load(42) }
        awaitCompleted(retry)
        assertEquals(true, retryResult)
        assertEquals(sameTick, history.tick)
        assertEquals(2, historyCalls.get())
        assertEquals(2, authorizedCalls.get())
        assertEquals(2, reads.size)
        assertSame(retry, reads.last().caller)
        assertEquals(listOf(2), acceptedIds.toList())
        assertFalse(history.loading)
        assertFalse(history.error)
        assertOnlyReadControl()
    }

    @Test fun removedParentCannotSettleCancelledInitialOrStartAnotherRead() {
        ready = true
        val initial = scope.launch { history.loadInitial(42, history.tick) }
        waitFor { firstArrived.count == 0L && reads.size == 1 }
        val oldRead = reads.single()
        assertSame(initial, oldRead.caller)
        val loadingBefore = history.loading
        val errorBefore = history.error
        assertTrue(loadingBefore)
        assertFalse(errorBefore)

        parentAlive = false
        initial.cancel()
        firstRelease.countDown()
        awaitCompleted(initial)
        assertTrue(initial.isCancelled)
        assertTrue(oldRead.transport.isCompleted && oldRead.caller.isCompleted)
        assertEquals(loadingBefore, history.loading)
        assertEquals(errorBefore, history.error)
        assertTrue(acceptedIds.isEmpty())
        var lateResult: Boolean? = null
        val late = scope.launch { lateResult = history.loadInitial(42, history.tick) }
        awaitCompleted(late)
        assertEquals(false, lateResult)
        assertEquals(1, historyCalls.get())
        assertEquals(1, authorizedCalls.get())
        assertEquals(1, reads.size)
        assertOnlyReadControl()
    }

    // FIRST @After: no inherited fixture cleanup can reset an origin before owned readers finish.
    @After fun cleanup() {
        try {
            cleanupGuard.requireHealthy()
            cleanupPhase { ownerJob.cancel() }
        } finally {
            firstRelease.countDown()
        }
        cleanupPhase { waitFor { ownerJob.isCompleted &&
            reads.all { it.transport.isCompleted && it.caller.isCompleted } } }
        if (apiOwned) {
            cleanupPhase { assertBoundOrigin(); awaitFixtureJobs() }
            cleanupPhase { ApiClient.logout() }
            cleanupPhase { awaitReset(); awaitFixtureJobs() }
        }
        cleanupPhase {
            cleanupGuard.runCleanup(true) {
                if (::server.isInitialized) server.shutdown()
                if (apiOwned) {
                    TripPassStore.initStores(MemoryDiskPreferences(), null)
                    Outbox.initStores(MemoryDiskPreferences(), null)
                    ApiClient.testTrace = null
                    ApiClient.testTimeoutMs = null
                    ApiClient.resetForTest() // Origin rebind LAST, after every owned/global child and server drain.
                }
            }
        }
    }
    private fun cleanupPhase(action: () -> Unit) {
        try { action() } catch (failure: Throwable) { poisonCleanup(failure) }
    }
    private fun poisonCleanup(failure: Throwable): Nothing {
        try { cleanupGuard.runCleanup(false) {} } catch (poison: Throwable) {
            if (poison !== failure) poison.addSuppressed(failure)
            throw poison
        }
        throw AssertionError(SnapshotWiringCleanupGuard.POISON_MARKER)
    }
    private fun awaitReset() = runBlocking {
        withTimeout(5_000) { while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10) }
    }
    private fun awaitFixtureJobs() = runBlocking {
        val parents = listOf(ApiClient to "bg", OfflineTripSnapshots to "jobs").map { (owner, name) ->
            val ownerScope = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope
            requireNotNull(ownerScope.coroutineContext[Job])
        }
        withTimeout(10_000) {
            while (true) {
                val children = parents.flatMap { it.children.toList() }
                if (children.isEmpty()) break
                children.forEach { it.join() }
            }
        }
    }
}
