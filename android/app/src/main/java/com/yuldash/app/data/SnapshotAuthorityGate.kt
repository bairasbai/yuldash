package com.yuldash.app.data

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class SnapshotAuthorityKind { ROLE, DETAILS, HISTORY }
internal class SnapshotReadTicket internal constructor(
    val ticket: SnapshotTicket, val bookingId: Int, val kind: SnapshotAuthorityKind, internal val epoch: Long,
)
internal class SnapshotMutationTicket internal constructor(val ticket: SnapshotTicket, val bookingId: Int, internal val epoch: Long)
internal class SnapshotNetworkProof internal constructor(
    internal val request: SnapshotReadTicket, internal val owner: SnapshotOwner, private val stillVerified: () -> Boolean,
) { internal fun verified() = stillVerified.invoke() }

/** Opaque capability: only the issuing gate's CURRENT instance is accepted. No JSON serialization. */
internal class SnapshotAuthorityPermit internal constructor(
    val kind: SnapshotAuthorityKind, val owner: SnapshotOwner, val bookingId: Int,
    internal val ticket: SnapshotTicket, internal val input: SnapshotState?, internal val state: SnapshotEpochState?,
    internal val fingerprint: String?, internal val mutation: Boolean = false,
    internal val inputRide: Int? = null, internal val inputRoute: SnapshotRoute? = null,
)

/** One shared instance per process, used by BOTH snapshot and legacy private-pass read boundaries.
 * Empty means denied, including an intact ciphertext and durable owner scope after a process death.
 * This gate has no disk/Keystore/auth mutation and never promises an unfailable persistent write.
 */
internal class SnapshotAuthorityGate(private val activeOwner: AtomicReference<SnapshotOwner?>) {
    private data class Key(val owner: SnapshotOwner, val bookingId: Int)
    private data class Entry(
        var epoch: Long = 0, var historyEpoch: Long = 0, var state: SnapshotEpochState? = null,
        var rideId: Int? = null, var route: SnapshotRoute? = null,
        var closed: Boolean = false, var lastRead: Long = 0, var detailsRead: Long = 0, var lastHistory: Long = 0,
        var lastSuccessfulCodeSequence: Long = 0,
        var cacheBlocked: Boolean = false, val expiredCandidates: MutableSet<String> = mutableSetOf(),
        var live: SnapshotAuthorityPermit? = null, var privateDetails: SnapshotAuthorityPermit? = null,
        var history: SnapshotAuthorityPermit? = null, var write: SnapshotAuthorityPermit? = null,
    )
    private val process = UUID.randomUUID().toString()
    private var sequence = 0L
    private val entries = mutableMapOf<Key, Entry>()
    private val closedRides = mutableSetOf<Pair<SnapshotOwner, Int>>()
    private val retiredScopes = mutableSetOf<String>()
    private fun current(owner: SnapshotOwner) = owner.valid && activeOwner.get() == owner && owner.namespace !in retiredScopes
    private fun entry(owner: SnapshotOwner, bookingId: Int) = entries.getOrPut(Key(owner, bookingId)) { Entry() }
    private fun validFingerprint(value: String?) = value?.matches(Regex("[0-9a-f]{64}")) == true
    @Synchronized fun ticket(): SnapshotTicket? = activeOwner.get()?.takeIf(::current)?.let {
        SnapshotTicket(it, process, Math.addExact(sequence, 1).also { next -> sequence = next })
    }
    @Synchronized fun current(ticket: SnapshotTicket): Boolean = ticket.process == process && ticket.sequence in 1L..sequence && current(ticket.owner)
    @Synchronized fun beginRead(bookingId: Int, kind: SnapshotAuthorityKind): SnapshotReadTicket? {
        if (bookingId <= 0) return null
        val ticket = ticket() ?: return null
        val value = entry(ticket.owner, bookingId)
        return SnapshotReadTicket(ticket, bookingId, kind,
            if (kind == SnapshotAuthorityKind.HISTORY) value.historyEpoch else value.epoch)
    }
    @Synchronized fun beginMutation(bookingId: Int): SnapshotMutationTicket? {
        if (bookingId <= 0) return null
        val ticket = ticket() ?: return null
        return SnapshotMutationTicket(ticket, bookingId, entry(ticket.owner, bookingId).epoch)
    }
    private fun validated(proof: SnapshotNetworkProof): Pair<SnapshotReadTicket, Entry>? {
        val request = proof.request
        if (!proof.verified() || proof.owner != request.ticket.owner || !current(request.ticket)) return null
        val value = entry(proof.owner, request.bookingId)
        val expectedEpoch = if (request.kind == SnapshotAuthorityKind.HISTORY) value.historyEpoch else value.epoch
        if (request.epoch != expectedEpoch) return null
        return request to value
    }

