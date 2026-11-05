package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OfflineTripSnapshotRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = SnapshotCoreFixture(SnapshotCoreDisk(temporary.newFolder().toPath()))

    @Test fun actualFileReopenRequiresNewProcessAuthorityAndPreservesOnlyMinimalOwnProjection() = runBlocking {
        val f = fixture()
        assertEquals(SnapshotPersistence.COMMITTED, f.save())
        assertNotNull(f.repo.load(f.owner, 11))
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertNull(cold.repo.load(cold.owner, 11))
        val fresh = cold.read()
        assertNotNull(fresh.permit)
        val saved = cold.repo.load(cold.owner, 11)!!
        assertEquals(SnapshotCoreFixture.ROUTE, saved.route)
        assertEquals("onboard", saved.state.status)
        val raw = f.disk.read(f.owner.namespace)!!
        for (field in listOf("phone", "boarding_code", "meet_lat", "token", "price", "passenger_name")) assertFalse(field, raw.contains(field))
        cold.active.set(cold.owner.copy(userId = 8))
        assertNull(cold.repo.load(cold.owner, 11))
    }
    @Test fun cachedDtoBareTicketAndRolePermitCannotWriteFullDetails() = runBlocking {
        val f = fixture()
        assertEquals(SnapshotPersistence.STALE, f.repo.observe(f.repo.ticket()!!, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE))
        val read = f.read(kind = SnapshotAuthorityKind.ROLE, route = null, rideId = null)
        assertEquals(SnapshotPersistence.STALE, f.repo.observe(read.request.ticket, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, permit = read.permit))
        assertNull(f.disk.read(f.owner.namespace))
    }
    @Test fun receiptBeforeFirstPayloadSurvivesReopenAndCannotBeErasedByOldEpoch() = runBlocking {
        val f = fixture()
        val correction = SnapshotCoreFixture.CORRECTION
        val request = f.gate.beginMutation(11)!!
        val ack = f.gate.acknowledge(request, correction)!!
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.observe(request.ticket, 11, correction,
            acknowledgement = true, permit = ack))
        assertNull(f.repo.load(f.owner, 11)) // POST is not cold read authority, nor a full route.
        val cold = SnapshotCoreFixture(f.disk, f.owner)
        assertEquals(SnapshotPersistence.STALE, cold.save())
        assertNull(cold.repo.load(cold.owner, 11))
        val newer = SnapshotCoreFixture.ONBOARD.copy(onboardAt = SnapshotCoreFixture.B)
        assertEquals(SnapshotPersistence.COMMITTED, cold.save(newer))
        val saved = cold.repo.load(cold.owner, 11)!!
        assertEquals(snapshotEpoch(SnapshotCoreFixture.B), snapshotEpoch(saved.state.maxOnboard))
        assertEquals(snapshotEpoch(SnapshotCoreFixture.A), snapshotEpoch(saved.state.corrected))
    }
    @Test fun unchangedReadOrdersOlderRequestsButDoesNotRenewEitherSavedClock() = runBlocking {
        val f = fixture()
        f.save()
        val initial = f.repo.load(f.owner, 11)!!
        val older = f.gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
        f.now = f.now.copy(wall = 2000, before = 2000, after = 2000)
        assertEquals(SnapshotPersistence.UNCHANGED, f.save())
        assertNull(f.accept(older, SnapshotCoreFixture.ONBOARD, null, null))
        val unchanged = f.repo.load(f.owner, 11)!!
        assertEquals(initial.payloadStamp, unchanged.payloadStamp)
        assertEquals(initial.stateStamp, unchanged.stateStamp)
        val deadline = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS
        f.now = f.now.copy(wall = deadline, before = deadline, after = deadline)
        assertNull(f.repo.load(f.owner, 11))
    }
    @Test fun corruptFieldsTypesAndOwnerOriginAreDeniedEvenWithCurrentAuthority() = runBlocking {
        for (damage in listOf<(JSONObject) -> Unit>(
            { it.getJSONObject("records").getJSONObject("11").put("phone", "private") },
            { it.getJSONObject("records").getJSONObject("11").put("booking", "11") },
            { it.getJSONObject("records").getJSONObject("11").put("phase", "unknown") },
            { it.put("origin", "https://other.test") },
            { it.put("schema", 1) },
        )) {
            val f = fixture(); f.save()
            val doc = JSONObject(f.disk.read(f.owner.namespace)!!)
            damage(doc); f.disk.write(f.owner.namespace, doc.toString())
            assertNull(f.repo.load(f.owner, 11))
        }
    }
    @Test fun firstCompatibleDetailsAfterNewerSparseStateKeepsTheNewerPhaseAndStateTime() = runBlocking {
        val f = fixture()
        val full = f.gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
        val sparse = f.read(SnapshotCoreFixture.ONBOARD.copy(phase = "arriving"), kind = SnapshotAuthorityKind.ROLE, route = null, rideId = null)
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.observe(sparse.request.ticket, 11,
            SnapshotCoreFixture.ONBOARD.copy(phase = "arriving"), permit = sparse.permit))
        f.now = f.now.copy(wall = 2000, before = 2000, after = 2000)
        val permit = f.accept(full, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE)
        assertNotNull(permit)
        assertEquals(SnapshotPersistence.COMMITTED, f.repo.observe(full.ticket, 11, SnapshotCoreFixture.ONBOARD,
            22, SnapshotCoreFixture.ROUTE, permit = permit))
        val saved = f.repo.load(f.owner, 11)!!
        assertEquals("arriving", saved.state.phase)
        assertEquals(1000L, saved.stateStamp.wall)
    }
    @Test fun closeRevokesMemoryBeforeWaitingForPausedOldWriterAndCannotBeUndone() = runBlocking {
        val f = fixture()
        val read = f.read()
        f.disk.entered = CountDownLatch(1); f.disk.continueWrite = CountDownLatch(1)
        val paused = async(Dispatchers.Default) {
            f.repo.observe(read.request.ticket, 11, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = read.permit)
        }
        try {
            check(f.disk.entered!!.await(5, TimeUnit.SECONDS))
            val close = async(start = CoroutineStart.UNDISPATCHED) { f.repo.close(f.repo.ticket()!!, 11) }
            assertFalse(f.gate.allowsSnapshot(f.owner, 11, SnapshotEpochState.first(SnapshotCoreFixture.ONBOARD)!!, 22, SnapshotCoreFixture.ROUTE))
            f.disk.continueWrite!!.countDown()
            assertEquals(SnapshotPersistence.STALE, paused.await())
            assertEquals(SnapshotPersistence.COMMITTED, close.await())
            assertNull(f.repo.load(f.owner, 11))
            assertEquals(SnapshotPersistence.STALE, f.save())
        } finally { f.disk.continueWrite!!.countDown() }
    }
    @Test fun pausedWriterForOldLoginCannotOverwriteOrAuthorizeTheNewLoginNamespace() = runBlocking {
        val f = fixture()
        val read = f.read()
        f.disk.entered = CountDownLatch(1); f.disk.continueWrite = CountDownLatch(1)
        val paused = async(Dispatchers.Default) {
            f.repo.observe(read.request.ticket, 11, SnapshotCoreFixture.ONBOARD, 22, SnapshotCoreFixture.ROUTE, permit = read.permit)
        }
        try {
            check(f.disk.entered!!.await(5, TimeUnit.SECONDS))
            val next = f.owner.copy(scope = UUID.randomUUID().toString(), generation = 2)
            f.active.set(next)
            f.disk.continueWrite!!.countDown()
            assertEquals(SnapshotPersistence.STALE, paused.await())
            val fresh = SnapshotCoreFixture(f.disk, next)
            assertNull(fresh.repo.load(next, 11))
            assertEquals(SnapshotPersistence.COMMITTED, fresh.save(SnapshotCoreFixture.ONBOARD.copy(onboardAt = SnapshotCoreFixture.B)))
            assertNotNull(fresh.repo.load(next, 11))
            assertNull(fresh.repo.load(f.owner, 11))
        } finally { f.disk.continueWrite!!.countDown() }
    }
}

