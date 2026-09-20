package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import java.util.WeakHashMap

/** IDs only. Keep deletion barriers until the store's coordinated logout reset. */
internal object TripPassDeletion {
    const val KEY = "trip_pass_deletions_v1"
    private val uncommitted = WeakHashMap<SharedPreferences, Set<Int>>()

    private fun ids(plain: SharedPreferences): Set<Int> {
        val stored = if (plain.contains(KEY)) {
            val array = JSONArray(requireNotNull(plain.getString(KEY, null)))
            (0 until array.length()).map { index ->
                val value = array.get(index)
                require(value is Int && value > 0)
                value
            }.toSet()
        } else emptySet()
        return stored + uncommitted[plain].orEmpty()
    }

    @Synchronized fun blocks(plain: SharedPreferences, bookingId: Int): Boolean =
        runCatching { bookingId in ids(plain) }.getOrDefault(true)

    private fun commit(plain: SharedPreferences, values: Set<Int>): Boolean {
        uncommitted[plain] = values
        val saved = runCatching {
            plain.edit().putString(KEY, JSONArray(values.sorted()).toString()).commit()
        }.getOrDefault(false)
        if (saved) uncommitted.remove(plain)
        return saved
    }

    @Synchronized fun request(plain: SharedPreferences, bookingId: Int): Boolean = runCatching {
        require(bookingId > 0)
        // Recommit even if present in memory: an earlier false may not have reached disk.
        commit(plain, ids(plain) + bookingId)
    }.getOrDefault(false)

    @Synchronized fun reconcile(
        plain: SharedPreferences,
        secure: SharedPreferences?,
        migrationFinished: Boolean,
    ): Boolean = runCatching {
        val deleted = ids(plain)
        if (uncommitted.containsKey(plain) && !commit(plain, deleted)) return@runCatching false
        // A pending migration still holds a snapshot of the old data. Retire it first.
        if (!migrationFinished) return@runCatching false
        if (deleted.isEmpty()) return@runCatching true
        val secureCleared = secure?.edit()?.apply { deleted.forEach { remove("pass_$it") } }?.commit() == true
        val plainCleared = plain.edit().apply { deleted.forEach { remove("pass_$it") } }.commit()
        secureCleared && plainCleared
    }.getOrDefault(false)

    /** Only after plain.clear + secure_clear_pending has reached disk. */
    @Synchronized fun resetCommitted(plain: SharedPreferences) { uncommitted.remove(plain) }
}
