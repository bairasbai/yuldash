package com.yuldash.app.data

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class DriverDecisionAction(val scope: String, val endpoint: String) {
    Confirm("booking.confirm", "confirm"), Decline("booking.cancel", "cancel"),
}

internal data class DriverDecision(
    val bookingId: Int, val action: DriverDecisionAction, val key: String, val hash: String,
    val createdAt: Long, val state: CommandIntent.State, val termsKey: String? = null,
)

internal data class DriverDecisionOutcome(
    val state: CommandIntent.State,
    val bookingStatus: String? = null,
    val declined: Boolean = false,
    val rejectionMessage: String? = null,
)

internal class DriverDecisionFailure(val problem: CommandIntent.Problem, val original: ApiException? = null) :
    IllegalStateException(problem.name)

internal class DriverDecisionStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val MAX_SLOTS = 64
        private const val RETRY_MS = 24 * 60 * 60 * 1000L
        private val keyPattern = Regex("cmd1_[a-f0-9-]{36}")
        val hashPattern = Regex("[a-f0-9]{64}")
        fun prefKey(account: String) = "rideshare_driver_decisions:$account"
    }

    private val failed = mutableSetOf<String>()

    fun list(account: String): List<DriverDecision> = storage {
        check(account !in failed)
        val array = read(account)?.let(::JSONArray) ?: return@storage emptyList()
        require(array.length() <= MAX_SLOTS)
        List(array.length()) { index ->
            val row = array.getJSONObject(index)
            DriverDecision(row.getInt("booking_id"), DriverDecisionAction.valueOf(row.getString("action")),
                row.getString("key"), row.getString("hash"), row.getLong("created"),
                CommandIntent.State.valueOf(row.getString("state")), row.opt("terms_key") as? String).also {
                require(it.bookingId > 0 && it.createdAt >= 0 && it.key.matches(keyPattern) && it.hash.matches(hashPattern))
                require(if (it.action == DriverDecisionAction.Decline) it.termsKey?.matches(hashPattern) == true else it.termsKey == null)
            }
        }.also { require(it.map(DriverDecision::bookingId).distinct().size == it.size) }
    }

    fun reserve(
        account: String, bookingId: Int, action: DriverDecisionAction, hash: String,
        previousKey: String?, termsKey: String?,
    ): DriverDecision {
        val rows = list(account)
        val prior = rows.find { it.bookingId == bookingId }
        if (prior?.state == CommandIntent.State.Unknown || prior?.key != previousKey) {
            throw DriverDecisionFailure(CommandIntent.Problem.Superseded)
        }
        val updated = rows.filter { it.bookingId != bookingId }.toMutableList()
        if (updated.size >= MAX_SLOTS) {
            val evict = updated.indexOfFirst { it.state != CommandIntent.State.Unknown }
            if (evict < 0) throw DriverDecisionFailure(CommandIntent.Problem.Full)
            updated.removeAt(evict)
        }
        val decision = DriverDecision(bookingId, action, CommandFingerprint.KEY_PREFIX + UUID.randomUUID(),
            hash, clock(), CommandIntent.State.Unknown, termsKey)
        save(account, updated + decision)
        return decision
    }

    fun repeat(account: String, decision: DriverDecision): DriverDecision {
        val current = list(account).find { it.bookingId == decision.bookingId }
        if (current?.key != decision.key || current.state != CommandIntent.State.Unknown) {
            throw DriverDecisionFailure(CommandIntent.Problem.Superseded)
        }
        if (clock() - current.createdAt !in 0L until RETRY_MS) throw DriverDecisionFailure(CommandIntent.Problem.Expired)
        return current
    }

    fun settle(account: String, decision: DriverDecision, state: CommandIntent.State): Boolean {
        val rows = list(account)
        val current = rows.find { it.bookingId == decision.bookingId }
        if (current?.key != decision.key) return false
        if (current.state != CommandIntent.State.Unknown || state == CommandIntent.State.Unknown) return current.state == state
        save(account, rows.map { if (it == current) current.copy(state = state) else it })
        return true
    }

    fun purge(account: String) {
        persist(account, null)
        failed.remove(account)
    }

    private fun save(account: String, rows: List<DriverDecision>) {
        val array = JSONArray()
        rows.forEach {
            array.put(JSONObject().put("booking_id", it.bookingId).put("action", it.action.name)
                .put("key", it.key).put("hash", it.hash).put("created", it.createdAt).put("state", it.state.name)
                .put("terms_key", it.termsKey))
        }
        persist(account, array.toString())
    }

    private fun persist(account: String, value: String?) = storage {
        try { check(write(account, value)) } catch (error: Exception) {
            failed.add(account)
            throw error
        }
    }

    private inline fun <T> storage(action: () -> T): T = try { action() } catch (_: Exception) {
        throw DriverDecisionFailure(CommandIntent.Problem.Storage)
    }
}

