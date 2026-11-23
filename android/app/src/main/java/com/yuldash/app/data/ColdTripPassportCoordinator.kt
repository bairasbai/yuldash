package com.yuldash.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ColdTripPassportCoordinator(
    private val monitor: Any,
    private val diskAccess: Mutex,
    private val authority: () -> SnapshotAuthorityGate,
    private val app: () -> Context?,
    private val storageEpoch: () -> Long,
    private val repo: (SnapshotOwner) -> OfflineTripSnapshotRepository?,
    private val current: (SnapshotOwner) -> Boolean,
    private val cacheEnabled: (SnapshotOwner) -> Boolean,
    private val privateDetails: (BookingDetailsDto?) -> Boolean,
    private val proofCurrent: (SnapshotNetworkProof) -> Boolean,
    private val projection: (Int) -> TripPrivateProjection?,
    private val clock: () -> SnapshotClock?,
    private val changed: () -> Unit,
    private val publishExpiry: (SnapshotColdRevocation, OfflineTripSnapshotRepository) -> Unit,
) {
    private data class ColdSavePreparation(
        val owner: SnapshotOwner,
        val projection: TripPrivateProjection,
        val permit: SnapshotAuthorityPermit,
        val state: SnapshotState,
        val rideId: Int,
        val route: SnapshotRoute,
        val detailsTicket: SnapshotTicket,
        val barrier: String,
    )

    private fun prepareColdSave(pass: TripPass, expectedGeneration: Long?): ColdSavePreparation? = synchronized(monitor) {
        if (expectedGeneration != null && expectedGeneration != ApiClient.queueSessionGeneration()) return null
        val projection = projection(pass.bookingId) ?: return null
        val owner = projection.owner
        val permit = projection.permit ?: return null
        val codeProof = projection.codeProof ?: return null
        val detailsTicket = projection.proof.request.ticket
        val codeTicket = codeProof.request.ticket
        val route = SnapshotRoute(projection.details.fromCity, projection.details.toCity, projection.details.departAt,
            projection.details.pickupStop, projection.details.dropoffStop, projection.details.rideHasStops)
        if (!current(owner) || !cacheEnabled(owner) || projection.details.role != "passenger" ||
            projection.details.status !in setOf("confirmed", "onboard") || pass.boardingCode.isBlank() ||
            privateTripFingerprint(projection.details, pass) != projection.fingerprint ||
            !privateDetails(projection.details) || !proofCurrent(projection.proof) || !proofCurrent(codeProof) ||
            projection.proof.request.kind != SnapshotAuthorityKind.DETAILS ||
            codeProof.request.kind != SnapshotAuthorityKind.DETAILS || codeProof.owner != owner ||
            codeProof.request.bookingId != pass.bookingId || codeProof.request.epoch != projection.proof.request.epoch ||
            permit.kind != SnapshotAuthorityKind.DETAILS || permit.ticket != codeTicket ||
            permit.fingerprint != projection.fingerprint || detailsTicket.process != codeTicket.process ||
            detailsTicket.sequence >= codeTicket.sequence || permit.inputRide != projection.details.rideId ||
            permit.inputRoute != route || !route.valid) return null
        val state = permit.input ?: return null
        val rideId = projection.details.rideId.takeIf { it > 0 } ?: return null
        val rawHash = snapshotHash(pass.toJson().toString())
        val barrier = "blocked:" + snapshotHash("cold-save\n$detailsTicket\n$codeTicket\n${projection.fingerprint}\n$rawHash")
        ColdSavePreparation(owner, projection, permit, state, rideId, route, detailsTicket, barrier)
    }

    suspend fun saveColdPassport(
        context: Context,
        pass: TripPass,
        retryMigration: Boolean = false,
        expectedGeneration: Long? = null,
        shouldContinue: () -> Boolean = { true },
    ): SnapshotPersistence = withContext(Dispatchers.IO) {
        val epoch = storageEpoch()
        diskAccess.withLock {
            if (epoch != storageEpoch() || !shouldContinue()) return@withLock SnapshotPersistence.STALE
            val preparation = prepareColdSave(pass, expectedGeneration) ?: return@withLock SnapshotPersistence.STALE
            val owner = preparation.owner
            val store = repo(owner) ?: return@withLock SnapshotPersistence.NOT_DURABLE
            val permit = preparation.permit
            val state = preparation.state
            val rideId = preparation.rideId
            val route = preparation.route
            val bookingId = pass.bookingId
            val began = store.beginColdSave(permit, state, rideId, route, preparation.barrier)
            if (began != SnapshotPersistence.COMMITTED) return@withLock began
            if (!shouldContinue() || prepareColdSave(pass, expectedGeneration) == null) {
                return@withLock SnapshotPersistence.STALE
            }
            val receipt = TripPassStore.commitSecure(context, pass, preparation.projection.fingerprint,
                retryMigration, expectedGeneration) ?: return@withLock SnapshotPersistence.DURABLY_BLOCKED
            if (!shouldContinue() || prepareColdSave(pass, expectedGeneration) == null) {
                return@withLock SnapshotPersistence.STALE
            }
            val observed = store.observe(permit.ticket, bookingId, state, rideId, route,
                permit = permit, coldSave = true)
            if (observed != SnapshotPersistence.COMMITTED) return@withLock observed
            if (!shouldContinue() || prepareColdSave(pass, expectedGeneration) == null) {
                return@withLock SnapshotPersistence.STALE
            }
            store.commitColdGrant(permit, preparation.detailsTicket, receipt, state, rideId, route)
        }
    }

    suspend fun loadColdPassport(owner: SnapshotOwner, bookingId: Int,
        coldRestoreAdmission: (() -> SnapshotColdRestoreTicket?)? = null,
    ): ColdTripPassport? = withContext(Dispatchers.IO) {
        val context = app() ?: return@withContext null
        val epoch = storageEpoch()
        diskAccess.withLock {
            if (epoch != storageEpoch() || !cacheEnabled(owner)) return@withLock null
            val store = repo(owner) ?: return@withLock null
            val restore = if (coldRestoreAdmission == null) authority().beginColdRestore(owner, bookingId)
                else coldRestoreAdmission()
            if (restore == null) return@withLock null
            val candidate = TripPassStore.readSecureCandidate(context, owner, bookingId) ?: return@withLock null
            store.loadCold(owner, bookingId, candidate, restore)
        }
    }

    fun canDrawCold(value: ColdTripPassport): Boolean {
        val owner = value.snapshot.owner
        val grant = value.grant
        if (!cacheEnabled(owner) || !grant.valid || grant.ownerId != owner.userId ||
            grant.origin != owner.origin || grant.scope != owner.scope ||
            grant.bookingId != value.snapshot.bookingId || grant.rideId != value.snapshot.rideId ||
            value.snapshot.state.role != "passenger" || value.pass.bookingId != value.snapshot.bookingId ||
            value.pass.boardingCode.isBlank() || grant.tripPassSha256 != snapshotHash(value.pass.toJson().toString())) {
            return false
        }
        val now = clock()
        if (now == null || value.remaining(now) == null) {
            if (authority().expireCandidate(owner, value.snapshot.bookingId, grant.snapshotCandidate)) changed()
            val revocation = authority().ticket()?.let { authority().revokeCold(it, value.snapshot.bookingId) }
            val store = repo(owner)
            if (revocation != null && store != null) publishExpiry(revocation, store)
            return false
        }
        return authority().allowsCold(value)
    }
}
