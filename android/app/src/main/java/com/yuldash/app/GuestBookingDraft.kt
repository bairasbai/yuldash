package com.yuldash.app

/** Only public choices travel through sign-in. Contacts and exact points belong to the signed-in form. */
internal data class GuestBookingDraft(
    val rideId: Int,
    val termsVersion: Long,
    val route: List<String>,
    val pickup: String?,
    val dropoff: String?,
    val seats: Int,
    val front: Boolean,
    val minor: Boolean,
) {
    fun restore(ride: Ride): RestoredGuestBooking {
        val validSegment = segmentReady(ride, pickup, dropoff)
        val frontUnavailable = front && (!ride.frontSeatAvailable || ride.frontSeatTaken)
        val minorsUnavailable = minor && ride.noMinors
        return RestoredGuestBooking(
            pickup = if (validSegment) pickup else initialPickupStop(ride),
            dropoff = if (validSegment) dropoff else initialDropoffStop(ride),
            seats = seats,
            front = front && !frontUnavailable,
            minor = minor && !minorsUnavailable,
            review = GuestBookingReview(
                routeChanged = !validSegment || route != routePlaces(ride),
                frontUnavailable = frontUnavailable,
                minorsUnavailable = minorsUnavailable,
                termsChanged = termsVersion != ride.termsVersion,
            ).takeIf { it.required },
        )
    }
}

internal data class RestoredGuestBooking(
    val pickup: String?,
    val dropoff: String?,
    val seats: Int,
    val front: Boolean,
    val minor: Boolean,
    val review: GuestBookingReview?,
)

internal data class GuestBookingReview(
    val routeChanged: Boolean,
    val frontUnavailable: Boolean,
    val minorsUnavailable: Boolean,
    val termsChanged: Boolean,
) {
    val required: Boolean get() = routeChanged || frontUnavailable || minorsUnavailable || termsChanged
}

internal data class GuestBookingLogin(val draft: GuestBookingDraft, val generation: Long, val revision: Long)
internal data class PendingGuestBooking(
    val draft: GuestBookingDraft,
    val generation: Long,
    val destination: YuldashViewModel.PendingPublicRideNavigation,
)
internal data class FailedGuestBooking(
    val draft: GuestBookingDraft,
    val generation: Long,
    val revision: Long,
    val screen: Screen,
)
internal data class OpenGuestBooking(
    val rideId: Int,
    val generation: Long,
    val revision: Long,
    val choices: RestoredGuestBooking,
)
