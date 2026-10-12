package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual dispatch/default Activity VM and YuldashApp; held release is bounded, not client join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingNotificationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val started = CountDownLatch(1); private val release = CountDownLatch(1); private val dispatched = CountDownLatch(1)
    private val role43 = AtomicInteger(); private val records = CopyOnWriteArrayList<Pair<String, String?>>()
    private var held = false; private var firstStatus = 200
    private val token11 = "header.eyJzdWIiOiIxMSJ9.signature"; private val token12 = "header.eyJzdWIiOiIxMiJ9.signature"
    private lateinit var vm: YuldashViewModel
    private val actor by lazy { Robolectric.buildActivity(MainActivity::class.java).create() }
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath; records += path to request.getHeader("Authorization")
                    if (path == "/bookings/43/role") {
                        val ordinal = role43.incrementAndGet()
                        if (held && ordinal == 1) { started.countDown(); assertTrue(release.await(40, TimeUnit.SECONDS)); dispatched.countDown() }
                        return json("""{"role":"passenger","status":"done","driver_phase":""}""", if (ordinal == 1) firstStatus else 200)
                    }
                    val bid = path.split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    return json(when {
                        path == "/bookings/mine" -> """{"items":[{"id":42,"ride_id":9,"status":"done","seats":1,"from_city":"Уфа","to_city":"Бирск","price":400}]}"""
                        path.endsWith("/role") -> """{"role":"passenger","status":"done","driver_phase":""}"""
                        path.endsWith("/receipt") -> """{"booking_id":$bid,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"my_stars":0}"""
                        path.endsWith("/tip") -> """{"already_thanked":false,"driver_name":"Водитель"}"""
                        else -> """{"items":[],"code":"","role":"passenger"}"""
                    })
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000; ApiClient.saveToken(token11)
        context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE).edit().putBoolean("onboarding_completed", true).commit()
        DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingRideId.value = null
        vm = ViewModelProvider(actor.get())[YuldashViewModel::class.java]
        vm.screen.value = Screen.ActiveTrip; vm.activeBookingId.value = 42; vm.persistNav()
    }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun pump(ms: Long = 600) {
        repeat((ms / 100).toInt()) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) { compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } }
        await { compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty() }; pump()
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        compose.onNodeWithText("Квитанция").performClick()
        await { records.any { it.first == "/trips/42/receipt" } }; pump()
    }
    private fun deliver(id: Int) = compose.runOnIdle {
        val intent = Intent(context, MainActivity::class.java).putExtra("type", "booking_done").putExtra("id", id.toString()).putExtra("recipient_user_id", "11")
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(actor.get(), intent)
    }
    private fun startHeld() { held = true; mount(); deliver(43); await { started.count == 0L } }
    private fun finishOld() { release.countDown(); assertTrue(dispatched.await(8, TimeUnit.SECONDS)); pump(1500) }
    private fun completed(id: Int) = await { vm.screen.value == Screen.ActiveTrip && vm.activeBookingId.value == id && DeepLink.pendingCompletedBookingId.value == null }
    @After fun cleanup() {
        release.countDown(); compose.runOnIdle { mounted.value = false }; pump(300)
        actor.destroy(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingRideId.value = null
        server.shutdown()
    }
    @Test fun newerBookingWinsAgainstHeldOldSuccess() {
        startHeld(); deliver(44); completed(44); finishOld()
        assertEquals(44, vm.activeBookingId.value); assertNull(vm.pendingCompletedNavigation.value)
    }
    @Test fun repeatedNewSameBookingStartsNewRevisionAndRejectsOld() {
        startHeld(); val old = vm.pendingCompletedNavigation.value!!.revision
        deliver(43); assertTrue(vm.pendingCompletedNavigation.value!!.revision > old)
        completed(43); finishOld(); assertTrue(role43.get() >= 2); assertNull(vm.pendingCompletedNavigation.value)
    }
    @Test fun accountChangeClearsHeldPendingAndRejectsOldResponse() {
        startHeld(); compose.runOnIdle { ApiClient.saveToken(token12) }
        await { vm.pendingCompletedNavigation.value == null && DeepLink.pendingCompletedBookingId.value == null }
        finishOld(); assertEquals(42, vm.activeBookingId.value)
        assertFalse(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token12" })
    }
    @Test fun sameOwnerNewTokenRestartsHeldRequestAndCanComplete() {
        startHeld(); compose.runOnIdle { ApiClient.saveToken(token11 + "new") }
        completed(43); finishOld()
        assertTrue(records.any { it.first == "/bookings/43/role" && it.second == "Bearer ${token11}new" })
    }
    @Test fun failureKeepsManualRetryAndDoesNotAutoRepeat() {
        firstStatus = 503; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkFailure").fetchSemanticsNodes().isNotEmpty() }
        pump(1000); assertEquals(1, role43.get()); assertNull(vm.pendingCompletedNavigation.value)
        compose.onNodeWithTag("bookingLinkRetry").performClick(); completed(43)
        // ActiveTrip также читает role при открытии; это не ещё одно нажатие Retry.
        assertTrue(role43.get() >= 2)
    }
    @Test fun unavailableDestinationConsumesPendingAndOffersClose() {
        firstStatus = 404; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkClose").fetchSemanticsNodes().isNotEmpty() }
        assertNull(vm.pendingCompletedNavigation.value); assertNull(DeepLink.pendingCompletedBookingId.value)
        compose.onNodeWithTag("bookingLinkClose").performClick(); pump(); compose.onNodeWithTag("bookingLinkFailure").assertDoesNotExist()
    }
    @Test fun failedOldOwnerRetryIsRemovedOnAccountChange() {
        firstStatus = 503; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { ApiClient.saveToken(token12) }; pump()
        compose.onNodeWithTag("bookingLinkFailure").assertDoesNotExist(); assertEquals(1, role43.get())
    }
    private fun failedRetry(): () -> Boolean {
        firstStatus = 503; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag("bookingLinkRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    @Test fun retainedRetryCannotRebindOldBookingToNewAccountInSameFrame() {
        val retry = failedRetry()
        compose.runOnIdle { ApiClient.saveToken(token12); retry(); assertNull(vm.pendingCompletedNavigation.value) }
        pump(); assertEquals(42, vm.activeBookingId.value); assertEquals(1, role43.get())
        assertFalse(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token12" })
    }
    @Test fun retainedRetryAllowsSameOwnerTokenReplacementInSameFrame() {
        val retry = failedRetry()
        compose.runOnIdle { ApiClient.saveToken(token11 + "new"); retry() }; completed(43)
        assertTrue(role43.get() >= 2)
        assertTrue(records.any { it.first == "/bookings/43/role" && it.second == "Bearer ${token11}new" })
    }
    @Test fun retainedRetryCannotReplaceAlreadyOpenedNewerBooking() {
        val retry = failedRetry(); deliver(44); completed(44); pump()
        compose.runOnIdle { retry(); assertNull(vm.pendingCompletedNavigation.value) }
        pump(); assertEquals(44, vm.activeBookingId.value); assertEquals(1, role43.get())
    }
    @Test fun successfulConsumeHasRouteAndNoPendingInObservedActivityBundle() {
        mount(); deliver(43); completed(43)
        val bundle = Bundle(); compose.runOnIdle { actor.saveInstanceState(bundle) }
        compose.runOnIdle { mounted.value = false }; pump(); DeepLink.pendingCompletedBookingId.value = null
        val next = Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {
            val fresh = ViewModelProvider(next.get())[YuldashViewModel::class.java]
            assertNotSame(vm, fresh); assertEquals(Screen.ActiveTrip, fresh.screen.value); assertEquals(43, fresh.activeBookingId.value)
            assertNull(fresh.pendingCompletedNavigation.value); assertNull(DeepLink.pendingCompletedBookingId.value)
        } finally { next.destroy() }
    }
}
