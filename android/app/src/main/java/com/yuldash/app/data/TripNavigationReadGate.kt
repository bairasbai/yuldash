package com.yuldash.app.data

import kotlinx.coroutines.CancellationException
import okhttp3.Call

/** One offline-trip destination owns this transport. Hiding that passport closes it permanently. */
internal class TripNavigationReadGate {
    @Volatile var isOpen = true
        private set
    private val scheduled = mutableSetOf<Call>()

    fun checkOpen() {
        if (!isOpen) throw CancellationException("Trip navigation was closed")
    }

    @Synchronized fun attach(call: Call) {
        checkOpen()
        scheduled += call
    }

    @Synchronized fun release(call: Call?) {
        if (call != null) scheduled.remove(call)
    }

    fun hide(owner: SnapshotOwner, bookingId: Int) {
        val pending = synchronized(this) {
            if (!isOpen) return
            isOpen = false
            scheduled.toList().also { scheduled.clear() }
        }
        pending.forEach(Call::cancel)
        // Discard already issued permits as well as requests that still wait for a response.
        synchronized(OfflineTripSnapshots) {
            val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner == owner } ?: return
            OfflineTripSnapshots.gate.invalidate(ticket, bookingId)
            OfflineTripSnapshots.revoked(owner, bookingId)
        }
    }
}
