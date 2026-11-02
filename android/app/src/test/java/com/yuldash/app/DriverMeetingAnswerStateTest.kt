package com.yuldash.app

import android.app.Application
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DriverMeetingAnswerStateTest {
    @Test fun heldHttpDisablesDuplicateUntilFailureThenRetrySendsSameDecision() = runBlocking {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodies = CopyOnWriteArrayList<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/bookings/42/meeting-point/answer") return MockResponse().setResponseCode(404)
                    bodies += request.body.readUtf8()
                    if (bodies.size == 1) {
                        held.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                        return MockResponse().setResponseCode(503).setBody("{}")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody("{}")
                }
            }
            start()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 10000
        val state = DriverMeetingAnswerState()
        var refreshes = 0
        fun answer() = state.answer(scope, 42, "proposal-42", false, "Повтори попытку",
            request = { ApiClient.answerMeetingPoint(42, "proposal-42", 0, state.choiceFor(42)) }, onRefresh = { refreshes++ })
        try {
            answer()
            assertTrue(withContext(Dispatchers.IO) { held.await(5, TimeUnit.SECONDS) })
            assertTrue(state.isBusy(42))
            answer(); answer()
            assertEquals(1, server.requestCount)
            release.countDown()
            withTimeout(10000) { while (state.isBusy(42)) delay(10) }
            assertNotNull(state.errorFor(42, "proposal-42"))
            assertEquals(0, refreshes)
            assertFalse(state.choiceFor(42))
            answer()
            withTimeout(10000) { while (state.isBusy(42)) delay(10) }
            assertEquals(2, server.requestCount)
            assertEquals(1, refreshes)
            assertNull(state.errorFor(42, "proposal-42"))
            assertTrue(bodies.all { !JSONObject(it).getBoolean("accept") })
        } finally {
            release.countDown(); scope.coroutineContext[Job]?.cancelAndJoin()
            server.shutdown(); ApiClient.resetForTest()
        }
    }

    @Test fun staleRefreshesButOldSessionCannotApplyAndCancellationReleasesGuard() = runTest {
        var current = true
        val state = DriverMeetingAnswerState { current }
        var refreshes = 0
        state.answer(backgroundScope, 42, "p", true, "Повтори попытку",
            request = { Result.failure<Unit>(ApiException(409, "Место изменилось", "meeting_point_stale")) }, onRefresh = { refreshes++ })
        runCurrent()
        assertEquals(1, refreshes)
        assertEquals("Место изменилось", state.errorFor(42, "p"))
        assertNull(state.errorFor(42, "new-p"))
        val waiting = CompletableDeferred<Result<Unit>>()
        state.answer(backgroundScope, 43, "next", true, "Повтори попытку", request = { waiting.await() }, onRefresh = { refreshes++ })
        runCurrent(); assertTrue(state.isBusy(43))
        current = false; waiting.complete(Result.success(Unit)); runCurrent()
        assertEquals(1, refreshes)
        assertFalse(state.isBusy(43))
        current = true
        val job = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + job)
        state.answer(scope, 44, "cancel", true, "Повтори попытку", request = { awaitCancellation() }, onRefresh = {})
        runCurrent(); assertTrue(state.isBusy(44))
        job.cancelAndJoin(); assertFalse(state.isBusy(44))
    }
}
