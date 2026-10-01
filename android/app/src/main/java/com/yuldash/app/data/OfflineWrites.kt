package com.yuldash.app.data

import android.content.SharedPreferences
import java.util.WeakHashMap

/** A successful return means the write completed, not merely that apply was scheduled. */
internal fun commitOfflineString(
    prefs: SharedPreferences, key: String, value: String?, scope: SharedPreferences = prefs,
): Boolean = OfflineWriteRecovery.commit(scope, prefs, key, value)

internal fun readOfflineString(prefs: SharedPreferences, scope: SharedPreferences, key: String): String? =
    OfflineWriteRecovery.read(scope, prefs, key)

internal fun recoverOfflineWrites(plain: SharedPreferences, secure: SharedPreferences?): Boolean =
    OfflineWriteRecovery.recover(plain, secure)

/** Only after a confirmed reset or physical deletion; never merely on reinitialization. */
internal fun discardOfflineWriteRecovery(plain: SharedPreferences, keys: Set<String>? = null) =
    OfflineWriteRecovery.discard(plain, keys)

/**
 * Failed commit can change memory or even disk; a failed rollback must not authorize reads
 * of the candidate or further HTTP/writes. The stable plain scope survives secure wrappers.
 * This RAM guard cannot prove cold-start recovery under continuing physical disk failure.
 */
private object OfflineWriteRecovery {
    private data class Pending(val encrypted: Boolean, val previous: MutableMap<String, String?>)
    private val pending = WeakHashMap<SharedPreferences, Pending>()

    private fun restore(scope: SharedPreferences, prefs: SharedPreferences): Boolean {
        val entry = pending[scope] ?: return true
        if (entry.encrypted != (prefs !== scope)) return false
        repeat(2) {
            val restored = runCatching {
                prefs.edit().apply { entry.previous.forEach { (key, value) -> putString(key, value) } }.commit()
            }.getOrDefault(false)
            if (restored) {
                pending.remove(scope)
                return true
            }
        }
        return false
    }

    @Synchronized fun commit(scope: SharedPreferences, prefs: SharedPreferences, key: String, value: String?): Boolean {
        if (!restore(scope, prefs)) return false
        val previous = runCatching { prefs.getString(key, null) }.getOrElse { return false }
        val saved = runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
        if (!saved) {
            pending[scope] = Pending(prefs !== scope, mutableMapOf(key to previous))
            restore(scope, prefs)
        }
        return saved
    }

    @Synchronized fun read(scope: SharedPreferences, prefs: SharedPreferences, key: String): String? {
        val previous = pending[scope]?.previous
        // containsKey is essential: confirmed absence (null) must not fall through to dirty memory.
        if (previous?.containsKey(key) == true) return previous[key]
        return prefs.getString(key, null)
    }

    @Synchronized fun recover(plain: SharedPreferences, secure: SharedPreferences?): Boolean {
        val entry = pending[plain] ?: return true
        val target = if (entry.encrypted) secure ?: return false else plain
        return restore(plain, target)
    }

    @Synchronized fun discard(plain: SharedPreferences, keys: Set<String>?) {
        if (keys == null) pending.remove(plain)
        else pending[plain]?.previous?.let { previous ->
            keys.forEach(previous::remove)
            if (previous.isEmpty()) pending.remove(plain)
        }
    }
}
