package com.yuldash.app.data

import android.content.Context
import com.yuldash.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

internal const val TAXI_CONSENT_PREFS = "taxi_price_consent"
internal const val TAXI_CONSENT_PENDING = "pending"
internal const val TAXI_CONSENT_LOCAL_API = "http://127.0.0.1:8000"

internal fun taxiConsentEnvironment(debug: Boolean, requested: Boolean, pinned: Boolean, actualBase: String): String? =
    TAXI_CONSENT_LOCAL_API.takeIf { debug && requested && pinned && actualBase == it }

internal data class TaxiConsentBinding(val scope: String, val origin: String)
internal data class TaxiConsentOwner(val generation: Long, val userId: Int, val binding: TaxiConsentBinding)

/** v1 slots predate server lookup. They remain barriers but never authorize a request. */
internal data class TaxiConsentMarker(val binding: TaxiConsentBinding, val slot: String,
    val requestKey: String?, val closeRequested: Boolean = false)

internal class TaxiConsentMarkerJournal(private val read: () -> String?, private val write: (String?) -> Boolean) {
    private val versions = mutableMapOf<TaxiConsentBinding, Long>()
    @Synchronized fun version(binding: TaxiConsentBinding): Long = versions[binding] ?: 0L
    private val uncertain = mutableSetOf<TaxiConsentBinding>()
    private fun records(): List<TaxiConsentMarker> {
        val text = read() ?: return emptyList()
        val rows = JSONArray(text)
        // Both journal versions wrote compact JSONArray.toString() and removed empty journals.
        // Android's permissive parser would otherwise accept e.g. trailing data or duplicate keys.
        require(rows.length() > 0 && rows.toString() == text) { "Malformed consent journal" }
        return (0 until rows.length()).map { i ->
            val o = rows.getJSONObject(i)
            val binding = TaxiConsentBinding(o.get("scope") as String, o.get("origin") as String)
            require(taxiConsentUuid(binding.scope) && snapshotOrigin(binding.origin) == binding.origin)
            val keys = o.keys().asSequence().toSet()
            when (o.get("v")) {
                1 -> {
                    require(keys == setOf("v", "slot", "scope", "origin"))
                    val slot = o.get("slot") as String
                    require(taxiConsentUuid(slot))
                    TaxiConsentMarker(binding, slot, null)
                }
                2 -> {
                    require(keys == setOf("v", "request_key", "scope", "origin", "close_requested"))
                    val key = o.get("request_key") as String
                    require(taxiConsentUuid(key))
                    TaxiConsentMarker(binding, key, key, o.get("close_requested") as Boolean)
                }
                else -> error("Invalid consent marker version")
            }
        }.also { rowsRead ->
            require(rowsRead.distinctBy { it.binding to it.slot }.size == rowsRead.size)
        }
    }

    @Synchronized fun all(binding: TaxiConsentBinding): List<TaxiConsentMarker> {
        check(binding !in uncertain) { "Consent storage unavailable" }
        return records().filter { it.binding == binding }.sortedBy { it.slot }
    }
    @Synchronized fun marker(binding: TaxiConsentBinding, slot: String): TaxiConsentMarker? =
        all(binding).singleOrNull { it.slot == slot }
    @Synchronized fun pending(binding: TaxiConsentBinding): Boolean = all(binding).isNotEmpty()

    private fun save(binding: TaxiConsentBinding, values: List<TaxiConsentMarker>) {
        val previous = read()
        val text = values.takeIf { it.isNotEmpty() }?.let { items ->
            JSONArray().apply {
                items.forEach { m ->
                    val row = JSONObject().put("scope", m.binding.scope).put("origin", m.binding.origin)
                    if (m.requestKey == null) row.put("v", 1).put("slot", m.slot)
                    else row.put("v", 2).put("request_key", m.requestKey).put("close_requested", m.closeRequested)
                    put(row)
                }
            }.toString()
        }
        // SharedPreferences may change its memory map even when commit() reports disk failure.
        if (!runCatching { write(text) && read() == text }.getOrDefault(false)) {
            if (!runCatching { write(previous) && read() == previous }.getOrDefault(false)) uncertain += binding
            error("Consent marker storage unavailable")
        }
        versions[binding] = version(binding) + 1
    }

