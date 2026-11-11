package com.yuldash.app.data

import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONObject

/** One publication per verified account. The session monitor serializes all store access. */
internal class RidePublicationIntent(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Draft(val hash: String, val departAt: String, val stopLanguage: String)
    data class Slot(
        val key: String, val hash: String, val createdAt: Long, val state: CommandIntent.State, val draft: Draft,
    )

    class Failure(
        val problem: CommandIntent.Problem, val slot: Slot? = null, val refusal: ApiException? = null,
    ) : IllegalStateException(problem.name)

    companion object {
        private const val RETRY_MS = 24 * 60 * 60 * 1000L
        private val keyPattern = Regex("cmd1_[a-f0-9-]{36}")
        private val hashPattern = Regex("[a-f0-9]{64}")
        fun prefKey(account: String) = "rideshare_publication_intent:$account"
    }

    private val failedAccounts = mutableSetOf<String>()

    fun current(account: String): Slot? = storage {
        check(account !in failedAccounts)
        read(account)?.let { value ->
            val row = JSONObject(value)
            val draft = Draft(row.getString("draft_hash"), row.getString("depart_at"), row.getString("stop_language"))
            Slot(row.getString("key"), row.getString("hash"), row.getLong("created"),
                CommandIntent.State.valueOf(row.getString("state")), draft).also {
                require(it.key.matches(keyPattern) && it.hash.matches(hashPattern) && it.createdAt >= 0)
                validateDraft(draft)
            }
        }
    }

    fun prepare(account: String, hash: String, draft: Draft, previousKey: String?, retry: Boolean): Slot {
        val prior = current(account)
        if (retry) {
            if (prior == null || prior.key != previousKey || prior.state != CommandIntent.State.Unknown) {
                throw Failure(CommandIntent.Problem.Superseded, prior)
            }
            if (prior.hash != hash || prior.draft != draft) throw Failure(CommandIntent.Problem.Changed, prior)
            if (clock() - prior.createdAt !in 0L until RETRY_MS) throw Failure(CommandIntent.Problem.Expired, prior)
            return prior
        }
        if (prior?.state == CommandIntent.State.Unknown) throw Failure(CommandIntent.Problem.Unknown, prior)
        if (prior?.key != previousKey) throw Failure(CommandIntent.Problem.Superseded, prior)
        return storage {
            validateDraft(draft)
            require(hash.matches(hashPattern))
            Slot(CommandFingerprint.KEY_PREFIX + UUID.randomUUID(), hash, clock(), CommandIntent.State.Unknown, draft)
                .also { save(account, it) }
        }
    }

    fun settle(account: String, slot: Slot, state: CommandIntent.State): Boolean {
        val latest = current(account)
        if (latest?.key != slot.key) return false
        if (latest.state != CommandIntent.State.Unknown || state == CommandIntent.State.Unknown) return latest.state == state
        save(account, latest.copy(state = state))
        return true
    }

    fun purge(account: String) {
        persist(account, null)
        failedAccounts.remove(account)
    }

    private fun validateDraft(draft: Draft) {
        require(draft.hash.matches(hashPattern) && draft.stopLanguage in setOf("ru", "ba"))
        OffsetDateTime.parse(draft.departAt)
    }

    private fun save(account: String, slot: Slot) = persist(account, JSONObject()
        .put("key", slot.key).put("hash", slot.hash).put("created", slot.createdAt).put("state", slot.state.name)
        .put("draft_hash", slot.draft.hash).put("depart_at", slot.draft.departAt)
        .put("stop_language", slot.draft.stopLanguage).toString())

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
        throw Failure(CommandIntent.Problem.Storage)
    }
}
