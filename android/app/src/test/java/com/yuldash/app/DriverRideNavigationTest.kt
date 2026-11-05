package com.yuldash.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriverRideNavigationTest {
    private fun row(id: Int, status: String = "onboard", ride: Int = 8) = DriverBookingDto(id, "", null, "", status, rideId = ride)
    private fun vm() = YuldashViewModel(SavedStateHandle()).apply {
        screen.value = Screen.ActiveTrip; activeBookingId.value = 42; activeDriverBookingId.value = 42
        navHistory.addAll(listOf(Screen.Home, Screen.DriverCabinet, Screen.ActiveTrip, Screen.TripChat))
    }
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        ApiClient.saveToken("fixture.eyJzdWIiOiIxIn0.fixture")
    }
    @After fun cleanup() { ApiClient.resetForTest(); Dispatchers.resetMain() }
    @Test fun closedAWithLiveBReturnsToCabinetAndRemovesClosedTripTrail() = runTest {
        val vm = vm()
        assertTrue(vm.finishDriverRideBooking(42) { Result.success(DriverBookingsFeed(listOf(row(42, "done"), row(43)), false)) })
        assertEquals(Screen.DriverCabinet, vm.screen.value)
        assertTrue(vm.focusRidingSection.value)
        assertEquals(listOf(Screen.Home), vm.navHistory.toList())
        assertNull(vm.activeTrip.value)
    }
    @Test fun completeLastShowsReceiptButPartialOrNetworkUnknownReturnsToReloadableCabinet() = runTest {
        val last = vm()
        assertFalse(last.finishDriverRideBooking(42) { Result.success(DriverBookingsFeed(listOf(row(42, "done")), false)) })
        assertEquals(42, last.driverRideOverBooking.value)
        for (feed in listOf(Result.success(DriverBookingsFeed(listOf(row(42, "done")), true)), Result.failure(IllegalStateException("offline")))) {
            val unknown = vm()
            assertTrue(unknown.finishDriverRideBooking(42) { feed })
            assertEquals(Screen.DriverCabinet, unknown.screen.value)
            assertEquals(0, unknown.driverRideOverBooking.value)
        }
    }
    @Test fun freshDifferentRideCannotProveAnotherPassengerAndMissingAnchorIsUnknown() = runTest {
        val owner = ApiClient.queueSessionGeneration()
        assertFalse(driverRideHasLiveBookings(42, owner) { Result.success(DriverBookingsFeed(listOf(row(42, "done"), row(43, ride = 9)), false)) }!!)
        assertNull(driverRideHasLiveBookings(42, owner) { Result.success(DriverBookingsFeed(listOf(row(43)), false)) })
    }
    @Test fun delayedLookupAfterOwnerOrNavigationOrBookingChangeCannotNavigate() = runTest {
        for (boundary in listOf("owner", "navigation", "booking", "role")) {
            val vm = vm()
            val reply = CompletableDeferred<Result<DriverBookingsFeed>>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { vm.finishDriverRideBooking(42) { reply.await() } }
            when (boundary) {
                "owner" -> ApiClient.saveToken("fixture.eyJzdWIiOiIyIn0.fixture")
                "navigation" -> vm.navigateLocally { vm.screen.value = Screen.Support }
                "booking" -> vm.activeBookingId.value = 43
                "role" -> vm.activeDriverBookingId.value = null
            }
            reply.complete(Result.success(DriverBookingsFeed(listOf(row(42, "done"), row(43)), false)))
            assertTrue(pending.await())
            assertFalse(vm.focusRidingSection.value)
            assertNotEquals(Screen.DriverCabinet, vm.screen.value)
        }
    }
}
