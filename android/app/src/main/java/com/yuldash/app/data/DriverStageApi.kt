package com.yuldash.app.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A driver's saved boarding or completion remains attached to its original request. */
internal object DriverStageApi {
    internal enum class Stage(val wire: String) { Boarded("boarded"), Done("done") }
    data class Intent(val stage: Stage, val slot: CommandIntent.Slot)

    data class Outcome(val state: CommandIntent.State, val trip: TripStateDto? = null, val code: String? = null)

    private const val SCOPE = "booking.driver_status"
    private var prefs: SharedPreferences? = null
    private val change = MutableStateFlow(0)
    val revision = change.asStateFlow()
    fun prefKey(stage: Stage, account: String) = "rideshare_driver_stage_${stage.wire}:$account"

    private val stores = Stage.entries.associateWith { stage ->
        CommandIntent(
            read = { account -> checkNotNull(prefs).getString(prefKey(stage, account), null) },
            write = { account, raw -> checkNotNull(prefs).edit().putString(prefKey(stage, account), raw).commit() },
        )
    }

    fun init(storage: SharedPreferences) { prefs = storage }
    fun purge(account: String) { stores.values.forEach { runCatching { it.purge(account) } }; change.value++ }

    suspend fun pending(generation: Long, bookingId: Int? = null): Result<List<Intent>> =
        withOwner(generation) { owner ->
            inStore(owner) {
                stores.flatMap { (stage, store) ->
                    store.list(owner.account).filter { it.state == CommandIntent.State.Unknown && (bookingId == null || it.rideId == bookingId) }
                        .map { Intent(stage, it) }
                }
            }
        }

    suspend fun advance(generation: Long, bookingId: Int, stage: Stage): Result<Outcome> =
        withOwner(generation) { owner ->
            if (bookingId <= 0) return@withOwner failure(CommandIntent.Problem.Storage)
            val prior = inStore(owner) {
                stores.mapNotNull { (kind, store) ->
                    store.list(owner.account).firstOrNull { it.rideId == bookingId }?.let { Intent(kind, it) }
                }
            }.getOrElse { return@withOwner Result.failure(it) }
            val unresolved = prior.firstOrNull { it.slot.state == CommandIntent.State.Unknown }
            if (unresolved != null) return@withOwner check(owner, unresolved, close = false)
            val same = prior.firstOrNull { it.stage == stage }?.slot
            if (same?.state == CommandIntent.State.Applied) {
                val trip = currentTrip(owner, bookingId).getOrElse { return@withOwner Result.failure(it) }
                if (trip.status == "done" || trip.status == "cancelled" ||
                    (stage == Stage.Boarded && trip.status == "onboard")) {
                    return@withOwner Result.success(Outcome(CommandIntent.State.Applied, trip))
                }
                if (stage == Stage.Done || trip.status != "confirmed") return@withOwner failure(CommandIntent.Problem.Changed)
            }
            val body = snapshot(bookingId, stage)
            val intent = inStore(owner) {
                val blocked = stores.values.any { store ->
                    store.list(owner.account).any { it.rideId == bookingId && it.state == CommandIntent.State.Unknown }
                }
                if (blocked) throw CommandIntent.Failure(CommandIntent.Problem.Unknown)
                val slot = stores.getValue(stage).prepare(owner.account, bookingId, body.payloadHash, same?.key, retry = false)
                change.value++
                Intent(stage, slot)
            }.getOrElse { return@withOwner Result.failure(it) }
            send(owner, intent, body)
        }

    suspend fun repeat(generation: Long, intent: Intent): Result<Outcome> =
        withOwner(generation) { owner ->
            val body = snapshot(intent.slot.rideId, intent.stage)
            val stored = inStore(owner) {
                stores.getValue(intent.stage).prepare(owner.account, intent.slot.rideId, body.payloadHash, intent.slot.key, retry = true)
            }.getOrElse { return@withOwner Result.failure(it) }
            send(owner, intent.copy(slot = stored), body)
        }

    suspend fun check(generation: Long, intent: Intent, close: Boolean): Result<Outcome> =
        withOwner(generation) { owner -> check(owner, intent, close) }

