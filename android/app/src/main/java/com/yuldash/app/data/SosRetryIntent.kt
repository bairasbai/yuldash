package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Only an opaque nonce and a hash survive restart; never the emergency text or location. */
internal class SosRetryIntent(
    private val storage: SharedPreferences?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PREF_KEY = "sos_pending_intent"
        private const val MAX_AGE_MS = 15 * 60 * 1000L
    }
    private data class Pending(val key: String, val fingerprint: String, val created: Long)
    private var loaded = false
    private val pending = linkedMapOf<String, Pending>()
    private val durableScopes = mutableSetOf<String>()

    fun prepare(body: JSONObject, scope: String = "sos"): String {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val time = now()
        if (!loaded) {
            loaded = true
            runCatching {
                val saved = JSONObject(storage?.getString(PREF_KEY, null) ?: return@runCatching)
                saved.keys().asSequence().take(33).forEach { slot ->
                    runCatching {
                        val record = saved.getJSONObject(slot)
                        val key = record.getString("key")
                        val hash = record.getString("fingerprint")
                        val created = record.getLong("created")
                        if ((slot == "sos" || slot.matches(Regex("roadside-booking-[0-9]+"))) &&
                            key.matches(Regex("[A-Za-z0-9_-]{16,128}")) && hash.matches(Regex("[0-9a-f]{64}"))) {
                            pending[slot] = Pending(key, hash, created)
                            if (slot != "sos") durableScopes.add(slot)
                        }
                    }
                }
            }
        }
        pending.entries.removeAll { time - it.value.created !in 0..MAX_AGE_MS }
        durableScopes.retainAll(pending.keys)
        pending[scope]?.takeIf { it.fingerprint == fingerprint }?.let { return it.key }
        val next = Pending(UUID.randomUUID().toString(), fingerprint, time)
        pending[scope] = next
        persist()
        // Protect the 32 already persisted roadside intents; bound only the extra memory slots.
        while (pending.keys.count { it != "sos" && it !in durableScopes } > 32) {
            pending.keys.firstOrNull { it != "sos" && it != scope && it !in durableScopes }?.let { pending.remove(it) }
        }
        return next.key
    }

    private fun persist() {
        // Optional metadata must never prevent an emergency when storage is unavailable.
        runCatching {
            val saved = JSONObject()
            val roadsideSlots = (pending.keys.filter { it in durableScopes } +
                pending.keys.filter { it != "sos" && it !in durableScopes }).take(32).toSet()
            pending.forEach { (scope, record) ->
                if (scope == "sos" || scope in roadsideSlots) saved.put(scope, JSONObject().put("key", record.key)
                    .put("fingerprint", record.fingerprint).put("created", record.created))
            }
            val editor = storage?.edit()
            if (saved.length() == 0) editor?.remove(PREF_KEY) else editor?.putString(PREF_KEY, saved.toString())
            if (editor?.commit() == true) {
                durableScopes.clear()
                durableScopes.addAll(roadsideSlots)
            }
        }
    }

    fun acknowledge(key: String, scope: String = "sos") {
        if (pending[scope]?.key != key) return
        pending.remove(scope)
        // Even failed durable cleanup remains acknowledged in this process.
        persist()
    }
}