    @Synchronized fun withCurrentRejectedDetails(proof: SnapshotNetworkProof, failure: () -> Unit) {
        if (proof.request.kind != SnapshotAuthorityKind.DETAILS || validated(proof) == null) return
        failure()
    }

    @Synchronized fun acceptRead(
        proof: SnapshotNetworkProof, state: SnapshotState, rideId: Int? = null, route: SnapshotRoute? = null,
        contactUnlocked: Boolean = false, privateFingerprint: String? = null,
    ): SnapshotAuthorityPermit? {
        val (request, value) = validated(proof) ?: return null
        if (request.kind == SnapshotAuthorityKind.HISTORY) return null
        if (route != null && (request.kind != SnapshotAuthorityKind.DETAILS || !route.valid || (rideId ?: 0) <= 0)) return null
        if (state.status in setOf("done", "cancelled")) { close(request.ticket, request.bookingId); return null }
        if (value.closed || (rideId != null && (rideId <= 0 || (proof.owner to rideId) in closedRides))) return null
        if (value.rideId != null && rideId != null && value.rideId != rideId) return null
        val merged = value.state?.merge(state) ?: if (value.state == null) SnapshotEpochState.first(state) else null
        merged ?: return null
        val compatibleFirstDetails = request.kind == SnapshotAuthorityKind.DETAILS && value.detailsRead == 0L && merged == value.state
        if (request.ticket.sequence < value.lastRead && !compatibleFirstDetails) return null
        if (contactUnlocked && (request.kind != SnapshotAuthorityKind.DETAILS || !validFingerprint(privateFingerprint) || (rideId ?: 0) <= 0)) return null
        val permit = SnapshotAuthorityPermit(request.kind, proof.owner, request.bookingId, request.ticket, state, merged,
            privateFingerprint.takeIf { request.kind == SnapshotAuthorityKind.DETAILS && contactUnlocked },
            inputRide = rideId, inputRoute = route)
        if (merged != value.state) value.privateDetails = null
        value.state = merged; value.rideId = rideId ?: value.rideId
        if (route != null) value.route = route
        value.lastRead = maxOf(value.lastRead, request.ticket.sequence)
        value.live = permit; value.write = permit
        if (request.kind == SnapshotAuthorityKind.DETAILS) {
            value.detailsRead = maxOf(value.detailsRead, request.ticket.sequence)
            value.privateDetails = permit.takeIf { it.fingerprint != null }
        }
        return permit
    }

    /** Only attachCode, after its accepted nonblank result, may advance this watermark. */
    @Synchronized fun recordCodeSuccess(proof: SnapshotNetworkProof): Boolean {
        val (request, value) = validated(proof) ?: return false
        if (request.kind != SnapshotAuthorityKind.DETAILS || value.closed) return false
        value.lastSuccessfulCodeSequence = maxOf(value.lastSuccessfulCodeSequence, request.ticket.sequence)
        return true
    }

    /** Blank CODE is semantic denial of these same private facts, not a generic late IO error. */
    @Synchronized fun denyBlankCode(proof: SnapshotNetworkProof, fingerprint: String, state: SnapshotEpochState): Boolean {
        val (request, value) = validated(proof) ?: return false
        if (request.kind != SnapshotAuthorityKind.DETAILS || value.closed || !validFingerprint(fingerprint) ||
            value.state != state || value.privateDetails?.fingerprint != fingerprint ||
            value.lastSuccessfulCodeSequence > request.ticket.sequence) return false
        return expireCandidate(proof.owner, request.bookingId,
            "blocked:" + snapshotHash("blank-code:${request.ticket.process}:${request.ticket.sequence}"))
    }