    private suspend fun check(owner: BookingCommandApi.CommandOwner, original: Intent, close: Boolean): Result<Outcome> {
        val intent = inStore(owner) {
            val row = stores.getValue(original.stage).list(owner.account).firstOrNull { it.rideId == original.slot.rideId }
            if (row?.key != original.slot.key || (close && row.state != CommandIntent.State.Unknown)) {
                throw CommandIntent.Failure(CommandIntent.Problem.Superseded)
            }
            original.copy(slot = row)
        }.getOrElse { return Result.failure(it) }
        val id = intent.slot.rideId
        val path = "/command-intents/${intent.slot.key}" + if (close) "/close" else ""
        val body = if (close) JSONObject().put("scope", SCOPE).put("target_id", id).put("payload_hash", intent.slot.hash).toString() else null
        val receipt = request(owner, if (close) "POST" else "GET", path, body).getOrElse { return Result.failure(it) }
        val result = receipt.optString("state")
        if (result == "unseen" && !close && receipt.isNull("scope") && receipt.isNull("ref_kind") &&
            receipt.isNull("ref_id") && receipt.isNull("current")) {
            return inStore(owner) { Outcome(CommandIntent.State.Unknown) }
        }
        if (receipt.optString("scope") != SCOPE) return failure(CommandIntent.Problem.Unknown)
        if (result == "applied" && receipt.optString("ref_kind") == "booking" && receipt.optInt("ref_id") == id) {
            if (receipt.optJSONObject("current")?.optInt("id") == id) return applied(owner, intent)
            if (receipt.isNull("current") && receipt.optString("result_code") == "idempotency_result_gone") {
                return settle(owner, intent, CommandIntent.State.Applied).map {
                    Outcome(CommandIntent.State.Applied, code = "idempotency_result_gone")
                }
            }
        }
        if (receipt.isNull("ref_id") && receipt.isNull("ref_kind") && receipt.isNull("current")) {
            val state = when (result) {
                "rejected" -> CommandIntent.State.Rejected
                "closed_no_effect" -> CommandIntent.State.Closed
                else -> return failure(CommandIntent.Problem.Unknown)
            }
            return settle(owner, intent, state).map { Outcome(state, code = receipt.optString("result_code").ifBlank { null }) }
        }
        return failure(CommandIntent.Problem.Unknown)
    }

    private suspend fun send(owner: BookingCommandApi.CommandOwner, intent: Intent, body: CommandFingerprint.Snapshot): Result<Outcome> {
        val id = intent.slot.rideId
        val result = request(owner, "POST", "/bookings/$id/commands/v1/driver-status", body.wireBody, intent.slot.key)
        val response = result.getOrNull()
        if (response?.optBoolean("ok") == true && response.optInt("id") == id) return applied(owner, intent)
        val refusal = result.exceptionOrNull() as? ApiException
        if (refusal != null && refusal.status in setOf(400, 403, 409, 410, 422)) {
            val checked = check(owner, intent, close = false).getOrNull()
            if (checked != null && checked.state != CommandIntent.State.Unknown) return Result.success(checked)
        }
        return failure(CommandIntent.Problem.Unknown)
    }

    private suspend fun applied(owner: BookingCommandApi.CommandOwner, intent: Intent): Result<Outcome> {
        val trip = currentTrip(owner, intent.slot.rideId).getOrElse { return Result.failure(it) }
        return settle(owner, intent, CommandIntent.State.Applied).map { Outcome(CommandIntent.State.Applied, trip) }
    }

    private suspend fun currentTrip(owner: BookingCommandApi.CommandOwner, bookingId: Int): Result<TripStateDto> {
        if (!ApiClient.isCurrentSession(owner.generation)) return failure(CommandIntent.Problem.Superseded)
        val read = OfflineTripSnapshotHooks.state(bookingId, owner.generation)
        return read.result.fold(
            onSuccess = { trip ->
                inStore(owner) {
                    if (trip.role != "driver" || read.acceptedRole == null) {
                        throw CommandIntent.Failure(CommandIntent.Problem.Unknown)
                    }
                    trip
                }
            },
            onFailure = { failure(CommandIntent.Problem.Unknown) },
        )
    }

    private suspend fun settle(owner: BookingCommandApi.CommandOwner, intent: Intent, state: CommandIntent.State) =
        inStore(owner) {
            if (!stores.getValue(intent.stage).settle(owner.account, intent.slot, state)) {
                throw CommandIntent.Failure(CommandIntent.Problem.Superseded)
            }
            change.value++
        }

    suspend fun reconcileLegacy(action: OutboxAction, generation: Long): Result<Unit> {
        val read = OfflineTripSnapshotHooks.state(action.bookingId, generation)
        return read.result.mapCatching { trip ->
            if (!ApiClient.isCurrentSession(generation)) throw CommandIntent.Failure(CommandIntent.Problem.Superseded)
            val expected = if (action.payload == "boarded") "onboard" else "done"
            if (read.acceptedRole == null || trip.role != "driver" || trip.status != expected) {
                throw ApiException(409, "", "stale_driver_stage")
            }
        }
    }

    private fun snapshot(id: Int, stage: Stage) =
        CommandFingerprint.create(SCOPE, id.toLong(), JSONObject().put("status", stage.wire))

    private suspend fun request(owner: BookingCommandApi.CommandOwner, method: String, path: String, body: String? = null, key: String? = null) =
        ApiClient.bookingCommandRequest(method, path, body, owner.generation, owner.base, key, blockDuringDeletion = method == "POST")

    private suspend fun <T> withOwner(generation: Long, block: suspend (BookingCommandApi.CommandOwner) -> Result<T>): Result<T> =
        BookingCommandApi.commandOwner(generation).fold(onSuccess = { block(it) }, onFailure = { Result.failure(it) })

    private suspend fun <T> inStore(owner: BookingCommandApi.CommandOwner, block: () -> T): Result<T> =
        BookingCommandApi.withCommandOwner(owner) {
            if (!BookingCommandApi.publicationAllowed(owner.generation)) throw CommandIntent.Failure(CommandIntent.Problem.Superseded)
            block()
        }

    private fun <T> failure(problem: CommandIntent.Problem): Result<T> = Result.failure(CommandIntent.Failure(problem))
}
