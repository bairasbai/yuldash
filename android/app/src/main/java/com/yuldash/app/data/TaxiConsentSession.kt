package com.yuldash.app.data

import android.content.Context
import com.yuldash.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val TAXI_CONSENT_PREFS = "taxi_price_consent"
internal const val TAXI_CONSENT_PENDING = "pending"
internal const val TAXI_CONSENT_LOCAL_API = "http://127.0.0.1:8000"

internal fun taxiConsentEnvironment(debug: Boolean, requested: Boolean, pinned: Boolean, actualBase: String): String? =
    TAXI_CONSENT_LOCAL_API.takeIf { debug && requested && pinned && actualBase == it }

internal data class TaxiConsentBinding(val scope: String, val origin: String)
internal data class TaxiConsentOwner(val generation: Long, val userId: Int, val binding: TaxiConsentBinding)

/** Only opaque markers. Reads NEVER remove markers belonging to another login. */
internal class TaxiConsentMarkerJournal(private val read: () -> String?, private val write: (String?) -> Boolean) {
    private data class Marker(val slot: String, val binding: TaxiConsentBinding)
    private fun records(): List<Marker> {
        val text = read() ?: return emptyList()
        val a = JSONArray(text)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            require(o.keys().asSequence().toSet() == setOf("v", "slot", "scope", "origin") && o.get("v") == 1)
            val slot = o.getString("slot"); val scope = o.getString("scope"); val origin = o.getString("origin")
            require(taxiConsentUuid(slot) && taxiConsentUuid(scope) && snapshotOrigin(origin) == origin)
            Marker(slot, TaxiConsentBinding(scope, origin))
        }.also { require(it.map(Marker::slot).distinct().size == it.size) }
    }
    @Synchronized fun pending(binding: TaxiConsentBinding): Boolean = records().any { it.binding == binding }
    @Synchronized fun has(binding: TaxiConsentBinding, slot: String): Boolean = records().any { it.binding == binding && it.slot == slot }
    private fun save(values: List<Marker>) {
        val text = values.takeIf { it.isNotEmpty() }?.let {
            JSONArray().apply { it.forEach { m -> put(JSONObject().put("v", 1).put("slot", m.slot)
                .put("scope", m.binding.scope).put("origin", m.binding.origin)) } }.toString()
        }
        check(write(text) && read() == text) { "Consent marker storage unavailable" }
    }
    @Synchronized fun mark(binding: TaxiConsentBinding): String {
        require(taxiConsentUuid(binding.scope) && snapshotOrigin(binding.origin) == binding.origin)
        val previous = records(); check(previous.none { it.binding == binding }) { "Unresolved consent" }
        val slot = UUID.randomUUID().toString(); save(previous + Marker(slot, binding)); return slot
    }
    @Synchronized fun clear(binding: TaxiConsentBinding, slot: String) {
        val previous = records(); require(previous.any { it.slot == slot && it.binding == binding })
        save(previous.filterNot { it.slot == slot && it.binding == binding })
    }
}

internal interface TaxiConsentPort {
    fun current(owner: TaxiConsentOwner): Boolean
    fun publish(owner: TaxiConsentOwner, action: () -> Unit): Boolean
    suspend fun quote(route: TaxiConsentRoute, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentQuote>
    suspend fun confirm(body: String, original: TaxiConsentQuote, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentReceipt>
}

internal object TaxiQuoteConsentApi : TaxiConsentPort {
    val requested: Boolean get() = BuildConfig.DEBUG && BuildConfig.YULDASH_TAXI_QUOTE_CONSENT_LOCAL
    fun owner(generation: Long = ApiClient.queueSessionGeneration()): TaxiConsentOwner? {
        val base = taxiConsentEnvironment(BuildConfig.DEBUG, requested, BuildConfig.YULDASH_NATIVE_QA_PINNED_API, ApiClient.apiBase()) ?: return null
        val snapshot = OfflineTripSnapshots.currentOwner()?.takeIf { OfflineTripSnapshots.cacheEnabled(it) } ?: return null
        if (snapshot.generation != generation || snapshot.origin != base) return null
        return TaxiConsentOwner(generation, snapshot.userId, TaxiConsentBinding(snapshot.scope, base))
    }
    override fun current(owner: TaxiConsentOwner): Boolean = this.owner(owner.generation) == owner
    override fun publish(owner: TaxiConsentOwner, action: () -> Unit): Boolean {
        var applied = false
        ApiClient.runIfCurrentSession(owner.generation) { if (current(owner)) { action(); applied = true } }
        return applied
    }
    override suspend fun quote(route: TaxiConsentRoute, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentQuote> {
        if (!current(owner)) return ApiClient.staleSession()
        return ApiClient.taxiConsentRequest("/instant/quotes/v1", route.quoteBody().toString(), owner, admit).mapCatching(::readTaxiConsentQuote)
    }
    override suspend fun confirm(body: String, original: TaxiConsentQuote, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentReceipt> {
        if (!current(owner)) return ApiClient.staleSession()
        return ApiClient.taxiConsentRequest("/instant/orders/confirmed/v1", body, owner, admit).mapCatching {
            check(current(owner)); readTaxiConsentReceipt(it, original, owner.userId, ApiClient::parseTaxiConsentOrder)
        }
    }
    @Volatile private var journal: TaxiConsentMarkerJournal? = null
    @Synchronized fun markers(context: Context): TaxiConsentMarkerJournal {
        journal?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(TAXI_CONSENT_PREFS, Context.MODE_PRIVATE)
        return TaxiConsentMarkerJournal({ prefs.getString(TAXI_CONSENT_PENDING, null) }, { value ->
            val edit = prefs.edit(); if (value == null) edit.remove(TAXI_CONSENT_PENDING) else edit.putString(TAXI_CONSENT_PENDING, value)
            edit.commit()
        }).also { journal = it }
    }
}
