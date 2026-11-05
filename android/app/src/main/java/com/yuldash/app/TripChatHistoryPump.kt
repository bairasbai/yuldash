package com.yuldash.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MessageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One owner's history pump; merging, seen marks and authority stay in TripChatState. */
internal class TripChatHistoryPump(
    private val bookingId: Int?,
    private val scope: CoroutineScope,
    private val isActive: () -> Boolean,
    /** Same owner lifetime, without temporary live/private readiness. */
    private val isParentCurrent: () -> Boolean,
    private val currentEpoch: () -> Long,
    private val currentIds: () -> Set<Int>,
    private val accept: (List<MessageDto>, Set<Int>) -> Unit,
) {
    var loading: Boolean by mutableStateOf(bookingId != null)
    var error: Boolean by mutableStateOf(false)
    var tick: Int by mutableIntStateOf(0)
    private val mutex = Mutex()
    private var version = 0L
    private var pending = false
    private var refreshJob: Job? = null
    private var lastStartedTick: Int? = null

    fun invalidate() { version++ }

    fun requestRefresh() {
        if (!isActive() || bookingId == null) return
        pending = true
        if (refreshJob?.isActive != true) refreshJob = scope.launch { drain(bookingId) }
    }

    /** Explicit/reconnect/continuity reads are not filtered by the reactive initial marker. */
    suspend fun load(id: Int): Boolean {
        if (!isActive()) return false
        pending = true
        return drain(id)
    }

    suspend fun loadInitial(id: Int, expectedTick: Int): Boolean = drain(id, expectedTick)

    /** All readers share one mutex; a signal/change during IO invalidates that snapshot. */
    private suspend fun drain(id: Int, initialTick: Int? = null): Boolean = mutex.withLock {
        if (!isActive()) return@withLock false
        if (initialTick != null) {
            if (id != bookingId || initialTick != tick) return@withLock false
            if (lastStartedTick == initialTick) return@withLock true
            pending = true
        }
        var startedUnapplied = false
        try {
            while (pending && isActive()) {
                pending = false
                loading = true
                error = false
                val requestedVersion = version
                val epoch = currentEpoch()
                // Never compare with rows that arrive after this request's server snapshot.
                val previousIds = currentIds()
                currentCoroutineContext().ensureActive()
                if (!isActive()) return@withLock false
                lastStartedTick = tick
                startedUnapplied = true
                val result = ApiClient.getMessages(id)
                currentCoroutineContext().ensureActive()
                if (!isActive()) return@withLock false
                if (requestedVersion != version || epoch != currentEpoch()) {
                    pending = true
                    continue
                }
                result.onSuccess { server -> accept(server, previousIds) }
                    .onFailure { error = true }
                startedUnapplied = false
            }
            isActive()
        } finally {
            if (isParentCurrent()) {
                loading = false
                if (startedUnapplied) error = true
            }
        }
    }
}
