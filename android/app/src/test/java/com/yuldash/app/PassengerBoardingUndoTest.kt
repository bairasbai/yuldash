package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.boardingUndoMatches
import com.yuldash.app.data.parseBoardingUndo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerBoardingUndoTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private var result: BoardingResult? = null

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }
    @After fun cleanup() { ApiClient.resetForTest(); server.shutdown() }
    private fun show(sec: Int = 120) = compose.setContent {
        PassengerBoardingUndo(42, BoardingUndoTerms(true, "request-1", sec)) { result = it }
    }
    private fun waitResult() = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        result != null
    }
    @Test fun passengerConfirmsThisRequestRatherThanChangingStatusLocally() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"confirmed","boarding_undo_sec":0}"""))
        show()
        compose.onNodeWithText("Я ещё не сел").performClick()
        waitResult()
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/bookings/42/boarded/undo/respond", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("request-1", body.getString("request_id"))
        assertTrue(body.getBoolean("confirm"))
        assertEquals("confirmed", result?.status)
    }
    @Test fun passengerRejectsTheCorrectionAndRemainsOnboard() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"onboard","boarding_undo_sec":0}"""))
        show()
        compose.onNodeWithText("Я в машине").performClick()
        waitResult()
        assertFalse(JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getBoolean("confirm"))
        assertEquals("onboard", result?.status)
    }
    @Test fun expiredRequestOffersNeitherAnswer() {
        show(0)
        compose.onAllNodesWithText("Я ещё не сел").assertCountEquals(0)
        compose.onAllNodesWithText("Я в машине").assertCountEquals(0)
        assertEquals(0, server.requestCount)
    }
    @Test fun oldReceiptCannotUndoTheNextBoarding() {
        val receipt = BoardingUndoTerms(confirmedId = "receipt", sourceOnboardAt = "2026-10-24T12:00:00.000000")
        assertTrue(boardingUndoMatches("2026-10-24T12:00:00Z", receipt))
        assertFalse(boardingUndoMatches("2026-10-24T12:00:00.000001Z", receipt))
        assertFalse(boardingUndoMatches(null, receipt))
        assertFalse(boardingUndoMatches("2026-10-24T12:00:00", BoardingUndoTerms()))
    }
    @Test fun oldServerDoesNotInventARequest() {
        assertEquals(BoardingUndoTerms(), parseBoardingUndo(JSONObject("{}")))
    }
    @Test fun heldReplyAfterNewRequestCannotApplyAndDoubleClickSendsOnce() = delayedBoundary("request")
    @Test fun heldReplyAfterBookingChangeCannotApply() = delayedBoundary("booking")
    @Test fun heldReplyAfterOwnerChangeCannotApply() = delayedBoundary("owner")
    @Test fun heldReplyAfterUnmountCannotApply() = delayedBoundary("unmount")
    private fun delayedBoundary(boundary: String) {
        val reply = CompletableDeferred<Result<BoardingResult>>()
        val mounted = mutableStateOf(true)
        val booking = mutableStateOf(42)
        val terms = mutableStateOf(BoardingUndoTerms(true, "request-1", 120))
        var calls = 0
        var finished = false
        compose.setContent {
            if (mounted.value) PassengerBoardingUndoContent(booking.value, terms.value,
                respond = { _, _, _, _ -> calls++; try { reply.await() } finally { finished = true } }) { result = it }
        }
        val oldClick = compose.onNodeWithText("Я ещё не сел").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { oldClick(); oldClick() }
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); calls == 1 }
        compose.runOnIdle {
            when (boundary) {
                "request" -> terms.value = terms.value.copy(requestId = "request-2")
                "booking" -> booking.value = 43
                "owner" -> ApiClient.logout()
                else -> mounted.value = false
            }
        }
        compose.runOnIdle { reply.complete(Result.success(BoardingResult("confirmed", 0))) }
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications(); finished }
        compose.waitForIdle()
        assertEquals(1, calls)
        assertNull(result)
    }

    @Test fun serverFailureKeepsTheSameRequestForExplicitRetry() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
            .setBody("""{"detail":{"ru":"Повтори ответ","ba":"Яуапты ҡабатла"}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"confirmed","boarding_undo_sec":0}"""))
        show()
        compose.onNodeWithText("Я ещё не сел").performClick()
        compose.waitUntil(15000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); Snapshot.sendApplyNotifications()
            compose.onAllNodesWithText("Повтори ответ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Я ещё не сел").performClick()
        waitResult()
        repeat(2) { assertEquals("request-1", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("request_id")) }
    }

}
