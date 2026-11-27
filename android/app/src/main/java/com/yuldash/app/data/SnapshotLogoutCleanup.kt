package com.yuldash.app.data

import android.content.Context

/** Exact registered preference pair only. The shared Android Keystore alias is never touched. */
internal class SnapshotLogoutCleanup(
    private val context: Context,
    private val delete: (String) -> Boolean = { context.deleteSharedPreferences(it) },
    private val absent: (String) -> Boolean = { name ->
        val directory = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        !java.io.File(directory, "$name.xml").exists() && !java.io.File(directory, "$name.xml.bak").exists()
    },
) {
    fun pending(): Boolean? = runCatching {
        context.getSharedPreferences(SNAPSHOT_META_PREFS, Context.MODE_PRIVATE).getBoolean(OfflineStoreReset.PENDING, false)
    }.getOrNull()
    fun run(): SnapshotPersistence {
        val meta = context.getSharedPreferences(SNAPSHOT_META_PREFS, Context.MODE_PRIVATE)
        val marked = runCatching { meta.edit().putBoolean(OfflineStoreReset.PENDING, true).commit() }.getOrDefault(false)
        val removed = runCatching { delete(SNAPSHOT_SECURE_PREFS) && absent(SNAPSHOT_SECURE_PREFS) }.getOrDefault(false)
        if (!removed) return if (marked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
        val cleaned = runCatching { delete(SNAPSHOT_META_PREFS) && absent(SNAPSHOT_META_PREFS) }.getOrDefault(false)
        if (cleaned) discardOfflineWriteRecovery(meta)
        return if (cleaned) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
    }
}
