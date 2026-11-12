package com.yuldash.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Application-owned process authority; neither init nor a durable scope grants a trip. */
internal object OfflineTripSnapshots : SnapshotTransportAuthority {
    private val active = AtomicReference<SnapshotOwner?>(null)
    override var gate = SnapshotAuthorityGate(active)
        private set
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskAccess = Mutex()
    @Volatile private var storageEpoch = 0L
    @Volatile private var resetPending = false
    private val owners = MutableStateFlow<SnapshotOwner?>(null)
    val owner = owners.asStateFlow()
    private val revisions = MutableStateFlow(0L)
    val version = revisions.asStateFlow()
    private val writes = MutableStateFlow(SnapshotPersistence.UNCHANGED)
    val persistence = writes.asStateFlow()
    private data class Live(val permit: SnapshotAuthorityPermit, val stamp: SnapshotClock)
    private val live = mutableMapOf<Int, Live>()
    private val details = mutableMapOf<Int, TripPrivateProjection>()
    private val driverCodes = mutableMapOf<Int, DriverBoardingCodeProjection>()
    private val histories = mutableMapOf<Int, Pair<TripReceiptDto, SnapshotOwner>>()
    private val warmFallbacks = mutableMapOf<Int, OfflineTripSnapshot>()
    internal data class Terminal(val owner: SnapshotOwner, val role: String, val status: String, val inTransit: Boolean, val stamp: SnapshotClock)
    private val terminals = mutableMapOf<Int, Terminal>()
    @Volatile private var app: Context? = null
    @Volatile private var repository: OfflineTripSnapshotRepository? = null
    @Volatile private var durable = false
    private var transientIdentity: Triple<String, Int, Long>? = null
    private var transientScope: String? = null
    private val retiredNamespaces = mutableSetOf<String>()
    @Volatile private var suppliedClock: (() -> SnapshotClock?)? = null

    fun init(context: Context) { app = context.applicationContext }
    private fun changed() { revisions.update { it + 1 } }
    override fun currentOwner(): SnapshotOwner? = active.get()?.takeIf {
        it.valid && ApiClient.isCurrentSession(it.generation) && ApiClient.myUserId() == it.userId &&
            snapshotOrigin(ApiClient.apiBase()) == it.origin
    }
    override fun cacheEnabled(owner: SnapshotOwner): Boolean = durable && !resetPending && currentOwner() == owner
    fun current(value: SnapshotOwner): Boolean = currentOwner() == value
    fun ticket(): SnapshotTicket? = currentOwner()?.let { gate.ticket() }
    @Synchronized fun scopeRetired(scope: String): Boolean {
        val id = ApiClient.myUserId() ?: return false
        val origin = snapshotOrigin(ApiClient.apiBase()) ?: return false
        return SnapshotOwner(id, scope, ApiClient.queueSessionGeneration(), origin).namespace in retiredNamespaces
    }

    /** No ciphertext is opened in process-only mode. Its UUID is stable for this exact identity. */
    @Synchronized fun activate(userId: Int?, durableScope: String?, generation: Long) {
        val origin = snapshotOrigin(ApiClient.apiBase())
        val identity = if (origin != null && userId != null && userId > 0 && ApiClient.isCurrentSession(generation))
            Triple(origin, userId, generation) else null
        val validScope = durableScope?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            ?.takeUnless { identity != null && SnapshotOwner(identity.second, it, generation, identity.first).namespace in retiredNamespaces }
        val scope = if (identity == null) null else validScope ?: run {
            val retired = transientScope?.let { SnapshotOwner(identity.second, it, generation, identity.first).namespace in retiredNamespaces } == true
            if (transientIdentity != identity || retired) { transientIdentity = identity; transientScope = UUID.randomUUID().toString() }
            transientScope
        }
        val next = if (identity != null && scope != null) SnapshotOwner(identity.second, scope, generation, identity.first) else null
        val old = active.get()
        val oldDurable = durable
        val nextDurable = next != null && validScope != null
        if (old == next && oldDurable == nextDurable) return
        // Revoke BEFORE publishing another owner or scheduling any disk operation.
        old?.takeIf { it.namespace != next?.namespace }?.let { gate.retire(it); retiredNamespaces += it.namespace }
        active.set(null)
        live.clear(); details.clear(); driverCodes.clear(); histories.clear(); terminals.clear(); warmFallbacks.clear()
        active.set(next); durable = nextDurable; owners.value = next; changed()
        val existing = repository
        // A healthy identity with bad optional scope must perform NO old-cache IO. Explicit
        // logout uses its ordered exact-file cleanup; a new durable session retires its old scope.
        if (old != null && oldDurable && nextDurable && existing != null && old.namespace != next?.namespace) {
            val epoch = storageEpoch
            jobs.launch { publish(epoch) { existing.retire(old) } }
        }
    }
    @Synchronized fun suspendTrust() {
        val value = currentOwner()
        transientIdentity = null; transientScope = null
        activate(value?.userId, null, value?.generation ?: ApiClient.queueSessionGeneration())
    }

