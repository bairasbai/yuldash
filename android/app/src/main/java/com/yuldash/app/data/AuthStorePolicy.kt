package com.yuldash.app.data

import android.content.SharedPreferences

/** Coordinates the two stores without guessing which account is newer after an interrupted write. */
internal object AuthStorePolicy {
    const val STATE_KEY = "auth_store_state"
    data class Selection(val prefs: SharedPreferences, val source: String)

    private fun commit(prefs: SharedPreferences, write: (SharedPreferences.Editor) -> Unit): Boolean =
        runCatching { prefs.edit().let { write(it); it.commit() } }.getOrDefault(false)

    private fun state(plain: SharedPreferences, value: String) = commit(plain) { it.putString(STATE_KEY, value) }
    private fun clear(prefs: SharedPreferences) = commit(prefs) { editor ->
        SessionKeys.CLEARED_ON_LOGOUT.forEach { editor.remove(it) }
    }
    private fun requireSaved(ok: Boolean) { check(ok) { "Session storage unavailable" } }

    private class InvalidSavedSession : RuntimeException()
    private fun read(prefs: SharedPreferences, key: String): String? = try {
        prefs.getString(key, null).also { value ->
            // EncryptedSharedPreferences returns the default for a different stored type;
            // regular SharedPreferences throws. Distinguish a missing/null string from that case.
            if (value == null && prefs.contains(key)) {
                val stored = prefs.all[key]
                if (stored != null && stored !is String) throw InvalidSavedSession()
            }
        }
    } catch (_: ClassCastException) {
        throw InvalidSavedSession()
    } catch (_: SecurityException) {
        // EncryptedSharedPreferences can open successfully but fail to decrypt one entry.
        throw InvalidSavedSession()
    }

    fun resolve(plain: SharedPreferences, secure: SharedPreferences?): Selection {
        return try {
            resolveSource(plain, secure).also { selected ->
                // Validate the whole identity before ApiClient publishes any part of it.
                SessionKeys.CLEARED_ON_LOGOUT.forEach { read(selected.prefs, it) }
            }
        } catch (_: InvalidSavedSession) {
            // A damaged account requires sign-in again, not a crash or a permanently null store.
            // Write failures still propagate: never claim recovery if cleanup was not saved.
            requireSaved(logout(plain, secure, null))
            Selection(secure ?: plain, if (secure != null) "secure" else "plain").also { selected ->
                SessionKeys.CLEARED_ON_LOGOUT.forEach { read(selected.prefs, it) }
            }
        }
    }

    private fun resolveSource(plain: SharedPreferences, secure: SharedPreferences?): Selection {
        val marker = read(plain, STATE_KEY)
        return when (marker) {
            "secure" -> {
                // A temporarily inaccessible secure store must never make an old plaintext login current.
                requireSaved(clear(plain))
                Selection(secure ?: plain, if (secure != null) "secure" else "plain")
            }
            "plain" -> migrate(plain, secure)
            "logout", "pending" -> {
                requireSaved(logout(plain, secure, null))
                Selection(secure ?: plain, if (secure != null) "secure" else "plain")
            }
            null -> {
                val plainToken = read(plain, "token")?.takeIf { it.isNotBlank() }
                val secureToken = secure?.let { read(it, "token") }?.takeIf { it.isNotBlank() }
                when {
                    plainToken != null && secureToken != null -> {
                        val existingSecure = checkNotNull(secure)
                        if (plainToken != secureToken || read(plain, "refresh_token") != read(existingSecure, "refresh_token")) {
                            requireSaved(logout(plain, secure, null))
                        } else {
                            requireSaved(state(plain, "secure"))
                            requireSaved(clear(plain))
                        }
                        Selection(existingSecure, "secure")
                    }
                    plainToken != null -> migrate(plain, secure)
                    secureToken != null -> {
                        requireSaved(state(plain, "secure"))
                        requireSaved(clear(plain))
                        Selection(secure!!, "secure")
                    }
                    else -> {
                        requireSaved(logout(plain, secure, null))
                        Selection(secure ?: plain, if (secure != null) "secure" else "plain")
                    }
                }
            }
            else -> {
                requireSaved(logout(plain, secure, null))
                Selection(secure ?: plain, if (secure != null) "secure" else "plain")
            }
        }
    }

    private fun migrate(plain: SharedPreferences, secure: SharedPreferences?): Selection {
        if (secure == null) {
            requireSaved(state(plain, "plain"))
            return Selection(plain, "plain")
        }
        // Read everything before editing either store: malformed values fail closed.
        val values = SessionKeys.CLEARED_ON_LOGOUT.associateWith { read(plain, it) }
        requireSaved(commit(secure) { editor -> values.forEach { (key, value) -> editor.putString(key, value) } })
        requireSaved(state(plain, "secure"))
        requireSaved(clear(plain))
        return Selection(secure, "secure")
    }

    fun writeSession(plain: SharedPreferences, selected: Selection, write: (SharedPreferences.Editor) -> Unit): Boolean {
        if (selected.source != "plain" && selected.source != "secure") return false
        if (!state(plain, "pending")) return false
        if (!commit(selected.prefs, write)) return false
        if (selected.source == "secure" && !clear(plain)) return false
        return state(plain, selected.source)
    }

    fun logout(plain: SharedPreferences, secure: SharedPreferences?, current: SharedPreferences?): Boolean {
        // A marker failure must not skip synchronous erasure of the stores that remain writable.
        // The false result still reports that durable logout was not fully confirmed.
        var success = state(plain, "logout")
        listOfNotNull(plain, secure, current).distinct().forEach { if (!clear(it)) success = false }
        return success
    }
}
