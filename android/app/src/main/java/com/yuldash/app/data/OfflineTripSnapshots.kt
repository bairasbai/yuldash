package com.yuldash.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Only the new no-contact cache. The old passenger passport and Outbox remain independent. */
internal object OfflineTripSnapshots {
    const val AUTH_SCOPE = "offline_trip_session_scope"
    const val SECURE_PREFS = "yuldash_trip_snapshot_secure_v1"
    const val META_PREFS = "yuldash_trip_snapshot_meta_v1"
    private val active = AtomicReference<SnapshotOwner?>(null)
    private val lastOwner = AtomicReference<SnapshotOwner?>(null)
    private val trustSuspended = AtomicBoolean(false)
    private val authReconfirmed = AtomicBoolean(false)
    private val ownerState = MutableStateFlow<SnapshotOwner?>(null)
    val owner = ownerState.asStateFlow()
    private val versions = MutableStateFlow(0L)
    val version = versions.asStateFlow()
    private val writes = MutableStateFlow(SnapshotPersistence.UNCHANGED)
    val persistence = writes.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var repository: OfflineTripSnapshotRepository? = null
    @Volatile private var context: Context? = null
    @Volatile private var testClock: (() -> SnapshotClock?)? = null

    @Synchronized fun init(app: Context) {
        context = app.applicationContext
        if (repository != null) return
        val meta = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val secure = runCatching {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, SECURE_PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
        val disk = object : SnapshotDisk {
            private fun barriers(): JSONObject = readOfflineString(meta, meta, "barriers")?.let(::JSONObject) ?: JSONObject()
            override fun read(namespace: String): String? = readOfflineString(requireNotNull(secure), meta, namespace)
            override fun write(namespace: String, value: String): Boolean =
                secure?.let { commitOfflineString(it, namespace, value, meta) } == true
            override fun barrier(key: String): String? = barriers().let { values ->
                if (!values.has(key)) null else values.getString(key).also {
                    require(it in setOf("closed", "blocked", "retired") || it.matches(Regex("blocked:[0-9a-f]{64}")))
                }
            }
            override fun setBarrier(key: String, value: String?): Boolean {
                val values = barriers()
                if (values.optString(key) == "closed" && value != "closed") return false
                if (value == null) values.remove(key) else values.put(key, value)
                return commitOfflineString(meta, "barriers", values.toString(), meta)
            }
            override fun retire(namespace: String): Boolean = secure?.let { commitOfflineString(it, namespace, null, meta) } == true
            override fun purgeExcept(namespace: String?): Boolean {
                val store = secure ?: return false
                val keys = store.all.keys.filter { it.matches(Regex("[0-9a-f]{64}")) && it != namespace }
                if (keys.any { !commitOfflineString(store, it, null, meta) }) return false
                val values = barriers()
                values.keys().asSequence().toList().filter { namespace == null || !it.startsWith("$namespace:") }.forEach(values::remove)
                return commitOfflineString(meta, "barriers", values.toString(), meta)
            }
        }
        repository = OfflineTripSnapshotRepository(disk, active, { this.clock() }) { versions.update { it + 1 } }
        // Cleanup starts after auth publishes its resolved scope, never before cold resolution.
    }

    fun clock(): SnapshotClock? {
        testClock?.let { return it() }
        return runCatching {
        val app = context ?: return null
        val boot = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
        val before = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val after = SystemClock.elapsedRealtime()
        require(boot == Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT))
        SnapshotClock(boot, wall, before, after).takeIf { it.valid }
        }.getOrNull()
    }

    /** Cheap draw/deadline guard after a full BOOT_COUNT validation in this process. */
    fun clockInBoot(boot: Int): SnapshotClock? {
        testClock?.let { return it() }
        val before = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        return SnapshotClock(boot, wall, before, SystemClock.elapsedRealtime())
    }

