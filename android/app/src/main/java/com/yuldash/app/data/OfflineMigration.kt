package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.WeakHashMap

/** Redo journal: no mutations are allowed between preparing the snapshot and retiring its source. */
internal object OfflineMigration {
    const val JOURNAL = "offline_migration_v1"
    private val pending = WeakHashMap<SharedPreferences, Map<String, String>>()
    private val quarantined = WeakHashMap<SharedPreferences, Boolean>()

    data class Selection(
        val storage: SharedPreferences,
        val writable: Boolean,
        val snapshot: Map<String, String> = emptyMap(),
        val readable: Boolean = true,
        val recoveryScope: SharedPreferences = storage,
    ) {
        fun read(key: String): String? = if (!readable) null else snapshot[key] ?: readOfflineString(storage, recoveryScope, key)
    }

    @Synchronized fun quarantine(plain: SharedPreferences) { quarantined[plain] = true }
    @Synchronized fun resetUnconfirmed(plain: SharedPreferences): Boolean = quarantined.containsKey(plain)

    /** Call only after the plain reset (including secure_clear_pending) was committed. */
    @Synchronized fun resetCommitted(plain: SharedPreferences) {
        pending.remove(plain)
        quarantined.remove(plain)
    }

    @Synchronized fun open(
        plain: SharedPreferences,
        secure: SharedPreferences?,
        accepts: (String) -> Boolean,
    ): Selection {
        fun select(storage: SharedPreferences, writable: Boolean, snapshot: Map<String, String> = emptyMap(), readable: Boolean = true) =
            Selection(storage, writable, snapshot, readable, plain)
        if (quarantined.containsKey(plain)) return select(plain, false, readable = false)
        // Do not migrate/send an unconfirmed candidate, including through a new secure wrapper.
        if (!recoverOfflineWrites(plain, secure)) return select(secure ?: plain, false)
        var snapshot = pending[plain]
        var copied = false
        return try {
            if (snapshot == null && plain.contains(JOURNAL)) {
                val journal = JSONObject(requireNotNull(plain.getString(JOURNAL, null)))
                require(journal.getInt("version") == 1)
                val data = journal.getJSONObject("data")
                snapshot = data.keys().asSequence().associateWith { key ->
                    require(accepts(key))
                    (data.get(key) as? String) ?: error("Invalid migration value")
                }
                pending[plain] = snapshot!!
            }
            if (secure == null) return select(plain, snapshot == null, snapshot.orEmpty())
            if (snapshot == null) {
                snapshot = plain.all.filterKeys(accepts).mapValues { (_, value) ->
                    (value as? String) ?: error("Invalid offline value")
                }
                if (snapshot!!.isEmpty()) return select(secure, true)
                pending[plain] = snapshot!!
            }
            val values = snapshot!!
            val data = JSONObject().apply { values.forEach { (key, value) -> put(key, value) } }
            val journal = JSONObject().put("version", 1).put("data", data).toString()
            if (!plain.edit().putString(JOURNAL, journal).commit()) return select(plain, false, values)
            if (!secure.edit().apply { values.forEach { (key, value) -> putString(key, value) } }.commit())
                return select(plain, false, values)
            copied = true
            if (!plain.edit().apply { values.keys.forEach { remove(it) }; remove(JOURNAL) }.commit())
                return select(secure, false, values)
            pending.remove(plain)
            select(secure, true)
        } catch (_: Exception) {
            // Invalid/unreadable journal is uncertainty, never permission to replace or send data.
            select(if (copied) secure!! else plain, false, snapshot.orEmpty(), readable = snapshot != null)
        }
    }
}
