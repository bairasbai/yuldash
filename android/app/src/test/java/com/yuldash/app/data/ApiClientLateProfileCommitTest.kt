package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientLateProfileCommitTest {
    private class PausedCaller : CoroutineDispatcher() {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(label: String): Runnable = tasks.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("Timed out waiting for $label")
    }

    private lateinit var server: MockWebServer
    private val caller = PausedCaller()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + caller)

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/me" -> MockResponse().setBody("{\"name\":\"Old account A\",\"role\":\"driver\"}")
                    "/me/update" -> MockResponse().setBody("{\"ok\":true}")
                    "/auth/verify" -> MockResponse().setBody("{\"access_token\":\"local-B\",\"refresh_token\":\"refresh-B\",\"user\":{\"name\":\"New account B\",\"role\":\"passenger\"}}")
                    "/auth/logout", "/push/unregister" -> MockResponse().setBody("{\"ok\":true}")
                    else -> MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 5_000
        ApiClient.init(ApplicationProvider.getApplicationContext())
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        ApiClient.saveToken("local-A")
        ApiClient.saveName("Original A")
        ApiClient.saveRole("driver")
    }

    @After fun cleanup() {
        job.cancel()
        ApiClient.logout()
        ApiClient.sessionExpired.value = false
        server.shutdown()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
    }

    private fun <T> finishOldResponseAfterSwitch(operation: suspend () -> Result<T>): Result<T> {
        val request = scope.async { operation() }
        caller.next("initial caller dispatch").run()
        // This continuation is enqueued only AFTER call's real IO block completed;
        // no network latch approximates the critical response/side-effect boundary.
        val completedIoContinuation = caller.next("successful HTTP result returning to caller")
        assertFalse(request.isCompleted)
        ApiClient.logout()
        ApiClient.saveToken("local-B")
        ApiClient.saveName("New account B")
        ApiClient.saveRole("passenger")
        completedIoContinuation.run()
        assertTrue("Resumed request must finish without another network operation", request.isCompleted)
        return runBlocking { request.await() }
    }

    @Test fun delayedMeContinuationCannotOverwriteNewAccountsNameOrRole() {
        finishOldResponseAfterSwitch { ApiClient.me() }
        assertEquals("local-B", ApiClient.currentToken())
        assertEquals("Old /me must not change B's displayed name", "New account B", ApiClient.cachedName())
        assertEquals("Old /me must not change B's cached role", "passenger", ApiClient.cachedRole())
    }

    @Test fun delayedUpdateNameContinuationCannotRenameTheNewAccount() {
        finishOldResponseAfterSwitch { ApiClient.updateName("Renamed old account A") }
        assertEquals("local-B", ApiClient.currentToken())
        assertEquals("Old /me/update must not rename B locally", "New account B", ApiClient.cachedName())
        assertEquals("passenger", ApiClient.cachedRole())
    }

    @Test fun directLoginToAnotherAccountDoesNotKeepThePreviousRole() = runBlocking {
        ApiClient.verifyCode("+70000000000", "000000", "B").getOrThrow()
        assertEquals("local-B", ApiClient.currentToken())
        assertEquals("New account B", ApiClient.cachedName())
        assertNotEquals("New login must clear or replace A's role", "driver", ApiClient.cachedRole())
    }
}
