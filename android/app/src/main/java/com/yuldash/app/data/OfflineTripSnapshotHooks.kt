package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** Domain projections of real HTTP results. Never treats an arbitrary cached DTO as a network read. */
internal object OfflineTripSnapshotHooks {
    private var scope: String? = null
    private var rejected = false
    private var incompleteAuthWrite = false
    fun selected(value: AuthStorePolicy.Selection?) { rejected = value?.snapshotScopeRejected == true }
    fun restore(prefs: SharedPreferences?, loggedIn: Boolean, userId: Int?, generation: Long) {
        scope = if (loggedIn && !rejected && !incompleteAuthWrite) resolveScope(prefs) else null
        OfflineTripSnapshots.activate(userId.takeIf { loggedIn }, scope, generation)
    }
    private fun resolveScope(prefs: SharedPreferences?): String? {
        prefs ?: return null
        val read = runCatching { prefs.getString(SNAPSHOT_AUTH_SCOPE, null) }
        if (read.isFailure) return null
        val saved = read.getOrNull()
        if (saved != null && !runCatching { UUID.fromString(saved).toString() == saved }.getOrDefault(false)) return null
        val candidate = saved?.takeUnless(OfflineTripSnapshots::scopeRetired) ?: UUID.randomUUID().toString()
        // Confirm durability even after a previous commit(false) changed SharedPreferences memory.
        return candidate.takeIf { runCatching { prefs.edit().putString(SNAPSHOT_AUTH_SCOPE, candidate).commit() }.getOrDefault(false) }
    }
    fun newSession(userId: Int?, value: String, generation: Long) {
        scope = value; rejected = false; incompleteAuthWrite = false
        OfflineTripSnapshots.activate(userId, value, generation)
    }
    fun clearSession(generation: Long) { scope = null; OfflineTripSnapshots.activate(null, null, generation) }
    fun refreshed(userId: Int?, generation: Long) {
        OfflineTripSnapshots.activate(userId, scope.takeUnless { incompleteAuthWrite }, generation)
    }
    fun authWriteResult(saved: Boolean): Boolean {
        if (!saved) { incompleteAuthWrite = true; OfflineTripSnapshots.suspendTrust() }
        return saved
    }
    fun authFailure(failure: Throwable): Result<Unit> {
        OfflineTripSnapshots.suspendTrust()
        return Result.failure(failure)
    }
    fun resetForTest() { scope = null; rejected = false; incompleteAuthWrite = false; OfflineTripSnapshots.resetForTest() }
    private fun prepareRead() { OfflineTripSnapshots.activate(ApiClient.myUserId(), scope.takeUnless { incompleteAuthWrite }, ApiClient.queueSessionGeneration()) }
    fun acceptedPrivateChange(path: String, owner: SnapshotOwner?) {
        if (owner == null || !OfflineTripSnapshots.current(owner)) return
        val match = Regex("^/bookings/([1-9][0-9]*)/(meeting-point(?:/(?:answer|withdraw))?|confirm)$").matchEntire(path) ?: return
        match.groupValues[1].toIntOrNull()?.let { OfflineTripSnapshots.forgetDetails(it) }
    }

