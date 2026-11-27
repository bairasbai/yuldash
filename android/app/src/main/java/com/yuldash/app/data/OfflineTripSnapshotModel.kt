package com.yuldash.app.data

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Cache identity, never a credential. Origin/scope survive a reopen; generation does not. */
internal data class SnapshotOwner(val userId: Int, val scope: String, val generation: Long, val origin: String) {
    val valid: Boolean get() = userId > 0 && generation >= 0 && snapshotOrigin(origin) == origin &&
        runCatching { UUID.fromString(scope).toString() == scope }.getOrDefault(false)
    val namespace: String by lazy { snapshotHash("owner\n$origin\n$userId\n$scope") }
    fun barrier(kind: String, id: Int): String = namespace + ":" + snapshotHash("$kind\n$origin\n$userId\n$scope\n$id")
}

internal fun snapshotOrigin(value: String): String? = runCatching {
    val uri = URI(value)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535)
    val port = uri.port.takeUnless { it == 80 && uri.scheme == "http" || it == 443 && uri.scheme == "https" } ?: -1
    URI(uri.scheme, null, uri.host.lowercase(), port, uri.path.orEmpty().trimEnd('/'), null, null).toASCIIString()
}.getOrNull()

internal fun snapshotHash(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val item = byte.toInt() and 0xff
            append(hex[item ushr 4])
            append(hex[item and 0x0f])
        }
    }
}

/** Historical display only: carries no private payload, current authority or action capability. */
internal data class HistoricalTripSummary(
    val owner: SnapshotOwner, val bookingId: Int, val rideId: Int, val route: SnapshotRoute,
    val state: SnapshotEpochState, val payloadStamp: SnapshotClock, val stateStamp: SnapshotClock,
    internal val candidate: String,
) {
    fun remaining(now: SnapshotClock): Long? {
        return minOf(payloadStamp.remaining(now) ?: return null, stateStamp.remaining(now) ?: return null)
    }
}

internal object OfflineTripSnapshotPolicy {
    const val MAX_AGE_MS = 6 * 3_600_000L
    const val COLD_GRANT_SCHEMA = 1
}

internal data class ColdTripPassportGrant(
    val ownerId: Int,
    val origin: String,
    val scope: String,
    val bookingId: Int,
    val rideId: Int,
    val snapshotCandidate: String,
    val sourcePrivateFingerprint: String,
    val tripPassSha256: String,
    val detailsTicket: SnapshotTicket,
    val codeTicket: SnapshotTicket,
    val grantStamp: SnapshotClock,
    val schema: Int = OfflineTripSnapshotPolicy.COLD_GRANT_SCHEMA,
) {
    val valid: Boolean
        get() = schema == OfflineTripSnapshotPolicy.COLD_GRANT_SCHEMA && ownerId > 0 &&
            snapshotOrigin(origin) == origin && runCatching { UUID.fromString(scope).toString() == scope }.getOrDefault(false) &&
            bookingId > 0 && rideId > 0 && snapshotCandidate.matches(Regex("blocked:[0-9a-f]{64}")) &&
            sourcePrivateFingerprint.matches(Regex("[0-9a-f]{64}")) && tripPassSha256.matches(Regex("[0-9a-f]{64}")) &&
            detailsTicket.sequence > 0 && codeTicket.sequence > detailsTicket.sequence &&
            detailsTicket.process == codeTicket.process && grantStamp.valid &&
            runCatching { UUID.fromString(detailsTicket.process).toString() == detailsTicket.process }.getOrDefault(false)
}

internal data class ColdTripPassport(
    val snapshot: OfflineTripSnapshot,
    val pass: TripPass,
    val grant: ColdTripPassportGrant,
) {
    fun remaining(now: SnapshotClock): Long? = minOf(
        snapshot.remaining(now) ?: return null,
        grant.grantStamp.remaining(now) ?: return null,
    )
}

internal data class SnapshotClock(val boot: Int, val wall: Long, val before: Long, val after: Long) {
    val valid: Boolean get() = boot >= 0 && wall >= 0 && before >= 0 && after >= before
    fun remaining(now: SnapshotClock): Long? = runCatching {
        require(valid && now.valid && boot == now.boot)
        val wallAge = Math.subtractExact(now.wall, wall)
        val minAge = Math.subtractExact(now.before, after)
        val maxAge = Math.subtractExact(now.after, before)
        require(wallAge >= 0 && minAge >= 0 && maxAge >= minAge)
        require(wallAge < OfflineTripSnapshotPolicy.MAX_AGE_MS && maxAge < OfflineTripSnapshotPolicy.MAX_AGE_MS)
        minOf(OfflineTripSnapshotPolicy.MAX_AGE_MS - wallAge, OfflineTripSnapshotPolicy.MAX_AGE_MS - maxAge)
    }.getOrNull()
}

/** Deliberately excludes contacts, codes, money and exact meeting points. */
internal data class SnapshotRoute(
    val from: String, val to: String, val departAt: String,
    val pickup: String?, val dropoff: String?, val hasStops: Boolean,
) {
    val valid: Boolean get() = from.isNotBlank() && to.isNotBlank() && from.length <= 500 && to.length <= 500 &&
        departAt.isNotBlank() && departAt.length <= 80 && (pickup?.length ?: 0) <= 500 && (dropoff?.length ?: 0) <= 500
}

internal data class SnapshotState(
    val role: String, val status: String, val phase: String? = null,
    val onboardAt: String? = null, val receiptSource: String? = null, val receiptAccepted: Boolean = false,
)

