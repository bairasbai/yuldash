package com.yuldash.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TaxiConsentProblem { UNAVAILABLE, STORAGE, QUOTE, PRICE_PENDING, REFRESH }
internal enum class TaxiConsentRecovery { LOOKUP_UNKNOWN, CLOSED, RETIRED, UNVERIFIED, UNAVAILABLE, CARD_UNAVAILABLE }
internal data class TaxiConsentTime(val wall: Long, val elapsed: Long)
internal data class TaxiConsentState(
    val quote: TaxiConsentQuote? = null, val quotedFor: TaxiConsentRoute? = null,
    val loading: Boolean = false, val sending: Boolean = false, val expired: Boolean = false,
    val review: Boolean = false, val updated: Boolean = false, val unknown: Boolean = false,
    val blocked: Boolean = false, val problem: TaxiConsentProblem? = null, val receipt: TaxiConsentReceipt? = null,
    val closeRequested: Boolean = false, val recoverable: Boolean = false, val recovering: Boolean = false, val canReplay: Boolean = false,
    val recovery: TaxiConsentRecovery? = null, val recoveredOrder: InstantOrderDto? = null,
)

/** Only this live screen owns the original bytes. A recreated controller can look up or close a key. */
internal class TaxiConsentController(
    private val owner: TaxiConsentOwner, private val port: TaxiConsentPort,
    private val markers: TaxiConsentMarkerJournal, private val clock: () -> TaxiConsentTime,
) {
    private class Attempt(val quote: TaxiConsentQuote, val body: String, val marker: TaxiConsentMarker, var unknown: Boolean = false)
    private val lock = Any()
    private var alive = true
    private var epoch = 0L
    private var route: TaxiConsentRoute? = null
    private var stamp: TaxiConsentStamp? = null
    private var quoteVersion = 0L
    private var delivered = false
    private var attempt: Attempt? = null
    // Keep the observed key even if local storage is unexpectedly cleared. Absence is not evidence.
    private var observed: TaxiConsentMarker? = null
    private val mutable = MutableStateFlow(TaxiConsentState())
    val state = mutable.asStateFlow()
    private val view get() = mutable.value

    init { syncMarker() }

    private fun live() = alive && port.current(owner)
    private fun done() = view.receipt != null || view.recoveredOrder != null
    /** The durable key spans receipt decoding, recomposition, and handoff to the visible card. */
    fun deliverOrder(isCurrentScreen: () -> Boolean, publish: (InstantOrderDto) -> Unit): Boolean = synchronized(lock) {
        val order = view.recoveredOrder ?: view.receipt?.order ?: return false
        val expected = observed ?: return false
        var shown = false
        if (alive && !delivered) port.publish(owner) {
            if (isCurrentScreen()) {
                val changed = runCatching { markers.marker(owner.binding) != expected }.getOrDefault(false)
                if (changed) {
                    mutable.value = TaxiConsentState()
                    syncMarker()
                } else {
                    publish(order)
                    delivered = true
                    shown = true
                    runCatching { markers.clear(expected) }
                }
            }
        }
        shown
    }

    private fun fresh(): Boolean {
        if (view.expired) return false
        val now = clock()
        val valid = stamp?.fresh(now.wall, now.elapsed) == true
        if (!valid && view.quote != null) mutable.value = view.copy(expired = true)
        return valid
    }
    private fun syncMarker(): Boolean {
        if (!live()) {
            close()
            mutable.value = TaxiConsentState(blocked = true, problem = TaxiConsentProblem.UNAVAILABLE)
            return true
        }
        return try {
            val actual = markers.marker(owner.binding)
            if (observed == null) observed = actual
            val known = observed
            if (known == null) {
                if (view.blocked) mutable.value = TaxiConsentState()
                false
            } else {
                val same = actual != null && actual.slot == known.slot && actual.requestKey == known.requestKey
                if (same) observed = actual
                val replay = same && attempt?.unknown == true && attempt?.marker == actual && !actual!!.closeRequested
                if (!replay) {
                    attempt = null
                    stamp = null
                }
                mutable.value = view.copy(quote = view.quote.takeIf { replay }, quotedFor = view.quotedFor.takeIf { replay },
                    review = view.review && replay, loading = false, unknown = true, blocked = !replay, canReplay = replay,
                    closeRequested = observed?.closeRequested == true, recoverable = known.requestKey != null && (same || actual == null), problem = null)
                true
            }
        } catch (_: Exception) {
            mutable.value = view.copy(quote = null, quotedFor = null, review = false, loading = false, blocked = true,
                canReplay = false, recoverable = false, problem = TaxiConsentProblem.STORAGE)
            true
        }
    }
    fun checkTime() = synchronized(lock) {
        if (!live() || (!done() && !view.sending && !view.recovering)) {
            if (!syncMarker() && view.quote != null) {
                if (markers.version(owner.binding) != quoteVersion) mutable.value = TaxiConsentState()
                else fresh()
            }
        }
    }
    fun routeChanged(input: TaxiConsentRoute?) = synchronized(lock) {
        if (route != input) {
            route = input
            if (!done() && !view.sending && !view.recovering && !syncMarker() && view.recovery != TaxiConsentRecovery.CLOSED) {
                epoch++
                stamp = null
                mutable.value = TaxiConsentState()
            }
        }
    }
    fun dismissReview() = synchronized(lock) {
        if (!view.sending) mutable.value = view.copy(review = false)
    }
    fun close() = synchronized(lock) {
        alive = false
        epoch++
        attempt = null
        observed = null
        route = null
        stamp = null
        mutable.value = TaxiConsentState(blocked = true)
    }

    suspend fun refresh(input: TaxiConsentRoute?, showReview: Boolean = false) {
        val ticket: Long
        val started: TaxiConsentTime
        val version: Long
        val updating: Boolean
        synchronized(lock) {
            if (!live() || view.sending || view.recovering || done() || syncMarker()) return
            if (view.recovery == TaxiConsentRecovery.CLOSED && !showReview) return
            updating = view.quote != null
            route = input
            ticket = ++epoch
            stamp = null
            mutable.value = TaxiConsentState(loading = input != null)
            if (input == null) return
            started = clock()
            version = markers.version(owner.binding)
        }
        val valid = { synchronized(lock) { live() && epoch == ticket && route == input && attempt == null } }
        val result = try {
            port.quote(input!!, owner) { valid() && runCatching { !markers.pending(owner.binding) && markers.version(owner.binding) == version }.getOrDefault(false) }
        } catch (cancel: CancellationException) {
            synchronized(lock) { if (valid()) mutable.value = view.copy(loading = false) }
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
        synchronized(lock) {
            if (!valid() || syncMarker()) return
            if (markers.version(owner.binding) != version) { mutable.value = TaxiConsentState(); return }
            result.fold(onSuccess = { quote ->
                stamp = TaxiConsentStamp(started.wall, started.elapsed, quote.deadline)
                quoteVersion = version
                mutable.value = TaxiConsentState(quote = quote, quotedFor = input, review = showReview, updated = showReview && updating)
                fresh()
            }, onFailure = { error ->
                val pending = error is ApiException && error.status == 409 && error.detailCode == "taxi_quote_price_pending"
                mutable.value = TaxiConsentState(problem = if (pending) TaxiConsentProblem.PRICE_PENDING else TaxiConsentProblem.QUOTE)
            })
        }
    }

    suspend fun primary(inputNow: TaxiConsentRoute? = route) {
        synchronized(lock) {
            if (!live() || view.sending || view.recovering || view.loading || done()) return
            if (syncMarker()) {
                if (view.canReplay) mutable.value = view.copy(review = true)
                return
            }
            route = inputNow
            if (view.quote != null && view.quotedFor == inputNow && markers.version(owner.binding) == quoteVersion && fresh()) {
                mutable.value = view.copy(review = true, updated = false)
                return
            }
        }
        refresh(inputNow, showReview = true)
    }

    suspend fun confirm(details: TaxiConsentDetails, inputNow: TaxiConsentRoute? = route) {
        val pending: Attempt?
        synchronized(lock) {
            if (!live() || view.sending || view.recovering || view.loading || done() || !view.review) return
            if (syncMarker()) {
                if (!view.canReplay) return
                pending = attempt ?: return
            } else {
                val quote = view.quote ?: return
                val input = inputNow ?: return
                route = input
                if (view.quotedFor != input || !fresh() || markers.version(owner.binding) != quoteVersion) pending = null
                else try {
                    val body = input.orderBody(details, quote)
                    var marked: TaxiConsentMarker? = null
                    check(port.publish(owner) { marked = markers.mark(owner.binding, quote.requestKey, expectedVersion = quoteVersion) })
                    val marker = requireNotNull(marked)
                    observed = marker
                    pending = Attempt(quote, body, marker)
                    attempt = pending
                } catch (_: Exception) {
                    mutable.value = view.copy(review = false, blocked = true, problem = TaxiConsentProblem.STORAGE)
                    return
                }
            }
            if (pending != null) mutable.value = view.copy(sending = true, canReplay = false, recoverable = false, problem = null)
        }
        if (pending == null) { refresh(inputNow, showReview = true); return }
        fun admitted(): Boolean = synchronized(lock) {
            live() && attempt === pending && runCatching { markers.marker(owner.binding) == pending.marker }.getOrDefault(false)
        }
        val result = try {
            port.confirm(pending.body, pending.quote, owner, ::admitted)
        } catch (cancel: CancellationException) {
            synchronized(lock) { if (live() && attempt === pending) unknown(pending) }
            throw cancel
        } catch (error: Exception) { Result.failure(error) }
        synchronized(lock) {
            if (!live() || attempt !== pending) return
            if (!admitted()) { unknown(pending); return }
            val receipt = result.getOrNull()
            if (receipt != null) {
                port.publish(owner) {
                    if (runCatching { markers.marker(owner.binding) == pending.marker }.getOrDefault(false)) {
                        attempt = null
                        mutable.value = TaxiConsentState(receipt = receipt)
                    } else unknown(pending)
                }
                return
            }
            val error = result.exceptionOrNull()
            val firstDenial = !pending.unknown && error is ApiException &&
                ((error.status == 409 && error.detailCode in setOf("taxi_quote_refresh_required", "taxi_consent_active_order")) ||
                    (error.status == 422 && error.detailCode in setOf("taxi_quote_request_invalid", "taxi_quote_payment_unavailable")))
            if (firstDenial && runCatching { port.publish(owner) { markers.clear(pending.marker) } }.getOrDefault(false)) {
                attempt = null
                observed = null
                stamp = null
                mutable.value = TaxiConsentState(problem = TaxiConsentProblem.REFRESH)
            } else unknown(pending)
        }
    }

    private fun unknown(pending: Attempt) {
        pending.unknown = true
        mutable.value = view.copy(sending = false, review = false, unknown = true)
        syncMarker()
    }

    suspend fun checkResult() = recover(closeRequested = false)
    suspend fun closeAttempt() = recover(closeRequested = true)

    private suspend fun recover(closeRequested: Boolean) {
        var expected: TaxiConsentMarker
        val ticket: Long
        synchronized(lock) {
            if (!live() || done() || view.sending || view.recovering || !syncMarker() || !view.recoverable) return
            val initial = observed ?: return
            val key = initial.requestKey ?: return
            var bound = initial
            try {
                if (!port.publish(owner) {
                    val actual = markers.marker(owner.binding)
                    bound = actual ?: markers.mark(owner.binding, key, closeRequested = true)
                    check(bound.slot == initial.slot && bound.requestKey == key)
                    if (closeRequested) bound = markers.fence(bound)
                }) return
            } catch (_: Exception) {
                mutable.value = view.copy(blocked = true, canReplay = false, review = false, problem = TaxiConsentProblem.STORAGE)
                return
            }
            observed = bound
            expected = bound
            ticket = ++epoch
            if (expected.closeRequested) attempt = null
            mutable.value = view.copy(closeRequested = expected.closeRequested, recovering = true, review = false, canReplay = false, blocked = true, problem = null)
        }
        fun valid() = synchronized(lock) {
            live() && epoch == ticket && runCatching { markers.marker(owner.binding) == expected }.getOrDefault(false)
        }
        val previousProof = synchronized(lock) { view.recovery?.takeIf { it in setOf(
            TaxiConsentRecovery.CARD_UNAVAILABLE, TaxiConsentRecovery.RETIRED, TaxiConsentRecovery.UNVERIFIED) } }
        var committed = false
        try {
            val result = port.result(requireNotNull(expected.requestKey), closeRequested, owner, ::valid).getOrThrow()
            if (!valid()) return
            val order = if (result is TaxiAttemptResult.Committed) {
                committed = true
                synchronized(lock) {
                    if (!valid() || !port.publish(owner) { expected = markers.fence(expected) }) return
                    observed = expected
                    attempt = null
                }
                port.card(result.orderId, owner, ::valid).getOrThrow()
            } else null
            synchronized(lock) {
                if (!valid()) return
                port.publish(owner) {
                    if (markers.marker(owner.binding) != expected) return@publish
                    if (order != null) {
                        attempt = null
                        mutable.value = TaxiConsentState(recoveredOrder = order)
                    } else if (result == TaxiAttemptResult.Closed && closeRequested) {
                        markers.clear(expected)
                        attempt = null
                        observed = null
                        stamp = null
                        mutable.value = TaxiConsentState(recovery = TaxiConsentRecovery.CLOSED)
                    } else {
                        val report = when (result) {
                            TaxiAttemptResult.Retired -> TaxiConsentRecovery.RETIRED
                            TaxiAttemptResult.Unverified -> TaxiConsentRecovery.UNVERIFIED
                            TaxiAttemptResult.LookupUnknown -> view.recovery?.takeIf {
                                it == TaxiConsentRecovery.RETIRED || it == TaxiConsentRecovery.UNVERIFIED || it == TaxiConsentRecovery.CARD_UNAVAILABLE
                            } ?: TaxiConsentRecovery.LOOKUP_UNKNOWN
                            else -> TaxiConsentRecovery.UNAVAILABLE
                        }
                        mutable.value = view.copy(recovery = report)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            synchronized(lock) {
                if (live() && epoch == ticket) mutable.value = view.copy(
                    recovery = if (committed) TaxiConsentRecovery.CARD_UNAVAILABLE else previousProof ?: TaxiConsentRecovery.UNAVAILABLE)
            }
        } finally {
            synchronized(lock) {
                if (live() && epoch == ticket && view.recovering) {
                    mutable.value = view.copy(recovering = false)
                    syncMarker()
                }
            }
        }
    }
}
