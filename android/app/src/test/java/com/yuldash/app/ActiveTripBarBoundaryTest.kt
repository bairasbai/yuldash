package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual bar/API/lifecycle on local HTTP; delayed release is bounded, not a client join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActiveTripBarBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val refreshed = "header.eyJzdWIiOiIxMSJ9.newsignature"
    private val mounted = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<Pair<String, String?>>()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val dispatched = CountDownLatch(1)
    private val polls = AtomicInteger()
    @Volatile private var holdFirst = false
    @Volatile private var firstTerminal = false
    @Volatile private var wrongId = false
    @Volatile private var failNext = false
    @Volatile private var refreshNext = false
    private var opened = 0

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val bearer = request.getHeader("Authorization")
                    records += path to bearer
                    if (path == "/auth/verify") return json("""{"access_token":"$tokenA","refresh_token":"bar-refresh-1","token_type":"bearer"}""")
                    if (path == "/auth/refresh") return json("""{"access_token":"$refreshed","refresh_token":"bar-refresh-2","token_type":"bearer"}""")
                    if (!path.startsWith("/instant/orders/")) return json("{}")
                    val n = polls.incrementAndGet()
                    val terminal = n == 1 && firstTerminal
                    val id = if (wrongId) 92 else path.substringAfterLast('/').toInt()
                    if (holdFirst && n == 1) {
                        started.countDown(); assertTrue(release.await(40, TimeUnit.SECONDS))
                        dispatched.countDown()
                    }
                    if (failNext && n > 1) { failNext = false; return json("{}", 503) }
                    if (refreshNext && bearer == "Bearer $tokenA" && n > 1) { refreshNext = false; return json("{}", 401) }
                    val name = when (bearer) {
                        "Bearer $tokenB" -> "Водитель Б"
                        "Bearer $refreshed" -> "Обновлённый водитель А"
                        else -> "Водитель А"
                    }
                    return json("""{"id":$id,"status":"${if (terminal) "done" else "accepted"}","driver_name":"$name","role":"passenger"}""")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(tokenA)
        NavSignals.activeTaxiTrip.value = 91; NavSignals.taxiTripOnScreen.value = false
    }
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun pump(ms: Long = 1000) {
        repeat((ms / 100).toInt()) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) { YuldashTheme { ActiveTripBar { opened++ } } } }
        pump()
    }
    private fun row(name: String) = compose.onNodeWithText("$name едет")
    private fun loaded() { mount(); await { compose.onAllNodesWithText("Водитель А едет").fetchSemanticsNodes().isNotEmpty() } }
    private fun retainedOpen() = compose.onNodeWithText("Открыть").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun finishHeld() { release.countDown(); assertTrue(dispatched.await(8, TimeUnit.SECONDS)); pump(1500) }

    @Test fun accountChangeDropsCachedRowAndImmediatelyPollsSameTripForNewSession() {
        loaded(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump()
        row("Водитель А").assertDoesNotExist(); row("Водитель Б").assertExists()
        assertTrue(records.any { it.first == "/instant/orders/91" && it.second == "Bearer $tokenB" })
        assertEquals(91, NavSignals.activeTaxiTrip.value)
    }
    @Test fun logoutDropsCachedRowAndCannotPollAsGuest() {
        loaded(); compose.runOnIdle { ApiClient.logout() }; pump(17000)
        row("Водитель А").assertDoesNotExist()
        assertFalse(records.any { it.first == "/instant/orders/91" && it.second == null })
    }
    @Test fun guestWithLeftoverTripIdCannotPoll() {
        ApiClient.logout(); mount(); pump(17000)
        assertFalse(records.any { it.first == "/instant/orders/91" })
        compose.onNodeWithText("Открыть").assertDoesNotExist()
    }
    @Test fun heldOldTerminalCannotRemoveSameIdTripInNewSession() {
        holdFirst = true; firstTerminal = true; mount(); assertTrue(started.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(); finishHeld()
        assertEquals(91, NavSignals.activeTaxiTrip.value); row("Водитель Б").assertExists()
        row("Водитель А").assertDoesNotExist()
        assertTrue(records.any { it.first == "/instant/orders/91" && it.second == "Bearer $tokenB" })
    }
    @Test fun heldOldTripCannotRemoveReplacementTrip() {
        holdFirst = true; firstTerminal = true; mount(); assertTrue(started.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { NavSignals.activeTaxiTrip.value = 92 }; pump(); finishHeld()
        assertEquals(92, NavSignals.activeTaxiTrip.value); row("Водитель А").assertExists()
        assertTrue(records.any { it.first == "/instant/orders/92" })
    }
    @Test fun currentTerminalClearsCurrentTrip() {
        firstTerminal = true; mount(); await { NavSignals.activeTaxiTrip.value == 0 }
        compose.onNodeWithText("Открыть").assertDoesNotExist(); assertEquals(1, polls.get())
    }
    @Test fun responseForAnotherIdCannotClearOrRenderCurrentTrip() {
        wrongId = true; firstTerminal = true; mount(); pump()
        assertTrue(records.any { it.first == "/instant/orders/91" })
        assertEquals(91, NavSignals.activeTaxiTrip.value); compose.onNodeWithText("Открыть").assertDoesNotExist()
    }
    @Test fun nonterminalResponseForAnotherIdCannotRenderCurrentTrip() {
        wrongId = true; mount(); pump()
        assertTrue(records.any { it.first == "/instant/orders/91" })
        assertEquals(91, NavSignals.activeTaxiTrip.value); compose.onNodeWithText("Открыть").assertDoesNotExist()
    }
    @Test fun staleExpectedGenerationCannotQueryNewAccount() {
        NavSignals.activeTaxiTrip.value = 0; mount()
        val generation = ApiClient.queueSessionGeneration()
        compose.runOnIdle { ApiClient.saveToken(tokenB) }
        val result = runBlocking { ApiClient.getInstantOrder(91, expectedGeneration = generation) }
        assertTrue(result.isFailure)
        assertFalse(records.any { it.first == "/instant/orders/91" })
    }
    @Test fun temporaryHttpFailureRetainsKnownRowAndTrip() {
        loaded(); failNext = true; pump(16000); await { polls.get() >= 2 }
        row("Водитель А").assertExists(); assertEquals(91, NavSignals.activeTaxiTrip.value)
        pump(16000); await { polls.get() >= 3 }; row("Водитель А").assertExists()
    }
    @Test fun lifecycleStopsPollingAndResumeImmediatelyRefreshes() {
        loaded(); compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; pump()
        val before = polls.get(); pump(31000); assertEquals(before, polls.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; pump()
        assertTrue(polls.get() > before); assertEquals(91, NavSignals.activeTaxiTrip.value)
    }
    @Test fun retainedActionCannotOpenUnderNextSessionInSameFrame() {
        loaded(); val open = retainedOpen()
        compose.runOnIdle { ApiClient.saveToken(tokenB); open(); assertEquals(0, opened) }
    }
    @Test fun retainedActionCannotOpenReplacementTripInSameFrame() {
        loaded(); val open = retainedOpen()
        compose.runOnIdle { NavSignals.activeTaxiTrip.value = 92; open(); assertEquals(0, opened) }
    }
    @Test fun currentActionOpensCurrentTrip() {
        loaded(); compose.onNodeWithText("Открыть").performClick(); assertEquals(1, opened)
    }
    @Test fun actualRefreshKeepsGenerationAndPollsWithRotatedToken() {
        runBlocking { assertTrue(ApiClient.verifyCode("70000000000", "0000", "Local bar user").isSuccess) }
        val generation = ApiClient.queueSessionGeneration(); loaded(); refreshNext = true; pump(16000)
        await { records.any { it.first == "/instant/orders/91" && it.second == "Bearer $refreshed" } }; pump()
        assertEquals(generation, ApiClient.queueSessionGeneration()); assertEquals(generation, ApiClient.sessionChanges.value)
        row("Обновлённый водитель А").assertExists()
        assertEquals(1, records.count { it.first == "/auth/refresh" })
        compose.onNodeWithText("Открыть").performClick(); assertEquals(1, opened)
    }
    @After fun cleanup() {
        release.countDown(); compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }; pump(300)
        NavSignals.activeTaxiTrip.value = 0; NavSignals.taxiTripOnScreen.value = false
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}
