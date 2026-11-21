package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject

internal data class RidePublicationOutcome(
    val state: CommandIntent.State,
    val ride: RideDto? = null,
    val rejectionMessage: String? = null,
) {
    companion object {
        const val SCOPE = "ride.create"
        private val rideStatuses = setOf("active", "done", "cancelled", "expired")

        fun ownedRide(body: JSONObject, userId: Int): RideDto {
            require(body.positiveId("driver_id") == userId)
            body.positiveId("id")
            require(body.opt("status") in rideStatuses)
            return body.toRideDto()
        }

        fun fromReceipt(body: JSONObject, userId: Int, closing: Boolean): RidePublicationOutcome {
            val state = body.getString("state")
            if (state == "unseen" && !closing) {
                require(body.isNull("scope") && body.isNull("ref_id") && body.isNull("current"))
                return RidePublicationOutcome(CommandIntent.State.Unknown)
            }
            require(body.opt("scope") == SCOPE)
            return when (state) {
                "applied" -> {
                    require(body.opt("ref_kind") == "ride")
                    val id = body.positiveId("ref_id")
                    val ride = if (body.isNull("current")) {
                        require(body.opt("result_code") == "idempotency_result_gone")
                        null
                    } else ownedRide(body.getJSONObject("current"), userId).also { require(it.id == id) }
                    RidePublicationOutcome(CommandIntent.State.Applied, ride)
                }
                "rejected", "closed_no_effect" -> {
                    require(body.isNull("ref_id") && body.isNull("ref_kind") && body.isNull("current"))
                    RidePublicationOutcome(
                        if (state == "rejected") CommandIntent.State.Rejected else CommandIntent.State.Closed,
                    )
                }
                else -> error("Unexpected publication result")
            }
        }

        private fun JSONObject.positiveId(field: String): Int {
            val raw = get(field) as? Number ?: error("Missing publication identifier")
            val id = raw.toInt()
            require(id > 0 && raw.toDouble() == id.toDouble())
            return id
        }
    }
}

internal data class RidePublicationSubmission(val slot: RidePublicationIntent.Slot, val outcome: RidePublicationOutcome)

internal object RidePublicationApi {
    private var prefs: SharedPreferences? = null
    private val store = RidePublicationIntent(
        read = { checkNotNull(prefs).getString(RidePublicationIntent.prefKey(it), null) },
        write = { account, value -> checkNotNull(prefs).edit().putString(RidePublicationIntent.prefKey(account), value).commit() },
    )
    private val series = RideSeriesStore(
        read = { checkNotNull(prefs).getString(RideSeriesStore.prefKey(it), null) },
        write = { account, value -> checkNotNull(prefs).edit().putString(RideSeriesStore.prefKey(account), value).commit() },
    )

    fun init(storage: SharedPreferences) { prefs = storage }
    fun purge(account: String) {
        runCatching { store.purge(account) }
        runCatching { series.purge(account) }
    }

