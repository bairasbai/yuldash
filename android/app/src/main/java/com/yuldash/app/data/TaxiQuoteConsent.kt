package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

/** Consent timestamps only. The scheduled-order parser deliberately stays unchanged. */
internal fun taxiConsentDeadline(value: String): Long? = runCatching {
    require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})?").matches(value))
    val instant = if (value.endsWith("Z") || value.drop(10).contains(Regex("[+-]"))) {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant()
    } else {
        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.UTC)
    }
    instant.toEpochMilli().also { require(it > 0) } // Floor fractions conservatively, never round the lock up.
}.getOrNull()

internal data class TaxiConsentStamp(val wall: Long, val elapsed: Long, val deadline: Long) {
    fun fresh(nowWall: Long, nowElapsed: Long): Boolean = runCatching {
        val wallAge = Math.subtractExact(nowWall, wall)
        val elapsedAge = Math.subtractExact(nowElapsed, elapsed)
        val window = Math.subtractExact(deadline, wall)
        wallAge >= 0 && elapsedAge >= 0 && window > 0 && maxOf(wallAge, elapsedAge) < window
    }.getOrDefault(false)
}

internal fun taxiConsentUuid(value: String): Boolean = runCatching {
    val id = UUID.fromString(value)
    id.toString() == value && id.version() == 4 && id.variant() == 2
}.getOrDefault(false)

internal data class TaxiConsentStop(val lat: Double, val lng: Double, val text: String)

/** One immutable input for BOTH endpoints. Stop order matters; options form a set. */
@ConsistentCopyVisibility
internal data class TaxiConsentRoute private constructor(
    val fromLat: Double, val fromLng: Double, val toLat: Double, val toLng: Double,
    val fromText: String, val toText: String, val category: String, val options: List<String>,
    val stops: List<TaxiConsentStop>, val roundTrip: Boolean, val returnWaitMin: Int,
) {
    fun quoteBody(): JSONObject = JSONObject()
        .put("from_lat", fromLat).put("from_lng", fromLng).put("to_lat", toLat).put("to_lng", toLng)
        .put("from_text", fromText).put("to_text", toText).put("category", category)
        .put("options", JSONArray(options)).put("round_trip", roundTrip).put("return_wait_min", returnWaitMin)
        .put("waypoints", JSONArray().also { a -> stops.forEach { a.put(JSONObject().put("lat", it.lat).put("lng", it.lng).put("text", it.text)) } })

    fun orderBody(details: TaxiConsentDetails, quote: TaxiConsentQuote): String = quoteBody().apply {
        if (details.comment.isNotBlank()) put("comment", details.comment.take(300))
        if (details.entrance.isNotBlank()) put("entrance", details.entrance.take(60))
        if (details.forName.isNotBlank()) put("for_name", details.forName.take(120))
        if (details.forPhone.isNotBlank()) put("for_phone", details.forPhone.take(32))
        if (details.womenOnly) put("women_only", true)
        if (details.paymentMethod.isNotBlank()) put("payment_method", details.paymentMethod)
        put("quote_id", quote.quoteId); put("request_key", quote.requestKey)
    }.toString()

    companion object {
        fun normalized(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
            fromText: String, toText: String, category: String, options: Collection<String>, stops: List<TaxiStop>,
            roundTrip: Boolean, returnWaitMin: Int): TaxiConsentRoute {
            fun point(lat: Double, lng: Double) { require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) }
            point(fromLat, fromLng); point(toLat, toLng); stops.forEach { point(it.lat, it.lng) }
            return TaxiConsentRoute(fromLat, fromLng, toLat, toLng, fromText, toText, category,
                options.map(String::trim).filter(String::isNotEmpty).distinct().sorted(),
                stops.map { TaxiConsentStop(it.lat, it.lng, it.text) }, roundTrip, if (roundTrip) returnWaitMin.coerceIn(0, 1440) else 0)
        }
    }
}

internal data class TaxiConsentDetails(val comment: String = "", val entrance: String = "",
    val forName: String = "", val forPhone: String = "", val womenOnly: Boolean = false, val paymentMethod: String = "")

internal data class TaxiConsentBreakdown(val ride: Int, val pickup: Int, val options: Int, val weather: Int)
internal data class TaxiConsentQuote(
    val quoteId: String, val requestKey: String, val snapshotDigest: String, val pricingInputsDigest: String,
    val expiresAt: String, val deadline: Long, val priceKop: Int, val promoKop: Int, val payableKop: Int,
    val breakdown: TaxiConsentBreakdown, val distanceKm: Double, val etaMin: Double, val roundTrip: Boolean,
)
internal data class TaxiConsentReceipt(val order: InstantOrderDto, val quote: TaxiConsentQuote, val accepted: TaxiConsentQuote)

private fun JSONObject.kop(key: String): Int {
    val value = get(key)
    require(value is Int || value is Long)
    val number = (value as Number).toLong()
    require(number in 0..Int.MAX_VALUE.toLong()) // Same range as the existing Android money DTO/formatter, never overflow.
    return number.toInt()
}

