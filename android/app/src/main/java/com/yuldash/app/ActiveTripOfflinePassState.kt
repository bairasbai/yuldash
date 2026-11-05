package com.yuldash.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.*

@Stable internal class ActiveTripOfflinePassState {
    var pass by mutableStateOf<TripPass?>(null)
    var failed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var retry by mutableIntStateOf(0)
}

/** Extracted passport effect. A failed write cannot substitute a previous private blob. */
@Composable internal fun rememberActiveTripOfflinePass(
    bookingId: Int?, session: Long, code: String, terminal: String?, role: String,
    details: BookingDetailsDto?, status: String, isCurrent: () -> Boolean,
): ActiveTripOfflinePassState {
    val context = LocalContext.current
    val state = remember(bookingId, session) { ActiveTripOfflinePassState() }
    val version by OfflineTripSnapshots.version.collectAsState()
    val current by rememberUpdatedState(isCurrent)
    LaunchedEffect(bookingId, code, state.retry, terminal, role, details, status, version) {
        val id = bookingId ?: return@LaunchedEffect
        state.pass = TripPassStore.load(context, id)
        val actual = OfflineTripSnapshots.projection(id)?.details
        val passDetails = offlinePassDetails(actual, id, status)
        val currentCode = OfflineTripSnapshots.currentBoardingCode(id)
        if (code.isBlank() || terminal != null || !current() || role == "driver" || passDetails == null ||
            currentCode.isNullOrBlank() || currentCode != code) {
            // A new DETAILS is waiting for its own CODE; no disk write has failed.
            state.failed = false; return@LaunchedEffect
        }
        state.busy = true
        try {
            val saved = saveActiveTripBoardingCode(context, id, code, passDetails, session, state.retry > 0) {
                current() && OfflineTripSnapshots.privateDetails(passDetails)
            }
            if (!current() || terminal != null) return@LaunchedEffect
            state.failed = !saved
            state.pass = TripPassStore.load(context, id)
        } finally { state.busy = false }
    }
    return state
}

/** Both pixels and accessibility are denied; retained actions must also check this same predicate. */
@Suppress("DEPRECATION")
internal fun Modifier.tripAuthorityDraw(current: () -> Boolean): Modifier =
    drawWithContent { if (current()) drawContent() }.semantics { if (!current()) invisibleToUser() }

@Composable internal fun TripAuthorityContent(current: () -> Boolean, content: @Composable () -> Unit) {
    val version by OfflineTripSnapshots.version.collectAsState()
    if (version.let { current() }) Box(Modifier.tripAuthorityDraw(current)) { content() }
}

internal fun currentTripPrivateDetails(bookingId: Int?): BookingDetailsDto? = bookingId?.let {
    OfflineTripSnapshots.projection(it)?.details?.takeIf(OfflineTripSnapshots::privateDetails)
}
internal fun currentOwnBookingDetails(value: BookingDetailsDto?): BookingDetailsDto? = value?.takeIf {
    OfflineTripSnapshots.freshDetails(it) && (it.role == "passenger" || OfflineTripSnapshots.privateDetails(it))
}
internal fun currentTripCode(bookingId: Int?, code: String): String = bookingId?.let {
    OfflineTripSnapshots.currentBoardingCode(it)?.takeIf { actual -> actual == code }
}.orEmpty()

internal fun currentBookingParentDetails(value: BookingDetailsDto?, bookingId: Int?, ride: Ride?): BookingDetailsDto? =
    value?.takeIf {
        it.bookingId == bookingId && ride.matchesPrivateBookingRide(it.bookingId, it.rideId) &&
            (OfflineTripSnapshots.freshDetails(it) || OfflineTripSnapshots.terminal(it.bookingId) != null)
    }
