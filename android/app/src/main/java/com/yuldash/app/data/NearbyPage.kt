package com.yuldash.app.data

import org.json.JSONObject

data class NearbyPage(
    val items: List<RideDto>,
    val total: Int,
    val hiddenUnverified: Int = 0,
    val hiddenBySeats: Int = 0,
    val maxFreeSeats: Int = 0,
    val countAtMaxFree: Int = 0,
)

internal fun parseNearbyPage(json: JSONObject, items: List<RideDto>): NearbyPage =
    NearbyPage(items, json.optInt("count"), json.optInt("hidden_unverified"), json.optInt("hidden_by_seats"),
        json.optInt("max_free_seats"), json.optInt("count_at_max_free"))
