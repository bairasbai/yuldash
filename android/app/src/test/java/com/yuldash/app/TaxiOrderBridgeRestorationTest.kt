package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Real HomeRoute/bar callbacks and Activity saved Bundle; no actual OS process kill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiOrderBridgeRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val controllers = mutableListOf<ActivityController<MainActivity>>()
    private val currentActor = mutableStateOf<ActivityController<MainActivity>?>(null)
    private val mounted = mutableStateOf(true)
    private val fullApp = mutableStateOf(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val records = CopyOnWriteArrayList<Pair<String, String?>>()
    private lateinit var server: MockWebServer
    private lateinit var actor: ActivityController<MainActivity>
    private val vm get() = ViewModelProvider(actor.get())[YuldashViewModel::class.java]

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context); ApiClient.saveToken(tokenA)
        clearTransient()
        context.getSharedPreferences("yuldash_prefs", 0).edit().clear()
            .putBoolean("onboarding_completed", false).putString("mode_last", "pooling")
            .putBoolean("mode_hint_shown", true).commit()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    records += path to request.getHeader("Authorization")
                    val order = """{"id":91,"status":"accepted","role":"passenger","driver_name":"Водитель А","from_text":"Пункт А","to_text":"Пункт Б"}"""
                    val body = when (path) {
                        "/instant/orders/91" -> order
                        "/instant/orders/mine" -> """{"items":[$order]}"""
                        else -> """{"items":[],"unread":0,"role":"passenger"}"""
                    }
                    return MockResponse().setResponseCode(if (path == "/instant/orders/active") 503 else 200)
                        .setHeader("Content-Type", "application/json").setBody(body)
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
        actor = create(); currentActor.value = actor
        vm.screen.value = Screen.Home; vm.startHomeTab.value = HomeTab.Profile
        vm.recordNavigationChange(); vm.persistNav()
        NavSignals.activeTaxiTrip.value = 91
    }

    private fun create(state: Bundle? = null) = Robolectric.buildActivity(MainActivity::class.java)
        .also { controllers += it }.create(state)
    private fun clearTransient() {
        NavSignals.activeTaxiTrip.value = 0; NavSignals.taxiOrderOnScreen.value = false
        NavSignals.taxiTripOnScreen.value = false; NavSignals.openInstantOrder.value = false
        NavSignals.openInstantChat.value = 0; NavSignals.openDriverCabinet.value = false
        NavSignals.openAdsCabinet.value = false; NavSignals.openPartnerCabinet.value = false
        DeepLink.pendingParcels.value = false; DeepLink.pendingSupport.value = false
        DeepLink.pendingBookingChatId.value = null; DeepLink.pendingCompletedBookingId.value = null
        DeepLink.pendingRideId.value = null; DeepLink.pendingFairness.value = false
        DeepLink.pendingRequestResponsesId.value = null; DeepLink.pendingRequestsFeed.value = false
        DeepLink.pendingApplicationScreen.value = null
    }
    private fun pump(ms: Long = 500) {
        repeat((ms / 100).toInt()) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) {
        Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
        Shadows.shadowOf(Looper.getMainLooper()).idle(); check()
    }
    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(
                LocalViewModelStoreOwner provides currentActor.value!!.get(),
                LocalPoolingNativeMapEnabled provides false,
            ) {
                YuldashTheme {
                    if (fullApp.value) YuldashApp() else HomeRoute(
                        vm = ViewModelProvider(currentActor.value!!.get())[YuldashViewModel::class.java],
                        prefs = context.getSharedPreferences("yuldash_prefs", 0), appScope = appScope,
                        requestsLoading = false, requestsError = false, payMethod = PayMethods.CASH,
                        onRetryRequests = {}, onLoginRequired = {}, onBookingStatus = {},
                        onRouteWatchPrefill = { _, _ -> }, onCreateRide = { _, _ -> }, onSos = {},
                        onTrustedContacts = {}, onAdImpression = {}, onAdClick = {},
                    )
                }
            }
        }
        pump()
    }
    private fun openAction(): () -> Boolean {
        mount(); await { compose.onAllNodesWithText("Открыть").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithText("Открыть").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun save() = Bundle().also { actor.saveInstanceState(it) }
    private fun assertPending(owner: Int = 11) {
        val pending = vm.pendingScreenNavigation.value
        assertNotNull("The actual Home tap must persist before a Compose frame", pending)
        assertEquals(Screen.InstantOrder, pending!!.destination); assertEquals(owner, pending.ownerId)
    }
    private fun restore(state: Bundle) {
        clearTransient(); actor = create(state); currentActor.value = actor
    }
    private fun tapAndSave(): Bundle {
        val open = openAction(); lateinit var state: Bundle
        compose.runOnIdle { open(); state = save() }
        return state
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; pump(300)
        appScope.cancel(); controllers.reversed().forEach { it.destroy() }
        clearTransient(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }

    @Test fun homeTapSurvivesBundleBeforeAnyNavigationEffect() {
        val state = tapAndSave(); val previous = vm
        compose.runOnIdle { restore(state) }
        assertNotSame(previous, vm); assertEquals(HomeTab.Map, vm.startHomeTab.value)
        assertPending(); assertTrue(NavSignals.openInstantOrder.value)
    }
    @Test fun pendingHomeTapCannotRebindToAnotherAccountAfterRestore() {
        val state = tapAndSave(); assertPending()
        compose.runOnIdle { ApiClient.saveToken(tokenB); restore(state) }
        assertNull(vm.pendingScreenNavigation.value); assertFalse(NavSignals.openInstantOrder.value)
        assertEquals(Screen.Home, vm.screen.value)
    }
    @Test fun pendingHomeTapIsDiscardedWhenRestoredWithoutLogin() {
        val state = tapAndSave(); assertPending()
        compose.runOnIdle { ApiClient.logout(); restore(state) }
        assertNull(vm.pendingScreenNavigation.value); assertFalse(NavSignals.openInstantOrder.value)
        compose.runOnIdle { ApiClient.saveToken(tokenA) }; pump()
        assertNull(vm.pendingScreenNavigation.value)
    }
    @Test fun sameOwnerTokenReplacementKeepsSavedHomeTap() {
        val state = tapAndSave(); assertPending()
        compose.runOnIdle { ApiClient.saveToken(tokenA + "new"); restore(state) }
        assertPending(); assertTrue(NavSignals.openInstantOrder.value)
    }
    @Test fun restoredAppConsumesOnceAndLoadsCurrentOwnersOrder() {
        val state = tapAndSave()
        compose.runOnIdle { restore(state); fullApp.value = true }
        await { vm.screen.value == Screen.InstantOrder && vm.pendingScreenNavigation.value == null }
        await { records.any { it.first == "/instant/orders/mine" && it.second == "Bearer $tokenA" } }
        assertFalse(NavSignals.openInstantOrder.value)
        compose.onNodeWithText("Водитель А").assertExists()
        compose.runOnIdle { vm.navigateLocally { vm.screen.value = Screen.Notifications } }; pump(1500)
        assertEquals(Screen.Notifications, vm.screen.value); assertNull(vm.pendingScreenNavigation.value)
    }
    @Test fun newestNotificationSupersedesHomeTapBeforeSave() {
        val open = openAction(); lateinit var state: Bundle
        compose.runOnIdle {
            open(); assertPending()
            MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }.invoke(actor.get(), Intent(context, MainActivity::class.java)
                    .putExtra("type", "support").putExtra("id", "43").putExtra("recipient_user_id", "11"))
            state = save()
        }
        compose.runOnIdle { restore(state) }
        assertEquals(Screen.SupportTickets, vm.pendingScreenNavigation.value?.destination)
        assertFalse(NavSignals.openInstantOrder.value); assertTrue(DeepLink.pendingSupport.value)
    }
    @Test fun laterTabChoiceCancelsSavedHomeTap() {
        val open = openAction(); compose.runOnIdle { open() }; assertPending(); pump(1500)
        lateinit var state: Bundle
        compose.onNodeWithText("Профиль").performClick()
        compose.runOnIdle { state = save(); restore(state) }
        assertEquals(HomeTab.Profile, vm.startHomeTab.value)
        assertNull(vm.pendingScreenNavigation.value); assertFalse(NavSignals.openInstantOrder.value)
    }
    @Test fun duplicateRetainedTapCannotCreateSecondDestination() {
        val open = openAction()
        compose.runOnIdle {
            open(); assertPending(); val first = vm.pendingScreenNavigation.value
            val revision = vm.privateNavigationRevision; open()
            assertEquals(first, vm.pendingScreenNavigation.value); assertEquals(revision, vm.privateNavigationRevision)
        }
    }
    @Test fun retainedTapAfterAccountChangeCannotCreatePendingDestination() {
        val open = openAction()
        compose.runOnIdle { ApiClient.saveToken(tokenB); open() }
        pump(); assertNull(vm.pendingScreenNavigation.value); assertFalse(NavSignals.openInstantOrder.value)
    }
    @Test fun disposedHomeTapCannotCreatePendingDestination() {
        val open = openAction(); compose.runOnIdle { mounted.value = false }; pump()
        compose.runOnIdle { open() }; assertNull(vm.pendingScreenNavigation.value)
        assertFalse(NavSignals.openInstantOrder.value)
    }
}
