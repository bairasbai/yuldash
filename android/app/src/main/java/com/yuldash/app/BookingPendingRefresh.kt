package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.BookingDetailsDto
import kotlinx.coroutines.delay

/** Keep a pending booking fresh only while the screen is visible. */
@Composable
internal fun BookingPendingRefresh(
    bookingId: Int?, pending: Boolean, detailsStatus: String?, session: Long,
    onDetails: (BookingDetailsDto) -> Unit, onChanged: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestStatus by rememberUpdatedState(detailsStatus)
    val latestChanged by rememberUpdatedState(onChanged)
    val latestDetails by rememberUpdatedState(onDetails)
    LaunchedEffect(bookingId, pending, lifecycleOwner, session) {
        val id = bookingId?.takeIf { pending } ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (session == ApiClient.queueSessionGeneration()) {
                delay(PENDING_BOOKING_REFRESH_MS)
                if (session != ApiClient.queueSessionGeneration()) return@repeatOnLifecycle
                val fresh = ApiClient.getBookingDetails(id).getOrNull() ?: continue
                if (session != ApiClient.queueSessionGeneration()) return@repeatOnLifecycle
                if (fresh.status != latestStatus) latestChanged() else latestDetails(fresh)
            }
        }
    }
}
