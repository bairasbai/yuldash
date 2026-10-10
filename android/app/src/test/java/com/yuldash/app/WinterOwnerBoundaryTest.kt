package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual shared Compose dialog/timer, local synthetic HTTP; no SMS or provider calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WinterOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted = mutableStateOf(true)
    private val show = mutableStateOf(true)
    private val asked = mutableStateOf(false)
    private val target = mutableStateOf(91)
    private val parentCurrent = mutableStateOf(true)
    private val active = mutableStateOf(true)
    private val startedAt = mutableStateOf<Long?>(System.currentTimeMillis() - 100000)
    private val threshold = mutableStateOf(1000L)
    private val callbackVersion = mutableStateOf(0)
    private var generation = 0L
    private var route = "order"
    private var hold = false
    private var reject = false
    private var serverState = "check_sent"
    private val records = CopyOnWriteArrayList<List<String?>>()
    private val completed = CopyOnWriteArrayList<String>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val lifecycle = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(tokenA)
        generation = ApiClient.queueSessionGeneration()
        lifecycle.registry.currentState = Lifecycle.State.CREATED
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(q: RecordedRequest): MockResponse {
                    val path = q.requestUrl!!.encodedPath
                    records += listOf(q.method, path, q.getHeader("Authorization"), q.body.readUtf8())
                    if (path == "/instant/availability") return MockResponse().setBody("""{"enabled":true}""")
                    if (path == "/instant/orders/mine") return MockResponse().setBody("""{"items":[$taxiOrder]}""")
                    if (path == "/instant/orders/91") return MockResponse().setBody(taxiOrder)
                    if (hold) { started.countDown(); assertTrue(release.await(35, TimeUnit.SECONDS)); returned.countDown() }
                    return MockResponse().setResponseCode(if (reject) 503 else 200).setHeader("Content-Type", "application/json")
                        .setBody(if (reject) """{"detail":"Контрольный отказ зимней проверки"}""" else """{"state":"$serverState","ok":true}""")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    private suspend fun arm(id: Int, owner: Long): Result<String> {
        try { return when (route) {
            "booking" -> ApiClient.winterCheck(id, expectedGeneration = owner)
            "parcel" -> ApiClient.winterCheckParcel(id, expectedGeneration = owner)
            else -> ApiClient.winterCheckOrder(id, expectedGeneration = owner)
        } } finally { completed += "arm:$id" }
    }
    private suspend fun ack(id: Int, owner: Long): Result<Unit> {
        try { return when (route) {
            "booking" -> ApiClient.winterCheckOk(id, expectedGeneration = owner)
            "parcel" -> ApiClient.winterCheckParcelOk(id, expectedGeneration = owner)
            else -> ApiClient.winterCheckOrderOk(id, expectedGeneration = owner)
        } } finally { completed += "ack:$id" }
    }
    private fun pump(ms: Long = 400) {
        repeat((ms / 100).toInt()) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    private fun mount(watch: Boolean = false, referenceActive: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) { YuldashTheme {
                val id = target.value
                val live = active.value
                fun sampledActive() = live
                val start = startedAt.value
                val version = callbackVersion.value
                val current = { parentCurrent.value && target.value == id && active.value }
                if (watch) WinterArrivalWatcher(key = id, startMs = { start }, active = if (referenceActive) ::sampledActive else { { live } }, asked = asked, show = show,
                    armAfterMs = threshold.value, ownerGeneration = generation, isCurrentTarget = current,
                    onArm = { owner -> arm(id + version, owner) })
                WinterArrivalDialog(show, id, generation, current) { owner -> ack(id, owner) }
            } }
        }; pump()
    }
    private fun resume() { compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }; pump(1000) }
    private val taxiOrder = """{"id":91,"status":"onboard","role":"passenger","driver_name":"Водитель А","from_text":"Пункт А","to_text":"Пункт Б","price_estimate":250,"payment_method":"cash","passenger_can_close":true}"""
    private fun mountActualTaxi() {
        assertTrue("actual timer requires a nonzero wall clock: ${System.currentTimeMillis()}", System.currentTimeMillis() > 0)
        LocationPrefs.lastLat = 54.735; LocationPrefs.lastLng = 55.958; LocationPrefs.sharingEnabled = false
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
            YuldashTheme { InstantOrderScreen(onBack = {}, onLoginRequired = {}, embedded = true, renderNativeMap = false, winterArmAfterMs = 0) }
        } }; await { TaxiNavigationState.currentTrip(generation)?.orderId == 91 }; pump()
    }
    private fun retain(s: String): () -> Boolean {
        await { compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText(s).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun confirm() = retain("Доехал ✓")
    private fun cancel() = retain("Ещё в пути")
    private fun count(ok: Boolean = true) = records.count { it[1]!!.endsWith(if (ok) "/winter-check/ok" else "/winter-check") }
    // finally observes callback return/cancellation. Server 'returned' alone is not client completion.
    private fun heldDone() { release.countDown(); assertTrue(returned.await(8, TimeUnit.SECONDS)); await { completed.isNotEmpty() }; pump(1200) }
    @After fun cleanup() {
        release.countDown(); compose.runOnUiThread { mounted.value = false; lifecycle.registry.currentState = Lifecycle.State.DESTROYED }; pump()
        ApiClient.testTrace = null
        NavSignals.activeTaxiTrip.value = 0; NavSignals.taxiOrderOnScreen.value = false; NavSignals.taxiTripOnScreen.value = false
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    @Test fun currentOrderConfirmationHasExactPayload() {
        mount(); val a = confirm(); compose.runOnIdle { a() }; await { count() == 1 && completed.count { it.startsWith("ack:") } == 1 }; pump()
        assertFalse(show.value); assertEquals(listOf("POST", "/instant/orders/91/winter-check/ok", "Bearer $tokenA", "{}"), records.single())
    }
    @Test fun currentBookingConfirmationUsesBookingEndpoint() { route = "booking"; mount(); val a = confirm(); compose.runOnIdle { a() }; await { count() == 1 && completed.count { it.startsWith("ack:") } == 1 }; assertEquals("/bookings/91/winter-check/ok", records.single()[1]) }
    @Test fun currentParcelConfirmationUsesParcelEndpoint() { route = "parcel"; mount(); val a = confirm(); compose.runOnIdle { a() }; await { count() == 1 && completed.count { it.startsWith("ack:") } == 1 }; assertEquals("/parcels/91/winter-check/ok", records.single()[1]) }
    @Test fun oldConfirmCannotUseNextOwnerBeforeFrame() { mount(); val a = confirm(); compose.runOnIdle { ApiClient.saveToken(tokenB); a() }; pump(1000); assertEquals(0, count()) }
    @Test fun oldConfirmCannotUseGuestBeforeFrame() { mount(); val a = confirm(); compose.runOnIdle { ApiClient.logout(); a() }; pump(1000); assertEquals(0, count()) }
    @Test fun targetReplacementBeforeFrameRejectsOldConfirm() { mount(); val a = confirm(); compose.runOnIdle { target.value = 92; a() }; pump(1000); assertEquals(0, count()) }
    @Test fun parentRetirementBeforeFrameRejectsOldConfirm() { mount(); val a = confirm(); compose.runOnIdle { parentCurrent.value = false; a() }; pump(1000); assertEquals(0, count()) }
    @Test fun disposedDialogCannotSendRetainedConfirm() { mount(); val a = confirm(); compose.runOnIdle { mounted.value = false }; pump(); compose.runOnIdle { a() }; pump(1000); assertEquals(0, count()) }
    @Test fun cancelledOpeningCannotSendRetainedConfirm() { mount(); val a = confirm(); val c = cancel(); compose.runOnIdle { c(); a() }; pump(1000); assertEquals(0, count()) }
    @Test fun previousOpeningCannotConfirmReopenedDialog() { mount(); val a = confirm(); val c = cancel(); compose.runOnIdle { c() }; pump(); compose.runOnIdle { show.value = true }; pump(); compose.runOnIdle { a() }; pump(1000); assertEquals(0, count()); assertTrue(show.value) }
    @Test fun previousOpeningCannotCancelReopenedDialog() { mount(); val c = cancel(); compose.runOnIdle { c() }; pump(); compose.runOnIdle { show.value = true }; pump(); compose.runOnIdle { c() }; pump(); assertTrue(show.value) }
    @Test fun duplicateHeldConfirmationSendsOnce() { hold = true; mount(); val a = confirm(); compose.runOnIdle { a(); a() }; await { started.count == 0L }; pump(); assertEquals(1, count()); assertTrue(show.value); heldDone(); assertFalse(show.value) }
    @Test fun failedConfirmationRemainsVisibleForRetry() { reject = true; mount(); val a = confirm(); compose.runOnIdle { a() }; await { count() == 1 && completed.count { it.startsWith("ack:") } == 1 }; pump(1200); assertTrue(show.value); compose.onNodeWithText("Контрольный отказ зимней проверки").assertExists(); reject = false; compose.runOnIdle { a() }; await { count() == 2 && completed.count { it.startsWith("ack:") } == 2 }; pump(); assertFalse(show.value) }
    @Test fun heldConfirmationCannotCloseReplacementDialog() { hold = true; mount(); val a = confirm(); compose.runOnIdle { a() }; await { started.count == 0L }; compose.runOnIdle { target.value = 92; show.value = true }; pump(); heldDone(); assertTrue(show.value) }
    @Test fun currentDueWatcherArmsOnce() { show.value = false; mount(true); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; pump(); assertTrue(asked.value); assertTrue(show.value); assertEquals("Bearer $tokenA", records.single()[2]); assertEquals("{}", records.single()[3]) }
    @Test fun inactiveWatcherDoesNotArm() { show.value = false; active.value = false; mount(true); resume(); assertEquals(0, count(false)); assertFalse(show.value) }
    @Test fun watcherWithoutStartDoesNotArm() { show.value = false; startedAt.value = null; mount(true); resume(); assertEquals(0, count(false)); assertFalse(asked.value) }
    @Test fun watcherBeforeDeadlineDoesNotArm() { show.value = false; threshold.value = 200000; mount(true); resume(); assertEquals(0, count(false)) }
    @Test fun watcherUsesLatestDeadlineWithoutKeyChange() { show.value = false; threshold.value = 200000; mount(true); compose.runOnIdle { threshold.value = 1000 }; pump(); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; assertEquals("/instant/orders/91/winter-check", records.single()[1]) }
    @Test fun watcherUsesLatestStartWithoutKeyChange() { show.value = false; startedAt.value = null; mount(true); compose.runOnIdle { startedAt.value = System.currentTimeMillis() - 100000 }; pump(); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 } }
    @Test fun watcherUsesLatestActiveWithoutKeyChange() { show.value = false; active.value = false; mount(true); compose.runOnIdle { active.value = true }; pump(); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 } }
    @Test fun watcherUpdatesEqualFunctionReferenceCaptures() { show.value = false; active.value = false; mount(true, referenceActive = true); compose.runOnIdle { active.value = true }; pump(); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; assertTrue(show.value) }
    @Test fun watcherUsesLatestCallbackWithoutKeyChange() { show.value = false; mount(true); compose.runOnIdle { callbackVersion.value = 1 }; pump(); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; assertEquals("/instant/orders/92/winter-check", records.single()[1]) }
    @Test fun watcherOldOwnerCannotArmOnResume() { show.value = false; mount(true); compose.runOnIdle { ApiClient.saveToken(tokenB) }; resume(); assertEquals(0, count(false)); assertFalse(asked.value) }
    @Test fun failedArmRemainsRetryableOnNextResume() { show.value = false; reject = true; mount(true); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; pump(1000); assertFalse(asked.value); assertFalse(show.value); compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED; reject = false }; pump(); resume(); await { count(false) == 2 && completed.count { it.startsWith("arm:") } == 2 }; pump(); assertTrue(show.value) }
    @Test fun serverTooEarlyDoesNotConsumeQuestion() { show.value = false; serverState = "too_early"; mount(true); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; pump(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun serverAlreadyAckedDoesNotAskAgain() { show.value = false; serverState = "ok"; mount(true); resume(); await { count(false) == 1 && completed.count { it.startsWith("arm:") } == 1 }; pump(); assertTrue(asked.value); assertFalse(show.value) }
    @Test fun queuedConfirmationRetiredParentSendsNothing() { mount(); val a = confirm(); compose.runOnIdle { a(); parentCurrent.value = false }; pump(1000); assertEquals(0, count()) }
    @Test fun queuedConfirmationReplacedTargetSendsNothing() { mount(); val a = confirm(); compose.runOnIdle { a(); target.value = 92 }; pump(1000); assertEquals(0, count()) }
    @Test fun heldArmCannotPublishForNextOwner() { show.value = false; hold = true; mount(true); resume(); await { started.count == 0L }; compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(); heldDone(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun heldArmCannotPublishForReplacementTarget() { show.value = false; hold = true; mount(true); resume(); await { started.count == 0L }; compose.runOnIdle { active.value = false; target.value = 92 }; pump(); heldDone(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun heldArmCannotPublishForInactiveTrip() { show.value = false; hold = true; mount(true); resume(); await { started.count == 0L }; compose.runOnIdle { active.value = false }; pump(); heldDone(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun heldArmCannotPublishForRetiredParent() { show.value = false; hold = true; mount(true); resume(); await { started.count == 0L }; compose.runOnIdle { parentCurrent.value = false }; pump(); heldDone(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun serverClosedConsumesWithoutQuestion() { show.value = false; serverState = "closed"; mount(true); resume(); await { completed.contains("arm:91") }; pump(); assertTrue(asked.value); assertFalse(show.value) }
    private fun currentServerQuestion(state: String) { show.value = false; serverState = state; mount(true); resume(); await { completed.contains("arm:91") }; pump(); assertTrue(asked.value); assertTrue(show.value) }
    @Test fun serverWaitingShowsQuestion() = currentServerQuestion("waiting")
    @Test fun serverNoShareStillShowsQuestion() = currentServerQuestion("no_share")
    @Test fun serverEscalatedStillShowsQuestion() = currentServerQuestion("escalated")
    @Test fun unknownServerStateRemainsRetryable() { show.value = false; serverState = "unknown_future_state"; mount(true); resume(); await { completed.contains("arm:91") }; pump(); assertFalse(asked.value); assertFalse(show.value) }
    @Test fun sixStaleApiEntriesRejectBeforeHttp() {
        show.value = false; mount(); compose.runOnIdle { ApiClient.saveToken(tokenB) }
        val stale = runBlocking { listOf(ApiClient.winterCheck(91, generation), ApiClient.winterCheckOk(91, generation),
            ApiClient.winterCheckOrder(91, generation), ApiClient.winterCheckOrderOk(91, generation),
            ApiClient.winterCheckParcel(91, generation), ApiClient.winterCheckParcelOk(91, generation)) }
        assertTrue(stale.all { it.isFailure }); assertTrue(records.isEmpty())
        assertTrue(runBlocking { ApiClient.winterCheckOrderOk(91, ApiClient.queueSessionGeneration()) }.isSuccess)
        assertEquals("Bearer $tokenB", records.single()[2])
    }
    @Test fun watcherRefreshesStartWhileAlreadyResumed() {
        show.value = false; startedAt.value = null; mount(true); resume(); assertEquals(0, count(false))
        compose.runOnIdle { startedAt.value = System.currentTimeMillis() - 100000 }; pump()
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idleFor(61, TimeUnit.SECONDS) }
        pump(); await { completed.contains("arm:91") }; assertEquals(1, count(false)); assertTrue(show.value)
    }
    @Test fun inactiveBeforeFrameRejectsConfirm() { mount(); val a = confirm(); compose.runOnIdle { active.value = false; a() }; pump(1000); assertEquals(0, count()) }
    @Test fun oldCancelCannotMutateNextOwnerState() { mount(); val c = cancel(); compose.runOnIdle { ApiClient.saveToken(tokenB); c() }; pump(); assertTrue(show.value) }
    @Test fun invalidTargetDoesNotOpenDialogOrArm() { show.value = false; target.value = 0; mount(true); resume(); assertTrue(records.isEmpty()); compose.runOnIdle { show.value = true }; pump(); compose.onAllNodesWithText("Доехал ✓").assertCountEquals(0) }
    @Test fun currentSystemBackDismissesWithoutHttp() { mount(); compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }; pump(); assertFalse(show.value); assertTrue(records.isEmpty()) }
    @Test fun actualTaxiFinishedPublicationRejectsRetainedConfirm() {
        mountActualTaxi(); resume(); val a = confirm(); val trip = TaxiNavigationState.currentTrip(generation)!!
        compose.runOnIdle { assertTrue(TaxiNavigationState.finishTrip(trip)); a() }; pump(1200); assertEquals(0, count())
    }
    @Test fun actualTaxiFinishedPublicationDoesNotArmOnResume() {
        mountActualTaxi(); val trip = TaxiNavigationState.currentTrip(generation)!!
        compose.runOnIdle { assertTrue(TaxiNavigationState.finishTrip(trip)) }; resume(); assertEquals(0, count(false)); compose.onAllNodesWithText("Доехал ✓").assertCountEquals(0)
    }
}
