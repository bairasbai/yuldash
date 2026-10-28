package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real guard/VM/UI actions: immediate retained-tap invalidation and pending-destination barriers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptRepeatedNavigationBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val vm = YuldashViewModel(SavedStateHandle())
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private var displayedSession = 0L
    private var receiptId = 0
    private var receiptCalls = 0
    private var contentMounted = false

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        displayedSession = ApiClient.queueSessionGeneration()
        vm.activeBookingId.value = 42; vm.selectedRide.value = bookingLinkStubRide(42)
        vm.screen.value = Screen.ActiveTrip
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Connection", "close")
                    .setHeader("Content-Type", "application/json").setBody(when (request.requestUrl!!.encodedPath) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"done"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":9,"role":"passenger","status":"done","from_city":"Уфа","to_city":"Бирск"}"""
                        "/trips/42/receipt" -> """{"booking_id":42,"ride_id":9,"role":"passenger","from_city":"Уфа","to_city":"Бирск","amount":400,"pay_method":"cash","paid":true,"my_stars":0}"""
                        "/bookings/42/tip" -> """{"already_thanked":false}"""
                        else -> """{"items":[],"messages":[],"code":""}"""
                    })
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 2000
        AppPrefs.setLanguage(context, AppLanguage.Ru)
    }
    private fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        compose.waitForIdle()
    }
    private fun button(): SemanticsNodeInteraction {
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        return compose.onNodeWithText("Квитанция").assertIsDisplayed().assertIsEnabled()
    }
    private fun capture(): () -> Unit = frozenReceiptClick(button())
    private fun mount(): () -> Unit {
        compose.mainClock.autoAdvance = false; contentMounted = true
        compose.setContent {
            if (mounted.value) YuldashTheme {
                GuardedActiveTripDestination(vm, displayedSession, emptyList(),
                    ActiveTripTerminalHint(42, "done", "passenger"), false,
                    onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {}, onTripStopped = {},
                    onOpenReceipt = { receiptCalls++; receiptId = it; vm.screen.value = Screen.TripReceipt },
                    currentReceiptBookingId = { receiptId })
            }
        }
        compose.waitUntil(12000) {
            Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100)
            Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            compose.onAllNodesWithTag("rideshareCompletedList").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("rideshareReceiptLoading").fetchSemanticsNodes().isEmpty()
        }
        return capture()
    }
    private fun remainsActive() {
        assertEquals("Pending/outgoing UI must not select a receipt", 0, receiptCalls)
        assertEquals(0, receiptId); assertEquals(Screen.ActiveTrip, vm.screen.value)
    }
    private fun pendingRejectsFreshOldBooking(change: () -> Unit) {
        val old = mount()
        compose.runOnIdle { change(); old(); remainsActive() }
        settle()
        val freshOldBooking = capture()
        compose.runOnIdle { freshOldBooking(); remainsActive(); old(); remainsActive() }
    }

    @Test fun sameBookingConsumeEnablesCurrentButtonButNeverRevivesRetainedOldTap() {
        val old = mount()
        compose.runOnIdle {
            vm.requestCompletedBooking(42, 11)
            old(); remainsActive()
            val destination = requireNotNull(vm.pendingBookingNavigation.value)
            assertTrue(vm.consumePendingBooking(destination) {
                vm.applyLiveSnapshotRoute(42, "passenger", "done", completed = true, cancelledInTransit = false)
            })
            old(); remainsActive()
        }
        settle()
        compose.runOnIdle {
            old(); remainsActive()
        }
        button().performClick()
        compose.runOnIdle {
            assertEquals(1, receiptCalls); assertEquals(42, receiptId); assertEquals(Screen.TripReceipt, vm.screen.value)
        }
    }
    @Test fun callbackCapturedDuringRevalidationNeverActivatesAfterConsume() {
        val old = mount()
        compose.runOnIdle { vm.requestCompletedBooking(42, 11); old(); remainsActive() }
        settle()
        val pendingTap = capture()
        compose.runOnIdle {
            pendingTap(); remainsActive()
            val destination = requireNotNull(vm.pendingBookingNavigation.value)
            assertTrue(vm.consumePendingBooking(destination) {
                vm.applyLiveSnapshotRoute(42, "passenger", "done", completed = true, cancelledInTransit = false)
            })
            pendingTap(); remainsActive()
        }
        settle()
        compose.runOnIdle {
            old(); remainsActive()
            pendingTap(); remainsActive()
        }
        button().performClick()
        compose.runOnIdle {
            assertEquals(1, receiptCalls); assertEquals(42, receiptId); assertEquals(Screen.TripReceipt, vm.screen.value)
        }
    }
    @Test fun freshOldBookingButtonIsRejectedWhileDifferentBookingIsPending() =
        pendingRejectsFreshOldBooking { vm.requestBookingDestination(43, 11, completed = false) }

    @Test fun freshOldBookingButtonIsRejectedWhileSameBookingIsRevalidated() =
        pendingRejectsFreshOldBooking { vm.requestCompletedBooking(42, 11) }

    @Test fun freshOldBookingButtonIsRejectedWhilePublicRideIsPending() =
        pendingRejectsFreshOldBooking { vm.requestPublicRide(43) }

    @Test fun freshOldBookingButtonIsRejectedWhilePrivateScreenIsPending() =
        pendingRejectsFreshOldBooking { vm.requestScreenDestination(Screen.SupportTickets, 11) }

    @After fun cleanup() {
        if (contentMounted) { compose.runOnIdle { mounted.value = false }; compose.waitForIdle() }
        vm.clearUserData(); ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
}