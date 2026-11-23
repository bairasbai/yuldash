package com.yuldash.app

import android.content.Context
import android.content.SharedPreferences
import com.yuldash.app.data.OfflineTripSnapshots
import java.util.UUID

/** An Activity's last Bundle can predate the acknowledgement received while it was stopped.
 * Keep only an opaque completion revision on disk. No address, order ID or account ID is stored.
 * The enclosing Bundle also requires the original durable login and API origin.
 */
internal class TaxiDraftRevision(
    private val prefs: SharedPreferences,
    private val currentOwner: () -> Boolean,
) {
    fun read(): String? = if (!currentOwner()) null else runCatching {
        prefs.getString(KEY, FIRST)?.takeIf { it == FIRST || runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }.getOrNull()

    // Only a successful create/schedule advances the revision; stale concurrent callbacks cannot
    // consume a subsequent draft. commit() completes before the successful result reaches the UI.
    fun complete(submitted: String?) = synchronized(lock) {
        if (submitted != null && submitted == read()) {
            runCatching { prefs.edit().putString(KEY, UUID.randomUUID().toString()).commit() }
        }
        Unit
    }

    companion object {
        private const val KEY = "completed_revision"
        private const val FIRST = "initial"
        private val lock = Any()

        fun forSession(context: Context, generation: Long): TaxiDraftRevision {
            val owner = OfflineTripSnapshots.currentOwner()?.takeIf { it.generation == generation }
            return TaxiDraftRevision(context.getSharedPreferences("taxi_draft_revision", Context.MODE_PRIVATE)) {
                owner != null && OfflineTripSnapshots.cacheEnabled(owner)
            }
        }
    }
}
