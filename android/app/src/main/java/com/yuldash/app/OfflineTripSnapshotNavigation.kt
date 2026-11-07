package com.yuldash.app

import androidx.compose.runtime.*
import com.yuldash.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

internal fun YuldashViewModel.openOfflineTrip(saved: OfflineTripSnapshot, revision: Long, cold: ColdTripPassport? = null) {
    if (privateNavigationRevision != revision || !OfflineTripSnapshots.current(saved.owner)) return
    selectedRide.value = bookingLinkStubRide(saved.bookingId)
    activeBookingId.value = saved.bookingId
    activeTrip.value = null; activeDriverBookingId.value = null
    offlineTripSnapshot.value = OfflineTripSnapshotMode(saved, revision, cold)
    screen.value = Screen.ActiveTrip
}

internal suspend fun restoreBookingSnapshotOrStub(
    vm: YuldashViewModel, bookingId: Int?, requested: Screen, session: Long, restoreScope: CoroutineScope,
    onRestored: (TripStateDto?) -> Unit,
) {
    fun isCurrentRestore() = restoreScope.isActive && vm.screen.value == requested &&
        vm.activeBookingId.value == bookingId && vm.selectedRide.value == null && vm.activeTrip.value == null && vm.offlineTripSnapshot.value == null &&
        ApiClient.isCurrentSession(session)
    var restored = false
    if (bookingId != null) restored = restoreBookingSnapshotNavigation(vm, bookingId, requested,
        vm.privateNavigationRevision, session, ::isCurrentRestore, onRestored)
    if (!restored && isCurrentRestore()) {
        if (bookingId != null) vm.selectedRide.value = bookingLinkStubRide(bookingId) else vm.screen.value = Screen.Home
    }
}

internal suspend fun restoreBookingSnapshotNavigation(
    vm: YuldashViewModel, bookingId: Int, requested: Screen, revision: Long, session: Long,
    isCurrent: () -> Boolean, onRestored: (TripStateDto?) -> Unit,
): Boolean {
    val read = readBookingNavigation(bookingId)
    if (!isCurrent()) return false
    var restored = false
    ApiClient.runIfCurrentSession(session) {
        if (!isCurrent() || vm.privateNavigationRevision != revision) return@runIfCurrentSession
        val state = read.live.getOrNull()
        if (state != null && (state.status !in setOf("confirmed", "onboard") || OfflineTripSnapshots.allowsLive(bookingId))) {
            vm.offlineTripSnapshot.value = null
            val target = vm.applyBookingLink(bookingId, state.role, state.status, false, state.cancelledInTransit)
            if (requested.contentScreen() == Screen.ActiveTrip && target == Screen.Booking && state.role == "passenger" &&
                bookingStatusAllowsActiveTrip(state.status)) vm.screen.value = requested
            onRestored(state); restored = true
        } else read.saved?.let { saved -> vm.openOfflineTrip(saved, revision, read.cold); restored = true }
    }
    return restored
}