internal object DriverBookingDecisionApi {
    private var prefs: SharedPreferences? = null
    private val store = DriverDecisionStore(
        read = { account -> checkNotNull(prefs).getString(DriverDecisionStore.prefKey(account), null) },
        write = { account, value -> checkNotNull(prefs).edit().putString(DriverDecisionStore.prefKey(account), value).commit() },
    )

    fun init(storage: SharedPreferences) { prefs = storage }
    fun purge(account: String) { runCatching { store.purge(account) } }

    suspend fun pending(generation: Long): Result<List<DriverDecision>> = authorized(generation) { owner ->
        use(owner) { store.list(owner.account).filter { it.state == CommandIntent.State.Unknown } }
    }

    suspend fun decide(generation: Long, id: Int, action: DriverDecisionAction): Result<DriverDecisionOutcome> =
        authorized(generation) { owner ->
            require(id > 0)
            val prior = use(owner) { store.list(owner.account).find { it.bookingId == id } }
                .getOrElse { return@authorized Result.failure(it) }
            if (prior != null && prior.state in setOf(CommandIntent.State.Unknown, CommandIntent.State.Applied)) {
                return@authorized check(owner, prior, false)
            }
            val terms = if (action == DriverDecisionAction.Decline) {
                val actor = request(owner, "GET", "/bookings/$id/role").getOrElse { return@authorized Result.failure(it) }
                if (actor.optString("role") != "driver") {
                    return@authorized Result.failure(DriverDecisionFailure(CommandIntent.Problem.Changed))
                }
                val preview = request(owner, "GET", "/bookings/$id/cancel-preview").getOrElse { return@authorized Result.failure(it) }
                if (preview.optString("booking_status") != "pending") {
                    return@authorized Result.failure(DriverDecisionFailure(CommandIntent.Problem.Changed))
                }
                preview.getString("terms_key")
            } else null
            val snapshot = CommandFingerprint.create(action.scope, id.toLong(), body(action, terms))
            val decision = use(owner) { store.reserve(owner.account, id, action, snapshot.payloadHash, prior?.key, terms) }
                .getOrElse { return@authorized Result.failure(it) }
            send(owner, decision)
        }

    suspend fun repeat(generation: Long, decision: DriverDecision): Result<DriverDecisionOutcome> =
        authorized(generation) { owner ->
            val current = use(owner) { store.repeat(owner.account, decision) }.getOrElse { return@authorized Result.failure(it) }
            send(owner, current)
        }

    suspend fun check(generation: Long, decision: DriverDecision, close: Boolean): Result<DriverDecisionOutcome> =
        authorized(generation) { owner -> check(owner, decision, close) }

    private suspend fun send(owner: BookingCommandApi.CommandOwner, decision: DriverDecision): Result<DriverDecisionOutcome> {
        val snap = CommandFingerprint.create(decision.action.scope, decision.bookingId.toLong(), body(decision.action, decision.termsKey))
        require(snap.payloadHash == decision.hash)
        val path = "/bookings/" + decision.bookingId + "/commands/v1/" + decision.action.endpoint
        val response = request(owner, "POST", path, snap.wireBody, decision.key).mapCatching {
            booking(it, decision.bookingId, owner.userId)
        }
        val result = response.getOrNull()
        if (result != null) return settle(owner, decision, result)
        val refusal = response.exceptionOrNull() as? ApiException
        if (refusal != null && refusal.status in 400..499 && refusal.status !in setOf(401, 404, 405, 408, 429)) {
            val checked = check(owner, decision, false).getOrNull()
            if (checked != null && checked.state != CommandIntent.State.Unknown) {
                return Result.success(if (checked.state == CommandIntent.State.Rejected) checked.copy(rejectionMessage = refusal.message) else checked)
            }
        }
        return Result.failure(DriverDecisionFailure(CommandIntent.Problem.Unknown, refusal))
    }

