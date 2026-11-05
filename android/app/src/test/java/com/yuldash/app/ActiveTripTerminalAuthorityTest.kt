package com.yuldash.app

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual parent/HTTP boundary, with one receipt response and no replacement authority DTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ActiveTripTerminalAuthorityTest : SnapshotWiringFixture() {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(false)
    private val roleHeld = CountDownLatch(1)
    private val releaseRole = CountDownLatch(1)
    private val receiptHeld = CountDownLatch(1)
    private val releaseReceipt = CountDownLatch(1)
    private val receiptCalls = AtomicInteger()

    private fun settle() {
        compose.mainClock.advanceTimeBy(50)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }

    private fun mount(vm: YuldashViewModel) {
        compose.mainClock.autoAdvance = true
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                val session = ApiClient.sessionChanges.collectAsState(initial = ApiClient.queueSessionGeneration()).value
                if (mounted.value) GuardedActiveTripDestination(vm, session, emptyList(), null, false,
                    onBack = {}, onTripEnd = {}, onSos = {}, onSupport = {}, onTrustedContacts = {}, onTripStopped = {},
                    onOpenReceipt = {}, currentReceiptBookingId = { 0 })
            }
        }
    }

    /** Unit-only observation explicitly approved by Root: read this synthetic fixture's actual
     * history reference, then ask the production authority. Never mutate the map, manufacture a
     * permit/DTO, inspect auth fields or add a second receipt GET to obtain a different projection.
     */
    @Suppress("UNCHECKED_CAST")
    private fun actualHistory(): TripReceiptDto? = synchronized(OfflineTripSnapshots) {
        val field = OfflineTripSnapshots::class.java.getDeclaredField("histories").apply { isAccessible = true }
        (field.get(OfflineTripSnapshots) as Map<Int, Pair<TripReceiptDto, SnapshotOwner>>)[11]?.first
    }

    @After fun unmountTerminal() {
        releaseReceipt.countDown()
        releaseRole.countDown()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
    }

    @Test fun equalTerminalFromActualReceiptKeepsCompletedHeaderAndRatingReachable(): Unit = runBlocking {
        // Network barriers outlive the unchanged 15s UI oracle and are always released in finally.
        ApiClient.testTimeoutMs = 45000
        handler = { request -> when (request.path) {
            "/bookings/11/trip-status" -> {
                assertEquals("POST", request.method)
                assertEquals("done", JSONObject(request.body.clone().readUtf8()).getString("status"))
                MockResponse().setBody("{\"status\":\"done\"}")
            }
            "/bookings/11/role" -> {
                roleHeld.countDown()
                check(releaseRole.await(30, TimeUnit.SECONDS))
                MockResponse().setBody(roleJson(status = "done").toString())
            }
            "/trips/11/receipt" -> {
                receiptCalls.incrementAndGet()
                receiptHeld.countDown()
                check(releaseReceipt.await(30, TimeUnit.SECONDS))
                MockResponse().setBody(receiptJson().toString())
            }
            "/bookings/11/tip" -> MockResponse().setBody("{\"already_thanked\":false}")
            "/bookings/11/messages" -> MockResponse().setBody("{\"items\":[]}")
            else -> defaultResponse(request)
        } }
        try {
            val details = ApiClient.getBookingDetails(11).getOrThrow()
            assertTrue(OfflineTripSnapshots.privateDetails(details))
            assertTrue(ApiClient.setTripStatus(11, "done").isSuccess)
            val terminalA = requireNotNull(OfflineTripSnapshots.terminal(11))
            val originalOwner = owner()
            assertEquals("done", terminalA.status)
            assertEquals("passenger", terminalA.role)
            assertFalse(OfflineTripSnapshots.allowsLive(11))
            assertNull(OfflineTripSnapshots.privatePass(11))
            assertNull(actualHistory())
            // Only the route is seeded, as in OfflineTripRestoreWiringTest; authority came from HTTP.
            val vm = YuldashViewModel(SavedStateHandle()).apply {
                activeBookingId.value = 11
                selectedRide.value = bookingLinkStubRide(11)
                screen.value = Screen.ActiveTrip
            }
            val originalRide = vm.selectedRide.value
            val revision = vm.privateNavigationRevision
            mount(vm)
            compose.waitUntil(15000) {
                settle()
                roleHeld.count == 0L && receiptHeld.count == 0L &&
                    compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
            assertSame(terminalA, OfflineTripSnapshots.terminal(11))
            assertNull(actualHistory())
            assertEquals(1, receiptCalls.get())
            println("TERMINAL_RECEIPT_PHASE beforeHeader=true receiptHeld=true live=false history=false")

            releaseReceipt.countDown()
            compose.waitUntil(15000) {
                settle()
                val current = OfflineTripSnapshots.terminal(11)
                current != null && current !== terminalA && actualHistory()?.let(OfflineTripSnapshots::allowsHistory) == true
            }
            val terminalB = requireNotNull(OfflineTripSnapshots.terminal(11))
            val history = requireNotNull(actualHistory())
            assertNotSame(terminalA, terminalB)
            assertEquals(terminalA, terminalB)
            assertTrue(OfflineTripSnapshots.allowsHistory(history))
            assertEquals(originalOwner, owner())
            assertEquals(11, vm.activeBookingId.value)
            assertSame(originalRide, vm.selectedRide.value)
            assertEquals(revision, vm.privateNavigationRevision)
            assertEquals(Screen.ActiveTrip, vm.screen.value)
            assertNull(vm.pendingBookingNavigation.value)
            assertNull(vm.pendingScreenNavigation.value)
            assertNull(vm.pendingPublicRideNavigation.value)
            assertFalse(OfflineTripSnapshots.allowsLive(11))
            assertNull(OfflineTripSnapshots.privatePass(11))
            assertEquals(1, receiptCalls.get())
            println("TERMINAL_RECEIPT_PHASE equal=${terminalA == terminalB} sameReference=${terminalA === terminalB} " +
                "history=${OfflineTripSnapshots.allowsHistory(history)} ownerCurrent=true routeCurrent=true live=false")

            compose.waitUntil(15000) {
                settle()
                compose.onAllNodesWithText("Поездка завершена").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Поездка завершена").assertIsDisplayed()
            compose.onNodeWithTag("rideshareCompletedList").performScrollToNode(hasTestTag("rideshareStar5"))
            compose.onNodeWithTag("rideshareStar5").assertIsDisplayed().performClick()
            compose.waitUntil(15000) { settle(); runCatching { compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("rideshareRatingSubmit").assertIsEnabled()
            assertTrue(OfflineTripSnapshots.allowsHistory(history))
            assertEquals(0, requests.count { it.method == "POST" && it.path == "/bookings/11/rate" })
        } finally {
            releaseReceipt.countDown()
            releaseRole.countDown()
        }
    }
}
