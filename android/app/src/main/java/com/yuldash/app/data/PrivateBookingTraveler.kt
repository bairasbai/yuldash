package com.yuldash.app.data

import android.os.SystemClock
import org.json.JSONObject

data class PrivateBookingTraveler(val name: String, val deadlineElapsedMs: Long)

internal suspend fun completedBookingTraveler(bookingId: Int, owner: Long, role: String): Result<PrivateBookingTraveler?> {
    val started = SystemClock.elapsedRealtime()
    return ApiClient.readPrivateBookingDetails(bookingId, owner).map { body ->
        if (ApiClient.isCurrentSession(owner) && body.opt("booking_id") == bookingId && body.opt("role") == role &&
            body.opt("status") == "done" && body.opt("contact_unlocked") == true) parsePrivateBookingTraveler(body, started)
        else null
    }
}

internal fun parsePrivateBookingTraveler(body: JSONObject, startedElapsedMs: Long): PrivateBookingTraveler? {
    if (body.optString("status") == "cancelled") return null
    val name = (body.opt("traveler_name") as? String)?.takeIf(String::isNotBlank) ?: return null
    val remaining = (body.opt("traveler_name_remaining_ms") as? Number)?.toDouble() ?: return null
    if (remaining !in 1.0..30_000.0 || remaining % 1.0 != 0.0) return null
    return PrivateBookingTraveler(name, startedElapsedMs + remaining.toLong())
}
