package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Immutable, current server projection. Persisted TripPass bytes can never mint this capability. */
internal data class TripPrivateProjection(
    val owner: SnapshotOwner, val details: BookingDetailsDto, val pass: TripPass,
    val proof: SnapshotNetworkProof, val permit: SnapshotAuthorityPermit?, val stamp: SnapshotClock,
    val driverCodeBlocked: Boolean = false,
) {
    val fingerprint: String = privateTripFingerprint(details, pass)
}

/** Driver code never enters TripPass/JSON. Its lifetime belongs to the ORIGINAL DETAILS. */
internal data class DriverBoardingCodeProjection(
    val projection: TripPrivateProjection, val code: String, val proof: SnapshotNetworkProof,
)

internal fun tripPassFromDetails(d: BookingDetailsDto, code: String = ""): TripPass = TripPass(
    bookingId = d.bookingId, fromCity = d.fromCity, toCity = d.toCity, departAt = d.departAt,
    driverName = d.driverName, driverCar = listOf(d.driverCarColor, d.driverCar).filter { it.isNotBlank() }.joinToString(" "),
    driverPlate = d.driverPlate, driverPhone = d.driverPhone, boardingCode = code,
    pickup = d.pickup, pickupLat = d.pickupLat, pickupLng = d.pickupLng, price = d.price, seats = d.seats,
    paymentNote = "", savedAt = System.currentTimeMillis(), payMethod = d.payMethod, payAmount = d.payAmount,
    pickupStop = d.pickupStop, dropoffStop = d.dropoffStop, rideHasStops = d.rideHasStops,
    meetLat = d.meetLat, meetLng = d.meetLng, meetNote = d.meetNote,
    meetLandmarkRu = d.meetLandmarkRu, meetLandmarkBa = d.meetLandmarkBa,
)

private fun canonicalPrivateJson(value: Any?): Any = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> JSONArray(value.keys().asSequence().toList().sorted().map { key ->
        JSONArray(listOf(key, canonicalPrivateJson(value.get(key))))
    })
    is JSONArray -> JSONArray((0 until value.length()).map { canonicalPrivateJson(value.get(it)) })
    else -> value
}

/** Ordered typed JSON, including every displayed pass field, S alighting/proposal and consent epochs.
 * savedAt is deliberately excluded: neither cache age nor byte identity grants authority.
 */
internal fun privateTripFingerprint(d: BookingDetailsDto, pass: TripPass): String {
    val undo = d.boardingUndo
    val proposal = d.meetingProposal
    return snapshotHash(JSONArray(listOf(
        d.bookingId, d.rideId, d.role, d.status, d.contactUnlocked,
        canonicalPrivateJson(pass.toJson().apply { remove("saved_at") }),
        d.passengerName, d.passengerPhone, d.driverVerified, d.minorPassenger, d.minorGuardianName, d.minorGuardianPhone,
        d.frontSeat, d.fromLat, d.fromLng, d.toLat, d.toLng, d.cancelledInTransit, d.declined,
        d.alightLat, d.alightLng, d.alightNote, d.alightLandmarkRu, d.alightLandmarkBa,
        d.meetingVersion, d.meetLandmarkId, d.alightLandmarkId,
        proposal?.let { JSONArray(listOf(it.proposalId, it.kind, it.createdAt, it.lat, it.lng, it.note, it.landmarkId, it.clear)) },
        JSONArray(listOf(undo.pending, undo.requestId, undo.waitSec, undo.onboardAt, undo.confirmedId, undo.sourceOnboardAt)),
    )).toString())
}

internal fun receiptFingerprint(value: TripReceiptDto): String = snapshotHash(JSONArray(listOf(
    value.bookingId, value.rideId, value.role, value.fromCity, value.toCity, value.departAt, value.seats,
    value.amount, value.payMethod, value.paid, value.driverName, value.driverVerified, value.counterpartyName,
    value.myStars, value.myRatingTags, value.counterpartyId, value.problemReportSec, value.canRate,
    value.pickupStop, value.dropoffStop,
)).toString())
