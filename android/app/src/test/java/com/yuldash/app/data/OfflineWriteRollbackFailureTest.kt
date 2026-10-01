package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Inject two distinct faults through SharedPreferences; the production writer/parser/HTTP run.
 * MemoryDiskPreferences.restarted() models a fresh disk read, not Android process death.
 * Neither this model nor Robolectric proves a physical storage/Keystore failure on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OfflineWriteRollbackFailureTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val sent = ConcurrentLinkedQueue<Pair<String, String>>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    sent.add(request.path.orEmpty() to request.body.readUtf8())
                    return MockResponse().setResponseCode(200).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.init(context)
        ApiClient.saveToken("QA-rollback-owner")
    }

    @After fun cleanup() {
        // Detach fault stores before logout, so cleanup cannot consume or hide the tested fault.
        Outbox.initStores(MemoryDiskPreferences(), null)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private enum class RollbackFault {
        EDIT, PUT_STRING, DURABLE_CANDIDATE_AND_FAILED_ROLLBACK,
        PERSISTENT_EDIT, PERSISTENT_DURABLE_ROLLBACK, NONE,
    }

    /**
     * First tracked commit changes memory and reports false. Usually disk retains A.
     * The durable variant writes B to disk but reports false, as an interrupted commit may do.
     * The next rollback throws before changing memory (EDIT/PUT_STRING), or changes memory
     * back to A but leaves B on disk and reports false (durable variant). Later writes work.
     */
    private class TwoFaultPreferences(
        val underlying: MemoryDiskPreferences,
        private val trackedKey: String,
        private val rollbackFault: RollbackFault,
    ) : SharedPreferences by underlying {
        private enum class Stage { CANDIDATE, ROLLBACK, RECOVERED }
        private var stage = Stage.CANDIDATE
        var armed = false
        var persistentFaultEnabled = true
        var candidateFailures = 0
            private set
        var rollbackFailures = 0
            private set

        override fun edit(): SharedPreferences.Editor {
            if (armed && stage == Stage.ROLLBACK && (rollbackFault == RollbackFault.EDIT ||
                    (rollbackFault == RollbackFault.PERSISTENT_EDIT && persistentFaultEnabled))) {
                rollbackFailures++
                if (rollbackFault == RollbackFault.EDIT) stage = Stage.RECOVERED
                error("synthetic rollback edit failure before memory restoration")
            }
            val editor = underlying.edit()
            return object : SharedPreferences.Editor by editor {
                private var writesTrackedKey = false

                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    if (armed && stage == Stage.ROLLBACK && key == trackedKey &&
                        rollbackFault == RollbackFault.PUT_STRING) {
                        rollbackFailures++
                        stage = Stage.RECOVERED
                        error("synthetic rollback encoding failure before memory restoration")
                    }
                    editor.putString(key, value)
                    if (key == trackedKey) writesTrackedKey = true
                    return this
                }

                override fun commit(): Boolean {
                    if (!armed || !writesTrackedKey || stage == Stage.RECOVERED) return editor.commit()
                    if (stage == Stage.CANDIDATE) {
                        candidateFailures++
                        stage = Stage.ROLLBACK
                        val oldFault = underlying.failWriteOf
                        underlying.failWriteOf = if (rollbackFault in setOf(
                                RollbackFault.DURABLE_CANDIDATE_AND_FAILED_ROLLBACK,
                                RollbackFault.PERSISTENT_DURABLE_ROLLBACK))
                            null else trackedKey
                        try { editor.commit() } finally { underlying.failWriteOf = oldFault }
                        return false
                    }
                    val persistentDiskFailure = rollbackFault == RollbackFault.PERSISTENT_DURABLE_ROLLBACK &&
                        persistentFaultEnabled
                    if (!persistentDiskFailure) stage = Stage.RECOVERED
                    if (rollbackFault == RollbackFault.DURABLE_CANDIDATE_AND_FAILED_ROLLBACK || persistentDiskFailure) {
                        rollbackFailures++
                        val oldFault = underlying.failWriteOf
                        val oldRemovalFault = underlying.failRemovalOf
                        underlying.failWriteOf = trackedKey
                        // Restoring previous=null removes the key; failWriteOf alone would allow
                        // that removal to reach disk and give a misleading successful rollback.
                        underlying.failRemovalOf = trackedKey
                        try { editor.commit() } finally {
                            underlying.failWriteOf = oldFault
                            underlying.failRemovalOf = oldRemovalFault
                        }
                        return false
                    }
                    return editor.commit()
                }
            }
        }
    }

    private fun rejectedQueueAppendDoesNotBecomeSendable(
        fault: RollbackFault, existingConfirmedMessage: Boolean = true,
    ) = runBlocking {
        val disk = MemoryDiskPreferences()
        Outbox.initStores(disk, null)
        if (existingConfirmedMessage) assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "confirmed A")))
        val confirmedRaw = disk.restarted().getString("queue", null)
        if (existingConfirmedMessage) assertNotNull(confirmedRaw) else assertNull(confirmedRaw)
        val faulty = TwoFaultPreferences(disk, "queue", fault)
        Outbox.initStores(faulty, null)
        val version = Outbox.version.value
        faulty.armed = true

        val accepted = Outbox.enqueue(context, Outbox.newMessage(8, "rejected B"))
        val versionAfterRejection = Outbox.version.value
        val rejectedCount = Outbox.count(context, 8)
        // Capture the point at which a real process could stop, before HTTP changes disk again.
        val restarted = disk.restarted()
        val diskRawAfterRejection = restarted.getString("queue", null)
        Outbox.initStores(faulty, null)
        val rejectedCountAfterReopen = Outbox.count(context, 8)
        Outbox.flush(context)
        val delivered = sent.toList()
        Outbox.initStores(restarted, null)
        val confirmedAfterRestart = Outbox.count(context, 7)
        val rejectedAfterRestart = Outbox.count(context, 8)
        println("QA020 queue fault=$fault existingA=$existingConfirmedMessage accepted=$accepted versionStable=${version == versionAfterRejection} " +
            "rejectedCount=$rejectedCount rejectedAfterReopen=$rejectedCountAfterReopen " +
            "requests=${delivered.size} confirmedAfterDiskRead=$confirmedAfterRestart rejectedAfterDiskRead=$rejectedAfterRestart")

        assertFalse("failed persistence must be reported to the caller", accepted)
        assertEquals(1, faulty.candidateFailures)
        assertEquals(if (fault == RollbackFault.NONE) 0 else 1, faulty.rollbackFailures)
        assertEquals("rejected append must not notify UI as a successful queue change", version, versionAfterRejection)
        assertEquals("rejected B must not be included in the visible queue", 0, rejectedCount)
        assertEquals("reopening the same store must not make rejected B sendable", 0, rejectedCountAfterReopen)
        assertEquals("disk must retain exactly the confirmed snapshot A", confirmedRaw, diskRawAfterRejection)
        assertEquals(if (existingConfirmedMessage) 1 else 0, confirmedAfterRestart)
        assertEquals("a new disk reader must not resurrect rejected B", 0, rejectedAfterRestart)
        assertTrue("flush must never send rejected B", delivered.all { (path, body) ->
            path == "/bookings/7/messages" && JSONObject(body).getString("text") == "confirmed A"
        })
        assertTrue("the one confirmed message must not be duplicated", delivered.size <= 1)
        if (!existingConfirmedMessage) assertTrue("an unsuccessful first append must send nothing", delivered.isEmpty())
        if (fault == RollbackFault.NONE) assertEquals("healthy rollback preserves a sendable A", 1, delivered.size)
    }

    @Test fun rollbackEditFailureDoesNotExposeOrSendRejectedMessage() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.EDIT)

    @Test fun rollbackEncodingFailureDoesNotExposeOrSendRejectedMessage() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.PUT_STRING)

    @Test fun ambiguousDurableCandidateAndFailedRollbackDoNotResurrectRejectedMessage() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.DURABLE_CANDIDATE_AND_FAILED_ROLLBACK)

    @Test fun ordinaryFailedCommitWithSuccessfulRollbackPreservesAndSendsConfirmedMessage() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.NONE)

    @Test fun firstAppendRollbackEditFailureKeepsAbsentQueueAbsent() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.EDIT, existingConfirmedMessage = false)

    @Test fun firstAppendRollbackEncodingFailureKeepsAbsentQueueAbsent() =
        rejectedQueueAppendDoesNotBecomeSendable(RollbackFault.PUT_STRING, existingConfirmedMessage = false)

    @Test fun persistentRollbackFailureBlocksNewWritesAndHttpUntilConfirmedRecovery() = runBlocking {
        val disk = MemoryDiskPreferences()
        Outbox.initStores(disk, null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "confirmed A")))
        val confirmedRaw = disk.restarted().getString("queue", null)
        val faulty = TwoFaultPreferences(disk, "queue", RollbackFault.PERSISTENT_EDIT)
        Outbox.initStores(faulty, null)
        faulty.armed = true
        val version = Outbox.version.value
        val rejected = Outbox.enqueue(context, Outbox.newMessage(8, "rejected B"))
        val rejectedCount = Outbox.count(context, 8)
        val dirtyMemoryBeforeBlockedWrite = disk.getString("queue", null)
        Outbox.initStores(faulty, null)
        val rejectedCountAfterReopen = Outbox.count(context, 8)
        val flushed = Outbox.flush(context)
        val anotherAccepted = Outbox.enqueue(context, Outbox.newMessage(9, "not accepted while uncertain"))
        val otherCount = Outbox.count(context, 9)
        println("QA020 persistent EDIT rejected=$rejected countB=$rejectedCount reopenB=$rejectedCountAfterReopen " +
            "flushed=$flushed anotherAccepted=$anotherAccepted countOther=$otherCount requests=${server.requestCount}")

        assertFalse(rejected)
        assertEquals(0, rejectedCount)
        assertEquals(0, rejectedCountAfterReopen)
        assertFalse("uncertain persistence must prevent HTTP", flushed)
        assertFalse("do not replace an unresolved confirmed snapshot with a new action", anotherAccepted)
        assertEquals(0, otherCount)
        assertEquals(version, Outbox.version.value)
        assertEquals(0, server.requestCount)
        assertEquals("a blocked write must not mutate the underlying uncertain memory", dirtyMemoryBeforeBlockedWrite,
            disk.getString("queue", null))
        assertEquals(confirmedRaw, disk.restarted().getString("queue", null))
        assertEquals(1, faulty.candidateFailures)
        assertTrue("the persistent rollback fault must actually be reached", faulty.rollbackFailures > 0)

        faulty.persistentFaultEnabled = false
        Outbox.initStores(faulty, null)
        assertEquals(1, Outbox.count(context, 7))
        assertEquals(0, Outbox.count(context, 8))
        assertEquals(confirmedRaw, disk.restarted().getString("queue", null))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(9, "accepted after recovery")))
        assertTrue(Outbox.flush(context))
        assertEquals(listOf("/bookings/7/messages", "/bookings/9/messages"), sent.map { it.first })
        assertEquals(listOf("confirmed A", "accepted after recovery"), sent.map { JSONObject(it.second).getString("text") })
    }

    @Test fun persistentAmbiguousDiskFailureBlocksCurrentProcessButColdRestartRemainsOpen() = runBlocking {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        Outbox.initStores(plain, secure)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "confirmed A")))
        val confirmedRaw = secure.restarted().getString("queue", null)
        val faulty = TwoFaultPreferences(secure, "queue", RollbackFault.PERSISTENT_DURABLE_ROLLBACK)
        Outbox.initStores(plain, faulty)
        faulty.armed = true
        val version = Outbox.version.value
        val rejected = Outbox.enqueue(context, Outbox.newMessage(8, "rejected B"))
        // This deliberately records an OPEN cold-restart criterion under continuing disk failure.
        // Passing the remaining current-process checks must not be reported as durable recovery.
        val unresolvedDiskSnapshot = JSONArray(requireNotNull(secure.restarted().getString("queue", null)))
        val rejectedCount = Outbox.count(context, 8)
        val flushed = Outbox.flush(context)
        val anotherAccepted = Outbox.enqueue(context, Outbox.newMessage(9, "blocked C"))
        // A new SharedPreferences proxy is not a real EncryptedSharedPreferences instance.
        // Its purpose is to ensure guard ownership follows the stable plain scope, not proxy identity.
        val anotherSecureWrapper = object : SharedPreferences by faulty {}
        Outbox.initStores(plain, anotherSecureWrapper)
        val countAfterNewWrapper = Outbox.count(context, 8)
        val flushedAfterNewWrapper = Outbox.flush(context)
        println("QA020 persistent DURABLE rejected=$rejected countB=$rejectedCount newWrapperB=$countAfterNewWrapper " +
            "flushed=$flushed flushedNewWrapper=$flushedAfterNewWrapper anotherAccepted=$anotherAccepted " +
            "requests=${server.requestCount} coldRestartCriterion=OPEN diskContainsRejectedB=true")

        assertFalse(rejected)
        assertEquals(0, rejectedCount)
        assertFalse(flushed)
        assertFalse(anotherAccepted)
        assertEquals(0, countAfterNewWrapper)
        assertFalse(flushedAfterNewWrapper)
        assertEquals(version, Outbox.version.value)
        assertEquals(0, server.requestCount)
        assertTrue("capture the unresolved durable B instead of claiming it was recovered",
            (0 until unresolvedDiskSnapshot.length()).any {
                unresolvedDiskSnapshot.getJSONObject(it).getInt("booking_id") == 8
            })
        assertTrue(faulty.rollbackFailures > 0)

        faulty.persistentFaultEnabled = false
        Outbox.initStores(plain, anotherSecureWrapper)
        assertEquals(confirmedRaw, secure.restarted().getString("queue", null))
        assertEquals(1, Outbox.count(context, 7))
        assertEquals(0, Outbox.count(context, 8))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(9, "accepted after recovery")))
        assertTrue(Outbox.flush(context))
        assertEquals(listOf("/bookings/7/messages", "/bookings/9/messages"), sent.map { it.first })
    }

    private fun passport(code: String, id: Int = 7) = TripPass.fromJson(
        JSONObject().put("booking_id", id).put("boarding_code", code).put("driver_name", "Synthetic driver"),
    )

    private fun rejectedPassportUpdateKeepsConfirmedCode(fault: RollbackFault) {
        val disk = MemoryDiskPreferences()
        TripPassStore.initStores(disk, null)
        assertTrue(TripPassStore.save(context, passport("1111")))
        assertTrue(TripPassStore.save(context, passport("3333", 9)))
        val confirmedRaw = disk.restarted().getString("pass_7", null)
        val faulty = TwoFaultPreferences(disk, "pass_7", fault)
        TripPassStore.initStores(faulty, null)
        faulty.armed = true

        val accepted = TripPassStore.save(context, passport("2222"))
        val currentCode = TripPassStore.load(context, 7)?.boardingCode
        TripPassStore.initStores(faulty, null)
        val reopenedCode = TripPassStore.load(context, 7)?.boardingCode
        val restarted = disk.restarted()
        val diskRawAfterRejection = restarted.getString("pass_7", null)
        TripPassStore.initStores(restarted, null)
        val codeAfterRestart = TripPassStore.load(context, 7)?.boardingCode
        val unrelatedCodeAfterRestart = TripPassStore.load(context, 9)?.boardingCode
        println("QA020 passport fault=$fault accepted=$accepted currentCode=$currentCode " +
            "reopenedCode=$reopenedCode codeAfterDiskRead=$codeAfterRestart")

        assertFalse(accepted)
        assertEquals(1, faulty.candidateFailures)
        assertEquals(if (fault == RollbackFault.NONE) 0 else 1, faulty.rollbackFailures)
        assertEquals("failed save must not expose the unconfirmed boarding code", "1111", currentCode)
        assertEquals("reopen must retain the confirmed boarding code", "1111", reopenedCode)
        assertEquals(confirmedRaw, diskRawAfterRejection)
        assertEquals("1111", codeAfterRestart)
        assertEquals("3333", unrelatedCodeAfterRestart)
        assertEquals("passport save must not send an HTTP request", 0, server.requestCount)
    }

    @Test fun rollbackEditFailureKeepsConfirmedPassportCode() =
        rejectedPassportUpdateKeepsConfirmedCode(RollbackFault.EDIT)

    @Test fun rollbackEncodingFailureKeepsConfirmedPassportCode() =
        rejectedPassportUpdateKeepsConfirmedCode(RollbackFault.PUT_STRING)

    @Test fun ordinaryFailedCommitWithSuccessfulRollbackKeepsConfirmedPassportCode() =
        rejectedPassportUpdateKeepsConfirmedCode(RollbackFault.NONE)

    @Test fun confirmedLogoutResetDoesNotResurrectThePreviousQueueFromRamGuard() = runBlocking {
        val disk = MemoryDiskPreferences()
        Outbox.initStores(disk, null)
        TripPassStore.initStores(MemoryDiskPreferences(), null)
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "old account A")))
        val faulty = TwoFaultPreferences(disk, "queue", RollbackFault.PERSISTENT_EDIT)
        Outbox.initStores(faulty, null)
        faulty.armed = true
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(8, "old rejected B")))
        ApiClient.logout()
        assertFalse(ApiClient.isLoggedIn())
        assertEquals("logout quarantine must take priority over the old confirmed guard", 0, Outbox.count(context, 7))
        assertEquals(0, Outbox.count(context, 8))
        assertFalse(Outbox.enqueue(context, Outbox.newMessage(9, "must wait for reset")))
        assertFalse(Outbox.flush(context))
        assertTrue(sent.none { it.first.endsWith("/messages") })

        faulty.persistentFaultEnabled = false
        // saveToken follows the real ensureResetCommitted path before accepting a new login.
        ApiClient.saveToken("QA-new-queue-owner")
        Outbox.initStores(faulty, null)
        assertEquals(0, Outbox.count(context, 7))
        assertEquals(0, Outbox.count(context, 8))
        assertNull(disk.restarted().getString("queue", null))
        assertTrue(Outbox.enqueue(context, Outbox.newMessage(9, "new account C")))
        assertTrue(Outbox.flush(context))
        assertEquals(listOf("/bookings/9/messages"), sent.filter { it.first.endsWith("/messages") }.map { it.first })
    }

    @Test fun confirmedLogoutResetDoesNotResurrectPreviousPassportsFromRamGuard() {
        val disk = MemoryDiskPreferences()
        TripPassStore.initStores(disk, null)
        Outbox.initStores(MemoryDiskPreferences(), null)
        assertTrue(TripPassStore.save(context, passport("1111")))
        val faulty = TwoFaultPreferences(disk, "pass_7", RollbackFault.PERSISTENT_EDIT)
        TripPassStore.initStores(faulty, null)
        faulty.armed = true
        assertFalse(TripPassStore.save(context, passport("2222")))
        ApiClient.logout()
        assertFalse(ApiClient.isLoggedIn())
        assertNull("logout quarantine must override the old confirmed passport guard", TripPassStore.load(context, 7))
        assertFalse(TripPassStore.save(context, passport("3333", 9)))

        faulty.persistentFaultEnabled = false
        ApiClient.saveToken("QA-new-passport-owner")
        TripPassStore.initStores(faulty, null)
        assertNull(TripPassStore.load(context, 7))
        assertNull(disk.restarted().getString("pass_7", null))
        assertTrue(TripPassStore.save(context, passport("3333", 9)))
        TripPassStore.initStores(disk.restarted(), null)
        assertNull(TripPassStore.load(context, 7))
        assertEquals("3333", TripPassStore.load(context, 9)?.boardingCode)
    }

    @Test fun confirmedPassportDeletionCannotBeUndoneByPendingWriteRecovery() {
        val plain = MemoryDiskPreferences()
        val secure = MemoryDiskPreferences()
        TripPassStore.initStores(plain, secure)
        assertTrue(TripPassStore.save(context, passport("1111")))
        // This fault refuses rollback putString commits but lets a deletion remove() commit.
        // Thus CLEARED can occur while an old pending-write snapshot still exists in RAM.
        val faulty = TwoFaultPreferences(secure, "pass_7", RollbackFault.PERSISTENT_DURABLE_ROLLBACK)
        TripPassStore.initStores(plain, faulty)
        faulty.armed = true
        assertFalse(TripPassStore.save(context, passport("2222")))
        val removal = TripPassStore.requestRemoval(context, 7)
        assertTrue("the healthy plain store must persist the deletion barrier",
            removal == TripPassStore.RemovalResult.DEFERRED || removal == TripPassStore.RemovalResult.CLEARED)
        assertNull("a durable deletion barrier must hide every snapshot", TripPassStore.load(context, 7))
        if (removal == TripPassStore.RemovalResult.CLEARED) {
            assertNull(secure.restarted().getString("pass_7", null))
            assertNull(plain.restarted().getString("pass_7", null))
        }
        faulty.persistentFaultEnabled = false
        if (removal == TripPassStore.RemovalResult.DEFERRED) {
            TripPassStore.initStores(plain, faulty)
            assertEquals(TripPassStore.RemovalResult.CLEARED, TripPassStore.requestRemoval(context, 7))
        }
        // With a prior CLEARED result, another passport write must not restore the old guard
        // before initStores has a chance to run its deletion reconciliation again.
        assertTrue(TripPassStore.save(context, passport("3333", 9)))
        assertNull("a new passport write must not resurrect a physically cleared old passport",
            secure.restarted().getString("pass_7", null))
        TripPassStore.initStores(plain, faulty)
        assertNull(TripPassStore.load(context, 7))
        assertNull("CLEARED means physical deletion, not only a hidden resurrected guard", secure.restarted().getString("pass_7", null))
        assertNull(plain.restarted().getString("pass_7", null))
        TripPassStore.initStores(plain.restarted(), secure.restarted())
        assertNull(TripPassStore.load(context, 7))
        assertEquals("3333", TripPassStore.load(context, 9)?.boardingCode)
    }
}
