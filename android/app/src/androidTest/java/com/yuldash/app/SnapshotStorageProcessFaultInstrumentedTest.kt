package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Explicit isolated storage audit only. Root owns process stops/network isolation and evidence capture.
 * This is a controlled commit refusal over REAL EncryptedSharedPreferences, not physical disk failure.
 * Gate grants below are SYNTHETIC contract fixtures, never actual HTTP/auth/UI acceptance evidence.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotStorageProcessFaultInstrumentedTest {
    companion object {
        const val QA_META = "qa_snapshot_storage_20261102_meta"
        const val QA_SECURE = "qa_snapshot_storage_20261102_secure"
        const val QA_ALIAS = "qa_snapshot_storage_20261102_key"
        private const val QA_RUN = "qa_snapshot_run_id"
        private const val EPOCH = "2030-01-01T10:00:00.000001"
        private val FILES = SnapshotPreferenceFiles(meta = QA_META, secure = QA_SECURE)
    }
    private val owner = SnapshotOwner(7, "00000000-0000-4000-8000-000000000007", 1, "https://snapshot-qa.invalid")
    private val route = SnapshotRoute("Synthetic A", "Synthetic B", "2030-01-01T10:00:00", "Synthetic pickup", "Synthetic dropoff", true)
    private val onboard = SnapshotState("passenger", "onboard", onboardAt = EPOCH)
    private val clock = SnapshotClock(3, 1000, 1000, 1000)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(context: Context) = MasterKey.Builder(context, QA_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private fun open(context: Context) = SnapshotPreferencesDisk.open(context, FILES, ::key)
    private fun secure(context: Context): SharedPreferences = EncryptedSharedPreferences.create(context, QA_SECURE, key(context),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun preferenceFiles(context: Context, name: String): List<File> {
        require(name in setOf(QA_META, QA_SECURE) && name.startsWith("qa_snapshot_"))
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        return listOf(File(directory, "$name.xml"), File(directory, "$name.xml.bak"))
    }
    private fun guardNames() {
        check(QA_META.startsWith("qa_snapshot_") && QA_SECURE.startsWith("qa_snapshot_") && QA_ALIAS.startsWith("qa_snapshot_"))
        check(QA_META != SNAPSHOT_META_PREFS && QA_SECURE != SNAPSHOT_SECURE_PREFS)
    }
    private fun proof(request: SnapshotReadTicket): SnapshotNetworkProof = SnapshotReadTrace().apply {
        attempt(); startWire(authorized = true); status(200)
    }.confirmed200(request, owner)!!
    private suspend fun seed(
        repository: OfflineTripSnapshotRepository, gate: SnapshotAuthorityGate,
        bookingId: Int = 11, rideId: Int = 22,
    ): SnapshotPersistence {
        val request = gate.beginRead(bookingId, SnapshotAuthorityKind.DETAILS)!!
        val permit = gate.acceptRead(proof(request), onboard, rideId, route)!!
        return repository.observe(request.ticket, bookingId, onboard, rideId, route, permit = permit)
    }

    @Test fun runOwnedPhase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("snapshotStoragePhase")
        assumeTrue("Explicit isolated QA phases only", phase in setOf("prepare", "cold", "cleanup"))
        val faultCase = args.getString("snapshotStorageCase")
        require(faultCase in setOf("payload", "barrier", "both", "clear"))
        val runId = requireNotNull(args.getString("snapshotStorageRunId"))
        require(UUID.fromString(runId).toString() == runId)
        val context = instrumentation.targetContext.applicationContext
        assertEquals("Use -PstorageAuditRunner=com.yuldash.app.StorageAuditRunner", Application::class.java, context.javaClass)
        guardNames()
        if (phase == "cleanup") {
            require(args.getString("snapshotStorageCleanupOwned") == "true") { "Root must confirm ownership from its resource registration" }
            val meta = context.getSharedPreferences(QA_META, Context.MODE_PRIVATE)
            val savedRun = meta.getString(QA_RUN, null)
            require(savedRun == null || savedRun == runId) { "A different QA run owns these preferences" }
            // Exact registered files and our own alias only. No auth reset, alias enumeration or key-file access.
            context.deleteSharedPreferences(QA_SECURE)
            context.deleteSharedPreferences(QA_META)
            assertTrue(preferenceFiles(context, QA_META).none(File::exists))
            assertTrue(preferenceFiles(context, QA_SECURE).none(File::exists))
            discardOfflineWriteRecovery(meta)
            val keystore = keys()
            if (keystore.containsAlias(QA_ALIAS)) keystore.deleteEntry(QA_ALIAS)
            assertFalse(keystore.containsAlias(QA_ALIAS))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("snapshot_phase", phase); putString("snapshot_case", faultCase)
                putString("snapshot_run_id", runId); putInt("snapshot_pid", Process.myPid())
                putBoolean("snapshot_owned_resources_absent", true)
            })
            return@runBlocking
        }
        if (phase == "prepare") {
            assertTrue("Refuse pre-existing QA files; Root must inspect/clean its registered run first",
                (preferenceFiles(context, QA_META) + preferenceFiles(context, QA_SECURE)).none(File::exists))
            assertFalse("Refuse a pre-existing alias; never replace an unknown key", keys().containsAlias(QA_ALIAS))
            val meta = context.getSharedPreferences(QA_META, Context.MODE_PRIVATE)
            assertTrue(meta.edit().putString(QA_RUN, runId).commit())
            val disk = open(context)
            assertTrue(keys().containsAlias(QA_ALIAS))
            // This early status also identifies resources if a later assertion fails.
            instrumentation.sendStatus(0, Bundle().apply {
                putString("snapshot_run_id", runId); putString("snapshot_phase", "owned_resources_created")
                putInt("snapshot_pid", Process.myPid())
            })
            val active = AtomicReference<SnapshotOwner?>(owner)
            val gate = SnapshotAuthorityGate(active)
            val repository = OfflineTripSnapshotRepository(disk, active, { clock }, gate)
            assertEquals(SnapshotPersistence.COMMITTED, seed(repository, gate))
            assertNotNull(repository.load(owner, 11))
            assertNotNull("Factory reopen must not auto-purge existing data", open(context).read(owner.namespace))
            val fault = QaCommitFault(requireNotNull(faultCase), owner.namespace)
            val wrappedMeta = fault.wrap(meta, encrypted = false)
            val wrappedSecure = fault.wrap(secure(context), encrypted = true)
            val failingDisk = SnapshotPreferencesDisk(wrappedMeta, wrappedSecure)
            val failingRepository = OfflineTripSnapshotRepository(failingDisk, active, { clock }, gate)
            fault.armed = true
            val result = if (faultCase == "clear") {
                val mutation = gate.beginMutation(11)!!
                val correction = SnapshotState("passenger", "confirmed", receiptSource = EPOCH, receiptAccepted = true)
                val permit = gate.acknowledge(mutation, correction)!!
                failingRepository.observe(mutation.ticket, 11, correction, acknowledgement = true, permit = permit)
            } else failingRepository.close(failingRepository.ticket()!!, 11)
            val expected = when (faultCase) {
                "payload", "clear" -> SnapshotPersistence.DURABLY_BLOCKED
                "both" -> SnapshotPersistence.NOT_DURABLE
                else -> SnapshotPersistence.COMMITTED
            }
            assertEquals(expected, result)
            assertNull(failingRepository.load(owner, 11))
            if (faultCase in setOf("payload", "both")) assertTrue(fault.payloadFailures > 0)
            if (faultCase in setOf("barrier", "both")) assertTrue(fault.barrierFailures > 0)
            if (faultCase == "clear") assertTrue(fault.clearFailures > 0)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("snapshot_phase", phase); putString("snapshot_case", faultCase); putString("snapshot_run_id", runId)
                putString("snapshot_persistence", result.name); putString("snapshot_authority_source", "synthetic_contract_fixture_not_http")
                putInt("snapshot_pid", Process.myPid()); putInt("snapshot_payload_failures", fault.payloadFailures)
                putInt("snapshot_barrier_failures", fault.barrierFailures); putInt("snapshot_clear_failures", fault.clearFailures)
            })
            // Preserve only this registered QA pair/key for Root's NEW-PID cold phase; do not self-kill.
        } else {
            val previousPid = args.getString("snapshotStoragePreviousPid")?.toIntOrNull() ?: error("Prepare PID is required")
            require(previousPid > 0); assertNotEquals("Root must actually restart the target process", previousPid, Process.myPid())
            val meta = context.getSharedPreferences(QA_META, Context.MODE_PRIVATE)
            assertEquals(runId, meta.getString(QA_RUN, null)); assertTrue(keys().containsAlias(QA_ALIAS))
            val unavailable = SnapshotPreferencesDisk.open(context, FILES) {
                throw SecurityException("synthetic QA key factory unavailable")
            }
            assertNull(unavailable.read(owner.namespace))
            assertFalse(unavailable.write(owner.namespace, "synthetic rejected plaintext fallback"))
            assertFalse(meta.contains(owner.namespace))
            val disk = open(context)
            val active = AtomicReference<SnapshotOwner?>(owner)
            val gate = SnapshotAuthorityGate(active)
            val repository = OfflineTripSnapshotRepository(disk, active, { clock }, gate)
            assertNull("A genuinely new process cannot authorize surviving bytes", repository.load(owner, 11))
            if (faultCase == "both") assertEquals("onboard", JSONObject(disk.read(owner.namespace)!!)
                .getJSONObject("records").getJSONObject("11").getString("status"))
            val late = gate.beginRead(11, SnapshotAuthorityKind.DETAILS)!!
            val terminal = gate.beginRead(11, SnapshotAuthorityKind.ROLE)!!
            assertNull(gate.acceptRead(proof(terminal), SnapshotState("passenger", "done")))
            assertNull(gate.acceptRead(proof(late), onboard, 22, route))
            assertNull(repository.load(owner, 11))
            // A separate live booking can recover ONLY through its fresh current details projection.
            val fresh = gate.beginRead(12, SnapshotAuthorityKind.DETAILS)!!
            val fingerprint = snapshotHash("current synthetic projection for booking twelve")
            val permit = gate.acceptRead(proof(fresh), onboard, 33, route,
                contactUnlocked = true, privateFingerprint = fingerprint)!!
            assertEquals(SnapshotPersistence.COMMITTED, repository.observe(fresh.ticket, 12, onboard, 33, route, permit = permit))
            assertNotNull(repository.load(owner, 12))
            assertTrue(gate.allowsPrivate(owner, 12, fingerprint))
            assertFalse(gate.allowsPrivate(owner, 12, snapshotHash("old private projection")))
            assertFalse(gate.allowsPrivate(owner, 11, fingerprint))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("snapshot_phase", phase); putString("snapshot_case", faultCase); putString("snapshot_run_id", runId)
                putInt("snapshot_pid", Process.myPid()); putInt("snapshot_previous_pid", previousPid)
                putString("snapshot_authority_source", "synthetic_contract_fixture_not_http")
                putBoolean("snapshot_cold_gate_denied", true); putBoolean("snapshot_terminal_rejected", true)
                putBoolean("snapshot_fresh_details_loaded", true)
            })
        }
    }

    /** Reject only the candidate operation; allow restoring the previous value through OfflineWrites. */
    private class QaCommitFault(private val kind: String, private val namespace: String) {
        var armed = false
        var payloadFailures = 0
        var barrierFailures = 0
        var clearFailures = 0
        fun wrap(real: SharedPreferences, encrypted: Boolean): SharedPreferences = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                val changes = mutableMapOf<String, String?>()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        changes[requireNotNull(key)] = value; editor.putString(key, value); return this
                    }
                    override fun commit(): Boolean {
                        if (armed) {
                            if (encrypted && kind in setOf("payload", "both")) {
                                val value = changes[namespace]?.let(::JSONObject)
                                if (value != null && value.getJSONArray("closed_bookings").length() > 0) { payloadFailures++; return false }
                            }
                            if (!encrypted) {
                                val values = changes[SNAPSHOT_BARRIERS_KEY]?.let(::JSONObject)
                                if (values != null && kind in setOf("barrier", "both") &&
                                    values.keys().asSequence().any { values.getString(it) == "closed" }) { barrierFailures++; return false }
                                if (values != null && kind == "clear" && values.length() == 0) { clearFailures++; return false }
                            }
                        }
                        return editor.commit()
                    }
                }
            }
        }
    }
}
