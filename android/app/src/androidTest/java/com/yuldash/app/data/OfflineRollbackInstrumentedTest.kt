package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real files/Keystore; a separately labelled injected rollback fault, no network/provider. */
@RunWith(AndroidJUnit4::class)
class OfflineRollbackInstrumentedTest {
    private class Fault { var armed = false; var candidate = true; var refuseRollback = true }
    private fun failing(prefs: SharedPreferences, fault: Fault) = object : SharedPreferences by prefs {
        override fun edit(): SharedPreferences.Editor {
            if(fault.armed && !fault.candidate && fault.refuseRollback) error("synthetic rollback edit failure")
            val editor = prefs.edit()
            return object : SharedPreferences.Editor by editor {
                private var queue = false
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    queue = queue || key == "queue"
                    return this
                }
                override fun commit(): Boolean {
                    if(fault.armed && fault.candidate && queue) {
                        assertTrue(editor.commit()) // Deliberately model durable but unacknowledged candidate.
                        fault.candidate = false
                        return false
                    }
                    return editor.commit()
                }
            }
        }
    }

    private fun queue(encrypted: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = if(encrypted) "secure" else "plain"
        val plain = context.getSharedPreferences("qa_rollback_${suffix}_plain", Context.MODE_PRIVATE)
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        fun secure(): SharedPreferences = EncryptedSharedPreferences.create(context, "qa_rollback_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val original = if(encrypted) secure() else plain
        val fault = Fault()
        val originalWrapper = failing(original, fault)
        val scope = if(encrypted) plain else originalWrapper
        assertTrue(plain.edit().clear().commit())
        if(encrypted) assertTrue(original.edit().clear().commit())
        val previousUrl = ApiClient.testBaseUrl
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        try {
            Outbox.initStores(scope, if(encrypted) originalWrapper else null)
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(7, "synthetic confirmed A")))
            val confirmed = original.getString("queue", null)
            fault.armed = true
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(8, "synthetic rejected B")))
            assertEquals(0, Outbox.count(context, 8))
            assertFalse(Outbox.flush(context))
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(9, "synthetic blocked C")))
            val reopened = if(encrypted) secure() else original
            assertTrue("fault model must really retain the unacknowledged candidate in the real file-backed store",
                (0 until JSONArray(reopened.getString("queue", null)).length()).any {
                    JSONArray(reopened.getString("queue", null)).getJSONObject(it).getInt("booking_id") == 8
                })
            // New actual EncryptedSharedPreferences wrapper; scope remains the same plain object.
            Outbox.initStores(scope, if(encrypted) failing(reopened, fault) else null)
            assertEquals(1, Outbox.count(context, 7))
            assertEquals(0, Outbox.count(context, 8))
            assertFalse(Outbox.flush(context))
            fault.refuseRollback = false
            Outbox.initStores(scope, if(encrypted) failing(reopened, fault) else null)
            assertEquals(confirmed, reopened.getString("queue", null))
            assertEquals(1, Outbox.count(context, 7))
            assertEquals(0, Outbox.count(context, 8))
            assertTrue(Outbox.enqueue(context, Outbox.newMessage(9, "synthetic accepted after recovery")))
            println("QA020_REAL_STORAGE encrypted=$encrypted reopenedSameProcess=true rejectedHidden=true recovered=true api=${android.os.Build.VERSION.SDK_INT}")
        } finally {
            fault.refuseRollback = false
            assertTrue(original.edit().clear().commit())
            assertTrue(plain.edit().clear().commit())
            discardOfflineWriteRecovery(scope)
            Outbox.init(context)
            ApiClient.testBaseUrl = previousUrl
        }
    }

    @Test fun realPlainFileBlocksUntilRollbackIsConfirmed() = queue(false)
    @Test fun actualNewEncryptedWrapperDoesNotBypassThePendingGuard() = queue(true)

    @Test fun realInterruptedPlainCommitCanPersistDespiteFalse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("qa_interrupted_commit", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().clear().commit())
        val result = AtomicReference<Boolean?>()
        val error = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                Thread.currentThread().interrupt()
                result.set(prefs.edit().putString("queue", "synthetic interrupted write").commit())
            } catch(t: Throwable) { error.set(t) }
        }
        try {
            writer.start()
            writer.join(5000)
            assertFalse("writer did not complete", writer.isAlive)
            error.get()?.let { throw it }
            assertEquals(false, result.get())
            val ownFile = File(context.applicationInfo.dataDir, "shared_prefs/qa_interrupted_commit.xml")
            assertTrue("actual disk outcome must be checked separately from the cached map",
                ownFile.readText().contains("synthetic interrupted write"))
            println("QA020_REAL_COMMIT api=${android.os.Build.VERSION.SDK_INT} returnedFalse=true syntheticValueOnDisk=true")
        } finally { assertTrue(prefs.edit().clear().commit()) }
    }
}
