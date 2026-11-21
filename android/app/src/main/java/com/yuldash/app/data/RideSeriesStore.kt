package com.yuldash.app.data

import org.json.JSONArray
import org.json.JSONObject

internal data class RideSeriesResult(
    val state: CommandIntent.State = CommandIntent.State.Unknown,
    val rideId: Int? = null,
    val already: Boolean = false,
    val code: String? = null,
    val ru: String? = null,
    val ba: String? = null,
) {
    init {
        require(if (state == CommandIntent.State.Applied) rideId != null && rideId > 0 else rideId == null)
    }
}

/** Access runs inside the session monitor shared with single-ride publication. */
internal class RideSeriesStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Row(val date: RideSeriesDate, val result: RideSeriesResult = RideSeriesResult())

    data class Slot(val key: String, val rideBody: String, val createdAt: Long, val rows: List<Row>) {
        val pending: List<Row> get() = rows.filter { it.result.state == CommandIntent.State.Unknown }
        fun mayRepeat(now: Long = System.currentTimeMillis()): Boolean =
            pending.isNotEmpty() && now - createdAt in 0L until RETRY_MS
    }

    companion object {
        private const val RETRY_MS = 24 * 60 * 60 * 1000L
        fun prefKey(account: String) = "rideshare_series_intent:$account"
    }

    private val failedAccounts = mutableSetOf<String>()

    fun current(account: String): Slot? = storage {
        check(account !in failedAccounts)
        read(account)?.let { value ->
            val record = JSONObject(value)
            val key = record.getString("key")
            val body = record.getString("ride")
            val createdAt = record.getLong("created")
            require(createdAt >= 0)
            val entries = record.getJSONArray("dates")
            require(entries.length() in 1..RideSeriesIntent.MAX_DATES)
            val rows = List(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                val departure = item.getString("depart_at")
                val commandKey = item.getString("command_key")
                val baseKey = commandKey.substringBeforeLast('-')
                val date = RideSeriesIntent.create(JSONObject(body), listOf(departure), baseKey).dates.single()
                require(date.departureUtc == departure && date.commandKey == commandKey && date.payloadHash == item.getString("hash"))
                val result = RideSeriesResult(
                    CommandIntent.State.valueOf(item.getString("state")),
                    if (item.isNull("ride_id")) null else item.getInt("ride_id"),
                    item.getBoolean("already"),
                    item.textOrNull("code"), item.textOrNull("ru"), item.textOrNull("ba"),
                )
                if (result.state == CommandIntent.State.Unknown) require(baseKey == key)
                Row(date, result)
            }
            val dates = rows.map { it.date.departureUtc }
            require(dates == dates.distinct().sorted() && rows.any { it.date.commandKey.substringBeforeLast('-') == key })
            Slot(key, body, createdAt, rows)
        }
    }

    fun reserve(account: String, intent: RideSeriesIntent): Slot {
        if (current(account) != null) throw RidePublicationIntent.Failure(CommandIntent.Problem.Unknown)
        return newSlot(intent).also { save(account, it) }
    }

    fun settle(account: String, key: String, updates: Map<RideSeriesDate, RideSeriesResult>): Slot {
        val prior = matching(account, key)
        check(prior.rows.map { it.date }.containsAll(updates.keys))
        val rows = prior.rows.map { row ->
            if (row.result.state != CommandIntent.State.Unknown) row
            else row.copy(result = updates[row.date] ?: row.result)
        }
        return prior.copy(rows = rows).also { if (rows != prior.rows) save(account, it) }
    }

    fun replaceRejected(account: String, key: String, dates: Set<String>, departures: List<String>): Slot {
        val prior = matching(account, key)
        val rejected = prior.rows.filter { it.result.state in setOf(CommandIntent.State.Rejected, CommandIntent.State.Closed) }
        if (prior.pending.isNotEmpty() || dates.isEmpty() || !rejected.map { it.date.departureUtc }.containsAll(dates)) {
            throw RidePublicationIntent.Failure(CommandIntent.Problem.Superseded)
        }
        val retained = prior.rows.filter { it.date.departureUtc !in dates }
        val retry = newSlot(RideSeriesIntent.create(JSONObject(prior.rideBody), departures))
        if (retry.rows.size != dates.size || retry.rows.any { row ->
                retained.any { it.date.departureUtc == row.date.departureUtc }
            }) {
            throw RidePublicationIntent.Failure(CommandIntent.Problem.Changed)
        }
        val rows = (retained + retry.rows).sortedBy { it.date.departureUtc }
        return retry.copy(rows = rows).also { save(account, it) }
    }

    fun release(account: String, key: String) {
        if (matching(account, key).pending.isNotEmpty()) throw RidePublicationIntent.Failure(CommandIntent.Problem.Unknown)
        persist(account, null)
    }

    fun purge(account: String) {
        persist(account, null)
        failedAccounts.remove(account)
    }

    private fun matching(account: String, key: String): Slot = current(account)?.takeIf { it.key == key }
        ?: throw RidePublicationIntent.Failure(CommandIntent.Problem.Superseded)

    private fun newSlot(intent: RideSeriesIntent) = Slot(intent.baseKey, intent.rideBody, clock(), intent.dates.map { Row(it) })

    private fun save(account: String, slot: Slot) {
        val values = slot.rows.map { (date, result) ->
            JSONObject().put("depart_at", date.departureUtc).put("command_key", date.commandKey)
                .put("hash", date.payloadHash).put("state", result.state.name).put("already", result.already)
                .put("ride_id", result.rideId).put("code", result.code).put("ru", result.ru).put("ba", result.ba)
        }
        persist(account, JSONObject().put("key", slot.key).put("ride", slot.rideBody).put("created", slot.createdAt)
            .put("dates", JSONArray(values)).toString())
    }

    private fun persist(account: String, value: String?) = storage {
        try {
            check(write(account, value))
        } catch (error: Exception) {
            failedAccounts.add(account)
            throw error
        }
    }

    private fun JSONObject.textOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (_: Exception) {
        throw RidePublicationIntent.Failure(CommandIntent.Problem.Storage)
    }
}
