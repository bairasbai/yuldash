package com.yuldash.app.data

import java.time.LocalDate
import org.json.JSONObject

internal suspend fun ApiClient.getReturnTemplate(rideId: Int, generation: Long): Result<RideReturnTemplate> =
    authorized("GET", "/rides/$rideId/return-template", null, owner = generation)
        .mapCatching(RideReturnTemplate::parse)

internal class RideReturnTemplate private constructor(private val fields: JSONObject) {
    val fromCity = fields.getString("from_city")
    val toCity = fields.getString("to_city")
    val waypoints = fields.getString("waypoints").split('|').map(String::trim).filter(String::isNotEmpty)
    val seatsTotal = fields.getInt("seats_total")
    val price = fields.getInt("price")
    val stopPrices = fields.getJSONArray("stop_prices").let { list -> List(list.length()) { list.getInt(it) } }
    val category = fields.getString("category")
    val petsAllowed = fields.getBoolean("pets_allowed")
    val childSeat = fields.getBoolean("child_seat")
    val womenOnly = fields.getBoolean("women_only")
    val smoking = fields.getBoolean("smoking")
    val baggage = fields.getBoolean("baggage")
    val airConditioner = fields.getBoolean("air_conditioner")
    val onlyTrusted = fields.getBoolean("only_trusted")
    val quiet = fields.getBoolean("quiet")
    val noMinors = fields.getBoolean("no_minors")
    val backMaxTwo = fields.getBoolean("back_max_two")
    val frontSeatAvailable = fields.getBoolean("front_seat_available")
    val dropped = fields.getJSONArray("dropped").let { list -> List(list.length()) { list.getString(it) } }
    val suggestedDate = if (fields.isNull("suggested_date")) null else LocalDate.parse(fields.getString("suggested_date"))

    fun savedJson(): String = fields.toString()

    companion object {
        fun parse(fields: JSONObject): RideReturnTemplate = RideReturnTemplate(fields).apply {
            require(fromCity.isNotBlank() && toCity.isNotBlank() && seatsTotal > 0 && price >= 0)
            require(category == "regular" || category == "urgent")
            require(stopPrices.isEmpty() || stopPrices.size == waypoints.size)
            require(stopPrices.all { it in 1 until price } && stopPrices.zipWithNext().all { (a, b) -> a < b })
        }

        fun restore(saved: String?): RideReturnTemplate? =
            saved?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}
