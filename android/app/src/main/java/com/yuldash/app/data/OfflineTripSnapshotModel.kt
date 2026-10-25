package com.yuldash.app.data

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A cache identity, never an API credential. Generation protects work within one process. */
internal data class SnapshotOwner(val userId: Int, val scope: String, val generation: Long) {
    val valid: Boolean get() = userId > 0 && runCatching { UUID.fromString(scope).toString() == scope }.getOrDefault(false)
    val namespace: String get() = snapshotHash("owner\n$userId\n$scope")
    fun barrier(kind: String, id: Int): String = namespace + ":" + snapshotHash("$kind\n$userId\n$scope\n$id")
}

internal fun snapshotHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal object OfflineTripSnapshotPolicy {
    // Limited saved information, not a GPS/contact authorization or a trip-duration promise.
    const val MAX_AGE_MS = 6 * 3_600_000L
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

/** Serializable equivalent of BoardingUndoState's epoch rules, including receipt-before-details. */
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
        val phase = next.phase ?: this.phase
        if (phase !in setOf("", "departed", "arriving")) return null
        return copy(status = next.status, phase = phase,
            maxOnboard = max?.toString(), corrected = correction?.toString())
    }

    companion object {
        fun first(state: SnapshotState): SnapshotEpochState? =
            SnapshotEpochState(state.role, "confirmed").merge(state)
    }
}

internal data class SnapshotTicket(val owner: SnapshotOwner, val process: String, val sequence: Long)

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

/** Failure provenance is collected at the original GET wire, never inferred from IOException. */
internal class SnapshotReadTrace(private val onAccessDenied: suspend (Int) -> Unit = {}) {
    private var saw401 = false
    private var denied = false
    private var wireFailure = false
    private var http5xx = false
    private var wire = false
    private var denialCode: Int? = null
    private var denialPersisted = false
    fun attempt() { wire = false; wireFailure = false; http5xx = false }
    fun startWire() { wire = true }
    fun status(code: Int) {
        if (code == 401) saw401 = true
        if (code in 400..499) denied = true
        if (code in setOf(403, 404, 410)) denialCode = code
        http5xx = code in 500..599
    }
    /** Persist observed access denial before body IO, cancellation, retry or refresh can hide it. */
    suspend fun persistAccessDenial() {
        val code = denialCode ?: return
        if (!denialPersisted) { onAccessDenied(code); denialPersisted = true }
    }
    fun refresh() { saw401 = true; wire = false }
    fun wireFailed() { wireFailure = wire }
    fun invalid() { wire = false; wireFailure = false; http5xx = false }
    val allowsFallback: Boolean get() = !saw401 && !denied && (wireFailure || http5xx)
}

internal data class SnapshotReadResult<T>(val result: Result<T>, val fallbackAllowed: Boolean, val ticket: SnapshotTicket?)
internal enum class SnapshotPersistence { COMMITTED, UNCHANGED, DURABLY_BLOCKED, NOT_DURABLE, STALE }
