package com.yuldash.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Disk methods report confirmed commits. A false result may have changed dirty memory. */
internal interface SnapshotDisk {
    fun read(namespace: String): String?
    fun write(namespace: String, value: String): Boolean
    fun barrier(key: String): String?
    fun setBarrier(key: String, value: String?): Boolean
    fun retire(namespace: String): Boolean
    fun purgeExcept(namespace: String?): Boolean = true
}

/** One serialized writer plus the SAME process gate used by navigation and the private passport.
 * A disk record is only a candidate. Ordinary load stays process-only; cold access additionally
 * requires the explicit grant, secure TripPass candidate and all durable barriers.
 */
internal class OfflineTripSnapshotRepository(
    private val disk: SnapshotDisk,
    private val activeOwner: AtomicReference<SnapshotOwner?>,
    private val clock: () -> SnapshotClock?,
    private val authority: SnapshotAuthorityGate,
    private val changed: () -> Unit = {},
) {
    private val writer = Mutex()
    private val quarantined = mutableSetOf<String>()
    private val observedOrders = mutableMapOf<String, Long>()
    private fun current(owner: SnapshotOwner) = owner.valid && activeOwner.get() == owner
    fun ticket(): SnapshotTicket? = authority.ticket()
    private fun valid(ticket: SnapshotTicket) = current(ticket.owner) && authority.current(ticket)
    private fun keys(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) }
    private fun number(value: JSONObject, key: String): Long = value.get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun integer(value: JSONObject, key: String): Int = Math.toIntExact(number(value, key))
    private fun string(value: JSONObject, key: String): String = value.get(key).let { require(it is String); it }
    private fun root(owner: SnapshotOwner): JSONObject = disk.read(owner.namespace)?.let {
        JSONObject(it).also { value ->
            keys(value, setOf("schema", "origin", "scope", "owner", "version", "records", "closed_bookings", "closed_rides"))
            require(integer(value, "schema") == 2 && string(value, "origin") == owner.origin &&
                string(value, "scope") == owner.scope && integer(value, "owner") == owner.userId)
            require(number(value, "version") >= 0)
            value.getJSONObject("records"); value.getJSONArray("closed_bookings"); value.getJSONArray("closed_rides")
            // Validate BOTH complete lists before publishing any denial. Even a removed record
            // must propagate the durable ride/scope fact to the shared private-pass gate.
            val bookings = ids(value, "closed_bookings")
            val rides = ids(value, "closed_rides")
            authority.durableTerminals(owner, bookings, rides)
        }
    } ?: JSONObject().put("schema", 2).put("origin", owner.origin).put("scope", owner.scope).put("owner", owner.userId)
        .put("version", 0L).put("records", JSONObject()).put("closed_bookings", JSONArray()).put("closed_rides", JSONArray())
    private fun ids(root: JSONObject, key: String): Set<Int> {
        val values = root.getJSONArray(key)
        return (0 until values.length()).map { require(values.get(it) is Int && values.getInt(it) > 0); values.getInt(it) }.toSet()
    }
    private fun closed(root: JSONObject, key: String, id: Int): Boolean = id in ids(root, key)
    private fun deny(owner: SnapshotOwner, booking: Int, ride: Int?, value: JSONObject): Boolean {
        val scopeClosed = disk.barrier(owner.barrier("scope", 0)) != null
        val bookingClosed = disk.barrier(owner.barrier("booking", booking)) == "closed" || closed(value, "closed_bookings", booking)
        val rideClosed = ride != null && (disk.barrier(owner.barrier("ride", ride)) == "closed" || closed(value, "closed_rides", ride))
        if (scopeClosed || bookingClosed || rideClosed) authority.durableTerminals(owner,
            bookings = if (bookingClosed) setOf(booking) else emptySet(),
            rides = if (rideClosed) setOf(requireNotNull(ride)) else emptySet(), scopeRetired = scopeClosed)
        return scopeClosed || bookingClosed || rideClosed
    }
    private fun stamp(value: SnapshotClock) = JSONObject().put("boot", value.boot).put("wall", value.wall)
        .put("before", value.before).put("after", value.after)
    private fun clock(value: JSONObject): SnapshotClock {
        keys(value, setOf("boot", "wall", "before", "after"))
        return SnapshotClock(integer(value, "boot"), number(value, "wall"), number(value, "before"), number(value, "after")).also { require(it.valid) }
    }
    private fun nullable(value: JSONObject, key: String): String? = if (value.get(key) == JSONObject.NULL) null else string(value, key)
    private data class SavedRecord(val snapshot: OfflineTripSnapshot, val grant: ColdTripPassportGrant?)

    private fun ticket(value: SnapshotTicket) = JSONObject().put("process", value.process).put("sequence", value.sequence)
    private fun ticket(value: JSONObject, owner: SnapshotOwner): SnapshotTicket {
        keys(value, setOf("process", "sequence"))
        val process = string(value, "process").also { require(UUID.fromString(it).toString() == it) }
        return SnapshotTicket(owner, process, number(value, "sequence")).also { require(it.sequence > 0) }
    }
    private fun encodeGrant(value: ColdTripPassportGrant): JSONObject = JSONObject()
        .put("schema", value.schema).put("owner", value.ownerId).put("origin", value.origin).put("scope", value.scope)
        .put("booking", value.bookingId).put("ride", value.rideId).put("snapshot_candidate", value.snapshotCandidate)
        .put("source_private_fingerprint", value.sourcePrivateFingerprint).put("trippass_sha256", value.tripPassSha256)
        .put("details_ticket", ticket(value.detailsTicket)).put("code_ticket", ticket(value.codeTicket))
        .put("grant_stamp", stamp(value.grantStamp))
    private fun decodeGrant(owner: SnapshotOwner, saved: OfflineTripSnapshot, value: JSONObject): ColdTripPassportGrant {
        keys(value, setOf("schema", "owner", "origin", "scope", "booking", "ride", "snapshot_candidate",
            "source_private_fingerprint", "trippass_sha256", "details_ticket", "code_ticket", "grant_stamp"))
        val grant = ColdTripPassportGrant(integer(value, "owner"), string(value, "origin"), string(value, "scope"),
            integer(value, "booking"), integer(value, "ride"), string(value, "snapshot_candidate"),
            string(value, "source_private_fingerprint"), string(value, "trippass_sha256"),
            ticket(value.getJSONObject("details_ticket"), owner), ticket(value.getJSONObject("code_ticket"), owner),
            clock(value.getJSONObject("grant_stamp")), integer(value, "schema"))
        require(grant.valid && grant.ownerId == owner.userId && grant.origin == owner.origin && grant.scope == owner.scope &&
            grant.bookingId == saved.bookingId && saved.rideId == grant.rideId && grant.snapshotCandidate == proof(saved) &&
            saved.ticketProcess == grant.codeTicket.process && saved.payloadTicket == grant.codeTicket.sequence &&
            grant.detailsTicket.process == saved.ticketProcess)
        return grant
    }
    private fun encode(value: OfflineTripSnapshot, grant: ColdTripPassportGrant? = null): JSONObject = JSONObject().put("booking", value.bookingId)
        .put("ride", value.rideId ?: JSONObject.NULL).put("role", value.state.role).put("status", value.state.status)
        .put("phase", value.state.phase).put("onboard", value.state.maxOnboard ?: JSONObject.NULL)
        .put("corrected", value.state.corrected ?: JSONObject.NULL).put("state_stamp", stamp(value.stateStamp))
        .put("payload_stamp", value.payloadStamp?.let(::stamp) ?: JSONObject.NULL)
        .put("process", value.ticketProcess).put("state_ticket", value.stateTicket).put("payload_ticket", value.payloadTicket)
        .put("route", value.route?.let { JSONObject().put("from", it.from).put("to", it.to).put("depart", it.departAt)
            .put("pickup", it.pickup ?: JSONObject.NULL).put("dropoff", it.dropoff ?: JSONObject.NULL).put("stops", it.hasStops) } ?: JSONObject.NULL)
        .apply { if (grant != null) put("cold_grant", encodeGrant(grant)) }
    /** Journal holds a candidate hash, never its personal projection. */
    private fun proof(value: OfflineTripSnapshot): String {
        fun clockArray(clock: SnapshotClock?) = clock?.let { JSONArray(listOf(it.boot, it.wall, it.before, it.after)) } ?: JSONObject.NULL
        val route = value.route?.let { JSONArray(listOf(it.from, it.to, it.departAt, it.pickup, it.dropoff, it.hasStops)) } ?: JSONObject.NULL
        return "blocked:" + snapshotHash(JSONArray(listOf(value.owner.namespace, value.bookingId, value.rideId, value.state.role,
            value.state.status, value.state.phase, value.state.maxOnboard, value.state.corrected, route,
            clockArray(value.payloadStamp), clockArray(value.stateStamp), value.ticketProcess, value.stateTicket, value.payloadTicket)).toString())
    }
    private fun decodeRecord(owner: SnapshotOwner, value: JSONObject): SavedRecord {
        val base = setOf("booking", "ride", "role", "status", "phase", "onboard", "corrected", "state_stamp",
            "payload_stamp", "process", "state_ticket", "payload_ticket", "route")
        val optional = if (value.has("cold_grant")) setOf("cold_grant") else emptySet<String>()
        keys(value, base + optional)
        val state = SnapshotEpochState(string(value, "role"), string(value, "status"), string(value, "phase"), nullable(value, "onboard"), nullable(value, "corrected"))
        require(state.role in setOf("driver", "passenger") && state.status in setOf("confirmed", "onboard"))
        require(state.phase in setOf("", "departed", "arriving"))
        require(state.maxOnboard == null || snapshotEpoch(state.maxOnboard) != null)
        require(state.corrected == null || snapshotEpoch(state.corrected) != null)
        val max = snapshotEpoch(state.maxOnboard)
        val corrected = snapshotEpoch(state.corrected)
        require(corrected == null || (max != null && corrected <= max))
        require(state.status != "onboard" || (max != null && (corrected == null || max > corrected)))
        require(state.status != "confirmed" || max == corrected)
        val route = if (value.get("route") == JSONObject.NULL) null else value.getJSONObject("route").let {
            keys(it, setOf("from", "to", "depart", "pickup", "dropoff", "stops")); require(it.get("stops") is Boolean)
            SnapshotRoute(string(it, "from"), string(it, "to"), string(it, "depart"), nullable(it, "pickup"), nullable(it, "dropoff"), it.getBoolean("stops"))
        }
        require(route == null || route.valid)
        val stamp = if (value.get("payload_stamp") == JSONObject.NULL) null else clock(value.getJSONObject("payload_stamp"))
        val ride = if (value.get("ride") == JSONObject.NULL) null else integer(value, "ride").also { require(it > 0) }
        val process = string(value, "process").also { require(UUID.fromString(it).toString() == it) }
        require(integer(value, "booking") > 0 && number(value, "state_ticket") > 0 && number(value, "payload_ticket") >= 0)
        require((route == null) == (stamp == null) && (route == null || (ride != null && number(value, "payload_ticket") > 0)))
        val saved = OfflineTripSnapshot(owner, integer(value, "booking"), ride, state, route, stamp,
            clock(value.getJSONObject("state_stamp")), process, number(value, "state_ticket"), number(value, "payload_ticket"))
        val grant = if (value.has("cold_grant")) decodeGrant(owner, saved, value.getJSONObject("cold_grant")) else null
        return SavedRecord(saved, grant)
    }
    private fun decode(owner: SnapshotOwner, value: JSONObject): OfflineTripSnapshot = decodeRecord(owner, value).snapshot

    suspend fun load(owner: SnapshotOwner, bookingId: Int): OfflineTripSnapshot? = writer.withLock {
        if (!current(owner) || owner.namespace in quarantined || bookingId <= 0) return@withLock null
        runCatching {
            val doc = root(owner)
            if (deny(owner, bookingId, null, doc)) return@withLock null
            val saved = doc.getJSONObject("records").optJSONObject(bookingId.toString())?.let { decode(owner, it) } ?: return@withLock null
            if (saved.bookingId != bookingId || deny(owner, bookingId, saved.rideId, doc) ||
                disk.barrier(owner.barrier("booking", bookingId)) != null) return@withLock null
            val candidate = proof(saved)
            if (authority.candidateDenied(owner, bookingId, candidate)) return@withLock null
            if (saved.route == null || saved.rideId == null || saved.payloadStamp == null) return@withLock null
            val now = clock()
            if (now == null || saved.remaining(now) == null) {
                if (authority.expireCandidate(owner, bookingId, candidate)) changed()
                return@withLock null
            }
            if (!current(owner) ||
                !authority.allowsSnapshot(owner, bookingId, saved.state, saved.rideId, saved.route)) null else saved
        }.getOrNull()
    }

    /**
     * Historical facts only, intentionally separate from load()/fresh authority.
     * After total write failure and process death an old historical fact can survive;
     * this result therefore NEVER asserts that a trip is currently active.
     */
    suspend fun historical(owner: SnapshotOwner): List<HistoricalTripSummary> = writer.withLock {
        if (!current(owner) || owner.namespace in quarantined) return@withLock emptyList()
        runCatching {
            val doc = root(owner)
            val now = clock() ?: return@withLock emptyList()
            val records = doc.getJSONObject("records")
            val result = records.keys().asSequence().mapNotNull { key ->
                val id = key.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val saved = runCatching { decode(owner, records.getJSONObject(key)) }.getOrNull() ?: return@mapNotNull null
                if (saved.bookingId != id || deny(owner, id, saved.rideId, doc) ||
                    disk.barrier(owner.barrier("booking", id)) != null) return@mapNotNull null
                val ride = saved.rideId ?: return@mapNotNull null
                val route = saved.route ?: return@mapNotNull null
                val stamp = saved.payloadStamp ?: return@mapNotNull null
                val candidate = proof(saved)
                if (saved.remaining(now) == null) {
                    if (authority.expireCandidate(owner, id, candidate)) changed()
                    return@mapNotNull null
                }
                if (!authority.allowsHistorical(owner, id, ride, candidate)) return@mapNotNull null
                HistoricalTripSummary(owner, id, ride, route, saved.state, stamp, saved.stateStamp, candidate)
            }.sortedByDescending { it.stateStamp.wall }.take(20).toList()
            if (current(owner)) result else emptyList()
        }.getOrDefault(emptyList())
    }

    /** A cached DTO or a bare ticket is insufficient: permit is issued only by the shared gate. */
    suspend fun observe(
        ticket: SnapshotTicket, bookingId: Int, state: SnapshotState, rideId: Int? = null, route: SnapshotRoute? = null,
        acknowledgement: Boolean = false, permit: SnapshotAuthorityPermit? = null, coldSave: Boolean = false,
    ): SnapshotPersistence = writer.withLock {
        if (!valid(ticket) || bookingId <= 0 || !authority.canObserve(permit, ticket, bookingId, state, rideId, route) ||
            acknowledgement != permit?.mutation ||
            (coldSave && (acknowledgement || permit?.kind != SnapshotAuthorityKind.DETAILS || route?.valid != true))) {
            return@withLock SnapshotPersistence.STALE
        }
        val owner = ticket.owner
        runCatching {
            val doc = root(owner)
            if (deny(owner, bookingId, rideId, doc)) return@withLock SnapshotPersistence.STALE
            val records = doc.getJSONObject("records")
            val oldRecord = records.optJSONObject(bookingId.toString())?.let { decodeRecord(owner, it) }
            val old = oldRecord?.snapshot
            val actualRide = rideId ?: old?.rideId
            if (deny(owner, bookingId, actualRide, doc)) return@withLock SnapshotPersistence.STALE
            if (old?.rideId != null && rideId != null && old.rideId != rideId) return@withLock SnapshotPersistence.STALE
            val orderKey = owner.barrier("booking", bookingId)
            val lastOrder = maxOf(observedOrders[orderKey] ?: 0, old?.takeIf { it.ticketProcess == ticket.process }?.stateTicket ?: 0)
            val diskMerged = (if (old == null) SnapshotEpochState.first(state) else old.state.merge(state)) ?: return@withLock SnapshotPersistence.STALE
            val authorizedState = permit?.state ?: return@withLock SnapshotPersistence.STALE
            if (!sameSnapshotProjection(diskMerged, authorizedState)) return@withLock SnapshotPersistence.STALE
            val corrected = listOfNotNull(snapshotEpoch(diskMerged.corrected), snapshotEpoch(authorizedState.corrected)).maxOrNull()
            val merged = authorizedState.copy(corrected = corrected?.toString())
            val compatibleFirstFull = old != null && old.route == null && route?.valid == true && (rideId ?: 0) > 0 && merged == old.state
            if (!acknowledgement && ticket.sequence < lastOrder && !compatibleFirstFull) return@withLock SnapshotPersistence.STALE
            val pendingCandidate = disk.barrier(orderKey)
            if (pendingCandidate != null && (old == null || pendingCandidate != proof(old))) {
                val previousEpoch = snapshotEpoch(old?.state?.maxOnboard)
                val nextEpoch = snapshotEpoch(merged.maxOnboard)
                val newerBoarding = previousEpoch != null && nextEpoch != null && nextEpoch > previousEpoch && merged.status == "onboard"
                val acceptedCorrection = merged.status == "confirmed" && state.receiptAccepted && snapshotEpoch(state.receiptSource) != null
                if (!newerBoarding && !acceptedCorrection) return@withLock SnapshotPersistence.DURABLY_BLOCKED
            }
            observedOrders[orderKey] = maxOf(lastOrder, ticket.sequence)
            val now = clock()?.takeIf { it.valid } ?: return@withLock blockDisk(owner, bookingId)
            val acceptedRoute = route?.takeIf { it.valid } ?: old?.route
            val full = route?.valid == true && (old?.ticketProcess != ticket.process || ticket.sequence >= old.payloadTicket)
            val expired = old?.route != null && old.payloadStamp != null &&
                (old.remaining(now) == null || authority.candidateDenied(owner, bookingId, proof(old)))
            val stateChanged = old == null || merged != old.state
            val payloadChanged = full && (old?.route != route || old?.rideId != actualRide)
            val grantChanged = oldRecord?.grant != null
            val coldRevokeRequired = coldSave || acknowledgement || permit.kind == SnapshotAuthorityKind.DETAILS ||
                (grantChanged && (stateChanged || payloadChanged || (full && expired)))
            var coldBarrierSaved = true
            var coldSaveEpoch: Long? = null
            if (coldRevokeRequired) {
                authority.revokeColdProjection(owner, bookingId)
                if (coldSave) coldSaveEpoch = authority.coldEpoch(owner, bookingId) ?: return@withLock SnapshotPersistence.STALE
                val coldKey = owner.barrier("cold-booking", bookingId)
                coldBarrierSaved = disk.setBarrier(coldKey, "blocked")
            }
            if (!coldSave && !stateChanged && !payloadChanged && !(full && expired) && (!grantChanged || !coldRevokeRequired)) {
                val pending = disk.barrier(orderKey)
                if (pending == null) {
                    if (full && old != null) authority.snapshotCommitted(permit, proof(old))
                    return@withLock if (!coldBarrierSaved) SnapshotPersistence.NOT_DURABLE else SnapshotPersistence.UNCHANGED
                }
                if (!full || old == null || pending != proof(old)) return@withLock SnapshotPersistence.DURABLY_BLOCKED
                val cleared = disk.setBarrier(orderKey, null); changed()
                if (cleared) authority.snapshotCommitted(permit, proof(old))
                return@withLock if (!valid(ticket)) SnapshotPersistence.STALE
                    else if (!coldBarrierSaved) SnapshotPersistence.NOT_DURABLE
                    else if (cleared) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
            }
            val next = OfflineTripSnapshot(owner, bookingId, actualRide, merged, acceptedRoute,
                if (full && (payloadChanged || expired || old?.payloadStamp == null)) now else old?.payloadStamp,
                if (stateChanged || (full && expired)) now else old!!.stateStamp, ticket.process,
                maxOf(ticket.sequence, lastOrder), if (full) ticket.sequence else old?.payloadTicket ?: 0)
            val barrier = owner.barrier("booking", bookingId)
            val blocked = disk.setBarrier(barrier, proof(next))
            if (!valid(ticket) || !authority.canObserve(permit, ticket, bookingId, state, rideId, route)) return@withLock SnapshotPersistence.STALE
            records.put(bookingId.toString(), encode(next)); doc.put("version", Math.addExact(doc.getLong("version"), 1))
            if (!disk.write(owner.namespace, doc.toString())) {
                if (!blocked) quarantined += owner.namespace
                changed()
                return@withLock if (!coldBarrierSaved) SnapshotPersistence.NOT_DURABLE
                    else if (blocked || (coldRevokeRequired && coldBarrierSaved)) SnapshotPersistence.DURABLY_BLOCKED
                    else SnapshotPersistence.NOT_DURABLE
            }
            val cleared = disk.setBarrier(barrier, null); changed()
            if (cleared) authority.snapshotCommitted(permit, proof(next))
            if (!valid(ticket) || !authority.canObserve(permit, ticket, bookingId, state, rideId, route)) SnapshotPersistence.STALE
            else if (!coldBarrierSaved) SnapshotPersistence.NOT_DURABLE
            else if (cleared && coldSave &&
                !authority.markColdSaveObserved(permit, requireNotNull(coldSaveEpoch))) SnapshotPersistence.STALE
            else if (cleared) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
        }.getOrElse { quarantined += owner.namespace; changed(); SnapshotPersistence.NOT_DURABLE }
    }

    suspend fun beginColdSave(
        permit: SnapshotAuthorityPermit, state: SnapshotState, rideId: Int, route: SnapshotRoute, barrier: String,
    ): SnapshotPersistence = writer.withLock {
        val owner = permit.owner
        val bookingId = permit.bookingId
        if (!valid(permit.ticket) || !authority.canObserve(permit, permit.ticket, bookingId, state, rideId, route) ||
            permit.kind != SnapshotAuthorityKind.DETAILS || !barrier.matches(Regex("blocked:[0-9a-f]{64}"))) {
            return@withLock SnapshotPersistence.STALE
        }
        authority.revokeColdProjection(owner, bookingId)
        val saved = runCatching { disk.setBarrier(owner.barrier("cold-booking", bookingId), barrier) }.getOrDefault(false)
        changed()
        if (!valid(permit.ticket) || !authority.canObserve(permit, permit.ticket, bookingId, state, rideId, route)) {
            SnapshotPersistence.STALE
        } else if (saved) SnapshotPersistence.COMMITTED else SnapshotPersistence.NOT_DURABLE
    }

    suspend fun commitColdGrant(
        permit: SnapshotAuthorityPermit,
        detailsTicket: SnapshotTicket,
        receipt: TripPassCommitReceipt,
        state: SnapshotState,
        rideId: Int,
        route: SnapshotRoute,
    ): SnapshotPersistence = writer.withLock {
        val owner = permit.owner
        val bookingId = permit.bookingId
        if (!valid(permit.ticket) || permit.kind != SnapshotAuthorityKind.DETAILS || permit.mutation ||
            !authority.canObserve(permit, permit.ticket, bookingId, state, rideId, route) ||
            !authority.coldSaveCommitCurrent(permit) ||
            detailsTicket.owner != owner || detailsTicket.process != permit.ticket.process ||
            detailsTicket.sequence >= permit.ticket.sequence || receipt.bookingId != bookingId ||
            receipt.sourcePrivateFingerprint != permit.fingerprint) return@withLock SnapshotPersistence.STALE
        runCatching {
            val doc = root(owner)
            if (deny(owner, bookingId, rideId, doc)) return@withLock SnapshotPersistence.STALE
            val records = doc.getJSONObject("records")
            val saved = records.optJSONObject(bookingId.toString())?.let { decodeRecord(owner, it) }?.snapshot
                ?: return@withLock SnapshotPersistence.DURABLY_BLOCKED
            val coldKey = owner.barrier("cold-booking", bookingId)
            if (disk.barrier(coldKey) == null || saved.rideId != rideId || saved.route != route ||
                saved.state.role != "passenger" || saved.ticketProcess != permit.ticket.process ||
                saved.payloadTicket != permit.ticket.sequence || !sameSnapshotProjection(saved.state, permit.state!!)) {
                return@withLock SnapshotPersistence.DURABLY_BLOCKED
            }
            val now = clock()?.takeIf { it.valid } ?: return@withLock SnapshotPersistence.DURABLY_BLOCKED
            if (saved.remaining(now) == null) return@withLock SnapshotPersistence.DURABLY_BLOCKED
            val grant = ColdTripPassportGrant(owner.userId, owner.origin, owner.scope, bookingId, rideId,
                proof(saved), requireNotNull(permit.fingerprint), receipt.payloadSha256,
                detailsTicket, permit.ticket, now)
            require(grant.valid)
            records.put(bookingId.toString(), encode(saved, grant))
            doc.put("version", Math.addExact(doc.getLong("version"), 1))
            if (!disk.write(owner.namespace, doc.toString())) {
                changed()
                return@withLock SnapshotPersistence.DURABLY_BLOCKED
            }
            val confirmed = root(owner).getJSONObject("records").getJSONObject(bookingId.toString())
                .let { decodeRecord(owner, it) }
            if (confirmed.grant != grant || confirmed.snapshot != saved) {
                quarantined += owner.namespace
                changed()
                return@withLock SnapshotPersistence.DURABLY_BLOCKED
            }
            if (!valid(permit.ticket) || !authority.canObserve(permit, permit.ticket, bookingId, state, rideId, route)) {
                return@withLock SnapshotPersistence.STALE
            }
            var clearAttempted = false
            val allowed = authority.allowColdRestoreAfterGrant(permit, grant, state, rideId, route) {
                clearAttempted = true
                disk.setBarrier(coldKey, null)
            }
            if (clearAttempted) changed()
            if (allowed) SnapshotPersistence.COMMITTED
            else if (clearAttempted) SnapshotPersistence.DURABLY_BLOCKED
            else SnapshotPersistence.STALE
        }.getOrElse { quarantined += owner.namespace; changed(); SnapshotPersistence.DURABLY_BLOCKED }
    }

    suspend fun loadCold(
        owner: SnapshotOwner,
        bookingId: Int,
        candidate: TripPassSecureCandidate,
        restore: SnapshotColdRestoreTicket,
    ): ColdTripPassport? = writer.withLock {
        if (!current(owner) || owner.namespace in quarantined || bookingId <= 0 ||
            restore.ticket.owner != owner || restore.bookingId != bookingId) return@withLock null
        runCatching {
            val doc = root(owner)
            if (deny(owner, bookingId, null, doc) || disk.barrier(owner.barrier("cold-scope", 0)) != null ||
                disk.barrier(owner.barrier("cold-booking", bookingId)) != null) return@withLock null
            val record = doc.getJSONObject("records").optJSONObject(bookingId.toString())
                ?.let { decodeRecord(owner, it) } ?: return@withLock null
            val saved = record.snapshot
            val grant = record.grant ?: return@withLock null
            val ride = saved.rideId ?: return@withLock null
            if (saved.bookingId != bookingId || deny(owner, bookingId, ride, doc) ||
                disk.barrier(owner.barrier("booking", bookingId)) != null ||
                disk.barrier(owner.barrier("cold-ride", ride)) != null ||
                saved.state.role != "passenger" || saved.route == null || saved.payloadStamp == null ||
                candidate.bookingId != bookingId || candidate.pass.bookingId != bookingId ||
                candidate.pass.boardingCode.isBlank() || candidate.raw != candidate.pass.toJson().toString() ||
                candidate.payloadSha256 != snapshotHash(candidate.raw) || grant.tripPassSha256 != candidate.payloadSha256) {
                return@withLock null
            }
            val value = ColdTripPassport(saved, candidate.pass, grant)
            val now = clock()
            if (authority.candidateDenied(owner, bookingId, grant.snapshotCandidate) || now == null || value.remaining(now) == null) {
                authority.expireCandidate(owner, bookingId, grant.snapshotCandidate)
                disk.setBarrier(owner.barrier("cold-booking", bookingId), "blocked")
                changed()
                return@withLock null
            }
            if (!current(owner) || !authority.restoreCold(restore, value)) null else value
        }.getOrNull()
    }

    suspend fun revokeCold(request: SnapshotColdRevocation): SnapshotPersistence {
        if (!authority.coldCurrent(request)) return SnapshotPersistence.STALE
        return writer.withLock {
            if (!authority.coldCurrent(request)) return@withLock SnapshotPersistence.STALE
            val owner = request.ticket.owner
            val bookingId = request.bookingId
            val blocked = runCatching {
                disk.setBarrier(owner.barrier("cold-booking", bookingId), "blocked")
            }.getOrDefault(false)
            val saved = runCatching {
                val doc = root(owner)
                val records = doc.getJSONObject("records")
                val old = records.optJSONObject(bookingId.toString())?.let { decodeRecord(owner, it) }
                if (old?.grant == null) true else {
                    records.put(bookingId.toString(), encode(old.snapshot))
                    doc.put("version", Math.addExact(doc.getLong("version"), 1))
                    disk.write(owner.namespace, doc.toString())
                }
            }.getOrDefault(false)
            if (!blocked && !saved) quarantined += owner.namespace
            changed()
            if (!authority.coldCurrent(request)) SnapshotPersistence.STALE
            else if (!blocked) SnapshotPersistence.NOT_DURABLE
            else if (saved) SnapshotPersistence.COMMITTED
            else SnapshotPersistence.DURABLY_BLOCKED
        }
    }

    private fun blockDisk(owner: SnapshotOwner, bookingId: Int): SnapshotPersistence {
        val blocked = runCatching { disk.setBarrier(owner.barrier("booking", bookingId), "blocked") }.getOrDefault(false)
        if (!blocked) quarantined += owner.namespace
        changed()
        return if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
    }
    /** An accepted but unrepresentable ack must hide old state even with unavailable clocks/storage. */
    suspend fun block(ticket: SnapshotTicket, bookingId: Int): SnapshotPersistence {
        if (!valid(ticket) || bookingId <= 0) return SnapshotPersistence.STALE
        authority.invalidate(ticket, bookingId)
        return writer.withLock { if (!valid(ticket)) SnapshotPersistence.STALE else blockDisk(ticket.owner, bookingId) }
    }
    suspend fun close(ticket: SnapshotTicket, id: Int, ride: Boolean = false): SnapshotPersistence {
        // This must precede waiting for a paused old writer, as well as both disk writes.
        if (!valid(ticket) || !authority.close(ticket, id, ride)) return SnapshotPersistence.STALE
        return writer.withLock {
            val owner = ticket.owner
            val blocked = runCatching { disk.setBarrier(owner.barrier(if (ride) "ride" else "booking", id), "closed") }.getOrDefault(false)
            val saved = runCatching {
                val doc = root(owner)
                val key = if (ride) "closed_rides" else "closed_bookings"
                if (!closed(doc, key, id)) doc.getJSONArray(key).put(id)
                val records = doc.getJSONObject("records")
                records.keys().asSequence().toList().forEach { bid ->
                    val item = records.getJSONObject(bid)
                    if ((!ride && item.getInt("booking") == id) || (ride && item.optInt("ride") == id)) records.remove(bid)
                }
                doc.put("version", Math.addExact(doc.getLong("version"), 1))
                current(owner) && disk.write(owner.namespace, doc.toString())
            }.getOrDefault(false)
            if (!blocked && !saved) quarantined += owner.namespace
            changed()
            if (!valid(ticket)) SnapshotPersistence.STALE else if (saved) SnapshotPersistence.COMMITTED
            else if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
        }
    }
    suspend fun retire(owner: SnapshotOwner): SnapshotPersistence {
        authority.retire(owner)
        return writer.withLock {
            val blocked = runCatching { disk.setBarrier(owner.barrier("scope", 0), "retired") }.getOrDefault(false)
            val removed = runCatching { disk.retire(owner.namespace) }.getOrDefault(false)
            quarantined += owner.namespace; changed()
            if (removed) SnapshotPersistence.COMMITTED else if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
        }
    }
    suspend fun purge() = writer.withLock { disk.purgeExcept(activeOwner.get()?.namespace); changed() }
}
