package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Storage only. The caller owns exactly one repository/shared gate and decides when to retire/purge.
 * Injected stores support fault tests; production opens through [open] and never stores payload in meta.
 * Keep the SAME meta object when reopening an encrypted wrapper: it is the recovery scope for both.
 */
internal class SnapshotPreferencesDisk(
    private val meta: SharedPreferences,
    private val secure: SharedPreferences?,
) : SnapshotDisk {
    init { require(meta !== secure) }
    private fun requireNamespace(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
    private fun barrierKey(value: String) { require(value.matches(Regex("[0-9a-f]{64}:[0-9a-f]{64}"))) }
    private fun barrierValue(value: String) {
        require(value in setOf("closed", "blocked", "retired") || value.matches(Regex("blocked:[0-9a-f]{64}")))
    }
    private fun barriers(): JSONObject = readOfflineString(meta, meta, SNAPSHOT_BARRIERS_KEY)?.let(::JSONObject)
        ?.also { values -> values.keys().asSequence().forEach { key ->
            barrierKey(key)
            require(values.get(key) is String)
            barrierValue(values.getString(key))
        } } ?: JSONObject()

    @Synchronized override fun read(namespace: String): String? {
        requireNamespace(namespace)
        val store = secure ?: return null
        return readOfflineString(store, meta, namespace)
    }
    @Synchronized override fun write(namespace: String, value: String): Boolean {
        requireNamespace(namespace)
        return secure?.let { commitOfflineString(it, namespace, value, meta) } == true
    }
    @Synchronized override fun barrier(key: String): String? {
        barrierKey(key)
        val values = barriers()
        return if (values.has(key)) values.getString(key) else null
    }
    @Synchronized override fun setBarrier(key: String, value: String?): Boolean {
        barrierKey(key); value?.let(::barrierValue)
        val values = barriers()
        val previous = if (values.has(key)) values.getString(key) else null
        if (previous in setOf("closed", "retired") && previous != value) return false
        if (value == null) values.remove(key) else values.put(key, value)
        return commitOfflineString(meta, SNAPSHOT_BARRIERS_KEY, values.toString(), meta)
    }
    @Synchronized override fun retire(namespace: String): Boolean {
        requireNamespace(namespace)
        return secure?.let { commitOfflineString(it, namespace, null, meta) } == true
    }
    @Synchronized override fun purgeExcept(namespace: String?): Boolean {
        return runCatching {
            namespace?.let(::requireNamespace)
            val store = secure ?: return@runCatching false
            val obsolete = store.all.keys.filter { it.matches(Regex("[0-9a-f]{64}")) && it != namespace }.sorted()
            // Never remove a guard before all corresponding ciphertext removals were confirmed.
            for (key in obsolete) if (!commitOfflineString(store, key, null, meta)) return@runCatching false
            val values = barriers()
            values.keys().asSequence().toList().filter { namespace == null || !it.startsWith("$namespace:") }.forEach(values::remove)
            commitOfflineString(meta, SNAPSHOT_BARRIERS_KEY, values.toString(), meta)
        }.getOrDefault(false)
    }

    companion object {
        /** keyFactory permits an isolated QA alias without opening the shared production key.
         * Opening is optional; callers must keep ordinary online work independent of cache failure.
         * No owner publication, cleanup, recovery reset, worker or auth access happens here.
         */
        fun open(context: Context): SnapshotPreferencesDisk = open(context, SnapshotPreferenceFiles()) {
            MasterKey.Builder(it).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        }

        // Custom/QA names REQUIRE an explicit key factory; they cannot silently use the shared production alias.
        fun open(context: Context, files: SnapshotPreferenceFiles, keyFactory: (Context) -> MasterKey): SnapshotPreferencesDisk {
            val app = context.applicationContext
            val meta = app.getSharedPreferences(files.meta, Context.MODE_PRIVATE)
            val secure = runCatching {
                EncryptedSharedPreferences.create(app, files.secure, keyFactory(app),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            }.getOrNull()
            return SnapshotPreferencesDisk(meta, secure)
        }
    }
}