/** Real temporary files for the pure repository tests; NOT an Android encryption-adapter proof. */
internal class SnapshotCoreDisk(private val directory: Path) : SnapshotDisk {
    var failPayload = false
    var failBarrier = false
    var failClear = false
    var entered: CountDownLatch? = null
    var continueWrite: CountDownLatch? = null
    private val barriers get() = directory.resolve("barriers.json")
    override fun read(namespace: String): String? = directory.resolve("$namespace.json").takeIf(Files::exists)?.toFile()?.readText(Charsets.UTF_8)
    override fun write(namespace: String, value: String): Boolean {
        entered?.countDown(); continueWrite?.let { check(it.await(5, TimeUnit.SECONDS)) }
        if (failPayload) return false
        directory.resolve("$namespace.json").toFile().writeText(value, Charsets.UTF_8); return true
    }
    private fun barrierMap() = if (Files.exists(barriers)) JSONObject(barriers.toFile().readText(Charsets.UTF_8)) else JSONObject()
    override fun barrier(key: String): String? = barrierMap().let { if (it.has(key)) it.getString(key) else null }
    override fun setBarrier(key: String, value: String?): Boolean {
        if (failBarrier || (failClear && value == null)) return false
        val map = barrierMap()
        if (map.optString(key) == "closed" && value != "closed") return false
        if (value == null) map.remove(key) else map.put(key, value)
        barriers.toFile().writeText(map.toString(), Charsets.UTF_8); return true
    }
    override fun retire(namespace: String): Boolean { Files.deleteIfExists(directory.resolve("$namespace.json")); return true }
    fun recoverWrites() { failPayload = false; failBarrier = false; failClear = false }
}

