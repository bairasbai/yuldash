package com.yuldash.app

import androidx.compose.runtime.snapshots.Snapshot

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
class BookingNotificationFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private val started = CountDownLatch(1); private val release = CountDownLatch(1); private val dispatched = CountDownLatch(1)
    private val role43 = AtomicInteger(); private val records = CopyOnWriteArrayList<Pair<String, String?>>()
    private val feedRequests = AtomicInteger(); private var holdFirstFeed = false
    private val feedStarted = CountDownLatch(1); private val feedRelease = CountDownLatch(1); private val feedDispatched = CountDownLatch(1)
    private var notificationEntry = false
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
                    if (path == "/notifications" && notificationEntry && holdFirstFeed) {
                        if (feedRequests.incrementAndGet() == 1) {
                            feedStarted.countDown(); assertTrue(feedRelease.await(40, TimeUnit.SECONDS)); feedDispatched.countDown()
                        } else return json("""{"items":[],"unread":0}""")
                    }
                    if (path == "/bookings/43/role") {
                        val ordinal = role43.incrementAndGet()
                        if (held && ordinal == 1) { started.countDown(); assertTrue(release.await(40, TimeUnit.SECONDS)); dispatched.countDown() }
                        return json("""{"role":"passenger","status":"confirmed","driver_phase":""}""", if (ordinal == 1) firstStatus else 200)
                    }
                    val bid = path.split('/').getOrNull(2)?.toIntOrNull() ?: 42
                    return json(when {
                        path == "/notifications" && notificationEntry -> """{"items":[{"id":901,"type":"booking","title_ru":"Проверяем бронь 43","title_ba":"Бронь 43","body_ru":"Уведомление","body_ba":"Хәбәр","ref_kind":"booking","ref_id":43,"read":false,"created_at":"2026-10-07T10:00:00Z"}],"unread":1}"""
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
        repeat((ms / 100).toInt()) { Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(10) }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(12000) { Snapshot.sendApplyNotifications();compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } }
        await { compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty() }; pump()
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        compose.onNodeWithText("Квитанция").performClick()
        await { records.any { it.first == "/trips/42/receipt" } }; pump()
    }
    private fun deliver(id: Int, type: String = "chat") = compose.runOnIdle {
        val intent = Intent(context, MainActivity::class.java).putExtra("type", type).putExtra("id", id.toString()).putExtra("recipient_user_id", "11")
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }.invoke(actor.get(), intent)
    }
    private fun startHeld() { held = true; mount(); deliver(43); await { started.count == 0L } }
    private fun finishOld() { release.countDown(); assertTrue(dispatched.await(8, TimeUnit.SECONDS)); pump(1500) }
    private fun reached(id: Int, completed: Boolean = false) = await { vm.screen.value == (if (completed) Screen.ActiveTrip else Screen.Booking) && vm.activeBookingId.value == id && DeepLink.pendingCompletedBookingId.value == null && DeepLink.pendingBookingChatId.value == null }
    @After fun cleanup() {
        release.countDown(); feedRelease.countDown(); compose.runOnIdle { mounted.value = false }; pump(300)
        actor.destroy(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        DeepLink.pendingCompletedBookingId.value = null; DeepLink.pendingBookingChatId.value = null; DeepLink.pendingRideId.value = null
        server.shutdown()
    }
    @Test fun newerCompletedWinsAgainstHeldChatSuccess() {
        startHeld(); deliver(44, "booking_done"); reached(44, true); finishOld()
        assertEquals(44, vm.activeBookingId.value); assertNull(DeepLink.pendingBookingChatId.value)
    }
    @Test fun newerChatWinsAgainstHeldCompletedSuccess() {
        held = true; mount(); deliver(43, "booking_done"); await { started.count == 0L }
        deliver(44); reached(44); finishOld(); assertEquals(Screen.Booking, vm.screen.value); assertEquals(44, vm.activeBookingId.value)
    }
    @Test fun newerBookingWinsAgainstHeldChatSuccess() {
        startHeld(); deliver(44, "booking"); reached(44); finishOld(); assertEquals(44, vm.activeBookingId.value)
    }
    @Test fun repeatedSameChatSupersedesHeldRequest() {
        startHeld(); deliver(43); reached(43); finishOld(); assertTrue(role43.get() >= 2)
    }
    @Test fun accountChangeRejectsHeldChatWithoutQueryingNewOwner() {
        startHeld(); compose.runOnIdle { ApiClient.saveToken(token12) }; pump()
        finishOld(); assertEquals(42, vm.activeBookingId.value); assertNull(DeepLink.pendingBookingChatId.value)
        assertFalse(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token12" })
    }
    @Test fun sameOwnerTokenReplacementCanFinishChat() {
        startHeld(); compose.runOnIdle { ApiClient.saveToken(token11 + "new") }; reached(43); finishOld()
        assertTrue(records.any { it.first == "/bookings/43/role" && it.second == "Bearer ${token11}new" })
    }
    @Test fun currentChatSurvivesLostGlobalSnapshotNotification() {
        mount()
        withLostGlobalSnapshotNotification {
            deliver(43); reached(43)
            assertTrue(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token11" })
        }
    }
    @Test fun bookingFailureWaitsForManualRetry() {
        firstStatus = 503; mount(); deliver(43, "booking")
        await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        pump(1000); assertEquals(1, role43.get()); assertNull(DeepLink.pendingBookingChatId.value)
        compose.onNodeWithTag("bookingLinkRetry").performClick(); reached(43)
    }
    @Test fun unavailableChatOffersClose() {
        firstStatus = 404; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkClose").fetchSemanticsNodes().isNotEmpty() }
        assertNull(DeepLink.pendingBookingChatId.value)
        compose.onNodeWithTag("bookingLinkClose").performClick(); pump(); compose.onNodeWithTag("bookingLinkFailure").assertDoesNotExist()
    }
    private fun failedRetry(): () -> Boolean {
        firstStatus = 503; mount(); deliver(43)
        await { compose.onAllNodesWithTag("bookingLinkRetry").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag("bookingLinkRetry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    @Test fun accountChangeRemovesOldChatFailure() {
        failedRetry(); compose.runOnIdle { ApiClient.saveToken(token12) }; pump()
        compose.onNodeWithTag("bookingLinkFailure").assertDoesNotExist(); assertEquals(1, role43.get())
    }
    @Test fun retainedChatRetryCannotRebindAcrossAccountsInSameFrame() {
        val retry = failedRetry()
        compose.runOnIdle { ApiClient.saveToken(token12); retry(); assertNull(DeepLink.pendingBookingChatId.value) }
        pump(); assertEquals(42, vm.activeBookingId.value); assertEquals(1, role43.get())
        assertFalse(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token12" })
    }
    @Test fun retainedChatRetryAllowsSameOwnerTokenReplacement() {
        val retry = failedRetry(); compose.runOnIdle { ApiClient.saveToken(token11 + "new"); retry() }; reached(43)
        assertTrue(records.any { it.first == "/bookings/43/role" && it.second == "Bearer ${token11}new" })
    }
    @Test fun retainedChatRetryCannotReplaceNewerCompleted() {
        val retry = failedRetry(); deliver(44, "booking_done"); reached(44, true); pump()
        compose.runOnIdle { retry(); assertNull(DeepLink.pendingBookingChatId.value) }
        pump(); assertEquals(44, vm.activeBookingId.value); assertEquals(1, role43.get())
    }
    @Test fun successfulBookingConsumePersistsActualActivityRoute() {
        mount(); deliver(43, "booking"); reached(43)
        val bundle = Bundle(); compose.runOnIdle { actor.saveInstanceState(bundle) }
        compose.runOnIdle { mounted.value = false }; pump(); DeepLink.pendingBookingChatId.value = null
        val next = Robolectric.buildActivity(MainActivity::class.java).create(bundle)
        try {
            val fresh = ViewModelProvider(next.get())[YuldashViewModel::class.java]
            assertNotSame(vm, fresh); assertEquals(Screen.Booking, fresh.screen.value); assertEquals(43, fresh.activeBookingId.value)
            assertNull(DeepLink.pendingBookingChatId.value); assertNull(DeepLink.pendingCompletedBookingId.value)
        } finally { next.destroy() }
    }
    private fun openFeedBooking() {
        notificationEntry = true; mount(); compose.runOnIdle { vm.screen.value = Screen.Notifications }
        await { compose.onAllNodesWithText("Проверяем бронь 43").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Проверяем бронь 43") and hasClickAction()).performClick()
    }
    @Test fun notificationFeedWaitsForAuthorizationBeforeOpeningBooking() {
        held = true; openFeedBooking(); await { started.count == 0L }
        assertEquals(Screen.Notifications, vm.screen.value); assertEquals(42, vm.activeBookingId.value)
        assertEquals(43, vm.pendingBookingNavigation.value?.bookingId)
        assertEquals(11, vm.pendingBookingNavigation.value?.ownerId); assertEquals(false, vm.pendingBookingNavigation.value?.completed)
        finishOld(); reached(43)
    }
    @Test fun forbiddenNotificationFeedBookingKeepsPreviousScreen() {
        firstStatus = 403; openFeedBooking()
        await { compose.onAllNodesWithTag("bookingLinkClose").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Screen.Notifications, vm.screen.value); assertEquals(42, vm.activeBookingId.value)
        assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun retainedNotificationFeedRowCannotRebindAcrossAccountsInSameFrame() {
        notificationEntry = true; mount(); compose.runOnIdle { vm.screen.value = Screen.Notifications }
        await { compose.onAllNodesWithText("Проверяем бронь 43").fetchSemanticsNodes().isNotEmpty() }
        val click = compose.onNode(hasText("Проверяем бронь 43") and hasClickAction())
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { ApiClient.saveToken(token12); click(); assertNull(vm.pendingBookingNavigation.value) }
        pump(); assertEquals(42, vm.activeBookingId.value)
        assertFalse(records.any { it.first == "/bookings/43/role" && it.second == "Bearer $token12" })
        assertFalse(records.any { it.first == "/notifications/read" && it.second == "Bearer $token12" })
    }
    @Test fun lateOldNotificationFeedCannotAppearAfterAccountChange() {
        notificationEntry = true; holdFirstFeed = true; mount(); compose.runOnIdle { vm.screen.value = Screen.Notifications }
        await { feedStarted.count == 0L }; compose.runOnIdle { ApiClient.saveToken(token12) }
        await { records.any { it.first == "/notifications" && it.second == "Bearer $token12" } }
        await { compose.onAllNodesWithText("Уведомлений пока нет").fetchSemanticsNodes().isNotEmpty() }
        feedRelease.countDown(); assertTrue(feedDispatched.await(8, TimeUnit.SECONDS)); pump(1500)
        compose.onNodeWithText("Проверяем бронь 43").assertDoesNotExist()
        compose.onNodeWithText("Уведомлений пока нет").assertIsDisplayed()
        assertEquals(Screen.Notifications, vm.screen.value); assertEquals(42, vm.activeBookingId.value)
        assertNull(vm.pendingBookingNavigation.value)
    }
    @Test fun anonymousNotificationFeedFinishesLoadingWithoutProtectedRequests() {
        compose.runOnIdle { ApiClient.logout(); vm.clearUserData(); vm.screen.value = Screen.Notifications; vm.persistNav() }
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides actor.get()) { YuldashTheme { YuldashApp() } } }
        await { compose.onAllNodesWithText("Уведомлений пока нет").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Screen.Notifications, vm.screen.value)
        assertFalse(records.any { it.first == "/notifications" || it.first == "/notifications/read" })
    }
}
