package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** Snapshot-only projections around the unchanged API transport and business contracts. */
internal object OfflineTripSnapshotHooks {
    private var scope: String? = null
    private var rejected = false
    @Volatile private var incompleteAuthWrite = false
    internal fun resetForTest() {
        incompleteAuthWrite = false
        OfflineTripSnapshots.resetForTest()
    }
    fun selected(value: AuthStorePolicy.Selection?) { rejected = value?.snapshotScopeRejected == true }
    fun restore(prefs: SharedPreferences?, loggedIn: Boolean, userId: Int?, generation: Long) {
        scope = resolveScope(prefs, rejected, loggedIn)
        if (scope != null) incompleteAuthWrite = false
        OfflineTripSnapshots.activate(userId, scope, generation)
    }
    fun newSession(userId: Int?, value: String, generation: Long) {
        rejected = false; scope = value; incompleteAuthWrite = false
        OfflineTripSnapshots.authReconfirmed()
        OfflineTripSnapshots.activate(userId, scope, generation)
    }
    fun clearSession(generation: Long) { scope = null; OfflineTripSnapshots.activate(null, null, generation) }
    fun refreshed(userId: Int?, generation: Long) {
        // Pair-only refresh cannot confirm the scope/identity of an interrupted new login.
        if (!incompleteAuthWrite) OfflineTripSnapshots.authReconfirmed()
        OfflineTripSnapshots.activate(userId, scope, generation)
    }
    fun resolveScope(prefs: SharedPreferences?, rejected: Boolean, loggedIn: Boolean): String? {
        if (!loggedIn || rejected || prefs == null) return null
        val read = runCatching { prefs.getString(OfflineTripSnapshots.AUTH_SCOPE, null) }
        val saved = read.getOrNull()
        if (read.isFailure || (saved != null && !runCatching { UUID.fromString(saved).toString() == saved }.getOrDefault(false))) return null
        val candidate = saved ?: UUID.randomUUID().toString()
        // Optional upgrade never changes auth_store_state or token identity.
        if (!runCatching { prefs.edit().putString(OfflineTripSnapshots.AUTH_SCOPE, candidate).commit() }.getOrDefault(false)) return null
        OfflineTripSnapshots.authReconfirmed()
        return candidate
    }

    fun authFailure(failure: Throwable): Result<Unit> {
        OfflineTripSnapshots.suspendTrust()
        return Result.failure(failure)
    }
    fun authWriteResult(saved: Boolean): Boolean {
        if (!saved) { incompleteAuthWrite = true; OfflineTripSnapshots.suspendTrust() }
        return saved
    }

    private fun denial(ticket: SnapshotTicket?, bookingId: Int) = SnapshotReadTrace {
        OfflineTripSnapshots.close(ticket, bookingId)
    }
    suspend fun details(bookingId: Int): Result<BookingDetailsDto> {
        val generation = ApiClient.queueSessionGeneration()
        val ticket = OfflineTripSnapshots.ticket()
        val denialTicket = OfflineTripSnapshots.ackTicket()
        return ApiClient.readTripSnapshotJson(bookingId, true, generation, denial(denialTicket, bookingId)).map { parseBookingDetailsDto(it) }
            .onSuccess { OfflineTripSnapshots.details(ticket ?: OfflineTripSnapshots.successfulRead(denialTicket), it) }
            .onFailure { OfflineTripSnapshots.denied(denialTicket, bookingId, it) }
    }
    suspend fun state(bookingId: Int, navigation: Boolean): SnapshotReadResult<TripStateDto> {
        val generation = ApiClient.queueSessionGeneration()
        val ticket = OfflineTripSnapshots.ticket()
        val denialTicket = OfflineTripSnapshots.ackTicket()
        val trace = denial(denialTicket, bookingId)
        val result = ApiClient.readTripSnapshotJson(bookingId, false, generation, trace).mapCatching {
            if (navigation) require(it.optString("role") in setOf("driver", "passenger") &&
                it.optString("status") in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
            TripStateDto(it.optString("role"), it.optString("status"), it.optString("driver_phase"),
                it.optBoolean("arrival_verified", false), it.optBoolean("alone_with_driver", false), it.optInt("peer_id"),
                it.optInt("boarding_undo_sec"), it.optInt("problem_report_sec"), it.optBoolean("cancelled_in_transit", false), parseBoardingUndo(it))
        }
        result.onSuccess { OfflineTripSnapshots.role(ticket ?: OfflineTripSnapshots.successfulRead(denialTicket), bookingId, it) }
            .onFailure { OfflineTripSnapshots.denied(denialTicket, bookingId, it) }
        return SnapshotReadResult(result, result.isFailure && trace.allowsFallback && ApiClient.isCurrentSession(generation), ticket)
    }

    private fun ack(generation: Long?) = OfflineTripSnapshots.ackTicket()?.takeIf { generation == null || it.owner.generation == generation }
    suspend fun tripStatus(bookingId: Int, status: String, generation: Long? = null, request: suspend () -> Result<JSONObject>): Result<Unit> {
        val ticket = ack(generation)
        return request().map {
            if (status == "done") OfflineTripSnapshots.close(ticket, bookingId) // Successful done contract is TripShare[].
            Unit
        }
    }
    suspend fun driverStatus(bookingId: Int, status: String, generation: Long? = null, request: suspend () -> Result<JSONObject>): Result<Unit> {
        val ticket = ack(generation)
        return request().map {
            if (it.optString("status") == "done") OfflineTripSnapshots.close(ticket, bookingId)
            else if (status == "boarded") OfflineTripSnapshots.boarding(ticket, bookingId, "driver", parseBoardingResult(it))
            Unit
        }
    }
    suspend fun rideTerminal(rideId: Int, request: suspend () -> Result<JSONObject>): Result<Unit> {
        val ticket = ack(null)
        return request().map {
            if (it.optString("status") in setOf("done", "cancelled")) OfflineTripSnapshots.close(ticket, rideId, ride = true)
            Unit
        }
    }
    suspend fun bookingTerminal(bookingId: Int, request: suspend () -> Result<JSONObject>): Result<Unit> {
        val ticket = ack(null)
        return request().map {
            if (it.optString("status") in setOf("done", "cancelled")) OfflineTripSnapshots.close(ticket, bookingId)
            Unit
        }
    }
    suspend fun cancel(bookingId: Int, request: suspend () -> Result<ApiClient.CancelResultDto>): Result<ApiClient.CancelResultDto> {
        val ticket = ack(null)
        return request().onSuccess { if (it.status in setOf("done", "cancelled")) OfflineTripSnapshots.close(ticket, bookingId) }
    }
}