    private suspend fun check(owner: BookingCommandApi.CommandOwner, decision: DriverDecision, close: Boolean): Result<DriverDecisionOutcome> {
        val current = use(owner) { store.list(owner.account).find { it.bookingId == decision.bookingId } }
            .getOrElse { return Result.failure(it) }
        if (current?.key != decision.key || (close && current.state != CommandIntent.State.Unknown)) {
            return Result.failure(DriverDecisionFailure(CommandIntent.Problem.Superseded))
        }
        val scope = decision.action.scope
        val path = "/command-intents/" + decision.key + if (close) "/close" else ""
        val body = if (close) JSONObject().put("scope", scope).put("target_id", decision.bookingId)
            .put("payload_hash", decision.hash).toString() else null
        val outcome = request(owner, if (close) "POST" else "GET", path, body)
            .mapCatching { receipt(it, decision, owner.userId, close) }.getOrElse { return Result.failure(it) }
        return settle(owner, decision, outcome)
    }

    private suspend fun settle(owner: BookingCommandApi.CommandOwner, decision: DriverDecision, outcome: DriverDecisionOutcome) =
        use(owner) {
            if (!store.settle(owner.account, decision, outcome.state)) throw DriverDecisionFailure(CommandIntent.Problem.Superseded)
            outcome
        }

    private fun body(action: DriverDecisionAction, terms: String?): JSONObject? = if (action == DriverDecisionAction.Confirm) null else {
        require(terms != null && terms.matches(DriverDecisionStore.hashPattern))
        JSONObject().put("reason", "").put("expected_terms_key", terms)
    }

    private fun booking(row: JSONObject, id: Int, userId: Int): DriverDecisionOutcome {
        val passenger = row.getInt("passenger_id")
        val status = row.getString("status")
        require(row.getInt("id") == id && row.getInt("ride_id") > 0 && passenger > 0 && passenger != userId)
        require(status in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
        return DriverDecisionOutcome(CommandIntent.State.Applied, status, row.optBoolean("declined"))
    }

    private fun receipt(view: JSONObject, decision: DriverDecision, userId: Int, closing: Boolean): DriverDecisionOutcome {
        val state = view.getString("state")
        if (state == "unseen" && !closing) {
            require(view.isNull("scope") && view.isNull("ref_id") && view.isNull("current"))
            return DriverDecisionOutcome(CommandIntent.State.Unknown)
        }
        require(view.optString("scope") == decision.action.scope)
        return when (state) {
            "applied" -> {
                require(view.optString("ref_kind") == "booking" && view.getInt("ref_id") == decision.bookingId)
                if (view.isNull("current")) {
                    require(view.optString("result_code") == "idempotency_result_gone")
                    DriverDecisionOutcome(CommandIntent.State.Applied)
                } else booking(view.getJSONObject("current"), decision.bookingId, userId)
            }
            "rejected", "closed_no_effect" -> {
                require(view.isNull("ref_id") && view.isNull("ref_kind") && view.isNull("current"))
                DriverDecisionOutcome(if (state == "rejected") CommandIntent.State.Rejected else CommandIntent.State.Closed)
            }
            else -> error("Unexpected driver decision receipt")
        }
    }

    private suspend fun request(owner: BookingCommandApi.CommandOwner, method: String, path: String, body: String? = null, key: String? = null) =
        ApiClient.bookingCommandRequest(method, path, body, owner.generation, owner.base, key, blockDuringDeletion = true)

    private suspend fun <T> use(owner: BookingCommandApi.CommandOwner, action: () -> T): Result<T> =
        BookingCommandApi.withCommandOwner(owner) {
            if (!BookingCommandApi.publicationAllowed(owner.generation)) throw DriverDecisionFailure(CommandIntent.Problem.Superseded)
            action()
        }

    private suspend fun <T> authorized(generation: Long, action: suspend (BookingCommandApi.CommandOwner) -> Result<T>): Result<T> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return try { action(owner) } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }
}
