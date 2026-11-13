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

    fun init(storage: SharedPreferences) { prefs = storage }
    fun purge(account: String) { runCatching { store.purge(account) } }

    suspend fun saved(generation: Long): Result<RidePublicationIntent.Slot?> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return use(owner) { store.current(owner.account) }
    }

    suspend fun publish(
        generation: Long, body: JSONObject, draft: RidePublicationIntent.Draft, previousKey: String?, retry: Boolean,
    ): Result<RidePublicationSubmission> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        val snapshot = runCatching { CommandFingerprint.create(RidePublicationOutcome.SCOPE, null, body) }
            .getOrElse { return Result.failure(RidePublicationIntent.Failure(CommandIntent.Problem.Storage)) }
        val slot = use(owner) { store.prepare(owner.account, snapshot.payloadHash, draft, previousKey, retry) }
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
