package com.yuldash.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Название места принадлежит последнему выбору, даже если прежний запрос отвечает позже. */
internal class LatestAddressLookup(private val scope: CoroutineScope) {
    private var revision = 0L
    private var pending: Job? = null

    /** Ручной выбор готового адреса тоже отменяет ожидающее название точки на карте. */
    fun cancel() {
        revision++
        pending?.cancel()
        pending = null
    }

    fun resolve(lookup: suspend () -> String, onResolved: (String) -> Unit): Job {
        cancel()
        val requestedRevision = revision
        return scope.launch {
            val address = lookup()
            if (isActive && requestedRevision == revision) onResolved(address)
        }.also { pending = it }
    }
}
