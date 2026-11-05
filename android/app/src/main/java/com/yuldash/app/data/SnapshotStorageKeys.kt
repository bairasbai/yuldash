package com.yuldash.app.data

// Unique names keep the existing SessionKeys source guard able to resolve writes.
internal const val SNAPSHOT_AUTH_SCOPE = "offline_trip_session_scope"
internal const val SNAPSHOT_SECURE_PREFS = "yuldash_trip_snapshot_secure_v1"
internal const val SNAPSHOT_META_PREFS = "yuldash_trip_snapshot_meta_v1"
internal const val SNAPSHOT_BARRIERS_KEY = "barriers"

internal data class SnapshotPreferenceFiles(
    val meta: String = SNAPSHOT_META_PREFS,
    val secure: String = SNAPSHOT_SECURE_PREFS,
) {
    init {
        require(meta.matches(Regex("[a-z][a-z0-9_]{1,100}")) && secure.matches(Regex("[a-z][a-z0-9_]{1,100}")))
        require(meta != secure)
    }
}
