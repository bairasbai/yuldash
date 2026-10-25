package com.yuldash.app

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.BoardingApi
import com.yuldash.app.data.BoardingResult
import com.yuldash.app.data.BoardingUndoTerms
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.NoShowTerms
import com.yuldash.app.data.afterRefusal
import com.yuldash.app.data.parseBoardingUndo
import com.yuldash.app.data.sameBoardingTimestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Server seconds become a deadline once per accepted reply, never when a lazy row is rendered. */
private fun cabinetNoShowTerms(dto: DriverBookingDto, previous: NoShowTerms? = null): NoShowTerms {
    val receivedWait = dto.noShowWaitSec.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000L }
    return NoShowTerms(dto.noShowAt, dto.noShowNeedsArriving,
        waitUntilMs = listOfNotNull(previous?.waitUntilMs, receivedWait).maxOrNull(),
        arrivingOpensAt = dto.arrivingOpensAt, arrivingWaitUntilMs = previous?.arrivingWaitUntilMs)
}

/** Only the open cabinet owns this projection. Disposing one lazy row cannot discard an ACK. */
internal class DriverCabinetBoardingState(
    val owner: Long,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val currentOwner: () -> Boolean = { ApiClient.isCurrentSession(owner) },
    private val readRows: suspend (Long) -> Result<List<DriverBookingDto>> = { ApiClient.getDriverBookings(it) },
) {
    internal class Row(dto: DriverBookingDto, now: Long) {
        val gate = BoardingUndoState()
        var status by mutableStateOf(dto.status)
        var undo by mutableStateOf(dto.boardingUndo)
        var deadline by mutableStateOf(if (dto.status == "onboard") now + dto.boardingUndoSec.coerceAtLeast(0) * 1000L else 0L)
        var noShow by mutableStateOf(cabinetNoShowTerms(dto))
        var noShowSent by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var mutationRevision = 0L
        var action = 0L
        init { gate.accept(dto.status, dto.status, dto.boardingUndo) }
    }

    internal data class ReadTicket(val id: Long, val revisions: Map<Int, Long>)
    private data class ActionTicket(val id: Long, val revision: Long)
    private enum class Action { Mark, Undo, NoShow, Arriving }
    private val states = mutableMapOf<Int, Row>()
    private var baseRows by mutableStateOf<List<DriverBookingDto>>(emptyList())
    private var latestRead = 0L
    private var nextAction = 0L

    val effectiveRows: List<DriverBookingDto>
        get() = baseRows.map { dto ->
            states[dto.bookingId]?.let { row ->
                dto.copy(status = row.status, boardingUndo = row.undo, boardingUndoSec = remaining(row),
                    noShowAt = row.noShow.noShowAt, noShowNeedsArriving = row.noShow.needsArriving,
                    arrivingOpensAt = row.noShow.arrivingOpensAt)
            } ?: dto
        }

    fun row(bookingId: Int): Row? = states[bookingId]
    fun remaining(bookingId: Int): Int = states[bookingId]?.let(::remaining) ?: 0
    private fun remaining(row: Row): Int = ((row.deadline - now()).coerceAtLeast(0) + 999L)
        .div(1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun beginRead(): ReadTicket = ReadTicket(++latestRead, states.mapValues { it.value.mutationRevision })

    /** Latest-issued wins even if that request failed: an older payload is never a fallback. */
    fun acceptRead(ticket: ReadTicket, incoming: List<DriverBookingDto>): Boolean {
        if (!currentOwner() || ticket.id != latestRead) return false
        val previous = baseRows.associateBy { it.bookingId }
        val merged = incoming.map { dto ->
            val row = states[dto.bookingId]
            if (row == null) {
                states[dto.bookingId] = Row(dto, now())
                dto
            } else if (ticket.revisions[dto.bookingId] != row.mutationRevision) {
                previous[dto.bookingId] ?: dto
            } else {
                val terminal = dto.status == "done" || dto.status == "cancelled"
                if (!row.busy || terminal) {
                    if (apply(row, BoardingResult(dto.status, dto.boardingUndoSec, dto.boardingUndo))) {
                        row.noShow = cabinetNoShowTerms(dto, row.noShow)
                        row.noShowSent = false
                        if (terminal && row.busy) row.mutationRevision++
                    } else if (terminal && row.status == dto.status) {
                        // Same terminal state only retires the transient note; the gate remains sealed.
                        row.noShowSent = false
                    }
                }
                dto
            }
        }.toMutableList()
        val received = incoming.map { it.bookingId }.toSet()
        previous.forEach { (id, dto) ->
            val row = states.getValue(id)
            if (id !in received && (row.busy || ticket.revisions[id] != row.mutationRevision)) merged += dto
        }
        baseRows = merged
        return true
    }

    suspend fun reload() {
        if (!currentOwner()) return
        val ticket = beginRead()
        val result = readRows(owner)
        currentCoroutineContext().ensureActive()
        result.onSuccess { acceptRead(ticket, it) }
    }

    private fun apply(row: Row, result: BoardingResult): Boolean {
        val incoming = result.status.ifBlank { row.status }
        val oldStatus = row.status
        val sameEpoch = row.undo.onboardAt == result.undo.onboardAt ||
            sameBoardingTimestamp(row.undo.onboardAt, result.undo.onboardAt)
        if (!row.gate.accept(row.status, incoming, result.undo)) return false
        row.status = incoming
        row.undo = result.undo
        row.deadline = if (incoming != "onboard") 0L else {
            val received = now() + result.undoSec.coerceAtLeast(0) * 1000L
            if (oldStatus == "onboard" && sameEpoch) minOf(row.deadline, received) else received
        }
        return true
    }

    fun mark(bookingId: Int, onFailure: (Throwable) -> Unit): Boolean =
        launch(bookingId, Action.Mark, onFailure)

    fun undo(bookingId: Int, onFailure: (Throwable) -> Unit): Boolean =
        launch(bookingId, Action.Undo, onFailure)

    fun noShow(bookingId: Int, onFailure: (Throwable) -> Unit, onFinished: () -> Unit): Boolean =
        launch(bookingId, Action.NoShow, onFailure, onFinished = onFinished)

    fun arriving(bookingId: Int, onFailure: (Throwable) -> Unit, onSuccess: () -> Unit): Boolean =
        launch(bookingId, Action.Arriving, onFailure, onSuccess = onSuccess)

    private fun launch(
        bookingId: Int, action: Action, onFailure: (Throwable) -> Unit,
        onSuccess: () -> Unit = {}, onFinished: () -> Unit = {},
    ): Boolean {
        val row = states[bookingId] ?: return false
        if (!currentOwner() || row.busy || row.status !in setOf("confirmed", "onboard")) return false
        if (action == Action.Undo) {
            if (row.status != "onboard" || row.undo.pending || remaining(row) <= 0) return false
        } else if (row.status != "confirmed") return false
        row.busy = true
        row.mutationRevision++
        val ticket = ActionTicket(++nextAction, row.mutationRevision)
        row.action = ticket.id
        scope.launch {
            fun current() = currentOwner() && row.action == ticket.id && row.mutationRevision == ticket.revision
            try {
                when (action) {
                    Action.Mark, Action.Undo -> {
                        val result = if (action == Action.Mark) BoardingApi.markBoarded(bookingId, owner)
                            else BoardingApi.undoBoarded(bookingId, owner)
                        currentCoroutineContext().ensureActive()
                        result.onSuccess {
                            if (current() && row.gate.resultIsCurrent(row.status, it) && apply(row, it)) {
                                row.gate.actionCommitted()
                                onSuccess()
                            }
                        }.onFailure {
                            if (current()) {
                                if (action == Action.Undo && it is ApiException && it.status == 409) row.deadline = 0L
                                onFailure(it)
                            }
                        }
                    }
                    Action.NoShow -> {
                        val result = ApiClient.markNoShow(bookingId, owner)
                        currentCoroutineContext().ensureActive()
                        result.onSuccess {
                            if (current() && apply(row, BoardingResult("cancelled", 0))) {
                                row.noShowSent = true
                                row.gate.actionCommitted()
                                onSuccess()
                            }
                        }.onFailure { error ->
                            if (current()) {
                                if (error is ApiException) {
                                    if (error.detailCode == "passenger_onboard") error.detail?.let {
                                        apply(row, BoardingResult("onboard", it.optInt("boarding_undo_sec").coerceAtLeast(0), parseBoardingUndo(it)))
                                    }
                                    row.noShow.afterRefusal(error.detailCode, error.detail)?.let { row.noShow = it }
                                }
                                onFailure(error)
                            }
                        }
                    }
                    Action.Arriving -> {
                        val result = BoardingApi.markArriving(bookingId, owner)
                        currentCoroutineContext().ensureActive()
                        result.onSuccess { if (current()) { row.noShow = it; onSuccess() } }
                            .onFailure { error ->
                                if (current()) {
                                    if (error is ApiException) row.noShow.afterRefusal(error.detailCode, error.detail)?.let { row.noShow = it }
                                    onFailure(error)
                                }
                            }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (row.action == ticket.id) {
                    row.busy = false
                    row.mutationRevision++
                }
                if (currentOwner()) onFinished()
            }
            // One reconciliation read after this explicit action; never replay its POST.
            if (currentOwner()) reload()
        }
        return true
    }
}
