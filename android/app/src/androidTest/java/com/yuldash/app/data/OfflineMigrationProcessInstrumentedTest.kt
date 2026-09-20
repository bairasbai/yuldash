package com.yuldash.app.data

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only explicitly enabled, isolated QA preferences. Actual disk/Keystore and three processes. */
@RunWith(AndroidJUnit4::class)
class OfflineMigrationProcessInstrumentedTest {
    private fun failCleanup(real: SharedPreferences, key: String) = object : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                private var cleanup = false
                override fun putString(k: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(k, value); return this
                }
                override fun remove(k: String?): SharedPreferences.Editor {
                    if (k == key) cleanup = true
                    editor.remove(k); return this
                }
                override fun commit(): Boolean = if (cleanup) false else editor.commit()
                override fun apply() { commit() }
            }
        }
    }

    @Test fun runPhase() {
        val phase = InstrumentationRegistry.getArguments().getString("migrationPhase")
        assumeTrue("Explicit isolated migration audit only", phase in listOf("seed", "recover", "verify"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = context.getSharedPreferences("qa_migration_probe", 0)
        val passPlain = context.getSharedPreferences("qa_migration_pass_plain", 0)
        val queuePlain = context.getSharedPreferences("qa_migration_queue_plain", 0)
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        fun secure(name: String) = EncryptedSharedPreferences.create(context, name, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val passSecure = secure("qa_migration_pass_secure")
        val queueSecure = secure("qa_migration_queue_secure")
        fun pass(code: String) = TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", code))

        if (phase == "seed") {
            listOf(passPlain, queuePlain, passSecure, queueSecure).forEach { assertTrue(it.edit().clear().commit()) }
            assertTrue(probe.edit().clear().putInt("seed_pid", Process.myPid()).commit())
            TripPassStore.initStores(passPlain, null)
            Outbox.initStores(queuePlain, null)
            assertTrue(TripPassStore.save(context, pass("old")))
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "first")))
            TripPassStore.initStores(failCleanup(passPlain, "pass_7"), passSecure)
            Outbox.initStores(failCleanup(queuePlain, "queue"), queueSecure)
            assertTrue(passPlain.contains(OfflineMigration.JOURNAL))
            assertTrue(queuePlain.contains(OfflineMigration.JOURNAL))
            assertFalse(TripPassStore.save(context, pass("not-accepted")))
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "not-accepted")))
            assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
            assertEquals(1, Outbox.count(context, 7))
        } else {
            assertNotEquals(probe.getInt("seed_pid", -1), Process.myPid())
            TripPassStore.initStores(passPlain, passSecure)
            Outbox.initStores(queuePlain, queueSecure)
            assertFalse(passPlain.contains(OfflineMigration.JOURNAL))
            assertFalse(queuePlain.contains(OfflineMigration.JOURNAL))
            assertFalse(passPlain.contains("pass_7"))
            assertFalse(queuePlain.contains("queue"))
            if (phase == "recover") {
                assertEquals("old", TripPassStore.load(context, 7)?.boardingCode)
                assertEquals(1, Outbox.count(context, 7))
                assertTrue(probe.edit().putInt("recover_pid", Process.myPid()).commit())
                assertTrue(TripPassStore.save(context, pass("new")))
                assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "second")))
            } else {
                assertNotEquals(probe.getInt("recover_pid", -1), Process.myPid())
                assertEquals("new", TripPassStore.load(context, 7)?.boardingCode)
                assertEquals(2, Outbox.count(context, 7))
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("migration_phase_verified", phase)
            putInt("process_id", Process.myPid())
        })
        if (phase != "verify") Process.killProcess(Process.myPid())
    }
}