    suspend fun saved(generation: Long): Result<RidePublicationIntent.Slot?> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return use(owner) { store.current(owner.account) }
    }

    suspend fun savedSeries(generation: Long): Result<RideSeriesStore.Slot?> = seriesOwner(generation) { owner ->
        use(owner) { series.current(owner.account) }
    }

    suspend fun reserveSeries(generation: Long, intent: RideSeriesIntent): Result<RideSeriesStore.Slot> =
        seriesOwner(generation) { owner ->
            use(owner) {
                if (store.current(owner.account)?.state == CommandIntent.State.Unknown) {
                    throw RidePublicationIntent.Failure(CommandIntent.Problem.Unknown)
                }
                series.reserve(owner.account, intent)
            }
        }

    suspend fun replaceSeriesDates(generation: Long, key: String, dates: Set<String>, departures: List<String>) =
        seriesOwner(generation) { owner -> use(owner) { series.replaceRejected(owner.account, key, dates, departures) } }

    suspend fun releaseSeries(generation: Long, key: String) =
        seriesOwner(generation) { owner -> use(owner) { series.release(owner.account, key) } }

    suspend fun sendSeries(generation: Long, key: String): Result<RideSeriesStore.Slot> = seriesOwner(generation) { owner ->
        val slot = activeSeries(owner, key).getOrElse { return@seriesOwner Result.failure(it) }
        if (!slot.mayRepeat()) return@seriesOwner recoverSeries(owner, slot, null)
        val pending = slot.pending.map { it.date }
        val intent = RideSeriesIntent(slot.key, slot.rideBody, pending)
        val updates = ApiClient.ridePublicationRequest("POST", "/rides/series", intent.requestBody().toString(),
            owner.generation, owner.base, slot.key).mapCatching { parseRideSeriesResponse(it, pending) }
            .getOrElse { return@seriesOwner Result.failure(it) }
        use(owner) { series.settle(owner.account, key, updates) }
    }

    suspend fun checkSeries(generation: Long, key: String, closeDate: String? = null): Result<RideSeriesStore.Slot> =
        seriesOwner(generation) { owner ->
            val slot = activeSeries(owner, key).getOrElse { return@seriesOwner Result.failure(it) }
            recoverSeries(owner, slot, closeDate)
        }

    private suspend fun recoverSeries(
        owner: BookingCommandApi.CommandOwner, slot: RideSeriesStore.Slot, closeDate: String?,
    ): Result<RideSeriesStore.Slot> {
        val pending = slot.pending.filter { closeDate == null || it.date.departureUtc == closeDate }
        var latest = slot
        var failure: Throwable? = null
        for ((date) in pending) {
            val close = closeDate != null
            val path = "/command-intents/" + date.commandKey + if (close) "/close" else ""
            val body = if (close) JSONObject().put("scope", RidePublicationOutcome.SCOPE)
                .put("target_id", JSONObject.NULL).put("payload_hash", date.payloadHash).toString() else null
            val response = ApiClient.ridePublicationRequest(if (close) "POST" else "GET", path, body, owner.generation, owner.base)
                .mapCatching { item ->
                    val outcome = RidePublicationOutcome.fromReceipt(item, owner.userId, close)
                    RideSeriesResult(outcome.state,
                        rideId = if (outcome.state == CommandIntent.State.Applied) item.getInt("ref_id") else null,
                        already = outcome.state == CommandIntent.State.Applied,
                        code = if (outcome.state == CommandIntent.State.Closed) "idempotency_closed_no_effect"
                        else item.seriesText("result_code"),
                        ru = item.seriesText("ru"), ba = item.seriesText("ba"))
                }
            val result = response.getOrNull()
            if (result == null) failure = response.exceptionOrNull()
            else latest = use(owner) { series.settle(owner.account, slot.key, mapOf(date to result)) }
                .getOrElse { return Result.failure(it) }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(latest)
    }

    private suspend fun activeSeries(owner: BookingCommandApi.CommandOwner, key: String): Result<RideSeriesStore.Slot> =
        use(owner) {
            series.current(owner.account)?.takeIf { it.key == key }
                ?: throw RidePublicationIntent.Failure(CommandIntent.Problem.Superseded)
        }

    private suspend fun <T> seriesOwner(
        generation: Long, action: suspend (BookingCommandApi.CommandOwner) -> Result<T>,
    ): Result<T> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return action(owner)
    }

    suspend fun publish(
        generation: Long, body: JSONObject, draft: RidePublicationIntent.Draft, previousKey: String?, retry: Boolean,
    ): Result<RidePublicationSubmission> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        val snapshot = runCatching { CommandFingerprint.create(RidePublicationOutcome.SCOPE, null, body) }
            .getOrElse { return Result.failure(RidePublicationIntent.Failure(CommandIntent.Problem.Storage)) }
        val slot = use(owner) {
            if (series.current(owner.account) != null) throw RidePublicationIntent.Failure(CommandIntent.Problem.Unknown)
            store.prepare(owner.account, snapshot.payloadHash, draft, previousKey, retry)
        }
            .getOrElse { return Result.failure(it) }
        val response = ApiClient.ridePublicationRequest("POST", "/rides/commands/v1", snapshot.wireBody,
            owner.generation, owner.base, slot.key).mapCatching { RidePublicationOutcome.ownedRide(it, owner.userId) }
        val ride = response.getOrNull()
        if (ride != null) {
            val accepted = settle(owner, slot, RidePublicationOutcome(CommandIntent.State.Applied, ride))
            if (accepted.isSuccess && !retry && ride.status == "active") Analytics.log("publish_ride")
            return accepted.map { RidePublicationSubmission(slot, it) }
        }
        val refusal = response.exceptionOrNull() as? ApiException
        if (refusal != null && refusal.status in 400..499 && refusal.status !in setOf(401, 404, 405, 408, 429)) {
            val outcome = check(owner, slot, close = false).getOrNull()
            if (outcome != null && outcome.state != CommandIntent.State.Unknown) {
                val checked = if (outcome.state == CommandIntent.State.Rejected) {
                    outcome.copy(rejectionMessage = refusal.message)
                } else outcome
                return Result.success(RidePublicationSubmission(slot, checked))
            }
        }
        return Result.failure(RidePublicationIntent.Failure(CommandIntent.Problem.Unknown, slot, refusal))
    }

    suspend fun check(generation: Long, slot: RidePublicationIntent.Slot, close: Boolean): Result<RidePublicationOutcome> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return check(owner, slot, close)
    }

    private suspend fun check(
        owner: BookingCommandApi.CommandOwner, slot: RidePublicationIntent.Slot, close: Boolean,
    ): Result<RidePublicationOutcome> {
        val current = use(owner) { store.current(owner.account) }.getOrElse { return Result.failure(it) }
        if (current?.key != slot.key || (close && current.state != CommandIntent.State.Unknown)) {
            return Result.failure(RidePublicationIntent.Failure(CommandIntent.Problem.Superseded, current))
        }
        val scope = RidePublicationOutcome.SCOPE
        val path = "/command-intents/" + slot.key + if (close) "/close" else ""
        val body = if (close) JSONObject().put("scope", scope).put("target_id", JSONObject.NULL)
            .put("payload_hash", slot.hash).toString() else null
        val response = ApiClient.ridePublicationRequest(if (close) "POST" else "GET", path, body, owner.generation, owner.base)
            .mapCatching { RidePublicationOutcome.fromReceipt(it, owner.userId, close) }
            .getOrElse { return Result.failure(it) }
        return settle(owner, slot, response)
    }

    private suspend fun settle(
        owner: BookingCommandApi.CommandOwner, slot: RidePublicationIntent.Slot, outcome: RidePublicationOutcome,
    ): Result<RidePublicationOutcome> = use(owner) {
        if (!store.settle(owner.account, slot, outcome.state)) {
            throw RidePublicationIntent.Failure(CommandIntent.Problem.Superseded, store.current(owner.account))
        }
        outcome
    }

    private suspend fun <T> use(owner: BookingCommandApi.CommandOwner, action: () -> T): Result<T> =
        BookingCommandApi.withCommandOwner(owner) {
            if (!BookingCommandApi.publicationAllowed(owner.generation)) {
                throw RidePublicationIntent.Failure(CommandIntent.Problem.Superseded)
            }
            action()
        }
}

