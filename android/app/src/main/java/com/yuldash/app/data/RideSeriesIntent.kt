package com.yuldash.app.data

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class RideSeriesDate(
    val departureUtc: String,
    val commandKey: String,
    val payloadHash: String,
)

internal data class RideSeriesIntent(
    val baseKey: String,
    val rideBody: String,
    val dates: List<RideSeriesDate>,
) {
    fun requestBody(selected: List<RideSeriesDate> = dates): JSONObject {
        require(selected.isNotEmpty() && dates.containsAll(selected))
        return JSONObject()
            .put("ride", JSONObject(rideBody))
            .put("departures", JSONArray(selected.map { it.departureUtc }))
    }

    companion object {
        const val MAX_DATES = 10

        fun create(
            draft: JSONObject,
            departures: List<String>,
            baseKey: String = CommandFingerprint.KEY_PREFIX + UUID.randomUUID(),
        ): RideSeriesIntent {
            require(departures.size in 1..MAX_DATES)
            require(baseKey.matches(Regex("[A-Za-z0-9_-]{16,115}")))
            val ride = JSONObject(draft.toString()).apply { remove("depart_at") }
            val dates = departures.map {
                OffsetDateTime.parse(it).toInstant().truncatedTo(ChronoUnit.MINUTES).toString()
            }.distinct().sorted()
            return RideSeriesIntent(baseKey, ride.toString(), dates.map { utc ->
                val dateBody = JSONObject().put("ride", ride).put("depart_at", utc)
                val fingerprint = CommandFingerprint.create("ride.create", null, dateBody)
                val suffix = utc.take(16).filter(Char::isDigit)
                RideSeriesDate(utc, "$baseKey-$suffix", fingerprint.payloadHash)
            })
        }
    }
}
