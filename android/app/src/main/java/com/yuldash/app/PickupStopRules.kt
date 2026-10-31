package com.yuldash.app

/** Only choices published by the server; never infer the place from a search string. */
internal fun pickupStops(ride: Ride): List<String> = ride.waypoints.map(String::trim)
    .filter { it.isNotEmpty() && !it.equals(ride.from.trim(), true) && !it.equals(ride.to.trim(), true) }
    .distinctBy { it.lowercase(java.util.Locale.ROOT) }

/** Start is sent by name: an empty request means unknown to the server on a route with stops. */
internal fun initialPickupStop(ride: Ride): String? {
    val stops = pickupStops(ride)
    if (stops.isEmpty()) return ride.from
    return when {
        ride.matchedStop in stops -> ride.matchedStop
        ride.matchedFromStart -> ride.from
        else -> null
    }
}

internal fun pickupStopReady(ride: Ride, picked: String?): Boolean =
    pickupStops(ride).isEmpty() || picked == ride.from || picked in pickupStops(ride)

/** The route is ordered; a drop-off is always strictly after boarding. */
internal fun routePlaces(ride: Ride): List<String> = listOf(ride.from) + pickupStops(ride) + ride.to

internal fun dropoffStops(ride: Ride, pickup: String?): List<String> {
    val route = routePlaces(ride)
    val index = route.indexOf(pickup)
    return if (index in 0 until route.lastIndex) route.drop(index + 1) else emptyList()
}

internal fun initialDropoffStop(ride: Ride): String? = when {
    pickupStops(ride).isEmpty() -> ride.to
    ride.matchedDropoffStop in pickupStops(ride) -> ride.matchedDropoffStop
    ride.matchedToEnd -> ride.to
    else -> null
}.takeIf { it in dropoffStops(ride, initialPickupStop(ride)) }

internal fun segmentReady(ride: Ride, pickup: String?, dropoff: String?): Boolean =
    pickup != null && pickupStopReady(ride, pickup) && dropoff != null && dropoff in dropoffStops(ride, pickup)

internal fun keepValidDropoff(ride: Ride, pickup: String?, dropoff: String?): String? =
    dropoff?.takeIf { it in dropoffStops(ride, pickup) }

/** RS-02 Б: цена участка в целых рублях по формуле сервера (`ride_seats.segment_price`):
 *  цен остановок нет → полная; иначе cum(высадка) − cum(посадка), где cum(начало)=0,
 *  cum(конец)=полная цена, остановка — её цена «до неё». null — маршрут не собран (не выдумываем). */
internal fun segmentPriceRub(ride: Ride, pickup: String?, dropoff: String?): Int? {
    val stops = pickupStops(ride)
    if (ride.stopPrices.isEmpty() || ride.stopPrices.size != stops.size) {
        return ride.price.takeIf { it > 0 }
    }
    val route = routePlaces(ride)   // [начало, ...остановки, конец]
    val a = pickup?.let { route.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    val b = dropoff?.let { route.indexOf(it) }?.takeIf { it > a } ?: route.lastIndex
    if (b <= a) return null
    fun cum(point: Int): Int = when {
        point <= 0 -> 0
        point >= route.lastIndex -> ride.price
        else -> ride.stopPrices[point - 1].price
    }
    return cum(b) - cum(a)
}

/** RS-02 А: свободно на участке — min по его звеньям из `seatsByLeg`; список не сошёлся
 *  по длине или участок не собран — null (смотреть общий остаток). */
internal fun seatsOnSpan(ride: Ride, pickup: String?, dropoff: String?): Int? {
    if (ride.seatsByLeg.isEmpty()) return null
    val route = routePlaces(ride)
    if (ride.seatsByLeg.size != route.lastIndex) return null
    val a = pickup?.let { route.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    val b = dropoff?.let { route.indexOf(it) }?.takeIf { it > a } ?: route.lastIndex
    if (b <= a) return null
    return ride.seatsByLeg.subList(a, b).minOrNull()
}

/** Only server hints on the same ride survive an unfiltered details fetch. */
internal fun Ride.withRouteHints(previous: Ride?): Ride = if (previous?.id != id) this else copy(
    matchedStop = matchedStop ?: previous.matchedStop, matchedFromStart = matchedFromStart || previous.matchedFromStart,
    matchedDropoffStop = matchedDropoffStop ?: previous.matchedDropoffStop, matchedToEnd = matchedToEnd || previous.matchedToEnd,
)

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