internal fun parseRideSeriesResponse(body: JSONObject, expected: List<RideSeriesDate>): Map<RideSeriesDate, RideSeriesResult> {
    val dateByDeparture = expected.associateBy { it.departureUtc }.toMutableMap()
    val items = body.getJSONArray("items")
    require(items.length() == expected.size)
    val outcomes = buildMap {
        repeat(items.length()) { index ->
            val item = items.getJSONObject(index)
            val date = requireNotNull(dateByDeparture.remove(RideSeriesIntent.minute(item.getString("depart_at"))))
            val code = item.seriesText("code")
            val state = when (item.getString("outcome")) {
                "unknown" -> CommandIntent.State.Unknown
                "applied" -> CommandIntent.State.Applied
                "rejected" -> if (code == "idempotency_closed_no_effect") CommandIntent.State.Closed else CommandIntent.State.Rejected
                else -> error("Unexpected series outcome")
            }
            val rawId = if (item.isNull("ride_id")) null else {
                item.get("ride_id") as? Number ?: error("Invalid series ride identifier")
            }
            val id = rawId?.toInt()
            require(rawId == null || rawId.toDouble() == id?.toDouble())
            put(date, RideSeriesResult(state, id, item.getBoolean("already"), code, item.seriesText("ru"), item.seriesText("ba")))
        }
    }
    require(outcomes.values.count { it.state == CommandIntent.State.Applied } == body.getInt("published"))
    require(outcomes.values.count { it.state == CommandIntent.State.Unknown } == body.getInt("unresolved"))
    require(outcomes.size - body.getInt("published") - body.getInt("unresolved") == body.getInt("failed"))
    return outcomes
}

private fun JSONObject.seriesText(name: String): String? = if (isNull(name)) null else getString(name)
