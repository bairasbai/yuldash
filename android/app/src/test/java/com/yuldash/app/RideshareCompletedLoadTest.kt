package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.json.JSONObject
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Completed/Compose and API loopback HTTP. No production payment or notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideshareCompletedLoadTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val booking = mutableStateOf(42)
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val timeouts = CopyOnWriteArrayList<String>()
    private val receiptCalls = AtomicInteger()
    private val tipCalls = AtomicInteger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val replied = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private var language = AppLanguage.Ru
    private var role = "passenger"
    private var receiptFailures = 0
    private var tipFailures = 0
    private var heldKind: String? = null
    private var heldOrdinal = 1
    private var thanksCode = 200
    private var commitBeforeFailure = false
    private var dropThanksBody = false
    @Volatile private var thanked = false
    private var receiptNavigations = 0
    private var closes = 0
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIzMyJ9.signature"
    @Before fun prepare() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = "${request.method} ${request.path}"
                    requests += key to request.getHeader("Authorization")
                    bodies += key to request.body.readUtf8()
                    val id = request.path.orEmpty().split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    val kind = when {
                        key == "GET /trips/$id/receipt" -> "receipt"
                        key == "GET /bookings/$id/tip" -> "tip"
                        key == "POST /bookings/$id/thanks" -> "thanks"
                        key == "POST /bookings/$id/rate" -> "rate"
                        else -> return MockResponse().setResponseCode(404).setBody("{}")
                    }
                    val ordinal = when (kind) { "receipt" -> receiptCalls.incrementAndGet(); "tip" -> tipCalls.incrementAndGet(); else -> 1 }
                    val previousThanks = thanked
                    if (kind == "thanks" && (thanksCode == 200 || commitBeforeFailure)) thanked = true
                    if (kind == heldKind && ordinal == heldOrdinal && id == 42) {
                        started.countDown(); if (!release.await(40, TimeUnit.SECONDS)) timeouts += kind
                        replied.countDown()
                    }
                    val code = when { kind == "receipt" && ordinal <= receiptFailures -> 503; kind == "tip" && ordinal <= tipFailures -> 503; kind == "thanks" -> thanksCode; else -> 200 }
                    val body = when (kind) {
                        "receipt" -> """{"booking_id":$id,"ride_id":9,"role":"$role","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"counterparty_name":"Водитель$id","my_stars":0,"my_rating_tags":""}"""
                        "tip" -> """{"driver_name":"Водитель$id","already_thanked":$previousThanks}"""
                        else -> "{}"
                    }
                    val response = MockResponse().setResponseCode(code).setBody(if (code == 200) body else "{}")
                    return if (kind == "thanks" && dropThanksBody) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else response
                }
            }; start()
        }
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(tokenA)
    }
    @After fun cleanup() {
        release.countDown(); compose.runOnIdle { mounted.value = false }; pump(300)
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
        assertTrue("Fixture timed out: $timeouts", timeouts.isEmpty())
    }
    private fun pump(ms: Long = 700) {
        repeat((ms / 100).toInt()) {
            compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10)
        }; compose.waitForIdle()
    }
    private fun waitFor(predicate: () -> Boolean) = compose.waitUntil(12000) {
        compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); predicate()
    }
    private fun mount() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides language) {
                RideshareCompletedScreen(booking.value, null, role, "cash", 400,
                    onClose = { closes++; mounted.value = false }, onOpenReceipt = { receiptNavigations++ }, onSupport = {})
            }
        }
        waitFor { receiptCalls.get() >= 1 && (role == "driver" || tipCalls.get() >= 1) }; pump()
    }
    private fun scroll(matcher: SemanticsMatcher) = compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(matcher)
    private fun thanksLabel() = if (language == AppLanguage.Ba) "«Рәхмәт» әйтеү" else "Сказать «Рәхмәт»"
    private fun thanks() = compose.onNodeWithText(thanksLabel())
    private fun clickThanks(): () -> Boolean { scroll(hasText(thanksLabel())); return thanks().fetchSemanticsNode().config[SemanticsActions.OnClick].action!! }
    private fun retry(kind: String): SemanticsNodeInteraction {
        val tag = if (kind == "receipt") "rideshareReceiptRetry" else "rideshareThanksRetry"
        if (kind != "receipt" || compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) { scroll(hasTestTag(tag)); return compose.onNodeWithTag(tag) }
        // Before patch the receipt retry has no tag; keep the regression about requests, not a missing tag.
        scroll(hasText(if (language == AppLanguage.Ba) "Ҡабатлау" else "Повторить"))
        return compose.onNodeWithText(if (language == AppLanguage.Ba) "Ҡабатлау" else "Повторить")
    }
    private fun posts() = requests.count { it.first == "POST /bookings/42/thanks" }
    private fun sentLabel() = if (language == AppLanguage.Ba) "Рәхмәт ебәрелде" else "Рәхмәт передано"
    @Test fun ordinaryReceiptAndThanksControl() {
        mount(); scroll(hasText(thanksLabel())); thanks().performClick(); waitFor { posts() == 1 }; pump()
        compose.onNodeWithText(sentLabel()).assertIsDisplayed()
        scroll(hasText("Квитанция")); compose.onNodeWithText("Квитанция").performClick()
        assertEquals(1, receiptNavigations); assertEquals(1, receiptCalls.get()); assertEquals(1, tipCalls.get())
        assertTrue(requests.all { it.second == "Bearer $tokenA" })
    }
    @Test fun alreadyThankedControl() {
        thanked = true; mount(); scroll(hasText(sentLabel())); compose.onNodeWithText(sentLabel()).performClick(); pump()
        assertEquals(0, posts())
    }
    @Test fun driverReceiptControlDoesNotLoadPassengerThanks() {
        role = "driver"; mount(); scroll(hasText("Квитанция")); compose.onNodeWithText("Квитанция").performClick()
        assertEquals(1, receiptNavigations); assertEquals(0, tipCalls.get()); assertEquals(0, posts())
    }
    @Test fun initialHeldThanksBlocksSendUntilConfirmedFalse() {
        heldKind = "tip"; mount(); val old = clickThanks(); compose.runOnIdle { old() }; pump()
        assertEquals("Sent before authoritative tip snapshot", 0, posts())
        thanks().assertIsNotEnabled(); compose.onNodeWithTag("rideshareThanksLoading").assertExists()
        release.countDown(); waitFor { replied.count == 0L }; pump()
        thanks().assertIsEnabled(); thanks().performClick(); waitFor { posts() == 1 }; pump()
        compose.onNodeWithText(sentLabel()).assertIsDisplayed()
    }
    @Test fun heldAlreadyThankedCannotSendDuplicate() {
        thanked = true; heldKind = "tip"; mount(); val old = clickThanks(); compose.runOnIdle { old() }; pump()
        assertEquals(0, posts()); release.countDown(); waitFor { replied.count == 0L }; pump()
        scroll(hasText(sentLabel())); compose.onNodeWithText(sentLabel()).assertIsDisplayed()
    }
    private fun failedThanks(ba: Boolean, saved: Boolean) {
        language = if (ba) AppLanguage.Ba else AppLanguage.Ru; thanked = saved; tipFailures = 1; mount()
        val old = clickThanks(); compose.runOnIdle { old() }; pump(); assertEquals(0, posts())
        scroll(hasTestTag("rideshareThanksError")); compose.onNodeWithTag("rideshareThanksError").assertIsDisplayed()
        retry("tip").performClick(); waitFor { tipCalls.get() == 2 }; pump()
        if (saved) { scroll(hasText(sentLabel())); compose.onNodeWithText(sentLabel()).assertIsDisplayed() }
        else { scroll(hasText(thanksLabel())); thanks().assertIsEnabled() }
        assertEquals(0, posts())
    }
    @Test fun russianTipErrorRetryRestoresUnsent() = failedThanks(false, false)
    @Test fun bashkirTipErrorRetryRestoresAlreadySent() = failedThanks(true, true)
    @Test fun doubleThanksRetryStartsOneHeldGet() {
        tipFailures = 1; heldKind = "tip"; heldOrdinal = 2; mount()
        val old = retry("tip").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { old(); old() }; waitFor { started.count == 0L }; pump()
        compose.runOnIdle { old() }; pump(); assertEquals(2, tipCalls.get()); assertEquals(0, posts())
        release.countDown(); pump()
    }
    private fun receiptRetry(ba: Boolean) {
        language = if (ba) AppLanguage.Ba else AppLanguage.Ru; receiptFailures = 1; mount()
        retry("receipt").performClick(); waitFor { receiptCalls.get() == 2 }; pump()
        scroll(hasTestTag("ridesharePaymentSummary")); compose.onNodeWithText(if (ba) "Түләү раҫланды" else "Оплата подтверждена").assertIsDisplayed()
        assertEquals(0, posts())
    }
    @Test fun russianReceiptErrorRetryLoadsActualData() = receiptRetry(false)
    @Test fun bashkirReceiptErrorRetryLoadsActualData() = receiptRetry(true)
    @Test fun retainedReceiptRetryCannotRestartHeldGet() {
        receiptFailures = 1; heldKind = "receipt"; heldOrdinal = 2; mount()
        val old = retry("receipt").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { old(); old() }; waitFor { started.count == 0L }; pump()
        compose.runOnIdle { old() }; pump(); assertEquals("Restarted pending receipt GET", 2, receiptCalls.get())
        release.countDown(); pump()
    }
    @Test fun heldReceiptKeepsUserRatingEdit() {
        heldKind = "receipt"; mount()
        compose.onNodeWithTag("rideshareStar4").performScrollTo().performClick()
        release.countDown(); waitFor { replied.count == 0L }; pump()
        compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled().performClick()
        waitFor { bodies.any { it.first == "POST /bookings/42/rate" } }
        assertEquals(4, JSONObject(bodies.single { it.first == "POST /bookings/42/rate" }.second).getInt("stars"))
    }
    private fun uncertain(saved: Boolean, twice: Boolean) {
        thanksCode = if (dropThanksBody) 200 else 503; commitBeforeFailure = saved; mount(); val old = clickThanks()
        compose.runOnIdle { old() }; waitFor { posts() == 1 }; pump()
        compose.runOnIdle { old() }; pump(); assertEquals("Repeated uncertain POST", 1, posts())
        scroll(hasTestTag("rideshareThanksError")); compose.onNodeWithTag("rideshareThanksError").assertIsDisplayed()
        if (twice) tipFailures = 2
        retry("tip").performClick(); waitFor { tipCalls.get() == 2 }; pump()
        if (twice) { compose.runOnIdle { old() }; pump(); assertEquals(1, posts()); tipFailures = 0; retry("tip").performClick(); waitFor { tipCalls.get() == 3 }; pump() }
        if (saved) { scroll(hasText(sentLabel())); compose.onNodeWithText(sentLabel()).assertIsDisplayed() }
        else { scroll(hasText(thanksLabel())); thanks().assertIsEnabled() }
        assertEquals(1, posts())
    }
    @Test fun committedThanks503ReconcilesWithoutSecondPost() = uncertain(true, false)
    @Test fun uncommittedThanks503ReconcilesToEnabledAction() = uncertain(false, false)
    @Test fun reconciliationTipFailureStillBlocksSend() = uncertain(true, true)
    @Test fun truncatedCommittedThanksResponseReconcilesWithoutDuplicate() {
        dropThanksBody = true; uncertain(true, false)
    }
    private fun doubleSend(sameFrame: Boolean) {
        heldKind = "thanks"; mount(); val old = clickThanks()
        compose.runOnIdle { old(); if (sameFrame) old() }; waitFor { started.count == 0L }; pump()
        if (!sameFrame) compose.runOnIdle { old() }; pump()
        assertEquals(1, posts()); thanks().assertIsNotEnabled(); release.countDown(); pump()
        compose.onNodeWithText(sentLabel()).assertIsDisplayed()
    }
    @Test fun sameFrameThanksSendRemainsSingleFlight() = doubleSend(true)
    @Test fun laterRetainedThanksSendRemainsSingleFlight() = doubleSend(false)
    private fun late(kind: String, boundary: String) {
        heldKind = kind; mount(); assertEquals(0L, started.count)
        compose.runOnIdle { when (boundary) { "booking" -> booking.value = 43; "session" -> ApiClient.saveToken(tokenB); else -> mounted.value = false } }; pump()
        val before = requests.toList(); release.countDown(); waitFor { replied.count == 0L }; pump()
        assertFalse(requests.drop(before.size).any { it.first.contains("/42/") && it.second == "Bearer $tokenB" })
        if (boundary == "booking") {
            scroll(hasText("Водитель43")); compose.onNodeWithText("Водитель43").assertIsDisplayed()
            if (kind == "tip") { scroll(hasText(thanksLabel())); thanks().assertIsEnabled() }
        } else { compose.onNodeWithTag("rideshareCompletedList").assertDoesNotExist(); if (boundary == "session") assertEquals(1, closes) }
    }
    @Test fun heldReceiptAfterBookingChange() = late("receipt", "booking")
    @Test fun heldReceiptAfterAccountChange() = late("receipt", "session")
    @Test fun heldReceiptAfterUnmount() = late("receipt", "unmount")
    @Test fun heldTipAfterBookingChange() = late("tip", "booking")
    @Test fun heldTipAfterAccountChange() = late("tip", "session")
    @Test fun heldTipAfterUnmount() = late("tip", "unmount")
}