    private fun repo(owner: SnapshotOwner): OfflineTripSnapshotRepository? {
        if (!cacheEnabled(owner)) return null
        repository?.let { return it }
        return synchronized(this) {
            repository ?: app?.let { context -> runCatching {
                val cleanup = SnapshotLogoutCleanup(context)
                if (cleanup.pending() != false) {
                    if (cleanup.pending() == true) logoutCleanup()
                    return@runCatching null
                }
                OfflineTripSnapshotRepository(SnapshotPreferencesDisk.open(context), active, ::clock, gate, ::changed)
                    .also { repository = it }
            }.getOrNull() }
        }
    }
    fun clock(): SnapshotClock? {
        suppliedClock?.let { return it() }
        return runCatching {
            val context = app ?: return null
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            val before = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis()
            val after = SystemClock.elapsedRealtime()
            require(boot == Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT))
            SnapshotClock(boot, wall, before, after).takeIf { it.valid }
        }.getOrNull()
    }
    /** Memory projections cannot cross a boot/process. No optional BOOT_COUNT read is needed. */
    fun processClock(): SnapshotClock = suppliedClock?.invoke()?.let { it.copy(boot = 0) } ?: run {
        val before = SystemClock.elapsedRealtime()
        SnapshotClock(0, System.currentTimeMillis(), before, SystemClock.elapsedRealtime())
    }
    fun clockInBoot(boot: Int): SnapshotClock = suppliedClock?.invoke() ?: run {
        val before = SystemClock.elapsedRealtime()
        SnapshotClock(boot, System.currentTimeMillis(), before, SystemClock.elapsedRealtime())
    }
    private suspend fun publish(epoch: Long = storageEpoch, durableAck: Boolean = false, action: suspend () -> SnapshotPersistence): SnapshotPersistence {
        val write: suspend () -> SnapshotPersistence = {
            diskAccess.withLock {
                if (epoch != storageEpoch) SnapshotPersistence.STALE
                else runCatching { action() }.getOrDefault(SnapshotPersistence.NOT_DURABLE)
            }
        }
        val result = if (durableAck) withContext(NonCancellable) { write() } else write()
        writes.value = result
        changed()
        return result
    }
    suspend fun load(owner: SnapshotOwner, bookingId: Int): OfflineTripSnapshot? = withContext(Dispatchers.IO) {
        val epoch = storageEpoch
        diskAccess.withLock { if (epoch != storageEpoch) null else repo(owner)?.load(owner, bookingId) }
    }
    suspend fun historicalSummaries(): List<HistoricalTripSummary> = withContext(Dispatchers.IO) {
        val owner = currentOwner() ?: return@withContext emptyList()
        if (!cacheEnabled(owner)) return@withContext emptyList()
        val epoch = storageEpoch
        diskAccess.withLock {
            if (epoch != storageEpoch || !cacheEnabled(owner)) emptyList()
            else repo(owner)?.historical(owner).orEmpty().takeIf { cacheEnabled(owner) }.orEmpty()
        }
    }
    fun canDrawHistorical(value: HistoricalTripSummary): Boolean {
        if (!cacheEnabled(value.owner) || !gate.allowsHistorical(value.owner, value.bookingId, value.rideId, value.candidate)) return false
        val now = clock() ?: return false
        if (value.remaining(now) != null) return true
        if (gate.expireCandidate(value.owner, value.bookingId, value.candidate)) changed()
        return false
    }
    fun canDrawSnapshot(value: OfflineTripSnapshot): Boolean = cacheEnabled(value.owner) &&
        gate.allowsSnapshot(value.owner, value.bookingId, value.state, value.rideId, value.route)
    private fun timeCurrent(owner: SnapshotOwner, bookingId: Int, stamp: SnapshotClock, candidate: String): Boolean {
        if (!current(owner)) return false
        if (stamp.remaining(processClock()) != null) return true
        if (gate.expireCandidate(owner, bookingId, "blocked:" + snapshotHash(candidate))) changed()
        return false
    }
    @Synchronized fun allowsLive(bookingId: Int): Boolean {
        val value = live[bookingId] ?: return false
        val permit = value.permit
        return timeCurrent(permit.owner, bookingId, value.stamp, permit.ticket.toString()) &&
            permit.input?.let { gate.canObserve(permit, permit.ticket, bookingId, it, permit.inputRide, permit.inputRoute) } == true
    }
    @Synchronized fun freshDetails(value: BookingDetailsDto): Boolean {
        val projection = details[value.bookingId] ?: return false
        return projection.details === value && projection.proof.verified() &&
            timeCurrent(projection.owner, value.bookingId, projection.stamp, projection.fingerprint)
    }
    /** Exact accepted current DETAILS for public UI; grants no private or live permission. */
    @Synchronized fun currentAcceptedDetails(value: BookingDetailsDto): Boolean {
        val actual = details[value.bookingId] ?: return false
        return actual.details === value && actual.proof.request.kind == SnapshotAuthorityKind.DETAILS &&
            actual.proof.request.bookingId == value.bookingId && freshDetails(value) && proofCurrent(actual.proof)
    }

    @Synchronized fun privateDetails(value: BookingDetailsDto?): Boolean {
        value ?: return false
        val projection = details[value.bookingId] ?: return false
        return freshDetails(value) && gate.allowsPrivate(projection.owner, value.bookingId, projection.fingerprint)
    }
    @Synchronized fun privatePass(bookingId: Int): TripPass? {
        val value = details[bookingId] ?: return null
        return value.pass.takeIf { value.details.role == "passenger" && privateDetails(value.details) }
    }
    @Synchronized fun matchesPass(pass: TripPass): Boolean = details[pass.bookingId]?.let {
        it.details.role == "passenger" && privateDetails(it.details) && (pass === it.pass || privateTripFingerprint(it.details, pass) == it.fingerprint)
    } == true
    @Synchronized fun currentBoardingCode(bookingId: Int): String? {
        val current = details[bookingId] ?: return null
        if (current.details.role == "passenger") return privatePass(bookingId)?.boardingCode
        val code = driverCodes[bookingId] ?: return null
        return code.code.takeIf {
            current.details.role == "driver" && !current.driverCodeBlocked && code.projection === current &&
                code.proof.verified() && code.proof.owner == current.owner && privateDetails(current.details)
        }
    }
    @Synchronized fun projection(bookingId: Int): TripPrivateProjection? = details[bookingId]?.takeIf { freshDetails(it.details) }
    @Synchronized fun forgetDetails(bookingId: Int) { details.remove(bookingId); driverCodes.remove(bookingId); changed() }
    override fun revoked(owner: SnapshotOwner, bookingId: Int) {
        synchronized(this) {
            if (current(owner)) { live.remove(bookingId); details.remove(bookingId); driverCodes.remove(bookingId); histories.remove(bookingId); terminals.remove(bookingId); warmFallbacks.remove(bookingId); changed() }
        }
    }
    @Synchronized fun forgetLive(owner: SnapshotOwner, bookingId: Int) {
        if (current(owner)) { live.remove(bookingId); driverCodes.remove(bookingId); if (details[bookingId]?.details?.status !in setOf("done", "cancelled")) details.remove(bookingId); changed() }
    }
    @Synchronized fun failedDetails(proof: SnapshotNetworkProof) {
        gate.withCurrentRejectedDetails(proof) {
            failedDetails(proof.request.ticket, proof.request.bookingId, false)
        }
    }

    @Synchronized fun failedDetails(ticket: SnapshotTicket?, bookingId: Int, fallbackAllowed: Boolean) {
        ticket ?: return
        if (!current(ticket.owner)) return
        val value = details[bookingId]
        if (value != null && value.details.role == "driver" && value.owner == ticket.owner &&
            ticket.sequence >= value.proof.request.ticket.sequence && !value.driverCodeBlocked) {
            // Keep permitted warm fields/public fallback, but require another actual DETAILS
            // before any code GET can authorize this driver's code again.
            details[bookingId] = value.copy(driverCodeBlocked = true)
            driverCodes.remove(bookingId)
            changed()
        }
        failedRead(ticket, bookingId, fallbackAllowed)
    }
    @Synchronized fun failedRead(ticket: SnapshotTicket?, bookingId: Int, fallbackAllowed: Boolean) {
        ticket ?: return
        if (fallbackAllowed || !current(ticket.owner)) return
        val newest = maxOf(live[bookingId]?.permit?.ticket?.sequence ?: 0L,
            details[bookingId]?.proof?.request?.ticket?.sequence ?: 0L)
        if (ticket.sequence < newest) return
        gate.expireCandidate(ticket.owner, bookingId, "blocked:" + snapshotHash("invalid-read:${ticket.process}:${ticket.sequence}"))
        forgetLive(ticket.owner, bookingId)
    }
    @Synchronized private fun olderThanPublished(proof: SnapshotNetworkProof): Boolean = proof.request.ticket.sequence < maxOf(
        live[proof.request.bookingId]?.permit?.ticket?.sequence ?: 0L,
        details[proof.request.bookingId]?.proof?.request?.ticket?.sequence ?: 0L,
    )
    @Synchronized fun warmFallback(bookingId: Int): OfflineTripSnapshot? = warmFallbacks[bookingId]?.takeIf { current(it.owner) }
    @Synchronized fun dismissFallback(bookingId: Int) { warmFallbacks.remove(bookingId); changed() }
    suspend fun offerFallback(ticket: SnapshotTicket?, bookingId: Int) {
        ticket ?: return
        val saved = load(ticket.owner, bookingId) ?: return
        synchronized(this) {
            if (current(saved.owner) && ticket.sequence >= (live[bookingId]?.permit?.ticket?.sequence ?: 0L)) {
                warmFallbacks[bookingId] = saved; changed()
            }
        }
    }
    suspend fun acceptRole(proof: SnapshotNetworkProof, value: TripStateDto): Boolean {
        val id = proof.request.bookingId
        if (value.status in setOf("done", "cancelled")) {
            if (!proofCurrent(proof)) return false
            terminal(proof.request.ticket, id, value.status, value.role, value.cancelledInTransit)
            return true
        }
        if (value.status == "pending") {
            return synchronized(this) { if (olderThanPublished(proof) || !proofCurrent(proof)) false else {
                gate.invalidate(proof.request.ticket, id); revoked(proof.owner, id); true
            } }
        }
        val state = snapshotState(value.role, value.status, value.driverPhase, value.boardingUndo)
        val permit = synchronized(this) {
            gate.acceptRead(proof, state)?.also { live[id] = Live(it, processClock()); warmFallbacks.remove(id); changed() }
        } ?: return false
        repo(proof.owner)?.let { store -> publish { store.observe(permit.ticket, id, state, permit = permit) } }
        return gate.canObserve(permit, permit.ticket, id, state)
    }
    suspend fun acceptDetails(proof: SnapshotNetworkProof, value: BookingDetailsDto): Boolean {
        if (!proof.verified() || !current(proof.owner) || proof.request.bookingId != value.bookingId) return false
        if (value.status in setOf("done", "cancelled")) {
            if (!proofCurrent(proof)) return false
            terminal(proof.request.ticket, value.bookingId, value.status, value.role, value.cancelledInTransit)
            if (current(proof.owner)) synchronized(this) {
                details[value.bookingId] = TripPrivateProjection(proof.owner, value, tripPassFromDetails(value), proof, null, processClock()); changed()
            }
            return true
        }
        if (value.status == "pending") synchronized(this) {
            if (olderThanPublished(proof) || !proofCurrent(proof)) return false
            gate.invalidate(proof.request.ticket, value.bookingId); revoked(proof.owner, value.bookingId)
        }
        val state = snapshotState(value.role, value.status, null, value.boardingUndo)
        val route = SnapshotRoute(value.fromCity, value.toCity, value.departAt, value.pickupStop, value.dropoffStop, value.rideHasStops)
        val pass = tripPassFromDetails(value)
        var permit: SnapshotAuthorityPermit? = null
        synchronized(this) {
            val old = details[value.bookingId]
            if (old != null && old.proof.request.ticket.sequence > proof.request.ticket.sequence) return false
            permit = gate.acceptRead(proof, state, value.rideId, route.takeIf { it.valid }, value.contactUnlocked,
                privateTripFingerprint(value, pass).takeIf { value.contactUnlocked })
            // pending grants only current own-details UI; no live/private/cache permission.
            if (value.status != "pending" && permit == null) return false
            driverCodes.remove(value.bookingId)
            details[value.bookingId] = TripPrivateProjection(proof.owner, value, pass, proof, permit, processClock())
            warmFallbacks.remove(value.bookingId)
            permit?.let { live[value.bookingId] = Live(it, processClock()) }
            changed()
        }
        val accepted = permit
        if (accepted != null) repo(proof.owner)?.let { store -> publish {
            store.observe(accepted.ticket, value.bookingId, state, value.rideId, route.takeIf { it.valid }, permit = accepted)
        } }
        return synchronized(this) { details[value.bookingId]?.details === value && freshDetails(value) }
    }
    @Synchronized fun denyBlankCode(expected: TripPrivateProjection, proof: SnapshotNetworkProof): Boolean {
        val id = expected.details.bookingId
        val actual = details[id] ?: return false
        val state = expected.permit?.state ?: return false
        if (!proof.verified() || proof.owner != expected.owner || actual.owner != expected.owner ||
            !current(expected.owner) || proof.request.bookingId != id || proof.request.kind != SnapshotAuthorityKind.DETAILS ||
            actual.fingerprint != expected.fingerprint || actual.permit?.state != state || !privateDetails(actual.details)) return false
        if (!gate.denyBlankCode(proof, expected.fingerprint, state)) return false
        live.remove(id); details.remove(id); driverCodes.remove(id); warmFallbacks.remove(id)
        changed()
        return true
    }

    @Synchronized fun attachCode(expected: TripPrivateProjection, code: String, proof: SnapshotNetworkProof): Boolean {
        if (code.isBlank() || details[expected.details.bookingId] !== expected || !privateDetails(expected.details)) return false
        if (!proof.verified() || proof.owner != expected.owner || proof.request.bookingId != expected.details.bookingId ||
            proof.request.kind != SnapshotAuthorityKind.DETAILS || !gate.current(proof.request.ticket) ||
            proof.request.epoch != expected.proof.request.epoch) return false
        if (expected.details.role == "driver") {
            if (expected.driverCodeBlocked || proof.request.ticket.sequence < expected.proof.request.ticket.sequence) return false
            val previous = driverCodes[expected.details.bookingId]
            if (previous != null && previous.projection === expected && previous.proof.request.ticket.sequence > proof.request.ticket.sequence) return false
            if (!gate.recordCodeSuccess(proof)) return false
            driverCodes[expected.details.bookingId] = DriverBoardingCodeProjection(expected, code, proof)
            changed()
            return true
        }
        if (expected.details.role != "passenger") return false
        val pass = expected.pass.copy(boardingCode = code)
        val state = expected.permit?.input ?: return false
        val permit = gate.acceptRead(proof, state, expected.permit.inputRide, expected.permit.inputRoute, true,
            privateTripFingerprint(expected.details, pass)) ?: return false
        if (!gate.canObserve(permit, permit.ticket, pass.bookingId, state, permit.inputRide, permit.inputRoute)) return false
        if (!gate.recordCodeSuccess(proof)) return false
        details[pass.bookingId] = expected.copy(pass = pass, permit = permit)
        live[pass.bookingId] = Live(permit, expected.stamp)
        changed(); return true
    }
    suspend fun acknowledge(request: SnapshotMutationTicket?, role: String, result: BoardingResult) {
        acknowledgeState(request, snapshotState(role, result.status, null, result.undo))
    }
    suspend fun acknowledgePhase(request: SnapshotMutationTicket?, phase: String, status: String) {
        request ?: return
        val known = synchronized(this) { live[request.bookingId]?.permit?.state }
        val actualStatus = status.takeIf { it in setOf("confirmed", "onboard") } ?: known?.status.orEmpty()
        acknowledgeState(request, SnapshotState("driver", actualStatus, phase, known?.maxOnboard,
            known?.corrected, actualStatus == "confirmed" && known?.corrected != null))
    }
    private suspend fun acknowledgeState(request: SnapshotMutationTicket?, state: SnapshotState) {
        request ?: return
        if (state.status in setOf("done", "cancelled")) { terminal(request.ticket, request.bookingId, state.status, state.role); return }
        val probe = gate.beginMutation(request.bookingId) ?: return
        if (probe.ticket.owner != request.ticket.owner || probe.epoch != request.epoch) return
        val warm = allowsLive(request.bookingId)
        val known = synchronized(this) { live[request.bookingId]?.permit?.state }
        val nextEpoch = snapshotEpoch(state.onboardAt)
        val correction = snapshotEpoch(state.receiptSource)
        val knownEpoch = snapshotEpoch(known?.maxOnboard)
        if (known != null && known.merge(state) == null &&
            ((nextEpoch != null && knownEpoch != null && nextEpoch < knownEpoch) ||
                (correction != null && knownEpoch != null && correction < knownEpoch))) return
        val permit = synchronized(this) { gate.acknowledge(request, state) }
        if (permit == null) {
            // No delayed repository.block(oldTicket): it could invalidate a newer DETAILS while waiting.
            if (gate.beginMutation(request.bookingId)?.epoch == request.epoch + 1) {
                synchronized(this) {
                    if (!allowsLive(request.bookingId)) { live.remove(request.bookingId); details.remove(request.bookingId); driverCodes.remove(request.bookingId) }
                    writes.value = SnapshotPersistence.NOT_DURABLE; changed()
                }
            }
            return
        }
        synchronized(this) {
            if (gate.canObserve(permit, permit.ticket, request.bookingId, state)) {
                details.remove(request.bookingId)
                driverCodes.remove(request.bookingId)
                if (warm) live[request.bookingId] = Live(permit, processClock()) else live.remove(request.bookingId)
                changed()
            }
        }
        repo(request.ticket.owner)?.let { store -> publish(durableAck = true) {
            store.observe(permit.ticket, request.bookingId, state, acknowledgement = true, permit = permit)
        } }
    }
    suspend fun acceptHistory(proof: SnapshotNetworkProof, value: TripReceiptDto): Boolean {
        val permit = synchronized(this) {
            gate.acceptHistory(proof, receiptFingerprint(value))?.also {
                live.remove(value.bookingId); details.remove(value.bookingId); driverCodes.remove(value.bookingId); warmFallbacks.remove(value.bookingId)
                histories[value.bookingId] = value to it.owner
                terminals[value.bookingId] = Terminal(it.owner, value.role, "done", false, processClock()); changed()
            }
        } ?: return false
        repo(permit.owner)?.let { store -> publish(durableAck = true) { store.close(permit.ticket, value.bookingId) } }
        return true
    }
    @Synchronized fun allowsHistory(value: TripReceiptDto): Boolean = histories[value.bookingId]?.let {
        it.first === value && gate.allowsHistory(it.second, value.bookingId, receiptFingerprint(value))
    } == true
    private fun proofCurrent(proof: SnapshotNetworkProof): Boolean = proof.verified() && current(proof.owner) &&
        gate.current(proof.request.ticket) && gate.beginRead(proof.request.bookingId, proof.request.kind)?.epoch == proof.request.epoch
    @Synchronized fun terminal(bookingId: Int): Terminal? = terminals[bookingId]?.takeIf {
        timeCurrent(it.owner, bookingId, it.stamp, "terminal:$bookingId:${it.stamp}")
    }
    suspend fun terminal(ticket: SnapshotTicket?, bookingId: Int, status: String, role: String? = null, inTransit: Boolean = false) {
        terminalWithPersistence(ticket, bookingId, status, role, inTransit)
    }
    suspend fun terminalWithPersistence(ticket: SnapshotTicket?, bookingId: Int, status: String, role: String?, inTransit: Boolean = false): SnapshotPersistence {
        ticket ?: return SnapshotPersistence.STALE
        if (!gate.current(ticket) || status !in setOf("done", "cancelled")) return SnapshotPersistence.STALE
        val actualRole = role ?: synchronized(this) { live[bookingId]?.permit?.state?.role }
        if (!gate.close(ticket, bookingId)) return SnapshotPersistence.STALE
        if (current(ticket.owner) && actualRole in setOf("driver", "passenger")) synchronized(this) {
            terminals[bookingId] = Terminal(ticket.owner, requireNotNull(actualRole), status, inTransit, processClock()); changed()
        }
        return closeWithPersistence(ticket, bookingId, false)
    }
    override suspend fun close(ticket: SnapshotTicket?, bookingId: Int) { close(ticket, bookingId, false) }
    suspend fun closeBookingWithPersistence(ticket: SnapshotTicket?, bookingId: Int): SnapshotPersistence =
        closeWithPersistence(ticket, bookingId, false)
    suspend fun close(ticket: SnapshotTicket?, id: Int, ride: Boolean) { closeWithPersistence(ticket, id, ride) }
    private suspend fun closeWithPersistence(ticket: SnapshotTicket?, id: Int, ride: Boolean): SnapshotPersistence {
        ticket ?: return SnapshotPersistence.STALE
        if (!gate.close(ticket, id, ride)) return SnapshotPersistence.STALE
        synchronized(this) {
            if (ride) {
                driverCodes.entries.removeAll { it.value.projection.owner == ticket.owner && it.value.projection.details.rideId == id }
                details.filterValues { it.owner == ticket.owner && it.details.rideId == id }.keys.toList().forEach { details.remove(it) }
                warmFallbacks.entries.removeAll { it.value.owner == ticket.owner && it.value.rideId == id }
                live.entries.removeAll { it.value.permit.owner == ticket.owner && !allowsLive(it.key) }
            } else { live.remove(id); driverCodes.remove(id); warmFallbacks.remove(id); if (details[id]?.details?.status !in setOf("done", "cancelled")) details.remove(id) }
            changed()
        }
        val store = repo(ticket.owner)
        return when {
            store != null -> publish(durableAck = true) { store.close(ticket, id, ride) }
            !current(ticket.owner) || resetPending -> SnapshotPersistence.STALE
            durable -> SnapshotPersistence.NOT_DURABLE
            else -> SnapshotPersistence.UNCHANGED
        }
    }
    /** Explicit logout/auth-reset only. No encrypted preferences/key is opened or inspected. */
    fun logoutCleanup() {
        val context = app ?: return
        val epoch = synchronized(this) {
            active.get()?.let { gate.retire(it); retiredNamespaces += it.namespace }
            live.clear(); details.clear(); driverCodes.clear(); histories.clear(); terminals.clear(); warmFallbacks.clear()
            active.set(null); owners.value = null; durable = false
            resetPending = true; storageEpoch++; changed()
            storageEpoch
        }
        jobs.launch {
            diskAccess.withLock {
                if (epoch != storageEpoch) return@withLock
                writes.value = SnapshotLogoutCleanup(context).run()
                repository = null
                if (epoch == storageEpoch) resetPending = writes.value != SnapshotPersistence.COMMITTED
                changed()
            }
        }
    }
    fun ensureResetCommitted(): Boolean {
        if (resetPending) return false
        val context = app ?: return true
        val cleanup = SnapshotLogoutCleanup(context)
        return when (cleanup.pending()) {
            false -> true
            true -> {
                val result = cleanup.run(); writes.value = result
                result == SnapshotPersistence.COMMITTED
            }
            null -> false
        }
    }
    internal fun resetForTest() {
        jobs.coroutineContext.cancelChildren()
        synchronized(this) {
            active.set(null); gate = SnapshotAuthorityGate(active); durable = false; repository = null; app = null
            resetPending = false; storageEpoch++
            transientIdentity = null; transientScope = null; suppliedClock = null; retiredNamespaces.clear()
            live.clear(); details.clear(); driverCodes.clear(); histories.clear(); terminals.clear(); warmFallbacks.clear(); owners.value = null; writes.value = SnapshotPersistence.UNCHANGED; changed()
        }
    }
    internal fun installForTest(disk: SnapshotDisk, clock: () -> SnapshotClock?) {
        resetForTest(); suppliedClock = clock
        repository = OfflineTripSnapshotRepository(disk, active, { this.clock() }, gate, ::changed)
    }
}

