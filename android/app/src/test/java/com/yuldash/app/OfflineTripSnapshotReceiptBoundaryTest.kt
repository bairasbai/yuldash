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

/** Retains the real completed-trip button callback; LegacySavedStateCompatibilityTest stays unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineTripSnapshotReceiptBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val vm = YuldashViewModel(SavedStateHandle())
    private val mounted = mutableStateOf(true)
    private lateinit var server: MockWebServer
    private var receiptId = 0
    private var receiptCalls = 0
    private var displayedSession = 0L
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        displayedSession = ApiClient.queueSessionGeneration()
        vm.activeBookingId.value = 42; vm.selectedRide.value = bookingLinkStubRide(42); vm.screen.value = Screen.ActiveTrip
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setHeader("Connection", "close").setBody(
                    when (request.requestUrl!!.encodedPath) {
                        "/bookings/42/role" -> """{"role":"passenger","status":"done"}"""
                        "/bookings/42/details" -> """{"booking_id":42,"ride_id":9,"role":"passenger","status":"done","from_city":"Уфа","to_city":"Бирск"}"""
                        else -> """{"items":[],"messages":[]}"""
                    })
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 2000
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }; compose.waitForIdle()
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private fun retainedReceipt(): () -> Boolean {
        compose.setContent {
            if (mounted.value) YuldashTheme {
                GuardedActiveTripDestination(vm, displayedSession, emptyList(), ActiveTripTerminalHint(42, "done", "passenger"), false,
                    onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {}, onTripStopped = {},
                    onOpenReceipt = { receiptCalls++; receiptId = it; vm.screen.value = Screen.TripReceipt }, currentReceiptBookingId = { receiptId })
            }
        }
        compose.waitUntil(12000) {
            Snapshot.sendApplyNotifications(); Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("rideshareCompletedHero").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasText("Квитанция"))
        compose.onNodeWithText("Квитанция").assertIsDisplayed().assertIsEnabled()
        return compose.onNodeWithText("Квитанция").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun rejected(change: () -> Unit) {
        val receipt = retainedReceipt()
        compose.runOnIdle { change(); assertTrue(receipt()) }
        assertEquals("A retained foreign/outgoing callback must not write the receipt context", 0, receiptCalls)
    }
    @Test fun currentReceiptCanFinishItsAlreadySelectedEmptyDestination() {
        val receipt = retainedReceipt()
        compose.runOnIdle { vm.screen.value = Screen.TripReceipt; assertTrue(receipt()) }
        assertEquals(1, receiptCalls); assertEquals(42, receiptId)
    }
    @Test fun outgoingOtherDestinationCannotOpenReceipt() {
        rejected { vm.screen.value = Screen.Support }
        assertEquals(Screen.Support, vm.screen.value); assertEquals(0, receiptId)
    }
    @Test fun newAccountCannotUseTheOldReceiptCallback() {
        rejected { ApiClient.saveToken("header.eyJzdWIiOiIxMiJ9.signature"); vm.screen.value = Screen.TripReceipt }
        assertEquals(12, ApiClient.myUserId()); assertEquals(0, receiptId)
    }
    @Test fun differentAlreadySelectedReceiptIsNeverOverwritten() {
        rejected { receiptId = 47; vm.screen.value = Screen.TripReceipt }
        assertEquals(47, receiptId)
    }
    @Test fun anotherBookingCannotUseTheOldReceiptCallback() {
        rejected { vm.activeBookingId.value = 43; vm.screen.value = Screen.TripReceipt }
        assertEquals(43, vm.activeBookingId.value); assertEquals(0, receiptId)
    }
    @Test fun anotherRideCannotUseTheOldReceiptCallback() {
        rejected { vm.selectedRide.value = bookingLinkStubRide(43); vm.screen.value = Screen.TripReceipt }
        assertEquals(0, receiptId)
    }
    @Test fun newNavigationRevisionCannotCompleteTheOldReceiptTransition() {
        rejected { vm.navigateLocally { vm.screen.value = Screen.TripReceipt } }
        assertEquals(0, receiptId)
    }
}
