package com.yuldash.app.data

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class BookingDecisionAction(val scope: String, val endpoint: String) {
    Confirm("booking.confirm", "confirm"), Decline("booking.cancel", "cancel"),
    PassengerCancel("booking.cancel", "cancel"),
}

internal data class PassengerCancellationTerms(
    val owner: BookingCommandApi.CommandOwner,
    val bookingId: Int,
    val status: String,
    val consequence: String,
    val termsKey: String,
)

private val passengerReasons = setOf("", "plans_changed", "found_other", "driver_no_response")

internal data class BookingDecision(
    val bookingId: Int, val action: BookingDecisionAction, val key: String, val hash: String,
    val createdAt: Long, val state: CommandIntent.State, val termsKey: String? = null, val reason: String? = null,
)

internal data class BookingDecisionOutcome(
    val state: CommandIntent.State,
    val bookingStatus: String? = null,
    val declined: Boolean = false,
    val rejectionMessage: String? = null,
    val contactThenCancel: Boolean = false,
    val rejectionCode: String? = null,
)

internal class BookingDecisionFailure(val problem: CommandIntent.Problem, val original: ApiException? = null) :
    IllegalStateException(problem.name)

internal class BookingDecisionStore(
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

    fun list(account: String): List<BookingDecision> = storage {
        check(account !in failed)
        val array = read(account)?.let(::JSONArray) ?: return@storage emptyList()
        require(array.length() <= MAX_SLOTS)
        List(array.length()) { index ->
            val row = array.getJSONObject(index)
            BookingDecision(row.getInt("booking_id"), BookingDecisionAction.valueOf(row.getString("action")),
                row.getString("key"), row.getString("hash"), row.getLong("created"),
                CommandIntent.State.valueOf(row.getString("state")), row.opt("terms_key") as? String,
                row.opt("reason") as? String).also {
                require(it.bookingId > 0 && it.createdAt >= 0 && it.key.matches(keyPattern) && it.hash.matches(hashPattern))
                require(if (it.action == BookingDecisionAction.Confirm) it.termsKey == null else it.termsKey?.matches(hashPattern) == true)
                require(if (it.action == BookingDecisionAction.PassengerCancel) it.reason in passengerReasons else it.reason == null)
            }
        }.also { require(it.map(BookingDecision::bookingId).distinct().size == it.size) }
    }

    fun reserve(
        account: String, bookingId: Int, action: BookingDecisionAction, hash: String,
        previousKey: String?, termsKey: String?, reason: String? = null,
    ): BookingDecision {
        val rows = list(account)
        val prior = rows.find { it.bookingId == bookingId }
        if (prior?.state == CommandIntent.State.Unknown || prior?.key != previousKey) {
            throw BookingDecisionFailure(CommandIntent.Problem.Superseded)
        }
        val updated = rows.filter { it.bookingId != bookingId }.toMutableList()
        if (updated.size >= MAX_SLOTS) {
            val evict = updated.indexOfFirst { it.state != CommandIntent.State.Unknown }
            if (evict < 0) throw BookingDecisionFailure(CommandIntent.Problem.Full)
            updated.removeAt(evict)
        }
        val decision = BookingDecision(bookingId, action, CommandFingerprint.KEY_PREFIX + UUID.randomUUID(),
            hash, clock(), CommandIntent.State.Unknown, termsKey, reason)
        save(account, updated + decision)
        return decision
    }

    fun repeat(account: String, decision: BookingDecision): BookingDecision {
        val current = list(account).find { it.bookingId == decision.bookingId }
        if (current?.key != decision.key || current.state != CommandIntent.State.Unknown) {
            throw BookingDecisionFailure(CommandIntent.Problem.Superseded)
        }
        if (clock() - current.createdAt !in 0L until RETRY_MS) throw BookingDecisionFailure(CommandIntent.Problem.Expired)
        return current
    }

    fun settle(account: String, decision: BookingDecision, state: CommandIntent.State): Boolean {
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

    private fun save(account: String, rows: List<BookingDecision>) {
        val array = JSONArray()
        rows.forEach {
            array.put(JSONObject().put("booking_id", it.bookingId).put("action", it.action.name)
                .put("key", it.key).put("hash", it.hash).put("created", it.createdAt).put("state", it.state.name)
                .put("terms_key", it.termsKey).put("reason", it.reason))
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
        throw BookingDecisionFailure(CommandIntent.Problem.Storage)
    }
}

internal object BookingDecisionApi {
    private var prefs: SharedPreferences? = null
    private val store = BookingDecisionStore(
        read = { account -> checkNotNull(prefs).getString(BookingDecisionStore.prefKey(account), null) },
        write = { account, value -> checkNotNull(prefs).edit().putString(BookingDecisionStore.prefKey(account), value).commit() },
    )

    fun init(storage: SharedPreferences) { prefs = storage }
    fun purge(account: String) { runCatching { store.purge(account) } }

    suspend fun pending(generation: Long, passenger: Boolean = false): Result<List<BookingDecision>> = authorized(generation) { owner ->
        use(owner) { store.list(owner.account).filter {
            it.state == CommandIntent.State.Unknown && (it.action == BookingDecisionAction.PassengerCancel) == passenger
        } }
    }

    suspend fun decide(generation: Long, id: Int, action: BookingDecisionAction): Result<BookingDecisionOutcome> =
        authorized(generation) { owner ->
            require(id > 0 && action != BookingDecisionAction.PassengerCancel)
            val prior = use(owner) { store.list(owner.account).find { it.bookingId == id } }
                .getOrElse { return@authorized Result.failure(it) }
            if (prior?.action == BookingDecisionAction.PassengerCancel) {
                return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Changed))
            }
            if (prior != null && prior.state in setOf(CommandIntent.State.Unknown, CommandIntent.State.Applied)) {
                return@authorized check(owner, prior, false)
            }
            val terms = if (action == BookingDecisionAction.Decline) {
                val actor = request(owner, "GET", "/bookings/$id/role").getOrElse { return@authorized Result.failure(it) }
                if (actor.optString("role") != "driver") {
                    return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Changed))
                }
                val preview = request(owner, "GET", "/bookings/$id/cancel-preview").getOrElse { return@authorized Result.failure(it) }
                if (preview.optString("booking_status") != "pending") {
                    return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Changed))
                }
                preview.getString("terms_key")
            } else null
            val snapshot = CommandFingerprint.create(action.scope, id.toLong(), body(action, terms))
            val decision = use(owner) { store.reserve(owner.account, id, action, snapshot.payloadHash, prior?.key, terms) }
                .getOrElse { return@authorized Result.failure(it) }
            send(owner, decision)
        }

    suspend fun repeat(generation: Long, decision: BookingDecision): Result<BookingDecisionOutcome> =
        authorized(generation) { owner ->
            val current = use(owner) { store.repeat(owner.account, decision) }.getOrElse { return@authorized Result.failure(it) }
            send(owner, current)
        }

    suspend fun check(generation: Long, decision: BookingDecision, close: Boolean): Result<BookingDecisionOutcome> =
        authorized(generation) { owner -> check(owner, decision, close) }

    suspend fun preparePassengerCancellation(generation: Long, id: Int, status: String): Result<PassengerCancellationTerms> =
        authorized(generation) { owner ->
            require(id > 0 && status in setOf("pending", "confirmed"))
            val saved = use(owner) { store.list(owner.account).find { it.bookingId == id } }
                .getOrElse { return@authorized Result.failure(it) }
            if (saved?.state == CommandIntent.State.Unknown) {
                return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Unknown))
            }
            val actor = request(owner, "GET", "/bookings/$id/role").getOrElse { return@authorized Result.failure(it) }
            if (actor.optString("role") != "passenger") {
                return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Changed))
            }
            request(owner, "GET", "/bookings/$id/cancel-preview").mapCatching { preview ->
                val consequence = preview.optString("consequence")
                if (preview.optString("booking_status") != status || consequence !in setOf("early", "late", "driver_departed")) {
                    throw BookingDecisionFailure(CommandIntent.Problem.Changed)
                }
                val terms = preview.getString("terms_key")
                require(terms.matches(BookingDecisionStore.hashPattern))
                PassengerCancellationTerms(owner, id, status, consequence, terms)
            }
        }

    suspend fun cancelPassenger(generation: Long, terms: PassengerCancellationTerms, reason: String): Result<BookingDecisionOutcome> =
        authorized(generation) { owner ->
            if (owner != terms.owner) return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Superseded))
            require(reason in passengerReasons)
            val id = terms.bookingId
            val prior = use(owner) { store.list(owner.account).find { it.bookingId == id } }
                .getOrElse { return@authorized Result.failure(it) }
            val action = BookingDecisionAction.PassengerCancel
            if (prior?.action == action && prior.state in setOf(CommandIntent.State.Unknown, CommandIntent.State.Applied)) {
                return@authorized check(owner, prior, false)
            }
            if (prior?.state == CommandIntent.State.Unknown) {
                return@authorized Result.failure(BookingDecisionFailure(CommandIntent.Problem.Changed))
            }
            val snapshot = CommandFingerprint.create(action.scope, id.toLong(), body(action, terms.termsKey, reason))
            val decision = use(owner) { store.reserve(owner.account, id, action, snapshot.payloadHash, prior?.key, terms.termsKey, reason) }
                .getOrElse { return@authorized Result.failure(it) }
            send(owner, decision)
        }

    private suspend fun send(owner: BookingCommandApi.CommandOwner, decision: BookingDecision): Result<BookingDecisionOutcome> {
        val snap = CommandFingerprint.create(decision.action.scope, decision.bookingId.toLong(), body(decision.action, decision.termsKey, decision.reason))
        require(snap.payloadHash == decision.hash)
        val path = "/bookings/" + decision.bookingId + "/commands/v1/" + decision.action.endpoint
        val response = request(owner, "POST", path, snap.wireBody, decision.key).mapCatching {
            booking(it, decision, owner.userId)
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
        return Result.failure(BookingDecisionFailure(CommandIntent.Problem.Unknown, refusal))
    }

    private suspend fun check(owner: BookingCommandApi.CommandOwner, decision: BookingDecision, close: Boolean): Result<BookingDecisionOutcome> {
        val current = use(owner) { store.list(owner.account).find { it.bookingId == decision.bookingId } }
            .getOrElse { return Result.failure(it) }
        if (current?.key != decision.key || (close && current.state != CommandIntent.State.Unknown)) {
            return Result.failure(BookingDecisionFailure(CommandIntent.Problem.Superseded))
        }
        val scope = current.action.scope
        val path = "/command-intents/" + current.key + if (close) "/close" else ""
        val body = if (close) JSONObject().put("scope", scope).put("target_id", current.bookingId)
            .put("payload_hash", current.hash).toString() else null
        val outcome = request(owner, if (close) "POST" else "GET", path, body)
            .mapCatching { receipt(it, current, owner.userId, close) }.getOrElse { return Result.failure(it) }
        return settle(owner, current, outcome)
    }

    private suspend fun settle(owner: BookingCommandApi.CommandOwner, decision: BookingDecision, outcome: BookingDecisionOutcome): Result<BookingDecisionOutcome> {
        val status = outcome.bookingStatus
        if (decision.action == BookingDecisionAction.PassengerCancel && outcome.state == CommandIntent.State.Applied &&
            (status == null || status == "done" || status == "cancelled")) {
            val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner.generation == owner.generation && it.owner.userId == owner.userId }
            val closed = if (status == null) OfflineTripSnapshots.closeBookingWithPersistence(ticket, decision.bookingId)
            else OfflineTripSnapshots.terminalWithPersistence(ticket, decision.bookingId, status, "passenger")
            if (closed == SnapshotPersistence.NOT_DURABLE || closed == SnapshotPersistence.STALE) {
                return Result.failure(BookingDecisionFailure(CommandIntent.Problem.Storage))
            }
        }
        return use(owner) {
            if (!store.settle(owner.account, decision, outcome.state)) throw BookingDecisionFailure(CommandIntent.Problem.Superseded)
            outcome
        }
    }

    private fun body(action: BookingDecisionAction, terms: String?, reason: String? = null): JSONObject? = if (action == BookingDecisionAction.Confirm) null else {
        require(terms != null && terms.matches(BookingDecisionStore.hashPattern))
        val reasonCode = if (action == BookingDecisionAction.PassengerCancel) reason else ""
        require(reasonCode in passengerReasons)
        JSONObject().put("reason", reasonCode).put("expected_terms_key", terms)
    }

    private fun booking(row: JSONObject, decision: BookingDecision, userId: Int): BookingDecisionOutcome {
        val passenger = row.getInt("passenger_id")
        val status = row.getString("status")
        require(row.getInt("id") == decision.bookingId && row.getInt("ride_id") > 0 && passenger > 0 &&
            (passenger == userId) == (decision.action == BookingDecisionAction.PassengerCancel))
        require(status in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
        return BookingDecisionOutcome(CommandIntent.State.Applied, status, row.optBoolean("declined"),
            contactThenCancel = row.optBoolean("contact_then_cancel"))
    }

    private fun receipt(view: JSONObject, decision: BookingDecision, userId: Int, closing: Boolean): BookingDecisionOutcome {
        val state = view.getString("state")
        if (state == "unseen" && !closing) {
            require(view.isNull("scope") && view.isNull("ref_id") && view.isNull("current"))
            return BookingDecisionOutcome(CommandIntent.State.Unknown)
        }
        require(view.optString("scope") == decision.action.scope)
        return when (state) {
            "applied" -> {
                require(view.optString("ref_kind") == "booking" && view.getInt("ref_id") == decision.bookingId)
                if (view.isNull("current")) {
                    require(view.optString("result_code") == "idempotency_result_gone")
                    BookingDecisionOutcome(CommandIntent.State.Applied)
                } else booking(view.getJSONObject("current"), decision, userId)
            }
            "rejected", "closed_no_effect" -> {
                require(view.isNull("ref_id") && view.isNull("ref_kind") && view.isNull("current"))
                BookingDecisionOutcome(if (state == "rejected") CommandIntent.State.Rejected else CommandIntent.State.Closed,
                    rejectionCode = view.optString("result_code").takeIf(String::isNotBlank))
            }
            else -> error("Unexpected driver decision receipt")
        }
    }

    private suspend fun request(owner: BookingCommandApi.CommandOwner, method: String, path: String, body: String? = null, key: String? = null) =
        ApiClient.bookingCommandRequest(method, path, body, owner.generation, owner.base, key, blockDuringDeletion = true)

    private suspend fun <T> use(owner: BookingCommandApi.CommandOwner, action: () -> T): Result<T> =
        BookingCommandApi.withCommandOwner(owner) {
            if (!BookingCommandApi.publicationAllowed(owner.generation)) throw BookingDecisionFailure(CommandIntent.Problem.Superseded)
            action()
        }

    private suspend fun <T> authorized(generation: Long, action: suspend (BookingCommandApi.CommandOwner) -> Result<T>): Result<T> {
        val owner = BookingCommandApi.commandOwner(generation).getOrElse { return Result.failure(it) }
        return try { action(owner) } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }
}
