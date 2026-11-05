package com.yuldash.app

import android.Manifest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import com.yuldash.app.data.TripLocationBus
import com.yuldash.app.data.ApiClient
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy

internal const val DRIVER_RIDE_LOOKUP_MS = 3_000L
internal const val RIDING_HEADER_KEY = "riding-header"

/** null means unknown, including an absent anchor or an incomplete feed without a live row. */
internal suspend fun driverRideHasLiveBookings(booking: Int, owner: Long,
    fetch: suspend (Long) -> Result<DriverBookingsFeed> = ::gpsDriverBookings): Boolean? {
    val feed = fetch(owner).getOrNull() ?: return null
    val ride = feed.rows.firstOrNull { it.bookingId == booking }?.rideId?.takeIf { it > 0 } ?: return null
    val live = feed.rows.any { it.bookingId != booking && it.rideId == ride && it.status in setOf("confirmed", "onboard") }
    return if (live) true else if (feed.partial) null else false
}

internal fun YuldashViewModel.returnToDriverRide() {
    navigateLocally {
        val closedTrail = setOf(Screen.ActiveTrip, Screen.TripChat, Screen.DriverCabinet)
        while (navHistory.lastOrNull() in closedTrail) navHistory.removeAt(navHistory.lastIndex)
        activeTrip.value = null
        focusRidingSection.value = true
        navPopping.value = true
        screen.value = Screen.DriverCabinet
    }
}

internal suspend fun YuldashViewModel.driverFinishedBooking(booking: Int): Boolean = finishDriverRideBooking(booking, ::gpsDriverBookings)

internal suspend fun YuldashViewModel.finishDriverRideBooking(booking: Int,
    fetch: suspend (Long) -> Result<DriverBookingsFeed>): Boolean {
    val owner = ApiClient.queueSessionGeneration()
    val revision = privateNavigationRevision
    val lookup = viewModelScope.async { driverRideHasLiveBookings(booking, owner, fetch) }
    val remains = try { withTimeoutOrNull(DRIVER_RIDE_LOOKUP_MS) { lookup.await() } } finally { lookup.cancel() }
    if (!ApiClient.isCurrentSession(owner) || privateNavigationRevision != revision ||
        activeBookingId.value != booking || !isDriverOf(booking) || screen.value.contentScreen() != Screen.ActiveTrip) return true
    if (remains == false) { driverRideOverBooking.value = booking; return false }
    // Network/partial uncertainty returns to a reloadable cabinet, never to the closed booking.
    returnToDriverRide()
    return true
}

internal fun YuldashViewModel.endTripScreen(booking: Int?, fallback: () -> Unit) {
    activeTrip.value = null
    if (booking == null || !isDriverOf(booking) || driverRideOverBooking.value == booking) { fallback(); return }
    viewModelScope.launch { if (!driverFinishedBooking(booking)) fallback() }
}

@Composable internal fun rememberRidingFocusList(focus: Boolean, ready: Boolean, onDone: () -> Unit): LazyListState {
    val state = rememberLazyListState()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(focus, ready) {
        if (!focus || !ready) return@LaunchedEffect
        repeat(30) {
            withFrameNanos { }
            val header = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == RIDING_HEADER_KEY }
            if (header != null) { state.animateScrollToItem(header.index); done(); return@LaunchedEffect }
            if (!state.canScrollForward) { done(); return@LaunchedEffect }
            state.animateScrollBy(state.layoutInfo.viewportSize.height * 0.8f, tween(CanonMotion.QUICK))
        }
        done()
    }
    return state
}

/** Sharing can be resumed without repeating boarding or another business status change. */
@Composable internal fun DriverRideShareButton(bookingId: Int, rideId: Int) {
    val context = LocalContext.current
    val action = rememberDriverGpsAction(bookingId)
    val scope = rememberCoroutineScope()
    var busy by remember(bookingId) { mutableStateOf(false) }
    var failed by remember(bookingId) { mutableStateOf(false) }
    var pending by remember(bookingId) { mutableStateOf<DriverGpsTicket?>(null) }
    val revision = DriverGpsActions.revision
    val sharing = revision.let { TripLocationBus.rideShareActive && DriverGpsActions.current()?.let { it.rideId == rideId && DriverGpsActions.precise(context) } == true }
    fun activate(ticket: DriverGpsTicket?) {
        scope.launch {
            try { failed = !action.finish(ticket, true) }
            finally { busy = false; pending = null }
        }
    }
    fun permissionResult(granted: Boolean) {
        DriverGpsActions.permission(granted)
        if (granted && action.live) activate(pending)
        else { DriverGpsActions.fail(pending); pending = null; busy = false; failed = true }
    }
    val permission = rememberPermissionGate(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        titleRu = "Показывать машину пассажирам",
        titleBa = "Машинаны пассажирҙарға күрһәтергә",
        whyRu = "Передавай положение машины пассажирам этого рейса, пока везёшь их.",
        whyBa = "Юлсыларҙы алып барған саҡта машинаның урынын ошо рейс пассажирҙарына күрһәт.",
        onGranted = { permissionResult(true) },
        onDenied = { permissionResult(false) },
    )
    val currentPending by rememberUpdatedState(pending)
    DisposableEffect(action) { onDispose { DriverGpsActions.fail(currentPending) } }
    DriverRideShareContent(sharing, busy, failed) {
        if (!busy) {
            busy = true; failed = false
            pending = action.begin(bookingId, rideId) // Canonical DTO ride, never the screen's stub Ride.id.
            if (pending == null) { busy = false; failed = true }
            else if (DriverGpsActions.precise(context)) activate(pending)
            else permission()
        }
    }
}

@Composable internal fun DriverRideShareContent(sharing: Boolean, busy: Boolean, failed: Boolean, onEnable: () -> Unit) {
    AnimatedContent(sharing, transitionSpec = { fadeIn(tween(CanonMotion.QUICK)) togetherWith fadeOut(tween(CanonMotion.QUICK)) }, label = "driver-gps-sharing") { active ->
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            if (active) Text(appText("Машину видят пассажиры рейса", "Машинаны рейс пассажирҙары күрә"), style = CanonCaption, color = CanonMuted)
            else {
                Text(appText("Передавай положение машины пассажирам этого рейса, пока везёшь их.",
                    "Юлсыларҙы алып барған саҡта машинаның урынын ошо рейс пассажирҙарына күрһәт."), style = CanonCaption, color = CanonMuted)
                if (failed) Text(appText("Не получилось включить передачу. Проверь точный геодоступ и сеть, затем повтори.",
                    "Урынды күрһәтеп булманы. Теүәл георөхсәтте һәм селтәрҙе тикшер ҙә ҡабатла."), style = CanonCaption, color = CanonRed)
                AppButton(appText("Показывать машину пассажирам", "Машинаны пассажирҙарға күрһәтергә"), onClick = onEnable,
                    loading = busy, style = AppButtonStyle.Secondary)
            }
        }
    }
}
