package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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

/** Actual passenger screen, bar and API on synthetic local HTTP; held replies use bounded observation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantOrderOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val first = mutableStateOf(true)
    private val second = mutableStateOf(false)
    private val mounted = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()
    private val mines = AtomicInteger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    @Volatile private var holdMine = 0
    @Volatile private var wrongPollId = false
    private var opened = 0

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken(tokenA)
        NavSignals.activeTaxiTrip.value = 0
        NavSignals.taxiOrderOnScreen.value = false; NavSignals.taxiTripOnScreen.value = false
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath; val bearer = request.getHeader("Authorization")
                    requests += path to bearer
                    val name = if (bearer == "Bearer $tokenB") "Водитель Б" else "Водитель А"
                    val id = if (bearer == "Bearer $tokenB") 92 else 91
                    if (path == "/instant/orders/mine") {
                        val number = mines.incrementAndGet()
                        if (number == holdMine) {
                            started.countDown(); assertTrue(release.await(40, TimeUnit.SECONDS)); returned.countDown()
                        }
                        return json("""{"items":[${order(id, name)}]}""")
                    }
                    if (path.startsWith("/instant/orders/")) {
                        val queried = path.substringAfterLast('/').toInt()
                        return json(order(if (wrongPollId) 93 else queried, name, if (wrongPollId) "done" else "accepted"))
                    }
                    return when (path) {
                        "/instant/availability" -> json("""{"enabled":true}""")
                        else -> json("""{"items":[],"drivers":[]}""")
                    }
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
    }
    private fun order(id: Int, name: String, status: String = "accepted") =
        """{"id":$id,"status":"$status","role":"passenger","driver_name":"$name","driver_car":"Машина","driver_plate":"ТЕСТ","price_estimate":250}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun pump(ms: Long = 1000) {
        repeat((ms / 100).toInt()) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(10)
        }; compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                YuldashTheme {
                    Column {
                        if (first.value) Box(Modifier.weight(1f)) { screen() }
                        if (second.value) Box(Modifier.weight(1f)) { screen() }
                        ActiveTripBar { opened++ }
                    }
                }
            }
        }; pump()
    }
    @androidx.compose.runtime.Composable private fun screen() = InstantOrderScreen(
        onBack = {}, onLoginRequired = {}, embedded = true, renderNativeMap = false,
    )
    private fun loaded() { mount(); await { NavSignals.activeTaxiTrip.value == 91 && NavSignals.taxiTripOnScreen.value } }
    private fun minimize() { compose.runOnIdle { first.value = false }; pump() }
    private fun finishHeld() { release.countDown(); assertTrue(returned.await(8, TimeUnit.SECONDS)); pump(1500) }

    @Test fun oldAccountTripCannotPollUnderNewAccount() {
        loaded(); minimize(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(16000)
        assertEquals(0, NavSignals.activeTaxiTrip.value)
        assertFalse(requests.any { it.first == "/instant/orders/91" && it.second == "Bearer $tokenB" })
        compose.onNodeWithText("Открыть").assertDoesNotExist()
    }
    @Test fun accountChangeImmediatelyHidesTripFromPaymentReader() {
        loaded(); compose.runOnIdle { ApiClient.saveToken(tokenB); assertEquals(0, NavSignals.activeTaxiTrip.value) }
    }
    @Test fun logoutImmediatelyHidesTripAndScreenFlags() {
        loaded(); compose.runOnIdle {
            ApiClient.logout(); assertEquals(0, NavSignals.activeTaxiTrip.value)
            assertFalse(NavSignals.taxiOrderOnScreen.value); assertFalse(NavSignals.taxiTripOnScreen.value)
        }
    }
    @Test fun checkingNewScreenPreservesMinimizedTrip() {
        loaded(); minimize(); holdMine = 2; compose.runOnIdle { first.value = true }; pump()
        assertTrue(started.await(8, TimeUnit.SECONDS)); assertEquals(91, NavSignals.activeTaxiTrip.value)
        finishHeld(); assertEquals(91, NavSignals.activeTaxiTrip.value)
    }
    @Test fun newAccountRestoresOwnOrderWithoutOldDriver() {
        loaded(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump()
        await { NavSignals.activeTaxiTrip.value == 92 }
        assertTrue(requests.any { it.first == "/instant/orders/mine" && it.second == "Bearer $tokenB" })
        compose.onAllNodesWithText("Водитель А едет").assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("Водитель Б едет").fetchSemanticsNodes().isNotEmpty())
    }
    @Test fun logoutStopsOrderPollingAsGuest() {
        loaded(); compose.runOnIdle { ApiClient.logout() }; pump(16000)
        assertFalse(requests.any { it.first.startsWith("/instant/orders/") && it.second == null })
        assertEquals(0, NavSignals.activeTaxiTrip.value)
    }
    @Test fun oldHeldRestoreCannotPublishIntoNewAccount() {
        holdMine = 1; mount(); assertTrue(started.await(8, TimeUnit.SECONDS))
        compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump()
        await { NavSignals.activeTaxiTrip.value == 92 }; finishHeld()
        assertEquals(92, NavSignals.activeTaxiTrip.value)
        assertTrue(NavSignals.taxiOrderOnScreen.value); assertTrue(NavSignals.taxiTripOnScreen.value)
    }
    @Test fun disposingOldSameIdScreenCannotUnhideCurrentScreen() {
        loaded(); compose.runOnIdle { second.value = true }; pump()
        await { mines.get() >= 2 }; pump()
        compose.runOnIdle { first.value = false }; pump()
        assertEquals(91, NavSignals.activeTaxiTrip.value)
        assertTrue(NavSignals.taxiOrderOnScreen.value); assertTrue(NavSignals.taxiTripOnScreen.value)
    }
    @Test fun terminalDtoForAnotherIdCannotClearCurrentTrip() {
        loaded(); wrongPollId = true; pump(4000)
        assertEquals(91, NavSignals.activeTaxiTrip.value)
        assertTrue(NavSignals.taxiTripOnScreen.value)
    }
    @Test fun retainedBarActionCannotOpenSameIdReplacementInSameFrame() {
        loaded(); minimize()
        await { compose.onAllNodesWithText("Открыть").fetchSemanticsNodes().isNotEmpty() }
        val open = compose.onNodeWithText("Открыть").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { NavSignals.activeTaxiTrip.value = 0; NavSignals.activeTaxiTrip.value = 91; open(); assertEquals(0, opened) }
    }
    @After fun cleanup() {
        release.countDown(); compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }; pump(300)
        NavSignals.activeTaxiTrip.value = 0; NavSignals.taxiOrderOnScreen.value = false; NavSignals.taxiTripOnScreen.value = false
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}