    /** A fresh receipt authorizes its CURRENT history projection and permanently closes live in this process. */
    @Synchronized fun acceptHistory(proof: SnapshotNetworkProof, payloadFingerprint: String): SnapshotAuthorityPermit? {
        val (request, value) = validated(proof) ?: return null
        if (request.kind != SnapshotAuthorityKind.HISTORY || !validFingerprint(payloadFingerprint) ||
            request.ticket.sequence <= value.lastHistory) return null
        close(request.ticket, request.bookingId)
        value.lastHistory = request.ticket.sequence
        return SnapshotAuthorityPermit(SnapshotAuthorityKind.HISTORY, proof.owner, request.bookingId,
            request.ticket, null, null, payloadFingerprint).also { value.history = it }
    }

    /** An accepted POST can correct a previously authorized warm state; POST alone cannot grant cold live access. */
    @Synchronized fun acknowledge(request: SnapshotMutationTicket, state: SnapshotState): SnapshotAuthorityPermit? {
        if (!current(request.ticket)) return null
        val value = entry(request.ticket.owner, request.bookingId)
        if (value.closed || request.epoch != value.epoch) return null
        if (state.status in setOf("done", "cancelled")) { close(request.ticket, request.bookingId); return null }
        val merged = value.state?.merge(state) ?: if (value.state == null) SnapshotEpochState.first(state) else null
        val warm = value.live != null
        // Invalidate older read tickets BEFORE any caller attempts a disk commit, even for an invalid ack.
        value.epoch++; value.privateDetails = null; value.live = null; value.write = null
        merged ?: return null
        value.state = merged
        val permit = SnapshotAuthorityPermit(SnapshotAuthorityKind.ROLE, request.ticket.owner, request.bookingId,
            request.ticket, state, merged, null, mutation = true)
        value.write = permit
        if (warm) value.live = permit
        return permit
    }

    /** Actual 403/404/410 closes the booking even if body IO fails. 401 suspends, but is not a terminal booking. */
    @Synchronized fun denied(request: SnapshotReadTicket, status: Int) {
        if (!current(request.ticket)) return
        if (status in setOf(403, 404, 410)) {
            entry(request.ticket.owner, request.bookingId).let { it.history = null; it.historyEpoch++ }
            close(request.ticket, request.bookingId)
        }
        else if (status == 401) invalidate(request.ticket, request.bookingId)
    }

    @Synchronized fun invalidate(ticket: SnapshotTicket, bookingId: Int) {
        if (!current(ticket) || bookingId <= 0) return
        entry(ticket.owner, bookingId).let { it.epoch++; it.historyEpoch++; it.live = null; it.privateDetails = null; it.history = null; it.write = null }
    }
    /** A time-denied candidate stays denied even if wall time later moves backwards.
     * This is a process-local soft denial, not a terminal booking or a history revocation.
     */
    @Synchronized fun expireCandidate(owner: SnapshotOwner, bookingId: Int, candidate: String): Boolean {
        if (!current(owner) || bookingId <= 0 || !candidate.matches(Regex("blocked:[0-9a-f]{64}"))) return false
        val value = entry(owner, bookingId)
        if (!value.expiredCandidates.add(candidate)) return false
        value.cacheBlocked = true; value.epoch++; value.live = null; value.privateDetails = null; value.write = null
        return true
    }
    @Synchronized fun candidateDenied(owner: SnapshotOwner, bookingId: Int, candidate: String): Boolean =
        !current(owner) || entries[Key(owner, bookingId)]?.expiredCandidates?.contains(candidate) == true

    /** Separate historical-only admission. It never creates an entry, live permit or private projection. */
    @Synchronized fun allowsHistorical(owner: SnapshotOwner, bookingId: Int, rideId: Int, candidate: String): Boolean {
        if (!current(owner) || bookingId <= 0 || rideId <= 0 || !candidate.matches(Regex("blocked:[0-9a-f]{64}")) ||
            (owner to rideId) in closedRides) return false
        val value = entries[Key(owner, bookingId)]
        return value?.closed != true && value?.cacheBlocked != true && value?.expiredCandidates?.contains(candidate) != true
    }

