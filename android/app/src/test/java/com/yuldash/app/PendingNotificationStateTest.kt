package com.yuldash.app

import androidx.lifecycle.SavedStateHandle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PendingNotificationStateTest {
    @After fun cleanup() { DeepLink.pendingCompletedBookingId.value = null }
    private fun copied(h: SavedStateHandle) = SavedStateHandle(h.keys().associateWith { h.get<Any?>(it) })
    @Test fun requestIsSavedWithoutWaitingForNavEffect() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, 11)
        val restored = YuldashViewModel(copied(h)).pendingCompletedForOwner(11)
        assertEquals(43, restored?.bookingId); assertEquals(11, restored?.ownerId)
        assertEquals(43, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun repeatedSameBookingGetsNewRevision() {
        val vm = YuldashViewModel(SavedStateHandle()); vm.requestCompletedBooking(43, 11)
        val old = vm.pendingCompletedNavigation.value!!; vm.requestCompletedBooking(43, 11)
        assertTrue(vm.pendingCompletedNavigation.value!!.revision > old.revision)
        var applied = false; assertFalse(vm.consumePendingCompleted(old) { applied = true }); assertFalse(applied)
        assertEquals(43, DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun oldDifferentBookingCannotConsumeNewer() {
        val vm = YuldashViewModel(SavedStateHandle()); vm.requestCompletedBooking(43, 11)
        val old = vm.pendingCompletedNavigation.value!!; vm.requestCompletedBooking(44, 11)
        assertFalse(vm.consumePendingCompleted(old) { fail("Old destination applied") })
        assertEquals(44, vm.pendingCompletedNavigation.value!!.bookingId)
    }
    @Test fun consumingSavesRouteAndRemovesPendingWithoutAnotherEffect() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, 11)
        assertTrue(vm.consumePendingCompleted(vm.pendingCompletedNavigation.value!!) {
            vm.screen.value = Screen.ActiveTrip; vm.activeBookingId.value = 43
            vm.navHistory.add(Screen.TripReceipt)
        })
        val restored = YuldashViewModel(copied(h))
        assertEquals(Screen.ActiveTrip, restored.screen.value); assertEquals(43, restored.activeBookingId.value)
        assertEquals(listOf(Screen.TripReceipt), restored.navHistory)
        assertNull(restored.pendingCompletedNavigation.value); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun ownerMismatchRemovesDiskPendingImmediately() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, 11)
        assertNull(vm.pendingCompletedForOwner(12)); assertNull(YuldashViewModel(copied(h)).pendingCompletedNavigation.value)
        assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun consumingSavesPreviousScreenBeforeNavigationEffectRuns() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h)
        vm.screen.value = Screen.TripReceipt; vm.navPrev.value = Screen.TripReceipt; vm.activeBookingId.value = 42
        vm.requestCompletedBooking(43, 11)
        vm.consumePendingCompleted(vm.pendingCompletedNavigation.value!!) {
            vm.screen.value = Screen.ActiveTrip; vm.activeBookingId.value = 43
        }
        assertEquals(listOf(Screen.TripReceipt), YuldashViewModel(copied(h)).navHistory)
    }
    @Test fun ownedPendingDoesNotSurviveLogoutOwnerCheck() {
        val vm = YuldashViewModel(SavedStateHandle()); vm.requestCompletedBooking(43, 11)
        assertNull(vm.pendingCompletedForOwner(null)); assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun preLoginPendingWaitsAndBindsFirstKnownOwner() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, null)
        assertNotNull(vm.pendingCompletedForOwner(null)); assertEquals(11, vm.pendingCompletedForOwner(11)?.ownerId)
        assertEquals(11, YuldashViewModel(copied(h)).pendingCompletedNavigation.value?.ownerId)
    }
    @Test fun ownerBindingRejectsPreviouslyCapturedUnboundRecord() {
        val vm = YuldashViewModel(SavedStateHandle()); vm.requestCompletedBooking(43, null)
        val old = vm.pendingCompletedNavigation.value!!; vm.pendingCompletedForOwner(11)
        assertFalse(vm.consumePendingCompleted(old) { fail("Unbound record applied") })
    }
    @Test fun clearUserDataRemovesPendingWithoutPersistNav() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, 11)
        vm.clearUserData(); assertNull(YuldashViewModel(copied(h)).pendingCompletedNavigation.value)
        assertNull(DeepLink.pendingCompletedBookingId.value)
    }
    @Test fun revisionKeepsIncreasingAfterConsumptionAndRestore() {
        val h = SavedStateHandle(); val vm = YuldashViewModel(h); vm.requestCompletedBooking(43, 11)
        val old = vm.pendingCompletedNavigation.value!!; vm.consumePendingCompleted(old) {}
        val restored = YuldashViewModel(copied(h)); restored.requestCompletedBooking(43, 11)
        assertTrue(restored.pendingCompletedNavigation.value!!.revision > old.revision)
    }
    @Test fun invalidBookingDoesNotReplaceCurrentPending() {
        val vm = YuldashViewModel(SavedStateHandle()); vm.requestCompletedBooking(43, 11)
        val old = vm.pendingCompletedNavigation.value; vm.requestCompletedBooking(0, 11); vm.requestCompletedBooking(-1, 11)
        assertEquals(old, vm.pendingCompletedNavigation.value)
    }
    @Test fun invalidSavedBookingIsIgnored() {
        val h = SavedStateHandle(mapOf<String, Any>("yuldash_pending_completed" to -1, "yuldash_pending_completed_revision" to 2L))
        assertNull(YuldashViewModel(h).pendingCompletedNavigation.value)
    }
}
