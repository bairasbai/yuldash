package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual cabinet lazy rows + HTTP: removing a child must not remove its parent's operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverCabinetBoardingRemountTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var host: DriverCabinetBoardingState
    private lateinit var parentScope: CoroutineScope
    private var visible by mutableStateOf(true)
    private var elapsed = 1000L
    private val epoch = "2026-10-25T19:30:24.000001"
    private val requests = CopyOnWriteArrayList<String>()
    private val plans = ConcurrentHashMap<String, LinkedBlockingQueue<Reply>>()
    private val allReplies = CopyOnWriteArrayList<Reply>()
    private class Reply(val body: String, val code: Int, blocked: Boolean) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (blocked) 1 else 0)
    }

    private fun queue(path: String, body: String, code: Int = 200, blocked: Boolean = false): Reply =
        Reply(body, code, blocked).also {
            plans.computeIfAbsent(path) { LinkedBlockingQueue() }.add(it)
            allReplies += it
        }

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path} ${request.body.readUtf8()}"
                    val reply = plans[request.path.orEmpty()]?.poll()
                    if (reply != null) {
                        reply.started.countDown()
                        if (!reply.release.await(15, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                        return MockResponse().setResponseCode(reply.code).setHeader("Content-Type", "application/json").setBody(reply.body)
                    }
                    return MockResponse().setResponseCode(if (request.path == "/driver/bookings") 404 else 200)
                        .setHeader("Content-Type", "application/json").setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 5000
    }

    @After fun cleanup() {
        allReplies.forEach { it.release.countDown() }
        if (::parentScope.isInitialized) compose.runOnIdle { parentScope.cancel() }
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    private fun dto(status: String = "confirmed", seconds: Int = 0, pending: Boolean = false, arriving: Boolean = false) =
        DriverBookingDto(42, "Айгуль", null, "Уфа → Мелеуз", status, rideId = 9,
            boardingUndoSec = seconds, noShowNeedsArriving = arriving,
            boardingUndo = BoardingUndoTerms(pending, if (pending) "request-1" else null, 120,
                if (status == "onboard") epoch else null))

    private fun render(initial: DriverBookingDto = dto()) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val state = remember {
                DriverCabinetBoardingState(ApiClient.queueSessionGeneration(), scope, now = { elapsed }).also {
                    it.acceptRead(it.beginRead(), listOf(initial))
                }
            }
            parentScope = scope
            host = state
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverCabinetContent(online = false, driverRides = emptyList(),
                    driverBookings = if (visible) state.effectiveRows else emptyList(), boardingHost = state,
                    ratingText = "—", onToggleOnline = {}, onRate = { _, _, _ -> },
                    onCreateRide = {}, onVerifyDriver = {}, onBoost = {}, onRequestsFeed = {})
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(15_000) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        condition()
    }
    private fun scroll(text: String, substring: Boolean = false) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = substring))
    }
    private fun mark() {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("driverBoardedButton"))
        compose.onNodeWithTag("driverBoardedButton").performClick()
    }
    private fun removeRow() = compose.runOnIdle { visible = false }
    private fun restoreRow() = compose.runOnIdle { visible = true }
    private fun postCount(path: String) = requests.count { it.startsWith("POST $path ") }
    private fun marked(seconds: Int = 300) = """{"status":"onboard","boarding_undo_sec":$seconds,"onboard_at":"$epoch"}"""
    private fun pending() = """{"status":"onboard","boarding_undo_sec":0,"onboard_at":"$epoch","boarding_undo_pending":true,"boarding_undo_request_id":"request-1","boarding_undo_wait_sec":120}"""
    private fun list(status: String = "onboard", seconds: Int = 0, pending: Boolean = false) =
        """{"items":[{"booking_id":42,"passenger_name":"Айгуль","route":"Уфа → Мелеуз","status":"$status","ride_id":9,"boarding_undo_sec":$seconds,"onboard_at":"$epoch","boarding_undo_pending":$pending,"boarding_undo_request_id":${if (pending) "\"old-request\"" else "null"}}]}"""

    @Test fun markAckSurvivesRemovalWhileHttpIsPendingAndDoesNotRenewTheClock() {
        val reply = queue("/bookings/42/driver-status", marked(), blocked = true)
        render(); mark(); waitFor { reply.started.count == 0L }
        removeRow(); reply.release.countDown()
        waitFor { host.row(42)?.status == "onboard" && host.row(42)?.busy == false }
        assertEquals(1, onboardPassengers(9, host.effectiveRows)) // Before any successful list GET.
        compose.runOnIdle { elapsed += 30_000 }
        restoreRow(); scroll("Пассажир в машине")
        compose.onNodeWithText("Пассажир в машине").assertIsDisplayed()
        scroll("Исправить посадку ·", true)
        compose.onNodeWithText("Исправить посадку · 4:30").assertIsDisplayed()
        compose.onAllNodesWithText("Сел").assertCountEquals(0)
        compose.onAllNodesWithText("Не пришёл").assertCountEquals(0)
        assertEquals(1, postCount("/bookings/42/driver-status"))
    }

    @Test fun delayedUndoAckRetainsTheWholeRequestAfterTheRowIsRecreated() {
        val reply = queue("/bookings/42/boarded/undo", pending(), blocked = true)
        render(dto("onboard", 120)); scroll("Исправить посадку ·", true)
        compose.onNodeWithText("Исправить посадку ·", substring = true).performClick()
        waitFor { reply.started.count == 0L }; removeRow(); reply.release.countDown()
        waitFor { host.row(42)?.undo?.pending == true && host.row(42)?.busy == false }
        restoreRow(); scroll("Ждём подтверждения пассажира")
        compose.onNodeWithText("Ждём подтверждения пассажира").assertIsDisplayed()
        compose.onAllNodesWithText("Исправить посадку ·", substring = true).assertCountEquals(0)
        assertEquals("request-1", host.row(42)!!.undo.requestId)
        assertEquals(epoch, host.row(42)!!.undo.onboardAt)
        assertEquals(120, host.row(42)!!.undo.waitSec)
        assertEquals(1, postCount("/bookings/42/boarded/undo"))
    }

    private fun confirmNoShow() {
        scroll("Не пришёл"); compose.onNodeWithText("Не пришёл").performClick()
        compose.onNodeWithText("Да, не пришёл").performClick()
    }

    @Test fun noShowAckAndItsNoteSurviveChildDisposalWithoutASecondPost() {
        val reply = queue("/bookings/42/no-show", """{"status":"cancelled"}""", blocked = true)
        render(); confirmNoShow(); waitFor { reply.started.count == 0L }
        removeRow(); reply.release.countDown()
        waitFor { host.row(42)?.status == "cancelled" && host.row(42)?.busy == false }
        restoreRow(); scroll("Отмечена неявка. Место вернулось в поездку.")
        compose.onNodeWithText("Отмечена неявка. Место вернулось в поездку.").assertIsDisplayed()
        assertEquals(1, postCount("/bookings/42/no-show"))
        assertEquals(0, onboardPassengers(9, host.effectiveRows))
    }

    @Test fun onboardRefusalKeepsEpochAndPendingFieldsAfterDisposal() {
        val reply = queue("/bookings/42/no-show",
            """{"detail":{"ru":"Посадка уже отмечена","ba":"Ултырыу билдәләнгән","code":"passenger_onboard","onboard_at":"$epoch","boarding_undo_sec":118,"boarding_undo_pending":true,"boarding_undo_request_id":"refusal-nonce","boarding_undo_wait_sec":111}}""",
            code = 409, blocked = true)
        render(); confirmNoShow(); waitFor { reply.started.count == 0L }
        removeRow(); reply.release.countDown()
        waitFor { host.row(42)?.status == "onboard" && host.row(42)?.busy == false }
        restoreRow(); scroll("Ждём подтверждения пассажира")
        assertEquals(epoch, host.row(42)!!.undo.onboardAt)
        assertEquals("refusal-nonce", host.row(42)!!.undo.requestId)
        assertEquals(111, host.row(42)!!.undo.waitSec)
        assertTrue(host.row(42)!!.undo.pending)
        compose.onAllNodesWithText("Не пришёл").assertCountEquals(0)
        assertEquals(1, postCount("/bookings/42/no-show"))
    }

    @Test fun aPendingMarkBlocksTheAlreadyOpenedNoShowConfirmation() {
        val reply = queue("/bookings/42/driver-status", marked(), blocked = true)
        render(); scroll("Не пришёл"); compose.onNodeWithText("Не пришёл").performClick()
        compose.runOnIdle { assertTrue(host.mark(42) {}) }
        waitFor { reply.started.count == 0L }
        compose.onNodeWithText("Да, не пришёл").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(host.noShow(42, {}, {})) }
        removeRow(); restoreRow()
        compose.runOnIdle { assertFalse(host.noShow(42, {}, {})) }
        reply.release.countDown(); waitFor { host.row(42)?.busy == false }
        assertEquals(0, postCount("/bookings/42/no-show"))
        assertEquals(1, postCount("/bookings/42/driver-status"))
    }

    @Test fun aPendingNoShowBlocksMarkAcrossRemovalAndRecreation() {
        val reply = queue("/bookings/42/no-show", """{"status":"cancelled"}""", blocked = true)
        render(); confirmNoShow(); waitFor { reply.started.count == 0L }
        compose.runOnIdle { assertFalse(host.mark(42) {}) }
        removeRow(); restoreRow()
        compose.runOnIdle { assertFalse(host.mark(42) {}) }
        reply.release.countDown(); waitFor { host.row(42)?.busy == false }
        assertEquals(0, postCount("/bookings/42/driver-status"))
        assertEquals(1, postCount("/bookings/42/no-show"))
    }

    @Test fun arrivingOutcomeAndNoShowTermsSurviveTheSameDisposal() {
        val reply = queue("/bookings/42/driver-status",
            """{"no_show_at":"2088-01-01T00:00:00","no_show_needs_arriving":false,"no_show_wait_sec":900}""", blocked = true)
        render(dto(arriving = true)); scroll("Подъезжаю"); compose.onNodeWithText("Подъезжаю").performClick()
        waitFor { reply.started.count == 0L }
        compose.runOnIdle { assertFalse(host.mark(42) {}) }
        removeRow(); reply.release.countDown(); waitFor { host.row(42)?.busy == false }
        restoreRow(); scroll("Не пришёл")
        compose.onNodeWithText("Не пришёл").assertIsNotEnabled()
        assertFalse(host.row(42)!!.noShow.needsArriving)
        assertNotNull(host.row(42)!!.noShow.waitUntilMs)
        assertEquals(1, postCount("/bookings/42/driver-status"))
        assertTrue(requests.single { it.startsWith("POST ") }.contains("arriving"))
    }

    private fun successfulReadKeepsTheServerWait(kind: String) {
        val oldIso = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 60_000), java.time.ZoneOffset.UTC).withNano(0))
        val arriving = kind != "no_show_too_early"
        val refused = kind != "arriving_ack"
        val actionPath = if (arriving) "/bookings/42/driver-status" else "/bookings/42/no-show"
        val body = when (kind) {
            "arriving_ack" -> """{"no_show_at":"$oldIso","no_show_needs_arriving":false,"no_show_wait_sec":900}"""
            "no_show_too_early" -> """{"detail":{"ru":"Ещё рано","ba":"Әле иртә","code":"no_show_too_early","no_show_at":"$oldIso","no_show_wait_sec":900}}"""
            else -> """{"detail":{"ru":"Ещё рано","ba":"Әле иртә","code":"arriving_too_early","arriving_opens_at":"$oldIso","arriving_wait_sec":900}}"""
        }
        queue(actionPath, body, code = if (refused) 409 else 200)
        val waitingForArrival = kind == "arriving_too_early"
        val fresh = queue("/driver/bookings",
            """{"items":[{"booking_id":42,"passenger_name":"Айгуль","passenger_rating":4.5,"route":"Уфа → Мелеуз","status":"confirmed","ride_id":9,"no_show_at":"$oldIso","no_show_wait_sec":900,"no_show_needs_arriving":$waitingForArrival,"arriving_opens_at":"$oldIso"}]}""",
            blocked = true)
        render(dto(arriving = arriving).copy(noShowAt = oldIso, arrivingOpensAt = oldIso))
        if (arriving) {
            scroll("Подъезжаю"); compose.onNodeWithText("Подъезжаю").performClick()
        } else confirmNoShow()
        waitFor { fresh.started.count == 0L && host.row(42)?.busy == false }
        val before = host.row(42)!!.noShow
        assertTrue((if (waitingForArrival) before.arrivingWaitUntilMs else before.waitUntilMs)!! > System.currentTimeMillis())
        removeRow(); fresh.release.countDown()
        waitFor { host.effectiveRows.single().passengerRating == 4.5 }
        restoreRow()
        val button = if (waitingForArrival) "Подъезжаю" else "Не пришёл"
        scroll(button); compose.onNodeWithText(button).assertIsNotEnabled()
        assertEquals(1, postCount(actionPath))
    }

    @Test fun successfulReadAfterArrivingDoesNotEraseTheServerSeconds() = successfulReadKeepsTheServerWait("arriving_ack")
    @Test fun successfulReadAfterNoShowTooEarlyDoesNotEraseTheServerSeconds() = successfulReadKeepsTheServerWait("no_show_too_early")
    @Test fun successfulReadAfterArrivingTooEarlyDoesNotEraseTheServerSeconds() = successfulReadKeepsTheServerWait("arriving_too_early")

    @Test fun successfulCancelledReadRetiresTheTransientNoShowNoteWithoutReopeningTheBooking() {
        queue("/bookings/42/no-show", """{"status":"cancelled"}""")
        val failedRead = queue("/driver/bookings", "{}", code = 404)
        render(); confirmNoShow(); waitFor {
            host.row(42)?.status == "cancelled" && host.row(42)?.busy == false && failedRead.started.count == 0L
        }
        // Failed reconciliation cannot remove the actual ACK; the next successful read must retire its note.
        removeRow(); restoreRow(); scroll("Отмечена неявка. Место вернулось в поездку.")
        compose.onNodeWithText("Отмечена неявка. Место вернулось в поездку.").assertIsDisplayed()
        queue("/driver/bookings", list(status = "cancelled"))
        lateinit var reader: Job
        compose.runOnIdle { reader = parentScope.launch { host.reload() } }
        waitFor { reader.isCompleted }
        assertEquals("cancelled", host.row(42)!!.status)
        assertFalse("A successful same-terminal read must retire the temporary active-row note", host.row(42)!!.noShowSent)
        removeRow(); restoreRow()
        compose.onAllNodesWithText("Отмечена неявка. Место вернулось в поездку.").assertCountEquals(0)
        compose.runOnIdle { assertFalse(host.mark(42) {}) }
        assertEquals(1, postCount("/bookings/42/no-show"))
        assertEquals(0, postCount("/bookings/42/driver-status"))
    }

    private fun reverseReads(failedLater: Boolean) {
        val a = queue("/driver/bookings", list(seconds = 120, pending = true), blocked = true)
        val b = queue("/driver/bookings", if (failedLater) "{}" else list(), code = if (failedLater) 404 else 200)
        render(dto("onboard", 0))
        lateinit var firstRead: Job
        lateinit var secondRead: Job
        compose.runOnIdle { firstRead = parentScope.launch { host.reload() } }
        waitFor { a.started.count == 0L }
        compose.runOnIdle { secondRead = parentScope.launch { host.reload() } }
        waitFor { b.started.count == 0L && secondRead.isCompleted }
        a.release.countDown(); waitFor { firstRead.isCompleted }
        assertEquals(0, host.remaining(42))
        assertFalse(host.row(42)!!.undo.pending)
        assertEquals(2, requests.count { it.startsWith("GET /driver/bookings ") })
    }

    @Test fun realHttpReadsOfTheSameEpochFinishingBThenACannotReopenTheWindow() = reverseReads(false)
    @Test fun anOlderHttpPayloadIsNotFallbackAfterTheNewerHttpFailure() = reverseReads(true)

    private fun readBeforeAction(undo: Boolean) {
        val oldRead = queue("/driver/bookings", list(status = if (undo) "onboard" else "confirmed", seconds = 120), blocked = true)
        val actionPath = if (undo) "/bookings/42/boarded/undo" else "/bookings/42/driver-status"
        queue(actionPath, if (undo) pending() else marked())
        render(if (undo) dto("onboard", 120) else dto())
        lateinit var reader: Job
        compose.runOnIdle { reader = parentScope.launch { host.reload() } }
        waitFor { oldRead.started.count == 0L }
        if (undo) {
            scroll("Исправить посадку ·", true)
            compose.onNodeWithText("Исправить посадку ·", substring = true).performClick()
        } else mark()
        waitFor { host.row(42)?.status == "onboard" && host.row(42)?.busy == false }
        oldRead.release.countDown(); waitFor { reader.isCompleted }
        assertEquals("onboard", host.row(42)!!.status)
        assertEquals(epoch, host.row(42)!!.undo.onboardAt)
        assertEquals(undo, host.row(42)!!.undo.pending)
        if (undo) assertEquals("request-1", host.row(42)!!.undo.requestId)
        assertEquals(1, postCount(actionPath))
    }

    @Test fun aListReadStartedBeforeMarkCannotRestoreItsOldConfirmedRow() = readBeforeAction(false)
    @Test fun aListReadStartedBeforeUndoCannotDiscardTheAcceptedRequest() = readBeforeAction(true)

    @Test fun latestTerminalReadSealsTheRowBeforeADelayedMarkAck() {
        val markReply = queue("/bookings/42/driver-status", marked(), blocked = true)
        render(); mark(); waitFor { markReply.started.count == 0L }
        queue("/driver/bookings", list(status = "done"))
        compose.runOnIdle { parentScope.launch { host.reload() } }
        waitFor { host.row(42)?.status == "done" }
        markReply.release.countDown(); waitFor { host.row(42)?.busy == false }
        assertEquals("done", host.row(42)!!.status)
        assertEquals(0, host.remaining(42))
        assertEquals(1, postCount("/bookings/42/driver-status"))
    }

    @Test fun replacingTheLoginRemovesTheRealCabinetAndRejectsOldOwnerNoShowBeforeHooks() {
        queue("/driver/bookings", list(status = "confirmed"))
        val markReply = queue("/bookings/42/driver-status", marked(), blocked = true)
        val owner = ApiClient.queueSessionGeneration()
        var left = 0
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverCabinetScreen(emptyList(), onBack = { left++ }, onCreateRide = {}, onVerifyDriver = {}, onBoost = {})
            }
        }
        waitFor { requests.any { it.startsWith("GET /driver/bookings ") } }
        mark(); waitFor { markReply.started.count == 0L }
        compose.runOnIdle { ApiClient.saveToken("header.eyJzdWIiOiIyMiJ9.signature") }
        waitFor { left == 1 }
        markReply.release.countDown()
        compose.onAllNodesWithText("Пассажир в машине").assertCountEquals(0)
        compose.onAllNodesWithText("Сел").assertCountEquals(0)
        val previousListener = TripLocationBus.driverStepListener
        var closingSteps = 0
        TripLocationBus.driverStepListener = { _, _, _ -> closingSteps++ }
        try {
            assertTrue(runBlocking { ApiClient.markNoShow(42, owner) }.isFailure)
            assertEquals(0, closingSteps)
            assertEquals(0, postCount("/bookings/42/no-show"))
        } finally { TripLocationBus.driverStepListener = previousListener }
    }
}
