package com.yuldash.app.data

import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only recovery metadata reaches disk. The caller serializes operations with the session lock. */
internal class CommandIntent(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val failedAccounts = mutableSetOf<String>()
    companion object {
        const val PREF_KEY = "rideshare_command_intents"
        fun prefKey(account: String) = "$PREF_KEY:$account"
        private const val MAX_SLOTS = 64
        private const val RETRY_MS = 24 * 60 * 60 * 1000L

        fun accountPrefix(base: String, userId: Int) = digest("$base\n$userId") + ":"
        fun account(base: String, userId: Int, createdAt: String) = accountPrefix(base, userId) + digest(createdAt)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    enum class State { Unknown, Applied, Rejected, Closed }
    enum class Problem { Storage, Full, Unknown, Changed, Expired, Superseded, Gone, Closed, Rejected }
    data class BookingTerms(val version: Long?)
    data class Slot(
        val rideId: Int, val key: String, val hash: String, val createdAt: Long, val state: State,
        val bookingTerms: BookingTerms? = null,
    )
    class Failure(val problem: Problem, val slot: Slot? = null, val rejectionCode: String? = null,
                  val existingBooking: BookingCreatedDto? = null) :
        IllegalStateException(problem.name)

    fun list(account: String): List<Slot> = storage {
        check(account !in failedAccounts)
        read(account)?.let { rows(JSONArray(it)) }.orEmpty()
    }

    fun prepare(
        account: String, rideId: Int, hash: String, previousKey: String?, retry: Boolean,
        bookingTerms: BookingTerms? = null,
    ): Slot {
        val rows = list(account).toMutableList()
        val prior = rows.firstOrNull { it.rideId == rideId }
        if (retry) {
            if (prior == null || prior.key != previousKey || prior.state != State.Unknown) {
                throw Failure(Problem.Superseded, prior)
            }
            if (prior.hash != hash) throw Failure(Problem.Changed, prior)
            if (clock() - prior.createdAt !in 0L until RETRY_MS) throw Failure(Problem.Expired, prior)
            return prior
        }
        if (prior != null && (prior.state == State.Unknown || prior.key != previousKey)) {
            throw Failure(Problem.Unknown, prior)
        }
        if (prior != null) rows.remove(prior)
        if (rows.size >= MAX_SLOTS) {
            val terminal = rows.indexOfFirst { it.state != State.Unknown }
            if (terminal < 0) throw Failure(Problem.Full)
            rows.removeAt(terminal)
        }
        val slot = Slot(rideId, CommandFingerprint.KEY_PREFIX + UUID.randomUUID(), hash, clock(), State.Unknown, bookingTerms)
        rows.add(slot)
        save(account, rows)
        return slot
    }

    fun settle(account: String, slot: Slot, state: State): Boolean {
        require(state != State.Unknown)
        val rows = list(account).toMutableList()
        val index = rows.indexOfFirst { it.rideId == slot.rideId && it.key == slot.key }
        if (index < 0) return false
        val current = rows[index]
        if (current.state != State.Unknown) return current.state == state
        rows[index] = current.copy(state = state)
        save(account, rows)
        return true
    }

    fun purge(account: String) {
        persist(account, null)
        failedAccounts.remove(account)
    }

    private fun rows(rows: JSONArray): List<Slot> {
        require(rows.length() <= MAX_SLOTS)
        return (0 until rows.length()).map { index ->
            val item = rows.getJSONObject(index)
            val terms = if (item.has("terms_version")) {
                BookingTerms(if (item.isNull("terms_version")) null else item.getLong("terms_version"))
            } else null
            Slot(item.getInt("ride_id"), item.getString("key"), item.getString("hash"),
                item.getLong("created"), State.valueOf(item.getString("state")), terms).also {
                require(it.rideId > 0 && it.key.matches(Regex("cmd1_[a-f0-9-]{36}")) &&
                    it.hash.matches(Regex("[a-f0-9]{64}")) && it.createdAt >= 0L)
            }
        }.also { require(it.map(Slot::rideId).distinct().size == it.size) }
    }

    private fun save(account: String, rows: List<Slot>) {
        val array = JSONArray()
        for (slot in rows) {
            val item = JSONObject().put("ride_id", slot.rideId).put("key", slot.key).put("hash", slot.hash)
                .put("created", slot.createdAt).put("state", slot.state.name)
            slot.bookingTerms?.let { item.put("terms_version", it.version ?: JSONObject.NULL) }
            array.put(item)
        }
        persist(account, array.toString())
    }

    private fun persist(account: String, value: String?) = storage {
        try {
            check(write(account, value))
        } catch (error: Exception) {
            failedAccounts.add(account)
            throw error
        }
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw Failure(Problem.Storage)
    }
}
