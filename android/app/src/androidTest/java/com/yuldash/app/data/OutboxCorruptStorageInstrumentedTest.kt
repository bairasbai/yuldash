package com.yuldash.app.data

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real preference files/Keystore; synthetic queue only, no provider/network flow. */
@RunWith(AndroidJUnit4::class)
class OutboxCorruptStorageInstrumentedTest {
    private fun checkCorruptStore(encrypted: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = if (encrypted) "secure" else "plain"
        val plain = context.getSharedPreferences("qa_outbox_corrupt_${suffix}_plain", Context.MODE_PRIVATE)
        val secure = if (encrypted) {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context, "qa_outbox_corrupt_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } else null
        val selected = secure ?: plain
        assertTrue(plain.edit().clear().commit())
        secure?.let { assertTrue(it.edit().clear().commit()) }
        val corrupt = """[{"id":101,"booking_id":7,"kind":"message","payload":"synthetic retained message","created_at":1000},17]"""
        assertTrue(selected.edit().putString("queue", corrupt).commit())
        val previousBaseUrl = ApiClient.testBaseUrl
        ApiClient.testBaseUrl = "http://127.0.0.1:1"
        try {
            Outbox.initStores(plain, secure)
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "synthetic rejected replacement")))
            assertFalse(Outbox.flush(context))
            assertEquals(corrupt, selected.getString("queue", null))
            // Reopen real preferences and migration selection, not a process-death claim.
            Outbox.initStores(plain, secure)
            assertFalse(Outbox.enqueue(context, Outbox.newMessage(7, "synthetic retry")))
            assertEquals(corrupt, selected.getString("queue", null))
            println("OUTBOX_CORRUPT_STORE encrypted=$encrypted api=${Build.VERSION.SDK_INT} retained=true pid=${android.os.Process.myPid()}")
        } finally {
            assertTrue(selected.edit().clear().commit())
            Outbox.init(context)
            ApiClient.testBaseUrl = previousBaseUrl
        }
    }

    @Test fun realPlainPreferencesRetainCorruptedQueue() = checkCorruptStore(false)
    @Test fun realEncryptedPreferencesRetainCorruptedQueue() = checkCorruptStore(true)
}
