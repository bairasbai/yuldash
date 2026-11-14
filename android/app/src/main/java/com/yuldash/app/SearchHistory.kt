package com.yuldash.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FilterCity
import com.yuldash.app.data.OfflineTripSnapshots
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class SearchRoute(val from: List<FilterCity>, val to: List<FilterCity>) {
    val key: String get() = JSONArray().put(from.key()).put(to.key()).toString()

    fun normalized(): SearchRoute? {
        fun side(cities: List<FilterCity>): List<FilterCity>? {
            if (cities.size > 3 || cities.any { it.ru.isBlank() || it.ru.length > 120 }) return null
            return cities.map { city ->
                val ru = city.ru.trim()
                FilterCity(ru, city.ba.trim().takeIf { it.isNotEmpty() && it.length <= 120 } ?: ru)
            }.distinctBy { searchCityKey(it.ru) }
        }
        val origin = side(from) ?: return null
        val destination = side(to) ?: return null
        return SearchRoute(origin, destination).takeIf { origin.isNotEmpty() || destination.isNotEmpty() }
    }

    private fun List<FilterCity>.key() = JSONArray(map { searchCityKey(it.ru) }.sorted())
}

internal data class SearchHistoryEntry(val route: SearchRoute, val at: Long)

private fun searchCityKey(city: String) = city.trim().lowercase(Locale.ROOT).replace('ё', 'е')

internal object SearchHistory {
    const val PREFS = "yuldash_search"
    const val KEY = "recent_searches_v1"
    private const val LIMIT = 5
    private const val REFINE_MS = 120_000L

    /** Called only after committing a new login, before publishing the new generation. */
    fun clearForNewSession(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit() }
    }

    fun record(entries: List<SearchHistoryEntry>, route: SearchRoute, now: Long, refine: Boolean = true): List<SearchHistoryEntry> {
        val next = route.normalized() ?: return entries
        val previous = entries.firstOrNull()
        val replaced = refine && previous != null && now - previous.at in 0L until REFINE_MS &&
            extends(previous.route.from, next.from) && extends(previous.route.to, next.to)
        return (listOf(SearchHistoryEntry(next, now)) + entries.filterIndexed { index, row ->
            !(index == 0 && replaced) && row.route.key != next.key
        }).take(LIMIT)
    }

    private fun extends(before: List<FilterCity>, after: List<FilterCity>): Boolean {
        val keys = after.map { searchCityKey(it.ru) }.toSet()
        return before.all { searchCityKey(it.ru) in keys }
    }

    fun decode(raw: String?, owner: String): List<SearchHistoryEntry> = runCatching {
        val payload = JSONObject(raw ?: return emptyList())
        if (payload.optString("owner") != owner) return emptyList()
        val rows = payload.getJSONArray("rows")
        (0 until minOf(rows.length(), LIMIT)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val route = SearchRoute(cities(row.optJSONArray("from")), cities(row.optJSONArray("to"))).normalized()
            val at = row.optLong("at", -1)
            if (route != null && at >= 0) SearchHistoryEntry(route, at) else null
        }.distinctBy { it.route.key }
    }.getOrDefault(emptyList())

    private fun cities(value: JSONArray?): List<FilterCity> {
        if (value == null) return emptyList()
        return (0 until value.length()).map { index ->
            val city = value.getJSONObject(index)
            require(city.opt("ru") is String)
            FilterCity(city.getString("ru"), city.optString("ba"))
        }
    }

    fun encode(owner: String, entries: List<SearchHistoryEntry>): String {
        fun cities(values: List<FilterCity>) = JSONArray(values.map { city ->
            JSONObject().put("ru", city.ru).put("ba", city.ba)
        })
        return JSONObject().put("owner", owner).put("rows", JSONArray(entries.map { row ->
            JSONObject().put("at", row.at).put("from", cities(row.route.from)).put("to", cities(row.route.to))
        })).toString()
    }
}

/** The guest owns only in-memory rows. Signed-in rows match the same durable login scope. */
internal class NearbySearchHistoryState(private val context: Context, private val generation: Long) {
    private val signedIn = ApiClient.isCurrentSession(generation)
    private val identity = if (signedIn) OfflineTripSnapshots.currentOwner() else null
    private val store by lazy { context.getSharedPreferences(SearchHistory.PREFS, Context.MODE_PRIVATE) }
    var entries by mutableStateOf<List<SearchHistoryEntry>>(emptyList())
        private set
    var showHistoryChip by mutableStateOf(false)
        private set

    fun changeSelection(action: () -> Unit) {
        ApiClient.runIfUnchangedSession(generation, action)
    }

    fun load() {
        access { owner ->
            if (owner != null) entries = read(owner)
            showHistoryChip = entries.isNotEmpty()
        }
    }

    fun record(route: SearchRoute, refine: Boolean = true) {
        change { SearchHistory.record(it, route, System.currentTimeMillis(), refine) }
    }

    fun remove(key: String) = change { rows -> rows.filterNot { it.route.key == key } }
    fun clear() = change { emptyList() }

    fun repeat(key: String, apply: (SearchRoute) -> Unit) {
        access { owner ->
            val previous = if (owner == null) entries else read(owner)
            val route = previous.firstOrNull { it.route.key == key }?.route ?: return@access
            save(owner, SearchHistory.record(previous, route, System.currentTimeMillis(), refine = false))
            apply(route)
        }
    }

    private fun read(owner: String) = runCatching { SearchHistory.decode(store.getString(SearchHistory.KEY, null), owner) }.getOrDefault(emptyList())

    private fun change(edit: (List<SearchHistoryEntry>) -> List<SearchHistoryEntry>) {
        access { owner ->
            val previous = if (owner == null) entries else read(owner)
            val next = edit(previous)
            if (next !== previous) save(owner, next)
        }
    }

    private fun save(owner: String?, rows: List<SearchHistoryEntry>) {
        if (owner == null || runCatching {
            val edit = store.edit()
            if (rows.isEmpty()) edit.remove(SearchHistory.KEY) else edit.putString(SearchHistory.KEY, SearchHistory.encode(owner, rows))
            edit.commit()
        }.getOrDefault(false)) {
            entries = rows
            if (rows.isNotEmpty()) showHistoryChip = true
        }
    }

    private fun access(action: (String?) -> Unit) {
        if (!signedIn) {
            ApiClient.runIfLoggedOutSession(generation) { action(null) }
            return
        }
        val saved = identity ?: return
        ApiClient.runIfCurrentSession(generation) {
            if (OfflineTripSnapshots.currentOwner() == saved) action(saved.scope)
        }
    }
}
