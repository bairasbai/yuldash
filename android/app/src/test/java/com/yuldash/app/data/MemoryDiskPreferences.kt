package com.yuldash.app.data

import android.content.SharedPreferences

/** SharedPreferences fault model: a failed disk write can still change process memory. */
internal class MemoryDiskPreferences(private val disk: MutableMap<String, Any> = mutableMapOf()) : SharedPreferences {
    private val memory = HashMap(disk)
    var failMarkerRemoval = false
    var failMarkerRestore = false
    var failRemovalOf: String? = null
    var failWriteOf: String? = null
    fun restarted() = MemoryDiskPreferences(disk)
    override fun getAll(): MutableMap<String, *> = HashMap(memory)
    override fun contains(key: String?) = memory.containsKey(key)
    override fun getString(key: String?, defValue: String?) = memory[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = memory[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String?, defValue: Int) = memory[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = memory[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = memory[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = memory[key] as Boolean? ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearing = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor { changes[requireNotNull(key)] = value; return this }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toMutableSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor { clearing = true; return this }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (failMarkerRestore && changes[OfflineStoreReset.PENDING] == true) return false
            if (clearing) memory.clear()
            changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
            if (failWriteOf?.let { changes[it] != null } == true) return false
            if (failRemovalOf?.let { clearing || (changes.containsKey(it) && changes[it] == null) } == true) return false
            if (failMarkerRemoval && changes.containsKey(OfflineStoreReset.PENDING) && changes[OfflineStoreReset.PENDING] == null) return false
            disk.clear()
            disk.putAll(memory)
            return true
        }
    }
}
