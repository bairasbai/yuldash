package com.yuldash.app

import java.util.UUID

internal const val DRIVER_GPS_MAX_MS = 6 * 3600_000L
internal data class DriverGpsOwner(val origin: String, val user: Int, val session: Long, val process: String)
internal data class DriverGpsTicket(val id: String, val owner: DriverGpsOwner, val bookingId: Int,
    val untilElapsed: Long, val untilWall: Long)
internal data class DriverGpsLease(val ticket: DriverGpsTicket, val rideId: Int, val carrier: Int,
    val carrierUntil: Long = Long.MAX_VALUE, val paused: Boolean = false)

/** Process memory only. Neither an Intent nor a cached trip is permission to publish. */
internal class DriverGpsGrant {
    private var pending: DriverGpsTicket? = null
    private var lease: DriverGpsLease? = null
    private val revoked = mutableSetOf<String>()
    private var preciseSeen = false

    @Synchronized fun begin(owner: DriverGpsOwner, booking: Int, elapsed: Long, wall: Long, canonicalRide: Int = 0): DriverGpsTicket? {
        if (booking <= 0 || owner.user <= 0 || owner.origin.isBlank()) return null
        var existing = current(owner, elapsed, wall)
        if (existing != null && canonicalRide > 0 && existing.rideId != canonicalRide) { revoke(); existing = null }
        if (existing == null) preciseSeen = false
        val ticket = existing?.ticket?.copy(bookingId = booking) ?: DriverGpsTicket(UUID.randomUUID().toString(), owner, booking,
            elapsed + DRIVER_GPS_MAX_MS, wall + DRIVER_GPS_MAX_MS)
        pending = ticket
        return ticket
    }
    private fun expired(t: DriverGpsTicket, elapsed: Long, wall: Long) =
        elapsed >= t.untilElapsed || wall >= t.untilWall || t.untilElapsed - elapsed > DRIVER_GPS_MAX_MS

    @Synchronized fun complete(ticket: DriverGpsTicket, owner: DriverGpsOwner, ride: Int, carrier: Int,
        carrierUntil: Long, elapsed: Long, wall: Long): Boolean {
        if (pending !== ticket || ticket.owner != owner || ticket.id in revoked) return false
        if (expired(ticket, elapsed, wall)) { revoked += ticket.id; if (pending === ticket) pending = null; return false }
        if (ride <= 0 || carrier <= 0 || carrierUntil <= elapsed) return false
        if (lease?.let { it.ticket.id == ticket.id && it.rideId != ride } == true) return false
        pending = null
        val until = lease?.takeIf { it.ticket.id == ticket.id && it.carrier == carrier && it.carrierUntil > 0 }?.carrierUntil?.let { minOf(it, carrierUntil) } ?: carrierUntil
        lease = DriverGpsLease(ticket, ride, carrier, until)
        return true
    }
    @Synchronized fun fail(ticket: DriverGpsTicket) {
        if (pending !== ticket) return
        pending = null
        if (lease?.ticket?.id != ticket.id) revoked += ticket.id
    }
    /** Reference identity distinguishes simultaneous attempts that share an existing grant id. */
    @Synchronized fun applyPending(ticket: DriverGpsTicket, owner: DriverGpsOwner, elapsed: Long, wall: Long,
        apply: () -> Boolean): Boolean {
        if (pending !== ticket || ticket.owner != owner || ticket.id in revoked || expired(ticket, elapsed, wall)) return false
        return apply()
    }
    @Synchronized fun revokePending(ticket: DriverGpsTicket) {
        if (pending !== ticket) return
        pending = null
        revoked += ticket.id
        if (lease?.ticket?.id == ticket.id && lease?.ticket?.owner == ticket.owner) lease = null
    }
    @Synchronized fun permission(granted: Boolean) {
        if (!granted && preciseSeen) revoke()
        if (granted) preciseSeen = true
    }
    @Synchronized fun revoke() {
        pending?.let { revoked += it.id }
        lease?.let { revoked += it.ticket.id }
        pending = null; lease = null
    }
    @Synchronized fun current(owner: DriverGpsOwner, elapsed: Long, wall: Long): DriverGpsLease? {
        pending?.takeIf { it.owner != owner || expired(it, elapsed, wall) }?.let { revoked += it.id; pending = null }
        val live = lease ?: return null
        if (live.ticket.owner != owner || live.ticket.id in revoked || expired(live.ticket, elapsed, wall)) {
            revoke(); return null
        }
        return live
    }
    @Synchronized fun transfer(owner: DriverGpsOwner, ride: Int, carrier: Int, until: Long, elapsed: Long, wall: Long): Boolean {
        val live = current(owner, elapsed, wall) ?: return false
        if (live.rideId != ride || carrier <= 0 || until <= elapsed) return false
        lease = live.copy(carrier = carrier, carrierUntil = if (live.carrier == carrier) minOf(live.carrierUntil, until) else until, paused = false)
        return true
    }
    @Synchronized fun pauseCarrier(booking: Int) {
        lease?.takeIf { it.carrier == booking }?.let { lease = it.copy(paused = true) }
    }
    @Synchronized fun canPublish(owner: DriverGpsOwner, booking: Int, precise: Boolean, elapsed: Long, wall: Long): Boolean {
        permission(precise)
        val live = current(owner, elapsed, wall) ?: return false
        return precise && !live.paused && live.carrier == booking && elapsed < live.carrierUntil
    }
}
