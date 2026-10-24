package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
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
}
