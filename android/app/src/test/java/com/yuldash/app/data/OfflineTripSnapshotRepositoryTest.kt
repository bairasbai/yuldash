package com.yuldash.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OfflineTripSnapshotRepositoryTest {
    private val a = "2026-10-24T12:00:00.000000"
    private val b = "2026-10-24T12:00:00.000001"
    private val route = SnapshotRoute("Уфа", "Мелеуз", "2026-10-25T10:00:00", "Стерлитамак", "Салават", true)
    private fun owner(id: Int = 7) = SnapshotOwner(id, UUID.randomUUID().toString(), 1)
    private class Disk(val dir: Path) : SnapshotDisk {
        var failPayload = false
        var failBarrier = false
        var failClear = false
        var entered: CountDownLatch? = null
        var continueWrite: CountDownLatch? = null
        override fun read(namespace: String): String? = dir.resolve("$namespace.json").takeIf(Files::exists)?.let { it.toFile().readText(Charsets.UTF_8) }
        override fun write(namespace: String, value: String): Boolean {
            entered?.countDown(); continueWrite?.let { check(it.await(5, TimeUnit.SECONDS)) }
            if (failPayload) return false
            dir.resolve("$namespace.json").toFile().writeText(value, Charsets.UTF_8); return true
        }
        private fun barriers() = dir.resolve("barriers.json").takeIf(Files::exists)?.let { JSONObject(it.toFile().readText(Charsets.UTF_8)) } ?: JSONObject()
        override fun barrier(key: String): String? = barriers().let { if (it.has(key)) it.getString(key) else null }
        override fun setBarrier(key: String, value: String?): Boolean {
            if (failBarrier || (failClear && value == null)) return false
            val map = barriers()
            if (map.optString(key) in setOf("closed", "retired") && value != map.optString(key)) return false
            if (value == null) map.remove(key) else map.put(key, value)
            dir.resolve("barriers.json").toFile().writeText(map.toString(), Charsets.UTF_8); return true
        }
        override fun retire(namespace: String): Boolean { Files.deleteIfExists(dir.resolve("$namespace.json")); return true }
    }
    private fun disk(): Disk {
        val parent = System.getProperty("offline.snapshot.test.tmp")?.let(Path::of)
        return Disk(if (parent == null) Files.createTempDirectory("snapshot-test-") else Files.createTempDirectory(parent, "case-"))
    }
    private val time = SnapshotClock(3, 1000, 1000, 1000)
    private fun observed(status: String = "onboard", epoch: String? = a) = SnapshotState("passenger", status, onboardAt = epoch)

    @Test fun realDiskReopenPreservesOnlyOwnMinimalOnboardAndBothStops() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        assertEquals(SnapshotPersistence.COMMITTED, repo.observe(repo.ticket()!!, 11, observed(), 22, route))
        val reopened = OfflineTripSnapshotRepository(storage, active, { time.copy(wall = 1001, before = 1001, after = 1001) })
        val saved = reopened.load(own, 11)!!
        assertEquals(route, saved.route); assertEquals("onboard", saved.state.status)
        val raw = storage.read(own.namespace)!!
        for (privateField in listOf("phone", "boarding_code", "pickup_lat", "token", "price", "passenger_name")) assertFalse(privateField, raw.contains(privateField))
        active.set(owner(8)); assertNull(reopened.load(own, 11))
    }
    @Test fun wholeRideBarrierBlocksTheFirstLateDetailsAfterReopen() = runBlocking {
        for (closing in listOf("cancel", "complete")) {
            val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
            val repo = OfflineTripSnapshotRepository(storage, active, { time })
            val oldRequest = repo.ticket()!!
            assertEquals(closing, SnapshotPersistence.COMMITTED, repo.close(repo.ticket()!!, 22, ride = true))
            assertEquals(SnapshotPersistence.STALE, repo.observe(oldRequest, 11, observed(), 22, route))
            val reopened = OfflineTripSnapshotRepository(storage, active, { time })
            assertEquals(SnapshotPersistence.STALE, reopened.observe(reopened.ticket()!!, 11, observed(), 22, route))
            assertNull(reopened.load(own, 11))
        }
    }
    @Test fun receiptBeforeDetailsSurvivesDeathAndRejectsAWhileAcceptingB() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        val receipt = SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true)
        repo.observe(repo.ticket()!!, 11, receipt, acknowledgement = true)
        val reopened = OfflineTripSnapshotRepository(storage, active, { time })
        assertEquals(SnapshotPersistence.STALE, reopened.observe(reopened.ticket()!!, 11, observed(), 22, route))
        assertEquals(SnapshotPersistence.COMMITTED, reopened.observe(reopened.ticket()!!, 11, observed(epoch = b), 22, route))
        assertEquals(SnapshotPersistence.STALE, reopened.observe(reopened.ticket()!!, 11, receipt, acknowledgement = true))
        assertEquals("onboard", reopened.load(own, 11)!!.state.status)
    }
    @Test fun queuedDoneAndBookingClosureRemainClosedAfterReopen() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        val pendingRead = repo.ticket()!!
        repo.close(repo.ticket()!!, 11)
        assertEquals(SnapshotPersistence.STALE, repo.observe(pendingRead, 11, observed(), 22, route))
        assertNull(OfflineTripSnapshotRepository(storage, active, { time }).load(own, 11))
    }
    @Test fun failedEncryptedChangeHasADurableBlockAndDoesNotReviveOldOnboard() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        storage.failPayload = true
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, repo.observe(repo.ticket()!!, 11,
            SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true), acknowledgement = true))
        storage.failPayload = false
        assertNull(OfflineTripSnapshotRepository(storage, active, { time }).load(own, 11))
    }
    @Test fun pausedOldWriterCannotOverwriteSameBookingInANewLoginOrPublishIt() = runBlocking {
        val storage = disk(); val old = owner(); val active = AtomicReference<SnapshotOwner?>(old)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        storage.entered = CountDownLatch(1); storage.continueWrite = CountDownLatch(1)
        val oldWrite = async(Dispatchers.Default) { repo.observe(repo.ticket()!!, 11, observed(), 22, route) }
        check(storage.entered!!.await(5, TimeUnit.SECONDS))
        val next = owner(); active.set(next)
        val newTicket = repo.ticket()!!
        val newWrite = async(Dispatchers.Default) { repo.observe(newTicket, 11, observed(epoch = b), 33, route.copy(to = "Сибай")) }
        storage.continueWrite!!.countDown()
        assertEquals(SnapshotPersistence.STALE, oldWrite.await())
        assertEquals(SnapshotPersistence.COMMITTED, newWrite.await())
        repo.retire(old)
        val reopened = OfflineTripSnapshotRepository(storage, active, { time })
        assertNull(reopened.load(old, 11)); assertEquals("Сибай", reopened.load(next, 11)!!.route!!.to)
    }
    @Test fun unchangedReadsDoNotExtendPayloadOrStateTimeAndExpireWithoutNetwork() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        var now = time
        val repo = OfflineTripSnapshotRepository(storage, active, { now })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        now = time.copy(wall = 2000, before = 2000, after = 2000)
        assertEquals(SnapshotPersistence.UNCHANGED, repo.observe(repo.ticket()!!, 11, observed(), 22, route))
        assertEquals(time, repo.load(own, 11)!!.payloadStamp)
        now = time.copy(wall = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, before = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS, after = 1000 + OfflineTripSnapshotPolicy.MAX_AGE_MS)
        assertNull(repo.load(own, 11))
    }
    @Test fun totalDiskFailureIsExplicitlyNotDurableRatherThanClaimingColdSafety() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        storage.failBarrier = true; storage.failPayload = true
        assertEquals(SnapshotPersistence.NOT_DURABLE, repo.close(repo.ticket()!!, 11))
        assertNull(repo.load(own, 11))
        // Honest limitation: no disk accepted the terminal fact, so a new process cannot know it.
        storage.failBarrier = false; storage.failPayload = false
        assertNotNull(OfflineTripSnapshotRepository(storage, active, { time }).load(own, 11))
    }
    @Test fun encryptedTerminalStillProtectsColdReadWhenPlainBarrierFailed() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        storage.failBarrier = true
        assertEquals(SnapshotPersistence.COMMITTED, repo.close(repo.ticket()!!, 22, ride = true))
        storage.failBarrier = false
        val reopened = OfflineTripSnapshotRepository(storage, active, { time })
        assertNull(reopened.load(own, 11))
        assertEquals(SnapshotPersistence.STALE, reopened.observe(reopened.ticket()!!, 99, observed(), 22, route))
    }
    @Test fun acceptedConsentWithUnavailableClockDurablyBlocksOldOnboard() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        var now: SnapshotClock? = time
        val repo = OfflineTripSnapshotRepository(storage, active, { now })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        now = null
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, repo.observe(repo.ticket()!!, 11,
            SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true), acknowledgement = true))
        assertNull(OfflineTripSnapshotRepository(storage, active, { time }).load(own, 11))
    }
    @Test fun olderFullResponseCannotReplaceLaterPayloadAndSparseReadDoesNotRefreshRouteAge() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        var now = time
        val repo = OfflineTripSnapshotRepository(storage, active, { now })
        val old = repo.ticket()!!; val recent = repo.ticket()!!
        repo.observe(recent, 11, observed(), 22, route)
        assertEquals(SnapshotPersistence.STALE, repo.observe(old, 11, observed(), 22, route.copy(to = "Сибай")))
        now = time.copy(wall = 2000, before = 2000, after = 2000)
        repo.observe(repo.ticket()!!, 11, observed().copy(phase = "arriving"))
        assertEquals(time, repo.load(own, 11)!!.payloadStamp)
        assertEquals(now, repo.load(own, 11)!!.stateStamp)
    }
    @Test fun unknownFieldsTypesOrImpossibleEpochsNeverRenderAfterReopen() = runBlocking {
        for (damage in listOf<(JSONObject) -> Unit>(
            { it.put("phone", "private") }, { it.put("booking", "11") }, { it.put("phase", "unknown") },
            { it.put("corrected", b) }, { it.put("state_ticket", "1") },
            { it.getJSONObject("route").put("stops", "true") }, { it.remove("payload_stamp") },
        )) {
            val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
            val repo = OfflineTripSnapshotRepository(storage, active, { time })
            repo.observe(repo.ticket()!!, 11, observed(), 22, route)
            val document = JSONObject(storage.read(own.namespace)!!)
            damage(document.getJSONObject("records").getJSONObject("11"))
            storage.write(own.namespace, document.toString())
            assertNull(OfflineTripSnapshotRepository(storage, active, { time }).load(own, 11))
        }
    }
    @Test fun unchangedNewerReadAfterColdReopenStillRejectsAnOlderInFlightPhase() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val first = OfflineTripSnapshotRepository(storage, active, { time })
        first.observe(first.ticket()!!, 11, observed().copy(phase = "arriving"), 22, route)
        val reopened = OfflineTripSnapshotRepository(storage, active, { time })
        val older = reopened.ticket()!!; val newer = reopened.ticket()!!
        assertEquals(SnapshotPersistence.UNCHANGED, reopened.observe(newer, 11, observed().copy(phase = "arriving")))
        assertEquals(SnapshotPersistence.STALE, reopened.observe(older, 11, observed().copy(phase = "departed")))
        assertEquals("arriving", reopened.load(own, 11)!!.state.phase)
        assertEquals(time, reopened.load(own, 11)!!.stateStamp)
    }
    @Test fun freshIdenticalFullDetailsRepairsOnlyTheCommittedCandidateBlockWithoutRenewingTtl() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        storage.failClear = true
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, repo.observe(repo.ticket()!!, 11, observed(), 22, route))
        storage.failClear = false
        val later = time.copy(wall = 2000, before = 2000, after = 2000)
        val reopened = OfflineTripSnapshotRepository(storage, active, { later })
        assertNull(reopened.load(own, 11))
        assertEquals(SnapshotPersistence.COMMITTED, reopened.observe(reopened.ticket()!!, 11, observed(), 22, route))
        val saved = reopened.load(own, 11)!!
        assertEquals(time, saved.payloadStamp); assertEquals(time, saved.stateStamp)
    }
    @Test fun freshOldOnboardFullDetailsCannotClearAnUncommittedCorrectionBlock() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        storage.failPayload = true
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, repo.observe(repo.ticket()!!, 11,
            SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true), acknowledgement = true))
        storage.failPayload = false
        val reopened = OfflineTripSnapshotRepository(storage, active, { time })
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, reopened.observe(reopened.ticket()!!, 11, observed(), 22, route))
        assertNull(reopened.load(own, 11))
    }
    @Test fun firstFullPayloadCanArriveAfterCompatibleNewerSparseStateAndPreservesStateTime() = runBlocking {
        for (fullFirst in listOf(false, true)) {
            val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
            var now = time
            val repo = OfflineTripSnapshotRepository(storage, active, { now })
            val full = repo.ticket()!!; val sparse = repo.ticket()!!
            if (fullFirst) repo.observe(full, 11, observed(), 22, route)
            repo.observe(sparse, 11, observed().copy(phase = "arriving"))
            if (!fullFirst) {
                now = time.copy(wall = 2000, before = 2000, after = 2000)
                assertEquals(SnapshotPersistence.COMMITTED, repo.observe(full, 11, observed(), 22, route))
            }
            val saved = repo.load(own, 11)!!
            assertEquals(route, saved.route); assertEquals("arriving", saved.state.phase)
            assertEquals(time, saved.stateStamp)
        }
    }
    @Test fun firstLateFullPayloadCannotCarryAnOlderEpochOrCorrectedBoarding() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        val oldFull = repo.ticket()!!
        repo.observe(repo.ticket()!!, 11, observed(epoch = b))
        assertEquals(SnapshotPersistence.STALE, repo.observe(oldFull, 11, observed(), 22, route))
        assertNull(repo.load(own, 11))
        val correctedFull = repo.ticket()!!
        repo.observe(repo.ticket()!!, 12, SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true), acknowledgement = true)
        assertEquals(SnapshotPersistence.STALE, repo.observe(correctedFull, 12, observed(), 22, route))
        assertNull(repo.load(own, 12))
    }
    @Test fun changingRouteOrExpiringTtlCannotEraseAFailedCorrectionButANewerBoardingCanRecover() = runBlocking {
        val storage = disk(); val own = owner(); val active = AtomicReference<SnapshotOwner?>(own)
        val repo = OfflineTripSnapshotRepository(storage, active, { time })
        repo.observe(repo.ticket()!!, 11, observed(), 22, route)
        storage.failPayload = true
        repo.observe(repo.ticket()!!, 11, SnapshotState("passenger", "confirmed", receiptSource = a, receiptAccepted = true), acknowledgement = true)
        storage.failPayload = false
        val later = time.copy(wall = 1001 + OfflineTripSnapshotPolicy.MAX_AGE_MS,
            before = 1001 + OfflineTripSnapshotPolicy.MAX_AGE_MS, after = 1001 + OfflineTripSnapshotPolicy.MAX_AGE_MS)
        val reopened = OfflineTripSnapshotRepository(storage, active, { later })
        assertEquals(SnapshotPersistence.DURABLY_BLOCKED, reopened.observe(reopened.ticket()!!, 11, observed(), 22, route.copy(to = "Сибай")))
        assertNull(reopened.load(own, 11))
        assertEquals(SnapshotPersistence.COMMITTED, reopened.observe(reopened.ticket()!!, 11, observed(epoch = b), 22, route))
        assertEquals(snapshotEpoch(b), snapshotEpoch(reopened.load(own, 11)!!.state.maxOnboard))
    }
}
