package com.yuldash.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Disk methods return confirmed commits. Implementations must not call the auth lock. */
internal interface SnapshotDisk {
    fun read(namespace: String): String?
    fun write(namespace: String, value: String): Boolean
    fun barrier(key: String): String?
    fun setBarrier(key: String, value: String?): Boolean
    fun retire(namespace: String): Boolean
    fun purgeExcept(namespace: String?): Boolean = true
}

/** One serialized writer; old scopes have disjoint disk keys and can never overwrite a new login. */
internal class OfflineTripSnapshotRepository(
    private val disk: SnapshotDisk,
    private val activeOwner: AtomicReference<SnapshotOwner?>,
    private val clock: () -> SnapshotClock?,
    private val changed: () -> Unit = {},
) {
    private val writer = Mutex()
    private val process = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val quarantined = mutableSetOf<String>()
    private val observedOrders = mutableMapOf<String, Long>()
    private fun current(owner: SnapshotOwner) = owner.valid && activeOwner.get() == owner
    fun ticket(): SnapshotTicket? = activeOwner.get()?.takeIf { it.valid }?.let { SnapshotTicket(it, process, sequence.incrementAndGet()) }
    private fun valid(ticket: SnapshotTicket) = ticket.process == process && ticket.sequence > 0 && current(ticket.owner)
    private fun block(owner: SnapshotOwner, bookingId: Int): SnapshotPersistence {
        val blocked = runCatching { disk.setBarrier(owner.barrier("booking", bookingId), "blocked") }.getOrDefault(false)
        if (!blocked) quarantined += owner.namespace
        changed()
        return if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
    }
    private fun keys(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) }
    private fun number(value: JSONObject, key: String): Long = value.get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun integer(value: JSONObject, key: String): Int = Math.toIntExact(number(value, key))
    private fun string(value: JSONObject, key: String): String = value.get(key).let { require(it is String); it }
    private fun root(owner: SnapshotOwner): JSONObject = disk.read(owner.namespace)?.let {
        JSONObject(it).also { value ->
            keys(value, setOf("schema", "scope", "owner", "version", "records", "closed_bookings", "closed_rides"))
            require(integer(value, "schema") == 1 && string(value, "scope") == owner.scope && integer(value, "owner") == owner.userId)
            require(number(value, "version") >= 0)
            value.getJSONObject("records"); value.getJSONArray("closed_bookings"); value.getJSONArray("closed_rides")
        }
    } ?: JSONObject().put("schema", 1).put("scope", owner.scope).put("owner", owner.userId)
        .put("version", 0L).put("records", JSONObject()).put("closed_bookings", JSONArray()).put("closed_rides", JSONArray())
    private fun closed(root: JSONObject, key: String, id: Int): Boolean {
        val values = root.getJSONArray(key)
        return (0 until values.length()).any { require(values.get(it) is Int && values.getInt(it) > 0); values.getInt(it) == id }
    }
    private fun deny(owner: SnapshotOwner, booking: Int, ride: Int?, value: JSONObject): Boolean =
        disk.barrier(owner.barrier("scope", 0)) != null || disk.barrier(owner.barrier("booking", booking)) == "closed" ||
            (ride != null && disk.barrier(owner.barrier("ride", ride)) == "closed") ||
            closed(value, "closed_bookings", booking) || (ride != null && closed(value, "closed_rides", ride))
    private fun stamp(value: SnapshotClock) = JSONObject().put("boot", value.boot).put("wall", value.wall)
        .put("before", value.before).put("after", value.after)
    private fun clock(value: JSONObject): SnapshotClock {
        keys(value, setOf("boot", "wall", "before", "after"))
        return SnapshotClock(integer(value, "boot"), number(value, "wall"), number(value, "before"), number(value, "after")).also { require(it.valid) }
    }
    private fun nullable(value: JSONObject, key: String): String? = if (value.get(key) == JSONObject.NULL) null else string(value, key)
    private fun encode(value: OfflineTripSnapshot): JSONObject = JSONObject().put("booking", value.bookingId)
        .put("ride", value.rideId ?: JSONObject.NULL).put("role", value.state.role).put("status", value.state.status)
        .put("phase", value.state.phase).put("onboard", value.state.maxOnboard ?: JSONObject.NULL)
        .put("corrected", value.state.corrected ?: JSONObject.NULL).put("state_stamp", stamp(value.stateStamp))
        .put("payload_stamp", value.payloadStamp?.let(::stamp) ?: JSONObject.NULL)
        .put("process", value.ticketProcess).put("state_ticket", value.stateTicket).put("payload_ticket", value.payloadTicket)
        .put("route", value.route?.let { JSONObject().put("from", it.from).put("to", it.to).put("depart", it.departAt)
            .put("pickup", it.pickup ?: JSONObject.NULL).put("dropoff", it.dropoff ?: JSONObject.NULL).put("stops", it.hasStops) } ?: JSONObject.NULL)
    /** Hash of a canonical, scoped candidate; journal never contains its personal projection. */
    private fun proof(value: OfflineTripSnapshot): String {
        fun clockArray(clock: SnapshotClock?) = clock?.let { JSONArray(listOf(it.boot, it.wall, it.before, it.after)) } ?: JSONObject.NULL
        val route = value.route?.let { JSONArray(listOf(it.from, it.to, it.departAt, it.pickup, it.dropoff, it.hasStops)) } ?: JSONObject.NULL
        return "blocked:" + snapshotHash(JSONArray(listOf(value.owner.namespace, value.bookingId, value.rideId, value.state.role,
            value.state.status, value.state.phase, value.state.maxOnboard, value.state.corrected, route,
            clockArray(value.payloadStamp), clockArray(value.stateStamp), value.ticketProcess, value.stateTicket, value.payloadTicket)).toString())
    }
    private fun decode(owner: SnapshotOwner, value: JSONObject): OfflineTripSnapshot {
        keys(value, setOf("booking", "ride", "role", "status", "phase", "onboard", "corrected", "state_stamp", "payload_stamp", "process", "state_ticket", "payload_ticket", "route"))
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
            keys(it, setOf("from", "to", "depart", "pickup", "dropoff", "stops"))
            require(it.get("stops") is Boolean)
            SnapshotRoute(string(it, "from"), string(it, "to"), string(it, "depart"), nullable(it, "pickup"), nullable(it, "dropoff"), it.getBoolean("stops"))
        }
        require(route == null || route.valid)
        val stamp = if (value.get("payload_stamp") == JSONObject.NULL) null else clock(value.getJSONObject("payload_stamp"))
        val ride = if (value.get("ride") == JSONObject.NULL) null else integer(value, "ride").also { require(it > 0) }
        val process = string(value, "process").also { require(UUID.fromString(it).toString() == it) }
        require(integer(value, "booking") > 0 && number(value, "state_ticket") > 0 && number(value, "payload_ticket") >= 0)
        require((route == null) == (stamp == null) && (route == null || (ride != null && number(value, "payload_ticket") > 0)))
        return OfflineTripSnapshot(owner, integer(value, "booking"), ride, state, route, stamp,
            clock(value.getJSONObject("state_stamp")), process, number(value, "state_ticket"), number(value, "payload_ticket"))
    }

    suspend fun load(owner: SnapshotOwner, bookingId: Int): OfflineTripSnapshot? = writer.withLock {
        if (!current(owner) || owner.namespace in quarantined || bookingId <= 0) return@withLock null
        runCatching {
            val doc = root(owner)
            val saved = doc.getJSONObject("records").optJSONObject(bookingId.toString())?.let { decode(owner, it) } ?: return@withLock null
            if (saved.bookingId != bookingId || deny(owner, bookingId, saved.rideId, doc) ||
                disk.barrier(owner.barrier("booking", bookingId)) != null) return@withLock null
            if (saved.remaining(clock() ?: return@withLock null) == null || !current(owner)) null else saved
        }.getOrNull()
    }

    suspend fun observe(ticket: SnapshotTicket, bookingId: Int, state: SnapshotState, rideId: Int? = null, route: SnapshotRoute? = null,
        acknowledgement: Boolean = false): SnapshotPersistence = writer.withLock {
        if (!valid(ticket) || bookingId <= 0) return@withLock SnapshotPersistence.STALE
        val owner = ticket.owner
        runCatching {
            val doc = root(owner)
            if (deny(owner, bookingId, rideId, doc)) return@withLock SnapshotPersistence.STALE
            val records = doc.getJSONObject("records")
            val old = records.optJSONObject(bookingId.toString())?.let { decode(owner, it) }
            val actualRide = rideId ?: old?.rideId
            if (deny(owner, bookingId, actualRide, doc)) return@withLock SnapshotPersistence.STALE
            if (old?.rideId != null && rideId != null && old.rideId != rideId) return@withLock SnapshotPersistence.STALE
            val orderKey = owner.barrier("booking", bookingId)
            val lastOrder = maxOf(observedOrders[orderKey] ?: 0, old?.takeIf { it.ticketProcess == process }?.stateTicket ?: 0)
            val merged = (if (old == null) SnapshotEpochState.first(state) else old.state.merge(state))
                ?: return@withLock if (acknowledgement && state.status == "confirmed" &&
                    (!state.receiptAccepted || snapshotEpoch(state.receiptSource) == null)) block(owner, bookingId) else SnapshotPersistence.STALE
            val compatibleFirstFull = old != null && old.route == null && route?.valid == true && (rideId ?: 0) > 0 && merged == old.state
            if (!acknowledgement && ticket.sequence < lastOrder && !compatibleFirstFull) return@withLock SnapshotPersistence.STALE
            val pendingCandidate = disk.barrier(orderKey)
            if (pendingCandidate != null && (old == null || pendingCandidate != proof(old))) {
                val previousEpoch = snapshotEpoch(old?.state?.maxOnboard)
                val nextEpoch = snapshotEpoch(merged.maxOnboard)
                val newerBoarding = previousEpoch != null && nextEpoch != null && nextEpoch > previousEpoch && merged.status == "onboard"
                val acceptedCorrection = merged.status == "confirmed" && state.receiptAccepted && snapshotEpoch(state.receiptSource) != null
                // A route/clock/phase refresh cannot erase an uncommitted correction or unknown state change.
                if (!newerBoarding && !acceptedCorrection) return@withLock SnapshotPersistence.DURABLY_BLOCKED
            }
            // UNCHANGED must still order in-flight reads after cold reopen, without renewing TTL or disk writes.
            observedOrders[orderKey] = maxOf(lastOrder, ticket.sequence)
            val now = clock()?.takeIf { it.valid } ?: run {
                return@withLock block(owner, bookingId)
            }
            val acceptedRoute = route?.takeIf { it.valid } ?: old?.route
            val full = route != null && route.valid && (old?.ticketProcess != process || ticket.sequence >= old.payloadTicket)
            val expired = old?.route != null && old.payloadStamp != null && old.remaining(now) == null
            val stateChanged = old == null || merged != old.state
            val payloadChanged = full && (old?.route != route || old?.rideId != actualRide)
            if (!stateChanged && !payloadChanged && !(full && expired)) {
                val pending = disk.barrier(orderKey)
                if (pending == null) return@withLock SnapshotPersistence.UNCHANGED
                // A fresh identical full response can finish only this already committed candidate.
                // A failed correction has a different proof and must keep blocking old onboard.
                if (!full || old == null || pending != proof(old)) return@withLock SnapshotPersistence.DURABLY_BLOCKED
                val cleared = disk.setBarrier(orderKey, null)
                changed()
                return@withLock if (!current(owner)) SnapshotPersistence.STALE
                    else if (cleared) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
            }
            val next = OfflineTripSnapshot(owner, bookingId, actualRide, merged, acceptedRoute,
                if (full && (payloadChanged || expired || old?.payloadStamp == null)) now else old?.payloadStamp,
                if (stateChanged || (full && expired)) now else old!!.stateStamp, process,
                maxOf(ticket.sequence, lastOrder),
                if (full) ticket.sequence else old?.payloadTicket ?: 0)
            val barrier = owner.barrier("booking", bookingId)
            // A durable block protects old state when the following encrypted commit fails.
            val blocked = disk.setBarrier(barrier, proof(next))
            if (!current(owner)) return@withLock SnapshotPersistence.STALE
            records.put(bookingId.toString(), encode(next)); doc.put("version", Math.addExact(doc.getLong("version"), 1))
            if (!disk.write(owner.namespace, doc.toString())) {
                if (!blocked) quarantined += owner.namespace
                changed()
                return@withLock if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
            }
            // A failed clear leaves a conservative block; it never authorizes dirty memory.
            val cleared = disk.setBarrier(barrier, null)
            changed()
            if (!current(owner)) SnapshotPersistence.STALE else if (cleared) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
        }.getOrElse { quarantined += owner.namespace; changed(); SnapshotPersistence.NOT_DURABLE }
    }

    suspend fun close(ticket: SnapshotTicket, id: Int, ride: Boolean = false): SnapshotPersistence = writer.withLock {
        if (!valid(ticket) || id <= 0) return@withLock SnapshotPersistence.STALE
        val owner = ticket.owner
        val blocked = runCatching { disk.setBarrier(owner.barrier(if (ride) "ride" else "booking", id), "closed") }.getOrDefault(false)
        val saved = runCatching {
            val doc = root(owner)
            val key = if (ride) "closed_rides" else "closed_bookings"
            if (!closed(doc, key, id)) doc.getJSONArray(key).put(id)
            val records = doc.getJSONObject("records")
            records.keys().asSequence().toList().forEach { bid ->
                val entry = records.getJSONObject(bid)
                if ((!ride && entry.getInt("booking") == id) || (ride && entry.optInt("ride") == id)) records.remove(bid)
            }
            doc.put("version", Math.addExact(doc.getLong("version"), 1))
            current(owner) && disk.write(owner.namespace, doc.toString())
        }.getOrDefault(false)
        if (!blocked && !saved) quarantined += owner.namespace
        changed()
        if (!current(owner)) SnapshotPersistence.STALE else if (saved) SnapshotPersistence.COMMITTED
        else if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
    }

    suspend fun retire(owner: SnapshotOwner): SnapshotPersistence = writer.withLock {
        val blocked = runCatching { disk.setBarrier(owner.barrier("scope", 0), "retired") }.getOrDefault(false)
        val removed = runCatching { disk.retire(owner.namespace) }.getOrDefault(false)
        quarantined += owner.namespace
        changed()
        if (removed) SnapshotPersistence.COMMITTED else if (blocked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
    }
    suspend fun purge() = writer.withLock { disk.purgeExcept(activeOwner.get()?.namespace); changed() }
}