    /** Called only after confirmed disk/clear success, or a fresh full read of an unchanged committed candidate.
     * A sparse ROLE response, POST or old permit cannot unfreeze the saved projection.
     */
    @Synchronized fun snapshotCommitted(permit: SnapshotAuthorityPermit?, candidate: String): Boolean {
        if (permit == null || permit.kind != SnapshotAuthorityKind.DETAILS || permit.mutation ||
            permit.inputRoute?.valid != true || !current(permit.ticket)) return false
        val value = entries[Key(permit.owner, permit.bookingId)] ?: return false
        if (value.closed || value.write !== permit || candidate in value.expiredCandidates ||
            (value.rideId != null && (permit.owner to value.rideId!!) in closedRides)) return false
        value.cacheBlocked = false
        return true
    }
    @Synchronized fun close(ticket: SnapshotTicket, id: Int, ride: Boolean = false): Boolean {
        if (!current(ticket) || id <= 0) return false
        val values = if (ride) {
            closedRides += ticket.owner to id
            entries.filter { it.key.owner == ticket.owner && it.value.rideId == id }.values
        } else listOf(entry(ticket.owner, id))
        values.forEach { if (!it.closed) it.epoch++; it.closed = true; it.live = null; it.privateDetails = null; it.write = null }
        return true
    }
    @Synchronized fun retire(owner: SnapshotOwner) {
        retiredScopes += owner.namespace
        entries.filter { it.key.owner.namespace == owner.namespace }.values.forEach {
            it.closed = true; it.epoch++; it.historyEpoch++; it.live = null; it.privateDetails = null; it.history = null; it.write = null
        }
    }
    /** Only after repository header/IDs validation; a foreign owner/generation cannot revoke current rights.
     * Ride facts revoke every known booking of that ride. History survives booking/ride closure.
     */
    @Synchronized fun durableTerminals(
        owner: SnapshotOwner, bookings: Set<Int> = emptySet(), rides: Set<Int> = emptySet(), scopeRetired: Boolean = false,
    ): Boolean {
        if (!current(owner) || bookings.any { it <= 0 } || rides.any { it <= 0 }) return false
        if (scopeRetired) { retire(owner); return true }
        if (bookings.isEmpty() && rides.isEmpty()) return true
        val ticket = ticket() ?: return false
        rides.forEach { close(ticket, it, ride = true) }
        bookings.forEach { close(ticket, it) }
        return true
    }
    @Synchronized fun canObserve(
        permit: SnapshotAuthorityPermit?, ticket: SnapshotTicket, bookingId: Int, state: SnapshotState,
        rideId: Int? = null, route: SnapshotRoute? = null,
    ): Boolean {
        if (permit == null || !current(ticket) || permit.owner != ticket.owner || permit.bookingId != bookingId ||
            permit.ticket != ticket || permit.input != state || permit.inputRide != rideId || permit.inputRoute != route ||
            permit.kind == SnapshotAuthorityKind.HISTORY) return false
        val value = entries[Key(ticket.owner, bookingId)] ?: return false
        return !value.closed && value.write === permit && (value.rideId == null || (ticket.owner to value.rideId!!) !in closedRides)
    }
    @Synchronized fun allowsSnapshot(
        owner: SnapshotOwner, bookingId: Int, state: SnapshotEpochState, rideId: Int?, route: SnapshotRoute? = null,
    ): Boolean {
        val value = entries[Key(owner, bookingId)] ?: return false
        return current(owner) && !value.closed && !value.cacheBlocked && value.live != null &&
            value.state?.let { sameSnapshotProjection(it, state) } == true &&
            (rideId == null || (owner to rideId) !in closedRides) && (value.rideId == null || value.rideId == rideId) &&
            (value.route == null || value.route == route)
    }
    /** Caller supplies the fingerprint of the ACTUAL projection being displayed, not just its booking ID.
     * Time/lifecycle validity remains mandatory at the projection's own read/draw boundary.
     */
    @Synchronized fun allowsPrivate(owner: SnapshotOwner, bookingId: Int, payloadFingerprint: String): Boolean {
        val value = entries[Key(owner, bookingId)] ?: return false
        return current(owner) && !value.closed && value.live != null && value.privateDetails?.fingerprint == payloadFingerprint &&
            validFingerprint(payloadFingerprint) && value.privateDetails?.state == value.state &&
            (value.rideId == null || (owner to value.rideId!!) !in closedRides)
    }
    @Synchronized fun allowsHistory(owner: SnapshotOwner, bookingId: Int, payloadFingerprint: String): Boolean =
        current(owner) && validFingerprint(payloadFingerprint) && entries[Key(owner, bookingId)]?.history?.fingerprint == payloadFingerprint
}
