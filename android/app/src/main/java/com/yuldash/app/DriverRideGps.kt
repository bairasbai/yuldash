package com.yuldash.app

import com.yuldash.app.data.DriverBookingDto

internal data class RideCarrierPick(val rideId: Int, val carrier: Int?, val carrierTtlSec: Int?, val liveBookings: Set<Int>)

/** Canonical ride comes from a fresh server row, never from a booking-shaped UI Ride. */
internal fun pickRideCarrier(rows: List<DriverBookingDto>, rideId: Int, closed: Set<Int>): RideCarrierPick {
    val live = rows.filter { it.rideId == rideId && it.bookingId !in closed && it.status in setOf("confirmed", "onboard") }
        .sortedBy { it.bookingId }
    val boarded = live.firstOrNull { it.status == "onboard" }
    val stop = live.firstOrNull { !it.pickupStop.isNullOrEmpty() }
    val start = live.firstOrNull { it.noShowWaitSec > 60 }
    val carrier = boarded ?: stop ?: start
    return RideCarrierPick(rideId, carrier?.bookingId,
        if (boarded == null && stop == null) carrier?.noShowWaitSec else null, live.map { it.bookingId }.toSet())
}
