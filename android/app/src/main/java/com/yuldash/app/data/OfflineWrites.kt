package com.yuldash.app.data

import android.content.SharedPreferences

/** A successful return means the write completed, not merely that apply was scheduled. */
internal fun commitOfflineString(prefs: SharedPreferences, key: String, value: String?): Boolean {
    val previous = runCatching { prefs.getString(key, null) }.getOrElse { return false }
    val saved = runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
    if (!saved) {
        // Android may change the in-memory map even when disk persistence fails.
        runCatching { prefs.edit().putString(key, previous).commit() }
    }
    return saved
}