internal class SnapshotCoreFixture(
    val disk: SnapshotCoreDisk,
    val owner: SnapshotOwner = SnapshotOwner(7, UUID.randomUUID().toString(), 1, "https://example.test"),
) {
    companion object {
        const val A = "2026-10-24T12:00:00.000000"
        const val B = "2026-10-24T12:00:00.000001"
        val ROUTE = SnapshotRoute("Уфа", "Мелеуз", "2030-01-01T10:00:00", "Стерлитамак", "Салават", true)
        val ONBOARD = SnapshotState("passenger", "onboard", onboardAt = A)
        val CORRECTION = SnapshotState("passenger", "confirmed", receiptSource = A, receiptAccepted = true)
    }
    val active = AtomicReference<SnapshotOwner?>(owner)
    val gate = SnapshotAuthorityGate(active)
    var now = SnapshotClock(3, 1000, 1000, 1000)
    var clockAvailable = true
    val repo = OfflineTripSnapshotRepository(disk, active, { now.takeIf { clockAvailable } }, gate)
    data class Read(val request: SnapshotReadTicket, val permit: SnapshotAuthorityPermit?)
    fun proof(request: SnapshotReadTicket): SnapshotNetworkProof = SnapshotReadTrace().apply {
        attempt(); startWire(authorized = true); status(200)
    }.confirmed200(request, owner)!!
    fun accept(request: SnapshotReadTicket, state: SnapshotState, rideId: Int?, route: SnapshotRoute?) =
        gate.acceptRead(proof(request), state, rideId, route)
    fun read(
        state: SnapshotState = ONBOARD, kind: SnapshotAuthorityKind = SnapshotAuthorityKind.DETAILS,
        route: SnapshotRoute? = ROUTE, rideId: Int? = 22,
    ): Read {
        val request = gate.beginRead(11, kind)!!
        return Read(request, accept(request, state, rideId, route))
    }
    suspend fun save(state: SnapshotState = ONBOARD): SnapshotPersistence {
        val read = read(state)
        return repo.observe(read.request.ticket, 11, state, 22, ROUTE, permit = read.permit)
    }
}
