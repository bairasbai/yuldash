package com.yuldash.app

import androidx.compose.runtime.Composable
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.TripStateDto

internal suspend fun restoreBookingSnapshotNavigation(
    vm: YuldashViewModel, bookingId: Int, requested: Screen, revision: Long, session: Long,
    isCurrent: () -> Boolean, onRestored: (TripStateDto?) -> Unit,
): Boolean {
    val read = readBookingNavigation(bookingId)
    if (!isCurrent()) return false
    var restored = false
    ApiClient.runIfCurrentSession(session) {
        if (isCurrent()) {
            restored = vm.applyRestoredBookingRead(read, bookingId, requested, revision)
            if (restored) onRestored(read.live.getOrNull())
        }
    }
    return restored
}

internal fun YuldashViewModel.applyLiveSnapshotRoute(bookingId: Int, role: String, status: String, completed: Boolean, cancelledInTransit: Boolean): Screen {
    offlineTripSnapshot.value = null
    return applyBookingLink(bookingId, role, status, completed, cancelledInTransit)
}

internal fun YuldashViewModel.applyOfflineBookingRead(read: BookingNavigationRead, revision: Long): Boolean {
    val saved = read.saved?.takeIf { OfflineTripSnapshots.current(it.owner) } ?: return false
    openOfflineTrip(saved, revision)
    return true
}

/** Called only after the app's original owner/generation/screen/revision restore guard. */
internal fun YuldashViewModel.applyRestoredBookingRead(read: BookingNavigationRead, bookingId: Int, requested: Screen, revision: Long): Boolean {
    val state = read.live.getOrNull() ?: return applyOfflineBookingRead(read, revision)
    val target = applyLiveSnapshotRoute(bookingId, state.role, state.status, false, state.cancelledInTransit)
    // Restoring a passenger ActiveTrip needs fresh role proof before live state, just like the old list restore.
    if (requested.contentScreen() == Screen.ActiveTrip && target == Screen.Booking && state.role == "passenger" && bookingStatusAllowsActiveTrip(state.status)) {
        activeTrip.value = selectedRide.value.takeIf { bookingStatusAllowsBoarding(state.status) }
        screen.value = requested
    }
    return true
}

/** A narrow destination wrapper; all working live UI remains in ActiveTripScreen. */
@Composable
internal fun GuardedActiveTripDestination(
    vm: YuldashViewModel, session: Long, contacts: List<TrustedContact>, terminalHint: ActiveTripTerminalHint?, chatOpen: Boolean?,
    onBack: () -> Unit, onTripEnd: () -> Unit, onSos: () -> Unit, onSupport: () -> Unit,
    onTrustedContacts: () -> Unit, onTripStopped: () -> Unit, onOpenReceipt: (Int) -> Unit, currentReceiptBookingId: () -> Int,
) {
    val mode = vm.offlineTripSnapshot.value
    if (mode != null) {
        fun currentOffline() = vm.offlineTripSnapshot.value === mode && vm.screen.value.contentScreen() == Screen.ActiveTrip &&
            vm.activeBookingId.value == mode.snapshot.bookingId && vm.privateNavigationRevision == mode.revision &&
            OfflineTripSnapshots.current(mode.snapshot.owner) && ApiClient.isCurrentSession(mode.snapshot.owner.generation)
        OfflineActiveTripSnapshot(mode, ::currentOffline,
            onRetry = { if (currentOffline()) vm.requestBookingDestination(mode.snapshot.bookingId, mode.snapshot.owner.userId, completed = false) },
            onBack = { vm.navigateLocally(onBack) })
    } else {
        val ride = vm.selectedRide.value
        val booking = vm.activeBookingId.value
        val revision = vm.privateNavigationRevision
        val owner = ApiClient.myUserId()
        fun currentIdentity() = vm.offlineTripSnapshot.value == null && vm.selectedRide.value == ride &&
            vm.activeBookingId.value == booking && vm.privateNavigationRevision == revision && ApiClient.isCurrentSession(session) && ApiClient.myUserId() == owner
        fun currentLive() = currentIdentity() && vm.screen.value.contentScreen() == Screen.ActiveTrip
        // The same receipt transition may already own its destination before the root's queued ID fallback.
        // A different destination, identity or an already selected other receipt remains protected.
        fun currentReceipt(id: Int) = owner != null && id > 0 && id == booking && currentIdentity() &&
            (vm.screen.value.contentScreen() == Screen.ActiveTrip ||
                (vm.screen.value == Screen.TripReceipt && currentReceiptBookingId() in listOf(0, id)))
        ActiveTripScreen(ride = ride, contacts = contacts, bookingId = booking, terminalHint = terminalHint,
            chatOpen = chatOpen, onChatOpenChange = { if (currentLive()) vm.showTripChat(it) },
            onBack = { if (currentLive()) onBack() }, onTripEnd = { if (currentLive()) onTripEnd() },
            onDriverFinished = { bid -> if (currentLive()) vm.driverFinishedBooking(bid) else false },
            onSos = { if (currentLive()) onSos() }, onSupport = { if (currentLive()) onSupport() },
            onTrustedContacts = { if (currentLive()) onTrustedContacts() },
            onTripStopped = { if (currentLive()) onTripStopped() }, onOpenReceipt = { if (currentReceipt(it)) onOpenReceipt(it) })
    }
}
