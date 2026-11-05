package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.parseDriverBookingDto
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal data class DriverBookingsFeed(val rows: List<DriverBookingDto>, val partial: Boolean)

/** Keep the HTTP partial marker: absence in a capped feed is not proof of the last passenger. */
internal suspend fun gpsDriverBookings(owner: Long): Result<DriverBookingsFeed> =
    ApiClient.authorized("GET", "/driver/bookings", null, owner).mapCatching { obj ->
        val items = obj.getJSONArray("items")
        DriverBookingsFeed((0 until items.length()).map { parseDriverBookingDto(items.getJSONObject(it)) },
            obj.optBoolean("_partial", false))
    }

internal object DriverGpsActions {
    private val process = UUID.randomUUID().toString()
    val grant = DriverGpsGrant()
    var revision by mutableIntStateOf(0)
        private set
    private var lastPrecise: Boolean? = null
    fun owner() = DriverGpsOwner(ApiClient.apiBase(), ApiClient.myUserId() ?: 0, ApiClient.queueSessionGeneration(), process)
    fun precise(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun begin(booking: Int, canonicalRide: Int = 0): DriverGpsTicket? = grant.begin(owner(), booking, SystemClock.elapsedRealtime(), System.currentTimeMillis(), canonicalRide)
    fun current() = grant.current(owner(), SystemClock.elapsedRealtime(), System.currentTimeMillis())
    fun allowed(context: Context, booking: Int) = grant.canPublish(owner(), booking, precise(context), SystemClock.elapsedRealtime(), System.currentTimeMillis())
    fun permission(granted: Boolean) {
        grant.permission(granted)
        if (lastPrecise != granted) { lastPrecise = granted; revision++ }
    }
    fun revoke() { grant.revoke(); revision++ }
    fun fail(ticket: DriverGpsTicket?) { if (ticket == null) return; grant.fail(ticket); revision++ }
    fun closed(booking: Int) { grant.pauseCarrier(booking); TripLocationBus.driverBookingClosed?.invoke(booking) }
    fun rideClosed(ride: Int) { if (current()?.rideId == ride) revoke() }

    suspend fun complete(ticket: DriverGpsTicket?, context: Context, language: AppLanguage,
        isCurrent: () -> Boolean, closed: Boolean = false,
        fetch: suspend (Long) -> Result<DriverBookingsFeed> = ::gpsDriverBookings,
        start: (Int) -> Unit = { TripLocationService.start(context, it, language, true) }): Boolean {
        ticket ?: return false
        try {
            if (!isCurrent() || ticket.owner != owner() || !precise(context)) { fail(ticket); return false }
            if (!grant.applyPending(ticket, owner(), SystemClock.elapsedRealtime(), System.currentTimeMillis()) {
                    if (closed) this.closed(ticket.bookingId)
                    true
                }) return false
            val requestedAt = SystemClock.elapsedRealtime()
            val feed = fetch(ticket.owner.session).getOrElse { fail(ticket); return false }
            if (!isCurrent() || ticket.owner != owner() || !precise(context)) { fail(ticket); return false }
            val now = SystemClock.elapsedRealtime()
            return grant.applyPending(ticket, owner(), now, System.currentTimeMillis()) {
                val ride = feed.rows.firstOrNull { it.bookingId == ticket.bookingId }?.rideId
                    ?: current()?.takeIf { it.ticket.id == ticket.id }?.rideId ?: 0
                val pick = pickRideCarrier(feed.rows, ride, if (closed) setOf(ticket.bookingId) else emptySet())
                val carrier = pick.carrier
                if (carrier == null) {
                    if (feed.partial) grant.fail(ticket) else grant.revokePending(ticket)
                    revision++
                    false
                } else if (!grant.complete(ticket, owner(), ride, carrier,
                        pick.carrierTtlSec?.let { requestedAt + it * 1000L } ?: Long.MAX_VALUE, now, System.currentTimeMillis())) {
                    fail(ticket)
                    false
                } else {
                    revision++
                    start(carrier)
                    true
                }
            }
        } catch (cancelled: CancellationException) { fail(ticket); throw cancelled }
    }
}

internal class LiveDriverGpsAction(private val context: Context, private val language: AppLanguage) {
    var live = true
    fun begin(booking: Int, canonicalRide: Int = 0) = if (live) DriverGpsActions.begin(booking, canonicalRide) else null
    suspend fun finish(ticket: DriverGpsTicket?, success: Boolean, closed: Boolean = false): Boolean {
        if (!success || !live) { DriverGpsActions.fail(ticket); return false }
        return DriverGpsActions.complete(ticket, context, language, { live }, closed)
    }
}

@Composable internal fun rememberDriverGpsAction(booking: Int?): LiveDriverGpsAction {
    val context = LocalContext.current.applicationContext
    val language = LocalAppLanguage.current
    val action = remember(booking, language) { LiveDriverGpsAction(context, language) }
    DisposableEffect(action) { onDispose { action.live = false } }
    return action
}
