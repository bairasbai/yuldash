package com.yuldash.app

/** Only choices published by the server; never infer the place from a search string. */
internal fun pickupStops(ride: Ride): List<String> = ride.waypoints.map(String::trim)
    .filter { it.isNotEmpty() && !it.equals(ride.from.trim(), true) && !it.equals(ride.to.trim(), true) }
    .distinctBy { it.lowercase(java.util.Locale.ROOT) }

/** Start is sent by name: an empty request means unknown to the server on a route with stops. */
internal fun initialPickupStop(ride: Ride): String? {
    val stops = pickupStops(ride)
    if (stops.isEmpty()) return null
    return when {
        ride.matchedStop in stops -> ride.matchedStop
        ride.matchedFromStart -> ride.from
        else -> null
    }
}

internal fun pickupStopReady(ride: Ride, picked: String?): Boolean =
    pickupStops(ride).isEmpty() || picked == ride.from || picked in pickupStops(ride)

@androidx.compose.runtime.Composable
internal fun bookingTripRide(b: com.yuldash.app.data.BookingMineDto, feed: Ride?): Ride {
    return Ride(
        id = b.id.toString(),   // id = booking_id → onOpenActiveTrip получит верный booking
        from = b.fromCity.ifBlank { feed?.from ?: appText("Поездка", "Сәфәр") },
        to = b.toCity.ifBlank { feed?.to ?: "№${b.rideId}" },
        time = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.time ?: ""),
        timeBa = b.departAt.takeIf { it.isNotBlank() }?.let(::formatDepart) ?: (feed?.timeBa ?: feed?.time ?: ""),
        driver = b.driverName.ifBlank { feed?.driver ?: "" },
        car = feed?.car ?: "",
        carBa = feed?.carBa ?: feed?.car ?: "",
        price = if (b.price > 0) b.price else (feed?.price ?: 0),
        seats = b.seats,
        rating = feed?.rating ?: 0.0,
        verified = b.driverVerified || (feed?.verified ?: false),
        boosted = false
    )
}
