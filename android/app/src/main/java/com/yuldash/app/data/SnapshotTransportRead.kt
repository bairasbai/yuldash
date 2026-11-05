package com.yuldash.app.data

/** Required process authority dependency. The application supplies its ONE shared gate. */
internal interface SnapshotTransportAuthority {
    val gate: SnapshotAuthorityGate
    fun currentOwner(): SnapshotOwner?
    fun cacheEnabled(owner: SnapshotOwner): Boolean
    fun revoked(owner: SnapshotOwner, bookingId: Int)
    suspend fun close(ticket: SnapshotTicket?, bookingId: Int)
}

/** Per logical GET. Only ApiClient's actual wire path calls these methods. */
internal class SnapshotTransportRead(
    val bookingId: Int, val kind: SnapshotAuthorityKind, val generation: Long,
    private val authority: SnapshotTransportAuthority,
) {
    private val owner = authority.currentOwner()?.takeIf { it.generation == generation }
    private var request = owner?.let { authority.gate.beginRead(bookingId, kind) }
    private var renew = false
    val trace = SnapshotReadTrace { authority.close(request?.ticket, bookingId) }
    val ticket: SnapshotTicket? get() = request?.ticket
    fun attempt() {
        trace.attempt()
        if (renew) {
            request = owner?.takeIf { authority.currentOwner() == it }
                ?.let { authority.gate.beginRead(bookingId, kind) }
            renew = false
        }
    }
    fun startWire(authorized: Boolean) { trace.startWire(authorized && owner != null && authority.currentOwner() == owner) }
    suspend fun status(status: Int) {
        trace.status(status)
        request?.let {
            // In particular 401 MUST run before body IO and before token refresh.
            authority.gate.denied(it, status)
            if (status in setOf(401, 403, 404, 410)) authority.revoked(it.ticket.owner, bookingId)
        }
        if (status == 401) renew = true
        trace.persistAccessDenial()
    }
    fun refresh() { trace.refresh() }
    fun wireFailed() { trace.wireFailed() }
    fun invalid() { trace.invalid() }
    fun proof(): SnapshotNetworkProof? {
        val actual = authority.currentOwner() ?: return null
        return request?.let { trace.confirmed200(it, actual) }
    }
    val fallbackAllowed: Boolean get() = owner != null && authority.cacheEnabled(owner) &&
        authority.currentOwner() == owner && trace.allowsFallback

    companion object {
        /** Status/undo POST denials also revoke before body IO; these contexts never mint a permit. */
        fun denialFor(path: String, generation: Long, auth: Boolean): SnapshotTransportRead? {
            if (!auth) return null
            val match = Regex("^/bookings/([1-9][0-9]*)/(driver-status|trip-status|cancel|no-show|confirm|boarded/undo(?:/respond)?)$").matchEntire(path) ?: return null
            val id = match.groupValues[1].toIntOrNull() ?: return null
            return SnapshotTransportRead(id, SnapshotAuthorityKind.ROLE, generation, OfflineTripSnapshots)
        }
    }
}