/** Exact registered preference pair only. The shared Android Keystore alias is never touched. */
internal class SnapshotLogoutCleanup(
    private val context: Context,
    private val delete: (String) -> Boolean = { context.deleteSharedPreferences(it) },
    private val absent: (String) -> Boolean = { name ->
        val directory = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        !java.io.File(directory, "$name.xml").exists() && !java.io.File(directory, "$name.xml.bak").exists()
    },
) {
    fun pending(): Boolean? = runCatching {
        context.getSharedPreferences(SNAPSHOT_META_PREFS, Context.MODE_PRIVATE).getBoolean(OfflineStoreReset.PENDING, false)
    }.getOrNull()
    fun run(): SnapshotPersistence {
        val meta = context.getSharedPreferences(SNAPSHOT_META_PREFS, Context.MODE_PRIVATE)
        val marked = runCatching { meta.edit().putBoolean(OfflineStoreReset.PENDING, true).commit() }.getOrDefault(false)
        val removed = runCatching { delete(SNAPSHOT_SECURE_PREFS) && absent(SNAPSHOT_SECURE_PREFS) }.getOrDefault(false)
        if (!removed) return if (marked) SnapshotPersistence.DURABLY_BLOCKED else SnapshotPersistence.NOT_DURABLE
        val cleaned = runCatching { delete(SNAPSHOT_META_PREFS) && absent(SNAPSHOT_META_PREFS) }.getOrDefault(false)
        if (cleaned) discardOfflineWriteRecovery(meta)
        return if (cleaned) SnapshotPersistence.COMMITTED else SnapshotPersistence.DURABLY_BLOCKED
    }
}

internal fun snapshotState(role: String, status: String, phase: String?, undo: BoardingUndoTerms): SnapshotState =
    SnapshotState(role, status, phase, undo.onboardAt, undo.sourceOnboardAt, !undo.confirmedId.isNullOrBlank())
