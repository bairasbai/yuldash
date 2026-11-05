package com.yuldash.app.data

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual adapter and OfflineWrites path with synthetic preferences, not physical disk failure. */
class SnapshotPreferencesDiskTest {
    private val pairs = mutableListOf<Pair<MemoryDiskPreferences, MemoryDiskPreferences>>()
    private val wrappedRecoveryScopes = mutableListOf<SharedPreferences>()
    private fun stores(): Pair<MemoryDiskPreferences, MemoryDiskPreferences> =
        (MemoryDiskPreferences() to MemoryDiskPreferences()).also(pairs::add)
    private val first = snapshotHash("first synthetic namespace")
    private val second = snapshotHash("second synthetic namespace")
    private val third = snapshotHash("third synthetic namespace")
    private fun key(namespace: String) = namespace + ":" + snapshotHash("synthetic barrier")

    @After fun cleanup() {
        pairs.forEach { (meta, secure) ->
            assertTrue(meta.edit().clear().commit()); assertTrue(secure.edit().clear().commit())
            // Confirmed erasure of these synthetic stores only; never an initialization shortcut.
            discardOfflineWriteRecovery(meta)
        }
        wrappedRecoveryScopes.forEach { discardOfflineWriteRecovery(it) }
    }
    @Test fun payloadExistsOnlyInSecureStoreAndClosedOrRetiredCannotBeClearedAfterReopen() {
        val (meta, secure) = stores()
        val disk = SnapshotPreferencesDisk(meta, secure)
        assertTrue(disk.write(first, "synthetic payload"))
        assertNull(meta.getString(first, null)); assertEquals("synthetic payload", secure.getString(first, null))
        assertTrue(disk.setBarrier(key(first), "closed"))
        assertTrue(disk.setBarrier(key(second), "retired"))
        val reopened = SnapshotPreferencesDisk(meta, object : SharedPreferences by secure {})
        assertFalse(reopened.setBarrier(key(first), null))
        assertFalse(reopened.setBarrier(key(first), "blocked"))
        assertFalse(reopened.setBarrier(key(second), null))
        assertEquals("closed", reopened.barrier(key(first)))
        assertEquals("retired", reopened.barrier(key(second)))
        assertFalse(meta.getString(SNAPSHOT_BARRIERS_KEY, null)!!.contains("synthetic payload"))
    }
    @Test fun unavailableEncryptionNeverUsesPlaintextPayloadButCanPersistAConservativeBarrier() {
        val (meta, _) = stores()
        val disk = SnapshotPreferencesDisk(meta, null)
        assertFalse(disk.write(first, "synthetic private payload"))
        assertNull(disk.read(first)); assertFalse(disk.retire(first)); assertFalse(disk.purgeExcept(null))
        assertTrue(disk.setBarrier(key(first), "closed"))
        assertEquals("closed", disk.barrier(key(first)))
        assertNull(meta.getString(first, null))
    }
    @Test fun falseCommitWithDirtyMemoryCannotExposeCandidateAndRecoversThroughSameMeta() {
        val (meta, secure) = stores()
        val observedMemory = mutableListOf<String?>()
        val recording = object : SharedPreferences by secure {
            override fun edit(): SharedPreferences.Editor {
                val editor = secure.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun commit(): Boolean = editor.commit().also { observedMemory += secure.getString(first, null) }
                }
            }
        }
        val disk = SnapshotPreferencesDisk(meta, recording)
        assertTrue(disk.write(first, "confirmed A"))
        secure.failWriteOf = first
        assertFalse(disk.write(first, "rejected B"))
        assertTrue("the failing candidate commit must actually change memory before rollback", "rejected B" in observedMemory)
        assertEquals("confirmed A", disk.read(first))
        assertEquals("confirmed A", SnapshotPreferencesDisk(meta, object : SharedPreferences by secure {}).read(first))
        assertFalse(disk.write(first, "blocked C"))
        secure.failWriteOf = null
        assertTrue(disk.write(first, "confirmed C"))
        assertEquals("confirmed C", disk.read(first))
    }
    @Test fun falseCommitCanPersistCandidateAndNewSecureWrapperMustKeepTheOriginalRecoveryScope() {
        val (meta, secure) = stores()
        val fault = PreferenceCommitFault().apply {
            reject = { it[first] == "rejected B" }; persistRejected = true; refuseRollback = true
        }
        var wrapped: SharedPreferences = fault.wrap(secure)
        val disk = SnapshotPreferencesDisk(meta, wrapped)
        assertTrue(disk.write(first, "confirmed A"))
        fault.armed = true
        assertFalse(disk.write(first, "rejected B"))
        val actualDiskReopen = secure.restarted()
        assertEquals("rejected B", actualDiskReopen.getString(first, null))
        wrapped = fault.wrap(actualDiskReopen)
        val reopened = SnapshotPreferencesDisk(meta, wrapped)
        assertEquals("confirmed A", reopened.read(first))
        assertFalse(reopened.write(first, "blocked C"))
        fault.refuseRollback = false
        assertTrue(reopened.write(first, "confirmed C"))
        assertEquals("confirmed C", reopened.read(first))
    }
    @Test fun failedClearKeepsCommittedCandidateBarrierAcrossANewAdapter() {
        val (meta, secure) = stores()
        val fault = PreferenceCommitFault().apply {
            reject = { it[SNAPSHOT_BARRIERS_KEY]?.let(::JSONObject)?.length() == 0 }
        }
        val stableMeta = fault.wrap(meta)
        wrappedRecoveryScopes += stableMeta
        val disk = SnapshotPreferencesDisk(stableMeta, secure)
        val barrier = "blocked:" + snapshotHash("committed candidate")
        assertTrue(disk.setBarrier(key(first), barrier)); assertTrue(disk.write(first, "confirmed candidate"))
        fault.armed = true
        assertFalse(disk.setBarrier(key(first), null))
        assertEquals(barrier, SnapshotPreferencesDisk(stableMeta, object : SharedPreferences by secure {}).barrier(key(first)))
        fault.armed = false
        assertTrue(disk.setBarrier(key(first), null)); assertNull(disk.barrier(key(first)))
        // The stable recovery scope is the wrapper passed to the adapter, not the backing preference object.
        assertTrue(stableMeta.edit().clear().commit()); discardOfflineWriteRecovery(stableMeta)
    }
    @Test fun partialPurgeReportsFailureAndKeepsGuardsUntilCiphertextRemovalIsConfirmed() {
        val (meta, secure) = stores()
        val fault = PreferenceCommitFault().apply { reject = { second in it && it[second] == null } }
        val disk = SnapshotPreferencesDisk(meta, fault.wrap(secure))
        for (namespace in listOf(first, second, third)) {
            assertTrue(disk.write(namespace, namespace)); assertTrue(disk.setBarrier(key(namespace), "closed"))
        }
        assertTrue(secure.edit().putString("library_metadata", "keep").commit())
        fault.armed = true
        assertFalse(disk.purgeExcept(third))
        assertNotNull(disk.barrier(key(first))); assertNotNull(disk.barrier(key(second)))
        assertEquals(third, disk.read(third))
        fault.armed = false
        assertTrue(disk.purgeExcept(third))
        assertNull(disk.read(first)); assertNull(disk.read(second)); assertEquals(third, disk.read(third))
        assertNull(disk.barrier(key(first))); assertNull(disk.barrier(key(second)))
        assertEquals("closed", disk.barrier(key(third)))
        assertEquals("keep", secure.getString("library_metadata", null))
    }
    @Test fun corruptBarrierOrInvalidNamespaceIsRejectedWithoutResettingItsGuardMap() {
        val (meta, secure) = stores()
        val disk = SnapshotPreferencesDisk(meta, secure)
        assertTrue(disk.write(first, "confirmed"))
        assertTrue(meta.edit().putString(SNAPSHOT_BARRIERS_KEY, "{\"unexpected\":\"closed\"}").commit())
        assertTrue(runCatching { disk.barrier(key(first)) }.isFailure)
        assertTrue(runCatching { disk.write("../foreign", "rejected") }.isFailure)
        assertEquals("confirmed", secure.getString(first, null))
        assertFalse(disk.purgeExcept(null)) // malformed guard map is not silently reset.
        assertEquals("{\"unexpected\":\"closed\"}", meta.getString(SNAPSHOT_BARRIERS_KEY, null))
    }
}

/** A false commit can leave real backing data changed; rollback faults are independent. */
private class PreferenceCommitFault {
    var armed = false
    var persistRejected = false
    var refuseRollback = false
    var reject: (Map<String, String?>) -> Boolean = { false }
    private var rejected = false
    fun wrap(real: SharedPreferences): SharedPreferences = object : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            val changes = mutableMapOf<String, String?>()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    changes[requireNotNull(key)] = value; editor.putString(key, value); return this
                }
                override fun commit(): Boolean {
                    if (armed && rejected && refuseRollback) return false
                    if (armed && reject(changes)) {
                        rejected = true
                        if (persistRejected) check(editor.commit())
                        return false
                    }
                    return editor.commit()
                }
            }
        }
    }
}
