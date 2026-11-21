package com.yuldash.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TaxiConsentProblem { UNAVAILABLE, STORAGE, QUOTE, PRICE_PENDING, REFRESH }
internal data class TaxiConsentTime(val wall: Long, val elapsed: Long)
internal data class TaxiConsentState(
    val quote: TaxiConsentQuote? = null, val quotedFor: TaxiConsentRoute? = null, val loading: Boolean = false, val sending: Boolean = false,
    val expired: Boolean = false, val review: Boolean = false, val updated: Boolean = false,
    val unknown: Boolean = false, val blocked: Boolean = false, val problem: TaxiConsentProblem? = null,
    val receipt: TaxiConsentReceipt? = null,
)

/** Request/quote/pair remain here, never in a Saver, preferences, outbox or process singleton. */
internal class TaxiConsentController(
    private val owner: TaxiConsentOwner, private val port: TaxiConsentPort,
    private val markers: TaxiConsentMarkerJournal, private val clock: () -> TaxiConsentTime,
) {
    private class Attempt(val route: TaxiConsentRoute, val quote: TaxiConsentQuote, val body: String, val slot: String,
        var unknown: Boolean = false)
    private val lock = Any()
    private var alive = true
    private var epoch = 0L
    private var route: TaxiConsentRoute? = null
    private var quotedRoute: TaxiConsentRoute? = null
    private var stamp: TaxiConsentStamp? = null
    private var attempt: Attempt? = null
    private val mutable = MutableStateFlow(TaxiConsentState())
    val state = mutable.asStateFlow()

    init {
        mutable.value = runCatching {
            if (!port.current(owner)) TaxiConsentState(blocked = true, problem = TaxiConsentProblem.UNAVAILABLE)
            else TaxiConsentState(blocked = markers.pending(owner.binding))
        }.getOrElse { TaxiConsentState(blocked = true, problem = TaxiConsentProblem.STORAGE) }
    }

    private fun live(): Boolean = alive && port.current(owner)
    private fun fresh(): Boolean {
        if (mutable.value.expired) return false // A rollback/expiry never resurrects the same quote.
        val now = clock()
        val valid = stamp?.fresh(now.wall, now.elapsed) == true
        if (!valid && mutable.value.quote != null) mutable.value = mutable.value.copy(expired = true)
        return valid
    }
    private fun barrier(): Boolean = try {
        val pending = markers.pending(owner.binding)
        if (pending) mutable.value = mutable.value.copy(quote = null, blocked = true, review = false)
        else if (mutable.value.blocked) mutable.value = mutable.value.copy(blocked = false, problem = null)
        pending
    } catch (_: Exception) {
        mutable.value = mutable.value.copy(quote = null, blocked = true, review = false, problem = TaxiConsentProblem.STORAGE); true
    }
    fun checkTime() = synchronized(lock) {
        if (!live()) {
            attempt = null; stamp = null; route = null; quotedRoute = null; alive = false; epoch++
            mutable.value = TaxiConsentState(blocked = true, problem = TaxiConsentProblem.UNAVAILABLE)
        } else if (attempt == null && mutable.value.receipt == null && !barrier()) fresh()
    }
    fun dismissReview() = synchronized(lock) {
        if (!mutable.value.sending) mutable.value = mutable.value.copy(review = false)
    }
    fun close() = synchronized(lock) {
        alive = false; epoch++; attempt = null; route = null; quotedRoute = null; stamp = null
        mutable.value = TaxiConsentState(blocked = true)
    }

    suspend fun refresh(input: TaxiConsentRoute?, showReview: Boolean = false) {
        val ticket: Long
        val started: TaxiConsentTime
        synchronized(lock) {
            route = input
            if (!live() || attempt != null || mutable.value.receipt != null) return
            epoch++; ticket = epoch; quotedRoute = null; stamp = null
            mutable.value = TaxiConsentState()
            try {
                if (markers.pending(owner.binding)) { mutable.value = TaxiConsentState(blocked = true); return }
            } catch (_: Exception) { mutable.value = TaxiConsentState(blocked = true, problem = TaxiConsentProblem.STORAGE); return }
            if (input == null) return
            mutable.value = TaxiConsentState(loading = true)
            started = clock()
        }
        val valid = { synchronized(lock) { live() && epoch == ticket && route == input && attempt == null } }
        val result = try {
            port.quote(input!!, owner) { valid() && runCatching { !markers.pending(owner.binding) }.getOrDefault(false) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Result.failure(error) }
        synchronized(lock) {
            if (!valid()) return
            result.fold(onSuccess = { quote ->
                if (runCatching { markers.pending(owner.binding) }.getOrDefault(true)) {
                    mutable.value = TaxiConsentState(blocked = true); return
                }
                stamp = TaxiConsentStamp(started.wall, started.elapsed, quote.deadline); quotedRoute = input
                mutable.value = TaxiConsentState(quote = quote, quotedFor = input, review = showReview, updated = showReview)
                fresh()
            }, onFailure = { error ->
                val pending = error is ApiException && error.status == 409 && error.detailCode == "taxi_quote_price_pending"
                mutable.value = TaxiConsentState(problem = if (pending) TaxiConsentProblem.PRICE_PENDING else TaxiConsentProblem.QUOTE)
            })
        }
    }

    suspend fun primary(inputNow: TaxiConsentRoute? = route) {
        val input: TaxiConsentRoute?
        synchronized(lock) {
            if (!live() || mutable.value.sending || mutable.value.loading || mutable.value.receipt != null) return
            // UNKNOWN deliberately precedes TTL and the current form/GPS inputs.
            if (attempt != null) { mutable.value = mutable.value.copy(review = true); return }
            if (barrier()) return
            route = inputNow
            if (mutable.value.quote != null && quotedRoute == route && fresh()) {
                mutable.value = mutable.value.copy(review = true, updated = false); return
            }
            input = route
        }
        refresh(input, showReview = true)
    }

    suspend fun confirm(details: TaxiConsentDetails, inputNow: TaxiConsentRoute? = route) {
        var send: Attempt? = null
        var recalculate: TaxiConsentRoute? = null
        synchronized(lock) {
            if (!live() || mutable.value.sending || mutable.value.loading || mutable.value.blocked || mutable.value.receipt != null || !mutable.value.review) return
            val previous = attempt
            if (previous != null) send = previous
            else {
                route = inputNow
                val quote = mutable.value.quote ?: return
                val input = route ?: return
                if (quotedRoute != input || !fresh()) { recalculate = input }
                else try {
                    var slot: String? = null
                    check(port.publish(owner) { slot = markers.mark(owner.binding) })
                    send = Attempt(input, quote, input.orderBody(details, quote), requireNotNull(slot))
                    attempt = send
                } catch (_: Exception) {
                    mutable.value = mutable.value.copy(review = false, blocked = true, problem = TaxiConsentProblem.STORAGE)
                    return
                }
            }
            if (send != null) mutable.value = mutable.value.copy(sending = true, problem = null)
        }
        if (recalculate != null) { refresh(recalculate, showReview = true); return }
        val pending = send ?: return
        val result = try {
            port.confirm(pending.body, pending.quote, owner) {
                synchronized(lock) { live() && attempt === pending && runCatching { markers.has(owner.binding, pending.slot) }.getOrDefault(false) }
            }
        } catch (cancel: CancellationException) {
            synchronized(lock) {
                if (live() && attempt === pending) {
                    pending.unknown = true; mutable.value = mutable.value.copy(sending = false, review = false, unknown = true)
                }
            }
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
        synchronized(lock) {
            if (!port.current(owner)) return
            result.fold(onSuccess = { receipt ->
                // Valid receipt of OUR attempt is authoritative even if GPS/form moved after send.
                // A quote's input fence must not hide an order that was actually committed.
                runCatching { port.publish(owner) { markers.clear(owner.binding, pending.slot) } }
                if (!alive || attempt !== pending) return
                attempt = null
                mutable.value = mutable.value.copy(sending = false, review = false, unknown = false, receipt = receipt)
            }, onFailure = { error ->
                if (!alive || attempt !== pending) return
                val firstDenial = !pending.unknown && error is ApiException &&
                    ((error.status == 409 && error.detailCode in setOf("taxi_quote_refresh_required", "taxi_consent_active_order")) ||
                        (error.status == 422 && error.detailCode in setOf("taxi_quote_request_invalid", "taxi_quote_payment_unavailable")))
                val cleared = firstDenial && runCatching {
                    var applied = false
                    port.publish(owner) { markers.clear(owner.binding, pending.slot); applied = true }
                    applied
                }.getOrDefault(false)
                if (cleared) {
                    attempt = null; stamp = null; quotedRoute = null
                    mutable.value = TaxiConsentState(problem = TaxiConsentProblem.REFRESH)
                } else {
                    pending.unknown = true
                    mutable.value = mutable.value.copy(sending = false, review = false, unknown = true)
                }
            })
        }
    }
}