    suspend fun details(bookingId: Int): Result<BookingDetailsDto> {
        prepareRead()
        val started = android.os.SystemClock.elapsedRealtime()
        val read = SnapshotTransportRead(bookingId, SnapshotAuthorityKind.DETAILS, ApiClient.queueSessionGeneration(), OfflineTripSnapshots)
        val result = ApiClient.readTripSnapshotJson("/bookings/$bookingId/details", read).mapCatching {
            require(!it.has("contact_unlocked") || it.get("contact_unlocked") is Boolean)
            parseBookingDetailsDto(it, started).also { value ->
                require(value.bookingId == bookingId && value.rideId > 0 && value.role in setOf("driver", "passenger") &&
                    value.status in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
            }
        }
        if (result.isFailure && result.exceptionOrNull() !is java.io.IOException && result.exceptionOrNull() !is ApiException) read.invalid()
        if (result.isFailure) OfflineTripSnapshots.failedDetails(read.ticket, bookingId, read.fallbackAllowed)
        result.getOrNull()?.let { value -> read.proof()?.let {
            if (!OfflineTripSnapshots.acceptDetails(it, value)) {
                OfflineTripSnapshots.failedDetails(it)
                return Result.failure(ApiException(409, "", "snapshot_superseded"))
            }
        } }
        return result
    }
    suspend fun state(bookingId: Int, generation: Long = ApiClient.queueSessionGeneration()): SnapshotReadResult<TripStateDto> {
        prepareRead()
        val read = SnapshotTransportRead(bookingId, SnapshotAuthorityKind.ROLE, generation, OfflineTripSnapshots)
        val result = ApiClient.readTripSnapshotJson("/bookings/$bookingId/role", read).mapCatching { parseState(it) }
        if (result.isFailure && result.exceptionOrNull() !is java.io.IOException && result.exceptionOrNull() !is ApiException) read.invalid()
        if (result.isFailure) OfflineTripSnapshots.failedRead(read.ticket, bookingId, read.fallbackAllowed)
        val value = result.getOrNull()
        val proof = value?.let { read.proof() }
        val accepted = if (proof != null && value != null) AcceptedRoleRead.accept(proof, value) else null
        val delivered = if (proof != null && accepted == null) Result.failure(ApiException(409, "", "snapshot_superseded")) else result
        if (result.isFailure && read.fallbackAllowed) OfflineTripSnapshots.offerFallback(read.ticket, bookingId)
        return SnapshotReadResult(delivered, result.isFailure && read.fallbackAllowed, read.ticket, accepted)
    }
    private fun parseState(value: JSONObject): TripStateDto {
        require(value.optString("role") in setOf("driver", "passenger") &&
            value.optString("status") in setOf("pending", "confirmed", "onboard", "done", "cancelled"))
        require(value.optString("driver_phase") in setOf("", "departed", "arriving"))
        require(value.optString("status") != "onboard" || snapshotEpoch(parseBoardingUndo(value).onboardAt) != null)
        return TripStateDto(value.optString("role"), value.optString("status"), value.optString("driver_phase"),
            value.optBoolean("arrival_verified", false), value.optBoolean("alone_with_driver", false), value.optInt("peer_id"),
            value.optInt("boarding_undo_sec"), value.optInt("problem_report_sec"), value.optBoolean("cancelled_in_transit", false), parseBoardingUndo(value))
    }
    suspend fun code(bookingId: Int): Result<String> {
        prepareRead()
        val owner = OfflineTripSnapshots.currentOwner() ?: return ApiClient.staleSession()
        repeat(2) { attempt ->
            if (!OfflineTripSnapshots.current(owner)) return ApiClient.staleSession()
            var expected = OfflineTripSnapshots.projection(bookingId)
            val permit = expected?.permit
            if (attempt > 0 || expected == null || expected.driverCodeBlocked || permit?.input?.let {
                    OfflineTripSnapshots.gate.canObserve(permit, permit.ticket, bookingId, it, permit.inputRide, permit.inputRoute)
                } != true) {
                details(bookingId); expected = OfflineTripSnapshots.projection(bookingId)
            }
            if (!OfflineTripSnapshots.current(owner)) return ApiClient.staleSession()
            val projection = expected?.takeIf { it.details.role in setOf("driver", "passenger") &&
                !it.driverCodeBlocked && OfflineTripSnapshots.privateDetails(it.details) }
                ?: return Result.failure(IllegalStateException("Current participant details required"))
            val read = SnapshotTransportRead(bookingId, SnapshotAuthorityKind.DETAILS, owner.generation, OfflineTripSnapshots)
            var semanticBlank = false
            val result = ApiClient.readTripSnapshotJson("/bookings/$bookingId/boarding-code", read).mapCatching {
                it.getString("code").also { code ->
                    semanticBlank = it.opt("code") is String && code.isBlank()
                    require(code.isNotBlank())
                }
            }
            val code = result.getOrNull()
            val proof = read.proof()
            if (semanticBlank) {
                proof?.let { OfflineTripSnapshots.denyBlankCode(projection, it) }
                return result
            }
            if (code != null && proof != null && OfflineTripSnapshots.attachCode(projection, code, proof)) return result
            if (result.isFailure) {
                OfflineTripSnapshots.failedRead(read.ticket, bookingId, read.fallbackAllowed)
                return result
            }
            // Refresh/revision change needs another actual DETAILS+code GET, never an old blob.
        }
        return Result.failure(IllegalStateException("Participant details changed during code read"))
    }
    suspend fun receipt(bookingId: Int, generation: Long?): Result<TripReceiptDto> {
        prepareRead()
        val read = SnapshotTransportRead(bookingId, SnapshotAuthorityKind.HISTORY, generation ?: ApiClient.queueSessionGeneration(), OfflineTripSnapshots)
        return ApiClient.readTripSnapshotJson("/trips/$bookingId/receipt", read).mapCatching {
            parseTripReceiptDto(it).also { value ->
                require(value.bookingId == bookingId && value.rideId > 0 && value.role in setOf("driver", "passenger"))
            }
        }.onSuccess { value -> read.proof()?.let { OfflineTripSnapshots.acceptHistory(it, value) } }
    }
    suspend fun boarding(bookingId: Int, role: String, generation: Long, request: suspend () -> Result<JSONObject>): Result<BoardingResult> {
        val ticket = OfflineTripSnapshots.gate.beginMutation(bookingId)?.takeIf { it.ticket.owner.generation == generation }
        return request().map(::parseBoardingResult).onSuccess { OfflineTripSnapshots.acknowledge(ticket, role, it) }
    }
    suspend fun driverStatus(bookingId: Int, status: String, generation: Long, request: suspend (Long) -> Result<JSONObject>): Result<Unit> {
        val ticket = OfflineTripSnapshots.gate.beginMutation(bookingId)?.takeIf { it.ticket.owner.generation == generation }
        return request(generation).map { body ->
            when {
                body.optString("status") in setOf("done", "cancelled") -> OfflineTripSnapshots.terminal(ticket?.ticket, bookingId, body.optString("status"), "driver")
                status == "boarded" -> OfflineTripSnapshots.acknowledge(ticket, "driver", parseBoardingResult(body))
                status in setOf("departed", "arriving") -> OfflineTripSnapshots.acknowledgePhase(ticket, status, body.optString("status"))
                else -> if (ticket != null && OfflineTripSnapshots.current(ticket.ticket.owner)) OfflineTripSnapshots.forgetDetails(bookingId)
            }
        }
    }
    suspend fun tripStatus(bookingId: Int, status: String, generation: Long, request: suspend (Long) -> Result<JSONObject>): Result<Unit> {
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner.generation == generation }
        return request(generation).map { if (status == "done") OfflineTripSnapshots.terminal(ticket, bookingId, status) else if (ticket != null && OfflineTripSnapshots.current(ticket.owner)) OfflineTripSnapshots.forgetDetails(bookingId) }
    }
    suspend fun arriving(bookingId: Int, generation: Long, request: suspend () -> Result<JSONObject>): Result<NoShowTerms> {
        val ticket = OfflineTripSnapshots.gate.beginMutation(bookingId)?.takeIf { it.ticket.owner.generation == generation }
        return request().map { OfflineTripSnapshots.acknowledgePhase(ticket, "arriving", it.optString("status")); parseNoShowTerms(it) }
    }
    suspend fun bookingTerminal(bookingId: Int, request: suspend (Long) -> Result<JSONObject>): Result<Unit> {
        val generation = ApiClient.queueSessionGeneration()
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner.generation == generation }
        return request(generation).map { if (it.optString("status") in setOf("done", "cancelled")) OfflineTripSnapshots.terminal(ticket, bookingId, it.optString("status"), "driver") }
    }
    suspend fun rideTerminal(rideId: Int, request: suspend (Long) -> Result<JSONObject>): Result<Unit> {
        val generation = ApiClient.queueSessionGeneration()
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner.generation == generation }
        return request(generation).map { if (it.optString("status") in setOf("done", "cancelled")) OfflineTripSnapshots.close(ticket, rideId, true) }
    }
}

