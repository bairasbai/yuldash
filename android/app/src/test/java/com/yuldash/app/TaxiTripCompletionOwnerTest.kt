package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real bar terminal reply while the still-mounted passenger screen has an old accepted DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiTripCompletionOwnerTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val showBar = mutableStateOf(false)
    private val payment = mutableStateOf(PayMethods.CASH)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val terminalDispatched = CountDownLatch(1)
    private val polls = AtomicInteger()
    private lateinit var server: MockWebServer
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        NavSignals.activeTaxiTrip.value = 0
        NavSignals.taxiOrderOnScreen.value = false; NavSignals.taxiTripOnScreen.value = false
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/instant/availability" -> json("""{"enabled":true}""")
                    "/instant/orders/mine" -> json("""{"items":[${order("accepted")}]}""")
                    "/instant/orders/91" -> {
                        if (polls.incrementAndGet() == 1) {
                            started.countDown(); assertTrue(release.await(40, TimeUnit.SECONDS))
                            json(order("accepted"))
                        } else {
                            terminalDispatched.countDown(); json(order("done"))
                        }
                    }
                    else -> json("{}")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 60000
    }
    private fun order(status: String) = """{"id":91,"status":"$status","role":"passenger","driver_name":"Водитель А"}"""
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun pump() {
        repeat(10) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); Thread.sleep(10)
        }; compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    @Test fun oldAcceptedScreenCannotRepublishAfterBarObservedCompletion() {
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                YuldashTheme {
                    Column {
                        Box(Modifier.weight(1f)) {
                            InstantOrderScreen({}, {}, embedded = true, payMethod = payment.value, renderNativeMap = false)
                        }
                        if (showBar.value) ActiveTripBar {}
                    }
                }
            }
        }
        await { NavSignals.activeTaxiTrip.value == 91 && started.count == 0L }
        compose.runOnIdle { showBar.value = true }
        await { terminalDispatched.count == 0L && NavSignals.activeTaxiTrip.value == 0 }
        assertEquals(2, polls.get()); assertEquals(1L, release.count)
        compose.runOnIdle { payment.value = PayMethods.SBP }; pump()
        assertEquals("Old accepted DTO must not restart the bar poll", 2, polls.get())
        assertEquals(0, NavSignals.activeTaxiTrip.value)
        assertFalse(NavSignals.taxiTripOnScreen.value)
        assertFalse(NavSignals.taxiOrderOnScreen.value)
    }
    @After fun cleanup() {
        release.countDown()
        compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }; pump()
        NavSignals.activeTaxiTrip.value = 0
        NavSignals.taxiOrderOnScreen.value = false; NavSignals.taxiTripOnScreen.value = false
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}