internal fun snapshotEpoch(value: String?): Instant? = value?.takeIf { it.isNotBlank() }?.let {
    runCatching { Instant.parse(if (it.endsWith("Z") || it.endsWith("+00:00")) it else it + "Z") }.getOrNull()
}

/** Receipt-before-details and microsecond ordering match the boarding consent contract. */
internal data class SnapshotEpochState(
    val role: String, val status: String, val phase: String = "",
    val maxOnboard: String? = null, val corrected: String? = null,
) {
    fun merge(next: SnapshotState): SnapshotEpochState? {
        if (status in setOf("done", "cancelled") || role != next.role || next.role !in setOf("passenger", "driver")) return null
        if (next.status !in setOf("confirmed", "onboard")) return null
        val known = snapshotEpoch(maxOnboard)
        val removed = snapshotEpoch(corrected)
        var max = known
        var correction = removed
        if (next.status == "onboard") {
            val received = snapshotEpoch(next.onboardAt) ?: return null
            if ((known != null && received < known) || (removed != null && received <= removed)) return null
            max = received
        } else if (next.receiptAccepted) {
            val source = snapshotEpoch(next.receiptSource) ?: return null
            if (known != null && source < known) return null
            max = source
            correction = if (removed == null || source > removed) source else removed
        } else if (status == "onboard") return null
        val nextPhase = next.phase ?: phase
        if (nextPhase !in setOf("", "departed", "arriving")) return null
        return copy(status = next.status, phase = nextPhase, maxOnboard = max?.toString(), corrected = correction?.toString())
    }

    companion object {
        fun first(state: SnapshotState): SnapshotEpochState? = SnapshotEpochState(state.role, "confirmed").merge(state)
    }
}

internal data class SnapshotTicket(val owner: SnapshotOwner, val process: String, val sequence: Long)

/** Older correction history is conservative metadata once a strictly newer onboard epoch is known. */
internal fun sameSnapshotProjection(left: SnapshotEpochState, right: SnapshotEpochState): Boolean =
    left.role == right.role && left.status == right.status && left.phase == right.phase &&
        snapshotEpoch(left.maxOnboard) == snapshotEpoch(right.maxOnboard) &&
        (left.status == "onboard" || snapshotEpoch(left.corrected) == snapshotEpoch(right.corrected))

internal data class OfflineTripSnapshot(
    val owner: SnapshotOwner, val bookingId: Int, val rideId: Int?, val state: SnapshotEpochState,
    val route: SnapshotRoute?, val payloadStamp: SnapshotClock?, val stateStamp: SnapshotClock,
    val ticketProcess: String, val stateTicket: Long, val payloadTicket: Long,
) {
    fun remaining(now: SnapshotClock): Long? {
        if (!owner.valid || bookingId <= 0 || (rideId ?: 0) <= 0 || route?.valid != true ||
            state.role !in setOf("driver", "passenger") || state.status !in setOf("confirmed", "onboard") ||
            (state.status == "onboard" && snapshotEpoch(state.maxOnboard) == null)) return null
        return minOf(payloadStamp?.remaining(now) ?: return null, stateStamp.remaining(now) ?: return null)
    }
}

/** Original GET provenance. A final fresh 200 and fallback eligibility are DIFFERENT facts. */
internal class SnapshotReadTrace(private val onAccessDenied: suspend (Int) -> Unit = {}) {
    private var saw401 = false
    private var denied = false
    private var wireFailure = false
    private var http5xx = false
    private var wire = false
    private var authorized = false
    private var code: Int? = null
    private var revision = 0L
    private var denialCode: Int? = null
    private var denialPersisted = false
    fun attempt() { revision++; wire = false; authorized = false; code = null; wireFailure = false; http5xx = false }
    fun startWire(authorized: Boolean = false) { wire = true; this.authorized = authorized }
    fun status(code: Int) {
        this.code = code
        if (code == 401) saw401 = true
        if (code in 400..499) denied = true
        if (code in setOf(403, 404, 410)) denialCode = code
        http5xx = code in 500..599
    }
    suspend fun persistAccessDenial() {
        val observed = denialCode ?: return
        if (!denialPersisted) { onAccessDenied(observed); denialPersisted = true }
    }
    fun refresh() { revision++; saw401 = true; wire = false; authorized = false }
    fun wireFailed() { revision++; wireFailure = wire }
    fun invalid() { revision++; wire = false; wireFailure = false; http5xx = false }
    val allowsFallback: Boolean get() = !saw401 && !denied && (wireFailure || http5xx)

    /** Call ONLY after transport/session AND decoded endpoint contract validation, never for a cache DTO.
     * Refresh followed by a real authorized 200 can prove this read; it still cannot allow fallback.
     */
    fun confirmed200(request: SnapshotReadTicket, observedOwner: SnapshotOwner): SnapshotNetworkProof? {
        if (!wire || !authorized || code != 200 || wireFailure || denialCode != null || observedOwner != request.ticket.owner) return null
        val captured = revision
        return SnapshotNetworkProof(request, observedOwner) {
            captured == revision && wire && authorized && code == 200 && !wireFailure
        }
    }
}

internal data class SnapshotReadResult<T>(
    val result: Result<T>, val fallbackAllowed: Boolean, val ticket: SnapshotTicket?,
    val acceptedRole: AcceptedRoleRead? = null,
)
internal enum class SnapshotPersistence { COMMITTED, UNCHANGED, DURABLY_BLOCKED, NOT_DURABLE, STALE }