/** Acceptance of one exact ROLE payload; never a LIVE/private permit or a reusable terminal flag. */
internal class AcceptedRoleRead private constructor(
    private val proof: SnapshotNetworkProof, private val value: TripStateDto,
) {
    fun matches(bookingId: Int, owner: SnapshotOwner?, delivered: TripStateDto): Boolean {
        val request = proof.request
        if (owner == null || value !== delivered || request.bookingId != bookingId ||
            request.kind != SnapshotAuthorityKind.ROLE || proof.owner != owner ||
            !OfflineTripSnapshots.current(owner) || !proof.verified() ||
            !OfflineTripSnapshots.gate.current(request.ticket)) return false
        val current = OfflineTripSnapshots.gate.beginRead(bookingId, SnapshotAuthorityKind.ROLE) ?: return false
        val terminal = OfflineTripSnapshots.terminal(bookingId) ?: return false
        return current.ticket.owner == owner && current.epoch == request.epoch &&
            terminal.owner == owner && terminal.role == "passenger" && terminal.status == "cancelled" &&
            value.role == "passenger" && value.status == "cancelled"
    }

    companion object {
        suspend fun accept(proof: SnapshotNetworkProof, value: TripStateDto): AcceptedRoleRead? =
            if (proof.request.kind == SnapshotAuthorityKind.ROLE && OfflineTripSnapshots.acceptRole(proof, value))
                AcceptedRoleRead(proof, value) else null
    }
}
