package com.yuldash.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BookingChatReceipt
import com.yuldash.app.data.BookingChatReceiptOwner
import com.yuldash.app.data.BookingChatReceiptStore
import com.yuldash.app.data.CHAT_RECEIPT_BATCH_SIZE
import com.yuldash.app.data.CHAT_RECEIPT_INTERVAL_MS
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.canAcknowledge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

internal data class TripChatReceiptView(
    val since: Instant? = null,
    val items: Map<Int, BookingChatReceipt> = emptyMap(),
    val unread: Int? = null,
    val revision: Long = 0,
)

internal class TripChatReceiptSync(
    private val bookingId: Int,
    private val owner: BookingChatReceiptOwner,
    private val scope: CoroutineScope,
    private val active: () -> Boolean,
) {
    private val storage = BookingChatReceiptStore(owner, bookingId)
    private val staged = mutableMapOf<Int, Boolean>()
    private var version = 0
    private var pump: Job? = null
    private var wake: Job? = null
    private var restorePending = false
    private var workPending = false

    var view by mutableStateOf(TripChatReceiptView())
        private set

    fun invalidate() {
        version++
        staged.clear()
        view = TripChatReceiptView(revision = view.revision + 1)
    }

    fun refresh() {
        restorePending = true
        runPump()
    }

    fun stage(messages: List<MessageDto>, read: Boolean) {
        val since = view.since ?: return
        if (!current()) return
        val eligible = messages.filter { message ->
            message.canAcknowledge(owner.userId, since) &&
                if (read) view.items[message.id]?.myRead != true && staged[message.id] != true
                else view.items[message.id]?.myDelivered != true && message.id !in staged
        }
        if (eligible.isEmpty()) return
        eligible.forEach { staged[it.id] = read }
        val requestedVersion = version
        scope.launch {
            try {
                withContext(Dispatchers.IO) { storage.save(eligible, read) }
                if (current() && requestedVersion == version) runPump()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (!current() || requestedVersion != version) return@launch
                eligible.forEach { if (staged[it.id] == read) staged.remove(it.id) }
                restorePending = true
                schedule(RETRY_MS)
            }
        }
    }

    private fun current(): Boolean = active() && ApiClient.bookingChatReceiptOwner(owner.generation) == owner

    private fun schedule(wait: Long) {
        if (wake?.isActive == true) return
        wake = scope.launch { delay(wait); wake = null; runPump() }
    }

    private fun runPump() {
        if (!current()) return
        workPending = true
        if (pump?.isActive == true) return
        pump = scope.launch {
            while (current() && workPending) {
                workPending = false
                val stamp = version
                val stillCurrent = { current() && version == stamp }
                try {
                    if (restorePending) {
                        restorePending = false
                        restore(stillCurrent)
                    }
                    if (stillCurrent() && !restorePending) flush(stillCurrent)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!stillCurrent()) continue
                    if (error is ApiException && error.status in setOf(403, 404, 410)) {
                        invalidate()
                        withContext(Dispatchers.IO) { runCatching { storage.clear() } }
                    } else {
                        restorePending = true
                        schedule(RETRY_MS)
                    }
                }
            }
        }
    }

    private suspend fun restore(current: () -> Boolean) {
        val first = ApiClient.getBookingChatReceipts(bookingId, null, owner.generation).getOrThrow()
        if (!current()) return
        val since = first.since
        if (since == null) {
            view = TripChatReceiptView(revision = view.revision + 1)
            staged.clear()
            withContext(Dispatchers.IO) { storage.clear() }
            return
        }
        val before = view.takeIf { it.since == since }?.items.orEmpty()
        val items = mutableMapOf<Int, BookingChatReceipt>()
        var page = first
        var cursor = 0
        while (current()) {
            page.items.forEach { items[it.messageId] = before[it.messageId]?.including(it) ?: it }
            val next = page.nextAfterId
            if (next == null) {
                view = TripChatReceiptView(since, items, page.unread, view.revision + 1)
                return
            }
            check(next > cursor)
            cursor = next
            page = ApiClient.getBookingChatReceipts(bookingId, cursor, owner.generation).getOrThrow()
            if (!current()) return
            if (page.since != since) {
                invalidate()
                restorePending = true
                schedule(RETRY_MS)
                return
            }
        }
    }

    private suspend fun flush(current: () -> Boolean) {
        val since = view.since ?: return
        val pending = withContext(Dispatchers.IO) { storage.pending(CHAT_RECEIPT_BATCH_SIZE) }
        if (!current() || pending.isEmpty()) return
        val (eligible, excluded) = pending.partition { it.message.canAcknowledge(owner.userId, since) }
        if (excluded.isNotEmpty()) withContext(Dispatchers.IO) {
            storage.settle(excluded.map { it.message.id }.toSet(), emptyList())
        }
        if (!current()) return
        if (eligible.isEmpty()) { workPending = true; return }
        val wait = withContext(Dispatchers.IO) { storage.claim(System.currentTimeMillis()) }
        if (!current()) return
        if (wait > 0) { schedule(wait); return }
        val ids = eligible.map { it.message.id }.toSet()
        val read = eligible.filter { it.read }.map { it.message.id }.toSet()
        val reply = ApiClient.confirmBookingChatReceipts(bookingId, ids, read, owner.generation).getOrThrow()
        if (!current()) return
        if (reply.since != since) { invalidate(); refresh(); return }
        withContext(Dispatchers.IO) { storage.settle(ids, reply.items) }
        if (!current()) return
        val updated = view.items.toMutableMap()
        reply.items.forEach { updated[it.messageId] = updated[it.messageId]?.including(it) ?: it }
        view = view.copy(items = updated, unread = reply.unread, revision = view.revision + 1)
        schedule(CHAT_RECEIPT_INTERVAL_MS)
    }

    private companion object { const val RETRY_MS = 3_000L }
}
