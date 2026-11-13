package com.yuldash.app.data

import org.json.JSONObject

internal data class BookingCommandOutcome(
    val state: CommandIntent.State,
    val booking: BookingCreatedDto? = null,
    val rejectionCode: String? = null,
    val existingBooking: BookingCreatedDto? = null,
) {
    companion object {
        const val SCOPE = "booking.create"
        const val PATH = "/bookings/commands/v1"
        const val FOR_ANOTHER_PATH = "/bookings/for-another/commands/v1"

        fun booking(response: JSONObject, rideId: Int, userId: Int): BookingCreatedDto {
            val id = response.getInt("id")
            val status = response.getString("status")
            require(id > 0 && status in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
            require(response.getInt("ride_id") == rideId && response.getInt("passenger_id") == userId)
            return BookingCreatedDto(id, status)
        }

        fun fromReceipt(response: JSONObject, rideId: Int, userId: Int, close: Boolean): BookingCommandOutcome {
            val state = response.getString("state")
            if (state == "unseen" && !close && response.isNull("scope")) {
                return BookingCommandOutcome(CommandIntent.State.Unknown)
            }
            require(response.getString("scope") == SCOPE)
            return when (state) {
                "applied" -> {
                    val current = response.optJSONObject("current")?.let { booking(it, rideId, userId) }
                    if (current != null) require(response.getInt("ref_id") == current.id)
                    else require(response.isNull("current") && response.optString("result_code") == "idempotency_result_gone")
                    BookingCommandOutcome(CommandIntent.State.Applied, current)
                }
                "rejected" -> BookingCommandOutcome(CommandIntent.State.Rejected,
                    rejectionCode = (response.opt("result_code") as? String)?.takeIf { it.isNotBlank() })
                "closed_no_effect" -> BookingCommandOutcome(CommandIntent.State.Closed)
                else -> error("Unexpected booking receipt")
            }
        }
    }
}