internal fun readTaxiConsentQuote(o: JSONObject): TaxiConsentQuote {
    require(o.getString("schema") == "taxi-initial-consent-v1")
    val id = o.getString("quote_id"); val key = o.getString("request_key")
    require(taxiConsentUuid(id) && taxiConsentUuid(key))
    val digest = o.getString("snapshot_digest"); val inputs = o.getString("pricing_inputs_digest")
    require(Regex("[0-9a-f]{64}").matches(digest) && Regex("[0-9a-f]{64}").matches(inputs))
    val date = o.getString("expires_at"); val deadline = requireNotNull(taxiConsentDeadline(date))
    val price = o.kop("price_kop"); val promo = o.kop("promo_discount_kop"); val payable = o.kop("payable_kop")
    require(promo <= price && price - promo == payable)
    val b = o.getJSONObject("breakdown")
    require(b.keys().asSequence().toSet() == setOf("ride_price_kop", "pickup_fee_kop", "options_fee_kop", "weather_fee_kop"))
    val parts = TaxiConsentBreakdown(b.kop("ride_price_kop"), b.kop("pickup_fee_kop"), b.kop("options_fee_kop"), b.kop("weather_fee_kop"))
    require(listOf(parts.ride, parts.pickup, parts.options, parts.weather).fold(0L) { sum, n -> sum + n } == price.toLong())
    fun finite(keyName: String): Double {
        val value = o.get(keyName); require(value is Number)
        return value.toDouble().also { require(it.isFinite() && it >= 0) }
    }
    val roundTrip = o.get("round_trip"); require(roundTrip is Boolean)
    return TaxiConsentQuote(id, key, digest, inputs, date, deadline, price, promo, payable, parts, finite("distance_km"), finite("eta_min"), roundTrip)
}

internal fun readTaxiConsentReceipt(o: JSONObject, original: TaxiConsentQuote, userId: Int,
    parseOrder: (JSONObject) -> InstantOrderDto): TaxiConsentReceipt {
    val c = o.getJSONObject("consent"); val quoted = readTaxiConsentQuote(c.getJSONObject("quote"))
    val accepted = readTaxiConsentQuote(c.getJSONObject("accepted"))
    require(quoted == original && accepted.quoteId == original.quoteId && accepted.requestKey == original.requestKey
        && accepted.payableKop <= original.payableKop && accepted.promoKop == original.promoKop
        && accepted.expiresAt == original.expiresAt && accepted.roundTrip == original.roundTrip)
    return TaxiConsentReceipt(readTaxiConsentCard(o.getJSONObject("order"), null, userId, parseOrder), quoted, accepted)
}

/** Passenger cards normally redact passenger_id. When present it must agree with this login. */
internal fun readTaxiConsentCard(order: JSONObject, expectedId: Int?, userId: Int,
    parseOrder: (JSONObject) -> InstantOrderDto): InstantOrderDto {
    val id = order.kop("id")
    require(id > 0 && (expectedId == null || id == expectedId) && order.get("role") == "passenger")
    require(order.get("status") in setOf("created", "searching", "offered", "accepted", "arriving", "onboard", "expired", "done", "cancelled"))
    if (!order.isNull("passenger_id")) require(order.kop("passenger_id") == userId)
    order.kop("passenger_price_kop")
    return parseOrder(order)
}

internal sealed interface TaxiAttemptResult {
    data class Committed(val orderId: Int, val initialPayableKop: Int) : TaxiAttemptResult
    data object LookupUnknown : TaxiAttemptResult
    data object Closed : TaxiAttemptResult
    data object Retired : TaxiAttemptResult
    data object Unverified : TaxiAttemptResult
}

// Android's JSONObject accepts unquoted names, duplicate keys and trailing text. A negative
// proof can unlock order creation, so accept only the wire grammar of the two flat receipts.
// Their names and string values are ASCII identifiers; legal JSON Unicode escapes also work.
private const val recoverySpace = "[ \\t\\r\\n]*"
private const val recoveryWord = """"(?:[a-zA-Z0-9_-]|\\u[0-9a-fA-F]{4})++""""
private const val recoveryField = "$recoveryWord${recoverySpace}:${recoverySpace}(?:$recoveryWord|0|[1-9][0-9]*+)"
private val recoveryFieldPattern = Regex(recoveryField)
private val recoveryDocument = Regex("""${recoverySpace}\{${recoverySpace}$recoveryField(?:${recoverySpace},${recoverySpace}$recoveryField){1,4}${recoverySpace}\}$recoverySpace""")
internal fun taxiRecoveryDocument(text: String): JSONObject {
    require(recoveryDocument.matches(text)) { "Invalid consent recovery document" }
    val result = JSONObject(text)
    require(recoveryFieldPattern.findAll(text).count() == result.length()) { "Duplicate consent recovery field" }
    return result
}

internal fun readTaxiAttemptResult(json: JSONObject, close: Boolean): TaxiAttemptResult {
    val keys = json.keys().asSequence().toSet()
    val schema = json.get("schema")
    val state = json.get("state")
    if (schema == "taxi-consent-result-v1") {
        if (!close && state == "unknown" && keys == setOf("schema", "state")) return TaxiAttemptResult.LookupUnknown
        require(state == "committed" && keys == setOf("schema", "state", "order_id", "initial_payable_kop", "next"))
        require(json.get("next") == "fetch_order")
        return TaxiAttemptResult.Committed(json.kop("order_id").also { require(it > 0) }, json.kop("initial_payable_kop"))
    }
    require(close && schema == "taxi-consent-close-v1" && keys == setOf("schema", "state", "history", "admission"))
    require(json.get("admission") == "blocked")
    return when (state to json.get("history")) {
        "closed" to "not_created" -> TaxiAttemptResult.Closed
        "retired" to "created_unavailable" -> TaxiAttemptResult.Retired
        "unknown" to "unverified" -> TaxiAttemptResult.Unverified
        else -> throw IllegalArgumentException("Invalid consent close result")
    }
}