    @Synchronized fun mark(binding: TaxiConsentBinding, key: String, closeRequested: Boolean = false, expectedVersion: Long? = null): TaxiConsentMarker {
        require(taxiConsentUuid(binding.scope) && snapshotOrigin(binding.origin) == binding.origin && taxiConsentUuid(key))
        check(expectedVersion == null || version(binding) == expectedVersion)
        check(!pending(binding)) { "Unresolved consent" }
        val next = TaxiConsentMarker(binding, key, key, closeRequested)
        save(binding, records() + next)
        return next
    }
    @Synchronized fun fence(previous: TaxiConsentMarker): TaxiConsentMarker {
        check(previous.requestKey != null && marker(previous.binding, previous.slot) == previous)
        if (previous.closeRequested) return previous
        val next = previous.copy(closeRequested = true)
        save(previous.binding, records().map { if (it == previous) next else it })
        return next
    }
    @Synchronized fun clear(expected: TaxiConsentMarker) {
        check(marker(expected.binding, expected.slot) == expected)
        save(expected.binding, records().filterNot { it == expected })
    }
}

internal interface TaxiConsentPort {
    fun current(owner: TaxiConsentOwner): Boolean
    fun publish(owner: TaxiConsentOwner, action: () -> Unit): Boolean
    suspend fun quote(route: TaxiConsentRoute, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentQuote>
    suspend fun confirm(body: String, original: TaxiConsentQuote, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentReceipt>
    suspend fun result(key: String, close: Boolean, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiAttemptResult>
    suspend fun card(orderId: Int, owner: TaxiConsentOwner, admit: () -> Boolean): Result<InstantOrderDto>
}

/** Only these four POSTs and a current order card may use the consent transport. */
internal class TaxiConsentRequest private constructor(val path: String, val body: String?) {
    val method: String get() = if (body == null) "GET" else "POST"

    companion object {
        fun quote(route: TaxiConsentRoute) = TaxiConsentRequest("/instant/quotes/v1", route.quoteBody().toString())
        fun confirm(body: String) = TaxiConsentRequest("/instant/orders/confirmed/v1", body)
        fun result(key: String, close: Boolean): TaxiConsentRequest {
            val path = if (close) "/instant/orders/close-attempt/v1" else "/instant/orders/result/v1"
            return TaxiConsentRequest(path, JSONObject().put("request_key", key).toString())
        }
        fun card(orderId: Int) = if (orderId > 0) TaxiConsentRequest("/instant/orders/$orderId", null) else null
    }
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
        return ApiClient.taxiConsentRequest(TaxiConsentRequest.quote(route), owner, admit).mapCatching(::readTaxiConsentQuote)
    }
    override suspend fun confirm(body: String, original: TaxiConsentQuote, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiConsentReceipt> {
        if (!current(owner)) return ApiClient.staleSession()
        return ApiClient.taxiConsentRequest(TaxiConsentRequest.confirm(body), owner, admit).mapCatching {
            check(current(owner)); readTaxiConsentReceipt(it, original, owner.userId, ApiClient::parseTaxiConsentOrder)
        }
    }
    override suspend fun result(key: String, close: Boolean, owner: TaxiConsentOwner, admit: () -> Boolean): Result<TaxiAttemptResult> {
        if (!current(owner) || !taxiConsentUuid(key)) return ApiClient.staleSession()
        return ApiClient.taxiConsentRequest(TaxiConsentRequest.result(key, close), owner, admit)
            .mapCatching { check(current(owner)); readTaxiAttemptResult(it, close) }
    }
    override suspend fun card(orderId: Int, owner: TaxiConsentOwner, admit: () -> Boolean): Result<InstantOrderDto> {
        val request = TaxiConsentRequest.card(orderId) ?: return ApiClient.staleSession()
        return ApiClient.taxiConsentRequest(request, owner, admit).mapCatching {
            check(current(owner))
            readTaxiConsentCard(it, orderId, owner.userId, ApiClient::parseTaxiConsentOrder)
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
