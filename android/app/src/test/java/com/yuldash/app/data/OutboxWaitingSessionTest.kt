package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A waiting flush must retain the account it belonged to before the mutex suspension. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxWaitingSessionTest {
    companion object { private val cleanupGuard = SnapshotWiringCleanupGuard() }
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val held = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val posted = CopyOnWriteArrayList<Pair<String, String?>>()
    private val server = MockWebServer()
    private var serverStartAttempted = false
    private var apiInitAttempted = false

    @Before fun prepare() = cleanupGuard.runSetup { prepareFixture02() }

    private fun prepareFixture02() {
        ApiClient.resetForTest()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.contains("/bookings/") == true) {
                    posted += request.path.orEmpty() to request.getHeader("Authorization")
                    if (request.path == "/bookings/42/messages") {
                        held.countDown()
                        if (!release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    }
                }
                return MockResponse().setBody("{}")
            }
        }
        serverStartAttempted = true
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 30000
        apiInitAttempted = true
        ApiClient.init(context)
        awaitSnapshotReset02()
        ApiClient.saveToken("local-account-A")
        Outbox.initStores(MemoryDiskPreferences(), MemoryDiskPreferences())
    }

    @After fun cleanup() {
        cleanupGuard.requireHealthy()
        try {
            release.countDown()
            if (apiInitAttempted) ApiClient.logout()
            awaitFixtureJobs02()
            cleanupGuard.requireHealthy()
            if (serverStartAttempted) server.shutdown()
            cleanupGuard.runCleanup(true) {
                ApiClient.resetForTest()
                ApiClient.testTimeoutMs = null
                Outbox.initStores(MemoryDiskPreferences(), null)
            }
        } catch (failure: Throwable) {
            recordBlockedCleanup03(failure)
        }
    }

    private fun recordBlockedCleanup03(failure: Throwable): Nothing {
        try {
            cleanupGuard.runCleanup(false) {}
        } catch (blocked: AssertionError) {
            blocked.addSuppressed(failure)
            throw blocked
        }
        throw failure // Defensive only: the pinned guard always throws for permitted=false.
    }

    /** Await only the real logout/startup reset before a legitimate new login. */
    private fun awaitSnapshotReset02() = runBlocking {
        withTimeout(5_000) {
            while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10)
        }
    }

    private fun awaitFixtureJobs02() = runBlocking {
        val parents = listOf(ApiClient to "bg", OfflineTripSnapshots to "jobs").map { (owner, name) ->
            val scope = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as CoroutineScope
            requireNotNull(scope.coroutineContext[Job])
        }
        withTimeout(5_000) {
            while (true) {
                val children = parents.flatMap { it.children.toList() }
                if (children.isEmpty()) break
                children.forEach { it.join() }
                delay(10)
            }
        }
    }

    @Test fun waitingFlushDoesNotAdoptTheNextAccountsQueue() = runBlocking {
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(42, "A-message")))
        val first = async(Dispatchers.IO) { Outbox.flush(context) }
        try {
            assertTrue("First flush did not reach HTTP", held.await(5, TimeUnit.SECONDS))
            // UNDISPATCHED guarantees entry and suspension at the occupied production mutex.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { Outbox.flush(context) }
            assertFalse(waiting.isCompleted)
            ApiClient.logout()
            awaitSnapshotReset02()
            try {
                ApiClient.saveToken("local-account-B")
            } catch (failure: Throwable) {
                runCatching { com.yuldash.app.data.printAuthResetDiagnostic01("remaining-outbox-waiting-b-login-failed", loginResultKnown = true, loginFailed = true) }
                    .onFailure { diagnosticFailure -> println("REMAINING_AUTH_DIAG01 phase=remaining-outbox-waiting-b-login-failed diagnosticUnavailable=" + diagnosticFailure.javaClass.simpleName) }
                throw failure
            }
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(99, "B-message")))
            release.countDown()
            assertFalse(first.await())
            assertFalse("Waiting A-flush adopted B's queue", waiting.await())
            assertEquals(1, Outbox.count(context, 99))
            assertEquals(listOf("/bookings/42/messages" to "Bearer local-account-A"), posted.toList())
            assertTrue("Current B must still be able to send its own queue", Outbox.flush(context))
            assertEquals(0, Outbox.count(context, 99))
            assertEquals("/bookings/99/messages" to "Bearer local-account-B", posted.last())
            assertEquals(2, posted.size)
        } finally { release.countDown() }
    }
}
