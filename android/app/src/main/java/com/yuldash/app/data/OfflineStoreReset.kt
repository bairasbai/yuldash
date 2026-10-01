package com.yuldash.app.data

import android.content.SharedPreferences

/** Durable request to erase an offline store that may be unavailable during logout. */
internal object OfflineStoreReset {
    const val PENDING = "secure_clear_pending"
    // A failed commit can remove the flag from memory while it remains on disk.
    // Keep that uncertainty across reinitializations of the same preferences object.
    private val incomplete = java.util.WeakHashMap<SharedPreferences, Boolean>()

    private fun finish(plain: SharedPreferences, secure: SharedPreferences): Boolean = runCatching {
        // Never remove the marker before the old secure data is durably erased.
        incomplete[plain] = true
        if (!secure.edit().clear().commit()) return@runCatching false
        if (!plain.edit().remove(PENDING).commit()) {
            runCatching { plain.edit().putBoolean(PENDING, true).commit() }
            return@runCatching false
        }
        incomplete.remove(plain)
        true
    }.getOrDefault(false)

    @Synchronized fun availableSecure(plain: SharedPreferences, secure: SharedPreferences?): SharedPreferences? {
        if (secure == null) return null
        val pending = incomplete.containsKey(plain) || runCatching { plain.getBoolean(PENDING, false) }.getOrDefault(true)
        return if (!pending || finish(plain, secure)) secure else null
    }

    @Synchronized fun clear(plain: SharedPreferences, secure: SharedPreferences?): Boolean = runCatching {
        OfflineMigration.quarantine(plain)
        // Plain data is erased together with the marker; future plain data belongs to the new session.
        if (!plain.edit().clear().putBoolean(PENDING, true).commit()) return@runCatching false
        discardOfflineWriteRecovery(plain)
        OfflineMigration.resetCommitted(plain)
        TripPassDeletion.resetCommitted(plain)
        secure != null && finish(plain, secure)
    }.getOrDefault(false)
}