    /** Pure publication under auth lock; retire IO is queued, never awaited from that lock. */
    fun activate(userId: Int?, durableScope: String?, generation: Long) {
        val next = if (userId != null && durableScope != null) SnapshotOwner(userId, durableScope, generation).takeIf { it.valid } else null
        val old = lastOwner.getAndSet(next)
        active.set(next)
        ownerState.value = next.takeUnless { trustSuspended.get() }
        scope.launch {
            if (old != null && next?.namespace != old.namespace) repository?.retire(old)?.let { writes.value = it }
            runCatching { repository?.purge() }
        }
        versions.update { it + 1 }
    }
    // Retain identity for invalidating accepted terminal/consent acks, but never read/show it.
    fun suspendTrust() {
        trustSuspended.set(true); authReconfirmed.set(false)
        ownerState.value = null; versions.update { it + 1 }
    }
    fun authReconfirmed() { authReconfirmed.set(true) }
    fun successfulRead(ticket: SnapshotTicket?): SnapshotTicket? {
        ticket ?: return null
        if (active.get() != ticket.owner) return null
        if (trustSuspended.get() && authReconfirmed.get()) {
            trustSuspended.set(false); ownerState.value = ticket.owner; versions.update { it + 1 }
        }
        return ticket.takeIf { current(it.owner) }
    }
    fun clearAll() { scope.launch { runCatching { repository?.purge() } } }
    fun ticket(): SnapshotTicket? = if (ownerState.value == active.get() && ownerState.value != null) repository?.ticket() else null
    fun ackTicket(): SnapshotTicket? = repository?.ticket()
    fun current(value: SnapshotOwner): Boolean = active.get() == value && ownerState.value == value
    suspend fun load(value: SnapshotOwner, bookingId: Int): OfflineTripSnapshot? = withContext(Dispatchers.IO) {
        if (!current(value)) null else repository?.load(value, bookingId)?.takeIf { current(value) }
    }
    private suspend fun publish(durableAck: Boolean = false, action: suspend (OfflineTripSnapshotRepository) -> SnapshotPersistence) {
        withContext(if (durableAck) Dispatchers.IO + NonCancellable else Dispatchers.IO) {
            writes.value = repository?.let { action(it) } ?: SnapshotPersistence.NOT_DURABLE
        }
    }
    private fun state(role: String, status: String, phase: String?, undo: BoardingUndoTerms) =
        SnapshotState(role, status, phase, undo.onboardAt, undo.sourceOnboardAt, !undo.confirmedId.isNullOrBlank())
    suspend fun details(ticket: SnapshotTicket?, value: BookingDetailsDto) {
        ticket ?: return
        if (value.status in setOf("done", "cancelled")) { close(ticket, value.bookingId); return }
        publish { it.observe(ticket, value.bookingId, state(value.role, value.status, null, value.boardingUndo), value.rideId,
            SnapshotRoute(value.fromCity, value.toCity, value.departAt, value.pickupStop, value.dropoffStop, value.rideHasStops)) }
    }
    suspend fun role(ticket: SnapshotTicket?, bookingId: Int, value: TripStateDto) {
        ticket ?: return
        if (value.status in setOf("done", "cancelled")) { close(ticket, bookingId); return }
        publish { it.observe(ticket, bookingId, state(value.role, value.status, value.driverPhase, value.boardingUndo)) }
    }
    suspend fun boarding(ticket: SnapshotTicket?, bookingId: Int, role: String, value: BoardingResult) {
        ticket ?: return
        if (value.status in setOf("done", "cancelled")) { close(ticket, bookingId); return }
        publish(durableAck = true) { it.observe(ticket, bookingId, state(role, value.status, null, value.undo), acknowledgement = true) }
    }
    suspend fun close(ticket: SnapshotTicket?, id: Int, ride: Boolean = false) {
        ticket ?: return
        publish(durableAck = true) { it.close(ticket, id, ride) }
    }
    suspend fun denied(ticket: SnapshotTicket?, bookingId: Int, failure: Throwable?) {
        if (failure is ApiException && failure.status in setOf(401, 403, 404, 410)) close(ticket, bookingId)
    }
    internal fun resetForTest() {
        active.set(null); lastOwner.set(null); ownerState.value = null
        trustSuspended.set(false); authReconfirmed.set(false)
        scope.coroutineContext.cancelChildren()
        repository = null; context = null; testClock = null; writes.value = SnapshotPersistence.UNCHANGED
    }
    internal fun installForTest(disk: SnapshotDisk, clock: () -> SnapshotClock?) {
        resetForTest()
        testClock = clock
        repository = OfflineTripSnapshotRepository(disk, active, { this.clock() }) { versions.update { it + 1 } }
    }
}
