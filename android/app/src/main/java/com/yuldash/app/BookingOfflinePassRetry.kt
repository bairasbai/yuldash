package com.yuldash.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import com.yuldash.app.data.OfflineTripSnapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive

/** Only an explicit save retry may reacquire lost participant details before another code GET. */
@Composable
internal fun BookingOfflinePassRetry(
    context: Context,
    bookingId: Int?,
    ride: Ride,
    details: BookingDetailsDto?,
    session: Long,
    retry: Int,
    isCurrentParent: () -> Boolean,
    onDetails: (BookingDetailsDto) -> Unit,
    onDetailsFailed: () -> Unit,
    onSaveFailed: (Boolean) -> Unit,
    onBusy: (Boolean) -> Unit,
) {
    val owner = remember(bookingId, session) { OfflineTripSnapshots.currentOwner() }
    val currentParent by rememberUpdatedState(isCurrentParent)
    val currentRide by rememberUpdatedState(ride)
    val currentSession by rememberUpdatedState(session)
    val currentDetails by rememberUpdatedState(details)
    val publishDetails by rememberUpdatedState(onDetails)
    val publishDetailsFailure by rememberUpdatedState(onDetailsFailed)
    val publishSaveFailure by rememberUpdatedState(onSaveFailed)
    val publishBusy by rememberUpdatedState(onBusy)
    LaunchedEffect(bookingId, retry) {
        if (retry == 0) return@LaunchedEffect
        val requestScope = this
        fun current() = requestScope.isActive && owner != null &&
            currentRide == ride && currentSession == session && ApiClient.isCurrentSession(session) &&
            OfflineTripSnapshots.currentOwner() == owner && currentParent()
        publishBusy(true)
        try {
            if (!current()) return@LaunchedEffect
            val id = bookingId ?: return@LaunchedEffect
            val existing = currentDetails?.takeIf(OfflineTripSnapshots::privateDetails)
            val fetched = existing == null
            val loaded = existing ?: run {
                val result = ApiClient.getBookingDetails(id)
                if (!current()) return@LaunchedEffect
                result.getOrElse { error ->
                    if (error is CancellationException) throw error
                    publishDetailsFailure()
                    publishSaveFailure(true)
                    return@LaunchedEffect
                }
            }
            if (!current()) return@LaunchedEffect
            if (loaded.bookingId != id || !ride.matchesPrivateBookingRide(id, loaded.rideId) ||
                loaded.role != "passenger" || !OfflineTripSnapshots.freshDetails(loaded)) {
                publishDetailsFailure()
                publishSaveFailure(true)
                return@LaunchedEffect
            }
            // Pending, terminal or still-locked own details may update the screen, never fetch a code.
            if (!loaded.contactUnlocked || loaded.status !in CONTACT_STATUSES) {
                publishSaveFailure(false)
                if (fetched) publishDetails(loaded)
                return@LaunchedEffect
            }
            if (!OfflineTripSnapshots.privateDetails(loaded)) {
                publishSaveFailure(true)
                return@LaunchedEffect
            }
            val saved = saveTripPass(context, loaded, session, retryMigration = true, shouldContinue = ::current)
            if (!current()) return@LaunchedEffect
            publishSaveFailure(!saved)
            // A failed code read can revoke this new DTO too. Never republish an invalidated status.
            if (fetched && OfflineTripSnapshots.freshDetails(loaded)) publishDetails(loaded)
        } finally {
            publishBusy(false)
        }
    }
}