/** One mount boundary for every active/chat entry. Saved screen/ride/TripPass are only candidates. */
@Composable internal fun GuardedActiveTripDestination(
    vm: YuldashViewModel, session: Long, contacts: List<TrustedContact>, terminalHint: ActiveTripTerminalHint?, chatOpen: Boolean?,
    onBack: () -> Unit, onTripEnd: () -> Unit, onSos: () -> Unit, onSupport: () -> Unit,
    onTrustedContacts: () -> Unit, onTripStopped: () -> Unit, onOpenReceipt: (Int) -> Unit, currentReceiptBookingId: () -> Int,
) {
    val owner by OfflineTripSnapshots.owner.collectAsState()
    val version by OfflineTripSnapshots.version.collectAsState()
    val booking = vm.activeBookingId.value
    val revision by vm.privateNavigationRevisions.collectAsState()
    val pending = vm.pendingBookingNavigation.value != null || vm.pendingScreenNavigation.value != null || vm.pendingPublicRideNavigation.value != null
    val ride = vm.selectedRide.value
    val initialTerminalContext = remember(owner, booking, session) { ride to revision }
    var confirmedTerminalContext by remember(owner, booking, session, ride, revision) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var retry by remember(owner, booking, revision) { mutableIntStateOf(0) }
    var loading by remember(owner, booking, revision) { mutableStateOf(true) }
    var failure by remember(owner, booking, revision) { mutableStateOf<Throwable?>(null) }
    var unavailable by remember(owner, booking, revision) { mutableStateOf(false) }
    fun currentIdentity(): Boolean = scope.isActive && !pending && vm.pendingBookingNavigation.value == null &&
        vm.pendingScreenNavigation.value == null && vm.pendingPublicRideNavigation.value == null &&
        vm.activeBookingId.value == booking && vm.selectedRide.value == ride && vm.privateNavigationRevision == revision &&
        ApiClient.isCurrentSession(session) && OfflineTripSnapshots.currentOwner() == owner
    fun currentLive() = currentIdentity() && vm.screen.value.contentScreen() == Screen.ActiveTrip
    fun currentReceipt(id: Int) = id > 0 && id == booking && currentIdentity() &&
        (vm.screen.value.contentScreen() == Screen.ActiveTrip ||
            (vm.screen.value == Screen.TripReceipt && currentReceiptBookingId() in listOf(0, id)))
    LaunchedEffect(owner, booking, ride, revision, pending, retry) {
        val id = booking ?: return@LaunchedEffect
        if (!currentLive()) return@LaunchedEffect
        loading = true; failure = null; unavailable = false
        val read = readBookingNavigation(id)
        if (!currentLive()) return@LaunchedEffect
        val state = read.live.getOrNull()
        val error = read.live.exceptionOrNull()
        if (state?.status in setOf("done", "cancelled")) confirmedTerminalContext = true
        if (state != null && state.status == "pending") {
            vm.applyBookingLink(id, state.role, state.status, false); loading = false; return@LaunchedEffect
        }
        if (read.saved != null) {
            vm.openOfflineTrip(read.saved, revision); unavailable = true
        } else if (error == null || error is java.io.IOException || (error is ApiException && error.status in 500..599)) {
            val details = ApiClient.getBookingDetails(id)
            if (!currentLive()) return@LaunchedEffect
            failure = details.exceptionOrNull() ?: error
            if (details.getOrNull()?.status in setOf("done", "cancelled")) confirmedTerminalContext = true
        } else failure = error
        if (read.live.isSuccess) vm.offlineTripSnapshot.value = null
        if (OfflineTripSnapshots.allowsLive(id) && !unavailable) {
            vm.activeTrip.value = vm.selectedRide.value.currentBookingMapRide(id)
            vm.activeDriverBookingId.value = id.takeIf { state?.role == "driver" || OfflineTripSnapshots.projection(id)?.details?.role == "driver" }
        } else if (OfflineTripSnapshots.terminal(id) == null) {
            vm.activeTrip.value = null
            if (failure == null && vm.offlineTripSnapshot.value == null) failure = IllegalStateException("Fresh trip authority unavailable")
        }
        loading = false
    }
    val mode = vm.offlineTripSnapshot.value
    LaunchedEffect(version, ride, booking, mode, loading) {
        if (currentLive() && !loading && mode == null && booking != null && OfflineTripSnapshots.allowsLive(booking)) vm.activeTrip.value = ride.currentBookingMapRide(booking)
    }
    val requestedFallback = booking?.let(OfflineTripSnapshots::warmFallback)
    LaunchedEffect(version, requestedFallback, revision) {
        if (requestedFallback != null && currentLive() && vm.offlineTripSnapshot.value == null) vm.openOfflineTrip(requestedFallback, revision)
    }
    if (mode != null && mode.snapshot.bookingId == booking) {
        OfflineActiveTripSnapshot(mode, { currentLive() && vm.offlineTripSnapshot.value === mode && mode.revision == revision },
            onRetry = { if (currentLive()) { OfflineTripSnapshots.dismissFallback(mode.snapshot.bookingId); vm.offlineTripSnapshot.value = null; retry++ } },
            onBack = { if (currentLive()) vm.navigateLocally(onBack) })
        return
    }
    val observedTerminal = booking?.let(OfflineTripSnapshots::terminal)
    val terminal = observedTerminal?.takeIf {
        confirmedTerminalContext || initialTerminalContext == (ride to revision)
    }
    // A terminal from another navigation publication needs one fresh read in THIS destination.
    var terminalProbeStarted by remember(observedTerminal, ride, revision) { mutableStateOf(false) }
    LaunchedEffect(observedTerminal, ride, revision, loading) {
        if (observedTerminal != null && terminal == null && !terminalProbeStarted && currentLive() && !loading) {
            terminalProbeStarted = true
            retry++
        }
    }
    val live = booking == null || version.let { currentLive() && !loading && !unavailable && OfflineTripSnapshots.allowsLive(booking) }
    if (!live && terminal == null) {
        ActiveTripUnverifiedScreen(loading, failure ?: IllegalStateException("Fresh trip authority unavailable"),
            onRetry = {
                if (currentLive() && !loading && booking != null && !OfflineTripSnapshots.allowsLive(booking)) {
                    loading = true
                    retry++
                }
            }, onBack = { if (currentLive()) onBack() })
        return
    }
    val capturedTerminal = terminal
    fun canDraw() = currentLive() && (if (capturedTerminal != null) OfflineTripSnapshots.terminal(requireNotNull(booking)) == capturedTerminal
        else booking == null || OfflineTripSnapshots.allowsLive(booking))
    key("active-trip-private-saveable-v1", owner, booking, session, ride, revision) {
        // Old flat values are not reinterpreted in this versioned private namespace.
        val contentStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        TripAuthorityContent(::canDraw) {
            contentStates.SaveableStateProvider("active-trip") {
                ActiveTripScreen(ride = ride, contacts = contacts, bookingId = booking,
                    isCurrentParent = ::currentLive,
                    isCurrentReceiptParent = { booking?.let(::currentReceipt) == true },
                    terminalHint = terminal?.let { ActiveTripTerminalHint(requireNotNull(booking), it.status, it.role, it.inTransit) } ?: terminalHint,
                    chatOpen = chatOpen, onChatOpenChange = { if (currentLive()) vm.showTripChat(it) },
                    onBack = { if (currentLive()) onBack() }, onTripEnd = { if (currentLive()) onTripEnd() },
                    onDriverFinished = { id -> if (currentLive()) vm.driverFinishedBooking(id) else false },
                    onSos = { if (currentLive()) onSos() }, onSupport = { if (currentLive()) onSupport() },
                    onTrustedContacts = { if (currentLive()) onTrustedContacts() }, onTripStopped = { if (currentLive()) onTripStopped() },
                    onOpenReceipt = { if (currentReceipt(it)) onOpenReceipt(it) })
            }
        }
    }
}
