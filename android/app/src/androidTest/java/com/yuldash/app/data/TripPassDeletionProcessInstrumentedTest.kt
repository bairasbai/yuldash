package com.yuldash.app.data

import android.os.Bundle
import android.os.Process
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in QA-only preferences: durable deletion across process death and Keystore recovery. */
@RunWith(AndroidJUnit4::class)
class TripPassDeletionProcessInstrumentedTest {
    @Test fun runPhase() {
        val phase = InstrumentationRegistry.getArguments().getString("deletionPhase")
        assumeTrue("Explicit isolated deletion audit only", phase in listOf("seed", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = context.getSharedPreferences("qa_deletion_probe", 0)
        val plain = context.getSharedPreferences("qa_deletion_pass_plain", 0)
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val secure = EncryptedSharedPreferences.create(
            context, "qa_deletion_pass_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        fun pass(id: Int, code: String) = TripPass.fromJson(
            JSONObject().put("booking_id", id).put("boarding_code", code),
        )

        if (phase == "seed") {
            // Only these three explicitly named QA stores are reset; application stores are untouched.
            listOf(plain, secure).forEach { assertTrue(it.edit().clear().commit()) }
            assertTrue(probe.edit().clear().putInt("seed_pid", Process.myPid()).commit())
            TripPassStore.initStores(plain, secure)
            assertTrue(TripPassStore.save(context, pass(7, "finished")))
            assertTrue(TripPassStore.save(context, pass(99, "unrelated")))
            assertTrue(secure.contains("pass_7"))
            assertTrue(secure.contains("pass_99"))

            // Keep the real secure store intact, but make it unavailable to the application store.
            TripPassStore.initStores(plain, null)
            assertEquals(TripPassStore.RemovalResult.DEFERRED, TripPassStore.requestRemoval(context, 7))
            assertNull(TripPassStore.load(context, 7))
            assertTrue("Physical cleanup must still be pending", secure.contains("pass_7"))
            val deleted = JSONArray(requireNotNull(plain.getString(TripPassDeletion.KEY, null)))
            assertEquals(1, deleted.length())
            assertEquals(7, deleted.getInt(0))
        } else {
            val seedPid = probe.getInt("seed_pid", -1)
            assertTrue("Run seed first", seedPid > 0)
            assertNotEquals("Verify must run in a new process", seedPid, Process.myPid())
            assertTrue("Seed must have left the old secure copy", secure.contains("pass_7"))
            assertTrue("Deletion barrier must survive process death", plain.contains(TripPassDeletion.KEY))
            TripPassStore.initStores(plain, secure)
            assertNull(TripPassStore.load(context, 7))
            assertFalse("Recovery must physically erase the deleted passport", secure.contains("pass_7"))
            assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
            assertTrue(secure.contains("pass_99"))
            assertFalse("A late save must not resurrect the finished trip",
                TripPassStore.save(context, pass(7, "late-response")))
            assertNull(TripPassStore.load(context, 7))
            assertFalse(secure.contains("pass_7"))
            assertEquals("unrelated", TripPassStore.load(context, 99)?.boardingCode)
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("deletion_phase_verified", phase)
            putInt("process_id", Process.myPid())
        })
        if (phase == "seed") Process.killProcess(Process.myPid())
    }
}
